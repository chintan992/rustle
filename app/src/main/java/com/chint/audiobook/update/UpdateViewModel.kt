package com.chint.audiobook.update

import android.app.Application
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(
  application: Application,
  private val repository: GitHubUpdateRepository
) : AndroidViewModel(application) {
  private val mutableState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
  val state: StateFlow<UpdateUiState> = mutableState.asStateFlow()

  private var operation: Job? = null
  private var pendingPermissionRelease: UpdateRelease? = null

  fun checkForUpdate(manual: Boolean = false) {
    if (mutableState.value is UpdateUiState.Checking || mutableState.value is UpdateUiState.Downloading) {
      return
    }
    if (!manual && !repository.shouldCheckForUpdate()) return

    operation?.cancel()
    operation = viewModelScope.launch {
      mutableState.value = UpdateUiState.Checking
      try {
        repository.recordUpdateCheck()
        val update = repository.findUpdate(installedVersionCode())
        mutableState.value = when {
          update != null -> UpdateUiState.Available(update)
          manual -> UpdateUiState.UpToDate
          else -> UpdateUiState.Idle
        }
      } catch (error: NoPublishedReleaseException) {
        mutableState.value = if (manual) {
          UpdateUiState.NoPublishedRelease
        } else {
          UpdateUiState.Idle
        }
      } catch (error: IOException) {
        mutableState.value = if (manual) {
          UpdateUiState.Failed(error.message ?: "Could not check for updates.")
        } else {
          Log.w(TAG, "Automatic update check failed.", error)
          UpdateUiState.Idle
        }
      } catch (error: SerializationException) {
        mutableState.value = if (manual) {
          UpdateUiState.Failed(error.message ?: "Could not read the update information.")
        } else {
          Log.w(TAG, "Automatic update check returned invalid data.", error)
          UpdateUiState.Idle
        }
      }
    }
  }

  fun downloadAndInstall(release: UpdateRelease) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
      !getApplication<Application>().packageManager.canRequestPackageInstalls()
    ) {
      pendingPermissionRelease = release
      mutableState.value = UpdateUiState.PermissionRequired(release)
      return
    }
    beginDownload(release)
  }

  fun openInstallPermissionSettings() {
    val context = getApplication<Application>()
    val intent = Intent(
      android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
      Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      context.startActivity(intent)
    } catch (error: ActivityNotFoundException) {
      pendingPermissionRelease = null
      mutableState.value = UpdateUiState.Failed("Android could not open app-install permission settings.")
    }
  }

  fun onResume() {
    val release = pendingPermissionRelease ?: return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
      getApplication<Application>().packageManager.canRequestPackageInstalls()
    ) {
      pendingPermissionRelease = null
      beginDownload(release)
    }
  }

  fun dismiss() {
    if (mutableState.value is UpdateUiState.Downloading) {
      operation?.cancel()
      operation = null
    }
    pendingPermissionRelease = null
    mutableState.value = UpdateUiState.Idle
  }

  private fun beginDownload(release: UpdateRelease) {
    operation?.cancel()
    operation = viewModelScope.launch {
      try {
        mutableState.value = UpdateUiState.Downloading(release, null)
        val apkFile = repository.download(release) { progress ->
          mutableState.value = UpdateUiState.Downloading(release, progress)
        }
        val context = getApplication<Application>()
        val apkUri = FileProvider.getUriForFile(
          context,
          "${context.packageName}.updates",
          apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW)
          .setDataAndType(apkUri, APK_MIME_TYPE)
          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(installIntent)
        mutableState.value = UpdateUiState.Idle
      } catch (error: IOException) {
        mutableState.value = UpdateUiState.Failed(error.message ?: "Could not download the update.")
      } catch (error: SecurityException) {
        mutableState.value = UpdateUiState.Failed("Android blocked opening the downloaded update.")
      } catch (error: ActivityNotFoundException) {
        mutableState.value = UpdateUiState.Failed("Android could not open the package installer.")
      } catch (error: IllegalArgumentException) {
        mutableState.value = UpdateUiState.Failed("The downloaded update could not be opened.")
      } catch (error: CancellationException) {
        throw error
      }
    }
  }

  private fun installedVersionCode(): Int {
    val packageInfo = getApplication<Application>().packageManager.getPackageInfo(
      getApplication<Application>().packageName,
      0
    )
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      packageInfo.longVersionCode.toInt()
    } else {
      @Suppress("DEPRECATION")
      packageInfo.versionCode
    }
  }

  companion object {
    private const val TAG = "RustleUpdater"
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
  }
}
