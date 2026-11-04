package com.chint.audiobook.ui.import

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import com.chint.audiobook.data.model.Book
import com.chint.audiobook.pipeline.ImportProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ImportViewModel : ViewModel() {

  private enum class ProgressPhase { PARSING, CLEANING, PREPARING, COMPLETED, FAILED, IDLE }

  private val _importProgress = MutableStateFlow<ImportProgress?>(null)
  val importProgress: StateFlow<ImportProgress?> = _importProgress.asStateFlow()

  private val _importedBook = MutableStateFlow<Book?>(null)
  val importedBook: StateFlow<Book?> = _importedBook.asStateFlow()

  private var lastProgressUpdateAt = 0L
  private var lastProgressPhase: ProgressPhase = ProgressPhase.IDLE
  private var pendingProgressPhase: ProgressPhase? = null
  private var pendingProgressPhaseSince = 0L

  fun updateProgress(progress: ImportProgress?) {
    val phase = when (progress) {
      null -> ProgressPhase.IDLE
      is ImportProgress.Parsing -> ProgressPhase.PARSING
      is ImportProgress.Cleaning -> ProgressPhase.CLEANING
      is ImportProgress.Preparing -> ProgressPhase.PREPARING
      is ImportProgress.Completed -> ProgressPhase.COMPLETED
      is ImportProgress.Failed -> ProgressPhase.FAILED
    }
    val now = SystemClock.uptimeMillis()
    val terminal = progress == null ||
      progress is ImportProgress.Parsing ||
      progress is ImportProgress.Completed ||
      progress is ImportProgress.Failed

    if (phase != lastProgressPhase && !terminal) {
      if (pendingProgressPhase != phase) {
        pendingProgressPhase = phase
        pendingProgressPhaseSince = now
      }
      if (now - pendingProgressPhaseSince < PHASE_SWITCH_DEBOUNCE_MS) {
        return
      }
    } else {
      pendingProgressPhase = null
    }

    if (!terminal && now - lastProgressUpdateAt < PROGRESS_UPDATE_INTERVAL_MS) {
      return
    }
    lastProgressUpdateAt = now
    lastProgressPhase = phase
    pendingProgressPhase = null
    _importProgress.value = progress
  }

  fun setImportedBook(book: Book) {
    _importedBook.value = book
  }

  fun reset() {
    lastProgressUpdateAt = 0L
    lastProgressPhase = ProgressPhase.IDLE
    pendingProgressPhase = null
    pendingProgressPhaseSince = 0L
    _importProgress.value = null
    _importedBook.value = null
  }

  private companion object {
    const val PROGRESS_UPDATE_INTERVAL_MS = 100L
    const val PHASE_SWITCH_DEBOUNCE_MS = 750L
  }
}
