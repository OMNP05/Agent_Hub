package dev.agenthub.app.settings.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.agenthub.app.core.coroutines.DispatcherProvider
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.map
import dev.agenthub.app.core.network.AgentHubApi
import dev.agenthub.app.core.network.apiUnitCall
import dev.agenthub.app.core.network.apiCall
import dev.agenthub.app.core.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Local, non-secret preferences. Nothing here is a token or key. */
interface SettingsStore {
    val themeMode: Flow<ThemeMode>
    val biometricLock: Flow<Boolean>
    val approvalNotifications: Flow<Boolean>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setBiometricLock(enabled: Boolean)
    suspend fun setApprovalNotifications(enabled: Boolean)
    suspend fun clear()
}

@Singleton
class DataStoreSettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsStore {
    private val data: Flow<Preferences> = context.settingsStore.data.catch { e ->
        // A corrupt or unreadable file falls back to defaults instead of crashing at launch.
        if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e
    }

    override val themeMode: Flow<ThemeMode> = data.map { ThemeCodec.decode(it[THEME]) }
    override val biometricLock: Flow<Boolean> = data.map { it[BIOMETRIC] ?: false }
    override val approvalNotifications: Flow<Boolean> = data.map { it[NOTIFY] ?: true }

    override suspend fun setThemeMode(mode: ThemeMode) { context.settingsStore.edit { it[THEME] = ThemeCodec.encode(mode) } }
    override suspend fun setBiometricLock(enabled: Boolean) { context.settingsStore.edit { it[BIOMETRIC] = enabled } }
    override suspend fun setApprovalNotifications(enabled: Boolean) { context.settingsStore.edit { it[NOTIFY] = enabled } }
    override suspend fun clear() { context.settingsStore.edit { it.clear() } }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val BIOMETRIC = booleanPreferencesKey("biometric_lock")
        val NOTIFY = booleanPreferencesKey("approval_notifications")
    }
}

object ThemeCodec {
    fun encode(mode: ThemeMode): String = mode.name.lowercase()
    fun decode(value: String?): ThemeMode = ThemeMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ThemeMode.System
}

interface AccountRepository {
    /** Pauses every agent at once; runs stop at their next Gateway call (architecture 1.6). */
    suspend fun killSwitch(): AppResult<Unit>

    /** Asks the server to prepare an export and returns the https link to open, if any. */
    suspend fun requestExport(): AppResult<String?>
    suspend fun deleteAccount(): AppResult<Unit>
}

@Singleton
class DefaultAccountRepository @Inject constructor(
    private val api: AgentHubApi,
    private val dispatchers: DispatcherProvider,
    private val database: dev.agenthub.app.core.database.AppDatabase,
    private val settings: SettingsStore,
) : AccountRepository {
    override suspend fun killSwitch(): AppResult<Unit> = withContext(dispatchers.io) { apiUnitCall { api.killSwitch() } }

    override suspend fun requestExport(): AppResult<String?> = withContext(dispatchers.io) {
        // Only an https link is ever offered to the browser.
        apiCall { api.exportData() }.map { it.url?.takeIf { url -> url.startsWith("https://") } }
    }

    override suspend fun deleteAccount(): AppResult<Unit> = withContext(dispatchers.io) {
        val result = apiUnitCall { api.deleteAccount() }
        if (result is AppResult.Success) {
            // The server removed everything; drop the local cache and preferences too.
            database.clearAllTables()
            settings.clear()
        }
        result
    }
}
