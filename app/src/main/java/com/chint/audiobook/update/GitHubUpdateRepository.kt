package com.chint.audiobook.update

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class GitHubUpdateRepository @Inject constructor(
  @param:ApplicationContext private val context: Context
) {
  private val client = OkHttpClient()
  private val json = Json { ignoreUnknownKeys = true }
  private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  suspend fun findUpdate(installedVersionCode: Int): UpdateRelease? = withContext(Dispatchers.IO) {
    val release = getJson(
      "$API_BASE/releases/latest",
      GitHubRelease.serializer()
    )
    if (release.draft || release.prerelease) {
      return@withContext null
    }

    val manifestAsset = release.assets.singleOrNull { it.name == MANIFEST_NAME }
      ?: throw IOException("The latest GitHub release is missing $MANIFEST_NAME.")
    val manifestUrl = validateReleaseAssetUrl(manifestAsset.browserDownloadUrl)
    val manifestText = getText(manifestUrl)
    val manifest = try {
      json.decodeFromString(UpdateManifest.serializer(), manifestText)
    } catch (error: SerializationException) {
      throw IOException("The latest GitHub release has invalid update metadata.", error)
    }

    if (
      manifest.versionCode <= 0 ||
      !VERSION_NAME_PATTERN.matches(manifest.versionName)
    ) {
      throw IOException("The latest GitHub release has invalid version metadata.")
    }
    if (!isNewerVersion(manifest.versionCode, installedVersionCode)) {
      return@withContext null
    }

    val abi = selectCompatibleAbi(manifest.assets, Build.SUPPORTED_ABIS)
      ?: throw IOException("No update APK is available for this device.")
    val assetDescription = manifest.assets.getValue(abi)
    val expectedName = "Rustle-$abi-${manifest.versionName}.apk"
    if (assetDescription.name != expectedName) {
      throw IOException("The release APK name does not match the update metadata.")
    }
    if (!SHA256_PATTERN.matches(assetDescription.sha256)) {
      throw IOException("The release APK has an invalid SHA-256 checksum.")
    }

    val apkAsset = release.assets.singleOrNull { it.name == assetDescription.name }
      ?: throw IOException("The latest GitHub release is missing ${assetDescription.name}.")

    UpdateRelease(
      versionCode = manifest.versionCode,
      versionName = manifest.versionName,
      abi = abi,
      downloadUrl = validateReleaseAssetUrl(apkAsset.browserDownloadUrl),
      sha256 = assetDescription.sha256.lowercase()
    )
  }

  suspend fun download(
    release: UpdateRelease,
    onProgress: (Int?) -> Unit
  ): File = withContext(Dispatchers.IO) {
    val coroutineContext = currentCoroutineContext()
    val updatesDirectory = File(context.cacheDir, UPDATES_DIRECTORY)
    if (!updatesDirectory.exists() && !updatesDirectory.mkdirs()) {
      throw IOException("Could not create the update download directory.")
    }

    val fileName = "Rustle-${release.abi}-${release.versionName}.apk"
    val outputFile = File(updatesDirectory, fileName)
    val partialFile = File(updatesDirectory, "$fileName.part")
    val request = Request.Builder()
      .url(validateReleaseAssetUrl(release.downloadUrl))
      .header("Accept", "application/octet-stream")
      .build()

    try {
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          throw IOException("GitHub APK download failed (HTTP ${response.code}).")
        }
        val body = response.body ?: throw IOException("GitHub returned an empty APK download.")
        val digest = MessageDigest.getInstance("SHA-256")
        val totalBytes = body.contentLength()
        var downloadedBytes = 0L

        body.byteStream().use { input ->
          FileOutputStream(partialFile).use { output ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            while (true) {
              coroutineContext.ensureActive()
              val count = input.read(buffer)
              if (count < 0) break
              output.write(buffer, 0, count)
              digest.update(buffer, 0, count)
              downloadedBytes += count
              onProgress(
                if (totalBytes > 0) {
                  ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                } else {
                  null
                }
              )
            }
            output.fd.sync()
          }
        }

        val actualSha256 = digest.digest().toHexString()
        if (!actualSha256.equals(release.sha256, ignoreCase = true)) {
          throw IOException("The downloaded APK checksum did not match the release metadata.")
        }
        if (outputFile.exists() && !outputFile.delete()) {
          throw IOException("Could not replace the previously downloaded APK.")
        }
        if (!partialFile.renameTo(outputFile)) {
          throw IOException("Could not finalize the downloaded APK.")
        }
      }
    } finally {
      if (partialFile.exists()) partialFile.delete()
    }

    outputFile
  }

  fun shouldCheckForUpdate(nowMillis: Long = System.currentTimeMillis()): Boolean =
    nowMillis - preferences.getLong(KEY_LAST_CHECK, 0L) >= CHECK_INTERVAL_MILLIS

  fun recordUpdateCheck(nowMillis: Long = System.currentTimeMillis()) {
    preferences.edit().putLong(KEY_LAST_CHECK, nowMillis).apply()
  }

  private fun <T> getJson(url: String, serializer: kotlinx.serialization.KSerializer<T>): T {
    val text = getText(url)
    return try {
      json.decodeFromString(serializer, text)
    } catch (error: SerializationException) {
      throw IOException("GitHub returned invalid release data.", error)
    }
  }

  private fun getText(url: String): String {
    val request = Request.Builder()
      .url(url)
      .header("Accept", "application/vnd.github+json")
      .header("X-GitHub-Api-Version", "2022-11-28")
      .build()

    client.newCall(request).execute().use { response ->
      if (response.code == 404) {
        if (url == "$API_BASE/releases/latest") {
          throw NoPublishedReleaseException()
        }
        throw IOException("The latest GitHub release is missing a required asset.")
      }
      if (!response.isSuccessful) {
        throw IOException("GitHub update check failed (HTTP ${response.code}).")
      }
      return response.body?.string()
        ?: throw IOException("GitHub returned an empty update response.")
    }
  }

  private fun validateReleaseAssetUrl(url: String): String {
    val parsed = try {
      java.net.URI(url)
    } catch (error: java.net.URISyntaxException) {
      throw IOException("The release contains an invalid download URL.", error)
    }
    if (
      parsed.scheme != "https" ||
      parsed.host != "github.com" ||
      parsed.rawUserInfo != null ||
      parsed.port != -1 && parsed.port != 443 ||
      !parsed.path.orEmpty().startsWith(RELEASE_DOWNLOAD_PATH)
    ) {
      throw IOException("The release contains an untrusted download URL.")
    }
    return url
  }

  private fun ByteArray.toHexString(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte) }

  @Serializable
  private data class GitHubRelease(
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<GitHubReleaseAsset>
  )

  @Serializable
  private data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String
  )

  companion object {
    private const val API_BASE = "https://api.github.com/repos/chintan992/rustle"
    private const val MANIFEST_NAME = "rustle-update.json"
    private const val RELEASE_DOWNLOAD_PATH = "/chintan992/rustle/releases/download/"
    private const val PREFERENCES_NAME = "update_checks"
    private const val KEY_LAST_CHECK = "last_check_millis"
    private const val UPDATES_DIRECTORY = "updates"
    private const val DOWNLOAD_BUFFER_SIZE = 32 * 1024
    private const val CHECK_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L
    private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
    private val VERSION_NAME_PATTERN = Regex("^[0-9]+(\\.[0-9]+){1,2}$")
  }
}

class NoPublishedReleaseException : IOException("No update release has been published yet.")
