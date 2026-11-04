package com.chint.audiobook.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import com.chint.audiobook.MainActivity
import com.chint.audiobook.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * WS1 (1b): the playback MediaStyle notification (ID 1002) — the system media
 * card (shade, lockscreen, quick-settings carousel) with rewind / play-pause /
 * forward / next-chapter actions.
 *
 * media3's own MediaSessionService notification pipeline stayed silent on the
 * Redmi (on-device finding: playing sessions with a connected bootstrap
 * controller produced no DefaultMediaNotificationProvider notification), so
 * this manager — proven against the system carousel in the earlier media
 * round — remains the notification source, now owned by [PlaybackService].
 *
 * Action taps send MEDIA_BUTTON intents to PlaybackService, which media3's
 * session dispatches through the callback pipeline: REW/FF use the player's
 * 10 s seek increments, and NEXT maps to a chapter skip in
 * PlaybackService.SessionCallback.onMediaButtonEvent — the same path physical
 * headset buttons take.
 *
 * Deliberately framework Notification.Builder + framework MediaStyle (no new
 * androidx.media dependency): media3's session exposes the framework token
 * this style expects. MediaStyle notifications tied to an active session are
 * exempt from the POST_NOTIFICATIONS gate on 13+.
 */
class MediaNotificationManager(
  context: Context,
  private val controller: PlaybackController,
  private val mediaSessionToken: () -> android.media.session.MediaSession.Token?
) {

  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val notificationManager =
    appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  /** Posted only when this differs from the last posted key (no churn on offsets). */
  private data class PostKey(val isPlaying: Boolean, val bookTitle: String, val chapterTitle: String)

  init {
    createChannel()
    scope.launch {
      combine(
        controller.playerState,
        controller.nowPlaying
      ) { state, nowPlaying ->
        PostKey(state.isPlaying, nowPlaying.bookTitle, nowPlaying.chapterTitle)
      }
        .distinctUntilChanged()
        .collect { key ->
          if (key.bookTitle.isBlank()) {
            // Nothing loaded yet: never show a dead card.
            notificationManager.cancel(NOTIFICATION_ID)
          } else {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(key))
          }
        }
    }
  }

  fun shutdown() {
    scope.cancel()
    notificationManager.cancel(NOTIFICATION_ID)
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "Playback",
        NotificationManager.IMPORTANCE_LOW
      )
      notificationManager.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(key: PostKey): Notification {
    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(appContext, CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(appContext)
    }

    val style = Notification.MediaStyle()
      .setMediaSession(mediaSessionToken())
      // Legacy shade: rewind, play/pause, fast-forward in the compact row.
      .setShowActionsInCompactView(0, 1, 2)

    builder
      .setContentTitle(key.chapterTitle)
      .setContentText(key.bookTitle)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentIntent(contentIntent())
      .setOnlyAlertOnce(true)
      .setShowWhen(false)
      // Ongoing while playing so it cannot be swiped mid-listen; dismissible
      // while paused (a later state change simply re-posts if needed).
      .setOngoing(key.isPlaying)
      .setStyle(style)
      .addAction(
        action(
          KeyEvent.KEYCODE_MEDIA_REWIND,
          "Rewind 10 seconds",
          android.R.drawable.ic_media_rew
        )
      )
      .addAction(
        action(
          KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
          if (key.isPlaying) "Pause" else "Play",
          if (key.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
      )
      .addAction(
        action(
          KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
          "Forward 10 seconds",
          android.R.drawable.ic_media_ff
        )
      )
      .addAction(
        action(
          KeyEvent.KEYCODE_MEDIA_NEXT,
          "Next chapter",
          android.R.drawable.ic_media_next
        )
      )
    return builder.build()
  }

  /** Media-button action: PlaybackService routes it through the session pipeline. */
  private fun action(keyCode: Int, title: String, icon: Int): Notification.Action =
    Notification.Action.Builder(icon, title, mediaButtonPendingIntent(keyCode, keyCode))
      .build()

  private fun mediaButtonPendingIntent(keyCode: Int, requestCode: Int): PendingIntent =
    PendingIntent.getService(
      appContext,
      requestCode,
      Intent(appContext, PlaybackService::class.java).setAction(Intent.ACTION_MEDIA_BUTTON)
        .putExtra(
          Intent.EXTRA_KEY_EVENT,
          KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        ),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

  private fun contentIntent(): PendingIntent =
    PendingIntent.getActivity(
      appContext,
      0,
      Intent(appContext, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

  companion object {
    private const val CHANNEL_ID = "audiobook_playback_channel"
    const val NOTIFICATION_ID = 1002
  }
}
