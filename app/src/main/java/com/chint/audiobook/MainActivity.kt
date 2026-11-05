package com.chint.audiobook

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chint.audiobook.data.model.ThemeMode
import com.chint.audiobook.theme.AudiobookTheme
import com.chint.audiobook.ui.import.PendingImport
import com.chint.audiobook.ui.settings.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Beta round WS2/WS4: lazy FirebaseAnalytics wrapper
    com.chint.audiobook.core.Telemetry.init(this)

    // A2: Firebase auto-collects lifecycle events; app_open marks cold starts.
    // No user properties or book content are attached.
    com.google.firebase.analytics.FirebaseAnalytics.getInstance(this)
      .logEvent(com.google.firebase.analytics.FirebaseAnalytics.Event.APP_OPEN, android.os.Bundle())

    // WS2: EPUB shared/opened into the app from outside (see manifest filters).
    maybeOfferImportUri(intent)

    setContent {
      // Reuse the same activity-scoped SettingsViewModel that MainNavigation
      // resolves - a second instance would double-observe the DataStore flow.
      val settingsViewModel: SettingsViewModel = viewModel()
      val loadedSettings by settingsViewModel.appSettingsFlow.collectAsState(initial = null)

      val systemDark = isSystemInDarkTheme()
      // N14-era guard: before DataStore's first emission falls back to system
      // dark mode, so a cold start never paints an off-brand flash. When the
      // user pinned an explicit theme the scheme swaps within the first frames.
      val darkTheme = when (loadedSettings?.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM, null -> systemDark
      }

      // Follow the APP theme choice (not just the system one) so the manual
      // Light/Dark override also flips the status-bar icon luminance.
      val systemBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme }
      DisposableEffect(darkTheme) {
        enableEdgeToEdge(statusBarStyle = systemBarStyle, navigationBarStyle = systemBarStyle)
        onDispose { }
      }

      AudiobookTheme(darkTheme = darkTheme) {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background
        ) {
          MainNavigation()
        }
      }
    }
  }

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    // WS2: a second share/open while the activity is alive (launchMode default
    // recreates, but singleTop-style deliveries land here on some OEMs).
    maybeOfferImportUri(intent)
  }

  private fun maybeOfferImportUri(intent: android.content.Intent?) {
    when (intent?.action) {
      android.content.Intent.ACTION_SEND -> {
        @Suppress("DEPRECATION")
        val stream = intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM) as? android.net.Uri
        if (stream != null) {
          PendingImport.offer(stream)
        } else {
          val extraText = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
          val extraSubject = intent.getStringExtra(android.content.Intent.EXTRA_SUBJECT)
          if (!extraText.isNullOrBlank()) {
            PendingImport.offerText(extraText, extraSubject)
          }
        }
      }
      android.content.Intent.ACTION_VIEW -> {
        if (intent.data != null) PendingImport.offer(intent.data)
      }
    }
  }
}
