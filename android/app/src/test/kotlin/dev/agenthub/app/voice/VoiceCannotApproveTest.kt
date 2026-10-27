package dev.agenthub.app.voice

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * CLAUDE.md rule 3: voice can start a request but can never approve one. This test makes that a build
 * failure: no file in the voice package may reference the approvals code or the decision API.
 */
class VoiceCannotApproveTest {
    private val forbidden = listOf(
        "approvals.",
        "ApprovalRepository",
        "ApprovalsViewModel",
        "Decision",
        "decide(",
        "AgentHubApi",
        "/decision",
    )

    private fun voiceSources(): List<File> {
        val dir = File("src/main/kotlin/dev/agenthub/app/voice").takeIf { it.isDirectory }
            ?: File("app/src/main/kotlin/dev/agenthub/app/voice")
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun theVoicePackageHasSourcesToCheck() {
        assertTrue("voice sources not found, the guard would pass vacuously", voiceSources().size >= 4)
    }

    @Test
    fun noVoiceCodeReferencesApprovals() {
        for (file in voiceSources()) {
            val text = file.readText()
            for (word in forbidden) {
                if (text.contains(word)) fail("${file.name} must not reference '$word': voice can never approve")
            }
        }
    }

    @Test
    fun theApprovalScreenOffersNoVoiceEntryPoint() {
        val approvalsDir = File("src/main/kotlin/dev/agenthub/app/approvals").takeIf { it.isDirectory }
            ?: File("app/src/main/kotlin/dev/agenthub/app/approvals")
        for (file in approvalsDir.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val text = file.readText()
            if (text.contains("dev.agenthub.app.voice") || text.contains("VoiceViewModel") || text.contains("SpeechEngine")) {
                fail("${file.name} must not use voice code: approvals need a tap")
            }
        }
    }
}
