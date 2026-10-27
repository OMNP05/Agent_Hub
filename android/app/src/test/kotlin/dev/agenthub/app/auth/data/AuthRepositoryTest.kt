package dev.agenthub.app.auth.data

import dev.agenthub.app.core.coroutines.DispatcherProvider
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.session.FakeSessionStore
import dev.agenthub.app.core.session.Session
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class AuthRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var store: FakeSessionStore
    private lateinit var repo: DefaultAuthRepository
    private lateinit var devices: dev.agenthub.app.push.FakeDeviceRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        store = FakeSessionStore()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AuthApi::class.java)
        devices = dev.agenthub.app.push.FakeDeviceRepository()
        repo = DefaultAuthRepository(api, store, TestDispatchers(Dispatchers.Unconfined), devices)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun successfulSignInStoresSessionAndSendsNonce() = runTest {
        server.enqueue(MockResponse().setBody("""{"access_token":"a","refresh_token":"r","expires_in":900}"""))

        val result = repo.signInWithGoogle("google-id-token", "nonce-1")

        assertEquals(AppResult.Success(Unit), result)
        assertEquals("a", store.current()?.accessToken)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"id_token\":\"google-id-token\"") && body.contains("\"nonce\":\"nonce-1\""))
    }

    @Test
    fun backendErrorCodeBecomesDomainErrorAndNoSessionIsStored() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"approval_expired","detail":"stack trace"}}"""))

        val result = repo.signInWithGoogle("t", "n")

        assertEquals(AppResult.Failure(DataError.ApprovalExpired), result)
        assertNull(store.current())
    }

    @Test
    fun networkFailureBecomesNetworkError() = runTest {
        server.shutdown()
        val result = repo.signInWithGoogle("t", "n")
        assertEquals(AppResult.Failure(DataError.Network), result)
    }

    @Test
    fun signOutClearsLocalSessionEvenWhenServerFails() = runTest {
        store.save(Session("a", "r"))
        server.enqueue(MockResponse().setResponseCode(500))

        repo.signOut()

        assertNull(store.current())
    }

    @Test
    fun signOutUnregistersThePushDevice() = runTest {
        store.save(Session("a", "r"))
        server.enqueue(MockResponse().setResponseCode(204))
        repo.signOut()
        assertEquals(1, devices.unregisterCalls)
    }

    @Test
    fun signOutStillClearsTheSessionWhenPushCleanupFails() = runTest {
        store.save(Session("a", "r"))
        devices.failUnregister = true
        server.enqueue(MockResponse().setResponseCode(204))
        repo.signOut()
        assertNull(store.current())
    }

    private class TestDispatchers(d: CoroutineDispatcher) : DispatcherProvider {
        override val main = d
        override val io = d
        override val default = d
    }
}
