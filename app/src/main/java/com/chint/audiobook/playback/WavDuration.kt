package com.chint.audiobook.playback

import java.io.File
import java.io.RandomAccessFile

/**
 * ElevenReader plan (A3): minimal WAV-header reader that computes PCM duration
 * from the fmt/data chunk pair without loading the file into memory. Feeds the
 * chapter timeline so the reader scrubber and ±skip jumps are exact by
 * construction, like the rest of the seek machinery.
 *
 * Unusable headers (short file, non-RIFF, non-PCM fmt, missing/empty data size)
 * return null — the caller flags the timeline entry unknown instead of
 * fabricating a duration.
 */
object WavDuration {

  /** PCM WAV duration in milliseconds, or null when the header is unusable. */
  fun durationMs(file: File): Long? = runCatching {
    RandomAccessFile(file, "r").use { raf ->
      val length = raf.length()
      if (length < 12L) return@use null

      readAscii(raf, 4).let { if (it != "RIFF") return@use null }
      raf.skipBytes(4) // RIFF chunk size is not needed
      readAscii(raf, 4).let { if (it != "WAVE") return@use null }

      var sampleRate = 0
      var byteRate = 0L
      var haveFmt = false
      var dataBytes: Long = -1

      while (true) {
        if (raf.filePointer + 8L > length) break
        val id = readAscii(raf, 4)
        val size = readLittleUint(raf)
        val bodyStart = raf.filePointer

        if (id == "fmt " && !haveFmt && size >= 16L) {
          // fmt body: formatCode(u16) channels(u16) sampleRate(u32) byteRate(u32)
          // blockAlign(u16) bitsPerSample(u16) — exactly 16 bytes consumed here.
          val formatCode = readLittleUint(raf) and 0xFFFFL // channels ride along, ignored
          sampleRate = readLittleUint(raf).toInt()
          byteRate = readLittleUint(raf)
          raf.skipBytes(2) // block align
          val bitsPerSample = readLe16(raf)
          if (formatCode == 1L && sampleRate > 0 && bitsPerSample >= 8) {
            haveFmt = true
          }
        } else if (id == "data") {
          // Trust the header, but never read past EOF (defensive against a
          // truncated/shrinking tail rather than a fabricated duration).
          dataBytes = minOf(size, length - bodyStart)
          break
        }
        raf.seek(bodyStart + size + (size % 2))
      }

      if (!haveFmt || dataBytes <= 0 || byteRate <= 0) {
        null
      } else {
        (dataBytes * 1000L) / byteRate
      }
    }
  }.getOrNull()

  private fun readAscii(raf: RandomAccessFile, count: Int): String {
    val bytes = ByteArray(count)
    raf.readFully(bytes)
    return String(bytes, Charsets.US_ASCII)
  }

  /** Unsigned little-endian WORD as a Long (values here are small/signs never matter). */
  private fun readLe16(raf: RandomAccessFile): Long {
    val b0 = raf.read().toLong()
    val b1 = raf.read().toLong()
    require(b0 >= 0 && b1 >= 0) { "EOF" }
    return b0 or (b1 shl 8)
  }

  /** Unsigned little-endian DWORD as a Long (values here are small/signs never matter). */
  private fun readLittleUint(raf: RandomAccessFile): Long {
    val b0 = raf.read().toLong()
    val b1 = raf.read().toLong()
    val b2 = raf.read().toLong()
    val b3 = raf.read().toLong()
    require(b0 >= 0 && b1 >= 0 && b2 >= 0 && b3 >= 0) { "EOF" }
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
  }
}
