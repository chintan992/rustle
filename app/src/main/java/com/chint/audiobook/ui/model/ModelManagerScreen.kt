package com.chint.audiobook.ui.model

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chint.audiobook.engine.DownloadState
import com.chint.audiobook.engine.DownloadMath
import com.chint.audiobook.engine.ModelManager
import com.chint.audiobook.engine.KokoroModelManager
import com.chint.audiobook.engine.RtfBenchmark
import com.chint.audiobook.engine.RtfBenchmarkResult
import com.chint.audiobook.engine.RtfStats

@Composable
fun ModelManagerScreen(
  downloadState: DownloadState,
  benchmarkResult: RtfBenchmarkResult?,
  packIntegrity: PackIntegrity,
  kokoroDownloadState: DownloadState,
  kokoroPackIntegrity: PackIntegrity,
  rtfStats: RtfStats,
  debugBuild: Boolean,
  debugNote: String?,
  onDownloadClick: () -> Unit,
  onClearModelData: () -> Unit,
  onDownloadKokoroClick: () -> Unit,
  onClearKokoroModelData: () -> Unit,
  onRunBenchmarkClick: () -> Unit,
  onDebugCorruptFinalPackFile: () -> Unit,
  onDebugCorruptStagedPackFile: () -> Unit,
  onBackClick: () -> Unit,
  isOnboarding: Boolean = false,
  engineInitializationState: EngineInitializationState = EngineInitializationState.Idle,
  onInitializeEngineClick: () -> Unit = {}
) {
  var showClearConfirmation by remember { mutableStateOf(false) }
  var showKokoroClearConfirmation by remember { mutableStateOf(false) }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .windowInsetsPadding(WindowInsets.safeDrawing)
      .padding(16.dp)
      .verticalScroll(rememberScrollState())
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
      IconButton(
        onClick = onBackClick,
        modifier = Modifier.semantics { contentDescription = "Back" }
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = null
        )
      }
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        if (isOnboarding) "Set up speech" else "Model Manager",
        style = MaterialTheme.typography.headlineSmall
      )
    }
    if (isOnboarding) {
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        "Step 2 of 3 - Download the voice pack to prepare Rustle's speech engine.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
    Spacer(modifier = Modifier.height(16.dp))
    Text(
      "Supertonic 3 int8 pack (sherpa-onnx) - 7 files, ~138 MB",
      style = MaterialTheme.typography.bodyMedium
    )
    Spacer(modifier = Modifier.height(16.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp)) {
        Text("Pack Status", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        when (downloadState) {
          is DownloadState.Idle -> {
            Text("Model files not downloaded")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onDownloadClick) { Text("Download Pack (~138 MB)") }
          }
          is DownloadState.WaitingForWifi -> {
            Text(downloadState.message, color = MaterialTheme.colorScheme.tertiary)
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
              modifier = Modifier.fillMaxWidth(),
              color = MaterialTheme.colorScheme.tertiary
            )
          }
          is DownloadState.Downloading -> {
            Text(
              "Downloading ${downloadState.fileIndex}/${downloadState.totalFiles}: " +
                "${downloadState.currentFile} " +
                "(${(downloadState.fileFraction * 100).toInt()}%)"
            )
            Text(
              "${downloadState.doneBytes / (1024 * 1024)} of " +
                "${downloadState.totalBytes / (1024 * 1024)} MB overall",
              style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
              progress = {
                DownloadMath.overallFraction(
                  downloadState.doneBytes, downloadState.totalBytes
                )
              },
              modifier = Modifier.fillMaxWidth()
            )
          }
          is DownloadState.Verifying -> {
            Text(downloadState.message)
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          }
          is DownloadState.Verified -> {
            Text("Model Pack Ready & Verified!")
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                "sha256 verified - the pack is safe to use",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
          is DownloadState.Error -> {
            Text("Error: ${downloadState.message}", color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onDownloadClick) { Text("Retry Download") }
          }
        }

        val integrity = packIntegrity
        if (integrity.packReady && integrity.hashMismatches.isEmpty()) {
          Text(
            "Files on disk: ${integrity.presentFiles}/${ModelManager.REQUIRED_FILES.size} " +
              "(${integrity.presentBytes / (1024 * 1024)} MB) - sha256 OK",
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        } else if (integrity.hashMismatches.isNotEmpty()) {
          Text(
            "sha256 mismatch: ${integrity.hashMismatches}",
            color = MaterialTheme.colorScheme.error
          )
        } else if (integrity.missingFiles.isNotEmpty()) {
          Text(
            "Missing: ${integrity.missingFiles}",
            color = MaterialTheme.colorScheme.error
          )
        }
      }
    }

    if (isOnboarding) {
      Spacer(modifier = Modifier.height(12.dp))
      when (val state = engineInitializationState) {
        EngineInitializationState.Idle -> Unit
        EngineInitializationState.Initializing -> {
          Text("Initializing the speech engine...", style = MaterialTheme.typography.bodyMedium)
          Spacer(modifier = Modifier.height(8.dp))
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        EngineInitializationState.Ready -> {
          Text("Speech engine ready. Opening voice selection...")
        }
        is EngineInitializationState.Failed -> {
          Text(state.message, color = MaterialTheme.colorScheme.error)
          Spacer(modifier = Modifier.height(8.dp))
          Button(onClick = onInitializeEngineClick) {
            Text("Retry engine setup")
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp)) {
        Text("Kokoro-82M v1.0 fp32", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          "English-focused secondary engine. Separate download: 4 files, about 360 MB. " +
            "Other languages continue to use Supertonic."
        )
        Spacer(modifier = Modifier.height(8.dp))
        when (kokoroDownloadState) {
          is DownloadState.Idle -> {
            Text("Kokoro model files not downloaded")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onDownloadKokoroClick) { Text("Download Kokoro (~360 MB)") }
          }
          is DownloadState.WaitingForWifi -> {
            Text(kokoroDownloadState.message, color = MaterialTheme.colorScheme.tertiary)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          }
          is DownloadState.Downloading -> {
            Text(
              "Downloading ${kokoroDownloadState.fileIndex}/${kokoroDownloadState.totalFiles}: " +
                "${kokoroDownloadState.currentFile} " +
                "(${(kokoroDownloadState.fileFraction * 100).toInt()}%)"
            )
            Text(
              "${kokoroDownloadState.doneBytes / 1_000_000} of " +
                "${kokoroDownloadState.totalBytes / 1_000_000} MB overall",
              style = MaterialTheme.typography.bodySmall
            )
            LinearProgressIndicator(
              progress = {
                DownloadMath.overallFraction(
                  kokoroDownloadState.doneBytes,
                  kokoroDownloadState.totalBytes
                )
              },
              modifier = Modifier.fillMaxWidth()
            )
          }
          is DownloadState.Verifying -> {
            Text(kokoroDownloadState.message)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          }
          is DownloadState.Verified -> {
            Text("Kokoro pack ready and SHA-256 verified.")
          }
          is DownloadState.Error -> {
            Text("Error: ${kokoroDownloadState.message}", color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onDownloadKokoroClick) { Text("Retry Kokoro Download") }
          }
        }

        if (kokoroPackIntegrity.packReady) {
          Text(
            "Files on disk: ${kokoroPackIntegrity.presentFiles}/${KokoroModelManager.REQUIRED_FILES.size} " +
              "(${kokoroPackIntegrity.presentBytes / 1_000_000} MB) - SHA-256 OK",
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        } else if (kokoroPackIntegrity.hashMismatches.isNotEmpty()) {
          Text(
            "SHA-256 mismatch: ${kokoroPackIntegrity.hashMismatches}",
            color = MaterialTheme.colorScheme.error
          )
        } else if (kokoroPackIntegrity.missingFiles.isNotEmpty()) {
          Text("Missing: ${kokoroPackIntegrity.missingFiles}", color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
          onClick = { showKokoroClearConfirmation = true },
          enabled = kokoroDownloadState !is DownloadState.Downloading &&
            kokoroDownloadState !is DownloadState.Verifying &&
            kokoroDownloadState !is DownloadState.WaitingForWifi
        ) {
          Text("Clear Kokoro model data")
        }
      }
    }

    if (showKokoroClearConfirmation) {
      AlertDialog(
        onDismissRequest = { showKokoroClearConfirmation = false },
        title = { Text("Clear Kokoro model data?") },
        text = {
          Text("This removes only the Kokoro-82M files and incomplete Kokoro downloads.")
        },
        confirmButton = {
          TextButton(
            onClick = {
              showKokoroClearConfirmation = false
              onClearKokoroModelData()
            }
          ) { Text("Clear") }
        },
        dismissButton = {
          TextButton(onClick = { showKokoroClearConfirmation = false }) { Text("Cancel") }
        }
      )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp)) {
        Text("Storage", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          "Remove downloaded model files and any incomplete download.",
          style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
          onClick = { showClearConfirmation = true },
          enabled = downloadState !is DownloadState.Downloading &&
            downloadState !is DownloadState.Verifying
        ) {
          Text("Clear model data")
        }
      }
    }

    if (showClearConfirmation) {
      AlertDialog(
        onDismissRequest = { showClearConfirmation = false },
        title = { Text("Clear model data?") },
        text = {
          Text(
            "This removes the downloaded model pack and any incomplete download. " +
              "You can download it again later."
          )
        },
        confirmButton = {
          TextButton(
            onClick = {
              showClearConfirmation = false
              onClearModelData()
            }
          ) {
            Text("Clear")
          }
        },
        dismissButton = {
          TextButton(onClick = { showClearConfirmation = false }) {
            Text("Cancel")
          }
        }
      )
    }

    if (debugBuild) {
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
          Text("RTF stats", style = MaterialTheme.typography.titleMedium)
          Spacer(modifier = Modifier.height(8.dp))
          if (rtfStats.count > 0) {
            Text(
              "RTF stats: ${rtfStats.count} chunks, mean ${"%.3f".format(rtfStats.mean)}, " +
                "p50 ${"%.3f".format(rtfStats.p50)}, p95 ${"%.3f".format(rtfStats.p95)}"
            )
            Text(
              "gate: mean and p95 <= ${RtfBenchmark.RTF_GATE}",
              style = MaterialTheme.typography.bodySmall,
              color = if (rtfStats.mean <= RtfBenchmark.RTF_GATE && rtfStats.p95 <= RtfBenchmark.RTF_GATE)
                MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.error
            )
          } else {
            Text(
              "No render_done events yet - synthesize a chapter first.",
              style = MaterialTheme.typography.bodySmall
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
          Text("Benchmark Self-Test", style = MaterialTheme.typography.titleMedium)
          Spacer(modifier = Modifier.height(8.dp))

          if (!packIntegrity.packReady) {
            Text(
              "Download the model pack first - the benchmark needs all 7 files (~145 MB).",
              color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
          }

          Button(
            onClick = onRunBenchmarkClick,
            enabled = packIntegrity.packReady &&
              downloadState !is DownloadState.Downloading &&
              downloadState !is DownloadState.Verifying &&
              downloadState !is DownloadState.WaitingForWifi
          ) {
            Text("Run RTF Benchmark Self-Test")
          }

          benchmarkResult?.let { bench ->
            Spacer(modifier = Modifier.height(12.dp))
            if (bench.initOk) {
              val verdict = if (bench.passesGate) "ENGINE READY" else "TOO SLOW FOR REALTIME (gate ${"%.2f".format(RtfBenchmark.RTF_GATE)})"
              Text(verdict, style = MaterialTheme.typography.titleMedium)
              Text("RTF: ${"%.3f".format(bench.rtf)} (gate <= 0.5)")
              Text("Cold load: ${bench.coldLoadMs} ms")
              Text("First audio: ${bench.firstAudioMs} ms")
              Text("Audio rendered: ${"%.2f".format(bench.audioDurationSec)} s in ${"%.2f".format(bench.wallDurationSec)} s")
              Text("sampleRate=${bench.sampleRate} numSpeakers=${bench.numSpeakers}")
              Text(bench.voiceMapping, style = MaterialTheme.typography.bodySmall)
            } else {
              Text("ENGINE NOT READY", color = MaterialTheme.colorScheme.error)
              Text(bench.reason, color = MaterialTheme.colorScheme.error)
              Text("No numbers are shown because the engine did not initialize.", style = MaterialTheme.typography.bodySmall)
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
          Text("Pack recovery self-tests", style = MaterialTheme.typography.titleMedium)
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            "Injects a temporary checksum failure to exercise the normal pack recovery flow. " +
              "These tests require the Supertonic pack to be installed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Spacer(modifier = Modifier.height(8.dp))
          Button(
            onClick = onDebugCorruptFinalPackFile,
            enabled = packIntegrity.packReady &&
              downloadState !is DownloadState.Downloading &&
              downloadState !is DownloadState.Verifying &&
              downloadState !is DownloadState.WaitingForWifi
          ) {
            Text("Test recovery from a corrupted installed file")
          }
          OutlinedButton(
            onClick = onDebugCorruptStagedPackFile,
            enabled = packIntegrity.packReady &&
              downloadState !is DownloadState.Downloading &&
              downloadState !is DownloadState.Verifying &&
              downloadState !is DownloadState.WaitingForWifi
          ) {
            Text("Test staged verification retry failure")
          }
          debugNote?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(16.dp))
  }
}
