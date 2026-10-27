package dev.agenthub.app.settings

import dev.agenthub.app.auth.data.AuthRepository
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.ui.theme.ThemeMode
import dev.agenthub.app.settings.data.AccountRepository
import dev.agenthub.app.settings.data.SettingsStore
import dev.agenthub.app.settings.data.ThemeCodec
import dev.agenthub.app.settings.ui.ConfirmAction
import dev.agenthub.app.settings.ui.LockGateState
import dev.agenthub.app.settings.ui.LockGateViewModel
import dev.agenthub.app.settings.ui.LockPhase
import dev.agenthub.app.settings.ui.SettingsEffect
import dev.agenthub.app.settings.ui.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

class FakeSettingsStore(theme: ThemeMode = ThemeMode.System, lock: Boolean = false) : SettingsStore {
    val theme = MutableStateFlow(theme)
    val lock = MutableStateFlow(lock)
    val notify = MutableStateFlow(true)
    var cleared = false
    override val themeMode: Flow<ThemeMode> = this.theme
    override val biometricLock: Flow<Boolean> = this.lock
    override val approvalNotifications: Flow<Boolean> = notify
    override suspend fun setThemeMode(mode: ThemeMode) { theme.value = mode }
    override suspend fun setBiometricLock(enabled: Boolean) { lock.value = enabled }
    override suspend fun setApprovalNotifications(enabled: Boolean) { notify.value = enabled }
    override suspend fun clear() { cleared = true }
}

class FakeAccountRepository : AccountRepository {
    var killResult: AppResult<Unit> = AppResult.Success(Unit)
    var exportResult: AppResult<String?> = AppResult.Success("https://files.example/export.zip")
    var deleteResult: AppResult<Unit> = AppResult.Success(Unit)
    var killCalls = 0
    var deleteCalls = 0
    override suspend fun killSwitch(): AppResult<Unit> { killCalls++; return killResult }
    override suspend fun requestExport() = exportResult
    override suspend fun deleteAccount(): AppResult<Unit> { deleteCalls++; return deleteResult }
}

class FakeAuthRepository : AuthRepository {
    var signOuts = 0
    override suspend fun signInWithGoogle(idToken: String, nonce: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun signOut() { signOuts++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsTests {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val store = FakeSettingsStore()
    private val account = FakeAccountRepository()
    private val auth = FakeAuthRepository()
    private fun vm() = SettingsViewModel(store, account, auth)

    @Test
    fun themeChoiceIsSavedAndReflected() {
        val v = vm()
        v.onTheme(ThemeMode.Dark)
        assertEquals(ThemeMode.Dark, store.theme.value)
        assertEquals(ThemeMode.Dark, v.uiState.value.themeMode)
    }

    @Test
    fun themeCodecRoundTripsAndFallsBackToSystem() {
        ThemeMode.entries.forEach { assertEquals(it, ThemeCodec.decode(ThemeCodec.encode(it))) }
        assertEquals(ThemeMode.System, ThemeCodec.decode(null))
        assertEquals(ThemeMode.System, ThemeCodec.decode("neon"))
    }

    @Test
    fun notificationAndBiometricPreferencesArePersisted() {
        val v = vm()
        v.onNotifications(false)
        v.onBiometricLock(true)
        assertFalse(store.notify.value)
        assertTrue(store.lock.value)
        assertTrue(v.uiState.value.biometricLock)
    }

    @Test
    fun killSwitchNeedsConfirmationThenPausesEverything() = runTest {
        val v = vm()
        v.onConfirmRequest(ConfirmAction.KillSwitch)
        assertEquals(0, account.killCalls)
        v.onConfirm()
        assertEquals(1, account.killCalls)
        assertEquals(SettingsEffect.KillSwitchDone, v.effects.first())
        assertNull(v.uiState.value.confirming)
    }

    @Test
    fun dismissingAConfirmationDoesNothing() {
        val v = vm()
        v.onConfirmRequest(ConfirmAction.DeleteAccount)
        v.onConfirmDismiss()
        v.onConfirm()
        assertEquals(0, account.deleteCalls)
    }

    @Test
    fun killSwitchFailureIsReported() {
        account.killResult = AppResult.Failure(DataError.Network)
        val v = vm()
        v.onConfirmRequest(ConfirmAction.KillSwitch)
        v.onConfirm()
        assertEquals(DataError.Network, v.uiState.value.error)
        assertFalse(v.uiState.value.isWorking)
    }

    @Test
    fun signOutAfterConfirmationSignsOut() {
        val v = vm()
        v.onConfirmRequest(ConfirmAction.SignOut)
        v.onConfirm()
        assertEquals(1, auth.signOuts)
    }

    @Test
    fun deletingTheAccountAlsoSignsOutLocallyButOnlyOnSuccess() {
        val v = vm()
        v.onConfirmRequest(ConfirmAction.DeleteAccount)
        v.onConfirm()
        assertEquals(1, account.deleteCalls)
        assertEquals(1, auth.signOuts)

        account.deleteResult = AppResult.Failure(DataError.Network)
        v.onConfirmRequest(ConfirmAction.DeleteAccount)
        v.onConfirm()
        assertEquals("a failed deletion must keep the user signed in", 1, auth.signOuts)
        assertEquals(DataError.Network, v.uiState.value.error)
    }

    @Test
    fun exportOpensOnlyTheLinkTheServerGave() = runTest {
        val v = vm()
        v.onExport()
        assertEquals(SettingsEffect.OpenExport("https://files.example/export.zip"), v.effects.first())
    }

    @Test
    fun exportFailureIsReported() {
        account.exportResult = AppResult.Failure(DataError.Network)
        val v = vm()
        v.onExport()
        assertEquals(DataError.Network, v.uiState.value.error)
    }

    // ---- lock gate ----

    @Test
    fun theAppStaysCoveredUntilTheLockSettingIsKnown() {
        assertEquals(LockPhase.Loading, LockGateState().phase)
    }

    @Test
    fun lockOffMeansOpen() {
        assertEquals(LockPhase.Open, LockGateViewModel(FakeSettingsStore(lock = false)).state.value.phase)
    }

    @Test
    fun lockOnStartsLockedAndUnlocksOnce() {
        val gate = LockGateViewModel(FakeSettingsStore(lock = true))
        assertEquals(LockPhase.Locked, gate.state.value.phase)
        gate.onUnlocked()
        assertEquals(LockPhase.Open, gate.state.value.phase)
    }

    @Test
    fun goingToTheBackgroundLocksAgain() {
        val gate = LockGateViewModel(FakeSettingsStore(lock = true))
        gate.onUnlocked()
        gate.onBackgrounded()
        assertEquals(LockPhase.Locked, gate.state.value.phase)
    }

    @Test
    fun turningTheLockOnInSettingsDoesNotLockTheCurrentSession() {
        val settings = FakeSettingsStore(lock = false)
        val gate = LockGateViewModel(settings)
        settings.lock.value = true
        assertEquals(LockPhase.Open, gate.state.value.phase)
        gate.onBackgrounded()
        assertEquals(LockPhase.Locked, gate.state.value.phase)
    }

    @Test
    fun theGateFollowsTheThemeSetting() {
        val settings = FakeSettingsStore(theme = ThemeMode.Light)
        val gate = LockGateViewModel(settings)
        assertEquals(ThemeMode.Light, gate.state.value.themeMode)
        settings.theme.value = ThemeMode.Dark
        assertEquals(ThemeMode.Dark, gate.state.value.themeMode)
    }
}
