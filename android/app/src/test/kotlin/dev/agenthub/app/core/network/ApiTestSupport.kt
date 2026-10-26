package dev.agenthub.app.core.network

import dev.agenthub.app.core.coroutines.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockWebServer
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

val testJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

object UnconfinedDispatchers : DispatcherProvider {
    private val d: CoroutineDispatcher = Dispatchers.Unconfined
    override val main = d
    override val io = d
    override val default = d
}

fun MockWebServer.agentHubApi(): AgentHubApi = Retrofit.Builder()
    .baseUrl(url("/"))
    .addConverterFactory(testJson.asConverterFactory("application/json".toMediaType()))
    .build()
    .create(AgentHubApi::class.java)
