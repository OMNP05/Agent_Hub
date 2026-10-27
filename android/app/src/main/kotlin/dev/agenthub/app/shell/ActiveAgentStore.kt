package dev.agenthub.app.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The agent the mini-bar acts on. Set when a chat is opened; cleared when that agent is deleted. */
@Singleton
class ActiveAgentStore @Inject constructor() {
    private val _agent = MutableStateFlow<ActiveAgent?>(null)
    val agent: StateFlow<ActiveAgent?> = _agent.asStateFlow()

    fun set(agent: ActiveAgent?) { _agent.value = agent }
}
