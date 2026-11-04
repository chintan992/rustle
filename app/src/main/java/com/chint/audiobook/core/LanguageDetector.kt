package com.chint.audiobook.core

/**
 * Beta round WS2: book-language detection, pure Kotlin (JVM-testable).
 *
 * Two-stage, over a capped sample (~20 KB of text):
 *  1. Unicode-script histogram. Any dominant non-Latin script decides its
 *     language directly (Greek -> el, Arabic -> ar, Devanagari -> hi, kana
 *     presence -> ja, Hangul -> ko). Cyrillic, however, maps to three catalog
 *     languages (ru/bg/uk), so it falls through to a small stopword vote.
 *  2. Latin stopword scoring: the highest-frequency function words of each
 *     Latin-script catalog language are counted in the sample; the strongest
 *     per-word-list-normalized score wins. Close relatives (cs/sk, sv/da,
 *     fi/et, lv/lt, it/es/pt) can tie imperfectly — this is a beta heuristic
 *     backed by an explicit per-book override in the VoiceSheet.
 *
 * Returns the code, or null when there is not enough evidence (caller uses
 * null -> [LanguageCatalog.DEFAULT]).
 */
object LanguageDetector {

  private const val SAMPLE_LIMIT = 20_000

  private const val SCRIPT_FRACTION = 0.55
  private const val HANGUL_FRACTION = 0.20
  private const val HAN_FRACTION = 0.55
  private const val LATIN_FRACTION = 0.50
  private const val MIN_LETTERS = 30
  private const val MIN_WORDS = 8

  fun detect(raw: String): String? {
    val text = raw.take(SAMPLE_LIMIT)
    var kana = 0
    var hangul = 0
    var han = 0
    var cyrillic = 0
    var greek = 0
    var arabic = 0
    var devanagari = 0
    var latin = 0

    for (c in text) {
      val cp = c.code
      when {
        cp in 0x3041..0x30FF -> kana++                       // hiragana + katakana + prolonged mark
        cp in 0xAC00..0xD7AF -> hangul++
        cp in 0x4E00..0x9FFF -> han++
        cp in 0x0400..0x04FF -> cyrillic++
        cp in 0x0370..0x03FF -> greek++
        cp in 0x0600..0x06FF -> arabic++
        cp in 0x0900..0x097F -> devanagari++
        cp in 0x0041..0x005A || cp in 0x0061..0x007A -> latin++
      }
    }

    val total = kana + hangul + han + cyrillic + greek + arabic + devanagari + latin
    if (total < MIN_LETTERS) return null

    // Kana presence is decisive for Japanese, even beside many kanji (han).
    if (kana > 0 && kana >= han / 3) return "ja"
    if (devanagari >= total * SCRIPT_FRACTION) return "hi"
    if (hangul >= total * HANGUL_FRACTION) return "ko"
    if (han >= total * HAN_FRACTION) return null             // Chinese: not in the 31
    if (greek >= total * SCRIPT_FRACTION) return "el"
    if (arabic >= total * SCRIPT_FRACTION) return "ar"

    val wordTokens = tokenize(text)
    if (wordTokens.size < MIN_WORDS) return null

    if (cyrillic >= total * SCRIPT_FRACTION) {
      return voteLanguage(wordTokens, listOf("ru", "bg", "uk")) ?: "ru"
    }

    if (latin >= total * LATIN_FRACTION) {
      return voteLanguage(wordTokens, LATIN_CODES)
    }

    return null
  }

  // ------------------------------------------------------------------ internals

  private fun tokenize(text: String): List<String> =
    Regex("[\\p{L}\\p{M}']+")
      .findAll(text)
      .map { it.value }
      .filter { it.length in 1..20 }
      .take(SAMPLE_LIMIT)
      .toList()

  /**
   * Count every candidate language's function words in one pass over the
   * tokens and return the code with the highest score, normalized by list
   * size so a longer list cannot win by chance. Ties keep the first code
   * (insertion order: better-known languages first).
   */
  private fun voteLanguage(tokens: List<String>, codes: List<String>): String? {
    var bestCode: String? = null
    var bestScore = 0L
    for (code in codes) {
      val stops = STOPWORDS[code]?.toSet() ?: continue
      var score = 0L
      for (t in tokens) if (t.lowercase() in stops) score++
      if (score == 0L) continue
      val normalized = score * 1000 / stops.size
      if (normalized > bestScore) {
        bestScore = normalized
        bestCode = code
      }
    }
    return bestCode
  }

  // ------------------------------------------------------------------ word tables

  private val LATIN_CODES = listOf(
    "en", "cs", "da", "de", "es", "et", "fi", "fr", "hr", "hu", "id", "it",
    "lt", "lv", "nl", "pl", "pt", "ro", "sk", "sl", "sv", "tr", "vi"
  )

  private val STOPWORDS: Map<String, List<String>> = mapOf(
    // --- Latin-script languages
    "en" to listOf(
      "the", "and", "of", "to", "is", "was", "it", "he", "that", "with",
      "as", "his", "for", "on", "in", "she", "had", "not", "but", "her"
    ),
    "cs" to listOf(
      "je", "proto", "nebo", "které", "která", "jsou", "také", "jako",
      "ale", "všechno", "bude", "byl", "byla", "ne"
    ),
    "da" to listOf(
      "og", "det", "der", "en", "som", "er", "for", "med", "ikke", "han",
      "har", "om", "men", "denne", "af", "min", "sit", "var"
    ),
    "de" to listOf(
      "der", "die", "das", "und", "ist", "nicht", "ich", "er", "ein",
      "eine", "mit", "den", "dem", "von", "zu", "auch", "sich", "sie",
      "aber", "wurde"
    ),
    "es" to listOf(
      "el", "la", "los", "las", "un", "una", "y", "es", "de", "que",
      "en", "no", "por", "con", "para", "se", "su", "como", "pero", "muy"
    ),
    "et" to listOf(
      "ja", "on", "ei", "et", "ka", "mitte", "see", "aga", "mis", "kui",
      "kuidas", "selle", "oli", "siis", "veel", "tema"
    ),
    "fi" to listOf(
      "ja", "on", "ei", "että", "hän", "mutta", "niin", "sen", "kun",
      "siitä", "ovat", "hänen", "sitä", "koska", "siellä", "sitten"
    ),
    "fr" to listOf(
      "le", "la", "les", "des", "un", "une", "et", "est", "de", "du",
      "en", "que", "qui", "pas", "sur", "pour", "dans", "au", "ce", "elle"
    ),
    "hr" to listOf(
      "je", "ne", "se", "ali", "bio", "bila", "su", "kada", "sve", "samo",
      "jer", "nisam", "nema", "ima", "kao", "kako", "za", "sa"
    ),
    "hu" to listOf(
      "az", "egy", "nem", "hogy", "van", "mint", "majd", "de", "is", "meg",
      "volt", "minden", "csak", "nagy", "ennek", "azzal"
    ),
    "id" to listOf(
      "dan", "yang", "di", "itu", "dengan", "tidak", "untuk", "ini",
      "dari", "adalah", "akan", "karena", "saat", "bisa", "juga", "sudah"
    ),
    "it" to listOf(
      "il", "lo", "la", "i", "gli", "le", "un", "uno", "una", "e", "è",
      "di", "che", "in", "non", "per", "con", "si", "come", "ma"
    ),
    "lt" to listOf(
      "ir", "yra", "ne", "kad", "bet", "per", "nuo", "iki", "su", "be",
      "tuo", "dėl", "arba", "jis", "ji", "jų", "nes", "labai"
    ),
    "lv" to listOf(
      "un", "ir", "ne", "bet", "kad", "kā", "no", "to", "lai", "viss",
      "jau", "tad", "vai", "arī", "pret", "tādēļ"
    ),
    "nl" to listOf(
      "de", "het", "een", "en", "is", "van", "niet", "ik", "met", "dat",
      "zijn", "ook", "maar", "voor", "nog", "op", "hij", "deze", "werd", "te"
    ),
    "pl" to listOf(
      "nie", "się", "jest", "to", "na", "że", "do", "tak", "był", "jak",
      "tego", "przez", "po", "ta", "ten", "już", "ale", "czy"
    ),
    "pt" to listOf(
      "o", "a", "os", "as", "um", "uma", "de", "que", "em", "é", "não",
      "para", "com", "se", "seu", "como", "mas", "muito", "pela", "pelos"
    ),
    "ro" to listOf(
      "de", "la", "un", "și", "în", "va", "ce", "pentru", "din", "care",
      "cu", "sunt", "este", "dar", "mai", "au", "este", "sunt"
    ),
    "sk" to listOf(
      "je", "ako", "alebo", "tento", "všetko", "tiež", "ktoré", "ktorý",
      "ne", "ale", "bol", "bola", "bolo", "sú"
    ),
    "sl" to listOf(
      "je", "se", "pa", "tudi", "ker", "za", "kot", "na", "brez", "šele",
      "bil", "bila", "bilo", "so", "kar", "vse", "samo", "kaj"
    ),
    "sv" to listOf(
      "och", "det", "att", "en", "som", "är", "för", "med", "inte", "han",
      "på", "den", "har", "om", "men", "vad", "utan", "innan"
    ),
    "tr" to listOf(
      "ve", "bir", "bu", "için", "ile", "değil", "çok", "ama", "daha",
      "gibi", "sonra", "her", "olarak", "kadar", "var", "bu"
    ),
    "vi" to listOf(
      "của", "và", "một", "những", "không", "được", "đã", "trong", "các",
      "cho", "người", "này", "với", "khi", "theo", "kia"
    ),
    // --- Cyrillic trio (stopword vote follows the script histogram)
    "ru" to listOf(
      "что", "это", "как", "был", "но", "же", "его", "их", "или", "так",
      "она", "они", "все", "всё", "ни", "по", "из", "у", "тогда", "когда"
    ),
    "bg" to listOf(
      "не", "са", "им", "за", "какво", "толкова", "само", "още", "нищо",
      "този", "тези", "сега", "има", "все", "хич", "бил"
    ),
    "uk" to listOf(
      "і", "та", "це", "є", "або", "її", "щоб", "цим", "також", "немає",
      "тільки", "його", "вже", "тому", "коли", "нього", "разом", "мені"
    )
  )
}
