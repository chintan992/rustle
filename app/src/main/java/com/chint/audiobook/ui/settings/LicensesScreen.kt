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
 * components, and the AI-narration disclosure.
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
        "Narration in Rustle is generated on your device by the Supertonic-3 " +
          "speech model (Supertone Inc.). Your books, text and audio never " +
          "leave your phone.",
        "The int8 ONNX build of the model is provided by the open-source " +
          "sherpa-onnx project and downloaded once from Hugging Face with " +
          "integrity verification.",
        "The Supertonic-3 model is licensed under the BigScience OpenRAIL-M " +
          "License, which permits commercial use and redistribution and " +
          "includes use restrictions that apply to everyone in the chain — " +
          "the full license text follows below."
      )
    )
    Section(
      title = "Data & telemetry",
      body = listOf(
        "Rustle collects anonymous usage and crash reports through Google " +
          "Analytics for Firebase: app interactions, device identifiers and " +
          "crash traces, used only to improve stability. Collected, not " +
          "shared, never linked to your books — no book text or audio is " +
          "ever collected or uploaded.",
        "This collection is required for the Play listing's Data Safety " +
          "declaration and is covered by Google's privacy policy."
      )
    )
    Section(
      title = "Third-party components",
      body = listOf(
        "sherpa-onnx — Apache License 2.0 — k2-fsa (used for on-device " +
          "inference of the speech model)",
        "ONNX Runtime — MIT License — Microsoft (neural network runtime)",
        "Jetpack Compose, Media3, DataStore and other AndroidX libraries — " +
          "Apache License 2.0 — Google LLC",
        "Kotlin — Apache License 2.0 — JetBrains"
      )
    )

    LicenseBlock(title = "Supertonic-3 — BigScience OpenRAIL-M License") {
      rawText(context, R.raw.license_openrail_m)
    }
    LicenseBlock(title = "sherpa-onnx — Apache License 2.0") {
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
