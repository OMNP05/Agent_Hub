package dev.agenthub.app.auth.data

import dev.agenthub.app.core.coroutines.DispatcherProvider
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.network.ApiErrorParser
import dev.agenthub.app.core.session.Session
import dev.agenthub.app.core.session.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

interface AuthRepository {
    suspend fun signInWithGoogle(idToken: String, nonce: String): AppResult<Unit>
    suspend fun signOut()
}

@Singleton
class DefaultAuthRepository @Inject constructor(
    private val api: AuthApi,
    private val store: SessionStore,
    private val dispatchers: DispatcherProvider,
    private val devices: dev.agenthub.app.push.DeviceRepository,
) : AuthRepository {

    override suspend fun signInWithGoogle(idToken: String, nonce: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                val response = api.signInWithGoogle(GoogleSignInRequest(idToken, nonce))
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    store.save(Session(body.accessToken, body.refreshToken))
                    AppResult.Success(Unit)
                } else {
                    AppResult.Failure(ApiErrorParser.toError(response.code(), response.errorBody()?.string()))
                }
            } catch (e: IOException) {
                AppResult.Failure(DataError.Network)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.Failure(DataError.Unknown)
            }
        }

    override suspend fun signOut() {
        withContext(dispatchers.io) {
            // A signed-out device must stop receiving approvals. Needs the session, so it goes first; best effort.
            try {
                devices.unregister()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // never block sign-out on push cleanup
            }
            // Revoking server-side is best effort; the local session is always cleared.
            val refresh = store.current()?.refreshToken
            if (refresh != null) {
                try {
                    api.logout(RefreshRequest(refresh))
                } catch (e: IOException) {
                    // offline: nothing more to do
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // ignore; sign-out must not fail
                }
            }
            store.clear()
        }
    }
}
