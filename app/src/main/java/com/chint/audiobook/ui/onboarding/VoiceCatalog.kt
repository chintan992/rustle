package com.chint.audiobook.ui.onboarding

import com.chint.audiobook.data.model.TtsEngine

/**
 * User-facing voice catalog. The storage/engine id ("F2") stays the key in
 * prefs and TTS calls; these human names + descriptions are display-only,
 * so users can pick without auditioning all ten.
 *
 * Descriptors are editorial flavor (Supertone ships the presets unnamed) —
 * keep them mild; validate by ear if a user ever reports a mismatch.
 */
data class VoicePreset(
  val id: String,
  val name: String,
  val description: String,
  val isDefault: Boolean = false
)

object VoiceCatalog {

  val presets = listOf(
    VoicePreset("F1", "Amelia", "Bright, clear storyteller"),
    VoicePreset("F2", "Clara", "Warm and steady", isDefault = true),
    VoicePreset("F3", "Hazel", "Soft, gentle pace"),
    VoicePreset("F4", "Iris", "Light and lively"),
    VoicePreset("F5", "Freya", "Rich and expressive"),
    VoicePreset("M1", "Oliver", "Calm, even tone"),
    VoicePreset("M2", "Henry", "Smooth and mellow"),
    VoicePreset("M3", "Marcus", "Deep and grounded"),
    VoicePreset("M4", "Felix", "Friendly and upbeat"),
    VoicePreset("M5", "Arthur", "Classic storyteller")
  )

  val kokoroPresets = listOf(
    VoicePreset("af_heart", "Heart", "Warm American English narrator", isDefault = true),
    VoicePreset("af_bella", "Bella", "Clear American English narrator"),
    VoicePreset("af_nicole", "Nicole", "Light American English narrator"),
    VoicePreset("af_nova", "Nova", "Bright American English narrator"),
    VoicePreset("am_fenrir", "Fenrir", "Low American English narrator"),
    VoicePreset("am_michael", "Michael", "Steady American English narrator"),
    VoicePreset("am_puck", "Puck", "Lively American English narrator"),
    VoicePreset("bf_emma", "Emma", "British English narrator"),
    VoicePreset("bf_lily", "Lily", "British English narrator"),
    VoicePreset("bm_george", "George", "British English narrator")
  )

  fun presetsFor(engine: TtsEngine): List<VoicePreset> =
    if (engine == TtsEngine.KOKORO) kokoroPresets else presets

  fun defaultId(engine: TtsEngine): String =
    presetsFor(engine).firstOrNull { it.isDefault }?.id ?: presetsFor(engine).first().id

  fun contains(engine: TtsEngine, id: String): Boolean =
    presetsFor(engine).any { it.id == id.trim() }

  /** Resolves a stored id; unknown ids fall back to showing the raw id. */
  fun byId(id: String): VoicePreset =
    presets.firstOrNull { it.id == id.trim().uppercase() }
      ?: kokoroPresets.firstOrNull { it.id == id.trim() }
      ?: VoicePreset(id = id, name = id, description = "")

  /** Short display form for chips and status lines ("Clara"). */
  fun displayName(id: String): String = byId(id).name
}
