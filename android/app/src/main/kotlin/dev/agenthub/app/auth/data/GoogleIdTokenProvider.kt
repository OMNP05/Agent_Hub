package dev.agenthub.app.auth.data

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import dev.agenthub.app.BuildConfig
import dev.agenthub.app.core.error.DataError
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject

sealed interface IdTokenResult {
    data class Token(val idToken: String, val nonce: String) : IdTokenResult
    data object Cancelled : IdTokenResult
    data class Failed(val error: DataError) : IdTokenResult
}

interface GoogleIdTokenProvider {
    suspend fun request(context: Context): IdTokenResult
}

object NonceGenerator {
    fun generate(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/** Credential Manager "Sign in with Google". Needs an Activity context so the account picker can show. */
class CredentialManagerGoogleIdTokenProvider @Inject constructor(
    private val hooks: java.util.Optional<dev.agenthub.app.core.network.DebugHooks>,
) : GoogleIdTokenProvider {

    override suspend fun request(context: Context): IdTokenResult {
        // Only a debug build can bind hooks; release always uses the real Google sign-in below.
        hooks.orElse(null)?.idTokens()?.let { return it.request(context) }
        val webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        if (webClientId.isBlank()) return IdTokenResult.Failed(DataError.SignInNotConfigured)

        val nonce = NonceGenerator.generate()
        val option = GetSignInWithGoogleOption.Builder(webClientId).setNonce(nonce).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        return try {
            val credential = CredentialManager.create(context).getCredential(context, request).credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                IdTokenResult.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken, nonce)
            } else {
                IdTokenResult.Failed(DataError.Unknown)
            }
        } catch (e: GetCredentialCancellationException) {
            IdTokenResult.Cancelled
        } catch (e: NoCredentialException) {
            IdTokenResult.Failed(DataError.NoGoogleAccount)
        } catch (e: GetCredentialException) {
            IdTokenResult.Failed(DataError.Unknown)
        } catch (e: GoogleIdTokenParsingException) {
            IdTokenResult.Failed(DataError.Unknown)
        }
    }
}
