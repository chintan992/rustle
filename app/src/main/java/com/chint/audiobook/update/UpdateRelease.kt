package com.chint.audiobook.update

import kotlinx.serialization.Serializable

data class UpdateRelease(
  val versionCode: Int,
  val versionName: String,
  val abi: String,
  val downloadUrl: String,
  val sha256: String
)

sealed interface UpdateUiState {
  data object Idle : UpdateUiState
  data object Checking : UpdateUiState
  data object UpToDate : UpdateUiState
  data object NoPublishedRelease : UpdateUiState
  data class Available(val release: UpdateRelease) : UpdateUiState
  data class PermissionRequired(val release: UpdateRelease) : UpdateUiState
  data class Downloading(val release: UpdateRelease, val progress: Int?) : UpdateUiState
  data class Failed(val message: String) : UpdateUiState
}

@Serializable
internal data class UpdateManifest(
  val versionCode: Int,
  val versionName: String,
  val assets: Map<String, ApkDescription>
)

@Serializable
internal data class ApkDescription(
  val name: String,
  val sha256: String
)

internal fun selectCompatibleAbi(
  assets: Map<String, ApkDescription>,
  supportedAbis: Array<String>
): String? = supportedAbis.firstOrNull(assets::containsKey)

internal fun isNewerVersion(availableVersionCode: Int, installedVersionCode: Int): Boolean =
  availableVersionCode > installedVersionCode
