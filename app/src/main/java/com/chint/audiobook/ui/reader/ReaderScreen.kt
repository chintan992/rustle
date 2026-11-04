package com.chint.audiobook.ui.reader

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chint.audiobook.R
import com.chint.audiobook.data.model.Bookmark
import com.chint.audiobook.data.model.ChapterRef
import com.chint.audiobook.data.model.ReaderFontFamily
import com.chint.audiobook.data.model.ReaderLineSpacing
import com.chint.audiobook.data.model.Segment
import com.chint.audiobook.data.model.TtsEngine
import com.chint.audiobook.playback.PlaybackSession
import com.chint.audiobook.theme.AppArrowDown
import com.chint.audiobook.theme.AppArrowUp
import com.chint.audiobook.theme.AppBookmark
import com.chint.audiobook.theme.AppChevronLeft
import com.chint.audiobook.theme.AppChevronRight
import com.chint.audiobook.theme.AppClock
import com.chint.audiobook.theme.AppIconsPause
import com.chint.audiobook.theme.AppMotion
import com.chint.audiobook.theme.AppReplayArrow
import com.chint.audiobook.theme.toFontFamily
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(
  bookTitle: String,
  chapterTitle: String,
  chapterIndex: Int,
  chapters: List<ChapterRef> = emptyList(),
  // UI Round 4 (R11): book-level progress summary shown atop the Contents sheet.
  bookProgressSummary: String? = null,
  segments: List<Segment>,
  currentSentenceIndex: Int,
  isPlaying: Boolean,
  buffering: Boolean,
  voice: String,
  ttsEngine: TtsEngine = TtsEngine.SUPERTONIC,
  speed: Float,
  highlightEnabled: Boolean,
  // UI Round 4 (R5): reader display preferences + settings-sheet wiring.
  fontSizeSp: Int,
  readerFontFamily: ReaderFontFamily,
  readerLineSpacing: ReaderLineSpacing,
  onUpdateFontSettings: (Int, ReaderFontFamily, ReaderLineSpacing) -> Unit = { _, _, _ -> },
  onPlayPauseClick: () -> Unit,
  onSentenceClick: (Int) -> Unit,
  onChapterSelect: (Int) -> Unit,
  onVoiceSelect: (String, Boolean) -> Unit,
  // Multi-voice: dialogue voice for the sheet section.
  currentDialogueVoice: String? = null,
  onDialogueVoiceSelect: (String?, Boolean) -> Unit = { _, _ -> },
  currentLanguage: String = "en",
  onLanguageSelect: (String) -> Unit = {},
  onSpeedSelect: (Float, Boolean) -> Unit,
  // ElevenReader plan (A3): chapter wall-clock position + scrubber/skip wiring.
  chapterPositionMs: Long,
  chapterDurationMs: Long,
  onSeekToMs: (Long) -> Unit,
  onSeekByMs: (Long) -> Unit,
  // Media round (A4): chapter transport callbacks (the ‹ Chapter n of N › row).
  onSkipToPreviousChapter: () -> Unit = {},
  onSkipToNextChapter: () -> Unit = {},
  // Cache/prep round (B3): contents-sheet readiness dots + live preparing marker.
  chaptersWithAudio: Set<Int> = emptySet(),
  preparingChapterIndex: Int? = null,
  // WS2: measured cached-chapter durations for the Contents sheet.
  chapterDurationsMs: Map<Int, Long> = emptyMap(),
  // Q4/Q3/Q7: characters, celebration, bookmark portability.
  characters: List<com.chint.audiobook.data.repository.BookCharacter> = emptyList(),
  onCharacterAdd: (String, String) -> Unit = { _, _ -> },
  onCharacterDelete: (com.chint.audiobook.data.repository.BookCharacter) -> Unit = {},
  endOfBookTitle: String? = null,
  onEndOfBookDismiss: () -> Unit = {},
  // WS3: in-book search wiring.
  searchRunning: Boolean = false,
  onSearchQuery: (String) -> Unit = {},
  searchHits: List<PlaybackSession.SearchHit> = emptyList(),
  onSearchHit: (PlaybackSession.SearchHit) -> Unit = {},
  // ElevenReader plan (A2/A4): bookmarks + sleep timer.
  bookmarks: List<Bookmark> = emptyList(),
  sleepTimerEndAt: Long = 0L,
  onAddBookmark: () -> Unit = {},
  onSeekToBookmark: (Bookmark) -> Unit = {},
  onDeleteBookmark: (Bookmark) -> Unit = {},
  onBookmarkExport: () -> Unit = {},
  onBookmarkImport: (String) -> Unit = {},
  onSleepStart: (Long) -> Unit = {},
  onSleepToEndOfChapter: () -> Unit = {},
  onSleepCancel: () -> Unit = {},
  // Track 3: skip quiet narration pauses
  skipSilence: Boolean = false,
  onSkipSilenceToggle: (Boolean) -> Unit = {},
  onBackClick: () -> Unit
) {
  val coroutineScope = rememberCoroutineScope()
  var showContentsSheet by rememberSaveable { mutableStateOf(false) }
  var showVoiceSheet by rememberSaveable { mutableStateOf(false) }
  var showSpeedSheet by rememberSaveable { mutableStateOf(false) }
  var showBookmarksSheet by rememberSaveable { mutableStateOf(false) }
  var showSleepSheet by rememberSaveable { mutableStateOf(false) }
  var showDisplaySheet by rememberSaveable { mutableStateOf(false) }
  // WS3: in-book search.
  var showSearchSheet by rememberSaveable { mutableStateOf(false) }
  var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

  LaunchedEffect(sleepTimerEndAt) {
    if (sleepTimerEndAt == 0L || sleepTimerEndAt == PlaybackSession.SLEEP_TIMER_END_OF_CHAPTER) {
      nowMs = System.currentTimeMillis()
      return@LaunchedEffect
    }
    while (true) {
      nowMs = System.currentTimeMillis()
      kotlinx.coroutines.delay(1_000L)
    }
  }
  // Q4: characters sheet.
  var showCharactersSheet by rememberSaveable { mutableStateOf(false) }
  val context = androidx.compose.ui.platform.LocalContext.current
  val listState = rememberLazyListState()
  var isUserBrowsingTranscript by rememberSaveable { mutableStateOf(false) }
  val transcriptScrollConnection = remember {
    object : NestedScrollConnection {
      override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
      ): Offset {
        if (source == NestedScrollSource.UserInput && consumed.y != 0f) {
          isUserBrowsingTranscript = true
        }
        return Offset.Zero
      }
    }
  }
  // Q7: bookmark import picker.
  val bookmarkImportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
    contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
  ) { uri ->
    if (uri != null) {
      coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val text = runCatching {
          context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
        if (text != null) {
          kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            onBookmarkImport(text)
          }
        }
      }
    }
  }

  // UI Round 4 (R4): paragraph-flow grouping. Prepared chapters tagged by the
  // R4 segmenter group into paragraph items; legacy chapters (no flags) fall
  // back to the original one-segment-per-row rendering. Sentence indices and
  // seek semantics are identical in both modes.
  val transcriptItems = remember(segments) { buildTranscriptItems(segments) }
  val paragraphMode = transcriptItems.isNotEmpty()

  // Round 8 (Task 1d): when the chapter changes during playback (auto-advance or a
  // sheet pick), jump to the top of the new chapter's content.
  LaunchedEffect(chapterIndex, segments.size) {
    listState.scrollToItem(0)
  }
  LaunchedEffect(chapterIndex) {
    isUserBrowsingTranscript = false
  }

  // Round 8 (Task 1d): follow the active sentence; scroll only when the item
  // holding it (paragraph in flow mode, row in fallback) is off-screen.
  LaunchedEffect(currentSentenceIndex, isUserBrowsingTranscript) {
    if (!isUserBrowsingTranscript && currentSentenceIndex in segments.indices) {
      val itemIndex = if (paragraphMode) {
        transcriptItems.indexOfLast { it.leadSegmentPos <= currentSentenceIndex }.coerceAtLeast(0)
      } else {
        currentSentenceIndex
      }
      val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == itemIndex }
      if (!isVisible) {
        listState.animateScrollToItem(itemIndex)
      }
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .windowInsetsPadding(WindowInsets.safeDrawing)
      .padding(16.dp)
  ) {
    // Standard top bar: back arrow, centered title block, sheet launcher.
    val backDesc = stringResource(R.string.common_back)
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      IconButton(
        onClick = onBackClick,
        modifier = Modifier.semantics { contentDescription = backDesc }
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = null
        )
      }
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(bookTitle, style = MaterialTheme.typography.titleSmall)
        Text(
          chapterTitle,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface
        )
      }
      Row {
        // Q4: dramatis personae entry.
        IconButton(
          onClick = { showCharactersSheet = true },
          modifier = Modifier.semantics { contentDescription = "Characters" }
        ) {
          Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null
          )
        }
        // WS3: full-book search entry.
        IconButton(
          onClick = { showSearchSheet = true },
          modifier = Modifier.semantics { contentDescription = "Search book" }
        ) {
          Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null
          )
        }
        IconButton(
          onClick = { showContentsSheet = true },
          modifier = Modifier.semantics { contentDescription = "Chapters" }
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.List,
            contentDescription = null
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    val activeTargetItemIndex by remember(currentSentenceIndex, paragraphMode, transcriptItems) {
      derivedStateOf {
        if (currentSentenceIndex in segments.indices) {
          if (paragraphMode) {
            transcriptItems.indexOfLast { it.leadSegmentPos <= currentSentenceIndex }.coerceAtLeast(0)
          } else {
            currentSentenceIndex
          }
        } else 0
      }
    }

    val isUserScrolledAway by remember {
      derivedStateOf {
        if (segments.isEmpty()) false
        else listState.layoutInfo.visibleItemsInfo.none { it.index == activeTargetItemIndex }
      }
    }

    val isTargetAbove by remember {
      derivedStateOf {
        activeTargetItemIndex < listState.firstVisibleItemIndex
      }
    }

    // Transcript Body: the text IS the player
    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
    ) {
      LazyColumn(
        state = listState,
        modifier = Modifier
          .fillMaxSize()
          .nestedScroll(transcriptScrollConnection),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
      // UI Round 4 (R4): paragraph-flow mode — consecutive chunks join into one
      // Text with per-chunk karaoke spans; scene breaks render as ⁂ dividers.
      if (paragraphMode) {
        items(
          items = transcriptItems,
          key = { item ->
            when (item) {
              is TranscriptItem.SceneBreak -> "sb_${item.pos}"
              is TranscriptItem.Paragraph -> "p_${item.leadSegmentPos}"
            }
          }
        ) { item ->
          when (item) {
            is TranscriptItem.SceneBreak -> SceneBreakDivider()
            is TranscriptItem.Paragraph -> ParagraphFlowText(
              chunks = item.chunks,
              currentSentenceIndex = currentSentenceIndex,
              highlightEnabled = highlightEnabled,
              fontSizeSp = fontSizeSp,
              readerFontFamily = readerFontFamily,
              readerLineSpacing = readerLineSpacing,
              onSentenceClick = onSentenceClick
            )
          }
        }
      } else {
        itemsIndexed(
          items = segments,
          key = { index, segment -> segment.hash.ifEmpty { index.toString() } }
        ) { index, segment ->
        val isActive = (index == currentSentenceIndex) && highlightEnabled
        val isPassed = highlightEnabled && (index < currentSentenceIndex)
        // ElevenReader plan (B1): two-tone amber highlight. The active sentence gets a
        // soft primary wash (light = deep amber @24% over Paper, dark = bright amber
        // @24% over Ink — theme-adaptive via primary.copy) plus a saturated full-alpha
        // primary accent bar on the leading edge. Passed sentences dim; the
        // highlight index math stays untouched (round-8/10 playback machinery).
        val washColor by animateColorAsState(
          targetValue = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.24f) else Color.Transparent,
          animationSpec = spring(stiffness = 380f)
        )
        val barColor by animateColorAsState(
          targetValue = if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent,
          animationSpec = spring(stiffness = 380f)
        )
        Row(
          modifier = Modifier
            .height(IntrinsicSize.Min)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(washColor)
            .clickable(role = Role.Button) { onSentenceClick(index) }
            .semantics { contentDescription = "Sentence $index: ${segment.text}" }
            .padding(start = 10.dp, top = 12.dp, bottom = 12.dp, end = 12.dp)
        ) {
          if (isActive) {
            Box(
              modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(barColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
          }
          Text(
            text = segment.text,
            style = MaterialTheme.typography.bodyLarge.copy(
              // UI Round 4 (R5): transcript typography follows display settings.
              fontSize = fontSizeSp.sp,
              fontFamily = readerFontFamily.toFontFamily(),
              lineHeight = (fontSizeSp * readerLineSpacing.multiplier).sp,
              fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = if (isPassed) {
              MaterialTheme.colorScheme.onSurfaceVariant
            } else {
              MaterialTheme.colorScheme.onSurface
            }
          )
        }
      }
      }
    }

    androidx.compose.animation.AnimatedVisibility(
      visible = isUserScrolledAway,
      enter = androidx.compose.animation.fadeIn(animationSpec = AppMotion.enterSpec()) +
        androidx.compose.animation.slideInVertically(animationSpec = AppMotion.enterSpec()) { it / 2 },
      exit = androidx.compose.animation.fadeOut(animationSpec = AppMotion.exitSpec()) +
        androidx.compose.animation.slideOutVertically(animationSpec = AppMotion.exitSpec()) { it / 2 },
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(bottom = 12.dp)
    ) {
      Button(
        onClick = {
          coroutineScope.launch {
            listState.animateScrollToItem(activeTargetItemIndex)
            isUserBrowsingTranscript = false
          }
        },
        colors = ButtonDefaults.buttonColors(
          containerColor = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(24.dp)
      ) {
        Icon(
          imageVector = if (isTargetAbove) AppArrowUp else AppArrowDown,
          contentDescription = null,
          modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          stringResource(R.string.reader_jump_to_active),
          style = MaterialTheme.typography.labelLarge
        )
      }
    }
  }

  // Render frontier & player bar (ElevenReader plan: chips → scrubber → transport)
    Card(
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
      shape = RoundedCornerShape(24.dp),
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 8.dp)
    ) {
      Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              "Now playing",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary
            )
            Text(
              chapterTitle,
              style = MaterialTheme.typography.titleSmall,
              maxLines = 1,
              overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
          }
          Text(
            stringResource(R.string.reader_chapter_counter, chapterIndex, chapters.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        Spacer(modifier = Modifier.height(10.dp))
        // UI Round 4 (R3): compact chips row — Bookmarks (count) · Voice · Speed.
        // "Contents" removed (the top-bar list icon opens the same sheet) and the
        // standalone Bookmarks launcher row under the transport is gone.
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          // UI Round 4 (R5): compact "Aa" chip opens the reader display sheet.
          FilledTonalButton(
            onClick = { showDisplaySheet = true },
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.height(40.dp)
          ) { Text("Aa") }
          FilledTonalButton(
            onClick = { showBookmarksSheet = true },
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.height(40.dp)
          ) {
            Icon(
              imageVector = AppBookmark,
              contentDescription = null,
              modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (bookmarks.isEmpty()) stringResource(R.string.reader_bookmarks) else stringResource(R.string.reader_bookmarks) + " ${bookmarks.size}")
          }
          FilledTonalButton(
            onClick = { showVoiceSheet = true },
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.height(40.dp)
          ) { Text("Voice: ${com.chint.audiobook.ui.onboarding.VoiceCatalog.displayName(voice)}") }
          FilledTonalButton(
            onClick = { showSpeedSheet = true },
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.height(40.dp)
          ) { Text("Speed: ${"%.2f".format(speed)}x") }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // A3 scrubber: appears only once at least one WAV duration has been
        // measured (chapterDurationMs > 0). No invented times while the render
        // frontier still sits at the chapter start.
        if (chapterDurationMs > 0L) {
          var scrubFraction by rememberSaveable { mutableStateOf<Float?>(null) }
          val shownFraction = scrubFraction
            ?: (chapterPositionMs.toFloat() / chapterDurationMs.toFloat()).coerceIn(0f, 1f)
          // UI Round 4 (R10): slim custom scrubber replaces M3's tall-gap Slider.
          // Fraction state + commit semantics preserved exactly: drag/tap updates
          // a local fraction, release seeks to fraction * chapterDurationMs.
          SlimScrubber(
            fraction = shownFraction,
            onScrub = { fraction -> scrubFraction = fraction },
            onCommit = {
              val target = scrubFraction
              if (target != null) {
                onSeekToMs((target.coerceIn(0f, 1f) * chapterDurationMs).toLong())
              }
              scrubFraction = null
            },
            modifier = Modifier.semantics { contentDescription = "Chapter position" }
          )
          val shownPositionMs = (shownFraction * chapterDurationMs).toLong()
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text(formatMs(shownPositionMs), style = MaterialTheme.typography.labelMedium)
            Text(
              text = "-" + formatMs(chapterDurationMs - shownPositionMs),
              style = MaterialTheme.typography.labelMedium
            )
          }
        } else if (buffering) {
          Text(
            stringResource(R.string.reader_rendering_chapter),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
          )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Media round (A4): slim chapter transport. Seven controls in the main
        // row would not fit a phone width, so chapter skips live on their own
        // centered line with the position label. Edge presence derives from the
        // same VM helpers the skip callbacks use (ChapterRef.index values, not
        // list positions, so non-contiguous numbering stays correct); the
        // chevrons are disabled, not hidden, at the book edges.
        Row(
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically
        ) {
          IconButton(
            onClick = onSkipToPreviousChapter,
            enabled = PlaybackSession.previousChapterOrNull(chapterIndex, chapters) != null,
            modifier = Modifier
              .size(40.dp)
              .semantics { contentDescription = "Previous chapter" }
          ) {
            Icon(imageVector = AppChevronLeft, contentDescription = null)
          }
          Text(
            stringResource(R.string.reader_chapter_counter, chapterIndex, chapters.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
          )
          IconButton(
            onClick = onSkipToNextChapter,
            enabled = PlaybackSession.nextChapterOrNull(chapterIndex, chapters) != null,
            modifier = Modifier
              .size(40.dp)
              .semantics { contentDescription = "Next chapter" }
          ) {
            Icon(imageVector = AppChevronRight, contentDescription = null)
          }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Transport row (ElevenReader plan): bookmark · -10 s · big pause · +10 s · sleep.
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          IconButton(
            onClick = onAddBookmark,
            modifier = Modifier.semantics { contentDescription = "Add bookmark at current position" }
          ) {
            Icon(imageVector = AppBookmark, contentDescription = null)
          }

          SkipTenSecondsBox(isForward = false) { onSeekByMs(-10_000L) }

          // Player-style control: one large tonal button, centered — the primary
          // action of the whole screen, 72dp for reachable, glanceable tapping.
          Box(
            modifier = Modifier.size(72.dp),
            contentAlignment = Alignment.Center
          ) {
            // UI Round 4 (R10): filled primary CTA — the tonal brown-on-brown
            // barely read against the Ink17 card on the dark theme.
            FilledIconButton(
              onClick = onPlayPauseClick,
              modifier = Modifier
                .size(72.dp)
                .semantics {
                  contentDescription = if (isPlaying) "Pause narration" else "Play narration"
                }
            ) {
              Icon(
                imageVector = if (isPlaying) AppIconsPause else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(36.dp)
              )
            }
          }

          SkipTenSecondsBox(isForward = true) { onSeekByMs(10_000L) }

          // UI Round 4 (R3): the live sleep-timer note collapses into a countdown
          // badge under the clock icon — no extra layout row while a timer runs.
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(
              onClick = { showSleepSheet = true },
              modifier = Modifier.semantics { contentDescription = "Sleep timer" }
            ) {
              Icon(imageVector = AppClock, contentDescription = null)
            }
            if (sleepTimerEndAt != 0L) {
                Text(
                text = if (sleepTimerEndAt == PlaybackSession.SLEEP_TIMER_END_OF_CHAPTER) {
                  "chapter"
                } else {
                    formatMs(sleepTimerEndAt - nowMs)
                },
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
              )
            }
          }
        }

        // Round 8 (Task 1d): annotate the render/buffer gap so the reader does
        // not look frozen while the next chapter's audio is prepared.
        if (buffering && chapterDurationMs > 0L) {
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            stringResource(R.string.reader_buffering),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
          )
        }
      }
    }
  }

  if (showSearchSheet) {
    // WS3: 300 ms debounce — typing settles before the session-side scan runs.
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) {
      kotlinx.coroutines.delay(300)
      onSearchQuery(query)
    }
    SearchSheet(
      query = query,
      searchRunning = searchRunning,
      onQueryChange = { query = it },
      hits = searchHits,
      onHitClick = onSearchHit,
      onDismiss = { showSearchSheet = false }
    )
  }

  if (showCharactersSheet) {
    CharactersSheet(
      characters = characters,
      onAdd = onCharacterAdd,
      onDelete = onCharacterDelete,
      onDismiss = { showCharactersSheet = false }
    )
  }

  // Q3: finished-book celebration (one-shot per end-of-book).
  endOfBookTitle?.let { title ->
    androidx.compose.material3.AlertDialog(
      onDismissRequest = onEndOfBookDismiss,
      title = { Text(stringResource(R.string.reader_book_finished_title)) },
      text = { Text(stringResource(R.string.reader_book_finished_message, title)) },
      confirmButton = {
        TextButton(onClick = onEndOfBookDismiss) { Text(stringResource(R.string.reader_book_finished_btn)) }
      }
    )
  }

  if (showContentsSheet) {
    ContentsSheet(
      chapters = chapters,
      currentChapterIndex = chapterIndex,
      bookProgressSummary = bookProgressSummary,
      chaptersWithAudio = chaptersWithAudio,
      preparingChapterIndex = preparingChapterIndex,
      chapterDurationsMs = chapterDurationsMs,
      onChapterSelect = onChapterSelect,
      onDismiss = { showContentsSheet = false }
    )
  }

  if (showVoiceSheet) {
    VoiceSheet(
      currentVoice = voice,
      ttsEngine = ttsEngine,
      currentDialogueVoice = currentDialogueVoice,
      onDialogueVoiceSelect = onDialogueVoiceSelect,
      onVoiceSelect = onVoiceSelect,
      currentLanguage = currentLanguage,
      onLanguageSelect = onLanguageSelect,
      onDismiss = { showVoiceSheet = false }
    )
  }

  if (showSpeedSheet) {
    SpeedSheet(
      currentSpeed = speed,
      skipSilence = skipSilence,
      onSkipSilenceToggle = onSkipSilenceToggle,
      onSpeedSelect = onSpeedSelect,
      onDismiss = { showSpeedSheet = false }
    )
  }

  // ElevenReader plan (A2): bookmark list sheet — tap to seek, Close to delete.
  if (showBookmarksSheet) {
    BookmarksSheet(
      bookmarks = bookmarks,
      currentChapterIndex = chapterIndex,
      onSeekToBookmark = onSeekToBookmark,
      onDeleteBookmark = onDeleteBookmark,
      onExport = onBookmarkExport,
      onImportClick = { bookmarkImportLauncher.launch("application/json") },
      onDismiss = { showBookmarksSheet = false }
    )
  }

  // ElevenReader plan (A4): sleep timer sheet.
  if (showSleepSheet) {
    SleepSheet(
      sleepTimerEndAt = sleepTimerEndAt,
      onSleepStart = onSleepStart,
      onSleepToEndOfChapter = onSleepToEndOfChapter,
      onSleepCancel = onSleepCancel,
      onDismiss = { showSleepSheet = false }
    )
  }

  // UI Round 4 (R5): reader display settings — stays open across chip taps so
  // several preferences can change before "Done".
  if (showDisplaySheet) {
    DisplaySheet(
      currentFontSizeSp = fontSizeSp,
      currentFontFamily = readerFontFamily,
      currentLineSpacing = readerLineSpacing,
      onFontSizeSelect = { size ->
        onUpdateFontSettings(size, readerFontFamily, readerLineSpacing)
      },
      onFontFamilySelect = { family ->
        onUpdateFontSettings(fontSizeSp, family, readerLineSpacing)
      },
      onLineSpacingSelect = { spacing ->
        onUpdateFontSettings(fontSizeSp, readerFontFamily, spacing)
      },
      onDismiss = { showDisplaySheet = false }
    )
  }
}

/**
 * UI Round 4 (R4): one LazyColumn item in paragraph-flow mode. Chunks carry
 * their 0-based position in the segments list so per-chunk taps keep the exact
 * onSentenceClick indices the fallback rows use.
 */
private sealed interface TranscriptItem {
  val leadSegmentPos: Int

  data class Paragraph(val chunks: List<Pair<Int, Segment>>) : TranscriptItem {
    override val leadSegmentPos get() = chunks.first().first
  }

  data class SceneBreak(val pos: Int) : TranscriptItem {
    override val leadSegmentPos get() = pos
  }
}

/**
 * Groups segments into paragraph items at startsParagraph boundaries. Returns
 * an empty list for legacy prepared chapters (no flags) — the caller keeps the
 * card-row fallback for those.
 */
private fun buildTranscriptItems(segments: List<Segment>): List<TranscriptItem> {
  if (segments.none { it.startsParagraph }) return emptyList()
  val items = mutableListOf<TranscriptItem>()
  var current = mutableListOf<Pair<Int, Segment>>()
  segments.forEachIndexed { pos, seg ->
    when {
      seg.isSceneBreak -> {
        if (current.isNotEmpty()) {
          items += TranscriptItem.Paragraph(current.toList())
          current = mutableListOf()
        }
        items += TranscriptItem.SceneBreak(pos)
      }
      seg.startsParagraph -> {
        if (current.isNotEmpty()) {
          items += TranscriptItem.Paragraph(current.toList())
          current = mutableListOf()
        }
        current += pos to seg
      }
      else -> current += pos to seg
    }
  }
  if (current.isNotEmpty()) items += TranscriptItem.Paragraph(current.toList())
  return items
}

/**
 * UI Round 4 (R4): a whole paragraph rendered as one Text. Active chunk gets
 * the amber wash + SemiBold (the two-tone karaoke language, wash instead of
 * accent bar — SpanStyle backgrounds are line-rectangular); passed chunks dim.
 * Tap resolution maps the annotated-region hit back to the chunk's sentence
 * index, so seek semantics match the fallback rows exactly.
 */
@Composable
private fun ParagraphFlowText(
  chunks: List<Pair<Int, Segment>>,
  currentSentenceIndex: Int,
  highlightEnabled: Boolean,
  fontSizeSp: Int,
  readerFontFamily: ReaderFontFamily,
  readerLineSpacing: ReaderLineSpacing,
  onSentenceClick: (Int) -> Unit
) {
  val washColor by animateColorAsState(
    targetValue = if (highlightEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.24f) else Color.Transparent,
    animationSpec = spring(stiffness = 380f)
  )
  val passedColor = MaterialTheme.colorScheme.onSurfaceVariant
  val normalColor = MaterialTheme.colorScheme.onSurface

  val annotated = remember(chunks, currentSentenceIndex, highlightEnabled, washColor, passedColor, normalColor) {
    buildAnnotatedString {
      chunks.forEachIndexed { chunkIdx, (pos, seg) ->
        if (chunkIdx > 0) append(" ")
        val isActive = highlightEnabled && pos == currentSentenceIndex
        val isPassed = highlightEnabled && pos < currentSentenceIndex
        pushStringAnnotation(tag = "chunk", annotation = pos.toString())
        pushStyle(
          SpanStyle(
            background = if (isActive) washColor else Color.Transparent,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isPassed) passedColor else normalColor
          )
        )
        append(seg.text)
        pop()
        pop()
      }
    }
  }

  val layoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }
  Text(
    text = annotated,
    style = MaterialTheme.typography.bodyLarge.copy(
      fontSize = fontSizeSp.sp,
      fontFamily = readerFontFamily.toFontFamily(),
      lineHeight = (fontSizeSp * readerLineSpacing.multiplier).sp
    ),
    onTextLayout = { layoutResult.value = it },
    modifier = Modifier
      .fillMaxWidth()
      .pointerInput(chunks) {
        detectTapGestures { position ->
          layoutResult.value?.let { layout ->
            val offset = layout.getOffsetForPosition(position)
            annotated.getStringAnnotations("chunk", offset, offset)
              .firstOrNull()
              ?.let { annotation -> onSentenceClick(annotation.item.toInt()) }
          }
        }
      }
      .semantics {
        contentDescription = "Paragraph: ${chunks.first().second.text.take(60)}"
      }
  )
}

@Composable
private fun SceneBreakDivider() {
  Text(
    text = "⁂",
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center,
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp)
  )
}

/**
 * ElevenReader plan (A3): ±10 s skip in ElevenReader's style — circular arrow
 * with the seconds printed over the ring. The forward twin is the same vector
 * rotated 90° at the call site; the icon path itself is defined once.
 */
@Composable
private fun SkipTenSecondsBox(
  isForward: Boolean,
  onClick: () -> Unit
) {
  Box(contentAlignment = Alignment.Center) {
    IconButton(
      onClick = onClick,
      modifier = Modifier.semantics {
        contentDescription = if (isForward) "Skip forward 10 seconds" else "Skip back 10 seconds"
      }
    ) {
      Icon(
        imageVector = AppReplayArrow,
        contentDescription = null,
        modifier = Modifier
          .size(28.dp)
          .rotate(if (isForward) 90f else 0f)
      )
    }
    Text(
      text = "10",
      style = MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold
      )
    )
  }
}

/**
 * UI Round 4 (R10): slim chapter scrubber — 4dp track + 12dp amber thumb drawn on
 * a Canvas, replacing M3's tall-gap Slider widget. Touch behavior matches the old
 * flow exactly: tap or drag publishes onScrub(fraction), release calls onCommit
 * (the caller keeps ownership of the scrubFraction state).
 */
@Composable
private fun SlimScrubber(
  fraction: Float,
  onScrub: (Float) -> Unit,
  onCommit: () -> Unit,
  modifier: Modifier = Modifier
) {
  val trackColor = MaterialTheme.colorScheme.surfaceVariant
  val activeColor = MaterialTheme.colorScheme.primary
  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(28.dp)
      .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }
      .pointerInput(Unit) {
        detectTapGestures { offset ->
          onScrub((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
          onCommit()
        }
      }
      .pointerInput(Unit) {
        detectHorizontalDragGestures(
          onDragEnd = onCommit,
          onDragCancel = onCommit
        ) { change, _ ->
          change.consume()
          onScrub((change.position.x / size.width.toFloat()).coerceIn(0f, 1f))
        }
      }
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val trackY = size.height / 2f
      val trackH = 4.dp.toPx()
      val thumbR = 6.dp.toPx()
      val trackStart = thumbR
      val trackW = size.width - 2f * thumbR
      val played = fraction.coerceIn(0f, 1f)
      drawRoundRect(
        color = trackColor,
        topLeft = Offset(trackStart, trackY - trackH / 2f),
        size = Size(trackW, trackH),
        cornerRadius = CornerRadius(trackH / 2f)
      )
      drawRoundRect(
        color = activeColor,
        topLeft = Offset(trackStart, trackY - trackH / 2f),
        size = Size(trackW * played, trackH),
        cornerRadius = CornerRadius(trackH / 2f)
      )
      drawCircle(
        color = activeColor,
        radius = thumbR,
        center = Offset(trackStart + trackW * played, trackY)
      )
    }
  }
}

/** Player time format: m:ss (h:mm:ss once a chapter crosses the hour mark). */
internal fun formatMs(ms: Long): String {
  val totalSeconds = ms.coerceAtLeast(0L) / 1000
  val hours = totalSeconds / 3600
  val minutes = (totalSeconds % 3600) / 60
  val seconds = totalSeconds % 60
  return if (hours > 0) {
    "%d:%02d:%02d".format(hours, minutes, seconds)
  } else {
    "%d:%02d".format(minutes, seconds)
  }
}
