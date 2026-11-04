package com.chint.audiobook.ui.import

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.chint.audiobook.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chint.audiobook.data.model.Book
import com.chint.audiobook.pipeline.ImportPipeline
import com.chint.audiobook.pipeline.ImportProgress
import com.chint.audiobook.theme.AppEpub
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

// UI Round 4 (R12): step chips mirror ImportPipeline's three phases.
private enum class StepState { UPCOMING, CURRENT, DONE }

@Composable
fun ImportScreen(
  progress: ImportProgress?,
  importedBook: Book? = null,
  defaultVoice: String = "F2",
  expressive: Boolean = true,
  // WS2: EPUB shared/opened into the app — auto-imports once, then consumes.
  initialUri: Uri? = null,
  onInitialUriConsumed: () -> Unit = {},
  // Track 2: shared text from web or external app
  initialText: SharedText? = null,
  onInitialTextConsumed: () -> Unit = {},
  onProgressUpdate: (ImportProgress) -> Unit,
  onBookImported: (Book) -> Unit,
  onStartListening: (Book) -> Unit = {},
  onBackToLibrary: () -> Unit = {},
  onBackClick: () -> Unit
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  val importPipeline = remember(context) { ImportPipeline(context) }
  var showPasteDialog by rememberSaveable { mutableStateOf(false) }
  var pasteTitle by rememberSaveable { mutableStateOf("") }
  var pasteContent by rememberSaveable { mutableStateOf("") }

  fun queryFileName(uri: Uri): String? {
    if (uri.scheme == "content") {
      runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
          val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (nameIndex != -1 && cursor.moveToFirst()) {
            return cursor.getString(nameIndex)
          }
        }
      }
    }
    return uri.path?.substringAfterLast('/')
  }

  fun importFromUri(fileUri: Uri) {
    coroutineScope.launch {
      runCatching {
        onProgressUpdate(ImportProgress.Parsing)
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
          val originalName = queryFileName(fileUri) ?: "import_temp"
          val mime = context.contentResolver.getType(fileUri)
          val extension = originalName.substringAfterLast('.', "").lowercase(Locale.ROOT)
          val safeExt = when {
            extension in listOf("epub", "txt", "md", "markdown") -> ".$extension"
            mime == "text/plain" -> ".txt"
            mime?.contains("markdown") == true -> ".md"
            else -> ".epub"
          }
          val tempFile = File(context.cacheDir, "import_temp_${System.currentTimeMillis()}$safeExt")

          try {
            context.contentResolver.openInputStream(fileUri)?.use { input ->
              FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
              }
            } ?: throw IllegalStateException("Cannot open input stream from file URI")

            importPipeline.importFile(
              file = tempFile,
              defaultVoice = defaultVoice,
              expressive = expressive,
              onProgress = onProgressUpdate
            )
          } finally {
            tempFile.delete()
          }
        }

        result.getOrThrow()
      }.onSuccess { book ->
        onBookImported(book)
      }.onFailure { error ->
        onProgressUpdate(ImportProgress.Failed(error.message ?: "Unknown import error"))
      }
    }
  }

  fun importDirectText(title: String, content: String) {
    coroutineScope.launch {
      runCatching {
        onProgressUpdate(ImportProgress.Parsing)
        val result = importPipeline.importText(
          title = title.ifBlank { "Imported Document" },
          content = content,
          defaultVoice = defaultVoice,
          expressive = expressive,
          onProgress = onProgressUpdate
        )
        result.getOrThrow()
      }.onSuccess { book ->
        onBookImported(book)
      }.onFailure { error ->
        onProgressUpdate(ImportProgress.Failed(error.message ?: "Unknown import error"))
      }
    }
  }

  LaunchedEffect(initialUri) {
    initialUri?.let { uri ->
      importFromUri(uri)
      onInitialUriConsumed()
    }
  }

  LaunchedEffect(initialText) {
    initialText?.let { shared ->
      importDirectText(shared.title ?: "Shared Article", shared.text)
      onInitialTextConsumed()
    }
  }

  val filePickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
  ) { uri: Uri? ->
    uri?.let { fileUri -> importFromUri(fileUri) }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .windowInsetsPadding(WindowInsets.safeDrawing)
      .padding(16.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically
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
      Text(stringResource(R.string.import_title), style = MaterialTheme.typography.headlineSmall)
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      stringResource(R.string.import_subtitle),
      style = MaterialTheme.typography.bodyMedium
    )

    Spacer(modifier = Modifier.height(24.dp))

    val book = importedBook
    if (book != null) {
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(20.dp)
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Icon(
            imageVector = AppEpub,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(40.dp)
          )
          Spacer(modifier = Modifier.height(12.dp))
          Text(
            stringResource(R.string.import_success),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            book.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.SemiBold
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            "${book.author} · ${book.chapters.size} chapters",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
          )
          Spacer(modifier = Modifier.height(16.dp))
          Button(
            onClick = { onStartListening(book) },
            modifier = Modifier
              .fillMaxWidth()
              .semantics { contentDescription = "Start listening to ${book.title}" }
          ) {
            Text(stringResource(R.string.import_open_in_reader))
          }
          Spacer(modifier = Modifier.height(8.dp))
          OutlinedButton(
            onClick = onBackToLibrary,
            modifier = Modifier.fillMaxWidth()
          ) {
            Text(stringResource(R.string.import_return_to_library))
          }
        }
      }
    } else {
      when (progress) {
        is ImportProgress.Parsing -> {
          ImportStepChips(parse = StepState.CURRENT, clean = StepState.UPCOMING, prepare = StepState.UPCOMING)
          Spacer(modifier = Modifier.height(12.dp))
          Text("Parsing document and structure...")
          Spacer(modifier = Modifier.height(8.dp))
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is ImportProgress.Cleaning -> {
          ImportStepChips(parse = StepState.DONE, clean = StepState.CURRENT, prepare = StepState.UPCOMING)
          Spacer(modifier = Modifier.height(12.dp))
          Text("Cleaning chapter ${progress.done} of ${progress.total}...")
          Spacer(modifier = Modifier.height(8.dp))
          LinearProgressIndicator(
            progress = { progress.done.toFloat() / progress.total },
            modifier = Modifier.fillMaxWidth()
          )
        }
        is ImportProgress.Preparing -> {
          ImportStepChips(parse = StepState.DONE, clean = StepState.DONE, prepare = StepState.CURRENT)
          Spacer(modifier = Modifier.height(12.dp))
          Text("Preparing chapter ${progress.done} of ${progress.total}...")
          Spacer(modifier = Modifier.height(8.dp))
          LinearProgressIndicator(
            progress = { progress.done.toFloat() / progress.total },
            modifier = Modifier.fillMaxWidth()
          )
        }
        is ImportProgress.Completed -> {
          Text("Finishing up...")
          Spacer(modifier = Modifier.height(8.dp))
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is ImportProgress.Failed -> {
          Text("Import Failed: ${progress.error}", color = MaterialTheme.colorScheme.error)
          Spacer(modifier = Modifier.height(8.dp))
          Button(onClick = { filePickerLauncher.launch("*/*") }) { Text("Try Again") }
        }
        null -> {
          ImportHeroCard(
            onChooseFile = { filePickerLauncher.launch("*/*") },
            onPasteText = { showPasteDialog = true }
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(16.dp))
  }

  if (showPasteDialog) {
    AlertDialog(
      onDismissRequest = { showPasteDialog = false },
      title = { Text("Paste Text or Article") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          OutlinedTextField(
            value = pasteTitle,
            onValueChange = { pasteTitle = it },
            label = { Text("Title (Optional)") },
            placeholder = { Text("e.g. Article Name, Notes") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )
          OutlinedTextField(
            value = pasteContent,
            onValueChange = { pasteContent = it },
            label = { Text("Text Content") },
            placeholder = { Text("Paste article, blog post, or story here...") },
            modifier = Modifier
              .fillMaxWidth()
              .height(180.dp)
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (pasteContent.isNotBlank()) {
              val title = pasteTitle
              val content = pasteContent
              showPasteDialog = false
              pasteTitle = ""
              pasteContent = ""
              importDirectText(title, content)
            }
          },
          enabled = pasteContent.isNotBlank()
        ) {
          Text("Import")
        }
      },
      dismissButton = {
        TextButton(onClick = { showPasteDialog = false }) {
          Text("Cancel")
        }
      }
    )
  }
}

@Composable
private fun ImportHeroCard(
  onChooseFile: () -> Unit,
  onPasteText: () -> Unit
) {
  val primary = MaterialTheme.colorScheme.primary
  Card(
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    )
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .padding(3.dp)
        .drawBehind {
          // Dashed drop-zone border, inset so the dash ring clears the card clip.
          val stroke = Stroke(
            width = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
          )
          drawRoundRect(
            color = primary,
            cornerRadius = CornerRadius(17.dp.toPx()),
            style = stroke
          )
        }
        .padding(24.dp),
      contentAlignment = Alignment.Center
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
          imageVector = AppEpub,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          "Choose an EPUB, TXT, or Markdown file — or paste text directly to turn into audio on-device.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
          onClick = onChooseFile,
          modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Choose File" }
        ) {
          Text("Choose File (EPUB, TXT, MD)")
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
          onClick = onPasteText,
          modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Paste Text or Article" }
        ) {
          Text("Paste Text or Article")
        }
      }
    }
  }
}

@Composable
private fun ImportStepChips(
  parse: StepState,
  clean: StepState,
  prepare: StepState
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    StepChip(
      label = "1. Parse",
      state = parse,
      modifier = Modifier.weight(1f)
    )
    StepChip(
      label = "2. Clean",
      state = clean,
      modifier = Modifier.weight(1f)
    )
    StepChip(
      label = "3. Prepare",
      state = prepare,
      modifier = Modifier.weight(1f)
    )
  }
}

@Composable
private fun StepChip(
  label: String,
  state: StepState,
  modifier: Modifier = Modifier
) {
  val (containerColor, contentColor) = when (state) {
    StepState.DONE -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
    StepState.CURRENT -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    StepState.UPCOMING -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
  }
  Card(
    modifier = modifier,
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(containerColor = containerColor)
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center
    ) {
      Box(modifier = Modifier.size(14.dp)) {
        if (state == StepState.DONE) {
          Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.fillMaxSize()
          )
        }
      }
      Spacer(modifier = Modifier.width(4.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = contentColor,
        fontWeight = if (state == StepState.CURRENT) FontWeight.Bold else FontWeight.Normal
      )
    }
  }
}
