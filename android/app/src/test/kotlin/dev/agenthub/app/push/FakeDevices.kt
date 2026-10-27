package dev.agenthub.app.push

import dev.agenthub.app.core.error.AppResult

class FakeDeviceRepository : DeviceRepository {
    var registerCalls = 0
    var registeredTokens = mutableListOf<String>()
    var unregisterCalls = 0
    var failUnregister = false

    override suspend fun register(): AppResult<Unit> { registerCalls++; return AppResult.Success(Unit) }
    override suspend fun register(token: String): AppResult<Unit> { registeredTokens += token; return AppResult.Success(Unit) }
    override suspend fun unregister() {
        unregisterCalls++
        if (failUnregister) throw IllegalStateException("push cleanup failed")
    }
}
