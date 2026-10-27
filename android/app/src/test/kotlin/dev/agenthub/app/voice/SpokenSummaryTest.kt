package dev.agenthub.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenSummaryTest {
    @Test
    fun shortTextIsKeptAsIs() {
        assertEquals("You have 3 open PRs.", SpokenSummary.shorten("You have 3 open PRs."))
    }

    @Test
    fun markdownLinksAndUrlsAreRemovedForSpeech() {
        assertEquals("Open the report now.", SpokenSummary.shorten("Open the [report](https://x.example/r?a=1) now."))
        assertEquals("See", SpokenSummary.shorten("See https://x.example/long/path"))
    }

    @Test
    fun formattingMarksAreDropped() {
        assertEquals("Bold and code and heading", SpokenSummary.shorten("**Bold** and `code` and # heading".replace("# ", "")))
    }

    @Test
    fun longRepliesAreCutAtASentenceBoundary() {
        val text = "First sentence is here. " + "Second sentence goes on for a while. ".repeat(20)
        val short = SpokenSummary.shorten(text, maxChars = 80)
        assertTrue(short.length <= 80)
        assertTrue(short.endsWith("."))
        assertTrue(short.startsWith("First sentence is here."))
    }

    @Test
    fun aSingleVeryLongSentenceIsCutAtAWordBoundary() {
        val short = SpokenSummary.shorten("word ".repeat(100), maxChars = 50)
        assertTrue(short.length <= 50)
        assertTrue(short.split(" ").all { it == "word" })
    }

    @Test
    fun hindiSentenceEndingIsRespected() {
        val text = "यह पहला वाक्य है। " + "यह दूसरा लंबा वाक्य है जो बहुत आगे तक जाता है। ".repeat(10)
        val short = SpokenSummary.shorten(text, maxChars = 60)
        assertTrue(short.endsWith("।"))
    }

    @Test
    fun emptyAndLinkOnlyInputGiveNothing() {
        assertEquals("", SpokenSummary.shorten(""))
        assertEquals("", SpokenSummary.shorten("   \n  "))
        assertEquals("", SpokenSummary.shorten("https://x.example"))
    }

    @Test
    fun recognizerErrorCodesMapToUserFacingCategories() {
        assertEquals(VoiceError.NoSpeech, SpeechErrors.fromCode(7))
        assertEquals(VoiceError.NoSpeech, SpeechErrors.fromCode(6))
        assertEquals(VoiceError.Network, SpeechErrors.fromCode(2))
        assertEquals(VoiceError.Network, SpeechErrors.fromCode(1))
        assertEquals(VoiceError.Busy, SpeechErrors.fromCode(8))
        assertEquals(VoiceError.PermissionDenied, SpeechErrors.fromCode(9))
        assertEquals(VoiceError.Failed, SpeechErrors.fromCode(5))
        assertEquals(VoiceError.Failed, SpeechErrors.fromCode(999))
    }

    @Test
    fun languagesCycleAndCarryRecognitionTags() {
        assertEquals("en-IN", VoiceLanguage.English.tag)
        assertEquals("hi-IN", VoiceLanguage.Hindi.tag)
        assertEquals(VoiceLanguage.Hindi, VoiceLanguage.English.next())
        assertEquals(VoiceLanguage.English, VoiceLanguage.Hindi.next())
    }
}
