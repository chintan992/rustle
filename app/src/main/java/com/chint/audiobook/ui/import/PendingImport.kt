package com.chint.audiobook.ui.import

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SharedText(val title: String?, val text: String)

/**
 * Universal import dispatcher: EPUB/TXT/MD files and shared text handed to the app
 * from outside (share sheet "Share to Audiobook", file-manager "Open with", or browser share).
 * MainActivity parks the Uri or text here; MainNavigation observes it and routes to
 * the Import entry.
 */
object PendingImport {
  private val _uri = MutableStateFlow<Uri?>(null)
  val uri: StateFlow<Uri?> = _uri.asStateFlow()

  private val _sharedText = MutableStateFlow<SharedText?>(null)
  val sharedText: StateFlow<SharedText?> = _sharedText.asStateFlow()

  fun offer(uri: Uri?) {
    if (uri != null) _uri.value = uri
  }

  fun offerText(text: String, title: String? = null) {
    if (text.isNotBlank()) {
      _sharedText.value = SharedText(title, text)
    }
  }

  fun consume() {
    _uri.value = null
  }

  fun consumeText() {
    _sharedText.value = null
  }
}
