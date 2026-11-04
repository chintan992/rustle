package com.chint.audiobook.engine

import android.content.Context
import android.util.Log
import com.chint.audiobook.core.LanguageCatalog
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.roundToInt

/**
 * Real Supertonic-3 engine on sherpa-onnx (official Android AAR, app/libs/sherpa-onnx-1.13.8.aar).
 *
 * Config mirrors the sherpa-onnx project's own Kotlin example
 * (kotlin-api-examples/test_supertonic_tts.kt) for the int8 pack
 * sherpa-onnx-supertonic-3-tts-int8-2026-05-11:
 * duration predictor / text encoder / vector estimator / vocoder / tts.json /
 * unicode_indexer.bin / voice.bin.
 *
 * Voice mapping (sid): voice.bin is built by sherpa-onnx scripts/supertonic/
 * generate_voices_bin.py via `sorted(input_dir.glob("*.json"))`, i.e. the
 * upstream voice_styles files in alphabetical order: F1,F2,F3,F4,F5,M1..M5 ->
 * sid 0..9. Verified at runtime by numSpeakers()==10.
 *
 * Plain [OfflineTts.generate] defaults lang to "en" inside sherpa-onnx's JNI
 * (csrc/offline-tts-supertonic-impl.cc: `GetExtraString("lang", "en")`), so no
 * extra config is needed for this English app.
 */
class TtsEngineManager(private val context: Context? = null) {

  companion object {
    private const val TAG = "TtsEngineManager"

    /** Expected output sample rate of the Supertonic-3 pack (tts.json: ae.sample_rate). */
    const val EXPECTED_SAMPLE_RATE = 44100
    const val EXPECTED_NUM_SPEAKERS = 10

    /** Engine synthesis speed; playback speed is applied player-side by ExoPlayer. */
    private const val ENGINE_SPEED = 1.0f

    /** Public doc of the sid mapping (see class KDoc), shown on the Model screen. */
    const val VOICE_MAPPING_LABEL = "F1-F5 -> sid 0-4, M1-M5 -> sid 5-9 (voice.bin order)"
  }

  private val mutex = Mutex()
  private val numChannels = 1
  private val bitsPerSample = 16

  @Volatile
  private var tts: OfflineTts? = null

  private var sampleRate = EXPECTED_SAMPLE_RATE
  private var numSpeakers = EXPECTED_NUM_SPEAKERS
  private var lastLoadDurationMs = 0L

  // ------------------------------------------------------------------ public shape (unchanged)

  suspend fun generateAudioWav(
    text: String,
    voice: String,
    modelDir: File,
    language: String = "en"
  ): ByteArray = mutex.withLock {
    val engine = requireEngine(modelDir)
    val sid = voiceToSid(voice, numSpeakers)
    val normLang = LanguageCatalog.normalizeOrNull(language) ?: "en"
    val config = GenerationConfig(
      sid = sid,
      speed = ENGINE_SPEED,
      extra = mapOf("lang" to normLang)
    )
    val generated = engine.generateWithConfig(text, config)
    if (generated.samples.isEmpty()) {
      throw IllegalStateException("sherpa-onnx generate() produced no samples for text of ${text.length} chars")
    }
    sampleRate = generated.sampleRate
    encodePcm16Le(generated.samples).let { pcm -> createWavHeaderAndData(pcm) }
  }

  fun getEngineLoadTimeMs(): Long = lastLoadDurationMs

  fun isEngineLoaded(): Boolean = tts != null

  // ------------------------------------------------------------------ additive, benchmark/readiness API

  /** Current engine sample rate after load (0 when not loaded). */
  fun getSampleRate(): Int = if (tts != null) sampleRate else 0

  /** Current engine speaker count after load (0 when not loaded). */
  fun getNumSpeakers(): Int = if (tts != null) numSpeakers else 0

  /**
   * Cold-load measurement for the RTF benchmark: releases any warm instance and
   * runs the true OfflineTts init from disk, returning the wall time in ms.
   */
  suspend fun coldReload(modelDir: File): Long = mutex.withLock {
    releaseLocked()
    initializeEngineLocked(modelDir)
    lastLoadDurationMs
  }

  /** Frees the native engine (e.g. on memory pressure / shutdown). */
  fun release() {
    // Not behind the mutex by design: called from onDestroy paths only.
    releaseLocked()
  }

  // ------------------------------------------------------------------ engine plumbing

  private fun requireEngine(modelDir: File): OfflineTts {
    tts?.let { return it }
    return initializeEngineLocked(modelDir)
  }

  /** Must be called while holding [mutex]. */
  private fun initializeEngineLocked(modelDir: File): OfflineTts {
    val missing = ModelManager.REQUIRED_FILES.filter { f ->
      val file = File(modelDir, f)
      !file.exists() || file.length() == 0L
    }
    if (missing.isNotEmpty()) {
      throw IllegalStateException(
        "Supertonic-3 pack incomplete in ${modelDir.path}: missing $missing. " +
          "Download the pack in the Model Manager before synthesis."
      )
    }

    val loadStart = System.currentTimeMillis()
    val cfg = OfflineTtsConfig(
      model = OfflineTtsModelConfig(
        supertonic = OfflineTtsSupertonicModelConfig(
          durationPredictor = File(modelDir, "duration_predictor.int8.onnx").absolutePath,
          textEncoder = File(modelDir, "text_encoder.int8.onnx").absolutePath,
          vectorEstimator = File(modelDir, "vector_estimator.int8.onnx").absolutePath,
          vocoder = File(modelDir, "vocoder.int8.onnx").absolutePath,
          ttsJson = File(modelDir, "tts.json").absolutePath,
          unicodeIndexer = File(modelDir, "unicode_indexer.bin").absolutePath,
          voiceStyle = File(modelDir, "voice.bin").absolutePath
        ),
        numThreads = 2,
        debug = false,
        provider = "cpu"
      )
    )

    val engine = OfflineTts(config = cfg)
    numSpeakers = engine.numSpeakers()
    sampleRate = engine.sampleRate()

    lastLoadDurationMs = (System.currentTimeMillis() - loadStart).coerceAtLeast(1L)

    Log.i(
      TAG,
      "sherpa-onnx OfflineTts initialized: sampleRate=$sampleRate numSpeakers=$numSpeakers " +
        "provider=cpu numThreads=2 loadMs=$lastLoadDurationMs modelDir=${modelDir.path}"
    )
    if (sampleRate != EXPECTED_SAMPLE_RATE) {
      Log.w(TAG, "sampleRate $sampleRate differs from expected $EXPECTED_SAMPLE_RATE (tts.json)")
    }
    if (numSpeakers != EXPECTED_NUM_SPEAKERS) {
      Log.w(TAG, "numSpeakers $numSpeakers differs from expected $EXPECTED_NUM_SPEAKERS (voice.bin)")
    }

    tts = engine
    return engine
  }

  private fun releaseLocked() {
    tts?.let { engine ->
      runCatching { engine.release() }
        .onFailure { Log.w(TAG, "OfflineTts release failed", it) }
    }
    tts = null
    lastLoadDurationMs = 0L
  }

  // ------------------------------------------------------------------ voice mapping

  /**
   * F1..F5 -> 0..4, M1..M5 -> 5..9 (voice.bin buildup order, see class KDoc).
   * Clamped into [0, numSpeakers-1] by this side as a belt-and-braces measure;
   * sherpa-onnx additionally clamps internally.
   */
  fun voiceToSid(voice: String, numSpeakers: Int = this.numSpeakers): Int {
    val v = voice.trim().uppercase()
    val sid = runCatching {
      when (v.firstOrNull()) {
        'F' -> (v.drop(1).toInt() - 1).coerceIn(0, 4)
        'M' -> 5 + (v.drop(1).toInt() - 1).coerceIn(0, 4)
        else -> 0
      }
    }.getOrDefault(0)
    val limit = (numSpeakers - 1).coerceAtLeast(0)
    return sid.coerceIn(0, limit)
  }

  /** All ten preset ids in voice.bin order, for diagnostics on the Model screen. */
  fun voicePresets(numSpeakers: Int = this.numSpeakers): List<String> {
    val presets = listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5")
    return presets.take(numSpeakers.coerceIn(1, presets.size))
  }

  // ------------------------------------------------------------------ WAV building

  /**
   * Float samples in [-1, 1] -> 16-bit little-endian PCM bytes.
   *
   * N12: the payload MUST be little-endian. The previous implementation wrote
   * shorts with big-endian [DataOutputStream.writeShort], which every ARM/x86
   * player mangled into the device-verified "high-pitched chirp".
   */
  internal fun encodePcm16Le(samples: FloatArray): ByteArray {
    val out = ByteArray(samples.size * 2)
    for (i in samples.indices) {
      val s = (samples[i].coerceIn(-1.0f, 1.0f) * 32767.0f).roundToInt()
        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
      out[i * 2] = (s and 0xFF).toByte()      // low byte first (little-endian)
      out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()  // high byte second
    }
    return out
  }

  internal fun createWavHeaderAndData(pcmData: ByteArray): ByteArray {
    val totalDataLen = pcmData.size + 36
    val byteRate = sampleRate * numChannels * bitsPerSample / 8

    val wavStream = ByteArrayOutputStream()
    val out = DataOutputStream(wavStream)

    // RIFF header
    out.writeBytes("RIFF")
    out.writeInt(Integer.reverseBytes(totalDataLen))
    out.writeBytes("WAVE")

    // fmt subchunk
    out.writeBytes("fmt ")
    out.writeInt(Integer.reverseBytes(16))
    out.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
    out.writeShort(java.lang.Short.reverseBytes(numChannels.toShort()).toInt())
    out.writeInt(Integer.reverseBytes(sampleRate))
    out.writeInt(Integer.reverseBytes(byteRate))
    out.writeShort(java.lang.Short.reverseBytes((numChannels * bitsPerSample / 8).toShort()).toInt())
    out.writeShort(java.lang.Short.reverseBytes(bitsPerSample.toShort()).toInt())

    // data subchunk
    out.writeBytes("data")
    out.writeInt(Integer.reverseBytes(pcmData.size))
    out.write(pcmData)

    out.flush()
    return wavStream.toByteArray()
  }
}
