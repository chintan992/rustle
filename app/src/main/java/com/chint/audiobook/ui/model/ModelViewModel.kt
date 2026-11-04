package com.chint.audiobook.ui.model

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chint.audiobook.BuildConfig
import com.chint.audiobook.engine.DownloadState
import com.chint.audiobook.engine.ModelManager
import com.chint.audiobook.engine.RtfBenchmark
import com.chint.audiobook.engine.RtfBenchmarkResult
import com.chint.audiobook.engine.RtfStats
import com.chint.audiobook.engine.TtsEngineManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import com.chint.audiobook.data.repository.LedgerRepository

data class PackIntegrity(
  val packReady: Boolean,
  val presentFiles: Int,
  val presentBytes: Long,
  val missingFiles: List<String>,
  val hashMismatches: List<String>
)

@HiltViewModel
class ModelViewModel @Inject constructor(
  @ApplicationContext private val context: Context,
  private val modelManager: ModelManager,
  private val settingsRepository: com.chint.audiobook.domain.repository.UserSettingsRepository,
  private val engineManager: TtsEngineManager,
  private val rtfBenchmark: RtfBenchmark,
  private val ledgerRepository: LedgerRepository
) : ViewModel() {


  private val _packIntegrity = MutableStateFlow(
    PackIntegrity(
      packReady = false,
      presentFiles = 0,
      presentBytes = 0L,
      missingFiles = emptyList(),
      hashMismatches = emptyList()
    )
  )

  private val _downloadState = MutableStateFlow<DownloadState>(
    if (modelManager.isModelReady()) DownloadState.Verified else DownloadState.Idle
  )
  val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

  val packIntegrity: StateFlow<PackIntegrity> = _packIntegrity.asStateFlow()

  // Round 10 (Task 3): debug-only pack fault-injection card. debugBuild is
  // captured at construction; the card is rendered only in debug builds.
  val debugBuild: Boolean = BuildConfig.DEBUG
  private val _debugNote = MutableStateFlow<String?>(null)
  val debugNote: StateFlow<String?> = _debugNote.asStateFlow()

  private val _benchmarkResult = MutableStateFlow<RtfBenchmarkResult?>(null)
  val benchmarkResult: StateFlow<RtfBenchmarkResult?> = _benchmarkResult.asStateFlow()

  /** Round 9 (Task 4): per-chunk RTF stats over every book's ledger render_done events. */
  private val _rtfStats = MutableStateFlow(RtfStats.EMPTY)
  val rtfStats: StateFlow<RtfStats> = _rtfStats.asStateFlow()

  @Volatile
  private var benchmarkRunning = false

  init {
    // Round 10 (Task 3): route debug-hook events into the UI note line.
    modelManager.debugEventListener = { note -> _debugNote.value = note }
    viewModelScope.launch(Dispatchers.IO) {
      // A8: if the pack repeatedly fails sha256 verification while present on
      // disk, surface it instead of pretending the pack is fine. Hashing the
      // model pack is disk-heavy and must not run on the main thread.
      val (_, mismatches) = modelManager.verifyChecksums()
      if (mismatches.isNotEmpty() && modelManager.isModelReady()) {
        _downloadState.value = DownloadState.Error("sha256 mismatch in pack files: $mismatches")
      }
      _packIntegrity.value = assessPack()
      refreshModelState()
    }
  }

  fun startDownload() {
    viewModelScope.launch {
      val wifiOnly = try {
        settingsRepository.appSettings.first().wifiOnlyDownloads
      } catch (_: Exception) {
        true
      }
      modelManager.downloadModel(wifiOnly = wifiOnly) { state ->
        _downloadState.value = state
      }.onSuccess {
        _packIntegrity.value = assessPack()
      }.onFailure { error ->
        _downloadState.value = DownloadState.Error(error.message ?: "Download failed")
        // Round 10 (Task 3): a debug fault-injection run ends in Error by
        // design (journey B); restore the quarantined final-dir file so the
        // user's pack emerges byte-identical. No-op when no backup exists.
        if (debugBuild) {
          modelManager.debugRestoreQuarantinedFile("voice.bin")
          _packIntegrity.value = assessPack()
        }
      }
    }
  }

  fun clearModelData() {
    if (downloadBusy()) return
    viewModelScope.launch(Dispatchers.IO) {
      modelManager.clearModelData()
        .onSuccess {
          _downloadState.value = DownloadState.Idle
          _packIntegrity.value = assessPack()
        }
        .onFailure { error ->
          _downloadState.value = DownloadState.Error(
            error.message ?: "Unable to clear model data"
          )
          _packIntegrity.value = assessPack()
        }
    }
  }

  /**
   * Round 10 (Task 3), debug builds only — Journey A: flip one byte of the
   * final-dir voice.bin at flow entry and let the NORMAL download flow
   * (resume-inventory -> staged refetch -> move -> post-move verify) repair it.
   */
  fun debugCorruptFinalPackFileAndRecover() {
    if (!debugBuild || downloadBusy()) return
    _debugNote.value = "Arming: corrupt 1 byte of voice.bin (final dir), then run the normal flow..."
    modelManager.debugArmFinalDirCorruption("voice.bin")
    startDownload()
  }

  /**
   * Round 10 (Task 3), debug builds only — Journey B: quarantine the final
   * voice.bin, then corrupt the staged copy before BOTH sha256 passes so the
   * first is recovered by refetch and the second exhausts the retry. The VM
   * restores the quarantine after the failure.
   */
  fun debugCorruptStagedPackFileTwiceAndExhaust() {
    if (!debugBuild || downloadBusy()) return
    _debugNote.value = "Arming: quarantine voice.bin, corrupt staged copy at each sha256 pass (×2)..."
    modelManager.debugQuarantineFinalFile("voice.bin")
    modelManager.debugArmStagedVerifyCorruption("voice.bin", times = 2)
    startDownload()
  }

  private fun downloadBusy(): Boolean =
    downloadState.value is DownloadState.Downloading || downloadState.value is DownloadState.Verifying

  fun runBenchmark() {
    // B4/ANR fix: coldReload + the first full generate() take seconds on this
    // hardware; running them on the main dispatcher freezes the UI (observed
    // "Audiobook isn't responding"). The benchmark work itself is synchronous,
    // so it must be shifted off the main thread. The in-flight flag keeps the
    // engine's "one generation at a time" invariant if the button is tapped twice.
    if (benchmarkRunning) return
    benchmarkRunning = true
    viewModelScope.launch {
      try {
        val modelDir = modelManager.getModelDir()
        val result = withContext(Dispatchers.IO) {
          rtfBenchmark.runBenchmark(modelDir)
        }
        _benchmarkResult.value = result
      } finally {
        benchmarkRunning = false
        refreshModelState()
      }
    }
  }

  /**
   * Round 9 (Task 4): re-aggregate per-chunk RTF stats across every book's
   * ledger (render_done events). Called on entry to the Model screen and after
   * each benchmark so the §10 ≥200-chunk bar is auditable on-device while a
   * background render is adding chunks.
   *
   * Also re-assesses the pack: without this, files deleted/restored outside the
   * app (adb, storage cleaner) left a stale "Ready & Verified!" headline until
   * the next download completed.
   */
  fun refreshModelState() {
    viewModelScope.launch(Dispatchers.IO) {
      val events = loadAllLedgerEvents()
      val stats = RtfStats.fromEvents(events)
      val integrity = assessPack()
      _rtfStats.value = stats
      _packIntegrity.value = integrity
      // Keep the headline honest in both directions, but never clobber an
      // in-flight download/verify state.
      val state = _downloadState.value
      if (integrity.packReady && state is DownloadState.Idle) {
        _downloadState.value = DownloadState.Verified
      } else if (!integrity.packReady && state is DownloadState.Verified) {
        _downloadState.value = DownloadState.Idle
      }
    }
  }

  private fun loadAllLedgerEvents(): List<com.chint.audiobook.data.model.LedgerEvent> {
    val booksDir = File(context.filesDir, "books")
    val bookDirs = booksDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
    return runCatching {
      bookDirs.flatMap { bookDir ->
        runCatching { ledgerRepository.readEvents(bookDir) }.getOrDefault(emptyList())
      }
    }.getOrDefault(emptyList())
  }

  private fun assessPack(): PackIntegrity {
    val modelDir = modelManager.getModelDir()
    val present = ModelManager.REQUIRED_FILES
      .map { File(modelDir, it) }
      .filter { it.exists() && it.length() > 0L }
    val (missing, mismatches) = modelManager.verifyChecksums()
    return PackIntegrity(
      packReady = modelManager.isModelReady(),
      presentFiles = present.size,
      presentBytes = present.sumOf { it.length() },
      missingFiles = missing,
      hashMismatches = mismatches
    )
  }
}
