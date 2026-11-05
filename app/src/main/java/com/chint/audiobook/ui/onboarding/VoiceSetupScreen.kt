package com.chint.audiobook.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chint.audiobook.R
import com.chint.audiobook.engine.ModelManager
import com.chint.audiobook.engine.KokoroModelManager
import com.chint.audiobook.engine.KokoroTtsEngineManager
import com.chint.audiobook.engine.TtsEngineManager
import com.chint.audiobook.data.model.TtsEngine
import kotlinx.coroutines.CancellationException
import com.chint.audiobook.playback.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun VoiceSetupScreen(
  engine: TtsEngine = TtsEngine.SUPERTONIC,
  isOnboarding: Boolean = false,
  onPreviewVoice: (suspend (text: String, voice: String) -> ByteArray)? = null,
  onComplete: (selectedVoice: String, highlightEnabled: Boolean) -> Unit,
  onSkip: () -> Unit
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  val engineManager = remember(context) { TtsEngineManager(context) }
  val kokoroEngineManager = remember(context) { KokoroTtsEngineManager() }
  val kokoroModelManager = remember(context) { KokoroModelManager(context) }
  val playbackController = remember(context) { PlaybackController(context) }
  DisposableEffect(playbackController, engineManager, kokoroEngineManager) {
    onDispose {
      playbackController.release()
      engineManager.release()
      kokoroEngineManager.release()
    }
  }
  val modelDir = remember(context, engine) {
    if (engine == TtsEngine.KOKORO) kokoroModelManager.getModelDir()
    else ModelManager(context).getModelDir()
  }

  val voicePresets = VoiceCatalog.presetsFor(engine)
  var selectedVoice by rememberSaveable(engine) {
    mutableStateOf(VoiceCatalog.defaultId(engine))
  }
  var highlightEnabled by rememberSaveable { mutableStateOf(true) }
  var previewError by rememberSaveable { mutableStateOf<String?>(null) }
  val voiceGridState = rememberLazyGridState()

  Column(
    modifier = Modifier
      .fillMaxSize()
      .windowInsetsPadding(WindowInsets.safeDrawing)
      .padding(16.dp)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Image(
        painter = painterResource(R.drawable.app_logo_card),
        contentDescription = stringResource(R.string.app_name),
        modifier = Modifier
          .size(48.dp)
          .clip(RoundedCornerShape(12.dp))
      )
      Column {
        Text(
          text = stringResource(R.string.onboarding_choose_narrator),
          style = MaterialTheme.typography.headlineMedium
        )
      }
    }
    if (isOnboarding) {
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        "Step 3 of 3 · Choose your default narrator.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = stringResource(R.string.onboarding_narrator_subtitle),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(16.dp))

    LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = 280.dp),
      state = voiceGridState,
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      items(voicePresets) { preset ->
        val id = preset.id
        val isSelected = (id == selectedVoice)
        Card(
          colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
          ),
          modifier = Modifier
            .fillMaxWidth()
            .clickable(
              role = Role.Button,
              onClick = { selectedVoice = id }
            )
            .semantics {
              contentDescription =
                if (isSelected) "Voice ${preset.name}, currently selected" else "Select voice ${preset.name}"
            }
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(text = preset.name, style = MaterialTheme.typography.titleMedium)
              Text(
                text = preset.description,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
              )
            }
            if (isSelected) {
              Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
              )
            }
            IconButton(
              onClick = {
                coroutineScope.launch {
                  val previewSampleText = "Hello! This is ${preset.name}."
                  try {
                    // OfflineTts init + generate are CPU-heavy native calls:
                    // never run them on the UI thread.
                    val previewFile = withContext(Dispatchers.IO) {
                      val wavBytes = onPreviewVoice?.invoke(previewSampleText, id)
                        ?: if (engine == TtsEngine.KOKORO) {
                          kokoroEngineManager.generateAudioWav(previewSampleText, id, modelDir)
                        } else {
                          engineManager.generateAudioWav(previewSampleText, id, modelDir)
                        }
                      val file = File(context.cacheDir, "voice_preview_${engine.name}_$id.wav")
                      file.writeBytes(wavBytes)
                      file
                    }
                    playbackController.appendSegmentWav(previewFile)
                    playbackController.play()
                    previewError = null
                  } catch (cancelled: CancellationException) {
                    throw cancelled
                  } catch (error: Exception) {
                    previewError = error.message ?: "Voice preview failed"
                  }
                }
              },
              modifier = Modifier.semantics { contentDescription = "Preview voice $id" }
            ) {
              Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }
      }
    }

    previewError?.let {
      Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      Spacer(modifier = Modifier.height(8.dp))
    }

    Spacer(modifier = Modifier.height(16.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Text(
        text = stringResource(R.string.onboarding_highlight_sentence),
        style = MaterialTheme.typography.bodyLarge
      )
      Switch(
        checked = highlightEnabled,
        onCheckedChange = { highlightEnabled = it },
        modifier = Modifier.semantics { contentDescription = if (highlightEnabled) "Sentence highlight enabled" else "Sentence highlight disabled" }
      )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      if (!isOnboarding) {
        TextButton(
          onClick = onSkip,
          modifier = Modifier
            .weight(1f)
            .semantics { contentDescription = "Skip onboarding" }
        ) {
          Text(stringResource(R.string.onboarding_skip))
        }
      }
      Button(
        onClick = { onComplete(selectedVoice, highlightEnabled) },
        modifier = Modifier
          .weight(1f)
          .semantics { contentDescription = "Continue with selected voice" }
      ) {
        Text(stringResource(R.string.onboarding_continue))
      }
    }
  }
}
