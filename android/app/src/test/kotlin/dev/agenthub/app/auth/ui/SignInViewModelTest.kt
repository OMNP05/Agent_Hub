package dev.agenthub.app.auth.ui

import android.content.Context
import android.content.ContextWrapper
import dev.agenthub.app.auth.data.AuthRepository
import dev.agenthub.app.auth.data.GoogleIdTokenProvider
import dev.agenthub.app.auth.data.IdTokenResult
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val context: Context = ContextWrapper(null)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeTokens(var result: IdTokenResult) : GoogleIdTokenProvider {
        override suspend fun request(context: Context) = result
    }

    private class FakeAuth(var result: AppResult<Unit> = AppResult.Success(Unit)) : AuthRepository {
        var calls = 0
        var lastNonce: String? = null
        override suspend fun signInWithGoogle(idToken: String, nonce: String): AppResult<Unit> {
            calls++; lastNonce = nonce; return result
        }
        override suspend fun signOut() {}
    }

    @Test
    fun successPassesTokenAndNonceToBackendAndStopsLoading() = runTest(dispatcher) {
        val auth = FakeAuth()
        val vm = SignInViewModel(FakeTokens(IdTokenResult.Token("tok", "n1")), auth)

        vm.onSignInClick(context)
        assertTrue(vm.uiState.value.isLoading)
        advanceUntilIdle()

        assertEquals(1, auth.calls)
        assertEquals("n1", auth.lastNonce)
        assertFalse(vm.uiState.value.isLoading)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun cancelledPickerIsNotAnError() = runTest(dispatcher) {
        val auth = FakeAuth()
        val vm = SignInViewModel(FakeTokens(IdTokenResult.Cancelled), auth)
        vm.onSignInClick(context)
        advanceUntilIdle()
        assertEquals(SignInUiState(), vm.uiState.value)
        assertEquals(0, auth.calls)
    }

    @Test
    fun backendFailureShowsErrorAndClearsLoading() = runTest(dispatcher) {
        val vm = SignInViewModel(
            FakeTokens(IdTokenResult.Token("tok", "n")),
            FakeAuth(AppResult.Failure(DataError.Network)),
        )
        vm.onSignInClick(context)
        advanceUntilIdle()
        assertEquals(SignInUiState(isLoading = false, error = DataError.Network), vm.uiState.value)

        vm.onErrorShown()
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun notConfiguredIsReported() = runTest(dispatcher) {
        val vm = SignInViewModel(FakeTokens(IdTokenResult.Failed(DataError.SignInNotConfigured)), FakeAuth())
        vm.onSignInClick(context)
        advanceUntilIdle()
        assertEquals(DataError.SignInNotConfigured, vm.uiState.value.error)
    }

    @Test
    fun secondTapWhileLoadingIsIgnored() = runTest(dispatcher) {
        val auth = FakeAuth()
        val vm = SignInViewModel(FakeTokens(IdTokenResult.Token("tok", "n")), auth)
        vm.onSignInClick(context)
        vm.onSignInClick(context)
        advanceUntilIdle()
        assertEquals(1, auth.calls)
    }
}
