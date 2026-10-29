/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.channels

import org.junit.Assert.*
import org.junit.Test

class EnglishGroupDetectorTest {
    @Test fun explicitLabelsAndSeparators() {
        listOf("English", "English Channels", "English Movies", "English Entertainment", "EN | Movies", "[EN] Sports", "ENG - News", "Movies (English)", " eNgLiSh  ", "ＥＮＧ｜Movies", "[en]\u00a0Sports", "English—News", "🇬🇧 English", "EN", "Movies / ENG")
            .forEach { assertEquals(it, EnglishConfidence.LIKELY, EnglishGroupDetector.classify(it).confidence) }
    }
    @Test fun geographyBrandsAndSubstringsDoNotProveLanguage() {
        listOf("UK", "USA", "Canada", "BBC", "FOX", "BBC One News", "Engineering", "Engagement", "England", "Entertainment", "French", "News", "enigma", "ENGland", "Movies ENgineering", "EN123", "123EN")
            .forEach { assertEquals(it, EnglishConfidence.UNKNOWN, EnglishGroupDetector.classify(it).confidence) }
    }
    @Test fun mixedLanguageAndContextualEnglishAreNeverHighConfidence() {
        listOf("En Route", "EN VIVO", "Not English", "Non-English", "English / Welsh", "English / French", "EN|ES Movies", "English and Arabic", "English हिन्दी", "English / 中文", "English multilingual", "EN dual audio", "English subtitles", "Sub EN", "English CC", "English Premier League", "English Football League", "English Championship", "Learn English", "Made in EN")
            .forEach { assertEquals(it, EnglishConfidence.REVIEW, EnglishGroupDetector.classify(it).confidence) }
    }
    @Test fun malformedAndUnboundedInputIsSafeAndNeverTruncatedIntoConfidence() {
        listOf(null, "", "   ", "English\u0000", "En\u200bglish", "English\u202e", "\ud800English", "English " + "x".repeat(100_000), "x".repeat(100_000) + "English")
            .forEach { assertEquals(EnglishConfidence.UNKNOWN, EnglishGroupDetector.classify(it).confidence) }
        assertEquals(EnglishConfidence.REVIEW, EnglishGroupDetector.classify("English\nsubtitles").confidence)
    }
    @Test fun classificationIsDeterministicOnLargeCategoryDataset() {
        val names = List(20_000) { if (it % 3 == 0) "EN | Movies $it" else "USA | Movies $it" }
        val before = names.toList()
        val first = names.map(EnglishGroupDetector::classify)
        assertEquals(first, names.map(EnglishGroupDetector::classify))
        assertEquals(6_667, first.count { it.confidence == EnglishConfidence.LIKELY })
        assertEquals(before, names)
    }
}
