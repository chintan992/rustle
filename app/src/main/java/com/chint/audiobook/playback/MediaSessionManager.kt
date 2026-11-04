package com.chint.audiobook.playback

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession

class MediaSessionManager(
  private val context: Context,
  private val exoPlayer: ExoPlayer
) {

  private var mediaSession: MediaSession? = null

  fun initialize() {
    if (mediaSession == null) {
      mediaSession = MediaSession.Builder(context, exoPlayer)
        .setId("AudiobookMediaSession")
        .build()
    }
  }

  fun release() {
    mediaSession?.run {
      player.release()
      release()
    }
    mediaSession = null
  }
}
