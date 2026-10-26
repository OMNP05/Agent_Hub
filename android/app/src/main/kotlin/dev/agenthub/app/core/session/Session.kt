package dev.agenthub.app.core.session

import kotlinx.coroutines.flow.Flow

/** The app's own session with the Agent Hub backend. Never holds provider tokens or LLM keys. */
data class Session(val accessToken: String, val refreshToken: String) {
    // Tokens must never reach logs, so the generated toString is replaced.
    override fun toString(): String = "Session(***)"
}

interface SessionStore {
    val session: Flow<Session?>

    /** Blocking read for OkHttp threads; backed by an in-memory copy. */
    fun current(): Session?

    suspend fun save(session: Session)
    suspend fun clear()
}
