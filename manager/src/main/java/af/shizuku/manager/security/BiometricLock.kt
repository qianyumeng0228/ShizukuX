package af.shizuku.manager.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import af.shizuku.manager.R

class BiometricLock(private val activity: FragmentActivity) {

    private val allowedAuthenticators =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun authenticate(onSuccess: () -> Unit, onError: (Int) -> Unit) {
        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errorCode)
                }
            })

        // setNegativeButtonText can't be combined with DEVICE_CREDENTIAL in
        // setAllowedAuthenticators (the platform provides its own cancel affordance). Allowing
        // DEVICE_CREDENTIAL also fixes the actual bug, not just the button label: with only
        // BIOMETRIC_STRONG, a device with no biometric enrolled had no working fallback at all
        // (the "Use PIN/Pattern" button just canceled with an error, and its label was wrong for
        // password-locked devices anyway). The system now prompts for whatever credential type
        // is actually configured (password, PIN, or pattern) with the correct label.
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock ShizukuX")
            .setSubtitle("Authenticate to access sensitive settings")
            .setAllowedAuthenticators(allowedAuthenticators)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    fun canAuthenticate(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(allowedAuthenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }
}
