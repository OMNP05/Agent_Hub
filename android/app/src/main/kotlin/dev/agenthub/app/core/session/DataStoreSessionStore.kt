package dev.agenthub.app.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.security.GeneralSecurityException

/** Persists the session encrypted; a copy is kept in memory so OkHttp threads never wait on disk. */
class DataStoreSessionStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: AesGcmCipher,
) : SessionStore {

    private val cache = MutableStateFlow<Loaded>(Loaded.NotLoaded)

    override val session: Flow<Session?> = dataStore.data.map { decode(it) }

    override fun current(): Session? {
        (cache.value as? Loaded.Value)?.let { return it.session }
        val loaded = runBlocking { dataStore.data.first() }.let { decode(it) }
        cache.value = Loaded.Value(loaded)
        return loaded
    }

    override suspend fun save(session: Session) {
        dataStore.edit {
            it[ACCESS] = cipher.encrypt(session.accessToken)
            it[REFRESH] = cipher.encrypt(session.refreshToken)
        }
        cache.value = Loaded.Value(session)
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
        cache.value = Loaded.Value(null)
    }

    // Unreadable data (changed key, corruption) is treated as signed out rather than crashing.
    private fun decode(prefs: Preferences): Session? {
        val access = prefs[ACCESS] ?: return null
        val refresh = prefs[REFRESH] ?: return null
        return try {
            Session(cipher.decrypt(access), cipher.decrypt(refresh))
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    private sealed interface Loaded {
        data object NotLoaded : Loaded
        data class Value(val session: Session?) : Loaded
    }

    private companion object {
        val ACCESS = stringPreferencesKey("access_enc")
        val REFRESH = stringPreferencesKey("refresh_enc")
    }
}
