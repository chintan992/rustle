package com.chint.audiobook.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
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

class KokoroModelManager(private val context: Context) {

  private val client = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()

  companion object {
    private const val TAG = "KokoroModelManager"
    const val HF_BASE_URL =
      "https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_0/resolve/" +
        "f7b96bb6bef5c5da4d3aa4f4e0498fbbf62dc78b/"

    val REQUIRED_FILES = listOf("model.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt")
    val DOWNLOAD_ORDER = listOf("tokens.txt", "lexicon-us-en.txt", "voices.bin", "model.onnx")
    val EXPECTED_SIZES = mapOf(
      "model.onnx" to 325_560_556L,
      "voices.bin" to 28_200_960L,
      "tokens.txt" to 687L,
      "lexicon-us-en.txt" to 5_956_885L
    )
    val EXPECTED_SHA256 = mapOf(
      "model.onnx" to "b40f62b166ac8164b0627ef48a0b358eda0985e272fb03ef5252e7206305da11",
      "voices.bin" to "1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29",
      "tokens.txt" to "6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425",
      "lexicon-us-en.txt" to "7daaab53a181be9885b853a8582bf1838186317e5dadacbcef9c426d6fa0da14"
    )
    const val PACK_TOTAL_BYTES = 359_719_088L
    private const val MAX_ATTEMPTS = 4
    private const val WIFI_POLL_INTERVAL_MS = 5_000L

    fun evaluatePackChecksums(dir: File, wanted: Set<String> = REQUIRED_FILES.toSet()): Pair<List<String>, List<String>> {
      val missing = REQUIRED_FILES.filter { name ->
        name in wanted && run {
          val file = File(dir, name)
          !file.isFile || file.length() != EXPECTED_SIZES.getValue(name)
        }
      }
      val mismatches = REQUIRED_FILES.filter { name ->
        if (name !in wanted) return@filter false
        val file = File(dir, name)
        file.isFile &&
          file.length() == EXPECTED_SIZES.getValue(name) &&
          runCatching { sha256Hex(file) }.getOrNull() != EXPECTED_SHA256.getValue(name)
      }
      return missing to mismatches
    }

    internal fun sha256Hex(file: File): String =
      MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
          }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
      }
  }

  fun getModelDir(): File = File(context.filesDir, "models/kokoro-82m-v1.0").apply {
    if (!exists() && !mkdirs()) throw IOException("Unable to create Kokoro model directory: $path")
  }

  private fun getStagingDir(): File =
    File(context.filesDir, "models/kokoro-82m-v1.0-staging").apply {
      if (!exists() && !mkdirs()) throw IOException("Unable to create Kokoro staging directory: $path")
    }

  fun isModelReady(): Boolean {
    val dir = getModelDir()
    return REQUIRED_FILES.all { name ->
      val file = File(dir, name)
      file.isFile && file.length() == EXPECTED_SIZES.getValue(name)
    }
  }

  fun verifyChecksums(dir: File = getModelDir()): Pair<List<String>, List<String>> =
    evaluatePackChecksums(dir)

  fun clearModelData(): Result<Unit> = runCatching {
    listOf(
      File(context.filesDir, "models/kokoro-82m-v1.0"),
      File(context.filesDir, "models/kokoro-82m-v1.0-staging")
    ).forEach { dir ->
      if (dir.exists() && !dir.deleteRecursively()) {
        throw IOException("Unable to remove Kokoro model data from ${dir.name}")
      }
    }
  }

  suspend fun downloadModel(
    wifiOnly: Boolean,
    onProgress: (DownloadState) -> Unit
  ): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      if (wifiOnly) {
        while (!isWifiConnected()) {
          onProgress(DownloadState.WaitingForWifi("Waiting for Wi-Fi to download the Kokoro model."))
          delay(WIFI_POLL_INTERVAL_MS)
        }
      }
      downloadPack(onProgress)
      Result.success(Unit)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      Log.e(TAG, "Kokoro model download failed", error)
      Result.failure(error)
    }
  }

  private suspend fun downloadPack(onProgress: (DownloadState) -> Unit) {
    val finalDir = getModelDir()
    val stagingDir = getStagingDir()
    var doneBytes = REQUIRED_FILES.sumOf { name ->
      val file = File(finalDir, name)
      if (file.isFile &&
        file.length() == EXPECTED_SIZES.getValue(name) &&
        sha256Hex(file) == EXPECTED_SHA256.getValue(name)
      ) file.length() else 0L
    }

    DOWNLOAD_ORDER.forEachIndexed { index, name ->
      val finalFile = File(finalDir, name)
      if (finalFile.isFile &&
        finalFile.length() == EXPECTED_SIZES.getValue(name) &&
        sha256Hex(finalFile) == EXPECTED_SHA256.getValue(name)
      ) {
        onProgress(
          DownloadState.Downloading(
            currentFile = name,
            fileIndex = index + 1,
            totalFiles = REQUIRED_FILES.size,
            fileFraction = 1f,
            doneBytes = doneBytes,
            totalBytes = PACK_TOTAL_BYTES
          )
        )
        return@forEachIndexed
      }

      val stagedFile = File(stagingDir, name)
      if (!isVerifiedFile(stagedFile, name)) {
        downloadFile(name, stagedFile, stagingDir, index + 1, doneBytes, onProgress)
      }
      doneBytes += EXPECTED_SIZES.getValue(name)
    }

    onProgress(DownloadState.Verifying("Verifying Kokoro model checksums…"))
    val (missing, mismatches) = evaluatePackChecksums(stagingDir, DOWNLOAD_ORDER.toSet())
    if (missing.isNotEmpty() || mismatches.isNotEmpty()) {
      throw IOException("Kokoro staged pack failed SHA-256 verification: missing=$missing mismatches=$mismatches")
    }

    DOWNLOAD_ORDER.forEach { name ->
      val staged = File(stagingDir, name)
      val target = File(finalDir, name)
      if (!staged.renameTo(target)) {
        staged.copyTo(target, overwrite = true)
        if (!staged.delete()) throw IOException("Unable to remove staged Kokoro file $name")
      }
    }
    val (finalMissing, finalMismatches) = verifyChecksums(finalDir)
    if (finalMissing.isNotEmpty() || finalMismatches.isNotEmpty()) {
      throw IOException("Kokoro final pack failed SHA-256 verification: missing=$finalMissing mismatches=$finalMismatches")
    }
    if (stagingDir.exists() && !stagingDir.deleteRecursively()) {
      Log.w(TAG, "Unable to remove Kokoro staging directory ${stagingDir.path}")
    }
    onProgress(DownloadState.Verified)
  }

  private suspend fun downloadFile(
    name: String,
    stagedFile: File,
    stagingDir: File,
    fileIndex: Int,
    doneBytes: Long,
    onProgress: (DownloadState) -> Unit
  ) {
    val partFile = File(stagingDir, "$name.part")
    var lastFailure: IOException? = null
    repeat(MAX_ATTEMPTS) { attempt ->
      try {
        val offset = partFile.length().takeIf { it < EXPECTED_SIZES.getValue(name) } ?: run {
          partFile.delete()
          0L
        }
        val request = Request.Builder()
          .url("$HF_BASE_URL$name")
          .apply { if (offset > 0L) header("Range", "bytes=$offset-") }
          .build()
        client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("HTTP ${response.code} downloading $name")
          val body = response.body ?: throw IOException("Empty response downloading $name")
          val append = offset > 0L && response.code == 206
          if (offset > 0L && !append) partFile.delete()
          val initialBytes = if (append) offset else 0L
          val digest = MessageDigest.getInstance("SHA-256")
          if (append) updateDigest(digest, partFile)
          var received = initialBytes
          body.byteStream().use { input ->
            FileOutputStream(partFile, append).use { output ->
              val buffer = ByteArray(128 * 1024)
              while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                received += count
                onProgress(
                  DownloadState.Downloading(
                    currentFile = name,
                    fileIndex = fileIndex,
                    totalFiles = REQUIRED_FILES.size,
                    fileFraction = (received.toFloat() / EXPECTED_SIZES.getValue(name)).coerceIn(0f, 1f),
                    doneBytes = doneBytes + received,
                    totalBytes = PACK_TOTAL_BYTES
                  )
                )
              }
            }
          }
          val expectedSize = EXPECTED_SIZES.getValue(name)
          if (received != expectedSize) {
            throw IOException("Downloaded $name has $received bytes; expected $expectedSize")
          }
          val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
          if (actualHash != EXPECTED_SHA256.getValue(name)) {
            partFile.delete()
            throw IOException("SHA-256 mismatch downloading $name")
          }
        }
        if (!partFile.renameTo(stagedFile)) {
          partFile.copyTo(stagedFile, overwrite = true)
          if (!partFile.delete()) throw IOException("Unable to remove partial Kokoro file $name")
        }
        return
      } catch (error: CancellationException) {
        throw error
      } catch (error: IOException) {
        lastFailure = error
        if (attempt + 1 < MAX_ATTEMPTS) {
          val backoffMs = 1_000L * (attempt + 1)
          Log.w(TAG, "Kokoro download attempt ${attempt + 1}/$MAX_ATTEMPTS failed for $name; retrying in ${backoffMs}ms", error)
          delay(backoffMs)
        }
      }
    }
    val detail = lastFailure?.message?.let { ": $it" }.orEmpty()
    throw IOException("Failed to download Kokoro file $name after $MAX_ATTEMPTS attempts$detail", lastFailure)
  }

  private fun updateDigest(digest: MessageDigest, file: File) {
    file.inputStream().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
  }

  private fun isVerifiedFile(file: File, name: String): Boolean =
    file.isFile &&
      file.length() == EXPECTED_SIZES.getValue(name) &&
      runCatching { sha256Hex(file) }.getOrNull() == EXPECTED_SHA256.getValue(name)

  private fun isWifiConnected(): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
  }
}
