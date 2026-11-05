package com.chint.audiobook.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chint.audiobook.data.model.AppSettings
import com.chint.audiobook.data.model.PlaybackState
import com.chint.audiobook.data.model.ReaderFontFamily
import com.chint.audiobook.data.model.ReaderLineSpacing
import com.chint.audiobook.data.model.ThemeMode
import com.chint.audiobook.data.model.TtsEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository @Inject constructor(
  @ApplicationContext private val context: Context
) : com.chint.audiobook.domain.repository.UserSettingsRepository {

  private object Keys {
    val TTS_ENGINE = stringPreferencesKey("tts_engine")
    val DEFAULT_VOICE = stringPreferencesKey("default_voice")
    val KOKORO_VOICE = stringPreferencesKey("kokoro_voice")
    val HIGHLIGHT_ENABLED = booleanPreferencesKey("highlight_enabled")
    // Cache/prep round (B2): the dead lookahead_seconds key is retired — its
    // DataStore entry (if any) is simply ignored from now on.
    val WIFI_ONLY_DOWNLOADS = booleanPreferencesKey("wifi_only_downloads")
    val BATTERY_SAVER_ENABLED = booleanPreferencesKey("battery_saver_enabled")
    val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    val THEME_MODE = stringPreferencesKey("theme_mode")

    // UI Round 4 (R5): reader display preferences.
    val READER_FONT_SIZE_SP = intPreferencesKey("reader_font_size_sp")
    val READER_FONT_FAMILY = stringPreferencesKey("reader_font_family")
    val READER_LINE_SPACING = stringPreferencesKey("reader_line_spacing")

    // Multi-voice: separate narrator for quoted dialogue (null = off).
    val DIALOGUE_VOICE = stringPreferencesKey("dialogue_voice")
    val KOKORO_DIALOGUE_VOICE = stringPreferencesKey("kokoro_dialogue_voice")
    // Beta round (WS1): expressive narration auto-cue insertion.
    val EXPRESSIVE_NARRATION = booleanPreferencesKey("expressive_narration")
    // Q2/Q5: battery guidance flag + yearly goal.
    val BATTERY_GUIDANCE_DISMISSED = booleanPreferencesKey("battery_guidance_dismissed")
    val YEARLY_GOAL_HOURS = intPreferencesKey("yearly_goal_hours")
    // Cache/prep round (B2): audio cache cap + background preparation.
    val CACHE_CAP_GB = intPreferencesKey("cache_cap_gb")
    val PREPARE_AHEAD_CHAPTERS = intPreferencesKey("prepare_ahead_chapters")
    val PREPARE_WIFI_ONLY = booleanPreferencesKey("prepare_wifi_only")
    val PREPARE_ONLY_CHARGING = booleanPreferencesKey("prepare_only_charging")
    val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
    val ANNOUNCEMENT_NOTIFICATIONS_ENABLED = booleanPreferencesKey("announcement_notifications_enabled")
    val ANNOUNCEMENT_PERMISSION_PROMPTED = booleanPreferencesKey("announcement_permission_prompted")

    // Playback state keys
    val BOOK_SLUG = stringPreferencesKey("last_book_slug")
    val CHAPTER_INDEX = intPreferencesKey("last_chapter_index")
    val SENTENCE_INDEX = intPreferencesKey("last_sentence_index")
    val OFFSET_MS = longPreferencesKey("last_offset_ms")
    val VOICE_OVERRIDE = stringPreferencesKey("last_voice_override")
    val SPEED = floatPreferencesKey("last_speed")
  }

  override val appSettings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
    AppSettings(
      ttsEngine = TtsEngine.fromStorageKey(prefs[Keys.TTS_ENGINE]),
      defaultVoice = prefs[Keys.DEFAULT_VOICE] ?: "F2",
      kokoroVoice = prefs[Keys.KOKORO_VOICE] ?: "af_heart",
      highlightEnabled = prefs[Keys.HIGHLIGHT_ENABLED] ?: true,
      wifiOnlyDownloads = prefs[Keys.WIFI_ONLY_DOWNLOADS] ?: true,
      batterySaverEnabled = prefs[Keys.BATTERY_SAVER_ENABLED] ?: false,
      onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
      themeMode = ThemeMode.fromStorageKey(prefs[Keys.THEME_MODE]),
      readerFontSizeSp = prefs[Keys.READER_FONT_SIZE_SP] ?: 16,
      readerFontFamily = ReaderFontFamily.fromStorageKey(prefs[Keys.READER_FONT_FAMILY]),
      readerLineSpacing = ReaderLineSpacing.fromStorageKey(prefs[Keys.READER_LINE_SPACING]),
      dialogueVoice = prefs[Keys.DIALOGUE_VOICE],
      kokoroDialogueVoice = prefs[Keys.KOKORO_DIALOGUE_VOICE],
      expressiveNarration = prefs[Keys.EXPRESSIVE_NARRATION] ?: true,
      batteryGuidanceDismissed = prefs[Keys.BATTERY_GUIDANCE_DISMISSED] ?: false,
      yearlyGoalHours = prefs[Keys.YEARLY_GOAL_HOURS] ?: 0,
      cacheCapGb = prefs[Keys.CACHE_CAP_GB] ?: 2,
      prepareAheadChapters = prefs[Keys.PREPARE_AHEAD_CHAPTERS] ?: 1,
      prepareWifiOnly = prefs[Keys.PREPARE_WIFI_ONLY] ?: true,
      prepareOnlyWhileCharging = prefs[Keys.PREPARE_ONLY_CHARGING] ?: true,
      skipSilence = prefs[Keys.SKIP_SILENCE] ?: false,
      announcementNotificationsEnabled = prefs[Keys.ANNOUNCEMENT_NOTIFICATIONS_ENABLED]
        ?: !(prefs[Keys.ONBOARDING_COMPLETED] ?: false),
      announcementPermissionPrompted = prefs[Keys.ANNOUNCEMENT_PERMISSION_PROMPTED]
        ?: (prefs[Keys.ONBOARDING_COMPLETED] ?: false)
    )
  }

  override suspend fun updateAppSettings(transform: (AppSettings) -> AppSettings) {
    context.dataStore.edit { prefs ->
      val current = AppSettings(
        ttsEngine = TtsEngine.fromStorageKey(prefs[Keys.TTS_ENGINE]),
        defaultVoice = prefs[Keys.DEFAULT_VOICE] ?: "F2",
        kokoroVoice = prefs[Keys.KOKORO_VOICE] ?: "af_heart",
        highlightEnabled = prefs[Keys.HIGHLIGHT_ENABLED] ?: true,
        wifiOnlyDownloads = prefs[Keys.WIFI_ONLY_DOWNLOADS] ?: true,
        batterySaverEnabled = prefs[Keys.BATTERY_SAVER_ENABLED] ?: false,
        onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
        themeMode = ThemeMode.fromStorageKey(prefs[Keys.THEME_MODE]),
        readerFontSizeSp = prefs[Keys.READER_FONT_SIZE_SP] ?: 16,
        readerFontFamily = ReaderFontFamily.fromStorageKey(prefs[Keys.READER_FONT_FAMILY]),
        readerLineSpacing = ReaderLineSpacing.fromStorageKey(prefs[Keys.READER_LINE_SPACING]),
        dialogueVoice = prefs[Keys.DIALOGUE_VOICE],
        kokoroDialogueVoice = prefs[Keys.KOKORO_DIALOGUE_VOICE],
        expressiveNarration = prefs[Keys.EXPRESSIVE_NARRATION] ?: true,
        batteryGuidanceDismissed = prefs[Keys.BATTERY_GUIDANCE_DISMISSED] ?: false,
        yearlyGoalHours = prefs[Keys.YEARLY_GOAL_HOURS] ?: 0,
        cacheCapGb = prefs[Keys.CACHE_CAP_GB] ?: 2,
        prepareAheadChapters = prefs[Keys.PREPARE_AHEAD_CHAPTERS] ?: 1,
        prepareWifiOnly = prefs[Keys.PREPARE_WIFI_ONLY] ?: true,
        prepareOnlyWhileCharging = prefs[Keys.PREPARE_ONLY_CHARGING] ?: true,
        skipSilence = prefs[Keys.SKIP_SILENCE] ?: false,
        announcementNotificationsEnabled = prefs[Keys.ANNOUNCEMENT_NOTIFICATIONS_ENABLED]
          ?: !(prefs[Keys.ONBOARDING_COMPLETED] ?: false),
        announcementPermissionPrompted = prefs[Keys.ANNOUNCEMENT_PERMISSION_PROMPTED]
          ?: (prefs[Keys.ONBOARDING_COMPLETED] ?: false)
      )
      val updated = transform(current)
      prefs[Keys.TTS_ENGINE] = updated.ttsEngine.name
      prefs[Keys.DEFAULT_VOICE] = updated.defaultVoice
      prefs[Keys.KOKORO_VOICE] = updated.kokoroVoice
      prefs[Keys.HIGHLIGHT_ENABLED] = updated.highlightEnabled
      prefs[Keys.WIFI_ONLY_DOWNLOADS] = updated.wifiOnlyDownloads
      prefs[Keys.BATTERY_SAVER_ENABLED] = updated.batterySaverEnabled
      prefs[Keys.ONBOARDING_COMPLETED] = updated.onboardingCompleted
      prefs[Keys.THEME_MODE] = updated.themeMode.name
      prefs[Keys.READER_FONT_SIZE_SP] = updated.readerFontSizeSp
      prefs[Keys.READER_FONT_FAMILY] = updated.readerFontFamily.name
      prefs[Keys.READER_LINE_SPACING] = updated.readerLineSpacing.name
      if (updated.dialogueVoice != null) prefs[Keys.DIALOGUE_VOICE] = updated.dialogueVoice else prefs.remove(Keys.DIALOGUE_VOICE)
      if (updated.kokoroDialogueVoice != null) {
        prefs[Keys.KOKORO_DIALOGUE_VOICE] = updated.kokoroDialogueVoice
      } else {
        prefs.remove(Keys.KOKORO_DIALOGUE_VOICE)
      }
      prefs[Keys.EXPRESSIVE_NARRATION] = updated.expressiveNarration
      prefs[Keys.BATTERY_GUIDANCE_DISMISSED] = updated.batteryGuidanceDismissed
      prefs[Keys.YEARLY_GOAL_HOURS] = updated.yearlyGoalHours
      prefs[Keys.CACHE_CAP_GB] = updated.cacheCapGb
      prefs[Keys.PREPARE_AHEAD_CHAPTERS] = updated.prepareAheadChapters
      prefs[Keys.PREPARE_WIFI_ONLY] = updated.prepareWifiOnly
      prefs[Keys.PREPARE_ONLY_CHARGING] = updated.prepareOnlyWhileCharging
      prefs[Keys.SKIP_SILENCE] = updated.skipSilence
      prefs[Keys.ANNOUNCEMENT_NOTIFICATIONS_ENABLED] = updated.announcementNotificationsEnabled
      prefs[Keys.ANNOUNCEMENT_PERMISSION_PROMPTED] = updated.announcementPermissionPrompted
    }
  }

  override val playbackState: Flow<PlaybackState> = context.dataStore.data.map { prefs ->
    PlaybackState(
      bookSlug = prefs[Keys.BOOK_SLUG] ?: "",
      chapterIndex = prefs[Keys.CHAPTER_INDEX] ?: 1,
      sentenceIndex = prefs[Keys.SENTENCE_INDEX] ?: 0,
      offsetMs = prefs[Keys.OFFSET_MS] ?: 0L,
      voice = prefs[Keys.VOICE_OVERRIDE] ?: "F2",
      speed = prefs[Keys.SPEED] ?: 1.0f
    )
  }

  override suspend fun savePlaybackState(state: PlaybackState) {
    context.dataStore.edit { prefs ->
      prefs[Keys.BOOK_SLUG] = state.bookSlug
      prefs[Keys.CHAPTER_INDEX] = state.chapterIndex
      prefs[Keys.SENTENCE_INDEX] = state.sentenceIndex
      prefs[Keys.OFFSET_MS] = state.offsetMs
      prefs[Keys.VOICE_OVERRIDE] = state.voice
      prefs[Keys.SPEED] = state.speed
    }
  }

  /**
   * BG0 (ghost-book fix): reset the persisted playback state to defaults when
   * its book is deleted — otherwise the stale slug keeps resolving as a
   * "restore target" for the deleted book (open-chapter math, stale reader
   * entries) and Continue falls back onto a ghost.
   */
  override suspend fun clearPlaybackState() {
    savePlaybackState(PlaybackState())
  }
}
