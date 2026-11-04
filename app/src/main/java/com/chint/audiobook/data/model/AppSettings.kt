package com.chint.audiobook.data.model

import kotlinx.serialization.Serializable

/** Theme preference exposed in Settings; SYSTEM follows the OS dark mode. */
@Serializable
enum class ThemeMode {
  SYSTEM,
  LIGHT,
  DARK;

  companion object {
    fun fromStorageKey(key: String?): ThemeMode =
      entries.firstOrNull { it.name == key } ?: SYSTEM
  }
}

/** UI Round 4 (R5): reader transcript typeface. */
@Serializable
enum class ReaderFontFamily {
  SANS,
  SERIF;

  companion object {
    fun fromStorageKey(key: String?): ReaderFontFamily =
      entries.firstOrNull { it.name == key } ?: SANS
  }
}

/**
 * UI Round 4 (R5): reader transcript line-height presets. NORMAL matches the
 * shipped bodyLarge rhythm exactly (16sp text / 24sp leading → ×1.50), so the
 * default rendering is pixel-identical to pre-R5 builds; COMPACT/AIRY deviate
 * on purpose.
 */
@Serializable
enum class ReaderLineSpacing(val multiplier: Float) {
  COMPACT(1.35f),
  NORMAL(1.50f),
  AIRY(1.80f);

  companion object {
    fun fromStorageKey(key: String?): ReaderLineSpacing =
      entries.firstOrNull { it.name == key } ?: NORMAL
  }
}

@Serializable
data class AppSettings(
  val defaultVoice: String = "F2",
  val highlightEnabled: Boolean = true,
  val wifiOnlyDownloads: Boolean = true,
  val batterySaverEnabled: Boolean = false,
  val onboardingCompleted: Boolean = false,
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  // UI Round 4 (R5): reader display preferences. Defaults mirror the previous
  // hardcoded look (16sp sans / bodyLarge 1.5-ish leading via NORMAL) so old
  // DataStore JSONs deserialize without migration.
  val readerFontSizeSp: Int = 16,
  val readerFontFamily: ReaderFontFamily = ReaderFontFamily.SANS,
  val readerLineSpacing: ReaderLineSpacing = ReaderLineSpacing.NORMAL,
  // Cache/prep round (B2): rendered-audio cap in GB (0 = unlimited) and the
  // download-ahead block. cacheCapGb=0 disables eviction; prepareAheadChapters=0
  // disables background synthesis entirely. batterySaverEnabled (a legacy
  // setting that previously had no consumer) now freezes preparation too.
  // Multi-voice round: separate narrator for quoted dialogue. null = off
  // (everything renders in the narration voice).
  val dialogueVoice: String? = null,
  // Beta round (WS1): auto-insert measured-safe expression cues (<laughter> on
  // laugh-syllable runs, <sigh>/<cough> on stage directions) at import time.
  // Affects newly imported/prepped chapters; the English-measured tag set is
  // not applied to non-English books.
  val expressiveNarration: Boolean = true,
  // Q2: one-time OEM battery-optimization guidance (dismissed = shown once).
  val batteryGuidanceDismissed: Boolean = false,
  // Q5: yearly listening goal in hours (0 = no goal).
  val yearlyGoalHours: Int = 0,
  val cacheCapGb: Int = 2,
  val prepareAheadChapters: Int = 1,
  val prepareWifiOnly: Boolean = true,
  val prepareOnlyWhileCharging: Boolean = true,
  // Track 3: skip quiet pauses in narration automatically
  val skipSilence: Boolean = false
)
