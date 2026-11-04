package com.chint.audiobook.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chint.audiobook.data.model.AppSettings
import com.chint.audiobook.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.chint.audiobook.domain.usecase.ObserveSettingsUseCase
import com.chint.audiobook.domain.usecase.UpdateSettingsUseCase

@HiltViewModel
class SettingsViewModel @Inject constructor(
  application: Application,
  private val observeSettings: ObserveSettingsUseCase,
  private val updateSettingsUseCase: UpdateSettingsUseCase,
  private val settingsRepository: com.chint.audiobook.domain.repository.UserSettingsRepository
) : AndroidViewModel(application) {



  val appSettings: StateFlow<AppSettings> = observeSettings().stateIn(
    viewModelScope,
    SharingStarted.WhileSubscribed(5000),
    AppSettings()
  )

  /**
   * N14: raw DataStore flow. Navigation collects this with
   * `collectAsState(initial = null)` and renders nothing until the first
   * emission, so no screen can paint from the AppSettings() default before
   * persisted settings (onboarding flag, voice) have been read from disk.
   */
  val appSettingsFlow: Flow<AppSettings> = observeSettings()

  fun updateSettings(transform: (AppSettings) -> AppSettings) {
    viewModelScope.launch {
      updateSettingsUseCase(transform)
    }
  }
}
