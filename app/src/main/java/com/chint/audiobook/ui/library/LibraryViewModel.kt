package com.chint.audiobook.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chint.audiobook.data.model.Book
import com.chint.audiobook.data.model.PlaybackPosition
import com.chint.audiobook.data.model.PlaybackState
import com.chint.audiobook.data.repository.LibraryRepository
import com.chint.audiobook.data.repository.PlaybackPositionRepository
import com.chint.audiobook.data.repository.SettingsRepository
import com.chint.audiobook.engine.AudioCacheManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.chint.audiobook.domain.usecase.DeleteBookUseCase
import com.chint.audiobook.domain.usecase.GetLibraryBooksUseCase
import com.chint.audiobook.domain.usecase.ReloadLibraryUseCase
import com.chint.audiobook.domain.repository.BookDeleteResult

@HiltViewModel
class LibraryViewModel @Inject constructor(
  application: Application,
  private val libraryRepository: com.chint.audiobook.domain.repository.BookRepository,
  private val settingsRepository: com.chint.audiobook.domain.repository.UserSettingsRepository,
  private val getLibraryBooks: GetLibraryBooksUseCase,
  private val reloadLibrary: ReloadLibraryUseCase,
  private val deleteBookUseCase: DeleteBookUseCase
) : AndroidViewModel(application) {



  // UI Round 4 (R6): per-book position sidecars for progress + Continue card.
  private val positionRepository = PlaybackPositionRepository(File(application.filesDir, "books"))

  val books: StateFlow<List<Book>> = getLibraryBooks()
  val appSettings = settingsRepository.appSettings.stateIn(
    viewModelScope,
    SharingStarted.WhileSubscribed(5000),
    com.chint.audiobook.data.model.AppSettings()
  )
  val playbackState: StateFlow<PlaybackState> = settingsRepository.playbackState.stateIn(
    viewModelScope,
    SharingStarted.WhileSubscribed(5000),
    PlaybackState()
  )

  /** UI Round 4 (R6): slug → latest sidecar position, kept in sync with the shelf. */
  private val _positions = MutableStateFlow<Map<String, PlaybackPosition>>(emptyMap())
  val positions: StateFlow<Map<String, PlaybackPosition>> = _positions.asStateFlow()

  // Media round (A5): per-book rendered-audio storage for the manage sheet.
  private val audioCacheManager = AudioCacheManager()
  private val _bookStorageBytes = MutableStateFlow<Map<String, Long>>(emptyMap())
  val bookStorageBytes: StateFlow<Map<String, Long>> = _bookStorageBytes.asStateFlow()

  // WS2: per-book listened totals for the manage sheet.
  private val listeningStats = com.chint.audiobook.engine.ListeningStatsRepository(
    File(application.filesDir, "stats")
  )
  private val _bookListenedSeconds = MutableStateFlow<Map<String, Long>>(emptyMap())
  val bookListenedSeconds: StateFlow<Map<String, Long>> = _bookListenedSeconds.asStateFlow()

  init {
    viewModelScope.launch {
      reloadLibrary()
      refreshPositions()
      refreshStorageBytes()
    }
  }

  fun reload() {
    viewModelScope.launch {
      reloadLibrary()
      refreshPositions()
      refreshStorageBytes()
    }
  }

  /**
   * UI Round 4 (R6): re-read sidecars on demand — the Navigation layer calls
   * this when the Library entry composes (arriving back from the reader, where
   * every gap/pause/seek wrote a new position).
   */
  fun refreshPositions() {
    viewModelScope.launch(Dispatchers.IO) {
      val slugs = libraryRepository.booksFlow.first().map { it.slug }
      _positions.value = slugs.mapNotNull { slug ->
        positionRepository.load(slug)?.let { slug to it }
      }.toMap()
    }
  }

  /** BG0: surfaced to the manage sheet when a delete leaves files behind. */
  private val _lastDeletePartial = MutableStateFlow(false)
  val lastDeletePartial: StateFlow<Boolean> = _lastDeletePartial.asStateFlow()

  fun consumePartialDeleteWarning() {
    _lastDeletePartial.value = false
  }

  fun deleteBook(slug: String) {
    viewModelScope.launch {
      // The sidecars (bookmarks/position) live inside books/<slug>/ and die
      // with the directory; the in-memory map is filtered on the next scan.
      val result = deleteBookUseCase(slug)
      _lastDeletePartial.value = result == BookDeleteResult.PARTIAL
      // BG0: a persisted playback state naming the deleted book must not
      // survive it — it keeps resolving as the restore target of a ghost.
      if (playbackState.value.bookSlug == slug) {
        settingsRepository.clearPlaybackState()
      }
      refreshPositions()
      refreshStorageBytes()
    }
  }

  /**
   * Media round (A5): wipe a book's rendered WAVs (chapter audio + shared
   * cache) without touching text/segments/sidecars — re-listening
   * re-synthesizes. Storage display refreshes afterwards.
   */
  fun clearAudioCache(slug: String) {
    viewModelScope.launch {
      withContext(Dispatchers.IO) {
        audioCacheManager.clearAudioCache(bookDir(slug))
      }
      refreshStorageBytes()
    }
  }

  /**
   * Media round (A5): re-walk each book dir for rendered-audio bytes. Runs on
   * the shelf's cadence (init, reload, after delete/clear).
   */
  private suspend fun refreshStorageBytes() {
    withContext(Dispatchers.IO) {
      val slugs = libraryRepository.booksFlow.first().map { it.slug }
      _bookStorageBytes.value = slugs.associate { slug ->
        slug to audioCacheManager.bookAudioBytes(bookDir(slug))
      }
      // WS2: listened totals ride the same refresh.
      _bookListenedSeconds.value = slugs.associate { slug ->
        slug to listeningStats.totalForBook(slug)
      }
    }
  }

  private fun bookDir(slug: String): File =
    File(getApplication<Application>().filesDir, "books/$slug")

  companion object {
    fun resolveOpenChapter(savedState: PlaybackState, targetSlug: String): Int {
      return if (savedState.bookSlug == targetSlug && savedState.chapterIndex > 0) {
        savedState.chapterIndex
      } else {
        1
      }
    }
  }
}
