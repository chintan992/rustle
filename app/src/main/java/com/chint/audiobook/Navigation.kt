package com.chint.audiobook

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.chint.audiobook.data.model.TtsEngine
import com.chint.audiobook.engine.TtsEngineSelection
import com.chint.audiobook.ui.import.ImportScreen
import com.chint.audiobook.ui.import.ImportViewModel
import com.chint.audiobook.ui.library.BookProgress
import com.chint.audiobook.ui.library.LibraryScreen
import com.chint.audiobook.ui.library.LibraryViewModel
import com.chint.audiobook.ui.miniplayer.MiniPlayer
import com.chint.audiobook.ui.model.ModelManagerScreen
import com.chint.audiobook.ui.model.ModelViewModel
import com.chint.audiobook.ui.onboarding.VoiceSetupScreen
import com.chint.audiobook.ui.reader.ReaderScreen
import com.chint.audiobook.ui.reader.ReaderViewModel
import com.chint.audiobook.ui.settings.SettingsScreen
import com.chint.audiobook.ui.settings.SettingsViewModel
import com.chint.audiobook.update.UpdateRelease
import com.chint.audiobook.update.UpdateUiState
import com.chint.audiobook.update.UpdateViewModel
import com.chint.audiobook.theme.AppMotion

@Composable
fun MainNavigation() {
  val settingsViewModel: SettingsViewModel = viewModel()
  val libraryViewModel: LibraryViewModel = viewModel()
  val readerViewModel: ReaderViewModel = viewModel()
  val modelViewModel: ModelViewModel = viewModel()
  val updateViewModel: UpdateViewModel = viewModel()
  val updateUiState by updateViewModel.state.collectAsState()

  // N14: collect the raw DataStore flow with initial=null and render nothing
  // until settings are actually loaded from disk, so no screen paints from the
  // AppSettings() default (e.g. a stale onboarding flag) before first read.
  val loadedSettings by settingsViewModel.appSettingsFlow.collectAsState(initial = null)

  if (loadedSettings == null) {
    return
  }

  // Latch onto the raw-flow snapshot, NOT the updatable StateFlow: the
  // StateFlow publishes its AppSettings() default on first subscribe (its
  // upstream has not emitted yet), which painted a one-frame onboarding flash
  // over the library on cold starts before the real value arrived.
  val appSettings = loadedSettings!!
  val books by libraryViewModel.books.collectAsState()
  val playbackState by libraryViewModel.playbackState.collectAsState()
  // UI Round 4 (R6): per-book position sidecars drive the Continue card + rows.
  val positions by libraryViewModel.positions.collectAsState()
  // Media round (A5): per-book rendered-audio sizes for the manage sheet.
  val storageBytes by libraryViewModel.bookStorageBytes.collectAsState()
  // BG0: partial-delete warning surfaced on the Library.
  val deletePartial by libraryViewModel.lastDeletePartial.collectAsState()
  // WS2: per-book listened totals for the manage sheet.
  val bookListenedSeconds by libraryViewModel.bookListenedSeconds.collectAsState()
  val playerState by readerViewModel.playerState.collectAsState()
  val currentChapterIndex by readerViewModel.currentChapterIndex.collectAsState()
  // ElevenReader plan (A2/A4): bookmarks + sleep timer state for the reader entry.
  val bookmarks by readerViewModel.bookmarks.collectAsState()
  val sleepTimerEndAt by readerViewModel.sleepTimerEndAt.collectAsState()
  // A1: active book for the persistent listening bar (collected at nav scope).
  val miniPlayerBook by readerViewModel.activeBook.collectAsState()

  val initialKey = if (!appSettings.onboardingCompleted) VoiceSetupKey else LibraryKey
  val navigationState = rememberNavigationState(initialKey)
  val navigator = remember(navigationState) { Navigator(navigationState) }

  // WS2 & Track 2: an EPUB/TXT/MD shared/opened or text shared into the app routes
  // straight to Import, which auto-runs the pipeline on it and consumes the payload.
  val pendingImportUri by com.chint.audiobook.ui.import.PendingImport.uri.collectAsState()
  val pendingSharedText by com.chint.audiobook.ui.import.PendingImport.sharedText.collectAsState()
  LaunchedEffect(pendingImportUri, pendingSharedText) {
    if (pendingImportUri != null || pendingSharedText != null) {
      navigator.navigate(ImportKey)
    }
  }

  // UI round: notification permission is requested at the moment onboarding
  // ENDS - a user-driven, contextual ask (Android's own best practice), not a
  // launch-blocking system dialog. Fires once per process via a saveable flag;
  // only on Android 13+ and only if not already granted.
  val localContext = androidx.compose.ui.platform.LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, updateViewModel) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) updateViewModel.onResume()
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  // Q2: one-time battery guidance when not exempt from optimization.
  val powerManager = localContext.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
  val batteryGuidanceVisible = !powerManager.isIgnoringBatteryOptimizations(localContext.packageName) &&
    !appSettings.batteryGuidanceDismissed

  var notificationPermissionRequested by rememberSaveable { mutableStateOf(false) }
  val notificationPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { isGranted: Boolean ->
    // Granted or denied - playback continues either way; media-session
    // notifications are exempt from POST_NOTIFICATIONS gating.
  }

  fun maybeRequestNotificationPermission() {
    if (notificationPermissionRequested) return
    notificationPermissionRequested = true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(
        localContext,
        Manifest.permission.POST_NOTIFICATIONS
      ) != PackageManager.PERMISSION_GRANTED
    ) {
      notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  LaunchedEffect(appSettings.onboardingCompleted) {
    if (appSettings.onboardingCompleted) {
      updateViewModel.checkForUpdate()
    }
    if (!appSettings.onboardingCompleted && navigationState.currentRoute != VoiceSetupKey) {
      navigator.navigate(VoiceSetupKey)
    }
  }

  // UI Round 4 (R1): lift every screen's bottom content above the persistent
  // listening bar while it is visible — Library's FAB was half-covered behind
  // the bar (device sweep 15_miniplayer.png). The padding goes on NavDisplay,
  // NOT the outer Box, so the MiniPlayer itself stays pinned to the true
  // bottom edge while the underlying screens shift up.
  val miniVisible = playerState.isPlaying && navigationState.currentRoute !is ReaderKey
  val miniPlayerGap by animateDpAsState(
    targetValue = if (miniVisible) 72.dp else 0.dp,
    animationSpec = if (miniVisible) AppMotion.enterSpec() else AppMotion.exitSpec(),
    label = "miniPlayerGap"
  )
  val navigationDirection = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1

  Box(
    modifier = Modifier.fillMaxSize()
  ) {
  NavDisplay(
    backStack = navigationState.backStack,
    onBack = { navigator.goBack() },
    modifier = Modifier.padding(bottom = miniPlayerGap),
    entryDecorators = listOf(
      rememberSaveableStateHolderNavEntryDecorator(),
      rememberViewModelStoreNavEntryDecorator()
    ),
    transitionSpec = {
      (slideInHorizontally(
        initialOffsetX = { fullWidth -> fullWidth * navigationDirection },
        animationSpec = AppMotion.navigationSpec()
      ) + fadeIn(animationSpec = AppMotion.navigationSpec())) togetherWith
      (slideOutHorizontally(
        targetOffsetX = { fullWidth -> -fullWidth / 4 * navigationDirection },
        animationSpec = AppMotion.navigationSpec()
      ) + fadeOut(animationSpec = AppMotion.navigationSpec()))
    },
    popTransitionSpec = {
      (slideInHorizontally(
        initialOffsetX = { fullWidth -> -fullWidth / 4 * navigationDirection },
        animationSpec = AppMotion.navigationSpec()
      ) + fadeIn(animationSpec = AppMotion.navigationSpec())) togetherWith
      (slideOutHorizontally(
        targetOffsetX = { fullWidth -> fullWidth * navigationDirection },
        animationSpec = AppMotion.navigationSpec()
      ) + fadeOut(animationSpec = AppMotion.navigationSpec()))
    },
    predictivePopTransitionSpec = {
      (slideInHorizontally(
        initialOffsetX = { fullWidth -> -fullWidth / 4 * navigationDirection },
        animationSpec = AppMotion.navigationSpec()
      ) + fadeIn(animationSpec = AppMotion.navigationSpec())) togetherWith
      (slideOutHorizontally(
        targetOffsetX = { fullWidth -> fullWidth * navigationDirection },
        animationSpec = AppMotion.navigationSpec()
      ) + fadeOut(animationSpec = AppMotion.navigationSpec()))
    },
    entryProvider = entryProvider {
      entry<VoiceSetupKey> {
        VoiceSetupScreen(
          engine = appSettings.ttsEngine,
          onComplete = { voice, highlight ->
            settingsViewModel.updateSettings {
              it.copy(
                defaultVoice = if (it.ttsEngine == TtsEngine.SUPERTONIC) voice else it.defaultVoice,
                kokoroVoice = if (it.ttsEngine == TtsEngine.KOKORO) voice else it.kokoroVoice,
                highlightEnabled = highlight,
                onboardingCompleted = true
              )
            }
            navigator.finishOnboarding()
            maybeRequestNotificationPermission()
          },
          onSkip = {
            settingsViewModel.updateSettings { it.copy(onboardingCompleted = true) }
            navigator.finishOnboarding()
            maybeRequestNotificationPermission()
          }
        )
      }

      entry<LibraryKey> {
        // UI Round 4 (R6): arrival refresh — every reader save (pause/seek/
        // heartbeat) went to the sidecars while LibraryViewModel just sat here.
        LaunchedEffect(Unit) { libraryViewModel.refreshPositions() }
        // UI Round 4 (R2): "Continue Listening" = the sidecar with the newest
        // updatedAt; empty map (fresh install) falls back to the first book.
        val lastBook = BookProgress.mostRecentlyPlayed(
          books.map { it.slug },
          positions
        )?.let { slug -> books.firstOrNull { it.slug == slug } }
          ?: books.firstOrNull()
        LibraryScreen(
          books = books,
          lastBook = lastBook,
          positions = positions,
          isPlaying = playerState.isPlaying,
          onBookClick = { book ->
            val targetChapter = LibraryViewModel.resolveOpenChapter(playbackState, book.slug)
            navigator.openReader(bookSlug = book.slug, chapterIndex = targetChapter)
          },
          onImportClick = { navigator.navigate(ImportKey) },
          onOpenModelManager = { navigator.navigate(ModelManagerKey) },
          onOpenSettings = { navigator.navigate(SettingsKey) },
          // Media round (A5): storage sizes + manage-sheet actions. Deleting the
          // active book parks playback/synthesis first (orphan-WAV guard), then
          // the recursive delete removes the whole book dir.
          storageBytes = storageBytes,
          bookListenedSeconds = bookListenedSeconds,
          showPartialDeleteWarning = deletePartial,
          onPartialDeleteWarningShown = { libraryViewModel.consumePartialDeleteWarning() },
          // Q2: show once when the system still optimizes our battery.
          showBatteryGuidance = batteryGuidanceVisible,
          onBatteryGuidanceDismiss = {
            settingsViewModel.updateSettings { it.copy(batteryGuidanceDismissed = true) }
          },
          onOpenBatterySettings = {
            localContext.startActivity(
              android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", localContext.packageName, null)
              )
            )
          },
          onDeleteBook = { slug ->
            readerViewModel.stopPlaybackForBook(slug)
            libraryViewModel.deleteBook(slug)
          },
          onClearAudioCache = { slug -> libraryViewModel.clearAudioCache(slug) }
        )
      }

      entry<ImportKey> {
        // Entry-scoped ViewModel: cleans up automatically when popped off back stack.
        val importViewModel: ImportViewModel = viewModel()
        val importProgress by importViewModel.importProgress.collectAsState()
        val importedBook by importViewModel.importedBook.collectAsState()

        // UI Round 4 (R12): onBookImported only refreshes the library — it used
        // to pop this entry instantly, making ImportProgress.Completed (the
        // success card) unreachable. reset() moved to exit-time below.
        ImportScreen(
          progress = importProgress,
          importedBook = importedBook,
          defaultVoice = appSettings.defaultVoice,
          expressive = appSettings.expressiveNarration,
          initialUri = pendingImportUri,
          onInitialUriConsumed = { com.chint.audiobook.ui.import.PendingImport.consume() },
          initialText = pendingSharedText,
          onInitialTextConsumed = { com.chint.audiobook.ui.import.PendingImport.consumeText() },
          onProgressUpdate = { progress -> importViewModel.updateProgress(progress) },
          onBookImported = { book ->
            importViewModel.setImportedBook(book)
            libraryViewModel.reload()
          },
          onStartListening = { book ->
            importViewModel.reset()
            navigator.goBack()
            navigator.openReader(bookSlug = book.slug, chapterIndex = 1)
          },
          onBackToLibrary = {
            importViewModel.reset()
            navigator.goBack()
          },
          onBackClick = {
            importViewModel.reset()
            navigator.goBack()
          }
        )
      }

      entry<ReaderKey> { key ->
        LaunchedEffect(key.bookSlug, key.chapterIndex) {
          readerViewModel.loadBookAndChapter(key.bookSlug, key.chapterIndex)
        }
        // BG0 (ghost-book fix): a load that resolved no book (deleted slug via
        // a stale restored entry) pops the reader instead of painting an empty
        // transcript over a dead playlist.
        val bookMissing by readerViewModel.bookMissing.collectAsState()
        LaunchedEffect(bookMissing) {
          if (bookMissing) navigator.goBack()
        }

        val activeBook by readerViewModel.activeBook.collectAsState()
        val segments by readerViewModel.chapterSegments.collectAsState()
        // Cache/prep round (B3): contents-sheet readiness indicators.
        val chaptersWithAudio by readerViewModel.chaptersWithAudio.collectAsState()
        val preparingChapterIndex by readerViewModel.preparingChapterIndex.collectAsState()
        // WS2: measured cached-chapter durations for the Contents sheet.
        val chapterDurationsMs by readerViewModel.chapterDurationsMs.collectAsState()
        // WS3: per-book voice override + search wiring.
        val readerVoiceOverride by readerViewModel.bookVoiceOverride.collectAsState()
        val readerDialogueVoice by readerViewModel.dialogueVoiceOverride.collectAsState()
        val searchRunning by readerViewModel.searchRunning.collectAsState()
        val searchHits by readerViewModel.searchHits.collectAsState()
        // Q4/Q3: characters + celebration.
        val readerCharacters by readerViewModel.characters.collectAsState()
        val endOfBookTitle by readerViewModel.endOfBookReached.collectAsState()
        LaunchedEffect(Unit) { readerViewModel.loadCharacters() }
        // Q7: share payload for bookmark export.
        val bookmarkExportAction = {
          val json = readerViewModel.exportBookmarks()
          val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_TEXT, json)
          }
          localContext.startActivity(android.content.Intent.createChooser(send, "Export bookmarks"))
        }

        // N15: the top-bar chapter title derives from the CURRENT chapter state,
        // not from the entry key - switching chapters via the Contents sheet must
        // retitle the bar without popping the back stack.
        val chapterTitle = activeBook?.chapters
          ?.firstOrNull { it.index == currentChapterIndex }?.title
          ?: "Chapter $currentChapterIndex"
        val selectedReaderEngine = TtsEngineSelection.forLanguage(
          appSettings.ttsEngine,
          activeBook?.language ?: "en"
        )

        ReaderScreen(
          bookTitle = activeBook?.title ?: "Audiobook",
          chapterTitle = chapterTitle,
          chapterIndex = currentChapterIndex,
          chapters = activeBook?.chapters ?: emptyList(),
          // UI Round 4 (R11): book-level progress header for the Contents sheet.
          bookProgressSummary = activeBook
            ?.let { book -> BookProgress.summary(book, positions[key.bookSlug]) },
          segments = segments,
          currentSentenceIndex = playerState.currentSentenceIndex,
          isPlaying = playerState.isPlaying,
          buffering = playerState.buffering,
          // WS3: effective narrator = book override when set.
          ttsEngine = selectedReaderEngine,
          voice = readerVoiceOverride?.takeIf {
            com.chint.audiobook.ui.onboarding.VoiceCatalog.contains(selectedReaderEngine, it)
          } ?: if (selectedReaderEngine == TtsEngine.KOKORO) {
            appSettings.kokoroVoice
          } else {
            appSettings.defaultVoice
          },
          // Multi-voice: dialogue voice (book override -> global) for the sheet.
          currentDialogueVoice = readerDialogueVoice?.takeIf {
            com.chint.audiobook.ui.onboarding.VoiceCatalog.contains(selectedReaderEngine, it)
          } ?: if (selectedReaderEngine == TtsEngine.KOKORO) {
            appSettings.kokoroDialogueVoice
          } else {
            appSettings.dialogueVoice
          },
          currentLanguage = activeBook?.language ?: "en",
          onLanguageSelect = { code -> readerViewModel.changeBookLanguage(code) },
          onDialogueVoiceSelect = { voice, applyToAllBooks ->
            readerViewModel.selectDialogueVoice(voice, applyToAllBooks)
          },
          speed = playerState.speed,
          highlightEnabled = appSettings.highlightEnabled,
          // UI Round 4 (R5): reader display preferences, persisted via settings.
          fontSizeSp = appSettings.readerFontSizeSp,
          readerFontFamily = appSettings.readerFontFamily,
          readerLineSpacing = appSettings.readerLineSpacing,
          onUpdateFontSettings = { size, family, spacing ->
            settingsViewModel.updateSettings {
              it.copy(
                readerFontSizeSp = size,
                readerFontFamily = family,
                readerLineSpacing = spacing
              )
            }
          },
          onPlayPauseClick = { readerViewModel.togglePlayPause() },
          onSentenceClick = { sentenceIdx -> readerViewModel.seekToSentence(sentenceIdx) },
          // Round 8: explicit sheet pick — dedicated path, not stale-key gated.
          onChapterSelect = { newChapterIdx ->
            readerViewModel.selectChapter(newChapterIdx)
          },
          // WS3: scoped voice pick — book pref (session) or global default.
          onVoiceSelect = { voice, applyToAllBooks ->
            if (applyToAllBooks) {
              val engine = TtsEngineSelection.forLanguage(
                appSettings.ttsEngine,
                activeBook?.language ?: "en"
              )
              settingsViewModel.updateSettings {
                if (engine == TtsEngine.KOKORO) it.copy(kokoroVoice = voice)
                else it.copy(defaultVoice = voice)
              }
            }
            readerViewModel.selectVoice(voice, applyToAllBooks)
          },
          // WS3: scoped speed pick — live always; book pref persists the override.
          onSpeedSelect = { speed, applyToAllBooks ->
            if (applyToAllBooks) readerViewModel.setSpeed(speed)
            else readerViewModel.selectSpeed(speed, applyToAllBooks)
          },
          chapterPositionMs = playerState.chapterPositionMs,
          chapterDurationMs = playerState.chapterDurationMs,
          onSeekToMs = { targetMs -> readerViewModel.seekToPositionMs(targetMs) },
          onSeekByMs = { deltaMs -> readerViewModel.seekByMs(deltaMs) },
          // Media round (A4): chapter transport row callbacks.
          onSkipToPreviousChapter = { readerViewModel.skipToPreviousChapter() },
          onSkipToNextChapter = { readerViewModel.skipToNextChapter() },
          // Cache/prep round (B3): contents-sheet readiness indicators.
          chaptersWithAudio = chaptersWithAudio,
          preparingChapterIndex = preparingChapterIndex,
          chapterDurationsMs = chapterDurationsMs,
          // Q4/Q3/Q7: characters, celebration, bookmark portability.
          characters = readerCharacters,
          onCharacterAdd = { name, note -> readerViewModel.addCharacter(name, note) },
          onCharacterDelete = { c -> readerViewModel.deleteCharacter(c) },
          endOfBookTitle = endOfBookTitle,
          onEndOfBookDismiss = { readerViewModel.dismissEndOfBook() },
          onBookmarkExport = bookmarkExportAction,
          onBookmarkImport = { json -> readerViewModel.importBookmarks(json) },
          searchRunning = searchRunning,
          onSearchQuery = { query -> readerViewModel.searchBook(query) },
          searchHits = searchHits,
          onSearchHit = { hit -> readerViewModel.playSearchHit(hit) },
          bookmarks = bookmarks,
          sleepTimerEndAt = sleepTimerEndAt,
          onAddBookmark = { readerViewModel.addBookmark() },
          onSeekToBookmark = { bookmark -> readerViewModel.seekToBookmark(bookmark) },
          onDeleteBookmark = { bookmark -> readerViewModel.deleteBookmark(bookmark) },
          onSleepStart = { durationMs -> readerViewModel.startSleepTimer(durationMs) },
          onSleepToEndOfChapter = { readerViewModel.startSleepTimerToEndOfChapter() },
          onSleepCancel = { readerViewModel.cancelSleepTimer() },
          skipSilence = appSettings.skipSilence,
          onSkipSilenceToggle = { enabled ->
            settingsViewModel.updateSettings { it.copy(skipSilence = enabled) }
          },
          onBackClick = { navigator.goBack() }
        )
      }
      entry<LicensesKey> {
        com.chint.audiobook.ui.settings.LicensesScreen(
          onBackClick = { navigator.goBack() }
        )
      }

      entry<ModelManagerKey> {
        val downloadState by modelViewModel.downloadState.collectAsState()
        val kokoroDownloadState by modelViewModel.kokoroDownloadState.collectAsState()
        val benchmarkResult by modelViewModel.benchmarkResult.collectAsState()
        val packIntegrity by modelViewModel.packIntegrity.collectAsState()
        val kokoroPackIntegrity by modelViewModel.kokoroPackIntegrity.collectAsState()
        val rtfStats by modelViewModel.rtfStats.collectAsState()
        val debugNote by modelViewModel.debugNote.collectAsState()
        // Keep pack integrity current while the model manager is visible.
        LaunchedEffect(Unit) {
          while (true) {
            modelViewModel.refreshModelState()
            kotlinx.coroutines.delay(5_000)
          }
        }
        ModelManagerScreen(
          downloadState = downloadState,
          benchmarkResult = benchmarkResult,
          packIntegrity = packIntegrity,
          kokoroDownloadState = kokoroDownloadState,
          kokoroPackIntegrity = kokoroPackIntegrity,
          rtfStats = rtfStats,
          debugBuild = modelViewModel.debugBuild,
          debugNote = debugNote,
          onDownloadClick = { modelViewModel.startDownload() },
          onClearModelData = { modelViewModel.clearModelData() },
          onDownloadKokoroClick = { modelViewModel.startKokoroDownload() },
          onClearKokoroModelData = { modelViewModel.clearKokoroModelData() },
          onRunBenchmarkClick = { modelViewModel.runBenchmark() },
          onDebugCorruptFinalPackFile = { modelViewModel.debugCorruptFinalPackFileAndRecover() },
          onDebugCorruptStagedPackFile = { modelViewModel.debugCorruptStagedPackFileTwiceAndExhaust() },
          onBackClick = { navigator.goBack() }
        )
      }

      entry<SettingsKey> {
        SettingsScreen(
          settings = appSettings,
          onUpdateSettings = { transform -> settingsViewModel.updateSettings(transform) },
          onOpenVoiceSetup = { navigator.navigate(VoiceSetupKey) },
          onOpenLicenses = { navigator.navigate(LicensesKey) },
          onCheckUpdates = { updateViewModel.checkForUpdate(manual = true) },
          onBackClick = { navigator.goBack() }
        )
      }
    }
  )

  // ElevenReader plan (A1): persistent listening bar — docks at the bottom of
  // every destination while audio plays and the Reader is NOT on top. Tap →
  // Reader at the live chapter (the stale-entry guard keeps playback going).
  MiniPlayer(
    book = miniPlayerBook,
    currentChapterIndex = currentChapterIndex,
    isPlaying = playerState.isPlaying,
    visible = miniVisible,
    // UI Round 4 (R11): chapter position drives the card-bottom progress line.
    chapterPositionMs = playerState.chapterPositionMs,
    chapterDurationMs = playerState.chapterDurationMs,
    onPlayPauseClick = { readerViewModel.togglePlayPause() },
    onOpenReader = {
      val slug = miniPlayerBook?.slug
      if (slug != null) {
        navigator.openReader(bookSlug = slug, chapterIndex = currentChapterIndex)
      }
    },
    modifier = Modifier.align(Alignment.BottomCenter)
  )
  UpdatePrompt(
    state = updateUiState,
    onDismiss = { updateViewModel.dismiss() },
    onDownload = { release -> updateViewModel.downloadAndInstall(release) },
    onOpenInstallSettings = { updateViewModel.openInstallPermissionSettings() }
  )
  }
}

@Composable
private fun UpdatePrompt(
  state: UpdateUiState,
  onDismiss: () -> Unit,
  onDownload: (UpdateRelease) -> Unit,
  onOpenInstallSettings: () -> Unit
) {
  when (state) {
    is UpdateUiState.Available -> AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.update_title)) },
      text = {
        Text(stringResource(R.string.update_available_message, state.release.versionName))
      },
      confirmButton = {
        TextButton(onClick = { onDownload(state.release) }) {
          Text(stringResource(R.string.update_download_install))
        }
      },
      dismissButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
      }
    )
    is UpdateUiState.PermissionRequired -> AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.update_permission_title)) },
      text = { Text(stringResource(R.string.update_permission_message)) },
      confirmButton = {
        TextButton(onClick = onOpenInstallSettings) {
          Text(stringResource(R.string.update_open_settings))
        }
      },
      dismissButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
      }
    )
    is UpdateUiState.Downloading -> AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.update_title)) },
      text = {
        Column {
          CircularProgressIndicator()
          Text(
            state.progress?.let { stringResource(R.string.update_downloading, "$it%") }
              ?: stringResource(R.string.update_downloading_unknown)
          )
        }
      },
      confirmButton = {
        TextButton(onClick = onDismiss) {
          Text(stringResource(R.string.update_cancel_download))
        }
      }
    )
    UpdateUiState.UpToDate -> AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.update_up_to_date_title)) },
      text = { Text(stringResource(R.string.update_up_to_date_message)) },
      confirmButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_dismiss)) }
      }
    )
    UpdateUiState.NoPublishedRelease -> AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.update_not_published_title)) },
      text = { Text(stringResource(R.string.update_not_published_message)) },
      confirmButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_dismiss)) }
      }
    )
    is UpdateUiState.Failed -> AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.update_error_title)) },
      text = { Text(state.message) },
      confirmButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_dismiss)) }
      }
    )
    UpdateUiState.Idle, UpdateUiState.Checking -> Unit
  }
}
