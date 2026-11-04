package com.chint.audiobook.engine

import com.chint.audiobook.data.model.ChapterIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Media/cache round (A5/B1): owns the on-device rendered-audio cache under
 * books/<slug>/audio/chNNNN/<hash>.wav (per chapter) and
 * books/<slug>/cache/audio/<hash>.wav (voice-shared global cache).
 *
 * Stateless file helpers — callers resolve the book dir (filesDir/books/<slug>).
 * Plain java.io throughout so everything runs on the JVM in unit tests; the
 * eviction selection is a pure function (see [selectChaptersToEvict] + tests).
 * WAVs are the entire storage cost of listening — ~10.6 MB per minute of
 * 44.1 kHz mono 16-bit audio — so without a cap a long book eventually fills
 * the device.
 */
class AudioCacheManager {

  private val json = Json { ignoreUnknownKeys = true }

  /** Total bytes of rendered WAVs for one book (chapter dirs + global cache). */
  fun bookAudioBytes(bookDir: File): Long =
    listOf(AUDIO_DIR, KOKORO_AUDIO_DIR, GLOBAL_CACHE_DIR, KOKORO_GLOBAL_CACHE_DIR)
      .sumOf { directoryBytes(File(bookDir, it)) }

  /** Checks if a chapter has been compressed to AAC LC (.m4a) with an index sidecar. */
  fun isChapterCompressed(bookDir: File, chapterIndex: Int): Boolean {
    val chapterDir = File(bookDir, "$AUDIO_DIR/ch${"%04d".format(chapterIndex)}")
    return AudioCompressor.isChapterCompressed(chapterDir)
  }

  /** Loads the ChapterIndex sidecar if present. */
  fun loadChapterIndex(bookDir: File, chapterIndex: Int): ChapterIndex? {
    val chapterDir = File(bookDir, "$AUDIO_DIR/ch${"%04d".format(chapterIndex)}")
    return AudioCompressor.loadIndex(chapterDir)
  }

  /**
   * Compresses the raw WAVs in the chapter directory into AAC LC .m4a and saves the index.
   */
  suspend fun compressChapter(
    bookDir: File,
    chapterIndex: Int,
    hashes: List<String>,
    durationsByHash: Map<String, Long>,
    encoder: AudioEncoder = AndroidMediaCodecEncoder
  ): Boolean {
    val chapterDir = File(bookDir, "$AUDIO_DIR/ch${"%04d".format(chapterIndex)}")
    return AudioCompressor.compressChapter(chapterDir, chapterIndex, hashes, durationsByHash, encoder)
  }

  /** Prunes raw WAV files from a compressed chapter directory. */
  fun pruneRawWavs(bookDir: File, chapterIndex: Int): Int {
    val chapterDir = File(bookDir, "$AUDIO_DIR/ch${"%04d".format(chapterIndex)}")
    return AudioCompressor.pruneRawWavs(chapterDir)
  }

  /**
   * Deletes ALL rendered audio for a book (chapter audio + global cache).
   * Book text, segments, bookmarks, and position sidecars are untouched —
   * re-listening simply re-synthesizes. Returns true when nothing failed.
   */
  fun clearAudioCache(bookDir: File): Boolean {
    var allDeleted = true
    for (dirName in listOf(AUDIO_DIR, KOKORO_AUDIO_DIR, GLOBAL_CACHE_DIR, KOKORO_GLOBAL_CACHE_DIR)) {
      val dir = File(bookDir, dirName)
      if (dir.exists() && !dir.deleteRecursively()) allDeleted = false
    }
    return allDeleted
  }

  /**
   * B1: stamps "chapter [i] was last played at [atMs]" in the book's
   * audio-access.json sidecar. Unstamped chapters fall back to their audio
   * directory's mtime during eviction.
   */
  suspend fun stampAccess(bookDir: File, chapterIndex: Int, atMs: Long = System.currentTimeMillis()) =
    withContext(Dispatchers.IO) {
      val stamps = readAccessStamps(bookDir)
      stamps[chapterIndex] = atMs
      runCatching {
        val file = accessFile(bookDir)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(MapSerializer, stamps.mapKeys { it.key.toString() }))
      }
    }

  /** Reads the access sidecar; missing/corrupt file means "no stamps". */
  fun readAccessStamps(bookDir: File): MutableMap<Int, Long> =
    runCatching {
      val file = accessFile(bookDir)
      if (!file.exists()) return mutableMapOf()
      val raw = json.decodeFromString(MapSerializer, file.readText())
      raw.mapKeys { it.key.toInt() }.toMutableMap()
    }.getOrDefault(mutableMapOf())

  /**
   * B1: eviction orchestration — builds per-chapter entries from the audio
   * directory tree + access stamps, selects via [selectChaptersToEvict], and
   * deletes the selected chapter directories. The global cache (voice-shared
   * across chapters) is left alone; it is bounded by per-segment reuse.
   * Returns the evicted chapter indexes. Cap <= 0 means unlimited (no-op).
   */
  suspend fun enforceCap(bookDir: File, capBytes: Long, keepChapterIndexes: Set<Int>): List<Int> =
    withContext(Dispatchers.IO) {
      if (capBytes <= 0L) return@withContext emptyList()
      val chapterDirs = listOf(AUDIO_DIR, KOKORO_AUDIO_DIR).flatMap { rootName ->
        File(bookDir, rootName).listFiles { f -> f.isDirectory && f.name.startsWith("ch") }
          ?.toList().orEmpty()
      }.sortedBy { it.absolutePath }
      if (chapterDirs.isEmpty()) return@withContext emptyList()

      val stamps = readAccessStamps(bookDir)
      val entries = chapterDirs.mapNotNull { dir ->
        val index = dir.name.removePrefix("ch").toIntOrNull() ?: return@mapNotNull null
        ChapterCacheEntry(
          chapterIndex = index,
          sizeBytes = directoryBytes(dir),
          lastAccessMs = stamps[index] ?: dir.lastModified(),
          directory = dir
        )
      }

      val toEvict = selectEntriesToEvict(entries, capBytes, keepChapterIndexes)
      var evicted = emptyList<Int>()
      for (entry in toEvict) {
        val dir = entry.directory ?: continue
        if (dir.deleteRecursively()) evicted = evicted + entry.chapterIndex
      }
      evicted
    }

  private fun accessFile(bookDir: File) = File(bookDir, ACCESS_SIDECAR)

  private fun directoryBytes(dir: File): Long =
    dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

  data class ChapterCacheEntry(
    val chapterIndex: Int,
    val sizeBytes: Long,
    val lastAccessMs: Long,
    val directory: File? = null
  )

  companion object {
    private const val AUDIO_DIR = "audio"
    private const val KOKORO_AUDIO_DIR = "audio-kokoro"
    private const val GLOBAL_CACHE_DIR = "cache/audio"
    private const val KOKORO_GLOBAL_CACHE_DIR = "cache/audio-kokoro"
    private const val ACCESS_SIDECAR = "audio-access.json"
    private val MapSerializer = MapSerializer(String.serializer(), Long.serializer())

    /**
     * B1: pure eviction selection — evict oldest-accessed chapters first until
     * the total fits the cap. Chapters in [keepChapterIndexes] (current
     * chapter through current + prepare-ahead) are never selected. Chapters
     * with zero bytes are ignored. Ties on access time fall back to chapter
     * index (deterministic for tests and for mtime-collapsing filesystems).
     */
    fun selectChaptersToEvict(
      entries: List<ChapterCacheEntry>,
      capBytes: Long,
      keepChapterIndexes: Set<Int>
    ): List<Int> = selectEntriesToEvict(entries, capBytes, keepChapterIndexes).map { it.chapterIndex }

    private fun selectEntriesToEvict(
      entries: List<ChapterCacheEntry>,
      capBytes: Long,
      keepChapterIndexes: Set<Int>
    ): List<ChapterCacheEntry> {
      if (capBytes <= 0L) return emptyList()
      var total = entries.sumOf { it.sizeBytes }
      if (total <= capBytes) return emptyList()

      val candidates = entries
        .filter { it.sizeBytes > 0L && it.chapterIndex !in keepChapterIndexes }
        .sortedWith(compareBy({ it.lastAccessMs }, { it.chapterIndex }, { it.directory?.absolutePath.orEmpty() }))

      val evicted = mutableListOf<ChapterCacheEntry>()
      for (candidate in candidates) {
        if (total <= capBytes) break
        evicted.add(candidate)
        total -= candidate.sizeBytes
      }
      return evicted
    }
  }
}
