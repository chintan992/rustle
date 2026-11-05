package com.chint.audiobook

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sealed navigation key hierarchy for Rustle audiobook app.
 *
 * Implements [NavKey] while specifying explicit [@SerialName] strings so that
 * KotlinX Serialization produces stable, deterministic payloads across releases
 * and is 100% immune to ProGuard / R8 class minification and obfuscation.
 */
@Serializable
sealed interface AppNavKey : NavKey

@Serializable
@SerialName("voice_setup")
data object VoiceSetupKey : AppNavKey

@Serializable
@SerialName("library")
data object LibraryKey : AppNavKey

@Serializable
@SerialName("import")
data object ImportKey : AppNavKey

@Serializable
@SerialName("reader")
data class ReaderKey(
  val bookSlug: String,
  val chapterIndex: Int = 1
) : AppNavKey

@Serializable
@SerialName("model_manager")
data object ModelManagerKey : AppNavKey

@Serializable
@SerialName("settings")
data object SettingsKey : AppNavKey

@Serializable
@SerialName("licenses")
data object LicensesKey : AppNavKey

internal fun onboardingStartRoute(
  onboardingCompleted: Boolean,
  onboardingModelReady: Boolean
): AppNavKey = when {
  onboardingCompleted -> LibraryKey
  onboardingModelReady -> VoiceSetupKey
  else -> ModelManagerKey
}
