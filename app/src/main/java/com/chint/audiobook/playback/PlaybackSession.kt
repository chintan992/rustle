package com.chint.audiobook.playback

import android.content.Context
import com.chint.audiobook.data.model.Book
import com.chint.audiobook.data.model.Bookmark
import com.chint.audiobook.data.model.ChapterRef
import com.chint.audiobook.data.model.PlaybackPosition
import com.chint.audiobook.data.model.PlaybackState
import com.chint.audiobook.data.model.Segment
import com.chint.audiobook.data.model.TtsEngine
import com.chint.audiobook.data.repository.BookmarksRepository
import com.chint.audiobook.data.repository.BookPrefsRepository
import com.chint.audiobook.data.repository.LibraryRepository
import com.chint.audiobook.data.repository.PlaybackPositionRepository
import com.chint.audiobook.data.repository.SettingsRepository
import com.chint.audiobook.engine.AudioCacheManager
import com.chint.audiobook.engine.DeviceConditions
import com.chint.audiobook.engine.ListeningStatsRepository
import com.chint.audiobook.engine.PreloadPolicy
import com.chint.audiobook.engine.SegmentVoices
import com.chint.audiobook.engine.TtsEngineSelection
import com.chint.audiobook.ui.onboarding.VoiceCatalog
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Synthesis gateway: everything the playback session needs from the render
 * service. WS1 (1a): implemented by ReaderViewModel over the bound
 * AudiobookService. WS1 (1b): implemented directly by PlaybackService, so the
 * session no longer depends on the UI process's binding timing.
 */
interface SynthesisGateway {
  fun startSynthesis(
    bookDir: File,
    bookSlug: String,
    chapterIndex: Int,
    segments: List<Segment>,
    voice: String,
    dialogueVoice: String? = null,
    language: String = "en",
    engine: TtsEngine = TtsEngine.SUPERTONIC
  )

  fun cancelSynthesis()

  fun startPreloadSynthesis(
    bookDir: File,
    bookSlug: String,
    chapterIndex: Int,
    segments: List<Segment>,
    voice: String,
    dialogueVoice: String? = null,
    language: String = "en",
    engine: TtsEngine = TtsEngine.SUPERTONIC
  )

  fun cancelPreload()
}

/**
 * WS1 (1a): the playback-lifetime core extracted from ReaderViewModel, moved
 * VERBATIM — every decision helper, guard and comment keeps its round-8/9/10
 * semantics (their tests move with them). This is the piece that must survive
 * the UI in 1b (PlaybackService owns an instance), so it owns: the
 * PlaybackController, the append poll + tracker, the chapter load /
 * auto-advance path, position persistence + heartbeat, the sleep timer, voice
 * re-targeting, background preparation, cache-cap enforcement on load, and the
 * per-book bookmark sidecar.
 *
 * Threading matches the old viewModelScope usage: a Main.immediate scope; the
 * organic chapter-end listener still fires synchronously on the player's
 * application thread.
 */
class PlaybackSession(
  private val context: Context,
  private val libraryRepository: LibraryRepository,
  private val settingsRepository: SettingsRepository,
  private val synthesis: SynthesisGateway
) {

  val playbackController = PlaybackController(context)

  private val bookmarksRepository = BookmarksRepository(File(context.filesDir, "books"))
  // WS3: per-book voice/speed overrides (books/<slug>/prefs.json).
  private val bookPrefsRepository = BookPrefsRepository(File(context.filesDir, "books"))
  // UI Round 4 (R6): per-book position sidecar next to the bookmarks sidecar.
  private val positionRepository = PlaybackPositionRepository(File(context.filesDir, "books"))
  private val json = Json { ignoreUnknownKeys = true }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  // Cache/prep round (B1/B3): eviction + access stamps; preload state for the
  // Contents sheet ("preparing…") and per-chapter audio readiness dots.
  private val audioCacheManager = AudioCacheManager()
  private val _preparingChapterIndex = MutableStateFlow<Int?>(null)
  val preparingChapterIndex: StateFlow<Int?> = _preparingChapterIndex.asStateFlow()
  private val _chaptersWithAudio = MutableStateFlow<Set<Int>>(emptySet())
  val chaptersWithAudio: StateFlow<Set<Int>> = _chaptersWithAudio.asStateFlow()

  /**
   * WS2: measured wall-clock duration of fully-cached chapters (from the
   * readiness refresh walk), for the Contents-sheet duration labels.
   * Unrendered chapters fall back to a word-count estimate at the call site.
   */
  private val _chapterDurationsMs = MutableStateFlow<Map<Int, Long>>(emptyMap())
  val chapterDurationsMs: StateFlow<Map<Int, Long>> = _chapterDurationsMs.asStateFlow()
  private var pollCycle = 0

  // ---- ElevenReader plan (A2): bookmarks -------------------------------------------------
  private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
  /** Bookmarks of the ACTIVE book; reloads with every chapter load. */
  val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

  // ---- ElevenReader plan (A4): sleep timer ------------------------------------------------
  // endAt == 0 → inactive; endAt == SLEEP_TIMER_END_OF_CHAPTER → chapter-end mode;
  // otherwise the epoch_ms deadline of the fixed-duration job.
  private val _sleepTimerEndAt = MutableStateFlow(0L)
  val sleepTimerEndAt: StateFlow<Long> = _sleepTimerEndAt.asStateFlow()
  private var sleepTimerJob: Job? = null

  private val _activeBook = MutableStateFlow<Book?>(null)
  val activeBook: StateFlow<Book?> = _activeBook.asStateFlow()

  private val _currentChapterIndex = MutableStateFlow(1)
  val currentChapterIndex: StateFlow<Int> = _currentChapterIndex.asStateFlow()

  private val _chapterSegments = MutableStateFlow<List<Segment>>(emptyList())
  val chapterSegments: StateFlow<List<Segment>> = _chapterSegments.asStateFlow()

  /**
   * BG0 (ghost-book fix): true when a load resolved book == null (deleted
   * slug). The Navigation layer pops the reader entry instead of rendering an
   * empty transcript over a dead playlist.
   */
  private val _bookMissing = MutableStateFlow(false)
  val bookMissing: StateFlow<Boolean> = _bookMissing.asStateFlow()

  /** WS3: active book's voice override (null = using the global default). */
  private val _bookVoiceOverride = MutableStateFlow<String?>(null)
  val bookVoiceOverride: StateFlow<String?> = _bookVoiceOverride.asStateFlow()

  val playerState: StateFlow<PlayerState> = playbackController.playerState

  private var voiceRetargetJob: Job? = null
  // UI Round 4 (R6): heartbeat sidecar writer while playing.
  private var positionTickJob: Job? = null
  private var lastSynthesizedVoice: String? = null

  /** Q3: non-null for one presentation when the active book reaches its end. */
  private val _endOfBookReached = MutableStateFlow<String?>(null)
  val endOfBookReached: StateFlow<String?> = _endOfBookReached.asStateFlow()

  /** Multi-voice: resolved dialogue voice for the active book (null = off). */
  @Volatile
  private var activeDialogueVoice: String? = null
  private var activeEngine: TtsEngine = TtsEngine.SUPERTONIC

  private val _dialogueVoiceOverride = MutableStateFlow<String?>(null)
  val dialogueVoiceOverride: StateFlow<String?> = _dialogueVoiceOverride.asStateFlow()
  private val appendTracker = PlaylistAppendTracker()

  // Round 8: every playlist load (entry, sheet pick, auto-advance) runs through this
  // single job slot; a new load cancels the previous one, so a manual chapter switch
  // supersedes an in-flight auto-advance and vice versa.
  private var loadJob: Job? = null

  // Round 8 (Task 2): flow-driven position saves are inhibited across a chapter
  // load / auto-advance so a stale old-chapter emission (old sentence/offset read
  // against the ALREADY-advanced chapter index) cannot clobber the freshly
  // persisted new-chapter state. The explicit save inside the load path is the
  // authoritative write and is not routed through this check.
  private var positionSavesSuppressed = false

  init {
    observeNewlyRenderedAudio()
    observeVoiceChanges()
    observePlayerPositionForPersistence()
    observePositionSidecarTick()
    // Round 8 (Task 1a/1c): the chapter end comes from the PLAYER, not the UI —
    // registered once, invoked synchronously on the main thread even while the
    // Activity is gone (service-bound reader in the background).
    playbackController.setOnChapterEnded { onChapterPlaylistEnded() }
  }

  /** Entry path: opening the reader from the Library (fresh key from resolveOpenChapter). */
  fun loadBookAndChapter(bookSlug: String, chapterIndex: Int) {
    // Returning from the mini-player should reveal the active reader without
    // rebuilding its playlist or seeking to a potentially stale saved position.
    if (_activeBook.value?.slug == bookSlug && _currentChapterIndex.value == chapterIndex) {
      return
    }
    loadJob?.cancel()
    loadJob = scope.launch {
      // Round 8: a restored entry key that disagrees with the persisted state while
      // the same book is already active on the persisted chapter is stale (process
      // death after a background auto-advance) — keep playing instead of reloading.
      val activeSlug = _activeBook.value?.slug
      val currentChapter = _currentChapterIndex.value
      if (activeSlug != null && activeSlug == bookSlug && currentChapter != chapterIndex) {
        val saved = settingsRepository.playbackState.first()
        if (shouldIgnoreStaleEntryKey(
            activeBookSlug = activeSlug,
            currentChapterIndex = currentChapter,
            keyBookSlug = bookSlug,
            keyChapterIndex = chapterIndex,
            savedBookSlug = saved.bookSlug,
            savedChapterIndex = saved.chapterIndex
          )
        ) {
          return@launch
        }
      }
      playbackController.clearPendingAutoResume()
      loadChapterInternal(bookSlug, chapterIndex, autoAdvance = false)
    }
  }

  /**
   * Round 8: explicit chapter pick from the Contents sheet. Same load path, but
   * never gated by the stale-entry-key check (the user deliberately picked it).
   */
  fun selectChapter(chapterIndex: Int) {
    val book = _activeBook.value ?: return
    loadJob?.cancel()
    loadJob = scope.launch {
      playbackController.clearPendingAutoResume()
      loadChapterInternal(book.slug, chapterIndex, autoAdvance = false)
    }
  }

  /**
   * Media round (A4): manual chapter transport. Same shared load path as a
   * Contents-sheet pick; playWhenReady is untouched, so playback continues (or
   * stays paused) across the skip just like an auto-advance. No-ops at the
   * respective book edge so the buttons can render disabled-by-absence.
   */
  fun skipToNextChapter() {
    val book = _activeBook.value ?: return
    val next = nextChapterOrNull(_currentChapterIndex.value, book.chapters) ?: return
    loadJob?.cancel()
    loadJob = scope.launch {
      playbackController.clearPendingAutoResume()
      loadChapterInternal(book.slug, next, autoAdvance = false)
    }
  }

  /** Media round (A4): mirror of [skipToNextChapter] toward the book start. */
  fun skipToPreviousChapter() {
    val book = _activeBook.value ?: return
    val previous = previousChapterOrNull(_currentChapterIndex.value, book.chapters) ?: return
    loadJob?.cancel()
    loadJob = scope.launch {
      playbackController.clearPendingAutoResume()
      loadChapterInternal(book.slug, previous, autoAdvance = false)
    }
  }

  /**
   * Shared load path (round 6 guarantees preserved): N9 tracker clear, chapter
   * segments reload, playlist rebuild from whatever pre-rendered WAVs exist,
   * restore guard, synthesis start. [autoAdvance] adds the round-8 extras:
   * no synthesized-voice guard suppression, immediate N13-semantics save, and a
   * conservative stop when the next chapter has no renderable text.
   */
  private suspend fun loadChapterInternal(bookSlug: String, chapterIndex: Int, autoAdvance: Boolean) {
    positionSavesSuppressed = true
    // Cache/prep round (B3): a chapter change invalidates the queued preload
    // target (it may now BE the current chapter, which the active path renders).
    synthesis.cancelPreload()
    _preparingChapterIndex.value = null
    try {
      // Round 8: suspend-only prelude. Nothing here mutates state the append-poll
      // loop reads, so a poll cycle that interleaves during this window keeps the
      // previous, self-consistent chapter snapshot (and appends nothing).
      libraryRepository.scanAndReloadBooks()
      val book = libraryRepository.getBookBySlug(bookSlug)
      // BG0: a load that resolves no book (deleted while a stale reader entry
      // restored) flags the entry for a pop instead of painting an empty
      // reader over whatever playlist is still loaded.
      _bookMissing.value = book == null
      val bookDir = File(context.filesDir, "books/$bookSlug")
      val chapterSlug = "ch${"%04d".format(chapterIndex)}"
      val segmentsFile = File(bookDir, "chapters/$chapterSlug.segments.json")
      var segments = emptyList<Segment>()
      if (book != null && segmentsFile.exists()) {
        segments = runCatching {
          json.decodeFromString<List<Segment>>(segmentsFile.readText())
        }.getOrDefault(emptyList())
      }

      // Round 8: atomic-on-main block — no suspension between tracker clear and
      // append-frontier marking, so the poll loop cannot re-mark a PREVIOUS
      // chapter's still-on-disk WAVs into the new chapter's frontier (on-device
      // round-8 finding: stale re-marks made a mid-chapter append gap satisfy
      // the full-coverage guard and triggered a false end-of-book pause).
      appendTracker.clear() // N9: fresh chapter, fresh append frontier
      _activeBook.value = book
      _currentChapterIndex.value = chapterIndex
      _chapterSegments.value = segments
      // A2: the bookmark list follows the chapter load (active-book sidecar).
      _bookmarks.value = bookmarksRepository.load(bookSlug)

      if (book != null) {
        // Media round (A2): stamp book/chapter metadata before any MediaItems
        // are built, so session + notification show real titles/cover.
        val chapterTitle = book.chapters
          .firstOrNull { it.index == chapterIndex }?.title
          ?: "Chapter $chapterIndex"
        playbackController.updateChapterMetadata(book, chapterIndex, chapterTitle)
        // Round 10 (Task 1): the controller seeds only the contiguous cached anchor
        // prefix; mark exactly those anchors appended so the poll loop appends the
        // rest in canonical order (never a cached far-tail WAV behind a gap).
        // Q3: any new chapter load clears the finished celebration.
      _endOfBookReached.value = null
      // WS3: per-book overrides follow the chapter load. Multi-voice: the
        // dialogue voice is book pref -> global setting (null = feature off).
        val bookPrefs = bookPrefsRepository.load(bookSlug)
        val settingsSnapshot = settingsRepository.appSettings.first()
        activeEngine = TtsEngineSelection.forLanguage(settingsSnapshot.ttsEngine, book.language)
        _bookVoiceOverride.value = bookPrefs.voiceFor(activeEngine)
        val globalDialogue = if (activeEngine == TtsEngine.KOKORO) {
          settingsSnapshot.kokoroDialogueVoice
        } else {
          settingsSnapshot.dialogueVoice
        }
        val dialogueVoice = bookPrefs.dialogueVoiceFor(activeEngine)
          ?.takeIf { VoiceCatalog.contains(activeEngine, it) }
          ?: globalDialogue?.takeIf { VoiceCatalog.contains(activeEngine, it) }
        _dialogueVoiceOverride.value = bookPrefs.dialogueVoiceFor(activeEngine)
        bookPrefs.speed?.let { playbackController.setSpeed(it) }
        // Round 10 (Task 1): the controller seeds only the contiguous cached anchor
        // prefix; mark exactly those anchors appended so the poll loop appends the
        // rest in canonical order (never a cached far-tail WAV behind a gap).
        val seededSegments = playbackController.loadChapterSegments(
          bookDir, chapterIndex, segments, dialogueVoice,
          activeEngine, resolveVoiceForEngine(bookPrefs.voiceFor(activeEngine), settingsSnapshot)
        )
        for (segment in seededSegments) {
          appendTracker.markAppended(segment.hash)
        }

        // Restore saved sentence position only when it matches this book+chapter
        // (round-6 N8 guard semantics, kept for BOTH entry loads and auto-advance):
        // right after a natural advance the saved chapter is the PREVIOUS one, so no
        // seek happens and the new chapter starts at sentence 0; if the saved state
        // happens to name the next chapter (leftover from an earlier session),
        // skipping to its saved sentence is the correct continuation.
        // (Suspends only AFTER the mark pass — the poll loop cannot interleave here
        // with a stale snapshot.)
        if (segments.isNotEmpty()) {
          val savedState = settingsRepository.playbackState.first()
          if (savedState.bookSlug == bookSlug && savedState.chapterIndex == chapterIndex) {
            playbackController.seekToSentence(savedState.sentenceIndex, savedState.offsetMs)
          }
        }

        // Round 8 (N10 interplay): clear the voice guard before starting so a voice
        // change racing with the load cannot swallow the re-target; the call itself
        // re-records the resolved voice — same value, one path.
        lastSynthesizedVoice = null
        activeDialogueVoice = dialogueVoice
        startActiveSynthesisSuspend()

        // Cache/prep round (B1/B2): stamp access, then enforce the cache cap.
        // Keep window = current chapter through current + prepareAhead so the
        // eviction never deletes audio the user is about to hear.
        audioCacheManager.stampAccess(bookDir, chapterIndex)
        val capBytes = settingsSnapshot.cacheCapGb * 1_000_000_000L
        if (capBytes > 0) {
          val keep = mutableSetOf(chapterIndex)
          var cursor = chapterIndex
          repeat(settingsSnapshot.prepareAheadChapters) {
            val next = nextChapterOrNull(cursor, book.chapters) ?: return@repeat
            keep.add(next)
            cursor = next
          }
          audioCacheManager.enforceCap(bookDir, capBytes, keep)
          refreshChaptersWithAudio(book.slug)
        }
      }
    } finally {
      positionSavesSuppressed = false
    }
  }

  /**
   * Round 8 (Task 1a/1c): the playlist reached its natural end while playing.
   * Runs synchronously at the end moment (main thread), so the state observed here
   * is exactly the ended chapter's.
   */
  private fun onChapterPlaylistEnded() {
    if (loadJob?.isActive == true) return // a load / manual chapter switch is in flight
    val book = _activeBook.value ?: return
    val segments = _chapterSegments.value
    if (segments.isEmpty()) return

    // A4: sleep timer in "end of chapter" mode stops HERE instead of advancing.
    val sleepMode = _sleepTimerEndAt.value
    if (sleepMode == SLEEP_TIMER_END_OF_CHAPTER) {
      playbackController.clearPendingAutoResume()
      playbackController.pause()
      cancelSleepTimer()
      return
    }

    // Mid-chapter early end: rendering lagged behind playback, so the playlist does
    // not yet span the whole chapter. The poll loop will append the tail as it
    // renders and playback resumes from the appended item (round-8 resume math) —
    // do not advance yet.
    if (!chapterFullyAppended(appendTracker.size(), segments)) {
      // Round 9 (Task 3.2): an organic end with the chapter tail still rendering
      // would present a dead Play until the next WAV lands. Smooth the gap the
      // same way the auto-advance gap is smoothed — but only when a next chapter
      // exists: the end-of-book park (full coverage, no next chapter) keeps its
      // honest real-Play semantics, and mid-chapter ends on the last chapter fall
      // through to the plain return (existing round-8 resume behavior).
      val hasNextChapter = nextChapterOrNull(_currentChapterIndex.value, book.chapters) != null
      if (shouldSmoothOrganicTailGap(
          organicEnded = true,
          chapterFullyAppended = false,
          hasNextChapter = hasNextChapter
        )
      ) {
        playbackController.markPendingAutoResume()
      }
      return
    }

    val next = nextChapterOrNull(_currentChapterIndex.value, book.chapters)
    if (next == null) {
      onEndOfBookReached()
    } else {
      startAutoAdvance(book, next)
    }
  }

  /** Round 8: advance to [nextChapterIndex] reusing the shared load path. */
  private fun startAutoAdvance(book: Book, nextChapterIndex: Int) {
    // Keep the UI in the playing state across the render gap (Task 1d).
    playbackController.markPendingAutoResume()
    loadJob?.cancel()
    loadJob = scope.launch {
      loadChapterInternal(book.slug, nextChapterIndex, autoAdvance = true)
    }
  }

  fun dismissEndOfBook() {
    _endOfBookReached.value = null
  }

  /**
   * Round 8: end of book — no loop. Park the player paused at the last chapter's
   * end (a later Play restarts that chapter from its start) and persist the final
   * position so "Continue Listening" lands on the last chapter.
   */
  private fun onEndOfBookReached() {
    val book = _activeBook.value ?: return
    playbackController.clearPendingAutoResume()
    playbackController.pause()
    // Q3: one-shot "finished" celebration for the reader to surface.
    _endOfBookReached.value = book.title
    val ps = playerState.value
    scope.launch {
      savePosition(
        bookSlug = book.slug,
        chapterIndex = _currentChapterIndex.value,
        sentenceIndex = ps.currentSentenceIndex,
        offsetMs = ps.currentPositionMs,
        voice = resolvePositionVoice(),
        speed = ps.speed
      )
    }
  }

  fun togglePlayPause() {
    if (playerState.value.isPlaying) {
      playbackController.pause()
    } else {
      val book = _activeBook.value
      val ended = playerState.value.playbackEnded
      if (ended && book != null && nextChapterOrNull(_currentChapterIndex.value, book.chapters) == null) {
        // Ended at end-of-book: Play restarts the last chapter from its beginning
        // instead of replaying only the final sentence at the ended position.
        playbackController.seekToSentence(0)
      }
      playbackController.play()
    }
    _activeBook.value?.let { book ->
      val ps = playerState.value
      scope.launch {
        savePosition(
          bookSlug = book.slug,
          chapterIndex = _currentChapterIndex.value,
          sentenceIndex = ps.currentSentenceIndex,
          offsetMs = ps.currentPositionMs,
          voice = resolvePositionVoice(),
          speed = ps.speed
        )
      }
    }
  }

  fun seekToSentence(sentenceIndex: Int) {
    playbackController.seekToSentence(sentenceIndex)
    _activeBook.value?.let { book ->
      val speed = playerState.value.speed
      scope.launch {
        savePosition(
          bookSlug = book.slug,
          chapterIndex = _currentChapterIndex.value,
          sentenceIndex = sentenceIndex,
          offsetMs = 0L,
          voice = resolvePositionVoice(),
          speed = speed
        )
      }
    }
  }

  fun setSpeed(speed: Float) {
    playbackController.setSpeed(speed)
  }

  // ---- ElevenReader plan (A3): wall-clock seeking passthroughs ---------------------------
  fun seekToPositionMs(targetMs: Long) {
    playbackController.seekToPositionMs(targetMs)
  }

  fun seekByMs(deltaMs: Long) {
    playbackController.seekByMs(deltaMs)
  }

  // ---- ElevenReader plan (A2): bookmark actions ------------------------------------------
  /** Adds a bookmark at the live position; label = opening words of the current sentence. */
  fun addBookmark() {
    val book = _activeBook.value ?: return
    val playerSnapshot = playerState.value
    val sentence = _chapterSegments.value.getOrNull(playerSnapshot.currentSentenceIndex)
    val label = sentence?.text?.take(60)?.trim()
      ?: "Chapter ${_currentChapterIndex.value}"
    val bookmark = Bookmark(
      chapterIndex = _currentChapterIndex.value,
      sentenceIndex = playerSnapshot.currentSentenceIndex,
      offsetMs = playerSnapshot.currentPositionMs,
      label = label,
      createdAt = System.currentTimeMillis()
    )
    scope.launch {
      val updated = _bookmarks.value + bookmark
      _bookmarks.value = updated
      bookmarksRepository.save(book.slug, updated)
    }
  }

  fun deleteBookmark(bookmark: Bookmark) {
    val book = _activeBook.value ?: return
    scope.launch {
      val updated = _bookmarks.value - bookmark
      _bookmarks.value = updated
      bookmarksRepository.save(book.slug, updated)
    }
  }

  /** Sheet tap: same-chapter bookmarks seek in place; cross-chapter ones load first. */
  fun seekToBookmark(bookmark: Bookmark) {
    val book = _activeBook.value ?: return
    if (bookmark.chapterIndex == _currentChapterIndex.value) {
      playbackController.seekToSentence(bookmark.sentenceIndex, bookmark.offsetMs)
      return
    }
    loadJob?.cancel()
    loadJob = scope.launch {
      playbackController.clearPendingAutoResume()
      loadChapterInternal(book.slug, bookmark.chapterIndex, autoAdvance = false)
      // The load restored the saved position guard; the bookmark seek is deliberate.
      playbackController.seekToSentence(bookmark.sentenceIndex, bookmark.offsetMs)
    }
  }

  // ---- ElevenReader plan (A4): sleep timer actions ----------------------------------------
  fun startSleepTimer(durationMs: Long) {
    cancelSleepTimer()
    _sleepTimerEndAt.value = System.currentTimeMillis() + durationMs
    playbackController.setVolume(1.0f)
    sleepTimerJob = scope.launch {
      val fadeDurationMs = 20_000L
      if (durationMs > fadeDurationMs) {
        delay(durationMs - fadeDurationMs)
        val steps = 20
        val stepDelay = fadeDurationMs / steps
        for (i in 1..steps) {
          delay(stepDelay)
          val fraction = (steps - i).toFloat() / steps
          playbackController.setVolume(fraction)
        }
      } else {
        delay(durationMs)
      }
      playbackController.pause()
      playbackController.setVolume(1.0f)
      _sleepTimerEndAt.value = 0L
    }
  }

  fun startSleepTimerToEndOfChapter() {
    cancelSleepTimer()
    _sleepTimerEndAt.value = SLEEP_TIMER_END_OF_CHAPTER
    // No job: the chapter-end listener (onChapterPlaylistEnded) pauses in this mode.
  }

  fun cancelSleepTimer() {
    sleepTimerJob?.cancel()
    sleepTimerJob = null
    _sleepTimerEndAt.value = 0L
    playbackController.setVolume(1.0f)
  }

  /**
   * Q6: shake-to-resume — a shake while a sleep timer has PAUSED playback
   * cancels the timer and resumes (Smart AudioBook Player's beloved gesture).
   * No-op when no timer is armed (shakes during normal use do nothing).
   */
  fun resumeFromShake() {
    if (_sleepTimerEndAt.value == 0L) return
    cancelSleepTimer()
    playbackController.setVolume(1.0f)
    playbackController.play()
  }

  fun play() {
    playbackController.play()
  }

  fun pause() {
    playbackController.pause()
  }

  fun setSkipSilence(enabled: Boolean) {
    playbackController.setSkipSilence(enabled)
  }

  fun isSkipSilenceEnabled(): Boolean = playbackController.isSkipSilenceEnabled()

  /** Q6: whether a sleep timer is currently armed (drives the shake listener). */
  val sleepTimerArmed: Boolean
    get() = _sleepTimerEndAt.value != 0L

  /**
   * Media round (A5)/BG0 (ghost-book fix): park everything when the ACTIVE
   * book is deleted from the Library. Order matters twice over:
   * 1. cancel the render FIRST — its loop mkdirs its way back into the deleted
   *    tree and would resurrect orphan WAV dirs;
   * 2. CLEAR the playlist (releases ExoPlayer's file handles on the book's
   *    WAVs) BEFORE the Library's recursive delete runs — a paused player pins
   *    those files, the delete fails partway, and book.json survives as a
   *    ghost that still shows on the shelf and still plays.
   * Also stands down every writer that could resurrect state for the dead
   * book: append poll (empty segments), position saves/ticker (null book),
   * sleep timer, preload, voice guard.
   */
  fun parkForDeletion(slug: String) {
    if (_activeBook.value?.slug != slug) return
    synthesis.cancelSynthesis()
    synthesis.cancelPreload()
    loadJob?.cancel()
    cancelSleepTimer()
    playbackController.clearPendingAutoResume()
    playbackController.clearPlaylist()
    lastSynthesizedVoice = null
    _activeBook.value = null
    _chapterSegments.value = emptyList()
    _preparingChapterIndex.value = null
    activeDialogueVoice = null
    _dialogueVoiceOverride.value = null
    // Polish: the per-book search index dies with the book.
    searchIndexSlug = null
    searchIndex = emptyMap()
  }

  /**
   * WS3: voice pick from the sheet. Global scope just updates the setting —
   * the settings observer re-targets synthesis. Book scope persists the
   * override and re-targets immediately (same re-render semantics as the
   * observer, which only watches the global setting).
   */
  fun selectVoice(voice: String, applyToAllBooks: Boolean) {
    val book = _activeBook.value ?: return
    if (applyToAllBooks) {
      scope.launch {
        settingsRepository.updateAppSettings {
          val engine = TtsEngineSelection.forLanguage(it.ttsEngine, book.language)
          if (engine == TtsEngine.KOKORO) it.copy(kokoroVoice = voice)
          else it.copy(defaultVoice = voice)
        }
      }
      return
    }
    scope.launch {
      val settings = settingsRepository.appSettings.first()
      val engine = TtsEngineSelection.forLanguage(settings.ttsEngine, book.language)
      if (_activeBook.value?.slug != book.slug) return@launch
      val prefs = bookPrefsRepository.load(book.slug)
      val updatedPrefs = if (engine == TtsEngine.KOKORO) {
        prefs.copy(kokoroVoice = voice)
      } else {
        prefs.copy(voice = voice)
      }
      bookPrefsRepository.save(book.slug, updatedPrefs)
      _bookVoiceOverride.value = voice
      if (engine != activeEngine) {
        loadChapterInternal(book.slug, _currentChapterIndex.value, autoAdvance = false)
        return@launch
      }
      val segments = _chapterSegments.value
      if (segments.isEmpty() || voice == lastSynthesizedVoice) return@launch
      lastSynthesizedVoice = voice
      synthesis.startSynthesis(
        bookDir = File(context.filesDir, "books/${book.slug}"),
        bookSlug = book.slug,
        chapterIndex = _currentChapterIndex.value,
        segments = segments,
        voice = voice,
        dialogueVoice = activeDialogueVoice,
        language = book.language,
        engine = engine
      )
    }
  }

  /**
   * Re-prep the book with [language] (a 31-language catalog code or "auto"),
   * re-detecting or resetting hashes and reloading the active chapter.
   */
  fun changeBookLanguage(language: String) {
    val book = _activeBook.value ?: return
    scope.launch {
      playbackController.pause()
      synthesis.cancelSynthesis()
      synthesis.cancelPreload()
      val settings = settingsRepository.appSettings.first()
      val targetEngine = TtsEngineSelection.forLanguage(settings.ttsEngine, language)
      val bookPrefs = bookPrefsRepository.load(book.slug)
      val effectiveVoice = resolveVoiceForEngine(bookPrefs.voiceFor(targetEngine), settings, targetEngine)
      val expressive = settings.expressiveNarration
      val reprep = com.chint.audiobook.pipeline.BookReprepper(context)
      reprep.reprepBook(book.slug, language, expressive, effectiveVoice)
      val reloadedBook = libraryRepository.getBookBySlug(book.slug)
      if (reloadedBook != null) {
        _activeBook.value = reloadedBook
      }
      loadChapterInternal(book.slug, _currentChapterIndex.value, autoAdvance = false)
    }
  }

  /**
   * Multi-voice: dialogue-voice pick. null disables multi-voice for the
   * scope; a voice id enables it. Book scope persists the override and
   * re-targets synthesis; All-books scope updates the global setting (the
   * settings observer re-targets).
   */
  fun selectDialogueVoice(voice: String?, applyToAllBooks: Boolean) {
    val book = _activeBook.value ?: return
    if (applyToAllBooks) {
      scope.launch {
        settingsRepository.updateAppSettings {
          val engine = TtsEngineSelection.forLanguage(it.ttsEngine, book.language)
          if (engine == TtsEngine.KOKORO) it.copy(kokoroDialogueVoice = voice)
          else it.copy(dialogueVoice = voice)
        }
      }
      return
    }
    scope.launch {
      val settings = settingsRepository.appSettings.first()
      val engine = TtsEngineSelection.forLanguage(settings.ttsEngine, book.language)
      if (_activeBook.value?.slug != book.slug) return@launch
      val prefs = bookPrefsRepository.load(book.slug)
      val updatedPrefs = if (engine == TtsEngine.KOKORO) {
        prefs.copy(kokoroDialogueVoice = voice)
      } else {
        prefs.copy(dialogueVoice = voice)
      }
      bookPrefsRepository.save(book.slug, updatedPrefs)
      _dialogueVoiceOverride.value = voice
      if (engine != activeEngine) {
        loadChapterInternal(book.slug, _currentChapterIndex.value, autoAdvance = false)
        return@launch
      }
      activeDialogueVoice = voice
      val segments = _chapterSegments.value
      if (segments.isEmpty()) return@launch
      lastSynthesizedVoice = null // force re-render of the chapter under the new dialogue voice
      synthesis.startSynthesis(
        bookDir = File(context.filesDir, "books/${book.slug}"),
        bookSlug = book.slug,
        chapterIndex = _currentChapterIndex.value,
        segments = segments,
        voice = lastSynthesizedVoice ?: resolveCurrentVoice(),
        dialogueVoice = voice,
        language = book.language,
        engine = engine
      )
    }
  }

  /** WS3: speed pick — always applies live; book scope persists the override. */
  fun selectSpeed(speed: Float, applyToAllBooks: Boolean) {
    playbackController.setSpeed(speed)
    val book = _activeBook.value ?: return
    if (!applyToAllBooks) {
      scope.launch {
        bookPrefsRepository.save(book.slug, bookPrefsRepository.load(book.slug).copy(speed = speed))
      }
    }
  }

  /** WS3: book-pref voice wins over the global default narrator. */
  private suspend fun resolveCurrentVoice(): String {
    val settings = settingsRepository.appSettings.first()
    val book = _activeBook.value
    val engine = book?.let { TtsEngineSelection.forLanguage(settings.ttsEngine, it.language) }
      ?: settings.ttsEngine
    activeEngine = engine
    val prefsVoice = book?.let { bookPrefsRepository.load(it.slug).voiceFor(engine) }
    return resolveVoiceForEngine(prefsVoice, settings, engine)
  }

  private fun resolveVoiceForEngine(
    bookVoice: String?,
    settings: com.chint.audiobook.data.model.AppSettings,
    engine: TtsEngine = activeEngine
  ): String {
    val globalVoice = if (engine == TtsEngine.KOKORO) settings.kokoroVoice else settings.defaultVoice
    return bookVoice?.takeIf { VoiceCatalog.contains(engine, it) }
      ?: globalVoice.takeIf { VoiceCatalog.contains(engine, it) }
      ?: VoiceCatalog.defaultId(engine)
  }

  private suspend fun startActiveSynthesisSuspend() {
    val book = _activeBook.value ?: return
    val chapterIdx = _currentChapterIndex.value
    val segments = _chapterSegments.value
    if (segments.isEmpty()) return

    val bookDir = File(context.filesDir, "books/${book.slug}")
    val voice = resolveCurrentVoice()
    lastSynthesizedVoice = voice

    synthesis.startSynthesis(
      bookDir = bookDir,
      bookSlug = book.slug,
      chapterIndex = chapterIdx,
      segments = segments,
      voice = voice,
      dialogueVoice = activeDialogueVoice,
      language = book.language,
      engine = activeEngine
    )
  }

  private fun observeVoiceChanges() {
    // Re-target running synthesis when narration OR dialogue voice changes
    // (APP_PLAN §9.6 N10 semantics, widened by the multi-voice round: a
    // dialogue-voice change re-renders the chapter's dialogue segments).
    voiceRetargetJob = scope.launch {
      settingsRepository.appSettings
        .map { it }
        .distinctUntilChanged()
        .collectLatest { settings ->
          val book = _activeBook.value ?: return@collectLatest
          val segments = _chapterSegments.value
          if (segments.isEmpty()) return@collectLatest
          val engine = TtsEngineSelection.forLanguage(settings.ttsEngine, book.language)
          val bookPrefs = bookPrefsRepository.load(book.slug)
          val voice = resolveVoiceForEngine(bookPrefs.voiceFor(engine), settings, engine)
          val globalDialogue = if (engine == TtsEngine.KOKORO) {
            settings.kokoroDialogueVoice
          } else {
            settings.dialogueVoice
          }
          val dialogueVoice = bookPrefs.dialogueVoiceFor(engine)
            ?.takeIf { VoiceCatalog.contains(engine, it) }
            ?: globalDialogue?.takeIf { VoiceCatalog.contains(engine, it) }
          if (engine != activeEngine) {
            loadChapterInternal(book.slug, _currentChapterIndex.value, autoAdvance = false)
            return@collectLatest
          }
          val narrationUnchanged = voice == lastSynthesizedVoice
          val dialogueUnchanged = dialogueVoice == activeDialogueVoice
          if (narrationUnchanged && dialogueUnchanged) return@collectLatest

          lastSynthesizedVoice = voice
          activeEngine = engine
          activeDialogueVoice = dialogueVoice
          _bookVoiceOverride.value = bookPrefs.voiceFor(engine)
          _dialogueVoiceOverride.value = bookPrefs.dialogueVoiceFor(engine)
          val bookDir = File(context.filesDir, "books/${book.slug}")
          synthesis.startSynthesis(
            bookDir = bookDir,
            bookSlug = book.slug,
            chapterIndex = _currentChapterIndex.value,
            segments = segments,
            voice = voice,
            dialogueVoice = dialogueVoice,
            language = book.language,
            engine = engine
          )
        }
    }
  }

  private fun observePlayerPositionForPersistence() {
    // Persist position on sentence / media item transitions.
    // Round 8: inhibited across loads / auto-advances (see positionSavesSuppressed).
    scope.launch {
      playerState
        .map { Pair(it.currentSentenceIndex, it.currentPositionMs) }
        .distinctUntilChanged()
        .collectLatest { (sentenceIdx, offsetMs) ->
          val book = _activeBook.value
          if (book != null && !positionSavesSuppressed) {
            savePosition(
              bookSlug = book.slug,
              chapterIndex = _currentChapterIndex.value,
              sentenceIndex = sentenceIdx,
              offsetMs = offsetMs,
              voice = resolvePositionVoice(),
              speed = playerState.value.speed
            )
          }
        }
    }
  }

  private fun observeNewlyRenderedAudio() {
    scope.launch {
      while (isActive) {
        val book = _activeBook.value
        val chapterIdx = _currentChapterIndex.value
        val segments = _chapterSegments.value

        if (book != null && segments.isNotEmpty()) {
          // Round 10 (Task 1): ordered frontier appends — a hash enters the playlist
          // only when everything before it is already appended and its own WAV is on
          // disk; appends stop at the first not-yet-rendered gap. The playlist is
          // therefore always the canonical-order projection of a contiguous prefix,
          // in ANY mixed cached-set x render-completion-order state.
          val anchors = PlaylistOrderPlanner.distinctAnchors(segments)
          val frontier = PlaylistOrderPlanner.frontierAppends(anchors, appendTracker.size()) { hash ->
            existingWavFile(book.slug, chapterIdx, hash) != null
          }
          for (anchor in frontier) {
            val wavFileToAppend = existingWavFile(book.slug, chapterIdx, anchor.hash)
            // Re-probe raced with the planner: treat the gap as still open and try
            // again next cycle rather than appending out of order.
            if (wavFileToAppend == null) break
            if (!playbackController.appendSegmentWav(wavFileToAppend, anchor.hash)) break
            appendTracker.markAppended(anchor.hash)
          }

          // Cache/prep round (B3): while the CURRENT chapter is fully appended
          // (engine otherwise idle from the reader's perspective), prepare the
          // next N chapters per settings + device conditions. The service
          // serializes preloads behind any remaining active synthesis.
          if (chapterFullyAppended(appendTracker.size(), segments)) {
            maybeStartPreload(book, chapterIdx)
          }
        }

        // Cache/prep round (B3): refresh the Contents-sheet readiness dots every
        // ~10 s (every 20th 500 ms cycle) — cheap directory scan, off the hot path.
        if (book != null && pollCycle++ % 20 == 0) {
          refreshChaptersWithAudio(book.slug)
        }

        delay(POLL_INTERVAL_MS)
      }
    }
  }

  /** First existing WAV for a segment hash — voice-aware (multi-voice contract). */
  private fun existingWavFile(bookSlug: String, chapterIndex: Int, hash: String): File? {
    val bookDir = File(context.filesDir, "books/$bookSlug")
    val chapterAudioDir = SegmentVoices.chapterAudioDir(bookDir, chapterIndex, activeEngine)
    val globalCacheAudioDir = SegmentVoices.globalCacheAudioDir(bookDir, activeEngine)
    val text = _chapterSegments.value.firstOrNull { it.hash == hash }?.text ?: ""

    return SegmentVoices.findWav(
      chapterAudioDir, globalCacheAudioDir, hash, text, activeDialogueVoice,
      activeEngine, lastSynthesizedVoice ?: VoiceCatalog.defaultId(activeEngine)
    )
  }

  /**
   * Cache/prep round (B3): evaluate the preload policy each poll cycle while
   * the current chapter is fully appended, and keep exactly ONE upcoming
   * chapter rendering at a time. Conditions that lapse (unplugged, metered,
   * battery saver, setting off) cancel the preload.
   */
  private suspend fun maybeStartPreload(book: Book, currentChapterIndex: Int) {
    val settings = settingsRepository.appSettings.first()
    val allowed = PreloadPolicy.shouldPreload(
      prepareAheadChapters = settings.prepareAheadChapters,
      wifiOnly = settings.prepareWifiOnly,
      onlyWhileCharging = settings.prepareOnlyWhileCharging,
      batterySaver = settings.batterySaverEnabled,
      isCharging = DeviceConditions.isCharging(context),
      isMetered = DeviceConditions.isMetered(context)
    )
    if (!allowed) {
      synthesis.cancelPreload()
      _preparingChapterIndex.value = null
      return
    }

    val bookDir = File(context.filesDir, "books/${book.slug}")
    val narrationVoice = resolveCurrentVoice()
    var cursor = currentChapterIndex
    var ahead = 0
    while (ahead < settings.prepareAheadChapters) {
      val next = nextChapterOrNull(cursor, book.chapters) ?: break
      val chapterSegments = readSegmentsFile(book.slug, next)
      if (chapterSegments.isEmpty()) break
      val nextAudioDir = SegmentVoices.chapterAudioDir(bookDir, next, activeEngine)
      val isCompressed = com.chint.audiobook.engine.AudioCompressor.isChapterCompressedFor(
        nextAudioDir,
        activeEngine,
        narrationVoice,
        activeDialogueVoice,
        chapterSegments.distinctBy { it.hash }.map { it.hash }
      )
      val hasMissing = !isCompressed && chapterSegments
        .distinctBy { it.hash }
        .any { existingWavFile(book.slug, next, it.hash) == null }
      if (hasMissing) {
        _preparingChapterIndex.value = next
        synthesis.startPreloadSynthesis(
          bookDir = bookDir,
          bookSlug = book.slug,
          chapterIndex = next,
          segments = chapterSegments,
          voice = narrationVoice,
          dialogueVoice = activeDialogueVoice,
          language = book.language,
          engine = activeEngine
        )
        return
      }
      ahead++
      cursor = next
    }
    // Everything ahead is already rendered.
    if (_preparingChapterIndex.value != null) {
      synthesis.cancelPreload()
      _preparingChapterIndex.value = null
    }
  }

  // ---- WS3: in-book full-text search ---------------------------------------------------

  /** One search result: where it is and a window around the match. */
  data class SearchHit(
    val chapterIndex: Int,
    val chapterTitle: String,
    val sentenceIndex: Int,
    val snippet: String
  )

  private val _searchRunning = MutableStateFlow(false)
  val searchRunning: StateFlow<Boolean> = _searchRunning.asStateFlow()

  /**
   * Polish round: per-book in-memory search index. The FIRST query of a book
   * session pays the one full scan (same cost as every query paid before);
   * subsequent queries run against memory with zero file IO — this is what
   * makes incremental typing on a 3,220-chapter book responsive. Keyed by
   * slug; a book's segmented text is immutable per import, so no invalidation
   * exists. Cleared on deletion park. Memory note: worst case (Shadow-Scale,
   * ~4M words) is tens of MB of strings — acceptable while a book is open.
   */
  private var searchIndexSlug: String? = null
  private var searchIndex: Map<Int, List<Segment>> = emptyMap()

  /**
   * WS3: case-insensitive search over every chapter, capped at [maxResults].
   * The first query builds the per-book index (IO); later queries are pure
   * memory matching. The sheet debounces typing; the running flag covers the
   * index build.
   */
  fun searchBook(query: String, maxResults: Int = 200, onDone: (List<SearchHit>) -> Unit) {
    val book = _activeBook.value ?: return onDone(emptyList())
    if (query.isBlank()) return onDone(emptyList())
    val needle = query.trim().lowercase()
    _searchRunning.value = true
    scope.launch {
      val hits = withContext(Dispatchers.IO) {
        if (searchIndexSlug != book.slug) {
          searchIndex = book.chapters.associate { chapter ->
            chapter.index to readSegmentsFile(book.slug, chapter.index)
          }
          searchIndexSlug = book.slug
        }
        val titles = book.chapters.associate { it.index to it.title }
        val results = mutableListOf<SearchHit>()
        outer@ for (chapter in book.chapters) {
          val segments = searchIndex[chapter.index] ?: continue
          for ((i, segment) in segments.withIndex()) {
            val at = segment.text.lowercase().indexOf(needle)
            if (at >= 0) {
              results.add(
                SearchHit(
                  chapterIndex = chapter.index,
                  chapterTitle = titles[chapter.index] ?: "Chapter ${chapter.index}",
                  sentenceIndex = i,
                  snippet = snippetAround(segment.text, at, needle.length)
                )
              )
              if (results.size >= maxResults) break@outer
            }
          }
        }
        results
      }
      _searchRunning.value = false
      onDone(hits)
    }
  }

  /** WS3: result tap — same composite path as a cross-chapter bookmark. */
  fun playSearchHit(hit: SearchHit) {
    val book = _activeBook.value ?: return
    if (hit.chapterIndex == _currentChapterIndex.value) {
      playbackController.seekToSentence(hit.sentenceIndex)
      return
    }
    loadJob?.cancel()
    loadJob = scope.launch {
      playbackController.clearPendingAutoResume()
      loadChapterInternal(book.slug, hit.chapterIndex, autoAdvance = false)
      playbackController.seekToSentence(hit.sentenceIndex)
    }
  }

  /** Reads chapters/chNNNN.segments.json; missing/corrupt reads as empty. */
  private fun readSegmentsFile(bookSlug: String, chapterIndex: Int): List<Segment> {
    val bookDir = File(context.filesDir, "books/$bookSlug")
    val segmentsFile = File(bookDir, "chapters/ch${"%04d".format(chapterIndex)}.segments.json")
    if (!segmentsFile.exists()) return emptyList()
    return runCatching {
      json.decodeFromString<List<Segment>>(segmentsFile.readText())
    }.getOrDefault(emptyList())
  }

  /** Cache/prep round (B3): chapters with at least one current-engine audio file (sheet dots). */
  private suspend fun refreshChaptersWithAudio(bookSlug: String) {
    val engine = activeEngine
    val narrationVoice = lastSynthesizedVoice ?: VoiceCatalog.defaultId(engine)
    val dialogueVoice = activeDialogueVoice
    val (chapterIndexes, durations) = withContext(Dispatchers.IO) {
      val bookDir = File(context.filesDir, "books/$bookSlug")
      val audioRoot = SegmentVoices.engineAudioDir(bookDir, engine)
      val audioDirs = audioRoot.listFiles { f -> f.isDirectory }.orEmpty()
      val expectedHashesByDir = audioDirs.associateWith { dir ->
        if (com.chint.audiobook.engine.AudioCompressor.isChapterCompressed(dir)) {
          val chapterIndex = dir.name.removePrefix("ch").toIntOrNull()
          chapterIndex?.let { index ->
            readSegmentsFile(bookSlug, index).distinctBy { it.hash }.map { it.hash }
          }.orEmpty()
        } else {
          emptyList()
        }
      }
      val chapterDirs = audioDirs.filter { dir ->
        val expectedHashes = expectedHashesByDir.getValue(dir)
        com.chint.audiobook.engine.AudioCompressor.isChapterCompressedFor(
          dir,
          engine,
          narrationVoice,
          dialogueVoice,
          expectedHashes
        ) ||
          dir.listFiles()?.any { file ->
            if (!file.isFile || file.length() <= 0L || !file.name.endsWith(".wav", ignoreCase = true)) {
              false
            } else if (engine == TtsEngine.KOKORO) {
              file.name.endsWith("~kokoro-${SegmentVoices.KOKORO_CACHE_VERSION}-$narrationVoice.wav") ||
                (dialogueVoice != null &&
                  file.name.endsWith("~kokoro-${SegmentVoices.KOKORO_CACHE_VERSION}-$dialogueVoice.wav"))
            } else {
              !file.name.contains("~") ||
                (dialogueVoice != null && file.name.endsWith("~$dialogueVoice.wav"))
            }
          } == true
      }
      val chapterIndexes = chapterDirs
        .mapNotNull { dir -> dir.name.removePrefix("ch").toIntOrNull() }
        .toSet()
      val durations = chapterDirs.associate { dir ->
        val index = dir.name.removePrefix("ch").toIntOrNull() ?: -1
        val compressedIndex =
          if (com.chint.audiobook.engine.AudioCompressor.isChapterCompressedFor(
              dir,
              engine,
              narrationVoice,
              dialogueVoice,
              expectedHashesByDir.getValue(dir)
            )
          ) {
            com.chint.audiobook.engine.AudioCompressor.loadIndex(dir)
          } else {
            null
          }
        val total = if (compressedIndex != null && compressedIndex.totalDurationMs > 0L) {
          compressedIndex.totalDurationMs
        } else {
          // Multi-voice: skip stale-voice orphans (a <hash>~<oldvoice>.wav left
          // behind by a dialogue-voice change) — count narration files and
          // current-dialogue files only.
          dir.listFiles()
            ?.filter { file ->
              if (file.length() <= 0L || !file.name.endsWith(".wav", ignoreCase = true)) {
                false
              } else if (engine == TtsEngine.KOKORO) {
                file.name.endsWith("~kokoro-${SegmentVoices.KOKORO_CACHE_VERSION}-$narrationVoice.wav") ||
                  (dialogueVoice != null &&
                    file.name.endsWith("~kokoro-${SegmentVoices.KOKORO_CACHE_VERSION}-$dialogueVoice.wav"))
              } else {
                !file.name.contains("~") ||
                  (dialogueVoice != null && file.name.endsWith("~$dialogueVoice.wav"))
              }
            }
            ?.sumOf { WavDuration.durationMs(it) ?: 0L } ?: 0L
        }
        index to total
      }.filterKeys { it >= 0 }
      chapterIndexes to durations
    }
    if (activeEngine == engine &&
      (lastSynthesizedVoice ?: VoiceCatalog.defaultId(activeEngine)) == narrationVoice &&
      activeDialogueVoice == dialogueVoice
    ) {
      _chaptersWithAudio.value = chapterIndexes
      _chapterDurationsMs.value = durations
    }
  }

  /**
   * N13: persist the voice/speed that are actually in play, not the
   * PlaybackState defaults ("F2"/1.0 clobbered the persisted values).
   * The synthesis voice is the resolved default (lastSynthesizedVoice when a
   * synthesis pass used it, else the current settings value); speed is
   * ExoPlayer's live playback speed.
   */
  private suspend fun resolvePositionVoice(): String =
    lastSynthesizedVoice ?: settingsRepository.appSettings.first().defaultVoice

  private suspend fun savePosition(
    bookSlug: String,
    chapterIndex: Int,
    sentenceIndex: Int,
    offsetMs: Long,
    voice: String,
    speed: Float
  ) {
    // BG0: saves suspend on DataStore writes — one in flight when its book is
    // deleted resumes AFTER the recursive delete, mkdir-ing the sidecar back
    // (ghost dir) and re-polluting the global playback state the Library just
    // cleared. Write-time guard: a book whose dir is gone is not writable.
    val bookDir = File(context.filesDir, "books/$bookSlug")
    if (!bookDir.exists()) return
    settingsRepository.savePlaybackState(
      PlaybackState(
        bookSlug = bookSlug,
        chapterIndex = chapterIndex,
        sentenceIndex = sentenceIndex,
        offsetMs = offsetMs,
        voice = voice,
        speed = speed
      )
    )
    // Re-check after the DataStore suspend — the delete may have landed inside
    // that window (the race this guard exists for).
    if (!bookDir.exists()) return
    // UI Round 4 (R6): every global position save also writes the per-book
    // sidecar (pause, seek-to-sentence, sentence transitions, auto-advance,
    // end-of-book). segmentCount is only known for the chapter currently
    // loaded; a mismatched chapter write carries 0 and progress degrades to
    // words-before-current only. seekByMs deltas are covered by the 10 s
    // ticker below / the next sentence transition instead of a write here.
    val segmentCount = if (chapterIndex == _currentChapterIndex.value) {
      _chapterSegments.value.size
    } else {
      0
    }
    positionRepository.save(
      bookSlug,
      PlaybackPosition(
        chapterIndex = chapterIndex,
        sentenceIndex = sentenceIndex,
        offsetMs = offsetMs,
        segmentCount = segmentCount,
        updatedAt = System.currentTimeMillis()
      )
    )
  }

  /**
   * UI Round 4 (R6): heartbeat sidecar writes while playing — a scrub/±10 s
   * seek never produces a sentence transition, so without this the sidecar
   * would lag until the next sentence boundary. Writes at most every 10 s and
   * only when (chapter, sentence, offset) actually moved.
   */
  private fun observePositionSidecarTick() {
    positionTickJob = scope.launch {
      var lastSnapshot: List<Any>? = null
      // WS2: wall-clock anchor for listening-time accounting — the same 10 s
      // tick that persists the position also accrues stats while playing.
      var lastStatsAtMs = System.currentTimeMillis()
      val stats = ListeningStatsRepository(File(context.filesDir, "stats"))
      while (isActive) {
        delay(POSITION_TICK_MS)
        val book = _activeBook.value ?: continue
        val ps = playerState.value
        if (ps.isPlaying && !positionSavesSuppressed) {
          val now = System.currentTimeMillis()
          // Clamp absorbs a dozy tick gap without inventing listening time.
          val listened = (now - lastStatsAtMs).coerceIn(0L, 30_000L)
          if (listened > 0L) stats.addListening(book.slug, listened)
          lastStatsAtMs = now
        } else {
          lastStatsAtMs = System.currentTimeMillis()
        }
        if (!ps.isPlaying || positionSavesSuppressed) continue
        val snapshot = listOf(
          book.slug,
          _currentChapterIndex.value,
          ps.currentSentenceIndex,
          ps.currentPositionMs
        )
        if (snapshot == lastSnapshot) continue
        lastSnapshot = snapshot
        // BG0: same write-time guard as savePosition — the 10 s tick can be in
        // flight across a book deletion and would otherwise resurrect the dir.
        val bookDir = File(context.filesDir, "books/${book.slug}")
        if (!bookDir.exists()) continue
        positionRepository.save(
          book.slug,
          PlaybackPosition(
            chapterIndex = _currentChapterIndex.value,
            sentenceIndex = ps.currentSentenceIndex,
            offsetMs = ps.currentPositionMs,
            segmentCount = _chapterSegments.value.size,
            updatedAt = System.currentTimeMillis()
          )
        )
      }
    }
  }

  /**
   * WS1 (1a): the old onCleared flush — final sidecar snapshot with the live
   * player state before the session (and its controller) go away. In 1b this
   * is called by the PlaybackService teardown instead of the ViewModel's.
   */
  fun shutdown() {
    // UI Round 4 (R6): flush the sidecar with the live player snapshot before
    // the controller is released — covers a process teardown mid-sentence when
    // no other save fired during the final 10 s window.
    val book = _activeBook.value
    if (book != null && File(context.filesDir, "books/${book.slug}").exists()) {
      val ps = playerState.value
      positionRepository.save(
        book.slug,
        PlaybackPosition(
          chapterIndex = _currentChapterIndex.value,
          sentenceIndex = ps.currentSentenceIndex,
          offsetMs = ps.currentPositionMs,
          segmentCount = _chapterSegments.value.size,
          updatedAt = System.currentTimeMillis()
        )
      )
    }
    voiceRetargetJob?.cancel()
    positionTickJob?.cancel()
    sleepTimerJob?.cancel()
    playbackController.setOnChapterEnded(null)
    scope.cancel()
    playbackController.release()
  }

  companion object {
    private const val POLL_INTERVAL_MS = 500L

    /** UI Round 4 (R6): sidecar heartbeat period while playing. */
    private const val POSITION_TICK_MS = 10_000L

    /** A4 sentinel: sleep timer waits for the chapter end (no countdown job). */
    const val SLEEP_TIMER_END_OF_CHAPTER = -1L

    /**
     * WS3: match window — ~40 chars before the hit, ~60 after, ellipses when
     * clipped. Pure so the window contract is unit-tested.
     */
    internal fun snippetAround(text: String, at: Int, needleLength: Int): String {
      val start = (at - 40).coerceAtLeast(0)
      val end = (at + needleLength + 60).coerceAtMost(text.length)
      val prefix = if (start > 0) "…" else ""
      val suffix = if (end < text.length) "…" else ""
      return prefix + text.substring(start, end) + suffix
    }

    /**
     * Round 8 (Task 1c): the next chapter after a natural playlist end, or null at
     * end of book. Uses ChapterRef.index values (not list position) so books with
     * non-contiguous chapter numbering still advance correctly.
     */
    fun nextChapterOrNull(currentChapterIndex: Int, chapters: List<ChapterRef>): Int? =
      chapters.map { it.index }.filter { it > currentChapterIndex }.minOrNull()

    /**
     * Media round (A4): the chapter before [currentChapterIndex], or null at the
     * book start. ChapterRef.index values (not list positions), mirroring
     * [nextChapterOrNull] for non-contiguous chapter numbering.
     */
    fun previousChapterOrNull(currentChapterIndex: Int, chapters: List<ChapterRef>): Int? =
      chapters.map { it.index }.filter { it < currentChapterIndex }.maxOrNull()

    /**
     * Round 8: true when the append frontier covers every distinct segment hash of
     * the chapter — i.e. the ended playlist spanned the WHOLE chapter. Duplicate
     * sentences share one hash/WAV and count once.
     */
    internal fun chapterFullyAppended(appendedCount: Int, segments: List<Segment>): Boolean =
      appendedCount >= segments.map { it.hash }.distinct().size

    /**
     * Round 9 (Task 3.2): presentation decision for the tail-gap flicker. An
     * ORGANIC playlist end while the chapter is NOT fully appended gets the
     * pending-auto-resume smoothing, but only when a NEXT chapter exists: the
     * end-of-book park (full coverage, no next chapter) keeps its honest
     * real-Play semantics, and the last chapter's mid-chapter tail keeps the
     * plain round-8 resume behavior. Clears on STATE_READY as today.
     */
    internal fun shouldSmoothOrganicTailGap(
      organicEnded: Boolean,
      chapterFullyAppended: Boolean,
      hasNextChapter: Boolean
    ): Boolean = organicEnded && !chapterFullyAppended && hasNextChapter

    /**
     * Round 8 (Task 2): a reader-entry key that disagrees with the persisted state
     * while the same book is already active ON the persisted chapter is a stale
     * restored key (process death after a background auto-advance) — ignore it and
     * keep the current playback.
     */
    internal fun shouldIgnoreStaleEntryKey(
      activeBookSlug: String?,
      currentChapterIndex: Int,
      keyBookSlug: String,
      keyChapterIndex: Int,
      savedBookSlug: String,
      savedChapterIndex: Int
    ): Boolean =
      activeBookSlug == keyBookSlug &&
        keyChapterIndex != currentChapterIndex &&
        savedBookSlug == activeBookSlug &&
        savedChapterIndex == currentChapterIndex
  }
}
