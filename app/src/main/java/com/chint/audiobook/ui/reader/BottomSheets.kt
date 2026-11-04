package com.chint.audiobook.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chint.audiobook.R
import com.chint.audiobook.data.model.Bookmark
import com.chint.audiobook.data.model.ChapterRef
import com.chint.audiobook.playback.PlaybackSession
import com.chint.audiobook.data.model.ReaderFontFamily
import com.chint.audiobook.data.model.ReaderLineSpacing
import com.chint.audiobook.theme.toFontFamily
import com.chint.audiobook.ui.onboarding.VoiceCatalog
import com.chint.audiobook.data.model.TtsEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentsSheet(
  chapters: List<ChapterRef>,
  currentChapterIndex: Int,
  // UI Round 4 (R11): book-level progress line from the R6 sidecars (may be null).
  bookProgressSummary: String? = null,
  // Cache/prep round (B3): per-chapter rendered-audio readiness + live
  // "preparing…" marker for the chapter the background preloader is working on.
  chaptersWithAudio: Set<Int> = emptySet(),
  preparingChapterIndex: Int? = null,
  // WS2: measured duration of cached chapters; others get a word-count estimate.
  chapterDurationsMs: Map<Int, Long> = emptyMap(),
  onChapterSelect: (Int) -> Unit,
  onDismiss: () -> Unit
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(stringResource(R.string.reader_table_of_contents), style = MaterialTheme.typography.titleLarge)
      // UI Round 4 (R11): book-level percent header when a position exists.
      if (bookProgressSummary != null) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = bookProgressSummary,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      Spacer(modifier = Modifier.height(16.dp))

      val listState = rememberLazyListState()
      // UI Round 4 (R11): open on the current chapter — a long book used to open
      // at chapter 1. Map by ChapterRef.index, which may be non-contiguous.
      LaunchedEffect(chapters, currentChapterIndex) {
        val currentRow = chapters.indexOfFirst { it.index == currentChapterIndex }
        if (currentRow >= 0) listState.scrollToItem(currentRow)
      }
      LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(chapters, key = { it.index }) { ch ->
          val isCurrent = (ch.index == currentChapterIndex)
          Card(
            colors = CardDefaults.cardColors(
              containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
              else MaterialTheme.colorScheme.surfaceContainerLow
            ),
            modifier = Modifier
              .fillMaxWidth()
              .clickable(role = Role.Button) {
                onChapterSelect(ch.index)
                onDismiss()
              }
              .semantics { contentDescription = "Chapter ${ch.index}: ${ch.title}" }
            ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) {
                  // UI Round 4 (R11): amber "listening" dot on the current chapter.
                  Box(
                    modifier = Modifier
                      .size(8.dp)
                      .clip(CircleShape)
                      .background(MaterialTheme.colorScheme.primary)
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                  text = "${ch.index}. ${ch.title}",
                  style = if (isCurrent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                  color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
              }
              // Cache/prep round (B3): right-side cluster — duration (WS2)
              // plus the readiness indicator (live "preparing…" under
              // synthesis, else a dot for any chapter with rendered audio).
              Row(verticalAlignment = Alignment.CenterVertically) {
                val durationLabel = formatChapterDuration(
                  chapterIndex = ch.index,
                  wordCount = ch.wordCount,
                  measuredMs = chapterDurationsMs[ch.index]
                )
                if (durationLabel != null) {
                  Text(
                    text = durationLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                }
                if (preparingChapterIndex == ch.index) {
                  Text(
                    text = stringResource(R.string.reader_preparing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                  )
                } else if (ch.index in chaptersWithAudio) {
                  Box(
                    modifier = Modifier
                      .size(6.dp)
                      .clip(CircleShape)
                      .background(MaterialTheme.colorScheme.tertiary)
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSheet(
  currentVoice: String,
  ttsEngine: TtsEngine = TtsEngine.SUPERTONIC,
  // Multi-voice: current dialogue voice (null = off) + pick callback.
  currentDialogueVoice: String? = null,
  onDialogueVoiceSelect: (String?, applyToAllBooks: Boolean) -> Unit = { _, _ -> },
  // WS3: scope picker — "This book" persists books/<slug>/prefs.json,
  // "All books" updates the global default (previous behavior).
  onVoiceSelect: (String, applyToAllBooks: Boolean) -> Unit,
  currentLanguage: String = "en",
  onLanguageSelect: (String) -> Unit = {},
  onDismiss: () -> Unit
) {
  var applyToAllBooks by rememberSaveable { mutableStateOf(false) }
  // Production-audit fix: Narration | Dialogue tabs — one compact 10-row
  // list at a time instead of a 21-row stacked sheet.
  var tab by rememberSaveable { mutableStateOf(VoiceTab.NARRATION) }
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(stringResource(R.string.reader_change_narrator), style = MaterialTheme.typography.titleLarge)

      ApplyScopeRow(applyToAllBooks = applyToAllBooks, onScopeChange = { applyToAllBooks = it })

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        FilterChip(
          selected = tab == VoiceTab.NARRATION,
          onClick = { tab = VoiceTab.NARRATION },
          label = { Text(stringResource(R.string.reader_tab_narration)) }
        )
        FilterChip(
          selected = tab == VoiceTab.DIALOGUE,
          onClick = { tab = VoiceTab.DIALOGUE },
          label = { Text(stringResource(R.string.reader_tab_dialogue)) }
        )
        FilterChip(
          selected = tab == VoiceTab.LANGUAGE,
          onClick = { tab = VoiceTab.LANGUAGE },
          label = { Text(stringResource(R.string.reader_tab_language)) }
        )
      }

      when (tab) {
        VoiceTab.NARRATION -> {
          Text(
            stringResource(R.string.reader_voice_changes_apply),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Spacer(modifier = Modifier.height(8.dp))
          LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(VoiceCatalog.presetsFor(ttsEngine)) { preset ->
              val id = preset.id
              val isCurrent = (id == currentVoice)
              Card(
                colors = CardDefaults.cardColors(
                  containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                  else MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable(role = Role.Button) {
                    onVoiceSelect(id, applyToAllBooks)
                    onDismiss()
                  }
                  .semantics { contentDescription = "Voice ${preset.name}" }
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth().padding(12.dp),
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  // UI Round 4 (R11): initials avatar, deterministic color per voice id.
                  VoiceAvatar(voiceId = id)
                  Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      Text(
                        preset.name,
                        style = MaterialTheme.typography.titleMedium
                      )
                      if (preset.isDefault) {
                        Text(
                          "  ·  " + stringResource(R.string.reader_default_voice),
                          style = MaterialTheme.typography.labelMedium,
                          color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                      }
                    }
                    Text(
                      preset.description,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                  if (isCurrent) {
                    Text(
                      stringResource(R.string.reader_active_voice),
                      style = MaterialTheme.typography.labelMedium,
                      color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                  }
                }
              }
            }
          }
        }
        VoiceTab.DIALOGUE -> {
          Text(
            stringResource(R.string.reader_dialogue_voice_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Spacer(modifier = Modifier.height(8.dp))
          LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
              val isOff = currentDialogueVoice == null
              Card(
                colors = CardDefaults.cardColors(
                  containerColor = if (isOff) MaterialTheme.colorScheme.primaryContainer
                  else MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable(role = Role.Button) {
                    onDialogueVoiceSelect(null, applyToAllBooks)
                    onDismiss()
                  }
                  .semantics { contentDescription = "Dialogue voice same as narration (off)" }
              ) {
                Text(
                  stringResource(R.string.reader_dialogue_voice_off),
                  modifier = Modifier.fillMaxWidth().padding(12.dp)
                )
              }
            }
            items(VoiceCatalog.presetsFor(ttsEngine)) { preset ->
              val id = preset.id
              val isCurrent = (id == currentDialogueVoice)
              Card(
                colors = CardDefaults.cardColors(
                  containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                  else MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable(role = Role.Button) {
                    onDialogueVoiceSelect(id, applyToAllBooks)
                    onDismiss()
                  }
                  .semantics { contentDescription = "Dialogue voice ${preset.name}" }
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth().padding(12.dp),
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  VoiceAvatar(voiceId = id)
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      preset.name,
                      style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                      preset.description,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                  if (isCurrent) {
                    Text(
                      stringResource(R.string.reader_active_voice),
                      style = MaterialTheme.typography.labelMedium,
                      color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                  }
                }
              }
            }
          }
        }
        VoiceTab.LANGUAGE -> {
          Text(
            "Current: ${com.chint.audiobook.core.LanguageCatalog.displayName(currentLanguage)} (${currentLanguage.uppercase()})",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            "Supertonic 3 supports 31 languages on-device. Changing language re-prepares chapter text.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Spacer(modifier = Modifier.height(8.dp))
          LazyColumn(
            modifier = Modifier.height(340.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            item {
              Card(
                colors = CardDefaults.cardColors(
                  containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable(role = Role.Button) {
                    onLanguageSelect(com.chint.audiobook.core.LanguageCatalog.AUTO)
                    onDismiss()
                  }
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth().padding(12.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-detect", style = MaterialTheme.typography.titleMedium)
                    Text(
                      "Re-detect language from book chapters",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                }
              }
            }
            items(com.chint.audiobook.core.LanguageCatalog.ALL.entries.toList()) { (code, name) ->
              val isCurrent = code.equals(currentLanguage, ignoreCase = true)
              Card(
                colors = CardDefaults.cardColors(
                  containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                  else MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable(role = Role.Button) {
                    onLanguageSelect(code)
                    onDismiss()
                  }
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth().padding(12.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(name, style = MaterialTheme.typography.titleMedium)
                  Text(
                    code.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }
            }
          }
        }
      }
      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}

private enum class VoiceTab { NARRATION, DIALOGUE, LANGUAGE }

/**
 * WS3: "applies to" toggle shared by the voice/speed sheets — This book
 * (default) writes books/<slug>/prefs.json; All books writes the global
 * setting (the pre-WS3 behavior).
 */
@Composable
internal fun ApplyScopeRow(applyToAllBooks: Boolean, onScopeChange: (Boolean) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    FilterChip(
      selected = !applyToAllBooks,
      onClick = { onScopeChange(false) },
      label = { Text(stringResource(R.string.reader_this_book)) }
    )
    FilterChip(
      selected = applyToAllBooks,
      onClick = { onScopeChange(true) },
      label = { Text(stringResource(R.string.reader_all_books)) }
    )
  }
}

/** UI Round 4 (R11): 32dp avatar circle, color picked deterministically by id. */
@Composable
private fun VoiceAvatar(voiceId: String) {
  val scheme = MaterialTheme.colorScheme
  val (bg, content) = when ((voiceId.hashCode() and 0x7fffffff) % 3) {
    0 -> scheme.primary to scheme.onPrimary
    1 -> scheme.secondary to scheme.onSecondary
    else -> scheme.tertiary to scheme.onTertiary
  }
  Box(
    modifier = Modifier
      .size(32.dp)
      .clip(CircleShape)
      .background(bg),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text = voiceId.take(1).uppercase(),
      style = MaterialTheme.typography.labelLarge,
      color = content,
      fontWeight = FontWeight.SemiBold
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpeedSheet(
  currentSpeed: Float,
  skipSilence: Boolean = false,
  onSkipSilenceToggle: (Boolean) -> Unit = {},
  // WS3: "This book" persists the override; "All books" keeps the global behavior.
  onSpeedSelect: (Float, applyToAllBooks: Boolean) -> Unit,
  onDismiss: () -> Unit
) {
  var applyToAllBooks by rememberSaveable { mutableStateOf(false) }
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(stringResource(R.string.reader_playback_speed), style = MaterialTheme.typography.titleLarge)

      ApplyScopeRow(applyToAllBooks = applyToAllBooks, onScopeChange = { applyToAllBooks = it })
      Text(
        text = "Currently ${"%.2f".format(currentSpeed)}x — player-side, pitch-corrected, applies to the current chunk immediately.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Spacer(modifier = Modifier.height(16.dp))

      // ElevenReader plan (B2): chip grid replaces the slider — one tap per speed,
      // selected chip highlighted by the theme (amber family on both sides).
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        for (step in 0..15) {
          val speedOption = 0.25f + (step * 0.25f)
          FilterChip(
            selected = (speedOption == currentSpeed),
            onClick = {
              onSpeedSelect(speedOption, applyToAllBooks)
              onDismiss()
            },
            label = { Text("${"%.2f".format(speedOption)}x") }
          )
        }
      }

      Spacer(modifier = Modifier.height(20.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Skip silence", style = MaterialTheme.typography.titleMedium)
          Text(
            "Skip quiet pauses in narration automatically",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        Switch(
          checked = skipSilence,
          onCheckedChange = onSkipSilenceToggle
        )
      }
    }
  }
}

/**
 * ElevenReader plan (A2): bookmark list — tap a row to seek (exact chapter +
 * sentence + offset), Close to delete. The ribbon STILL added the bookmark via
 * the transport button; this sheet only navigates/removes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
  bookmarks: List<Bookmark>,
  currentChapterIndex: Int,
  onSeekToBookmark: (Bookmark) -> Unit,
  onDeleteBookmark: (Bookmark) -> Unit,
  // Q7: portability.
  onExport: () -> Unit = {},
  onImportClick: () -> Unit = {},
  onDismiss: () -> Unit
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(stringResource(R.string.reader_bookmarks), style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          // Q7: bookmarks are portable — export via share sheet, import via picker.
          TextButton(onClick = onExport) { Text(stringResource(R.string.reader_export)) }
          TextButton(onClick = onImportClick) { Text(stringResource(R.string.reader_import)) }
        }
      }
      Spacer(modifier = Modifier.height(16.dp))

      if (bookmarks.isEmpty()) {
        Text(
          text = stringResource(R.string.reader_no_bookmarks),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          items(bookmarks) { bookmark ->
            Card(
              colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
              ),
              modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) {
                  onSeekToBookmark(bookmark)
                  onDismiss()
                }
                .semantics { contentDescription = "Bookmark: ${bookmark.label}" }
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = bookmark.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                  )
                  Text(
                    text = "Chapter ${bookmark.chapterIndex}" +
                      if (bookmark.chapterIndex == currentChapterIndex) " — this chapter" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
                IconButton(
                  onClick = { onDeleteBookmark(bookmark) },
                  modifier = Modifier.semantics { contentDescription = "Delete bookmark" }
                ) {
                  Icon(imageVector = Icons.Default.Close, contentDescription = null)
                }
              }
            }
          }
        }
      }
    }
  }
}

/**
 * ElevenReader plan (A4): sleep timer sheet — fixed durations, chapter-end mode,
 * and a cancel row while a timer is running.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepSheet(
  sleepTimerEndAt: Long,
  onSleepStart: (Long) -> Unit,
  onSleepToEndOfChapter: () -> Unit,
  onSleepCancel: () -> Unit,
  onDismiss: () -> Unit
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(stringResource(R.string.reader_sleep_timer), style = MaterialTheme.typography.titleLarge)
      Spacer(modifier = Modifier.height(16.dp))

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (minutes in listOf(5, 15, 30, 60)) {
          FilledTonalButton(
            onClick = {
              onSleepStart(minutes.toLong() * 60_000L)
              onDismiss()
            },
            modifier = Modifier.semantics { contentDescription = "Sleep in $minutes minutes" }
          ) {
            Text("$minutes min")
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))
      Button(
        onClick = {
          onSleepToEndOfChapter()
          onDismiss()
        },
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(stringResource(R.string.reader_pause_at_chapter_end))
      }

      if (sleepTimerEndAt != 0L) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = if (sleepTimerEndAt == PlaybackSession.SLEEP_TIMER_END_OF_CHAPTER) {
            "Sleep timer active — pauses at the end of this chapter"
          } else {
            "Sleep timer active — pauses in about ${ceilMinutes(sleepTimerEndAt)}"
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        FilledTonalButton(
          onClick = {
            onSleepCancel()
            onDismiss()
          },
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(stringResource(R.string.reader_cancel_sleep_timer))
        }
      }
    }
  }
}

/** Rounded-up minutes until [endAt] (epoch ms), minimum 1 for display. */
private fun ceilMinutes(endAt: Long): Long {
  val remaining = endAt - System.currentTimeMillis()
  return ((remaining.coerceAtLeast(1L) + 59_999L) / 60_000L)
}

/** UI Round 4 (R5): selectable transcript font sizes. */
private val READER_SIZES = listOf(15, 16, 18, 20)

private const val READER_PREVIEW =
  "The wharf was quiet, except for one man sitting on a crate, watching the tide pull the boats away from their moorings."

/**
 * UI Round 4 (R5): reader display settings — transcript size / typeface / line
 * spacing. Every chip fires its callback immediately (persisted through
 * SettingsViewModel) and the preview paragraph re-renders in the new style,
 * so the sheet doubles as the preview.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DisplaySheet(
  currentFontSizeSp: Int,
  currentFontFamily: ReaderFontFamily,
  currentLineSpacing: ReaderLineSpacing,
  onFontSizeSelect: (Int) -> Unit,
  onFontFamilySelect: (ReaderFontFamily) -> Unit,
  onLineSpacingSelect: (ReaderLineSpacing) -> Unit,
  onDismiss: () -> Unit
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .padding(16.dp)
        // R5: three groups + preview exceed short screens — keep "Done"
        // reachable by scrolling.
        .verticalScroll(rememberScrollState())
    ) {
      Text(stringResource(R.string.reader_reading_display), style = MaterialTheme.typography.titleLarge)
      Text(
        "Applies to the transcript on the reading screen.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      Spacer(modifier = Modifier.height(12.dp))

      Text(
        text = READER_PREVIEW,
        style = MaterialTheme.typography.bodyLarge.copy(
          fontSize = currentFontSizeSp.sp,
          fontFamily = currentFontFamily.toFontFamily(),
          lineHeight = (currentFontSizeSp * currentLineSpacing.multiplier).sp
        ),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
      )

      Spacer(modifier = Modifier.height(16.dp))
      Text(stringResource(R.string.reader_text_size), style = MaterialTheme.typography.titleMedium)
      Spacer(modifier = Modifier.height(8.dp))
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        for (size in READER_SIZES) {
          FilterChip(
            selected = size == currentFontSizeSp,
            onClick = { onFontSizeSelect(size) },
            label = { Text("$size sp") }
          )
        }
      }

      Spacer(modifier = Modifier.height(16.dp))
      Text(stringResource(R.string.reader_typeface), style = MaterialTheme.typography.titleMedium)
      Spacer(modifier = Modifier.height(8.dp))
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        for (family in ReaderFontFamily.entries) {
          FilterChip(
            selected = family == currentFontFamily,
            onClick = { onFontFamilySelect(family) },
            label = { Text(family.name.lowercase().replaceFirstChar { it.uppercase() }) }
          )
        }
      }

      Spacer(modifier = Modifier.height(16.dp))
      Text(stringResource(R.string.reader_line_spacing), style = MaterialTheme.typography.titleMedium)
      Spacer(modifier = Modifier.height(8.dp))
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        for (spacing in ReaderLineSpacing.entries) {
          FilterChip(
            selected = spacing == currentLineSpacing,
            onClick = { onLineSpacingSelect(spacing) },
            label = {
              Text(
                spacing.name.lowercase().replaceFirstChar { it.uppercase() } +
                  " ×" + spacing.multiplier.toString().trimEnd('0').trimEnd('.')
              )
            }
          )
        }
      }

      Spacer(modifier = Modifier.height(16.dp))
      FilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.common_done))
      }
    }
  }
}

/**
 * WS2: Contents-row duration label — measured minutes for cached chapters
 * (plain "12 min"), a word-count estimate for the rest ("≈ 9 min" at 150 wpm).
 * Null when a chapter has no words and no measured audio.
 */
internal fun formatChapterDuration(chapterIndex: Int, wordCount: Int, measuredMs: Long?): String? {
  val minutes: Long = when {
    measuredMs != null && measuredMs > 0L -> (measuredMs + 59_999L) / 60_000L
    wordCount > 0 -> ((wordCount + 149) / 150).toLong()
    else -> return null
  }
  if (minutes <= 0L) return null
  val isEstimate = measuredMs == null || measuredMs <= 0L
  return if (isEstimate) "≈ $minutes min" else "$minutes min"
}


/**
 * WS3: in-book full-text search — query field (300 ms debounce), running
 * indicator, capped result list; tap jumps playback to the sentence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
  query: String,
  searchRunning: Boolean,
  onQueryChange: (String) -> Unit,
  hits: List<PlaybackSession.SearchHit>,
  onHitClick: (PlaybackSession.SearchHit) -> Unit,
  onDismiss: () -> Unit
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(stringResource(R.string.reader_search), style = MaterialTheme.typography.titleLarge)
      Spacer(modifier = Modifier.height(8.dp))
      OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text("Search the whole book…") },
        modifier = Modifier.fillMaxWidth()
      )
      Spacer(modifier = Modifier.height(8.dp))
      if (searchRunning) {
        Text(
          "Searching…",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      } else if (hits.isEmpty()) {
        Text(
          "Matches appear here as you type.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      Spacer(modifier = Modifier.height(8.dp))
      LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(hits) { hit ->
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier
              .fillMaxWidth()
              .clickable(role = Role.Button) {
                onHitClick(hit)
                onDismiss()
              }
          ) {
            Column(modifier = Modifier.padding(12.dp)) {
              Text(
                "${hit.chapterIndex}. ${hit.chapterTitle}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
              )
              Text(
                hit.snippet,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2
              )
            }
          }
        }
      }
    }
  }
}


/**
 * Q4: dramatis personae — manual per-book cast list. Add by name (+ optional
 * note), delete per row. The list lives in books/<slug>/characters.json
 * (mirrored for backup like bookmarks/positions).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharactersSheet(
  characters: List<com.chint.audiobook.data.repository.BookCharacter>,
  onAdd: (String, String) -> Unit,
  onDelete: (com.chint.audiobook.data.repository.BookCharacter) -> Unit,
  onDismiss: () -> Unit
) {
  var name by rememberSaveable { mutableStateOf("") }
  var note by rememberSaveable { mutableStateOf("") }
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(stringResource(R.string.reader_characters), style = MaterialTheme.typography.titleLarge)
      Text(
        "Your own cast list for this book",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Spacer(modifier = Modifier.height(12.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          singleLine = true,
          placeholder = { Text("Name") },
          modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
          value = note,
          onValueChange = { note = it },
          singleLine = true,
          placeholder = { Text("Note") },
          modifier = Modifier.weight(1f)
        )
        TextButton(
          onClick = {
            onAdd(name, note)
            name = ""
            note = ""
          },
          enabled = name.isNotBlank()
        ) { Text("Add") }
      }
      Spacer(modifier = Modifier.height(12.dp))
      if (characters.isEmpty()) {
        Text(
          "No characters yet.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(characters, key = { it.name + it.note }) { character ->
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(character.name, style = MaterialTheme.typography.titleSmall)
                if (character.note.isNotBlank()) {
                  Text(
                    character.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }
              TextButton(onClick = { onDelete(character) }) { Text("Remove") }
            }
          }
        }
      }
    }
  }
}
