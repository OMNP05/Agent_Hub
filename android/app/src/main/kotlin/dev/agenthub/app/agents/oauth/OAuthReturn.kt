package dev.agenthub.app.agents.oauth

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.net.URI
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

/** Result of the provider consent, delivered by the backend through the verified App Link. */
data class OAuthReturn(val agentId: String, val ok: Boolean)

object OAuthLinkParser {
    /** Expects `https://<host>/oauth/done?status=ok&agent_id=...`; returns null for anything else. */
    fun parse(url: String?, expectedHost: String): OAuthReturn? {
        val uri = try { url?.let(URI::create) } catch (e: IllegalArgumentException) { null } ?: return null
        if (uri.scheme != "https" || uri.host != expectedHost) return null
        if (uri.path?.startsWith("/oauth/done") != true) return null
        val params = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            val key = it.substringBefore('=')
            val value = it.substringAfter('=', "")
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }
        val agentId = params["agent_id"]?.takeIf { it.isNotBlank() } ?: return null
        return OAuthReturn(agentId, ok = params["status"] == "ok")
    }
}

/** Hands the App Link result from the Activity to whichever screen is waiting for it. */
@Singleton
class OAuthReturnBus @Inject constructor() {
    private val channel = Channel<OAuthReturn>(Channel.BUFFERED)
    val events: Flow<OAuthReturn> = channel.receiveAsFlow()
    fun publish(result: OAuthReturn) { channel.trySend(result) }
}
