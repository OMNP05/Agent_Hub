package dev.agenthub.app.settings.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Local screen lock only. It never replaces server authentication (architecture 1.2): the session
 * and its tokens are unchanged, the app is simply covered until the device owner is verified.
 */
object AppLock {
    private const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    fun isAvailable(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun authenticate(activity: FragmentActivity, title: String, onSuccess: () -> Unit, onFailure: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFailure()
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(AUTHENTICATORS).build(),
        )
    }
}
