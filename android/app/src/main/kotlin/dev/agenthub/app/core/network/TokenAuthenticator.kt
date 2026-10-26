package dev.agenthub.app.core.network

import dev.agenthub.app.auth.data.AuthApi
import dev.agenthub.app.auth.data.RefreshRequest
import dev.agenthub.app.core.session.Session
import dev.agenthub.app.core.session.SessionStore
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException

/**
 * Refreshes the session once after a 401 and retries the request.
 *
 * - Refresh tokens rotate, so refreshes are serialised: a second 401 that arrives while another
 *   thread refreshed simply retries with the new access token.
 * - A refresh the server rejects (4xx, including refresh-token reuse) signs the user out.
 * - A network failure or 5xx keeps the session so the user is not logged out by a bad connection.
 */
class TokenAuthenticator(
    private val store: SessionStore,
    private val authApi: AuthApi,
) : Authenticator {

    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (attempts(response) >= MAX_ATTEMPTS) return null
        val sentToken = response.request.header("Authorization")?.removePrefix("Bearer ")

        synchronized(lock) {
            val current = store.current() ?: return null
            if (sentToken != null && sentToken != current.accessToken) {
                return response.request.withToken(current.accessToken)
            }
            val refreshed = refresh(current) ?: return null
            return response.request.withToken(refreshed.accessToken)
        }
    }

    private fun refresh(current: Session): Session? {
        val result = try {
            runBlocking { authApi.refresh(RefreshRequest(current.refreshToken)) }
        } catch (e: IOException) {
            return null
        }
        val body = result.body()
        if (result.isSuccessful && body != null) {
            val next = Session(body.accessToken, body.refreshToken)
            runBlocking { store.save(next) }
            return next
        }
        if (result.code() in 400..499) runBlocking { store.clear() }
        return null
    }

    private fun attempts(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private fun Request.withToken(token: String): Request =
        newBuilder().header("Authorization", "Bearer $token").build()

    private companion object {
        const val MAX_ATTEMPTS = 2
    }
}
