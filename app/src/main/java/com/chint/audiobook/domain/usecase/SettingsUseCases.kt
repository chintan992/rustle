package com.chint.audiobook.domain.usecase

import com.chint.audiobook.data.model.AppSettings
import com.chint.audiobook.data.model.PlaybackState
import com.chint.audiobook.domain.repository.UserSettingsRepository
import kotlinx.coroutines.flow.Flow

class ObserveSettingsUseCase(private val settingsRepository: UserSettingsRepository) {
  operator fun invoke(): Flow<AppSettings> = settingsRepository.appSettings
}

class UpdateSettingsUseCase(private val settingsRepository: UserSettingsRepository) {
  suspend operator fun invoke(transform: (AppSettings) -> AppSettings) {
    settingsRepository.updateAppSettings(transform)
  }
}

class ObservePlaybackStateUseCase(private val settingsRepository: UserSettingsRepository) {
  operator fun invoke(): Flow<PlaybackState> = settingsRepository.playbackState
}

class SavePlaybackStateUseCase(private val settingsRepository: UserSettingsRepository) {
  suspend operator fun invoke(state: PlaybackState) {
    settingsRepository.savePlaybackState(state)
  }
}

