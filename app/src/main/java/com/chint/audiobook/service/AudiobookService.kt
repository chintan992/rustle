package com.chint.audiobook.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chint.audiobook.R
import com.chint.audiobook.data.model.LedgerEvent
import com.chint.audiobook.data.model.Segment
import com.chint.audiobook.data.model.TtsEngine
import com.chint.audiobook.data.repository.LedgerRepository
import com.chint.audiobook.engine.KokoroModelManager
import com.chint.audiobook.engine.KokoroTtsEngineManager
import com.chint.audiobook.engine.ModelManager
import com.chint.audiobook.engine.SegmentVoices
import com.chint.audiobook.engine.TtsEngineManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AudiobookService : Service() {

  private val binder = LocalBinder()
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
  
  @Inject lateinit var ledgerRepository: LedgerRepository
  @Inject lateinit var ttsEngineManager: TtsEngineManager
  @Inject lateinit var modelManager: ModelManager
  @Inject lateinit var kokoroTtsEngineManager: KokoroTtsEngineManager
  @Inject lateinit var kokoroModelManager: KokoroModelManager
  private var synthesisJob: Job? = null
  // Cache/prep round (B3): separate slot for background chapter preparation.
  private var preloadJob: Job? = null

  inner class LocalBinder : Binder() {
    fun getService(): AudiobookService = this@AudiobookService
  }

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    // Polish round: no permanent foreground promotion at creation — the media
    // card (PlaybackService) is the only ongoing notification while idle.
    // This service surfaces its own notification only WHILE synthesizing
    // (promote/demote below), so the shade shows one card, not two.
  }

  override fun onBind(intent: Intent?): IBinder = binder

  /**
   * Polish round: foreground only while a render is actually running.
   * Promotion is best-effort — under Android 12+ background-FGS restrictions
   * (e.g. a preload firing while the app is swiped away and the media service
   * is not foreground) startForeground can throw ForegroundServiceStartNotAllowed;
   * synthesis then continues un-promoted (bound process + wake lock) rather
   * than crashing.
   */
  private var synthesisForeground = false

  private fun promoteToForeground() {
    if (synthesisForeground) return
    runCatching {
      // Ensure the started state before going foreground (a bound-only
      // service promoting itself is not guaranteed on all versions).
      startService(Intent(this, AudiobookService::class.java))
      startForeground(NOTIFICATION_ID, createNotification("Rendering narration…"))
      synthesisForeground = true
    }.onFailure { Log.w(TAG, "foreground promote skipped: ${it.message}") }
  }

  /** Drops the notification once neither the active render nor a preload is running. */
  private fun demoteFromForeground() {
    if (synthesisJob?.isActive == true || preloadJob?.isActive == true) return
    if (synthesisForeground) {
      synthesisForeground = false
      stopForeground(STOP_FOREGROUND_REMOVE)
    }
    // Also clears a straggler posted while promotion was skipped.
    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
  }

  /**
   * Media round (A5): stop any in-flight render without starting a new one.
   * Library book deletion calls this FIRST — the render loop mkdirs its way
   * back into books/<slug>/audio/, so a deletion with a live render would
   * resurrect orphan WAV directories after the recursive delete.
   */
  fun cancelSynthesis() {
    synthesisJob?.cancel()
  }

  fun startSynthesis(
    bookDir: File,
    bookSlug: String,
    chapterIndex: Int,
    segments: List<Segment>,
    voice: String,
    dialogueVoice: String? = null,
    language: String = "en",
    engine: TtsEngine = TtsEngine.SUPERTONIC
  ) {
    // Round 9 (Task 4): marker for start-audio latency measurement (open book /
    // chapter tap -> synthesis start -> engine init -> first render_done).
    Log.i(
      TAG,
      "startSynthesis book=$bookSlug chapter=$chapterIndex segments=${segments.size} voice=$voice lang=$language"
    )
    promoteToForeground()
    synthesisJob?.cancel()
    synthesisJob = serviceScope.launch {
      // Round 8: one wake lock per synthesis run. A superseded run's finally must
      // not release the lock its successor (chapter advance, voice re-target) just
      // acquired; chapter auto-advance makes back-to-back runs the common case.
      val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
      val jobWakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "AudiobookApp::SynthesisWakeLock"
      )
      jobWakeLock.acquire(30 * 60 * 1000L /* 30 min */)
      try {
        val selectedEngine = com.chint.audiobook.engine.TtsEngineSelection.forLanguage(engine, language)
        val modelDir = modelDirFor(selectedEngine)

        val chapterAudioDir = SegmentVoices.chapterAudioDir(bookDir, chapterIndex, selectedEngine)
        val globalCacheAudioDir = SegmentVoices.globalCacheAudioDir(bookDir, selectedEngine)

        for (segment in segments) {
          // Round 8: segment-granular cancellation — a superseding startSynthesis
          // (chapter advance, voice re-target) stops this run at the next sentence
          // boundary instead of rendering the whole superseded chapter first.
          ensureActive()

          // Multi-voice: skip is voice-aware — a segment only counts as rendered
          // when the WAV for its CLASSIFIED role + current voices exists.
          val segVoice = SegmentVoices.voiceFor(segment.text, voice, dialogueVoice)
          val existing = SegmentVoices.findWav(
            chapterAudioDir, globalCacheAudioDir, segment.hash, segment.text, dialogueVoice,
            selectedEngine, voice
          )
          if (existing != null) {
            continue
          }

          renderSegment(
            bookDir, bookSlug, chapterIndex, segment, segVoice, voice, dialogueVoice,
            modelDir, language, selectedEngine
          ) {
            "Rendering sentence ${segment.i}/${segments.size}"
          }
        }
        updateNotification("Synthesis Complete")
      } catch (ce: CancellationException) {
        // BG0: a deleted book cancels mid-render — its ledger is gone too, so
        // the render_interrupted append itself must not resurrect the dir.
        if (bookDir.exists()) {
          ledgerRepository.appendEvent(
            bookDir,
            LedgerEvent(
              event = "render_interrupted",
              bookSlug = bookSlug,
              chapterIndex = chapterIndex,
              reason = "Synthesis cancelled"
            )
          )
        }
        throw ce
      } catch (e: Exception) {
        if (bookDir.exists()) {
          ledgerRepository.appendEvent(
            bookDir,
            LedgerEvent(
              event = "render_failed",
              bookSlug = bookSlug,
              chapterIndex = chapterIndex,
              reason = e.message ?: "Unknown synthesis error"
            )
          )
        }
        updateNotification("Synthesis Error: ${e.message}")
      } finally {
        if (jobWakeLock.isHeld) {
          jobWakeLock.release()
        }
      }
    }
    // Polish round: demote when idle — a chained successor (chapter advance,
    // voice re-target) cancels this job and launches its own, and the demote
    // check sees that live job, so the notification survives the chain.
    synthesisJob?.invokeOnCompletion { demoteFromForeground() }
  }

  /**
   * Cache/prep round (B3): shared per-segment render used by the active-chapter
   * path and the preload path — render_start ledger event, engine call, atomic
   * WAV write, render_done with RTF, round-9 logcat marker. [notificationLabel]
   * supplies the progress text ("Rendering sentence x/y" vs "Preparing chNNNN").
   *
   * BG0 (ghost-book fix): job cancellation is segment-granular — a segment
   * already inside the engine call finishes and would write its WAV + ledger
   * lines AFTER the Library's recursive delete, mkdir-ing the deleted tree
   * back. Both writes are guarded by a bookDir existence check; a vanished
   * dir cancels the run (CancellationException — no render_failed event for a
   * book that no longer exists).
   */
  private suspend fun renderSegment(
    bookDir: File,
    bookSlug: String,
    chapterIndex: Int,
    segment: Segment,
    voice: String,
    narrationVoice: String,
    dialogueVoice: String?,
    modelDir: File,
    language: String = "en",
    engine: TtsEngine = TtsEngine.SUPERTONIC,
    notificationLabel: () -> String
  ) {
    if (!bookDir.exists()) {
      throw CancellationException("book $bookSlug deleted before render")
    }
    updateNotification(notificationLabel())

    // Log render_start
    ledgerRepository.appendEvent(
      bookDir,
      LedgerEvent(
        event = "render_start",
        bookSlug = bookSlug,
        chapterIndex = chapterIndex,
        segmentHash = segment.hash,
        voice = voice
      )
    )

    val startTime = System.currentTimeMillis()
    val wavBytes = when (engine) {
      TtsEngine.SUPERTONIC -> ttsEngineManager.generateAudioWav(segment.text, voice, modelDir, language)
      TtsEngine.KOKORO -> kokoroTtsEngineManager.generateAudioWav(segment.text, voice, modelDir)
    }
    val endTime = System.currentTimeMillis()

    // BG0: the engine call above is the long window — the delete most likely
    // landed inside it. Do not write anything for a book that is gone.
    if (!bookDir.exists()) {
      throw CancellationException("book $bookSlug deleted during render")
    }

    val wallDurationSec = (endTime - startTime) / 1000.0
    val sampleRate = if (engine == TtsEngine.KOKORO) {
      KokoroTtsEngineManager.EXPECTED_SAMPLE_RATE
    } else {
      TtsEngineManager.EXPECTED_SAMPLE_RATE
    }
    val audioDurationSec = (wavBytes.size - 44).coerceAtLeast(0) / (sampleRate * 2.0)
    val rtf = if (audioDurationSec > 0) wallDurationSec / audioDurationSec else 0.0

    // Atomic write: audio/chNNNN/<name>.wav.tmp -> rename. Multi-voice: the
    // filename comes from SegmentVoices — dialogue segments embed their voice
    // (<hash>~<voice>.wav), narration keeps the original <hash>.wav contract.
    val isDialogue = SegmentVoices.isDialogue(segment.text)
    val wavName = SegmentVoices.wavFileName(
      segment.hash, isDialogue, dialogueVoice, engine, narrationVoice
    )
    val chapterAudioDir = SegmentVoices.chapterAudioDir(bookDir, chapterIndex, engine)
    chapterAudioDir.mkdirs()

    val tmpFile = File(chapterAudioDir, "$wavName.tmp")
    val finalFile = File(chapterAudioDir, wavName)

    tmpFile.writeBytes(wavBytes)
    val renamed = tmpFile.renameTo(finalFile)
    if (!renamed) {
      tmpFile.copyTo(finalFile, overwrite = true)
      tmpFile.delete()
    }

    // Log render_done
    ledgerRepository.appendEvent(
      bookDir,
      LedgerEvent(
        event = "render_done",
        bookSlug = bookSlug,
        chapterIndex = chapterIndex,
        segmentHash = segment.hash,
        voice = voice,
        audioSeconds = audioDurationSec,
        wallSeconds = wallDurationSec,
        rtf = rtf
      )
    )

    // Round 9 (Task 4): per-chunk logcat marker for latency + RTF audits.
    Log.i(
      TAG,
      "renderDone book=$bookSlug chapter=$chapterIndex rtf=${
        "%.3f".format(rtf)
      } wall=${"%.2f".format(wallDurationSec)}s audio=${"%.2f".format(audioDurationSec)}s"
    )
  }

  /**
   * Cache/prep round (B3): background preparation of an UPCOMING chapter.
   * Shares the service's limitedParallelism(1) scope, so preloads serialize
   * BEHIND active-chapter synthesis — the active chapter always wins the
   * engine and preloads only run while it is otherwise idle. Already-rendered
   * segments (ledger or on disk — covers the voice-shared global cache) are
   * skipped, so re-requesting a finished chapter is a no-op. Failures are
   * logged and swallowed: preload is best-effort by definition.
   */
  fun startPreloadSynthesis(
    bookDir: File,
    bookSlug: String,
    chapterIndex: Int,
    segments: List<Segment>,
    voice: String,
    dialogueVoice: String? = null,
    language: String = "en",
    engine: TtsEngine = TtsEngine.SUPERTONIC
  ) {
    promoteToForeground()
    preloadJob?.cancel()
    preloadJob = serviceScope.launch {
      try {
        val selectedEngine = com.chint.audiobook.engine.TtsEngineSelection.forLanguage(engine, language)
        val modelDir = modelDirFor(selectedEngine)
        val chapterAudioDir = SegmentVoices.chapterAudioDir(bookDir, chapterIndex, selectedEngine)
        val globalCacheAudioDir = SegmentVoices.globalCacheAudioDir(bookDir, selectedEngine)
        // Multi-voice: a segment is missing only when ITS voice's WAV is absent.
        val missing = segments.filter {
          SegmentVoices.findWav(
            chapterAudioDir, globalCacheAudioDir, it.hash, it.text, dialogueVoice,
            selectedEngine, voice
          ) == null
        }
        if (missing.isEmpty()) return@launch
        Log.i(TAG, "preloadStart book=$bookSlug chapter=$chapterIndex missing=${missing.size} lang=$language")
        for (segment in missing) {
          ensureActive()
          // Another path (voice re-target, earlier preload) may have written it
          // since the snapshot — voice-aware probe before spending the engine.
          val existing = SegmentVoices.findWav(
            chapterAudioDir, globalCacheAudioDir, segment.hash, segment.text, dialogueVoice,
            selectedEngine, voice
          )
          if (existing != null) continue
          val segVoice = SegmentVoices.voiceFor(segment.text, voice, dialogueVoice)
          renderSegment(
            bookDir, bookSlug, chapterIndex, segment, segVoice, voice, dialogueVoice,
            modelDir, language, selectedEngine
          ) {
            "Preparing ch${"%04d".format(chapterIndex)} (${missing.indexOf(segment) + 1}/${missing.size})"
          }
        }
        Log.i(TAG, "preloadDone book=$bookSlug chapter=$chapterIndex")
        // Compress completed preload chapter into AAC LC .m4a to save ~90% disk space
        val distinctSegments = segments.distinctBy { it.hash }
        val textByHash = distinctSegments.associate { it.hash to it.text }
        val durations = distinctSegments.associate { seg ->
          val wav = SegmentVoices.findWav(
            chapterAudioDir, globalCacheAudioDir, seg.hash, seg.text, dialogueVoice,
            selectedEngine, voice
          )
          seg.hash to (wav?.let { com.chint.audiobook.playback.WavDuration.durationMs(it) } ?: 0L)
        }
        val compressed = com.chint.audiobook.engine.AudioCompressor.compressChapter(
          chapterDir = chapterAudioDir,
          chapterIndex = chapterIndex,
          hashes = distinctSegments.map { it.hash },
          durationsByHash = durations,
          sampleRate = if (selectedEngine == TtsEngine.KOKORO) {
            KokoroTtsEngineManager.EXPECTED_SAMPLE_RATE
          } else {
            TtsEngineManager.EXPECTED_SAMPLE_RATE
          },
          engine = selectedEngine,
          narrationVoice = voice,
          dialogueVoice = dialogueVoice,
          wavResolver = { hash ->
            SegmentVoices.findWav(
              chapterAudioDir, globalCacheAudioDir, hash, textByHash[hash] ?: "",
              dialogueVoice, selectedEngine, voice
            )
          }
        )
        Log.i(TAG, "compressChapter returned $compressed for ch${"%04d".format(chapterIndex)}")
        if (compressed) {
          val pruned = com.chint.audiobook.engine.AudioCompressor.pruneRawWavs(chapterAudioDir)
          Log.i(TAG, "compressed ch${"%04d".format(chapterIndex)}: pruned $pruned raw WAVs")
        }
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: Exception) {
        Log.w(TAG, "preloadFailed book=$bookSlug chapter=$chapterIndex: ${e.message}")
      }
    }
    preloadJob?.invokeOnCompletion { demoteFromForeground() }
  }

  private fun modelDirFor(engine: TtsEngine): File =
    if (engine == TtsEngine.KOKORO) kokoroModelManager.getModelDir() else modelManager.getModelDir()

  /** Cache/prep round (B3): stop the in-flight preload (conditions lost / chapter changed). */
  fun cancelPreload() {
    preloadJob?.cancel()
  }

  override fun onDestroy() {
    serviceScope.cancel()
    super.onDestroy()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "Audiobook Playback & Synthesis",
        NotificationManager.IMPORTANCE_LOW
      )
      val manager = getSystemService(NotificationManager::class.java)
      manager.createNotificationChannel(channel)
    }
  }

  private fun createNotification(contentText: String): Notification {
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Audiobook")
      .setContentText(contentText)
      .setSmallIcon(R.drawable.ic_notification)
      .setOngoing(true)
      .build()
  }

  private fun updateNotification(contentText: String) {
    val notification = createNotification(contentText)
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.notify(NOTIFICATION_ID, notification)
  }

  companion object {
    private const val TAG = "AudiobookService"
    private const val CHANNEL_ID = "audiobook_service_channel"
    private const val NOTIFICATION_ID = 1001
  }
}
