package dev.agenthub.app.demo

import dev.agenthub.app.agents.data.AgentRepository
import dev.agenthub.app.agents.data.DefaultAgentRepository
import dev.agenthub.app.approvals.data.ApprovalRepository
import dev.agenthub.app.approvals.data.DefaultApprovalRepository
import dev.agenthub.app.auth.data.AuthApi
import dev.agenthub.app.chat.data.ChatRepository
import dev.agenthub.app.chat.data.DefaultChatRepository
import dev.agenthub.app.chat.data.OkHttpChatStream
import dev.agenthub.app.core.database.FakeAgentDao
import dev.agenthub.app.core.database.FakeConversationDao
import dev.agenthub.app.core.database.FakeMessageDao
import dev.agenthub.app.core.network.AgentHubApi
import dev.agenthub.app.core.network.AuthInterceptor
import dev.agenthub.app.core.network.TokenAuthenticator
import dev.agenthub.app.core.network.UnconfinedDispatchers
import dev.agenthub.app.core.session.FakeSessionStore
import dev.agenthub.app.llm.data.DefaultLlmRepository
import dev.agenthub.app.llm.data.LlmRepository
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The real app stack wired to the demo backend, with the same OkHttp interceptors, Retrofit converters, SSE
 * client and repositories that production uses. Only the Room DAOs are in-memory fakes, because they need
 * an Android device. Streaming pauses are removed (speed = 0) so the tests run in milliseconds.
 */
class DemoHarness(
    val backend: DemoBackend = DemoBackend(latencyMs = 0, speed = 0.0),
    val store: FakeSessionStore = FakeSessionStore(),
) {
    private val base = "http://demo.test/".toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val plain = OkHttpClient.Builder().addInterceptor(backend).build()
    private val authApi = retrofit(plain).create(AuthApi::class.java)
    private val authed = plain.newBuilder()
        .addInterceptor(AuthInterceptor(store, base.host))
        .authenticator(TokenAuthenticator(store, authApi))
        .build()
    val api: AgentHubApi = retrofit(authed).create(AgentHubApi::class.java)

    val agentDao = FakeAgentDao()
    private val conversationDao = FakeConversationDao()
    private val messageDao = FakeMessageDao()

    val agents: AgentRepository = DefaultAgentRepository(api, agentDao, json, UnconfinedDispatchers)
    val chats: ChatRepository = DefaultChatRepository(api, conversationDao, messageDao, json, UnconfinedDispatchers)
    val approvals: ApprovalRepository = DefaultApprovalRepository(api, UnconfinedDispatchers)
    val llm: LlmRepository = DefaultLlmRepository(api, UnconfinedDispatchers)
    val stream = OkHttpChatStream(authed.newBuilder().readTimeout(0, TimeUnit.SECONDS).build(), base)
    val auth = dev.agenthub.app.auth.data.DefaultAuthRepository(authApi, store, UnconfinedDispatchers, dev.agenthub.app.push.FakeDeviceRepository())

    private fun retrofit(client: OkHttpClient) = Retrofit.Builder()
        .baseUrl(base)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    companion object {
        val epoch: Instant = Instant.parse("2026-10-27T10:00:00Z")
    }
}
