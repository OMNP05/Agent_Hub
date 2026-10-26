package dev.agenthub.app.agents.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OAuthLinkParserTest {
    private val host = "agenthub.example"

    @Test
    fun parsesSuccessAndFailureLinks() {
        assertEquals(OAuthReturn("a1", true), OAuthLinkParser.parse("https://agenthub.example/oauth/done?status=ok&agent_id=a1", host))
        assertEquals(OAuthReturn("a1", false), OAuthLinkParser.parse("https://agenthub.example/oauth/done?status=error&agent_id=a1", host))
    }

    @Test
    fun rejectsWrongSchemeHostPathOrMissingAgent() {
        assertNull(OAuthLinkParser.parse("http://agenthub.example/oauth/done?status=ok&agent_id=a1", host))
        assertNull(OAuthLinkParser.parse("https://evil.example/oauth/done?status=ok&agent_id=a1", host))
        assertNull(OAuthLinkParser.parse("https://agenthub.example/other?status=ok&agent_id=a1", host))
        assertNull(OAuthLinkParser.parse("https://agenthub.example/oauth/done?status=ok", host))
        assertNull(OAuthLinkParser.parse("https://agenthub.example/oauth/done?status=ok&agent_id=", host))
        assertNull(OAuthLinkParser.parse(null, host))
        assertNull(OAuthLinkParser.parse("not a url", host))
    }

    @Test
    fun hostLookalikesAreRejected() {
        assertNull(OAuthLinkParser.parse("https://agenthub.example.evil.com/oauth/done?status=ok&agent_id=a1", host))
        assertNull(OAuthLinkParser.parse("https://evil.com/@agenthub.example/oauth/done?status=ok&agent_id=a1", host))
    }
}
