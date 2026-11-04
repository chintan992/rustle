package com.chint.audiobook.engine

/**
 * Round 9 (Task 2): pure math for the A8 download flow, extracted so it is
 * unit-testable on the JVM without OkHttp or the filesystem.
 */
object DownloadMath {

  /** Backoff schedule in ms for per-file retry [attempt] (1-based): 2 s, 4 s, 8 s (capped). */
  fun retryBackoffMs(attempt: Int, maxMs: Long = 15_000L): Long {
    val raw = 2_000L shl ((attempt - 1).coerceIn(0, 8))
    return raw.coerceAtMost(maxMs)
  }

  /**
   * Range decision for resuming a partial download: HTTP 206 means the server
   * honored the Range header and we append to the partial file; any other
   * success code (e.g. 200) means the server ignored Range and the file must
   * restart from zero — the caller truncates before appending so no corrupt
   * middle gap is possible.
   */
  fun appendMode(responseCode: Int): Boolean = responseCode == 206

  /** True for HTTP codes we treat as transient (retryable): 429 and 5xx. */
  fun isRetryableHttp(responseCode: Int): Boolean =
    responseCode == 429 || (responseCode in 500..599)

  /**
   * Overall progress fraction: bytes already on disk (verified-complete files +
   * the current partial) over the known pack total. Coerced so a stale part
   * larger than the pack total can never push the bar over 1.0.
   */
  fun overallFraction(doneBytes: Long, totalBytes: Long): Float {
    if (totalBytes <= 0L) return 0f
    return (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
  }

  /** Per-file fraction; indeterminate (0f... leave to caller) when contentLength is unknown. */
  fun fileFraction(doneBytesInFile: Long, contentLength: Long): Float =
    when {
      contentLength <= 0L -> 0f
      else -> (doneBytesInFile.toFloat() / contentLength).coerceIn(0f, 1f)
    }
}
