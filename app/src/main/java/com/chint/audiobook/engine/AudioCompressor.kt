package com.chint.audiobook.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.chint.audiobook.data.model.ChapterIndex
import com.chint.audiobook.data.model.SegmentIndexEntry
import com.chint.audiobook.data.model.TtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream

/**
 * Interface for audio encoding to allow decoupling platform MediaCodec
 * from pure Kotlin business logic and JVM tests.
 */
interface AudioEncoder {
  suspend fun encodePcmToAac(
    pcmStream: InputStream,
    sampleRate: Int,
    channelCount: Int,
    bitrate: Int,
    outputFile: File
  ): Boolean
}

/**
 * Default Android platform encoder using MediaCodec (AAC LC) and MediaMuxer (MP4 container).
 */
object AndroidMediaCodecEncoder : AudioEncoder {
  private const val TIMEOUT_US = 10_000L

  override suspend fun encodePcmToAac(
    pcmStream: InputStream,
    sampleRate: Int,
    channelCount: Int,
    bitrate: Int,
    outputFile: File
  ): Boolean = withContext(Dispatchers.IO) {
    var codec: MediaCodec? = null
    var muxer: MediaMuxer? = null
    try {
      val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
      }

      codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
      codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      codec.start()

      muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      var audioTrackIndex = -1
      var muxerStarted = false

      val bufferInfo = MediaCodec.BufferInfo()
      val inputBuffer = ByteArray(8192)
      var isInputEos = false
      var totalBytesRead = 0L

      val bytesPerSample = channelCount * 2 // 16-bit PCM

      while (true) {
        if (!isInputEos) {
          val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
          if (inputBufferIndex >= 0) {
            val byteBuffer = codec.getInputBuffer(inputBufferIndex)
            if (byteBuffer != null) {
              byteBuffer.clear()
              val bytesRead = pcmStream.read(inputBuffer, 0, minOf(inputBuffer.size, byteBuffer.remaining()))
              if (bytesRead > 0) {
                byteBuffer.put(inputBuffer, 0, bytesRead)
                val presentationTimeUs = (totalBytesRead * 1_000_000L) / (sampleRate * bytesPerSample)
                codec.queueInputBuffer(inputBufferIndex, 0, bytesRead, presentationTimeUs, 0)
                totalBytesRead += bytesRead
              } else {
                isInputEos = true
                val presentationTimeUs = (totalBytesRead * 1_000_000L) / (sampleRate * bytesPerSample)
                codec.queueInputBuffer(inputBufferIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              }
            }
          }
        }

        val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
        if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          if (muxerStarted) throw IllegalStateException("MediaCodec format changed multiple times")
          val newFormat = codec.outputFormat
          audioTrackIndex = muxer.addTrack(newFormat)
          muxer.start()
          muxerStarted = true
        } else if (outputBufferIndex >= 0) {
          val encodedData = codec.getOutputBuffer(outputBufferIndex)
          if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
            encodedData.position(bufferInfo.offset)
            encodedData.limit(bufferInfo.offset + bufferInfo.size)
            muxer.writeSampleData(audioTrackIndex, encodedData, bufferInfo)
          }
          codec.releaseOutputBuffer(outputBufferIndex, false)
          if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            break
          }
        }
      }
      true
    } catch (e: Exception) {
      Log.e("AudioCompressor", "encodePcmToAac failed: ${e.message}", e)
      outputFile.delete()
      false
    } finally {
      try {
        codec?.stop()
        codec?.release()
      } catch (_: Exception) {}
      try {
        muxer?.stop()
        muxer?.release()
      } catch (_: Exception) {}
    }
  }
}

/**
 * Handles two-tier audio storage: compresses raw chapter WAVs into AAC LC .m4a
 * with a chapter-index.json sidecar to achieve ~90% storage reduction while preserving
 * segment-accurate seek and karaoke highlighting.
 */
object AudioCompressor {
  private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

  const val INDEX_FILE_NAME = "chapter-index.json"
  const val COMPRESSED_FILE_NAME = "chapter.m4a"
  const val SAMPLE_RATE = 44100
  const val CHANNELS = 1
  const val BITRATE_BPS = 64_000 // 64 kbps AAC

  fun buildChapterIndex(
    chapterIndex: Int,
    hashes: List<String>,
    durationsByHash: Map<String, Long>,
    engine: TtsEngine? = null,
    narrationVoice: String? = null,
    dialogueVoice: String? = null,
    engineVersion: String? = null
  ): ChapterIndex {
    var cumulativeStartMs = 0L
    val entries = hashes.map { hash ->
      val duration = durationsByHash[hash] ?: 0L
      val entry = SegmentIndexEntry(hash = hash, startMs = cumulativeStartMs, durationMs = duration)
      cumulativeStartMs += duration
      entry
    }
    return ChapterIndex(
      chapterIndex = chapterIndex,
      totalDurationMs = cumulativeStartMs,
      segments = entries,
      engine = engine,
      narrationVoice = narrationVoice,
      dialogueVoice = dialogueVoice,
      engineVersion = engineVersion
        ?: if (engine == TtsEngine.KOKORO) SegmentVoices.KOKORO_CACHE_VERSION else null
    )
  }

  fun indexFile(chapterDir: File): File = File(chapterDir, INDEX_FILE_NAME)
  fun compressedFile(chapterDir: File): File = File(chapterDir, COMPRESSED_FILE_NAME)

  fun saveIndex(chapterDir: File, index: ChapterIndex) {
    val file = indexFile(chapterDir)
    file.parentFile?.mkdirs()
    file.writeText(json.encodeToString(ChapterIndex.serializer(), index))
  }

  fun loadIndex(chapterDir: File): ChapterIndex? {
    val file = indexFile(chapterDir)
    if (!file.exists() || file.length() == 0L) return null
    return runCatching {
      json.decodeFromString(ChapterIndex.serializer(), file.readText())
    }.getOrNull()
  }

  /**
   * Extracts raw 16-bit PCM bytes by skipping the 44-byte standard RIFF header.
   */
  fun extractPcmBytes(wavFile: File): ByteArray? {
    if (!wavFile.exists() || wavFile.length() <= 44L) return null
    return runCatching {
      val allBytes = wavFile.readBytes()
      if (allBytes.size > 44) allBytes.copyOfRange(44, allBytes.size) else null
    }.getOrNull()
  }

  fun isChapterCompressed(chapterDir: File): Boolean {
    val m4a = compressedFile(chapterDir)
    val index = indexFile(chapterDir)
    return m4a.exists() && m4a.length() > 0L && index.exists() && index.length() > 0L
  }

  /** Matches the stored engine/voice identity and, when supplied, every expected segment hash. */
  fun isChapterCompressedFor(
    chapterDir: File,
    engine: TtsEngine,
    narrationVoice: String,
    dialogueVoice: String?,
    expectedHashes: Collection<String>? = null
  ): Boolean {
    if (!isChapterCompressed(chapterDir)) return false
    val index = loadIndex(chapterDir) ?: return false
    val identityMatches = engine == TtsEngine.SUPERTONIC ||
      (index.engine == engine &&
        index.narrationVoice == narrationVoice &&
        index.dialogueVoice == dialogueVoice)
    if (!identityMatches) return false
    if (engine == TtsEngine.KOKORO && index.engineVersion != SegmentVoices.KOKORO_CACHE_VERSION) {
      return false
    }
    if (expectedHashes == null) return true
    if (expectedHashes.isEmpty()) return false
    val indexedHashes = index.segments.mapTo(HashSet()) { it.hash }
    return expectedHashes.all(indexedHashes::contains)
  }

  suspend fun compressChapter(
    chapterDir: File,
    chapterIndex: Int,
    hashes: List<String>,
    durationsByHash: Map<String, Long>,
    encoder: AudioEncoder = AndroidMediaCodecEncoder,
    sampleRate: Int = SAMPLE_RATE,
    engine: TtsEngine? = null,
    narrationVoice: String? = null,
    dialogueVoice: String? = null,
    engineVersion: String? = null,
    wavResolver: ((String) -> File?)? = null
  ): Boolean = withContext(Dispatchers.IO) {
    if (hashes.isEmpty()) return@withContext false
    val index = buildChapterIndex(
      chapterIndex,
      hashes,
      durationsByHash,
      engine,
      narrationVoice,
      dialogueVoice,
      engineVersion ?: if (engine == TtsEngine.KOKORO) SegmentVoices.KOKORO_CACHE_VERSION else null
    )
    val pcmBuffers = hashes.mapNotNull { hash ->
      val wav = wavResolver?.invoke(hash) ?: File(chapterDir, "$hash.wav")
      extractPcmBytes(wav)
    }
    if (pcmBuffers.size != hashes.size) {
      Log.w("AudioCompressor", "compressChapter missing PCM buffers: found ${pcmBuffers.size} of ${hashes.size}")
      return@withContext false
    }

    val totalBytes = pcmBuffers.sumOf { it.size }
    val concatenatedPcm = ByteArray(totalBytes)
    var offset = 0
    for (buf in pcmBuffers) {
      System.arraycopy(buf, 0, concatenatedPcm, offset, buf.size)
      offset += buf.size
    }

    val tempOut = File(chapterDir, "chapter.m4a.tmp")
    val success = encoder.encodePcmToAac(
      pcmStream = concatenatedPcm.inputStream(),
      sampleRate = sampleRate,
      channelCount = CHANNELS,
      bitrate = BITRATE_BPS,
      outputFile = tempOut
    )
    Log.i("AudioCompressor", "encoder result=$success, tempOut=${tempOut.length()} bytes")

    if (success && tempOut.exists() && tempOut.length() > 0L) {
      val targetM4a = compressedFile(chapterDir)
      if (tempOut.renameTo(targetM4a) || (tempOut.copyTo(targetM4a, overwrite = true).also { tempOut.delete() }.exists())) {
        saveIndex(chapterDir, index)
        return@withContext true
      }
    }
    tempOut.delete()
    false
  }

  fun pruneRawWavs(chapterDir: File): Int {
    if (!isChapterCompressed(chapterDir)) return 0
    val wavs = chapterDir.listFiles { f -> f.isFile && f.extension.equals("wav", ignoreCase = true) } ?: return 0
    var deletedCount = 0
    for (wav in wavs) {
      if (wav.delete()) deletedCount++
    }
    return deletedCount
  }
}
