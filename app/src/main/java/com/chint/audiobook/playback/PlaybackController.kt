package com.chint.audiobook.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.chint.audiobook.data.model.Book
import com.chint.audiobook.data.model.Segment
import com.chint.audiobook.engine.SegmentVoices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class PlayerState(
  val isPlaying: Boolean = false,
  val currentSentenceIndex: Int = 0,
  val currentPositionMs: Long = 0L,
  val durationMs: Long = 0L,
  val speed: Float = 1.0f,
  /**
   * Round 8: true while the playlist sits at its natural end (ExoPlayer STATE_ENDED),
   * independent of isPlaying. Lets the ViewModel restart cleanly from the chapter
   * start when the user presses Play at end-of-book.
   */
  val playbackEnded: Boolean = false,
  /**
   * Round 8: true while rendering/buffering (ExoPlayer STATE_BUFFERING) or while an
   * auto-advance render gap is in progress. Lets the UI annotate the state instead
   * of only flipping the play/pause label.
   */
  val buffering: Boolean = false,
  /**
   * ElevenReader plan (A3): chapter-level wall-clock position/duration for the
   * scrubber, from the WAV-duration timeline annex. Zero while the chapter has
   * no measured WAV yet — the UI renders a conservative empty scrubber rather
   * than inventing a time.
   */
  val chapterPositionMs: Long = 0L,
  val chapterDurationMs: Long = 0L
)

/**
 * Media round (A2): the human-readable identity of what is loaded, mirrored from
 * the MediaItem metadata for UI that does not touch media3 (notification, later
 * widgets). Blank bookTitle = nothing loaded.
 */
data class NowPlaying(
  val bookTitle: String = "",
  val chapterTitle: String = "",
  val author: String = "",
  val coverFile: File? = null
)

/**
 * Resume point for re-preparing the playlist after appending to an idle or ended player.
 */
data class AppendResumePoint(val startIndex: Int, val startPositionMs: Long)

/**
 * Round 8 (Task 1a): true when a STATE_ENDED transition is an organic playlist end —
 * the player reached the end of its playlist while still wanting to play
 * (playWhenReady == true). Programmatic clears and re-prepares never produce an
 * ENDED *transition* under playWhenReady; pausing before the end leaves
 * playWhenReady == false, so a manual seek to the last item's end does not signal.
 */
internal fun isOrganicPlaylistEnd(playbackState: Int, playWhenReady: Boolean): Boolean =
  playbackState == Player.STATE_ENDED && playWhenReady

/**
 * Round 8 (Task 1b): pure resume-point math for re-preparing after appending to a
 * player that was IDLE or ENDED.
 *
 * - STATE_IDLE (first appends; round-4 verified): the playlist was never playing,
 *   so keep resuming where the player already sits (its current index/position).
 * - STATE_ENDED (round-8 fix): the previous playlist played through to the end of
 *   its LAST item. Resuming at (lastIndex, lastEnd) would instantly re-end the
 *   player (the "audio exists but nothing moves" experience). The item this call
 *   just appended is the FIRST item after that finished playlist, so resume at its
 *   index from its beginning (positionMs = C.TIME_UNSET).
 */
internal fun computeAppendResumePoint(
  itemCountAfterAppend: Int,
  previousPlaybackState: Int,
  currentMediaItemIndex: Int,
  currentPositionMs: Long
): AppendResumePoint {
  val lastIndex = (itemCountAfterAppend - 1).coerceAtLeast(0)
  return if (previousPlaybackState == Player.STATE_ENDED) {
    AppendResumePoint(
      startIndex = lastIndex,
      startPositionMs = C.TIME_UNSET
    )
  } else {
    AppendResumePoint(
      startIndex = currentMediaItemIndex.coerceIn(0, lastIndex),
      startPositionMs = currentPositionMs
    )
  }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackController(private val context: Context) {

  companion object {
    private const val TAG = "PlaybackController"
  }

  // WS1 (1b): public player access for the MediaLibrarySession hosted by
  // PlaybackService (the session must wrap the player the service owns).
  val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
    // Music-player round: the system card's seek chips and the
    // COMMAND_SEEK_TO_BACKWARD/FORWARD buttons seek ±10 s — matching the
    // reader's ±10 s transport.
    .setSeekBackIncrementMs(10_000L)
    .setSeekForwardIncrementMs(10_000L)
    .build()

  private val _playerState = MutableStateFlow(PlayerState())
  val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

  /**
   * Media round (A2): metadata for the currently loaded book/chapter. Applied to
   * every MediaItem built after [updateChapterMetadata] — ExoPlayer surfaces the
   * playing item's metadata through the MediaSession, so the system media card
   * and MediaStyle notification show real titles instead of a file name.
   */
  private var currentMetadata: MediaMetadata = MediaMetadata.EMPTY

  /**
   * Media round (A2): mirror of [currentMetadata] for non-media3 consumers
   * (MediaNotificationManager content lines).
   */
  private val _nowPlaying = MutableStateFlow(NowPlaying())
  val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

  private var currentSegments: List<Segment> = emptyList()

  // WS1 (1b): the MediaSession moved to PlaybackService — one session per
  // process, hosted by the MediaLibraryService so the framework renders the
  // media notification and routes media buttons. A second controller-owned
  // session here would register a duplicate media card.

  /**
   * Round 10 (Task 1): canonical playlist mapping — one [PlaylistAnchor] per
   * distinct segment hash in first-occurrence order, and the count of anchors
   * actually in the player (playlist prefix position == anchor position for every
   * canonical append). Non-canonical appends (S0 preview) sit beyond this window.
   */
  private var canonicalAnchors: List<PlaylistAnchor> = emptyList()
  private var canonicalAppended: Int = 0

  /**
   * ElevenReader plan (A3): chapter audio timeline annex. WAV durations are
   * measured once each as WAVs are seen (seed or canonical append); the timeline
   * is rebuilt from the canonical anchors + durations map. This never replaces
   * the sentence-seek machinery — it adds wall-clock seeking (scrubber, ±skip).
   */
  private val durationsByHash = HashMap<String, Long>()
  private var timeline: List<TimelineEntry> = emptyList()

  /**
   * Round 8 (Task 1a): one-shot organic chapter-end callback, registered once by the
   * ViewModel. Invoked synchronously on the player's application thread (main) at the
   * exact moment of the organic end, so consumer guards observe pre-end state with no
   * buffered-signal race. Deliberately NOT part of [PlayerState] — the UI keeps its
   * normal pause/play semantics.
   */
  private var chapterEndListener: (() -> Unit)? = null

  /**
   * Round 8 (Task 1d): presentation smoothing across the auto-advance render gap.
   * While set, the player still intends to play (playWhenReady == true) even though
   * the playlist is being swapped/empty; the presented state stays "playing"
   * (playing-into-buffering) instead of flashing a dead Play button. Cleared when
   * real playback starts, when playWhenReady drops, or explicitly by the ViewModel.
   */
  private var pendingAutoResume = false

  /**
   * Round 8: set around programmatic playlist swaps ([loadChapterSegments]) so no
   * organic-end signal can leak out of a re-prepare.
   */
  private var suppressEndSignal = false

  init {
    // Media round (A1): speech-typed media attributes with framework audio-focus
    // handling — playback pauses on permanent loss (phone call), ducks on
    // transient loss (notification), and pauses on headphone unplug
    // (AUDIO_BECOMING_NOISY). Without this the app talks over calls.
    exoPlayer.setAudioAttributes(
      AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
        .build(),
      /* handleAudioFocus = */ true
    )
    exoPlayer.setHandleAudioBecomingNoisy(true)

    exoPlayer.addListener(object : Player.Listener {
      override fun onIsPlayingChanged(isPlaying: Boolean) {
        updateState()
      }

      override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        updateState()
      }

      override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        updateState()
        // Round 9 (Task 4): audible-start marker for start-audio latency deltas.
        Log.i(TAG, "playWhenReady=$playWhenReady reason=$reason positionMs=${exoPlayer.currentPosition} isPlaying=${exoPlayer.isPlaying}")
      }

      override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) {
          // Real playback position reached; the advance-gap smoothing is no longer needed.
          pendingAutoResume = false
          // Round 9 (Task 4): READY marker for start-audio latency deltas.
          Log.i(TAG, "ExoPlayer STATE_READY items=${exoPlayer.mediaItemCount} positionMs=${exoPlayer.currentPosition}")
        }
        val organicEnd = isOrganicPlaylistEnd(playbackState, exoPlayer.playWhenReady) && !suppressEndSignal
        updateState()
        if (organicEnd) {
          chapterEndListener?.invoke()
        }
      }
    })
  }

  /** Registers (or clears) the one-shot organic chapter-end listener. */
  fun setOnChapterEnded(listener: (() -> Unit)?) {
    chapterEndListener = listener
  }

  /**
   * Round 8: called by the ViewModel when an auto-advance has swapped to the next
   * chapter and its first audio is still being rendered/appended. Keeps the
   * presented state "playing into buffering" until real playback starts.
   */
  fun markPendingAutoResume() {
    pendingAutoResume = true
    updateState()
  }

  /**
   * Round 8: cancels the advance-gap smoothing (manual loads, end of book).
   */
  fun clearPendingAutoResume() {
    pendingAutoResume = false
    updateState()
  }

  /**
   * Media round (A2): sets the metadata stamped onto every subsequent MediaItem
   * (chapter title / author / book title / cover). Call before
   * [loadChapterSegments] on every chapter load. Stamps a MediaItem at build
   * time only — existing playlist items keep the metadata they were built with.
   */
  fun updateChapterMetadata(book: Book, chapterIndex: Int, chapterTitle: String) {
    val builder = MediaMetadata.Builder()
      .setTitle(chapterTitle)
      .setArtist(book.author.ifBlank { book.title })
      .setAlbumTitle(book.title)
      .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
    // coverPath is stored relative to the book dir (ImportPipeline writes
    // "cover.jpg"); resolve against filesDir the same way LibraryScreen does.
    var coverFile: File? = null
    book.coverPath?.let { relative ->
      val resolved = File(context.filesDir, "books/${book.slug}/$relative")
      if (resolved.exists()) {
        coverFile = resolved
        builder.setArtworkUri(Uri.fromFile(resolved))
      }
    }
    currentMetadata = builder.build()
    _nowPlaying.value = NowPlaying(
      bookTitle = book.title,
      chapterTitle = chapterTitle,
      author = book.author,
      coverFile = coverFile
    )
  }

  private fun mediaItemFor(wavFile: File): MediaItem =
    MediaItem.Builder()
      .setUri(Uri.fromFile(wavFile))
      .setMediaMetadata(currentMetadata)
      .build()

  private fun mediaItemForCompressed(m4aFile: File, startMs: Long, durationMs: Long): MediaItem =
    MediaItem.Builder()
      .setUri(Uri.fromFile(m4aFile))
      .setMediaMetadata(currentMetadata)
      .setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder()
          .setStartPositionMs(startMs)
          .setEndPositionMs(startMs + durationMs)
          .build()
      )
      .build()

  fun loadChapterSegments(
    bookDir: File,
    chapterIndex: Int,
    segments: List<Segment>,
    dialogueVoice: String? = null
  ): List<Segment> {
    // Round 10 (Task 1): the playlist is the distinct-hash projection of segments
    // (one item per unique WAV), exactly what the chapterFullyAppended distinct-hash
    // guard assumes. currentSegments feeds seekToSentence's hash lookup.
    currentSegments = segments
    val anchors = PlaylistOrderPlanner.distinctAnchors(segments)
    canonicalAnchors = anchors
    canonicalAppended = 0
    // A3: fresh chapter — fresh duration memory and an empty timeline until the
    // seeded WAVs are measured below.
    durationsByHash.clear()
    timeline = emptyList()

    // Round 8: programmatic playlist swap — never surface an organic end from here.
    suppressEndSignal = true
    try {
      exoPlayer.clearMediaItems()

      val chapterAudioDir = File(bookDir, "audio/ch${"%04d".format(chapterIndex)}")
      val globalCacheAudioDir = File(bookDir, "cache/audio")

      // Tier 2: Check if this chapter is already compressed into chapter.m4a + chapter-index.json
      if (com.chint.audiobook.engine.AudioCompressor.isChapterCompressed(chapterAudioDir)) {
        val chapterIndexObj = com.chint.audiobook.engine.AudioCompressor.loadIndex(chapterAudioDir)
        val m4aFile = com.chint.audiobook.engine.AudioCompressor.compressedFile(chapterAudioDir)
        if (chapterIndexObj != null) {
          val segmentByHash = chapterIndexObj.segments.associateBy { it.hash }
          if (anchors.all { it.hash in segmentByHash }) {
            val mediaItems = ArrayList<MediaItem>(anchors.size)
            for (anchor in anchors) {
              val entry = segmentByHash[anchor.hash]!!
              durationsByHash[anchor.hash] = entry.durationMs
              mediaItems.add(mediaItemForCompressed(m4aFile, entry.startMs, entry.durationMs))
            }
            if (mediaItems.isNotEmpty()) {
              exoPlayer.setMediaItems(mediaItems)
              exoPlayer.prepare()
            }
            canonicalAppended = anchors.size
            timeline = AudioTimeline.build(canonicalAnchors, durationsByHash)
            return segments
          }
        }
      }

      // Multi-voice: resolution classifies the segment so dialogue segments
      // resolve their voice-embedded filename (<hash>~<voice>.wav).
      val textByHash = segments.associate { it.hash to it.text }
      fun resolveWavFile(hash: String): File? =
        SegmentVoices.findWav(
          chapterAudioDir, globalCacheAudioDir, hash,
          textByHash[hash] ?: "", dialogueVoice
        )

      // Round 10 (Task 1): seed only the CONTIGUOUS cached anchor prefix. A far-tail
      // cached WAV behind a missing gap anchor is left for the ordered appends so the
      // playlist can never play it before its predecessors.
      val seedCount = PlaylistOrderPlanner.seedCount(anchors) { resolveWavFile(it) != null }

      val seededAnchors = ArrayList<PlaylistAnchor>(seedCount)
      val mediaItems = ArrayList<MediaItem>(seedCount)
      for (i in 0 until seedCount) {
        // The probe inside seedCount already passed; re-resolve so a vanish race
        // truncates the seed instead of failing the load.
        val wavFile = resolveWavFile(anchors[i].hash) ?: break
        seededAnchors.add(anchors[i])
        mediaItems.add(mediaItemFor(wavFile))
        // A3: measure seeded WAVs so the scrubber/±skip know the audio that exists.
        durationsByHash.getOrPut(anchors[i].hash) { WavDuration.durationMs(wavFile) ?: 0L }
      }

      if (mediaItems.isNotEmpty()) {
        exoPlayer.setMediaItems(mediaItems)
        exoPlayer.prepare()
      }
      canonicalAppended = seededAnchors.size
      timeline = AudioTimeline.build(canonicalAnchors, durationsByHash)
      return seededAnchors.map { segments[it.segmentIndex] }
    } finally {
      suppressEndSignal = false
    }
  }

  /**
   * Round 10 (Task 1): appends one WAV and, when [hash] names the next canonical
   * anchor (the reader's ordered frontier path), advances the anchor mapping. A null
   * hash (S0 voice preview) appends without canonical bookkeeping. Returns false when
   * the file was not appendable so the caller keeps it in the frontier instead of
   * marking it done.
   */
  fun appendSegmentWav(wavFile: File, hash: String? = null): Boolean {
    if (wavFile.exists() && wavFile.length() > 0L) {
      val previousState = exoPlayer.playbackState
      val wasIdle = (previousState == Player.STATE_IDLE || previousState == Player.STATE_ENDED)
      exoPlayer.addMediaItem(mediaItemFor(wavFile))
      val expected = canonicalAnchors.getOrNull(canonicalAppended)
      if (hash != null && expected != null && expected.hash == hash) {
        canonicalAppended++
        // A3: measure the freshly appended WAV and extend the timeline.
        durationsByHash.getOrPut(hash) { WavDuration.durationMs(wavFile) ?: 0L }
        timeline = AudioTimeline.build(canonicalAnchors, durationsByHash)
      }
      if (wasIdle) {
        // Round 8 (Task 1b): re-prepare so the newly appended item becomes the
        // playlist start; the resume point differs for ENDED (first item after the
        // finished playlist, from its beginning) vs IDLE (round-4 behavior).
        val resume = computeAppendResumePoint(
          itemCountAfterAppend = exoPlayer.mediaItemCount,
          previousPlaybackState = previousState,
          currentMediaItemIndex = exoPlayer.currentMediaItemIndex,
          currentPositionMs = exoPlayer.currentPosition
        )
        exoPlayer.setMediaItems(mediaItemsSnapshot(), resume.startIndex, resume.startPositionMs)
        exoPlayer.prepare()
      }
      return true
    }
    return false
  }

  private fun mediaItemsSnapshot(): List<MediaItem> {
    val items = mutableListOf<MediaItem>()
    for (i in 0 until exoPlayer.mediaItemCount) {
      items.add(exoPlayer.getMediaItemAt(i))
    }
    return items
  }

  fun play() {
    exoPlayer.play()
  }

  fun pause() {
    exoPlayer.pause()
  }

  /**
   * BG0 (ghost-book fix): drop the entire playlist and reset to IDLE. ExoPlayer
   * holds file handles on file:// media items — a paused player keeps the
   * deleted book's WAVs pinned, so the Library's recursive delete fails
   * partway and book.json survives as a ghost. clearMediaItems + stop releases
   * the handles BEFORE deletion runs. Also forgets the canonical-anchor
   * mapping, so any stray append/seek after this is a no-op rather than a
   * replay of orphaned audio. Suppressed-end signal keeps the swap from
   * surfacing a fake organic chapter end.
   */
  fun clearPlaylist() {
    suppressEndSignal = true
    try {
      exoPlayer.stop()
      exoPlayer.clearMediaItems()
    } finally {
      suppressEndSignal = false
    }
    canonicalAnchors = emptyList()
    canonicalAppended = 0
    currentSegments = emptyList()
    durationsByHash.clear()
    timeline = emptyList()
    pendingAutoResume = false
    updateState()
  }

  /**
   * Round 10 (Task 1): [sentenceIndex] is a SEGMENT index (UI transcript position)
   * regardless of playlist gaps. Resolved through the canonical anchor order:
   * clicking a duplicated sentence seeks its actual WAV (first occurrence), an
   * appended sentence its own item, and a not-yet-rendered sentence falls to the
   * nearest appended anchor at-or-before it (old clamp-to-frontier end kept).
   */
  fun seekToSentence(sentenceIndex: Int, offsetMs: Long = 0L) {
    val position = PlaylistOrderPlanner.playlistPositionFor(
      anchors = canonicalAnchors,
      appendedCount = canonicalAppended,
      segmentIndex = sentenceIndex,
      segmentHash = currentSegments.getOrNull(sentenceIndex)?.hash
    )
    val targetIndex = position.coerceIn(0, (exoPlayer.mediaItemCount - 1).coerceAtLeast(0))
    if (exoPlayer.mediaItemCount > targetIndex) {
      exoPlayer.seekTo(targetIndex, offsetMs)
    }
  }

  /**
   * ElevenReader plan (A3): wall-clock seek. The scrubber target resolves
   * through the WAV-duration timeline to playlist item + offset (never an
   * invented time). No-op while the chapter has no measured WAV yet.
   */
  fun seekToPositionMs(targetMs: Long) {
    val point = AudioTimeline.resolveSeek(timeline, targetMs) ?: return
    if (exoPlayer.mediaItemCount > point.itemIndex) {
      exoPlayer.seekTo(point.itemIndex, point.offsetMs)
    }
  }

  /** A3 skip helper: resolve current from the timeline, then seek to current ± [deltaMs]. */
  fun seekByMs(deltaMs: Long) {
    val current = AudioTimeline.positionOf(
      timeline,
      exoPlayer.currentMediaItemIndex,
      exoPlayer.currentPosition.coerceAtLeast(0L)
    )
    if (current <= 0L && deltaMs < 0L) return // nothing measured behind position 0
    seekToPositionMs(current + deltaMs)
  }

  fun setSpeed(speed: Float) {
    val clampedSpeed = speed.coerceIn(0.25f, 4.0f)
    exoPlayer.playbackParameters = PlaybackParameters(clampedSpeed)
    updateState()
  }

  fun setVolume(volume: Float) {
    exoPlayer.volume = volume.coerceIn(0.0f, 1.0f)
  }

  fun getVolume(): Float = exoPlayer.volume

  fun setSkipSilence(enabled: Boolean) {
    exoPlayer.skipSilenceEnabled = enabled
  }

  fun isSkipSilenceEnabled(): Boolean = exoPlayer.skipSilenceEnabled

  fun release() {
    // WS1 (1b): the MediaSession (and its release) belongs to PlaybackService.
    exoPlayer.release()
  }

  /**
   * Round 10 (Task 1): the reported sentence is a SEGMENT index — the anchor segment
   * behind the currently playing playlist item — so highlight and transcript stay
   * aligned in every mixed cache state. Items beyond the canonical window
   * (non-canonical S0 preview appends) report their raw position, which is what the
   * pre-round-10 code reported.
   */
  private fun reportSentenceIndex(): Int {
    val itemIndex = exoPlayer.currentMediaItemIndex
    return if (itemIndex in 0 until canonicalAppended) {
      canonicalAnchors[itemIndex].segmentIndex
    } else {
      itemIndex
    }
  }

  private fun updateState() {
    val rawIsPlaying = exoPlayer.isPlaying
    if (rawIsPlaying) {
      pendingAutoResume = false
    }
    // Round 8 (Task 1d): keep the presented "playing" state across the auto-advance
    // render gap (player may sit in ENDED/IDLE/BUFFERING while the next chapter's
    // first WAV is rendered). pause/play semantics are untouched: the smoothing is
    // gated on playWhenReady, so a user pause presents a real "not playing".
    val smoothing = pendingAutoResume && exoPlayer.playWhenReady
    val playbackState = exoPlayer.playbackState
    // A3: chapter-level wall-clock values for the scrubber (0 while unmeasured).
    val chapterPositionMs = AudioTimeline.positionOf(
      timeline,
      exoPlayer.currentMediaItemIndex,
      exoPlayer.currentPosition.coerceAtLeast(0L)
    )
    val chapterDurationMs = AudioTimeline.totalKnownDurationMs(timeline)
    _playerState.value = PlayerState(
      isPlaying = rawIsPlaying || smoothing,
      currentSentenceIndex = reportSentenceIndex(),
      currentPositionMs = exoPlayer.currentPosition,
      durationMs = exoPlayer.duration.coerceAtLeast(0L),
      speed = exoPlayer.playbackParameters.speed,
      playbackEnded = playbackState == Player.STATE_ENDED,
      buffering = playbackState == Player.STATE_BUFFERING || (smoothing && !rawIsPlaying),
      chapterPositionMs = chapterPositionMs,
      chapterDurationMs = chapterDurationMs
    )
  }
}
