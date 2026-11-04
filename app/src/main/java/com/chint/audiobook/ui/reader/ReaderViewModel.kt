package com.chint.audiobook.ui.reader

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chint.audiobook.data.model.AppSettings
import com.chint.audiobook.data.model.Book
import com.chint.audiobook.data.model.Bookmark
import com.chint.audiobook.data.model.Segment
import com.chint.audiobook.data.repository.BookCharacter
import com.chint.audiobook.data.repository.CharacterRepository
import com.chint.audiobook.data.repository.SettingsRepository
import com.chint.audiobook.playback.PlaybackService
import com.chint.audiobook.playback.PlaybackSession
import com.chint.audiobook.playback.PlayerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * WS1 (1b): UI adapter over the service-owned [PlaybackSession].
 *
 * Playback + synthesis now live in [PlaybackService] (a MediaLibraryService),
 * so they survive the UI: task-swipe keeps the foreground service playing, and
 * the framework renders the media notification / quick-settings card from the
 * session. This ViewModel:
 *  - starts + binds PlaybackService (started via startForegroundService so the
 *    binding outlives the activity),
 *  - mirrors the session's state flows into Compose-visible StateFlows (the
 *    session arrives a few milliseconds after construction; the mirrors hold
 *    defaults until then and every forward is a null-safe no-op),
 *  - forwards user commands from the reader UI.
 *
 * onCleared no longer releases the player — the service owns it.
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(application: Application) : AndroidViewModel(application) {

  private val settingsRepository = SettingsRepository(application)

  // Q4: dramatis personae sidecar.
  private val characterRepository = CharacterRepository(java.io.File(application.filesDir, "books"))
  private val _characters = MutableStateFlow<List<BookCharacter>>(emptyList())
  val characters: StateFlow<List<BookCharacter>> = _characters.asStateFlow()

  private var playbackSession: PlaybackSession? = null
  private var serviceBound = false
  private var mirrorJobs: List<Job> = emptyList()

  /**
   * 1b: commands forwarded before the service binder arrives would be dropped
   * null-safe no-ops — fatal for the reader entry, whose load fires the moment
   * the entry composes (a fast Continue-tap right after cold start). The load
   * replays on connect; transport taps after that are user-paced and safe.
   */
  private var pendingLoad: Pair<String, Int>? = null

  private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      val session = (service as? PlaybackService.LocalBinder)?.getSession() ?: return
      playbackSession = session
      startMirrors(session)
      pendingLoad?.let { (slug, chapter) ->
        pendingLoad = null
        session.loadBookAndChapter(slug, chapter)
      }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      stopMirrors()
      playbackSession = null
    }
  }

  init {
    // Started (not just bound) so the service outlives this VM's unbind on
    // task swipe. Deliberately a PLAIN startService, NOT startForegroundService:
    // the FGS-start contract demands startForeground() within ~5 s, but
    // MediaSessionService only promotes itself when playback actually begins
    // (paused-at-open violated the contract and ANR-killed the process —
    // on-device finding). Plain startService has no such deadline; media3
    // calls startForeground itself the moment playback needs the notification.
    getApplication<Application>().startService(
      Intent(application, PlaybackService::class.java)
    )
    application.bindService(
      Intent(application, PlaybackService::class.java),
      serviceConnection,
      Context.BIND_AUTO_CREATE
    )
    serviceBound = true
  }

  // ---- Session state mirrors (defaults until the service connects) ------------------------

  private val _activeBook = MutableStateFlow<Book?>(null)
  val activeBook: StateFlow<Book?> = _activeBook.asStateFlow()

  private val _currentChapterIndex = MutableStateFlow(1)
  val currentChapterIndex: StateFlow<Int> = _currentChapterIndex.asStateFlow()

  private val _chapterSegments = MutableStateFlow<List<Segment>>(emptyList())
  val chapterSegments: StateFlow<List<Segment>> = _chapterSegments.asStateFlow()

  private val _playerState = MutableStateFlow(PlayerState())
  val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

  private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
  val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

  private val _sleepTimerEndAt = MutableStateFlow(0L)
  val sleepTimerEndAt: StateFlow<Long> = _sleepTimerEndAt.asStateFlow()

  private val _preparingChapterIndex = MutableStateFlow<Int?>(null)
  val preparingChapterIndex: StateFlow<Int?> = _preparingChapterIndex.asStateFlow()

  private val _chaptersWithAudio = MutableStateFlow<Set<Int>>(emptySet())
  val chaptersWithAudio: StateFlow<Set<Int>> = _chaptersWithAudio.asStateFlow()

  // WS2: measured cached-chapter durations (Contents sheet).
  private val _chapterDurationsMs = MutableStateFlow<Map<Int, Long>>(emptyMap())
  val chapterDurationsMs: StateFlow<Map<Int, Long>> = _chapterDurationsMs.asStateFlow()

  private val _bookMissing = MutableStateFlow(false)
  val bookMissing: StateFlow<Boolean> = _bookMissing.asStateFlow()

  // WS3: active book's voice override (null = global default).
  private val _bookVoiceOverride = MutableStateFlow<String?>(null)
  val bookVoiceOverride: StateFlow<String?> = _bookVoiceOverride.asStateFlow()

  // Multi-voice: active book's dialogue-voice override (null = global/off).
  private val _dialogueVoiceOverride = MutableStateFlow<String?>(null)
  val dialogueVoiceOverride: StateFlow<String?> = _dialogueVoiceOverride.asStateFlow()

  // Q3: finished-book celebration (one-shot, cleared on next load).
  private val _endOfBookReached = MutableStateFlow<String?>(null)
  val endOfBookReached: StateFlow<String?> = _endOfBookReached.asStateFlow()

  fun dismissEndOfBook() {
    playbackSession?.dismissEndOfBook()
    _endOfBookReached.value = null
  }

  // WS3: in-book search state.
  private val _searchRunning = MutableStateFlow(false)
  val searchRunning: StateFlow<Boolean> = _searchRunning.asStateFlow()
  private val _searchHits = MutableStateFlow<List<PlaybackSession.SearchHit>>(emptyList())
  val searchHits: StateFlow<List<PlaybackSession.SearchHit>> = _searchHits.asStateFlow()

  val appSettings = settingsRepository.appSettings.stateIn(
    viewModelScope,
    SharingStarted.WhileSubscribed(5000),
    AppSettings()
  )

  private fun startMirrors(session: PlaybackSession) {
    stopMirrors()
    mirrorJobs = listOf(
      viewModelScope.launch { session.activeBook.collect { _activeBook.value = it } },
      viewModelScope.launch { session.currentChapterIndex.collect { _currentChapterIndex.value = it } },
      viewModelScope.launch { session.chapterSegments.collect { _chapterSegments.value = it } },
      viewModelScope.launch { session.playerState.collect { _playerState.value = it } },
      viewModelScope.launch { session.bookmarks.collect { _bookmarks.value = it } },
      viewModelScope.launch { session.sleepTimerEndAt.collect { _sleepTimerEndAt.value = it } },
      viewModelScope.launch { session.preparingChapterIndex.collect { _preparingChapterIndex.value = it } },
      viewModelScope.launch { session.chaptersWithAudio.collect { _chaptersWithAudio.value = it } },
      viewModelScope.launch { session.chapterDurationsMs.collect { _chapterDurationsMs.value = it } },
      viewModelScope.launch { session.bookMissing.collect { _bookMissing.value = it } },
      viewModelScope.launch { session.bookVoiceOverride.collect { _bookVoiceOverride.value = it } },
      viewModelScope.launch { session.dialogueVoiceOverride.collect { _dialogueVoiceOverride.value = it } },
      viewModelScope.launch { session.endOfBookReached.collect { _endOfBookReached.value = it } },
      viewModelScope.launch { session.searchRunning.collect { _searchRunning.value = it } },
      viewModelScope.launch { appSettings.collect { settings -> session.setSkipSilence(settings.skipSilence) } }
    )
  }

  private fun stopMirrors() {
    mirrorJobs.forEach { it.cancel() }
    mirrorJobs = emptyList()
  }

  // ---- Command forwards (public API identical to the pre-1b ViewModel) ---------------------

  fun loadBookAndChapter(bookSlug: String, chapterIndex: Int) {
    val session = playbackSession
    if (session != null) {
      session.loadBookAndChapter(bookSlug, chapterIndex)
    } else {
      pendingLoad = bookSlug to chapterIndex
    }
  }

  fun selectChapter(chapterIndex: Int) {
    playbackSession?.selectChapter(chapterIndex)
  }

  fun skipToNextChapter() {
    playbackSession?.skipToNextChapter()
  }

  fun skipToPreviousChapter() {
    playbackSession?.skipToPreviousChapter()
  }

  fun togglePlayPause() {
    playbackSession?.togglePlayPause()
  }

  fun seekToSentence(sentenceIndex: Int) {
    playbackSession?.seekToSentence(sentenceIndex)
  }

  fun setSpeed(speed: Float) {
    playbackSession?.setSpeed(speed)
  }

  // WS3: scoped picks from the voice/speed sheets.
  fun selectVoice(voice: String, applyToAllBooks: Boolean) {
    playbackSession?.selectVoice(voice, applyToAllBooks)
  }

  fun selectSpeed(speed: Float, applyToAllBooks: Boolean) {
    playbackSession?.selectSpeed(speed, applyToAllBooks)
  }

  /** Multi-voice: dialogue-voice pick (null = off). */
  fun selectDialogueVoice(voice: String?, applyToAllBooks: Boolean) {
    playbackSession?.selectDialogueVoice(voice, applyToAllBooks)
  }

  // ---- Q4: character list -----------------------------------------------------------------

  fun loadCharacters() {
    val slug = _activeBook.value?.slug ?: return
    viewModelScope.launch {
      _characters.value = characterRepository.load(slug)
    }
  }

  fun addCharacter(name: String, note: String) {
    val slug = _activeBook.value?.slug ?: return
    if (name.isBlank()) return
    viewModelScope.launch {
      val updated = _characters.value + BookCharacter(name.trim(), note.trim())
      _characters.value = updated
      characterRepository.save(slug, updated)
    }
  }

  fun deleteCharacter(character: BookCharacter) {
    val slug = _activeBook.value?.slug ?: return
    viewModelScope.launch {
      val updated = _characters.value - character
      _characters.value = updated
      characterRepository.save(slug, updated)
    }
  }

  fun changeBookLanguage(language: String) {
    playbackSession?.changeBookLanguage(language)
  }

  // ---- Q7: bookmark export/import ----------------------------------------------------------

  /** JSON for the active book's bookmarks (share sheet payload). */
  private val exportJson = kotlinx.serialization.json.Json { prettyPrint = true }
  private val importJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

  fun exportBookmarks(): String {
    val bookmarks = _bookmarks.value
    val slug = _activeBook.value?.slug ?: ""
    return exportJson.encodeToString(
      kotlinx.serialization.builtins.ListSerializer(
        com.chint.audiobook.data.model.Bookmark.serializer()
      ),
      bookmarks
    ) // payload self-describes the book in each entry's label; slug kept in filename
      .let { headerComment(slug) + it }
  }

  /** Q7 export header; the import side strips everything before the first line break. */
  private fun headerComment(slug: String): String = "// book: $slug" + "\n"

  /** Q7: merge imported bookmarks (exact-duplicate guard on the full tuple). */
  fun importBookmarks(json: String) {
    val slug = _activeBook.value?.slug ?: return
    val imported = runCatching {
      val cleaned = json.substringAfterLast("// book:").dropWhile { it == '\n' || it == '\r' }
      importJson.decodeFromString(
        kotlinx.serialization.builtins.ListSerializer(
          com.chint.audiobook.data.model.Bookmark.serializer()
        ),
        cleaned
      )
    }.getOrDefault(emptyList())
    if (imported.isEmpty()) return
    viewModelScope.launch {
      val merged = (_bookmarks.value + imported).distinctBy {
        Triple(it.chapterIndex, it.sentenceIndex, it.offsetMs to it.createdAt)
      }
      _bookmarks.value = merged
      com.chint.audiobook.data.repository.BookmarksRepository(
        java.io.File(getApplication<Application>().filesDir, "books")
      ).save(slug, merged)
    }
  }

  /** WS3: debounce-free query forward — the sheet already debounces. */
  fun searchBook(query: String) {
    playbackSession?.searchBook(query) { hits -> _searchHits.value = hits }
  }

  fun playSearchHit(hit: PlaybackSession.SearchHit) {
    playbackSession?.playSearchHit(hit)
  }

  fun seekToPositionMs(targetMs: Long) {
    playbackSession?.seekToPositionMs(targetMs)
  }

  fun seekByMs(deltaMs: Long) {
    playbackSession?.seekByMs(deltaMs)
  }

  fun addBookmark() {
    playbackSession?.addBookmark()
  }

  fun deleteBookmark(bookmark: Bookmark) {
    playbackSession?.deleteBookmark(bookmark)
  }

  fun seekToBookmark(bookmark: Bookmark) {
    playbackSession?.seekToBookmark(bookmark)
  }

  fun startSleepTimer(durationMs: Long) {
    playbackSession?.startSleepTimer(durationMs)
  }

  fun startSleepTimerToEndOfChapter() {
    playbackSession?.startSleepTimerToEndOfChapter()
  }

  fun cancelSleepTimer() {
    playbackSession?.cancelSleepTimer()
  }

  fun setSkipSilence(enabled: Boolean) {
    playbackSession?.setSkipSilence(enabled)
  }

  fun isSkipSilenceEnabled(): Boolean =
    playbackSession?.isSkipSilenceEnabled() ?: false

  /** BG0: park playback before the Library deletes the ACTIVE book's files. */
  fun stopPlaybackForBook(slug: String) {
    playbackSession?.parkForDeletion(slug)
  }

  override fun onCleared() {
    // 1b: the service owns the session/player — no shutdown here, only
    // detach. Media3 pauses+stops the service per its own lifecycle rules
    // when nothing is playing.
    stopMirrors()
    playbackSession = null
    if (serviceBound) {
      runCatching {
        getApplication<Application>().unbindService(serviceConnection)
      }
      serviceBound = false
    }
    super.onCleared()
  }
}
