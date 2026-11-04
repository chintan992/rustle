package com.chint.audiobook.core

import java.io.File
import java.security.MessageDigest

object SegmentHasher {
  const val DEFAULT_ENGINE_ID = "supertonic-3"
  const val DEFAULT_MODEL_DIGEST = "int8-2026-05-11"

  fun computeModelDigest(modelDir: File): String {
    if (!modelDir.exists() || !modelDir.isDirectory) {
      return DEFAULT_MODEL_DIGEST
    }

    val ttsJson = File(modelDir, "tts.json")
    val filesInfo = listOf(
      "duration_predictor.int8.onnx",
      "text_encoder.int8.onnx",
      "vector_estimator.int8.onnx",
      "vocoder.int8.onnx"
    ).joinToString(";") { name ->
      val f = File(modelDir, name)
      "$name:${if (f.exists()) f.length() else 0L}"
    }

    val ttsContent = if (ttsJson.exists()) ttsJson.readText() else "missing"
    val rawPayload = "$ttsContent\n$filesInfo"

    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(rawPayload.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }.take(16)
  }

  fun computeHash(
    text: String,
    voice: String,
    engineId: String = DEFAULT_ENGINE_ID,
    modelDigest: String = DEFAULT_MODEL_DIGEST,
    language: String = "en"
  ): String {
    val normLang = language.trim().lowercase()
    val payload = if (normLang.isEmpty() || normLang == "en") {
      "$engineId\n$modelDigest\n$voice\n$text"
    } else {
      "$engineId\n$modelDigest\n$voice\n$normLang\n$text"
    }
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(payload.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
  }
}
