package com.chint.audiobook.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import com.chint.audiobook.MainActivity
import com.chint.audiobook.data.model.Segment
import com.chint.audiobook.data.repository.LibraryRepository
import com.chint.audiobook.data.repository.SettingsRepository
import com.chint.audiobook.service.AudiobookService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * WS1 (1b): the platform music-player home for playback.
 *
 * Owns the [PlaybackSession] and hosts a [MediaLibrarySession] (library base =
 * Android Auto-ready), so playback survives the UI: task-swipe keeps the
 * foreground service alive while playing, the framework renders the media
 * notification / quick-settings card / lockscreen controls from the session,
 * and media buttons (wired, Bluetooth, Wear) route through the session
 * callback. This replaces the hand-rolled MediaNotificationManager (A3) with
 * media3's default notification provider.
 *
 * Platform surfaces configured here:
 *  - session activity → MainActivity (card/notification tap reopens the app)
 *  - custom layout: rewind/forward ±10 s (player commands with 10 s
 *    increments set on the ExoPlayer) + previous/next chapter (custom session
 *    commands) — the notification, shade card and carousel all render these
 *  - media button preferences: the same chapter/seek actions for Wear OS and
 *    Assistant surfaces
 *  - onMediaButtonEvent: KEYCODE_MEDIA_NEXT/PREVIOUS (headset double/triple
 *    click) map to CHAPTER skips, not the default ±1 playlist item (±1
 *    sentence)
 *  - onPlaybackResumption: rebuild the last session from the persisted
 *    playback state after process death
 *
 * The synthesis [AudiobookService] is bound BY THIS SERVICE (not the UI), so
 * rendering also outlives the activity; the UI binds this service only.
 */
@AndroidEntryPoint
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaLibraryService() {

  @Inject lateinit var libraryRepository: LibraryRepository
  @Inject lateinit var settingsRepository: SettingsRepository
  private lateinit var playbackSession: PlaybackSession

  private var librarySession: MediaLibrarySession? = null
  private var synthesisService: AudiobookService? = null
  private var synthesisBound = false
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  /** Bootstrap controller (see onCreate) — released in onDestroy. */
  private var housekeepingController: ListenableFuture<MediaController>? = null

  /** 1b: the media card (see onCreate) — cancelled in onDestroy. */
  private var mediaNotificationManager: MediaNotificationManager? = null

  // Q6: shake-to-resume while a sleep timer is armed.
  private var shakeDetector: ShakeDetector? = null
  private var sensorManager: android.hardware.SensorManager? = null
  private var shakeListenerRegistered = false
  private val shakeListener = object : android.hardware.SensorEventListener {
    override fun onSensorChanged(event: android.hardware.SensorEvent) {
      val fired = shakeDetector?.onSample(
        System.currentTimeMillis(), event.values[0], event.values[1], event.values[2]
      ) ?: false
      if (fired) playbackSession.resumeFromShake()
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
  }

  /** 1a's gateway, now service-to-service (no UI binding timing involved). */
  private val synthesisGateway = object : SynthesisGateway {
    override fun startSynthesis(
      bookDir: File,
      bookSlug: String,
      chapterIndex: Int,
      segments: List<Segment>,
      voice: String,
      dialogueVoice: String?,
      language: String
    ) {
      synthesisService?.startSynthesis(bookDir, bookSlug, chapterIndex, segments, voice, dialogueVoice, language)
    }

    override fun cancelSynthesis() {
      synthesisService?.cancelSynthesis()
    }

    override fun startPreloadSynthesis(
      bookDir: File,
      bookSlug: String,
      chapterIndex: Int,
      segments: List<Segment>,
      voice: String,
      dialogueVoice: String?,
      language: String
    ) {
      synthesisService?.startPreloadSynthesis(bookDir, bookSlug, chapterIndex, segments, voice, dialogueVoice, language)
    }

    override fun cancelPreload() {
      synthesisService?.cancelPreload()
    }
  }

  private val synthesisConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      synthesisService = (service as? AudiobookService.LocalBinder)?.getService()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      synthesisService = null
    }
  }

  /** Same-process binder: the ReaderViewModel talks to the session directly. */
  inner class LocalBinder : android.os.Binder() {
    fun getService(): PlaybackService = this@PlaybackService
    fun getSession(): PlaybackSession = playbackSession
  }

  private val binder = LocalBinder()

  override fun onBind(intent: Intent?): IBinder? = super.onBind(intent) ?: binder

  override fun onCreate() {
    super.onCreate()
    libraryRepository = LibraryRepository(this)
    settingsRepository = SettingsRepository(this)

    bindService(
      Intent(this, AudiobookService::class.java),
      synthesisConnection,
      Context.BIND_AUTO_CREATE
    )
    synthesisBound = true

    playbackSession = PlaybackSession(
      context = this,
      libraryRepository = libraryRepository,
      settingsRepository = settingsRepository,
      synthesis = synthesisGateway
    )

    librarySession = MediaLibrarySession.Builder(
      this,
      playbackSession.playbackController.exoPlayer,
      SessionCallback()
    )
      .setSessionActivity(sessionActivity())
      .setCustomLayout(commandButtons())
      .setMediaButtonPreferences(commandButtons())
      .build()

    // media3's notification/foreground machinery only engages once a
    // CONTROLLER connects (onGetSession → addSession → playback changes
    // dispatch onUpdateNotification). Our UI talks to the session through the
    // local binder, so nothing would ever connect — this housekeeping
    // controller bootstraps the notification pipeline for our own session
    // (on-device finding: playing with zero connected controllers produced no
    // media notification and no foreground promotion).
    housekeepingController =
      MediaController.Builder(this, librarySession!!.token).buildAsync()

    // On-device finding: media3's default notification provider stayed silent
    // on this device even with a connected controller — so the proven A3-era
    // MediaStyle notification (ID 1002) remains the media card, owned by the
    // service now. Its action taps arrive here as MEDIA_BUTTON intents and
    // flow through the session callback (10 s seeks, NEXT = chapter skip).
    // Q6: shake-to-resume — listener active ONLY while a sleep timer is armed
    // (the timer paused playback; a shake cancels it and resumes). Zero sensor
    // cost during normal listening.
    shakeDetector = ShakeDetector()
    sensorManager = getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
    serviceScope.launch {
      playbackSession.sleepTimerEndAt.collect { armed ->
        val active = armed != 0L
        val sensor = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        if (active && sensor != null && !shakeListenerRegistered) {
          shakeListenerRegistered = sensorManager!!.registerListener(shakeListener, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
        } else if (!active && shakeListenerRegistered) {
          sensorManager?.unregisterListener(shakeListener)
          shakeListenerRegistered = false
        }
      }
    }

    mediaNotificationManager = MediaNotificationManager(
      context = this,
      controller = playbackSession.playbackController,
      mediaSessionToken = { librarySession?.platformToken }
    )
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
    librarySession

  /**
   * Default media3 semantics, made explicit: while actually playing the
   * service stays foreground; otherwise task removal may stop it.
   */
  override fun onTaskRemoved(rootIntent: Intent?) {
    val player = librarySession?.player
    if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
      stopSelf()
    }
  }

  override fun onDestroy() {
    if (shakeListenerRegistered) {
      sensorManager?.unregisterListener(shakeListener)
      shakeListenerRegistered = false
    }
    serviceScope.cancel()
    playbackSession.shutdown()
    mediaNotificationManager?.shutdown()
    mediaNotificationManager = null
    housekeepingController?.let { future ->
      runCatching {
        if (future.isDone) future.get().release()
      }
    }
    housekeepingController = null
    librarySession?.release()
    librarySession = null
    if (synthesisBound) {
      runCatching { unbindService(synthesisConnection) }
      synthesisBound = false
    }
    super.onDestroy()
  }

  private fun sessionActivity(): PendingIntent =
    PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

  /**
   * The action set shown on every surface. Player-command buttons (±10 s) use
   * the standard commands the system knows how to render; chapter skips are
   * custom session commands resolved in [SessionCallback.onCustomCommand].
   */
  private fun commandButtons(): List<CommandButton> = listOf(
    CommandButton.Builder()
      .setDisplayName("Previous chapter")
      .setSessionCommand(chapterCommand(ACTION_PREVIOUS_CHAPTER))
      .setIconResId(android.R.drawable.ic_media_previous)
      .build(),
    CommandButton.Builder()
      .setDisplayName("Rewind 10 seconds")
      .setPlayerCommand(Player.COMMAND_SEEK_BACK)
      .setIconResId(android.R.drawable.ic_media_rew)
      .build(),
    CommandButton.Builder()
      .setDisplayName("Forward 10 seconds")
      .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
      .setIconResId(android.R.drawable.ic_media_ff)
      .build(),
    CommandButton.Builder()
      .setDisplayName("Next chapter")
      .setSessionCommand(chapterCommand(ACTION_NEXT_CHAPTER))
      .setIconResId(android.R.drawable.ic_media_next)
      .build()
  )

  private fun chapterCommand(action: String) = SessionCommand(action, Bundle.EMPTY)

  private inner class SessionCallback : MediaLibrarySession.Callback {

    override fun onConnect(
      session: MediaSession,
      controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
      val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
        .buildUpon()
        .add(chapterCommand(ACTION_NEXT_CHAPTER))
        .add(chapterCommand(ACTION_PREVIOUS_CHAPTER))
        .build()
      return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
        .setAvailableSessionCommands(sessionCommands)
        .build()
    }

    override fun onCustomCommand(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
      customCommand: SessionCommand,
      args: Bundle
    ): ListenableFuture<SessionResult> {
      when (customCommand.customAction) {
        ACTION_NEXT_CHAPTER -> playbackSession.skipToNextChapter()
        ACTION_PREVIOUS_CHAPTER -> playbackSession.skipToPreviousChapter()
      }
      return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    override fun onGetLibraryRoot(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
      val rootItem = MediaItem.Builder()
        .setMediaId("ROOT")
        .setMediaMetadata(
          MediaMetadata.Builder()
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setTitle("Rustle Audiobooks")
            .build()
        )
        .build()
      return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
    }

    override fun onGetChildren(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      parentId: String,
      page: Int,
      pageSize: Int,
      params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
      val books = libraryRepository.booksFlow.value
      val items = books.map { book ->
        MediaItem.Builder()
          .setMediaId(book.slug)
          .setMediaMetadata(
            MediaMetadata.Builder()
              .setIsBrowsable(false)
              .setIsPlayable(true)
              .setTitle(book.title)
              .setArtist(book.author)
              .build()
          )
          .build()
      }
      return Futures.immediateFuture(
        LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
      )
    }

    override fun onSetMediaItems(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
      mediaItems: MutableList<MediaItem>,
      startIndex: Int,
      startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
      val requestedItem = mediaItems.firstOrNull()
      if (requestedItem != null) {
        val slug = requestedItem.mediaId
        serviceScope.launch {
          playbackSession.loadBookAndChapter(slug, 1)
          playbackSession.play()
        }
      }
      return Futures.immediateFuture(
        MediaSession.MediaItemsWithStartPosition(
          mediaItems,
          startIndex,
          startPositionMs
        )
      )
    }

    /**
     * Headset/Bluetooth/Wear media keys: NEXT/PREVIOUS skip CHAPTERS. The
     * default would seek ±1 playlist item — here ±1 sentence. Everything
     * else (play/pause, stop) falls through to default handling.
     */
    override fun onMediaButtonEvent(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
      mediaButtonEvent: Intent
    ): Boolean {
      if (Intent.ACTION_MEDIA_BUTTON == mediaButtonEvent.action) {
        @Suppress("DEPRECATION")
        val keyEvent = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
        if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
          when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
              playbackSession.skipToNextChapter()
              return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
              playbackSession.skipToPreviousChapter()
              return true
            }
          }
        }
      }
      return super.onMediaButtonEvent(session, controller, mediaButtonEvent)
    }

    /**
     * WS1 (1c): playback resumption after process death — the system's
     * "resume" card (quick settings / notification) lands here. The persisted
     * playback state names the book+chapter; the async load rebuilds the real
     * playlist (file-based, seeded from cached WAVs). The future completes
     * immediately with an empty item list so the framework proceeds; the
     * load's own setMediaItems becomes the live playlist a moment later.
     */
    override fun onPlaybackResumption(
      session: MediaSession,
      controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
      serviceScope.launch {
        val saved = settingsRepository.playbackState.first()
        if (saved.bookSlug.isNotBlank()) {
          playbackSession.loadBookAndChapter(saved.bookSlug, saved.chapterIndex)
        }
      }
      return Futures.immediateFuture(
        MediaSession.MediaItemsWithStartPosition(
          /* mediaItems = */ emptyList(),
          /* startIndex = */ 0,
          /* startPositionMs = */ C.TIME_UNSET
        )
      )
    }
  }

  companion object {
    const val ACTION_NEXT_CHAPTER = "com.chint.audiobook.cmd.NEXT_CHAPTER"
    const val ACTION_PREVIOUS_CHAPTER = "com.chint.audiobook.cmd.PREVIOUS_CHAPTER"
  }
}
