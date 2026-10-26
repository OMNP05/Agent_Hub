package dev.agenthub.app.core.network

import dev.agenthub.app.auth.data.AuthApi
import dev.agenthub.app.core.session.FakeSessionStore
import dev.agenthub.app.core.session.Session
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class NetworkAuthTest {
    private lateinit var server: MockWebServer
    private lateinit var store: FakeSessionStore
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        store = FakeSessionStore(Session("old-access", "old-refresh"))
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AuthApi::class.java)
        client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(store, server.hostName))
            .authenticator(TokenAuthenticator(store, api))
            .build()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun get(path: String = "/agents") = client.newCall(Request.Builder().url(server.url(path)).build()).execute()

    private val refreshOk = """{"access_token":"new-access","refresh_token":"new-refresh"}"""

    @Test
    fun addsBearerToken() {
        server.enqueue(MockResponse().setBody("{}"))
        get().close()
        assertEquals("Bearer old-access", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun refreshesOnceOn401AndRetriesWithRotatedToken() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody(refreshOk))
        server.enqueue(MockResponse().setBody("{}"))

        val response = get()
        assertEquals(200, response.code)
        response.close()

        assertEquals("Bearer old-access", server.takeRequest().getHeader("Authorization"))
        val refresh = server.takeRequest()
        assertEquals("/auth/refresh", refresh.path)
        assertEquals(null, refresh.getHeader("Authorization"))
        assertEquals("Bearer new-access", server.takeRequest().getHeader("Authorization"))
        assertEquals(Session("new-access", "new-refresh").accessToken, store.current()?.accessToken)
        assertEquals("new-refresh", store.current()?.refreshToken)
    }

    @Test
    fun rejectedRefreshSignsTheUserOut() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"refresh_reused"}}"""))

        assertEquals(401, get().use { it.code })
        assertNull(store.current())
    }

    @Test
    fun serverErrorDuringRefreshKeepsTheSession() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(503))

        assertEquals(401, get().use { it.code })
        assertEquals("old-refresh", store.current()?.refreshToken)
        assertEquals(0, store.clearCount)
    }

    @Test
    fun stopsAfterOneRetryIfNewTokenIsAlsoRejected() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody(refreshOk))
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(401, get().use { it.code })
        assertEquals(3, server.requestCount)
    }

    @Test
    fun neverSendsTheTokenToAnotherHost() {
        val other = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(store, "api.example.invalid"))
            .build()
        server.enqueue(MockResponse().setBody("{}"))
        other.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()
        assertNull(server.takeRequest().getHeader("Authorization"))
    }
}
