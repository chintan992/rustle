package com.chint.audiobook.engine

import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class KokoroTtsEngineManager {

  companion object {
    private const val TAG = "KokoroTtsEngineManager"
    const val EXPECTED_SAMPLE_RATE = 24_000
    const val EXPECTED_NUM_SPEAKERS = 54
    private const val ENGINE_SPEED = 1.0f

    val VOICE_IDS = listOf(
      "af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica", "af_kore",
      "af_nicole", "af_nova", "af_river", "af_sarah", "af_sky", "am_adam",
      "am_echo", "am_eric", "am_fenrir", "am_liam", "am_michael", "am_onyx",
      "am_puck", "am_santa", "bf_alice", "bf_emma", "bf_isabella", "bf_lily",
      "bm_daniel", "bm_fable", "bm_george", "bm_lewis", "ef_dora", "em_alex",
      "em_santa", "ff_siwis", "hf_alpha", "hf_beta", "hm_omega", "hm_psi",
      "if_sara", "im_nicola", "jf_alpha", "jf_gongitsune", "jf_nezumi",
      "jf_tebukuro", "jm_kumo", "pf_dora", "pm_alex", "pm_santa", "zf_xiaobei",
      "zf_xiaoni", "zf_xiaoxiao", "zf_xiaoyi", "zm_yunjian", "zm_yunxi",
      "zm_yunxia", "zm_yunyang"
    )
  }

  private val mutex = Mutex()
  private val wavEncoder = TtsEngineManager()

  @Volatile
  private var tts: OfflineTts? = null

  private var sampleRate = EXPECTED_SAMPLE_RATE
  private var lastLoadDurationMs = 0L

  suspend fun generateAudioWav(text: String, voice: String, modelDir: File): ByteArray = mutex.withLock {
    val engine = requireEngine(modelDir)
    val sid = voiceToSid(voice)
    val synthesisText = stripExpressionTags(text)
    val chunks = KokoroTextChunker.split(synthesisText)
    if (chunks.isEmpty()) throw IllegalArgumentException("Cannot synthesize empty Kokoro text")
    Log.d(
      TAG,
      "Kokoro chunking: inputChars=${synthesisText.length} " +
        "inputWords=${KokoroTextChunker.wordCount(synthesisText)} chunks=${chunks.size} " +
        "maxChunkChars=${chunks.maxOf { it.length }}"
    )

    val samples = ArrayList<FloatArray>(chunks.size)
    for ((chunkIndex, chunk) in chunks.withIndex()) {
      val generated = engine.generateWithConfig(
        chunk,
        GenerationConfig(sid = sid, speed = ENGINE_SPEED)
      )
      if (generated.samples.isEmpty()) {
        throw IllegalStateException(
          "Kokoro produced no samples for chunk ${chunkIndex + 1}/${chunks.size} " +
            "(${chunk.length} characters, ${KokoroTextChunker.wordCount(chunk)} words)"
        )
      }
      if (generated.sampleRate != EXPECTED_SAMPLE_RATE) {
        throw IllegalStateException(
          "Kokoro returned sample rate ${generated.sampleRate}; expected $EXPECTED_SAMPLE_RATE"
        )
      }
      sampleRate = generated.sampleRate
      samples += generated.samples
    }
    val totalSamples = samples.sumOf { it.size }
    val combined = FloatArray(totalSamples)
    var offset = 0
    samples.forEach { chunk ->
      chunk.copyInto(combined, destinationOffset = offset)
      offset += chunk.size
    }
    wavEncoder.createWavHeaderAndData(wavEncoder.encodePcm16Le(combined), sampleRate)
  }

  fun voiceToSid(voice: String): Int =
    VOICE_IDS.indexOf(voice.trim().lowercase()).takeIf { it >= 0 }
      ?: throw IllegalArgumentException("Unknown Kokoro voice '$voice'")

  fun getEngineLoadTimeMs(): Long = lastLoadDurationMs
  fun isEngineLoaded(): Boolean = tts != null
  fun getSampleRate(): Int = if (tts != null) sampleRate else 0

  fun release() {
    releaseLocked()
  }

  suspend fun <T> releaseAndRun(operation: () -> T): T = mutex.withLock {
    releaseLocked()
    operation()
  }

  private fun releaseLocked() {
    tts?.let { engine ->
      runCatching { engine.release() }
        .onFailure { Log.w(TAG, "OfflineTts release failed", it) }
    }
    tts = null
    lastLoadDurationMs = 0L
  }

  private fun requireEngine(modelDir: File): OfflineTts {
    tts?.let { return it }
    val missing = KokoroModelManager.REQUIRED_FILES.filter { name ->
      val file = File(modelDir, name)
      !file.isFile || file.length() != KokoroModelManager.EXPECTED_SIZES.getValue(name)
    }
    if (missing.isNotEmpty()) {
      throw IllegalStateException(
        "Kokoro-82M v1.0 pack is incomplete in ${modelDir.path}: missing or invalid $missing. " +
          "Download the Kokoro pack in Model Manager before synthesis."
      )
    }

    val loadStart = System.currentTimeMillis()
    val kokoro = OfflineTtsKokoroModelConfig().apply {
      model = File(modelDir, "model.onnx").absolutePath
      voices = File(modelDir, "voices.bin").absolutePath
      tokens = File(modelDir, "tokens.txt").absolutePath
      lexicon = File(modelDir, "lexicon-us-en.txt").absolutePath
      dataDir = ""
      lang = "en-us"
    }
    val config = OfflineTtsConfig(
      model = OfflineTtsModelConfig(
        kokoro = kokoro,
        numThreads = 2,
        debug = false,
        provider = "cpu"
      ),
      maxNumSentences = 1
    )
    val engine = OfflineTts(config = config)
    val actualSpeakers = engine.numSpeakers()
    val actualRate = engine.sampleRate()
    if (actualSpeakers != EXPECTED_NUM_SPEAKERS || actualRate != EXPECTED_SAMPLE_RATE) {
      engine.release()
      throw IllegalStateException(
        "Unexpected Kokoro model metadata: sampleRate=$actualRate speakers=$actualSpeakers"
      )
    }
    sampleRate = actualRate
    lastLoadDurationMs = (System.currentTimeMillis() - loadStart).coerceAtLeast(1L)
    tts = engine
    Log.i(
      TAG,
      "Kokoro initialized: sampleRate=$sampleRate speakers=$actualSpeakers " +
        "provider=cpu numThreads=2 loadMs=$lastLoadDurationMs"
    )
    return engine
  }

  private fun stripExpressionTags(text: String): String =
    text.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
}

object KokoroTextChunker {
  const val MAX_CHARS = 160

  fun split(text: String, maxChars: Int = MAX_CHARS): List<String> {
    require(maxChars > 0) { "maxChars must be positive" }
    val normalizedText = text.trim().replace(Regex("\\s+"), " ")
    if (normalizedText.isEmpty()) return emptyList()

    val chunks = mutableListOf<String>()
    val current = StringBuilder()

    fun appendUnit(unit: String) {
      if (current.isNotEmpty() && current.length + 1 + unit.length > maxChars) {
        chunks += current.toString()
        current.setLength(0)
      }
      if (current.isNotEmpty()) current.append(' ')
      current.append(unit)
    }

    fun appendWordsWithinLimit(textUnit: String) {
      for (word in textUnit.split(' ')) {
        require(word.length <= maxChars) {
          "A Kokoro input token exceeds the safe chunk limit ($maxChars characters); " +
            "it was not synthesized to avoid silently truncating text."
        }
        if (current.isNotEmpty() && current.length + 1 + word.length > maxChars) {
          chunks += current.toString()
          current.setLength(0)
        }
        if (current.isNotEmpty()) current.append(' ')
        current.append(word)
      }
    }

    val sentences = normalizedText.split(
      Regex("(?<=[.!?])\\s+(?=[\\p{Lu}\\p{N}\"'“‘(])")
    )
    for (sentence in sentences) {
      if (sentence.length <= maxChars) {
        appendUnit(sentence)
        continue
      }

      val clauses = sentence.split(Regex("(?<=[,;:])\\s+"))
      for (clause in clauses) {
        if (clause.length <= maxChars) {
          appendUnit(clause)
        } else {
          appendWordsWithinLimit(clause)
        }
      }
    }
    if (current.isNotEmpty()) chunks += current.toString()
    return chunks
  }

  fun wordCount(text: String): Int =
    text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
}
