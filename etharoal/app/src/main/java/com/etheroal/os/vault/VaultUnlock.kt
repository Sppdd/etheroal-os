package com.etheroal.os.vault

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A see-through screen that shows the system's fingerprint / face / screen-lock prompt. It is a
 * separate activity so it can appear over whatever app the operator is using.
 */
class VaultUnlockActivity : Activity() {
    private val cancel = CancellationSignal()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val reason = intent.getStringExtra(EXTRA_REASON).orEmpty().take(120)
        val builder = BiometricPrompt.Builder(this)
            .setTitle("Unlock Etheroal's vault")
            .setSubtitle(reason.ifEmpty { "Etheroal wants to use a saved login" })
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION") builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(cancel, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = done(false)
        })
    }

    private fun done(ok: Boolean) {
        pending?.complete(ok)
        pending = null
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        pending?.complete(false)
        pending = null
        cancel.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_REASON = "reason"
        @Volatile private var pending: CompletableDeferred<Boolean>? = null

        /** Shows the unlock prompt; true when the owner unlocked. The vault key then works for 30 seconds. */
        suspend fun unlock(context: Context, reason: String): Boolean {
            pending?.complete(false)
            val waiter = CompletableDeferred<Boolean>().also { pending = it }
            context.startActivity(
                Intent(context, VaultUnlockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(EXTRA_REASON, reason),
            )
            return withTimeoutOrNull(90_000) { waiter.await() } ?: false
        }
    }
}
