package dev.agenthub.app.core.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSessionStore(initial: Session? = null) : SessionStore {
    private val state = MutableStateFlow(initial)
    var clearCount = 0
        private set

    override val session: Flow<Session?> = state
    override fun current(): Session? = state.value
    override suspend fun save(session: Session) { state.value = session }
    override suspend fun clear() { clearCount++; state.value = null }
}
