package com.chint.audiobook.ui.onboarding

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

  /** Resolves a stored id; unknown ids fall back to showing the raw id. */
  fun byId(id: String): VoicePreset =
    presets.firstOrNull { it.id == id.trim().uppercase() }
      ?: VoicePreset(id = id, name = id, description = "")

  /** Short display form for chips and status lines ("Clara"). */
  fun displayName(id: String): String = byId(id).name
}
