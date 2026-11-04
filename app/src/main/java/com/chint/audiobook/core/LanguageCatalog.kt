package com.chint.audiobook.core

/**
 * Beta round WS2: the 31 languages of the Supertonic-3 pack (HF card /
 * sherpa-onnx-supertonic-3-tts-int8-2026-05-11 language table, verified
 * 2026-10-26). Codes are the `lang` values the sherpa JNI reads
 * (GetExtraString("lang", ...)) and the keys the pipeline stores on
 * [com.chint.audiobook.data.model.Book.language].
 */
object LanguageCatalog {

  /** Cache-independent storage value meaning "redetect on next re-prep". */
  const val AUTO = "auto"

  /** Book/segment default when detection has no usable evidence. */
  const val DEFAULT = "en"

  val ALL: Map<String, String> = mapOf(
    "en" to "English",
    "ko" to "Korean",
    "ja" to "Japanese",
    "ar" to "Arabic",
    "bg" to "Bulgarian",
    "cs" to "Czech",
    "da" to "Danish",
    "de" to "German",
    "el" to "Greek",
    "es" to "Spanish",
    "et" to "Estonian",
    "fi" to "Finnish",
    "fr" to "French",
    "hi" to "Hindi",
    "hr" to "Croatian",
    "hu" to "Hungarian",
    "id" to "Indonesian",
    "it" to "Italian",
    "lt" to "Lithuanian",
    "lv" to "Latvian",
    "nl" to "Dutch",
    "pl" to "Polish",
    "pt" to "Portuguese",
    "ro" to "Romanian",
    "ru" to "Russian",
    "sk" to "Slovak",
    "sl" to "Slovenian",
    "sv" to "Swedish",
    "tr" to "Turkish",
    "uk" to "Ukrainian",
    "vi" to "Vietnamese"
  )

  /** Catalog codes in display (field-insertion) order, for pickers. */
  fun codes(): List<String> = ALL.keys.toList()

  fun displayName(code: String): String = ALL[code] ?: code

  fun isKnown(code: String): Boolean = ALL.containsKey(code)

  /** Normalize user/storage values into a usable code (unknown/auto/blank -> default). */
  fun normalizeOrNull(code: String?): String? =
    when {
      code == null -> null
      code == AUTO -> AUTO
      isKnown(code.lowercase()) -> code.lowercase()
      isKnown(code.substringBefore("-").substringBefore("_").lowercase()) ->
        code.substringBefore("-").substringBefore("_").lowercase()
      else -> null
    }
}
