package dev.agenthub.app.security

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * CLAUDE.md security rules 5 and 10: no secrets in the Android app or in source control. This scans the
 * committed app sources for credential-shaped strings and for forbidden storage or logging calls.
 */
class NoSecretsInSourceTest {
    private fun appRoot(): File = File("src").takeIf { it.isDirectory } ?: File("app/src")

    private fun sources(): List<File> =
        appRoot().walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml", "kts") && "/build/" !in it.invariantSeparatorsPath }.toList()

    // Shapes of real credentials; test fixtures use obviously fake values like "sk-test" and are excluded below.
    private val secretShapes = listOf(
        Regex("""AIza[0-9A-Za-z_\-]{35}"""),
        Regex("""sk-ant-[A-Za-z0-9_\-]{20,}"""),
        Regex("""sk-[A-Za-z0-9]{40,}"""),
        Regex("""gh[pousr]_[A-Za-z0-9]{36,}"""),
        Regex("""-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"""),
        Regex("""[0-9]{8,}-[0-9a-z]{20,}\.apps\.googleusercontent\.com"""),
        Regex("""GOCSPX-[A-Za-z0-9_\-]{20,}"""),
    )

    @Test
    fun theScannerFindsTheSourceTree() {
        assertTrue("source tree not found, the scan would pass vacuously", sources().size > 100)
    }

    @Test
    fun noCredentialShapedStringsAreCommitted() {
        for (file in sources()) {
            val text = file.readText()
            for (shape in secretShapes) {
                val hit = shape.find(text)
                if (hit != null) fail("${file.name} contains something shaped like a credential (${shape.pattern.take(24)}...)")
            }
        }
    }

    @Test
    fun noPlaintextPreferenceStorageOfTokens() {
        for (file in sources().filter { it.extension == "kt" && "/test/" !in it.invariantSeparatorsPath }) {
            val text = file.readText()
            if (text.contains("getSharedPreferences(")) fail("${file.name} uses SharedPreferences; secrets must use the encrypted session store")
        }
    }

    @Test
    fun productionCodeNeverLogs() {
        // Logging is the easiest way to leak a token or an email body, so the app does not log at all.
        for (file in sources().filter { it.extension == "kt" && "/test/" !in it.invariantSeparatorsPath }) {
            val text = file.readText()
            if (Regex("""\bLog\.[dviwe]\(""").containsMatchIn(text) || text.contains("println(") || text.contains("Timber.")) {
                fail("${file.name} writes to the log; tokens, keys and message bodies must never be logged")
            }
        }
    }

    @Test
    fun theManifestDisablesBackupSoNothingLeavesTheDevice() {
        val manifest = File(appRoot(), "main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""android:allowBackup="false""""))
    }

    @Test
    fun cleartextTrafficIsOnlyAllowedInTheDebugBuild() {
        val main = File(appRoot(), "main/res/xml/network_security_config.xml").readText()
        assertTrue(main.contains("""cleartextTrafficPermitted="false""""))
        assertTrue("main config must not whitelist cleartext hosts", !main.contains("domain-config"))
    }

    @Test
    fun noGoogleServicesFileOrKeystoreIsCheckedIn() {
        val forbidden = listOf("google-services.json", "local.properties", "keystore.properties")
        val found = appRoot().absoluteFile.parentFile.walkTopDown().filter { f ->
            f.isFile && "/build/" !in f.invariantSeparatorsPath && (f.name in forbidden || f.extension in setOf("jks", "keystore"))
        }.toList()
        // local.properties legitimately exists on a developer machine; it is gitignored, so only committed files matter.
        val risky = found.filter { it.name != "local.properties" }
        assertTrue("secret files in the project tree: ${risky.map { it.name }}", risky.isEmpty())
    }
}
