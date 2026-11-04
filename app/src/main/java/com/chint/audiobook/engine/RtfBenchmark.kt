package com.chint.audiobook.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * B4: honest RTF self-test.
 *
 * - [RtfBenchmarkResult.coldLoadMs] now times a real sherpa-onnx OfflineTts
 *   cold re-load from disk (not a file-stat).
 * - [RtfBenchmarkResult.firstAudioMs] times the full first generate() call.
 * - [RtfBenchmarkResult.rtf] is wall-seconds / audio-seconds of that call.
 * - [RtfBenchmarkResult.passesGate] is the APP_PLAN gate rtf <= 0.5.
 *
 * If anything is wrong (pack missing, engine init failure) the result is
 * engineReady=false with a human-readable [RtfBenchmarkResult.reason]; the
 * benchmark never fabricates numbers.
 */
data class RtfBenchmarkResult(
  val coldLoadMs: Long,
  val firstAudioMs: Long,
  val audioDurationSec: Double,
  val wallDurationSec: Double,
  val rtf: Double,
  val passesGate: Boolean,
  val initOk: Boolean = false,
  val sampleRate: Int = 0,
  val numSpeakers: Int = 0,
  /** Why the engine is not ready; empty when [initOk] is true. */
  val reason: String = "",
  /** Documented sid map, same string as shown on the Model Manager screen. */
  val voiceMapping: String = TtsEngineManager.VOICE_MAPPING_LABEL
)

class RtfBenchmark(
  private val context: Context? = null,
  private val engineManager: TtsEngineManager
) {

  companion object {
    private const val TAG = "RtfBenchmark"
    private const val SAMPLE_TEXT =
      "The quick brown fox jumps over the lazy dog. " +
        "Supertonic renders this sentence on device in real time."
    const val RTF_GATE = 0.5
  }

  suspend fun runBenchmark(modelDir: File): RtfBenchmarkResult {
    return try {
      // 1) Real cold load: release any warm engine, then init from the on-disk pack.
      val coldLoadMs = engineManager.coldReload(modelDir)

      // 2) Full first generate() from the freshly-loaded engine.
      val generateStart = System.currentTimeMillis()
      val wavBytes = engineManager.generateAudioWav(text = SAMPLE_TEXT, voice = "F2", modelDir = modelDir)
      val firstAudioMs = (System.currentTimeMillis() - generateStart).coerceAtLeast(1L)

      // 3) Honest duration from actual output bytes (44-byte WAV header, 16-bit mono LE).
      val engineSampleRate = engineManager.getSampleRate().let { if (it in 1..192000) it else 44100 }
      val pcmByteCount = (wavBytes.size - 44).coerceAtLeast(0)
      val bytesPerSecond = engineSampleRate * 2 // mono, 16-bit
      val audioDurationSec = pcmByteCount / bytesPerSecond.toDouble()
      val wallDurationSec = firstAudioMs / 1000.0
      val rtf = if (audioDurationSec > 0) wallDurationSec / audioDurationSec else 0.0

      val result = RtfBenchmarkResult(
        coldLoadMs = coldLoadMs,
        firstAudioMs = firstAudioMs,
        audioDurationSec = audioDurationSec,
        wallDurationSec = wallDurationSec,
        rtf = rtf,
        passesGate = rtf <= RTF_GATE && initOk(),
        initOk = initOk(),
        sampleRate = engineSampleRate,
        numSpeakers = engineManager.getNumSpeakers()
      )
      Log.i(
        TAG,
        "benchmark: coldLoadMs=$coldLoadMs firstAudioMs=$firstAudioMs audio=${"%.2f".format(audioDurationSec)}s " +
          "wall=${"%.2f".format(wallDurationSec)}s rtf=${"%.3f".format(rtf)} gate=${result.passesGate}"
      )
      result
    } catch (e: Throwable) {
      Log.e(TAG, "benchmark failed", e)
      RtfBenchmarkResult(
        coldLoadMs = 0L,
        firstAudioMs = 0L,
        audioDurationSec = 0.0,
        wallDurationSec = 0.0,
        rtf = 0.0,
        passesGate = false,
        initOk = false,
        reason = e.message ?: (e::class.java.simpleName + " during engine initialisation")
      )
    }
  }

  private fun initOk(): Boolean = engineManager.isEngineLoaded() && engineManager.getSampleRate() > 0
}
