package com.chint.audiobook.core

import android.content.Context
import android.os.Bundle
import android.util.Log

/**
 * Beta round WS2/WS4 telemetry: lazy FirebaseAnalytics wrapper.
 *
 * No-PII by contract: params are language codes, pack ids, booleans and
 * bucketed values only — no titles, slugs, file names or content. The client
 * is captured lazily on the first [init] from an Android component, so JVM
 * unit tests never touch the Firebase SDK, and every [log] call is guarded —
 * telemetry must never break playback.
 */
object Telemetry {

  private const val TAG = "Telemetry"

  @Volatile
  private var analytics: com.google.firebase.analytics.FirebaseAnalytics? = null

  /** Call once from MainActivity.onCreate (where Firebase already initializes). */
  fun init(context: Context) {
    if (analytics != null) return
    runCatching {
      com.google.firebase.analytics.FirebaseAnalytics.getInstance(context.applicationContext)
    }.onSuccess { analytics = it }
      .onFailure { Log.w(TAG, "analytics init failed: ${it.message}") }
  }

  /** WS2 events. */
  const val EVENT_BOOK_IMPORT = "book_import"
  const val EVENT_LANGUAGE_SET = "language_set"

  /** WS4 events (constants live here; the WS4 commit wires the call sites). */
  const val EVENT_NARRATION_START = "narration_start"
  const val EVENT_SYNTHESIS_RESULT = "synthesis_result"
  const val EVENT_MODEL_PACK = "model_pack"
  const val EVENT_PACK_SWITCH = "pack_switch"

  /** Log with string params; a null value drops the param (bundle needs non-null). */
  fun log(event: String, vararg params: Pair<String, String?>) {
    val client = analytics ?: return
    val bundle = Bundle()
    for ((key, value) in params) {
      if (value != null) bundle.putString(key, value)
    }
    try {
      client.logEvent(event, bundle)
    } catch (e: Exception) {
      // The placeholder google-services config and test stubs both degrade to
      // this log; playback/booking pipelines continue regardless.
      Log.w(TAG, "log $event failed: ${e.message}")
    }
  }
}
