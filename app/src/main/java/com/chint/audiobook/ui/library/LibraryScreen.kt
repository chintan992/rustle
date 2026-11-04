package com.chint.audiobook.ui.library

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.chint.audiobook.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chint.audiobook.data.model.Book
import com.chint.audiobook.data.model.PlaybackPosition
import com.chint.audiobook.theme.AppEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun LibraryScreen(
  books: List<Book>,
  // UI Round 4 (R2): the Continue card's book is the most recently PLAYED one
  // (position sidecars), no longer "the first book on the shelf".
  lastBook: Book?,
  // UI Round 4 (R6): per-book progress for the Continue card + shelf rows.
  positions: Map<String, PlaybackPosition>,
  isPlaying: Boolean,
  onBookClick: (Book) -> Unit,
  onImportClick: () -> Unit,
  onOpenModelManager: () -> Unit,
  onOpenSettings: () -> Unit,
  // Media round (A5): per-book storage + manage-sheet actions. Long-press (or
  // the row's ⋮) opens the sheet; delete/clear confirm before acting.
  storageBytes: Map<String, Long> = emptyMap(),
  onDeleteBook: (String) -> Unit = {},
  onClearAudioCache: (String) -> Unit = {},
  // WS2: per-book listened total for the manage sheet.
  bookListenedSeconds: Map<String, Long> = emptyMap(),
  // BG0: shown when a delete could not remove every file (e.g. WAVs pinned by
  // another process); the book may re-appear until a clean retry succeeds.
  showPartialDeleteWarning: Boolean = false,
  onPartialDeleteWarningShown: () -> Unit = {},
  // Q2: one-time OEM battery-optimization guidance.
  showBatteryGuidance: Boolean = false,
  onBatteryGuidanceDismiss: () -> Unit = {},
  onOpenBatterySettings: () -> Unit = {}
) {
  // Media round (A5): the book whose manage sheet is open (null = closed).
  var managedBook by remember { mutableStateOf<Book?>(null) }
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var isSearchActive by rememberSaveable { mutableStateOf(false) }
  var stateFilter by rememberSaveable { mutableStateOf(BookState.ALL) }
  var actionsExpanded by rememberSaveable { mutableStateOf(false) }
  val libraryListState = rememberLazyListState()
  val libraryGridState = rememberLazyGridState()
  val filterListState = rememberLazyListState()
  val isCompactHeader = LocalConfiguration.current.screenWidthDp < 420

  Scaffold(
    floatingActionButton = {
      FloatingActionButton(
        onClick = onImportClick,
        containerColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { contentDescription = "Import a book" }
      ) {
        Icon(
          imageVector = Icons.Default.Add,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimary
        )
      }
    }
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(16.dp)
    ) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Image(
            painter = painterResource(R.drawable.app_logo_mark),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier
              .size(42.dp)
              .clip(RoundedCornerShape(12.dp))
          )
          Column(
            modifier = Modifier
              .weight(1f)
              .padding(start = 10.dp)
          ) {
            Text(
              text = stringResource(R.string.library_title),
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.SemiBold
            )
            Text(
              text = stringResource(R.string.library_header_subtitle),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(
              onClick = {
                isSearchActive = !isSearchActive
                if (!isSearchActive) searchQuery = ""
              },
              modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                  if (isSearchActive) MaterialTheme.colorScheme.primaryContainer
                  else MaterialTheme.colorScheme.surfaceContainerHigh
                )
                .semantics { contentDescription = "Search books" }
            ) {
              Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = if (isSearchActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            if (!isCompactHeader) {
              IconButton(
                onClick = onOpenModelManager,
                modifier = Modifier
                  .size(48.dp)
                  .clip(RoundedCornerShape(12.dp))
                  .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                  .semantics { contentDescription = "Open model manager" }
              ) {
                Icon(
                  imageVector = AppEngine,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
              IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                  .size(48.dp)
                  .clip(RoundedCornerShape(12.dp))
                  .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                  .semantics { contentDescription = "Open settings" }
              ) {
                Icon(
                  imageVector = Icons.Default.Settings,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            } else {
              Box {
                IconButton(
                  onClick = { actionsExpanded = true },
                  modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .semantics { contentDescription = "More library actions" }
                ) {
                  Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
                DropdownMenu(
                  expanded = actionsExpanded,
                  onDismissRequest = { actionsExpanded = false }
                ) {
                  DropdownMenuItem(
                    text = { Text("Model manager") },
                    onClick = {
                      actionsExpanded = false
                      onOpenModelManager()
                    },
                    leadingIcon = {
                      Icon(AppEngine, contentDescription = null)
                    }
                  )
                  DropdownMenuItem(
                    text = { Text("Settings") },
                    onClick = {
                      actionsExpanded = false
                      onOpenSettings()
                    },
                    leadingIcon = {
                      Icon(Icons.Default.Settings, contentDescription = null)
                    }
                  )
                }
              }
            }
          }
        }
      }

      if (isSearchActive) {
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
          value = searchQuery,
          onValueChange = { searchQuery = it },
          placeholder = { Text(stringResource(R.string.library_search_placeholder)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
          trailingIcon = {
            if (searchQuery.isNotEmpty()) {
              IconButton(onClick = { searchQuery = "" }) {
                Icon(Icons.Default.Close, contentDescription = "Clear search")
              }
            }
          }
        )
      }

      Spacer(modifier = Modifier.height(16.dp))

      val isSearching = searchQuery.isNotBlank()

      // "Continue Listening" pinned card (hidden while searching). The same
      // book remains in the shelf below so a single-book library is still
      // browsable and manageable from both surfaces.
      if (!isSearching) {
        lastBook?.let { book ->
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier
              .fillMaxWidth()
              .combinedClickable(
                role = Role.Button,
                onClick = { onBookClick(book) },
                onLongClick = { managedBook = book }
              )
              .semantics { contentDescription = "Continue listening to ${book.title} by ${book.author}" }
          ) {
            Row(
              modifier = Modifier.padding(16.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text("Continue Listening", style = MaterialTheme.typography.labelMedium)
                Text(book.title, style = MaterialTheme.typography.titleLarge)
                Text(book.author, style = MaterialTheme.typography.bodyMedium)
                // UI Round 4 (R2): honest progress line, chapter + percent.
                BookProgress.summary(book, positions[book.slug])?.let { summary ->
                  Spacer(modifier = Modifier.height(2.dp))
                  Text(summary, style = MaterialTheme.typography.bodySmall)
                }
              }
              // ElevenReader plan (B2): circular amber play button (the orb), not a
              // bare glyph — matches their circular-icon language.
              Box(
                modifier = Modifier
                  .size(48.dp)
                  .clip(CircleShape)
                  .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = Icons.Default.PlayArrow,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onPrimary
                )
              }
            }
          }
          Spacer(modifier = Modifier.height(16.dp))
        }
      }

      if (books.isEmpty()) {        // Warm, actionable empty state instead of a lone gray sentence.
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
              painter = painterResource(R.drawable.app_logo_card),
              contentDescription = stringResource(R.string.app_name),
              modifier = Modifier
                .size(108.dp)
                .clip(RoundedCornerShape(24.dp))
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
              text = stringResource(R.string.library_shelf_empty),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = stringResource(R.string.library_shelf_empty_desc),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            FilledTonalButton(onClick = onImportClick) {
              Text(stringResource(R.string.library_import_first_book))
            }
          }
        }
      } else {
        val baseBooks = if (isSearching) {
          val q = searchQuery.trim().lowercase()
          books.filter {
            val langCode = it.language.lowercase()
            val langName = com.chint.audiobook.core.LanguageCatalog.displayName(it.language).lowercase()
            it.title.lowercase().contains(q) ||
              it.author.lowercase().contains(q) ||
              langCode.contains(q) ||
              langName.contains(q)
          }
        } else {
          // Keep the Continue book in the shelf as well. The hero is a
          // shortcut to resume playback, not a replacement for the library
          // item.
          books
        }

        if (isSearching) {
          Text(
            text = stringResource(R.string.library_search_results, baseBooks.size),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
          )
          Spacer(modifier = Modifier.height(8.dp))
        } else {
          // Q3: new/started/finished state filter (derived from position sidecars).
          LazyRow(
            modifier = Modifier.fillMaxWidth(),
            state = filterListState,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp)
          ) {
            items(BookState.entries) { state ->
              val count = baseBooks.count { bookStateOf(it, positions) == state }
              FilterChip(
                selected = stateFilter == state,
                onClick = { stateFilter = state },
                label = { Text(state.label + if (state != BookState.ALL) " ($count)" else "") }
              )
            }
          }
        }

        val visibleBooks = if (isSearching) baseBooks else {
          baseBooks.filter { stateFilter == BookState.ALL || bookStateOf(it, positions) == stateFilter }
        }

        if (isSearching && visibleBooks.isEmpty()) {
          Box(
            modifier = Modifier
              .weight(1f)
              .fillMaxWidth(),
            contentAlignment = Alignment.Center
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text(
                text = stringResource(R.string.library_no_audiobooks_found),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = stringResource(R.string.library_no_matches_for, searchQuery),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        } else if (!isSearching && visibleBooks.isEmpty()) {
          Box(
            modifier = Modifier
              .weight(1f)
              .fillMaxWidth(),
            contentAlignment = Alignment.Center
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier.padding(horizontal = 24.dp)
            ) {
              Text(
                text = stringResource(R.string.library_caught_up_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = stringResource(R.string.library_caught_up_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
              )
              Spacer(modifier = Modifier.height(16.dp))
              FilledTonalButton(onClick = onImportClick) {
                Text(stringResource(R.string.library_add_books))
              }
            }
          }
        } else {
          // UI Round 4 (R7): 2-column grid on wide screens, single list on phones.
          val isWide = LocalConfiguration.current.screenWidthDp >= 600
          if (isWide) {
            LazyVerticalGrid(
              columns = GridCells.Adaptive(minSize = 300.dp),
              state = libraryGridState,
              modifier = Modifier.weight(1f),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              gridItems(visibleBooks, key = { it.slug }) { book ->
                BookShelfRow(
                  book = book,
                  position = positions[book.slug],
                  storageBytes = storageBytes[book.slug],
                  onBookClick = onBookClick,
                  onManage = { managedBook = book }
                )
              }
            }
          } else {
            LazyColumn(
              modifier = Modifier.weight(1f),
              state = libraryListState,
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              items(visibleBooks, key = { it.slug }) { book ->
                BookShelfRow(
                  book = book,
                  position = positions[book.slug],
                  storageBytes = storageBytes[book.slug],
                  onBookClick = onBookClick,
                  onManage = { managedBook = book }
                )
              }
            }
          }
        }
      }
    }
  }

  // Media round (A5): the manage sheet lives outside the Scaffold so it can
  // overlay regardless of scroll state; confirmations gate both destructive
  // actions (clear cache re-synthesizes; delete is irreversible).
  managedBook?.let { book ->
    ManageBookSheet(
      book = book,
      storageBytes = storageBytes[book.slug],
      listenedSeconds = bookListenedSeconds[book.slug] ?: 0L,
      onDismiss = { managedBook = null },
      onClearAudioCache = {
        onClearAudioCache(book.slug)
        managedBook = null
      },
      onDeleteBook = {
        onDeleteBook(book.slug)
        managedBook = null
      },
      onOpenBook = {
        managedBook = null
        onBookClick(book)
      }
    )
  }
  // Q2: OEM battery managers (MIUI et al.) kill long background synthesis —
  // exactly what prepare-ahead needs. One-time prompt pointing at the
  // app-details screen (Battery → No restrictions); generic across OEMs.
  if (showBatteryGuidance) {
    AlertDialog(
      onDismissRequest = onBatteryGuidanceDismiss,
      title = { Text(stringResource(R.string.library_keep_listening_bg_title)) },
      text = { Text(stringResource(R.string.library_keep_listening_bg_text)) },
      confirmButton = {
        TextButton(onClick = {
          onOpenBatterySettings()
          onBatteryGuidanceDismiss()
        }) { Text(stringResource(R.string.library_open_app_info)) }
      },
      dismissButton = {
        TextButton(onClick = onBatteryGuidanceDismiss) { Text(stringResource(R.string.common_dismiss)) }
      }
    )
  }
  if (showPartialDeleteWarning) {
    AlertDialog(
      onDismissRequest = onPartialDeleteWarningShown,
      title = { Text(stringResource(R.string.library_partial_delete_title)) },
      text = { Text(stringResource(R.string.library_partial_delete_text)) },
      confirmButton = {
        TextButton(onClick = onPartialDeleteWarningShown) { Text(stringResource(R.string.common_ok)) }
      }
    )
  }
}

/**
 * Media round (A5): long-press/⋮ management sheet — cached-audio size,
 * clear-cache and delete-from-device, both behind M3 confirm dialogs. Delete
 * also destroys bookmarks/position sidecars (they live inside books/<slug>/),
 * which the confirm copy states plainly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManageBookSheet(
  book: Book,
  storageBytes: Long?,
  listenedSeconds: Long = 0L,
  onDismiss: () -> Unit,
  onClearAudioCache: () -> Unit,
  onDeleteBook: () -> Unit,
  onOpenBook: () -> Unit
) {
  var confirmClear by remember { mutableStateOf(false) }
  var confirmDelete by remember { mutableStateOf(false) }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
      Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
      Text(book.author, style = MaterialTheme.typography.bodyMedium)
      Text(
        "${book.chapters.size} chapters · ${com.chint.audiobook.core.LanguageCatalog.displayName(book.language)} · ${formatBytes(storageBytes ?: 0L)} rendered audio" +
          if (listenedSeconds > 0L) {
            " · listened ${com.chint.audiobook.engine.ListeningStatsRepository.formatDuration(listenedSeconds)}"
          } else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Spacer(modifier = Modifier.height(16.dp))
      ListItem(
        headlineContent = { Text(stringResource(R.string.library_open_book)) },
        leadingContent = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
        modifier = Modifier
          .clip(RoundedCornerShape(12.dp))
          .clickable(onClick = onOpenBook)
      )
      ListItem(
        headlineContent = { Text(stringResource(R.string.library_clear_rendered_audio)) },
        supportingContent = { Text(stringResource(R.string.library_clear_audio_desc)) },
        leadingContent = { Icon(Icons.Default.Refresh, contentDescription = null) },
        modifier = Modifier
          .clip(RoundedCornerShape(12.dp))
          .clickable(onClick = { confirmClear = true })
      )
      ListItem(
        headlineContent = { Text(stringResource(R.string.library_delete_from_device)) },
        supportingContent = { Text(stringResource(R.string.library_delete_from_device_desc)) },
        leadingContent = { Icon(Icons.Default.Delete, contentDescription = null) },
        modifier = Modifier
          .clip(RoundedCornerShape(12.dp))
          .clickable(onClick = { confirmDelete = true })
      )
      Spacer(modifier = Modifier.height(24.dp))
    }
  }

  if (confirmClear) {
    AlertDialog(
      onDismissRequest = { confirmClear = false },
      title = { Text(stringResource(R.string.library_clear_audio_title)) },
      text = { Text(stringResource(R.string.library_clear_audio_message, book.title)) },
      confirmButton = {
        TextButton(onClick = { confirmClear = false; onClearAudioCache() }) { Text(stringResource(R.string.common_clear)) }
      },
      dismissButton = {
        TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) }
      }
    )
  }

  if (confirmDelete) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text(stringResource(R.string.library_delete_book_title, book.title)) },
      text = { Text(stringResource(R.string.library_delete_book_message)) },
      confirmButton = {
        TextButton(onClick = { confirmDelete = false; onDeleteBook() }) { Text(stringResource(R.string.common_delete)) }
      },
      dismissButton = {
        TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) }
      }
    )
  }
}

/** Media round (A5): human storage sizes for the manage sheet row. */
internal fun formatBytes(bytes: Long): String = when {
  bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
  bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
  bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
  else -> "$bytes B"
}

/**
 * UI Round 4 (R7): shelf row — [cover 56×80dp] [title / author / progress].
 * Progress stays null-honest: no summary line and no bar when no position
 * sidecar exists for the book.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookShelfRow(
  book: Book,
  position: PlaybackPosition?,
  storageBytes: Long?,
  onBookClick: (Book) -> Unit,
  onManage: () -> Unit
) {
  Card(
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ),
    modifier = Modifier
      .fillMaxWidth()
      .combinedClickable(
        role = Role.Button,
        onClick = { onBookClick(book) },
        onLongClick = onManage
      )
  ) {
    Row(
      modifier = Modifier.padding(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      BookCover(book = book, modifier = Modifier.size(width = 56.dp, height = 80.dp))
      Spacer(modifier = Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          book.title,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
        Text(book.author, style = MaterialTheme.typography.bodyMedium)
        Text(
          text = if (storageBytes != null && storageBytes > 0L) {
            "${book.chapters.size} ch · ${book.language.uppercase()} · ${formatBytes(storageBytes)}"
          } else {
            "${book.chapters.size} ch · ${book.language.uppercase()}"
          },
          style = MaterialTheme.typography.bodySmall
        )
        // UI Round 4 (R6): per-book progress line when a position exists.
        BookProgress.summary(book, position)?.let { summary ->
          Spacer(modifier = Modifier.height(2.dp))
          Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        // UI Round 4 (R7): 3dp honest progress bar under the text block.
        BookProgress.fraction(book, position)?.let { fraction ->
          Spacer(modifier = Modifier.height(6.dp))
          LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
              .fillMaxWidth()
              .height(3.dp)
              .clip(RoundedCornerShape(2.dp)),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
          )
        }
      }
      // Media round (A5): explicit affordance alongside the long-press.
      IconButton(
        onClick = onManage,
        modifier = Modifier.semantics { contentDescription = "Manage ${book.title}" }
      ) {
        Icon(
          imageVector = Icons.Default.MoreVert,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}

/** UI Round 4 (R7): decoded cover art, or a deterministic gradient placeholder. */
@Composable
private fun BookCover(book: Book, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val coverFile = remember(book.slug, book.coverPath) {
    // coverPath is stored relative to the book dir (ImportPipeline writes "cover.jpg").
    book.coverPath?.let { relative -> File(context.filesDir, "books/${book.slug}/$relative") }
  }
  val coverBitmap by produceState<ImageBitmap?>(initialValue = null, coverFile) {
    value = coverFile?.let { file ->
      withContext(Dispatchers.IO) {
        file.takeIf { it.exists() }
          ?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
      }
    }
  }
  val bitmap = coverBitmap
  if (bitmap != null) {
    Image(
      bitmap = bitmap,
      contentDescription = "Cover of ${book.title}",
      modifier = modifier.clip(RoundedCornerShape(12.dp)),
      contentScale = ContentScale.Crop
    )
  } else {
    CoverPlaceholder(
      title = book.title,
      slug = book.slug,
      modifier = modifier.clip(RoundedCornerShape(12.dp))
    )
  }
}

/** 1-of-3 amber/sand/terracotta gradient pair picked deterministically by slug. */
@Composable
private fun CoverPlaceholder(title: String, slug: String, modifier: Modifier = Modifier) {
  val scheme = MaterialTheme.colorScheme
  // and-mask keeps the pick stable even for Int.MIN_VALUE hashCode.
  val (start, end, contentColor) = when ((slug.hashCode() and 0x7fffffff) % 3) {
    0 -> Triple(scheme.primary, scheme.tertiary, scheme.onPrimary)
    1 -> Triple(scheme.tertiary, scheme.secondary, scheme.onTertiary)
    else -> Triple(scheme.secondary, scheme.primary, scheme.onSecondary)
  }
  Box(
    modifier = modifier.background(Brush.linearGradient(listOf(start, end))),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text = titleInitials(title),
      style = MaterialTheme.typography.titleMedium,
      color = contentColor,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center
    )
  }
}

private fun titleInitials(title: String): String =
  title.split(Regex("\\s+"))
    .filter { it.isNotBlank() }
    .take(2)
    .map { it.first().uppercaseChar() }
    .joinToString("")


/** Q3: derived shelf state — NEW (no position), FINISHED (>= 99.5%), STARTED. */
enum class BookState(val label: String) { ALL("All"), NEW("New"), STARTED("Started"), FINISHED("Finished") }

internal fun bookStateOf(book: Book, positions: Map<String, PlaybackPosition>): BookState {
  val position = positions[book.slug] ?: return BookState.NEW
  val fraction = BookProgress.fraction(book, position)
  return if (fraction != null && fraction >= 0.995f) BookState.FINISHED else BookState.STARTED
}
