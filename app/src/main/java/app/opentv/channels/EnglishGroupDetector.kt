/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.channels

import java.text.Normalizer
import java.util.Locale

/** Local metadata evidence, never a guarantee about a stream's audio. */
enum class EnglishConfidence { LIKELY, REVIEW, UNKNOWN }
enum class EnglishEvidence { EXPLICIT_LABEL, MIXED_LANGUAGES, AMBIGUOUS_CONTEXT, NO_EVIDENCE, UNUSABLE_NAME }
data class EnglishClassification(val confidence: EnglishConfidence, val evidence: EnglishEvidence)

/** Deliberately conservative: no geography, channel names, EPG, playback or network input. */
object EnglishGroupDetector {
    const val MAX_NAME_LENGTH = 512
    private val tokenBreak = Regex("[^\\p{L}\\p{M}\\p{N}]+")
    // These tokens must stand alone: Engineering, Engagement and England never match.
    private val english = setOf("english", "en", "eng")
    private val categoryWords = setOf("channels", "movies", "sports", "news", "entertainment", "tv", "kids", "music", "documentaries")
    private val bracketedEnglish = Regex("[\\[(]\\s*(en|eng)\\s*[\\])]")
    private val separatedEnglish = Regex("(^|[|:/;–—-])\\s*(en|eng)\\s*($|[|:/;–—-])")
    private val otherLanguages = setOf(
        "french", "francais", "français", "fr", "fra", "fre", "spanish", "español", "espanol", "es", "spa",
        "german", "deutsch", "de", "deu", "ger", "italian", "italiano", "it", "ita", "portuguese", "português", "pt", "por",
        "arabic", "ar", "ara", "hindi", "hi", "hin", "urdu", "ur", "russian", "ru", "rus", "turkish", "tr", "tur",
        "chinese", "mandarin", "cantonese", "zh", "zho", "japanese", "ja", "jpn", "korean", "ko", "kor",
        "dutch", "nl", "nld", "polish", "pl", "pol", "punjabi", "tamil", "telugu", "bengali", "greek", "el", "ell",
        "romanian", "ro", "ron", "ukrainian", "ukr", "uk", "persian", "farsi", "fa", "fas", "vietnamese", "vi",
        "thai", "th", "indonesian", "id", "malay", "ms", "swedish", "sv", "danish", "da", "norwegian", "no", "finnish", "fi",
        "welsh", "cymraeg", "cy", "cym", "irish", "ga", "gle", "gaelic", "gd", "gla", "hebrew", "he", "heb",
        "czech", "cs", "ces", "slovak", "sk", "slk", "hungarian", "hu", "hun", "bulgarian", "bg", "bul",
        "serbian", "sr", "srp", "croatian", "hr", "hrv", "bosnian", "bs", "bos", "albanian", "sq", "sqi",
        "somali", "swahili", "afrikaans", "zulu", "amharic", "nepali", "sinhala", "malayalam", "kannada", "marathi", "gujarati",
        "العربية", "हिन्दी", "русский", "中文", "日本語", "한국어", "ελληνικά",
    )
    private val mixed = setOf("multi", "multilingual", "bilingual", "dual", "international", "world", "global", "mixed", "various")
    private val ambiguous = setOf("not", "non", "without", "except", "sub", "subs", "subtitle", "subtitles", "subtitled", "subbed", "vivo", "direct", "caption", "captions", "cc", "sdh", "learning", "learn", "course", "courses", "premier", "league", "championship", "football", "soccer", "cup")

    fun classify(name: String?): EnglishClassification {
        // Reject rather than truncate: an unseen suffix might say "subtitles" or another language.
        if (name == null || name.length > MAX_NAME_LENGTH || name.isBlank() || name.any { Character.isISOControl(it) && !it.isWhitespace() }) {
            return EnglishClassification(EnglishConfidence.UNKNOWN, EnglishEvidence.UNUSABLE_NAME)
        }
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        if (normalized.length > MAX_NAME_LENGTH || normalized.any { Character.getType(it) == Character.FORMAT.toInt() }) {
            return EnglishClassification(EnglishConfidence.UNKNOWN, EnglishEvidence.UNUSABLE_NAME)
        }
        if (hasMalformedSurrogates(normalized)) return EnglishClassification(EnglishConfidence.UNKNOWN, EnglishEvidence.UNUSABLE_NAME)
        val tokens = normalized.split(tokenBreak).filter { it.isNotEmpty() }
        if (tokens.none { it in english }) return EnglishClassification(EnglishConfidence.UNKNOWN, EnglishEvidence.NO_EVIDENCE)
        if (tokens.any { it in otherLanguages || it in mixed }) return EnglishClassification(EnglishConfidence.REVIEW, EnglishEvidence.MIXED_LANGUAGES)
        if (tokens.any { it in ambiguous }) return EnglishClassification(EnglishConfidence.REVIEW, EnglishEvidence.AMBIGUOUS_CONTEXT)
        // Abbreviations need an isolated label or a leading category descriptor. Natural phrases
        // such as "Made in EN" or "En Route" remain ambiguous.
        val deliberate = "english" in tokens ||
            (tokens.firstOrNull() in english && (tokens.size == 1 || tokens.getOrNull(1) in categoryWords)) ||
            bracketedEnglish.containsMatchIn(normalized) || separatedEnglish.containsMatchIn(normalized)
        return if (deliberate) EnglishClassification(EnglishConfidence.LIKELY, EnglishEvidence.EXPLICIT_LABEL)
        else EnglishClassification(EnglishConfidence.REVIEW, EnglishEvidence.AMBIGUOUS_CONTEXT)
    }
    private fun hasMalformedSurrogates(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (Character.isHighSurrogate(char)) {
                if (index == value.length || !Character.isLowSurrogate(value[index++])) return true
            } else if (Character.isLowSurrogate(char)) return true
        }
        return false
    }

}
