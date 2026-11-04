package com.chint.audiobook.domain.repository

import com.chint.audiobook.data.model.AppSettings
import com.chint.audiobook.data.model.PlaybackState
import kotlinx.coroutines.flow.Flow

interface UserSettingsRepository {
  val appSettings: Flow<AppSettings>
  val playbackState: Flow<PlaybackState>
  suspend fun updateAppSettings(transform: (AppSettings) -> AppSettings)
  suspend fun savePlaybackState(state: PlaybackState)
  suspend fun clearPlaybackState()
}

