package com.chint.audiobook.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chint.audiobook.R

/**
 * Play-release compliance: the in-app license bundle required by the
 * Supertonic-3 OpenRAIL-M license — its FULL text and Attachment A use
 * restrictions must be passed on to users as an enforceable provision — plus
 * attributions for the Apache-2.0 (sherpa-onnx) and MIT (ONNX Runtime)
 * components, the Kokoro training-data credits required by the Kokoro
 * model card (Koniwa CC BY 3.0, SIWIS CC BY 4.0), and the AI-narration
 * disclosure.
 *
 * License texts are bundled verbatim in res/raw (fetched from the upstream
 * sources; see RUSTLE_RELEASE_REPORT.md) and rendered read-only.
 */
@Composable
fun LicensesScreen(onBackClick: () -> Unit) {
  val context = LocalContext.current

  Column(
    modifier = Modifier
      .fillMaxSize()
      .windowInsetsPadding(WindowInsets.safeDrawing)
      .padding(16.dp)
      .verticalScroll(rememberScrollState())
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically
    ) {
      IconButton(
        onClick = onBackClick,
        modifier = Modifier.semantics { contentDescription = "Back" }
      ) {
        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
      }
      Spacer(modifier = Modifier.width(8.dp))
      Text("Licenses & AI Disclosure", style = MaterialTheme.typography.headlineSmall)
    }
    Spacer(modifier = Modifier.height(16.dp))

    Section(
      title = "AI narration",
      body = listOf(
        "Narration in Rustle is generated on your device by either the " +
          "Supertonic-3 speech model (Supertone Inc.) or Kokoro-82M v1.0. " +
          "Your books, text and audio never leave your phone.",
        "The int8 ONNX build of the model is provided by the open-source " +
          "sherpa-onnx project and downloaded once from Hugging Face with " +
          "integrity verification.",
        "The Supertonic-3 model is licensed under the BigScience OpenRAIL-M " +
          "License, which permits commercial use and redistribution and " +
          "includes use restrictions that apply to everyone in the chain — " +
          "the full license text follows below. Kokoro-82M v1.0 model weights " +
          "are provided under Apache License 2.0.",
        "Kokoro's training set also included third-party audio that keeps its " +
          "own license and is credited here: Koniwa tone recordings — " +
          "© Koniwa contributors, CC BY 3.0, github.com/koniwa/koniwa — and " +
          "SIWIS Audio — CC BY 4.0, datashare.ed.ac.uk/handle/10283/2353."
      )
    )
    Section(
      title = "Data & telemetry",
      body = listOf(
        "Rustle collects usage and crash reports through Google " +
          "Analytics for Firebase and Firebase Crashlytics. These services " +
          "may collect app interactions, app/device identifiers and crash " +
          "diagnostics. Google processes this information to provide the " +
          "services; Rustle does not send book text, file names or audio.",
        "See the Privacy Policy in Settings for details about Firebase, " +
          "optional announcements, model/update downloads, and your choices."
      )
    )
    Section(
      title = "Third-party components",
      body = listOf(
        "sherpa-onnx — Apache License 2.0 — k2-fsa (used for on-device " +
          "inference of the speech model)",
        "Kokoro-82M v1.0 — Apache License 2.0 — hexgrad (model weights)",
        "ONNX Runtime — MIT License — Microsoft (neural network runtime)",
        "Jetpack Compose, Media3, DataStore and other AndroidX libraries — " +
          "Apache License 2.0 — Google LLC",
        "Kotlin — Apache License 2.0 — JetBrains",
        "Koniwa tone recordings (Kokoro training data) — CC BY 3.0 — " +
          "© Koniwa contributors — github.com/koniwa/koniwa",
        "SIWIS Audio (Kokoro training data) — CC BY 4.0 — " +
          "datashare.ed.ac.uk/handle/10283/2353"
      )
    )

    LicenseBlock(title = "Supertonic-3 — BigScience OpenRAIL-M License") {
      rawText(context, R.raw.license_openrail_m)
    }
    LicenseBlock(title = "sherpa-onnx and Kokoro-82M — Apache License 2.0") {
      rawText(context, R.raw.license_apache_2)
    }
    LicenseBlock(title = "ONNX Runtime — MIT License") {
      rawText(context, R.raw.license_mit_onnxruntime)
    }

    Spacer(modifier = Modifier.height(24.dp))
  }
}

@Composable
private fun rawText(context: android.content.Context, resId: Int): String =
  remember(resId) {
    runCatching {
      context.resources.openRawResource(resId).bufferedReader().use { it.readText() }
    }.getOrDefault("(license text unavailable)")
  }

@Composable
private fun Section(title: String, body: List<String>) {
  Card(
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      Spacer(modifier = Modifier.height(8.dp))
      body.forEach { line ->
        Text(
          line,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
      }
    }
  }
}

@Composable
private fun LicenseBlock(title: String, text: @Composable () -> String) {
  Spacer(modifier = Modifier.height(16.dp))
  Text(title, style = MaterialTheme.typography.titleMedium)
  Spacer(modifier = Modifier.height(8.dp))
  Text(
    text(),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant
  )
}
