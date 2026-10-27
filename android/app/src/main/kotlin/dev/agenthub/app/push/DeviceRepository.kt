package dev.agenthub.app.push

import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dev.agenthub.app.core.coroutines.DispatcherProvider
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.network.AgentHubApi
import dev.agenthub.app.core.network.apiUnitCall
import dev.agenthub.app.core.network.dto.DeviceRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Source of this device's FCM token. Null when Firebase is not configured in this build. */
interface FcmTokenSource {
    suspend fun token(): String?
    suspend fun delete()
}

@Singleton
class FirebaseTokenSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : FcmTokenSource {

    // Without google-services.json there is no default FirebaseApp; push is then simply off.
    private fun available(): Boolean = FirebaseApp.getApps(context).isNotEmpty()

    override suspend fun token(): String? {
        if (!available()) return null
        return suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (cont.isActive) cont.resume(task.result.takeIf { task.isSuccessful })
            }
        }
    }

    override suspend fun delete() {
        if (!available()) return
        suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { if (cont.isActive) cont.resume(Unit) }
        }
    }
}

interface DeviceRepository {
    /** Registers this device for approval pushes. Safe to call repeatedly; does nothing if push is unavailable. */
    suspend fun register(): AppResult<Unit>

    /** Registers a token Firebase just issued. */
    suspend fun register(token: String): AppResult<Unit>

    /** Unregisters on sign-out so a signed-out device stops receiving approvals (architecture 1.2). Best effort. */
    suspend fun unregister()
}

@Singleton
class DefaultDeviceRepository @Inject constructor(
    private val api: AgentHubApi,
    private val tokens: FcmTokenSource,
    private val dispatchers: DispatcherProvider,
) : DeviceRepository {

    override suspend fun register(): AppResult<Unit> {
        val token = tokens.token() ?: return AppResult.Success(Unit)
        return register(token)
    }

    override suspend fun register(token: String): AppResult<Unit> = withContext(dispatchers.io) {
        apiUnitCall { api.registerDevice(DeviceRequest(token)) }
    }

    override suspend fun unregister() {
        withContext(dispatchers.io) {
            val token = tokens.token()
            if (token != null) apiUnitCall { api.unregisterDevice(DeviceRequest(token)) }
            tokens.delete()
        }
    }
}
