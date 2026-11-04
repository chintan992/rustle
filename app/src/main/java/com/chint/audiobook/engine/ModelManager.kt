package com.chint.audiobook.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.chint.audiobook.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed class DownloadState {
  data object Idle : DownloadState()

  /** Wifi-only setting is on and no Wi-Fi network is reachable: wait, do not fail. */
  data class WaitingForWifi(val message: String) : DownloadState()

  data class Downloading(
    val currentFile: String,
    /** 1-based index of the current file in [ModelManager.DOWNLOAD_ORDER]. */
    val fileIndex: Int = 0,
    val totalFiles: Int = ModelManager.REQUIRED_FILES.size,
    /** Progress within the current file (0..1), 0 while content-length is unknown. */
    val fileFraction: Float = 0f,
    /** All completed bytes so far (previous files + current partial), incl. resumed state. */
    val doneBytes: Long = 0L,
    /** Known pack total (145,295,768 bytes = ~138 MiB). */
    val totalBytes: Long = ModelManager.PACK_TOTAL_BYTES
  ) : DownloadState()

  /** sha256 verification over the staged pack. */
  data class Verifying(val message: String) : DownloadState()

  data object Verified : DownloadState()

  data class Error(val message: String) : DownloadState()
}

/** Internal marker for transient failures that a retry (with backoff) can fix. */
private class RetryableDownloadException(message: String, cause: Throwable? = null) :
  IOException(message, cause)

class ModelManager(private val context: Context) {

  private val client = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

  /**
   * Round 10 (Task 3): debug-only fault-injection state. Shots are armed only
   * through [debugArmFinalDirCorruption]/[debugArmStagedVerifyCorruption],
   * which are guarded by [BuildConfig.DEBUG]; in a release build the flow's
   * consume sites find no shots, so the hooks are inert by construction.
   */
  private val debugHooks = if (BuildConfig.DEBUG) DebugPackHooks() else null

  /** Optional sink for human-readable debug-hook events (wired by the model VM). */
  @Volatile
  var debugEventListener: ((String) -> Unit)? = null

  private fun debugLog(message: String) {
    Log.w(TAG, message)
    debugEventListener?.invoke(message)
  }

  companion object {
    private const val TAG = "ModelManager"

    const val HF_BASE_URL = "https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/main/"
    val REQUIRED_FILES = listOf(
      "duration_predictor.int8.onnx",
      "text_encoder.int8.onnx",
      "vector_estimator.int8.onnx",
      "vocoder.int8.onnx",
      "tts.json",
      "unicode_indexer.bin",
      "voice.bin"
    )

    /**
     * Round 9 (A8): download order — small index/config files first so a broken
     * flow fails fast and visibly before any big transfer, then the four big
     * onnx files (task order: tts.json, unicode_indexer.bin, voice.bin, onnx*).
     */
    val DOWNLOAD_ORDER = listOf(
      "tts.json",
      "unicode_indexer.bin",
      "voice.bin",
      "duration_predictor.int8.onnx",
      "text_encoder.int8.onnx",
      "vector_estimator.int8.onnx",
      "vocoder.int8.onnx"
    )

    /** ~145 MB total (the old "~400 MB" label was wrong; measured 145,295,768 bytes). */
    const val PACK_TOTAL_BYTES = 145_295_768L

    /**
     * sha256 of each pack file, verified against the Hugging Face repo manifest
     * (LFS oids) after the desktop download. Emitted after every download/push.
     */
    val EXPECTED_SHA256 = mapOf(
      "duration_predictor.int8.onnx" to "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db",
      "text_encoder.int8.onnx" to "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff",
      "vector_estimator.int8.onnx" to "20cd86fa5c6effedfda0e7cffe5b0569ca401c440a0c3a1d72bf39286c0db3fd",
      "vocoder.int8.onnx" to "e923d60f53f95eb1ce235f1dc33ec56d9c057823c96fa6f8acf98f32b0da6152",
      "unicode_indexer.bin" to "8402ca48e5189a8950138580b0fff64db6f072f24ac07cd54ba8b2fbb9883b30",
      "voice.bin" to "67d5209b0ee8ce6c74105ffbe12fe6a7628aea3b4ba2fcb308a4a67938a93ce8"
      // tts.json is small and not LFS in the HF repo; existence-only check.
    )

    private const val MAX_ATTEMPTS_PER_FILE = 4
    private const val PROGRESS_CHUNK_BYTES = 256 * 1024L
    private const val WIFI_POLL_INTERVAL_MS = 5_000L

    /**
     * Round 10 (Task 3): sha256 pack bookkeeping over [dir], pure (File-only,
     * no Context) so the JVM suite can exercise it directly. [verifyChecksums]
     * delegates to this unchanged.
     * Byte-precise: [Pair.first] (missing) lists only files BOTH in [wanted]
     * and REQUIRED_FILES that are absent or zero-length; [Pair.second]
     * (mismatch) lists only physically-present, non-empty files whose sha256
     * differs from [EXPECTED_SHA256], in map order.
     */
    fun evaluatePackChecksums(dir: File, wanted: Set<String>): Pair<List<String>, List<String>> {
      val missing = REQUIRED_FILES.filter { f ->
        f in wanted && run {
          val file = File(dir, f)
          !file.exists() || file.length() == 0L
        }
      }
      val mismatches = EXPECTED_SHA256.mapNotNull { (fileName, expected) ->
        val file = File(dir, fileName)
        if (!file.exists() || file.length() == 0L) return@mapNotNull null
        val actual = runCatching { sha256Hex(file) }.getOrNull()
        if (actual != expected) fileName else null
      }
      return missing to mismatches
    }

    internal fun sha256Hex(file: File): String = MessageDigest.getInstance("SHA-256").let { md ->
      file.inputStream().use { input ->
        val buf = ByteArray(8192)
        var read: Int
        while (input.read(buf).also { read = it } != -1) md.update(buf, 0, read)
        md.digest().joinToString("") { "%02x".format(it) }
      }
    }
  }

  fun getModelDir(): File {
    val dir = File(context.filesDir, "models/supertonic-3-int8")
    if (!dir.exists()) dir.mkdirs()
    return dir
  }

  /**
   * Round 9 (A8): staging directory. All 7 files land here first; only after
   * pack-wide sha256 verification are they moved into [getModelDir] — so a
   * half-verified pack can never be mistaken for a functional one.
   */
  fun getStagingDir(): File {
    val dir = File(context.filesDir, "models/supertonic-3-int8-staging")
    if (!dir.exists()) dir.mkdirs()
    return dir
  }

  /**
   * Removes the downloaded model pack and any incomplete staged download.
   *
   * The directories are recreated lazily by [getModelDir] and [getStagingDir]
   * when the next download or integrity check needs them.
   */
  fun clearModelData(): Result<Unit> = runCatching {
    val modelDir = File(context.filesDir, "models/supertonic-3-int8")
    val stagingDir = File(context.filesDir, "models/supertonic-3-int8-staging")
    listOf(modelDir, stagingDir).forEach { dir ->
      if (dir.exists() && !dir.deleteRecursively()) {
        error("Unable to remove model data from ${dir.name}")
      }
    }
  }

  fun isModelReady(): Boolean {
    val modelDir = getModelDir()
    return REQUIRED_FILES.all { fileName ->
      val file = File(modelDir, fileName)
      file.exists() && file.length() > 0L
    }
  }

  /**
   * A8: sha256 pack verification. Returns missing files and hash mismatches;
   * empty lists mean the physically-present pack is byte-identical to the repo.
   * [dir] defaults to the final model dir; the download flow verifies the
   * staging dir the same way before moving anything. [only] restricts the
   * check to a subset (the files staged by the current run) so a pack already
   * on disk is not failed by the empty staging dir.
   *
   * Round 10 (Task 3): delegates to [evaluatePackChecksums] — the same logic,
   * factored out so the JVM suite can pin the bookkeeping byte-precisely.
   */
  fun verifyChecksums(dir: File = getModelDir(), only: Set<String>? = null): Pair<List<String>, List<String>> =
    evaluatePackChecksums(dir, only ?: REQUIRED_FILES.toSet())

  fun isWifiConnected(): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
    val activeNetwork = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
  }

  /**
   * Round 9 (A8): the full in-app download flow.
   *
   * - honors [wifiOnly]: waits on Wi-Fi (state [DownloadState.WaitingForWifi])
   *   instead of failing, per the wifi_only_downloads setting;
   * - downloads [DOWNLOAD_ORDER] sequentially into the staging dir, one file at
   *   a time, with per-file progress + overall "done MB of ~138 MB";
   * - resumes a partial `.part` file via an HTTP Range request; when the server
   *   ignores Range (HTTP 200 instead of 206) the file restarts from zero
   *   (logged, never silently corrupted);
   * - retries transient failures per file (4 attempts, expo backoff);
   * - verifies checksums over the staged pack, retries a mismatched file once,
   *   then fails with a clear message;
   * - only then moves the files into the final model dir and reports Verified.
   */
  suspend fun downloadModel(
    wifiOnly: Boolean = false,
    onProgress: (DownloadState) -> Unit
  ): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      runDownloadFlow(wifiOnly, onProgress)
      Result.success(Unit)
    } catch (ce: CancellationException) {
      // Propagate coroutine cancellation (VM cleared while downloading);
      // swallowing it would report a fake "download failed".
      throw ce
    } catch (t: Throwable) {
      Result.failure(t)
    } finally {
      // Round 10 (Task 3): never let an armed debug shot leak into a later,
      // unrelated download. Consumed shots are already gone; anything left
      // armed (e.g. the flow failed before a verification pass) is dropped.
      if (debugHooks?.isArmed() == true) {
        debugHooks.disarm()
        debugLog("R10 debug: leftover armed hook shot(s) disarmed at flow end")
      }
    }
  }

  // ------------------------------------------------------------- debug hooks
  // Round 10 (Task 3): on-device proof harness for the A8 checksum-recovery
  // paths. Debug builds only (BuildConfig.DEBUG); each arm injects exactly one
  // byte fault and the NORMAL user download path runs unchanged, so what is
  // proven is the app's own recovery machinery, not a bespoke workaround.

  /** Journey A: corrupt one byte of a final-dir pack file at flow entry. */
  fun debugArmFinalDirCorruption(fileName: String): Boolean {
    if (!BuildConfig.DEBUG) return false
    val hooks = debugHooks ?: return false
    hooks.armFinalDirCorruption(fileName)
    debugLog("R10 debug: armed — corrupt 1 byte of '$fileName' in the final dir at the next download entry")
    return true
  }

  /** Journey B: corrupt the staged copy before each of the two sha256 passes. */
  fun debugArmStagedVerifyCorruption(fileName: String, times: Int = 2): Boolean {
    if (!BuildConfig.DEBUG) return false
    val hooks = debugHooks ?: return false
    hooks.armStagedVerifyCorruption(fileName, times)
    debugLog("R10 debug: armed — corrupt 1 byte of staged '$fileName' before each of the $times sha256 passes")
    return true
  }

  /**
   * Journey B prep: move the final-dir copy of [fileName] byte-identically to
   * '<fileName>.r10debug-bak' so the flow really stages a fresh download (a
   * verified final copy would be resume-skipped and the hook would starve).
   * The backup is restored by [debugRestoreQuarantinedFile] after the
   * exhausted-retry failure so the user's pack emerges intact.
   */
  fun debugQuarantineFinalFile(fileName: String): Boolean {
    if (!BuildConfig.DEBUG) return false
    val file = File(getModelDir(), fileName)
    val bak = File(getModelDir(), "$fileName.r10debug-bak")
    bak.delete()
    if (file.exists()) {
      val moved = file.renameTo(bak) || run {
        file.copyTo(bak, overwrite = true)
        file.delete()
        true
      }
      debugLog("R10 debug: quarantined final '$fileName' -> '$fileName.r10debug-bak' (moved=$moved)")
    }
    return true
  }

  /**
   * Journey B post: put the quarantined backup back and re-verify
   * byte-identically. Returns false when there is no backup or the restored
   * copy fails its sha256 — the caller decides what to surface.
   */
  fun debugRestoreQuarantinedFile(fileName: String): Boolean {
    if (!BuildConfig.DEBUG) return false
    val dir = getModelDir()
    val bak = File(dir, "$fileName.r10debug-bak")
    if (!bak.exists()) return false
    val file = File(dir, fileName)
    file.delete()
    val restored = bak.renameTo(file) || run {
      bak.copyTo(file, overwrite = true)
      bak.delete()
      true
    }
    val (missing, mismatch) = verifyChecksums(dir, only = setOf(fileName))
    val ok = restored && missing.isEmpty() && mismatch.isEmpty()
    debugLog("R10 debug: restored '$fileName' from .r10debug-bak ok=$ok (missing=$missing mismatch=$mismatch)")
    return ok
  }

  /** Hook consumption at flow entry: flip one final-dir byte, in place. */
  private fun debugConsumeFinalDirHook(finalDir: File) {
    if (!BuildConfig.DEBUG) return
    val fileName = debugHooks?.consumeFinalDirShot() ?: return
    val offset = DebugPackCorruptor.flipOneByte(File(finalDir, fileName))
    debugLog(
      "R10 debug hook: flipped 1 byte of final-dir '$fileName' at offset=$offset — the flow must detect and repair"
    )
  }

  /** Hook consumption right before a staging sha256 pass. */
  private fun debugConsumeStagedVerifyHook(staging: File, label: String) {
    if (!BuildConfig.DEBUG) return
    val fileName = debugHooks?.consumeStagedVerifyShot() ?: return
    val offset = DebugPackCorruptor.flipOneByte(File(staging, fileName))
    debugLog(
      "R10 debug hook [$label]: flipped 1 byte of staged '$fileName' at offset=$offset — verify must catch it"
    )
  }

  private suspend fun runDownloadFlow(wifiOnly: Boolean, onProgress: (DownloadState) -> Unit) {
    if (wifiOnly) {
      waitWhileNoWifi(onProgress)
    }

    val staging = getStagingDir()
    val finalDir = getModelDir()
    val stagedNow = mutableSetOf<String>()

    // Round 10 (Task 3): debug-only — flip exactly one byte of one final-dir
    // pack file BEFORE the resume inventory reads it, so the flow itself must
    // detect the mismatch and repair by refetch + staging move. Inert in
    // release (no shot can be armed).
    debugConsumeFinalDirHook(finalDir)

    for ((index, fileName) in DOWNLOAD_ORDER.withIndex()) {
      // Skip verified files in the final dir AND already-complete staged files
      // from a previous run — that is the cross-restart resume inventory.
      // Final-dir files are NOT tracked in stagedNow: the end-of-run staging
      // verification covers only what this run fetched; the post-move full-pack
      // verification covers everything.
      if (isFileHashOk(finalDir, fileName)) {
        Log.i(TAG, "A8 resume skip: $fileName already present+verified in final dir")
        continue
      }
      val stagedCompleted = File(staging, fileName)
      if (stagedCompleted.exists() && stagedCompleted.length() > 0L) {
        Log.i(TAG, "A8 resume skip: $fileName already staged complete (${stagedCompleted.length()} bytes) from a previous run")
        stagedNow.add(fileName)
        continue
      }

      val url = "$HF_BASE_URL$fileName"
      val partFile = File(staging, "$fileName.part")
      val bytesIntoFile = retryingDownload(url, fileName, partFile) { fileDone, contentLength ->
        emitProgress(
          onProgress, fileName, index,
          fileDone = fileDone, fileContentLength = contentLength,
          doneBytesBase = inventoryBytes(staging, finalDir, exclude = fileName)
        )
      }
      // Atomic completion marker in staging: .part -> final staged name.
      val stagedFile = File(staging, fileName)
      if (!partFile.renameTo(stagedFile)) {
        partFile.copyTo(stagedFile, overwrite = true)
        partFile.delete()
      }
      stagedNow.add(fileName)
      Log.i(TAG, "A8 download complete: $fileName bytes=$bytesIntoFile (${index + 1}/${DOWNLOAD_ORDER.size})")
    }

    if (stagedNow.isEmpty()) {
      // The final pack was already complete and hash-verified when we entered
      // the flow; nothing was transferred.
      Log.i(TAG, "A8 download flow: pack already verified in final dir — nothing to do")
      onProgress(DownloadState.Verified)
      return
    }

    // sha256 verification over whatever this run staged.
    onProgress(DownloadState.Verifying("sha256 over staged files..."))
    debugConsumeStagedVerifyHook(staging, "1st staged verify")
    val (firstMissing, firstMismatch) = verifyChecksums(staging, only = stagedNow)
    when (val verdict = ChecksumRecovery.decideStaged(firstMissing, firstMismatch)) {
      is ChecksumRecovery.StagedVerdict.Incomplete ->
        throw IllegalStateException("Download incomplete: still missing after all files fetched: ${verdict.missing}")
      is ChecksumRecovery.StagedVerdict.Clean -> Unit
      is ChecksumRecovery.StagedVerdict.Refetch -> {
        Log.w(TAG, "A8 sha256 mismatch on first verification: ${verdict.mismatched} — deleting and retrying once")
        for (fileName in verdict.mismatched) {
          File(staging, fileName).delete()
          val refetched = File(staging, fileName)
          retryingDownload("$HF_BASE_URL$fileName", fileName, refetched) { fileDone, contentLength ->
            emitProgress(
              onProgress, fileName, DOWNLOAD_ORDER.indexOf(fileName),
              fileDone = fileDone, fileContentLength = contentLength,
              doneBytesBase = inventoryBytes(staging, finalDir, exclude = fileName)
            )
          }
        }
        onProgress(DownloadState.Verifying("sha256 re-check after retry..."))
        debugConsumeStagedVerifyHook(staging, "recheck after retry")
        val (reMissing, reMismatch) = verifyChecksums(staging, only = stagedNow)
        when (ChecksumRecovery.decideAfterRefetch(reMissing, reMismatch, verdict.mismatched)) {
          is ChecksumRecovery.RecoveryVerdict.Recovered -> {
            Log.i(TAG, "A8 retry recovered mismatched file(s): ${verdict.mismatched}")
          }
          is ChecksumRecovery.RecoveryVerdict.Exhausted -> {
            throw IllegalStateException(
              "Pack verification failed after download+retry: " + buildString {
                if (reMissing.isNotEmpty()) append("missing: $reMissing ")
                if (reMismatch.isNotEmpty()) append("sha256 mismatch: $reMismatch")
              }
            )
          }
        }
      }
    }

    // Move staged files into the final dir atomically per-file (same-volume
    // rename; copy fallback disclosed, still only after successful hash).
    // Only files actually staged THIS run are moved: on a partial retry the
    // other pack files already live in the final dir (resume-skip branch).
    finalDir.mkdirs()
    for (fileName in REQUIRED_FILES) {
      val stagedFile = File(staging, fileName)
      if (!stagedFile.exists()) continue
      val finalFile = File(finalDir, fileName)
      if (!stagedFile.renameTo(finalFile)) {
        stagedFile.copyTo(finalFile, overwrite = true)
        stagedFile.delete()
      }
    }

    val (finalMissing, finalMismatch) = verifyChecksums(finalDir)
    if (finalMissing.isNotEmpty() || finalMismatch.isNotEmpty()) {
      throw IllegalStateException("Post-move verification failed: missing=$finalMissing mismatch=$finalMismatch")
    }

    staging.deleteRecursively()
    Log.i(TAG, "A8 download flow complete: pack verified and moved into ${finalDir.path}")
    onProgress(DownloadState.Verified)
  }

  // ---------------------------------------------------------------- internals

  /** One file: up to [MAX_ATTEMPTS_PER_FILE] attempts with expo backoff between them. */
  private suspend fun retryingDownload(
    url: String,
    fileName: String,
    partFile: File,
    onFileProgress: (doneBytes: Long, contentLength: Long) -> Unit
  ): Long {
    var lastError: Exception? = null
    for (attempt in 1..MAX_ATTEMPTS_PER_FILE) {
      try {
        return downloadOnce(url, fileName, partFile, onFileProgress)
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        // (RetryableDownloadException extends IOException, so this arm also takes
        // plain transport failures — Wi-Fi drop mid-body, socket timeout, DNS
        // miss, connection reset — all transient. The .part keeps whatever the
        // failed attempt wrote, so the next attempt resumes it.)
        lastError = e
        if (attempt < MAX_ATTEMPTS_PER_FILE) {
          val backoff = DownloadMath.retryBackoffMs(attempt)
          Log.w(TAG, "A8 retry $attempt/$MAX_ATTEMPTS_PER_FILE for $fileName in ${backoff}ms: ${e.message}")
          delay(backoff)
        }
      }
    }
    throw lastError ?: IllegalStateException("download failed without a recorded error: $fileName")
  }

  /**
   * Single fetch attempt into [partFile]. A partial file is resumed with a
   * Range request; when the server answers 206 we append, any other success
   * answer (HTTP 200 = the server ignored Range) restarts the file from zero
   * and is logged — the part file is truncated first so no gap-corrupted file
   * can be assembled.
   */
  private fun downloadOnce(
    url: String,
    fileName: String,
    partFile: File,
    onFileProgress: (doneBytes: Long, contentLength: Long) -> Unit
  ): Long {
    val existing = if (partFile.exists()) partFile.length() else 0L
    val requestBuilder = Request.Builder().url(url)
    if (existing > 0L) {
      requestBuilder.header("Range", "bytes=$existing-")
    }

    client.newCall(requestBuilder.build()).execute().use { response ->
      val code = response.code

      // Transient HTTP conditions -> retryable for the attempt loop.
      if (DownloadMath.isRetryableHttp(code)) {
        throw RetryableDownloadException("HTTP $code (transient) for $fileName")
      }
      if (code != 200 && code != 206) {
        throw IllegalStateException("Failed to download $fileName: HTTP $code")
      }

      if (existing > 0L && code != 206) {
        // Server ignored Range: restart that file from zero.
        Log.i(
          TAG,
          "A8 resume: server ignored Range for $fileName (HTTP $code, expected 206) — restarting file from zero"
        )
        if (!partFile.delete()) partFile.writeBytes(ByteArray(0))
      }

      val appendMode = DownloadMath.appendMode(code)
      val body = response.body ?: throw RetryableDownloadException("empty response body for $fileName")
      val resumedBytes = if (appendMode) existing else 0L

      // Redirect-chain audit (HF resolve -> CDN), for honest failure reports.
      var hops = 0
      var prior = response.priorResponse
      while (prior != null) {
        hops++
        prior = prior.priorResponse
      }
      Log.i(
        TAG,
        "A8 GET $fileName code=$code redirects=$hops append=$appendMode resumedBytes=$resumedBytes url=${response.request.url}"
      )

      FileOutputStream(partFile, appendMode).use { output ->
        val source = body.byteStream()
        val buffer = ByteArray(64 * 1024)
        var writtenInAttempt = 0L
        var lastEmitted = 0L
        while (true) {
          val read = source.read(buffer)
          if (read == -1) break
          output.write(buffer, 0, read)
          writtenInAttempt += read
          if (writtenInAttempt - lastEmitted >= PROGRESS_CHUNK_BYTES) {
            lastEmitted = writtenInAttempt
            onFileProgress(resumedBytes + writtenInAttempt, body.contentLength())
          }
        }
        onFileProgress(resumedBytes + writtenInAttempt, body.contentLength())
        val totalInFile = resumedBytes + writtenInAttempt
        if (totalInFile == 0L) {
          throw RetryableDownloadException("downloaded $fileName but produced 0 bytes")
        }
        return totalInFile
      }
    }
    throw IllegalStateException("unreachable: downloadOnce completed without returning")
  }

  /** Waits for Wi-Fi when wifi-only mode is on, surfacing the waiting state. */
  private suspend fun waitWhileNoWifi(onProgress: (DownloadState) -> Unit) {
    while (!isWifiConnected()) {
      onProgress(DownloadState.WaitingForWifi("Waiting for Wi-Fi (wifi_only_downloads is on)..."))
      delay(WIFI_POLL_INTERVAL_MS)
    }
  }

  /**
   * Bytes already accounted for, excluding the in-flight file (its bytes arrive
   * through the progress callback). Counted per file: verified final-dir copy
   * first, then a verified complete staged copy (a previous run's completion),
   * then the raw .part bytes.
   */
  private fun inventoryBytes(staging: File, finalDir: File, exclude: String?): Long =
    DOWNLOAD_ORDER.filter { it != exclude }.sumOf { name ->
      bytesOnDiskFor(staging, finalDir, name)
    }

  private fun bytesOnDiskFor(staging: File, finalDir: File, fileName: String): Long {
    val inFinal = File(finalDir, fileName)
    if (inFinal.exists() && isFileHashOk(finalDir, fileName)) return inFinal.length()
    val staged = File(staging, fileName)
    if (staged.exists() && staged.length() > 0L) return staged.length()
    return File(staging, "$fileName.part").length()
  }

  private fun isFileHashOk(dir: File, fileName: String): Boolean {
    val file = File(dir, fileName)
    if (!file.exists() || file.length() == 0L) return false
    val expected = EXPECTED_SHA256[fileName] ?: return true // tts.json: existence-only
    val actual = runCatching { sha256Hex(file) }.getOrNull() ?: return false
    return actual == expected
  }

  private fun emitProgress(
    onProgress: (DownloadState) -> Unit,
    fileName: String,
    index: Int,
    fileDone: Long,
    fileContentLength: Long,
    doneBytesBase: Long
  ) {
    onProgress(
      DownloadState.Downloading(
        currentFile = fileName,
        fileIndex = index + 1,
        fileFraction = DownloadMath.fileFraction(fileDone, fileContentLength),
        doneBytes = doneBytesBase + fileDone,
        totalBytes = PACK_TOTAL_BYTES
      )
    )
  }
}
