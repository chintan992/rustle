package com.chint.audiobook.engine

import com.chint.audiobook.core.LanguageCatalog
import com.chint.audiobook.data.model.TtsEngine

object TtsEngineSelection {
  fun forLanguage(preferred: TtsEngine, language: String): TtsEngine {
    val normalized = LanguageCatalog.normalizeOrNull(language)
    return if (preferred == TtsEngine.KOKORO && normalized == "en") {
      TtsEngine.KOKORO
    } else {
      TtsEngine.SUPERTONIC
    }
  }
}
