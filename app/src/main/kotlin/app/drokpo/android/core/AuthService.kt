package app.drokpo.android.core

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import app.drokpo.android.BuildConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Port of the iOS `AuthServiceError` (+ the Android-only [Cancelled]). */
sealed class AuthServiceError(message: String) : Exception(message) {
    class MissingToken : AuthServiceError("Sign-in didn't return a valid token. Please try again.")
    class NoPresenter : AuthServiceError("Couldn't present the sign-in screen.")

    /**
     * User backed out of the Google/Apple UI. Callers ignore it silently (no
     * alert) — iOS swallows `ASAuthorizationError.canceled` the same way.
     */
    class Cancelled : AuthServiceError("Sign-in was cancelled.")

    /**
     * Android-only: Credential Manager found no Google account to offer (none on the
     * device, or Google Play services can't provide one). iOS's GIDSignIn always falls back
     * to a web sign-in, so it has no equivalent error.
     */
    class NoGoogleAccount : AuthServiceError(
        "No Google account is available on this device. Add one in Settings, or continue with phone.",
    )
}

typealias PhoneResendToken = PhoneAuthProvider.ForceResendingToken

sealed interface PhoneVerification {
    /** SMS sent — show the code step. Keep `resendToken` for "Resend code". */
    data class CodeSent(val verificationId: String, val resendToken: PhoneResendToken?) : PhoneVerification

    /** Android instant verification / SMS auto-retrieval already signed the user in. */
    data object AutoVerified : PhoneVerification
}

/**
 * Sign-in providers, all ending in `FirebaseAuth.signInWithCredential` —
 * SessionStore's auth-state listener takes over from there (routing,
 * account fetch), so none of these return anything.
 */
object AuthService {
    private const val TAG = "AuthService"

    /** Firebase's web-flow cancellation (user closed the Custom Tab). */
    private const val ERROR_WEB_CONTEXT_CANCELED = "ERROR_WEB_CONTEXT_CANCELED"

    /** BuildConfig.APPLE_SIGN_IN_ENABLED — SignInScreen hides the Apple button when false. */
    val isAppleSignInEnabled: Boolean = BuildConfig.APPLE_SIGN_IN_ENABLED

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    // region Google

    /**
     * Credential Manager's "Sign in with Google" sheet → Google ID token →
     * GoogleAuthProvider credential → Firebase. The iOS counterpart is
     * GIDSignIn presented from the key window's root view controller.
     */
    suspend fun signInWithGoogle(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) throw AuthServiceError.NoPresenter()
        // Only exists when google-services.json was present at build time —
        // never reference it as R.string or the json-less build breaks.
        val serverClientId = webClientId(activity) ?: throw AuthServiceError.NoPresenter()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(serverClientId).build())
            .build()
        val credential = try {
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (e: GetCredentialCancellationException) {
            throw AuthServiceError.Cancelled()
        } catch (e: NoCredentialException) {
            throw AuthServiceError.NoGoogleAccount()
        }

        val isGoogleIdToken = credential is CustomCredential && (
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL
            )
        if (!isGoogleIdToken) throw AuthServiceError.MissingToken()
        val idToken = try {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (e: GoogleIdTokenParsingException) {
            throw AuthServiceError.MissingToken()
        }
        if (idToken.isBlank()) throw AuthServiceError.MissingToken()

        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    /**
     * `default_web_client_id`, generated by the google-services plugin from the json's web OAuth
     * client. Looked up by name on purpose (lint DiscouragedApi): the resource exists only when
     * google-services.json does, so an `R.string` reference would break the json-less build.
     * The release resource shrinker can't see by-name lookups: `res/raw/drokpo_keep.xml` keeps it.
     */
    @SuppressLint("DiscouragedApi")
    private fun webClientId(context: Context): String? {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        if (id == 0) return null
        return context.getString(id).takeIf { it.isNotBlank() }
    }

    /**
     * Sign-out half of Credential Manager: forget the selected Google account
     * so the next sign-in shows the account picker again (iOS's GIDSignIn
     * state is cleared by Firebase sign-out alone). Never throws.
     */
    internal suspend fun clearCredentialState(context: Context) {
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "clearCredentialState failed", e)
        }
    }

    // endregion

    // region Apple

    /**
     * Sign in with Apple through Firebase's web OAuth flow (Android has no
     * native Apple sheet). Requests the name scope only, like iOS's
     * `requestedScopes = [.fullName]`. Firebase generates and verifies the
     * nonce itself here, so iOS's nonce helpers have no counterpart.
     *
     * Only called when [isAppleSignInEnabled]: the flow needs an Apple
     * Services ID configured on the Firebase apple.com provider.
     */
    suspend fun signInWithApple(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) throw AuthServiceError.NoPresenter()
        val provider = OAuthProvider.newBuilder("apple.com")
            .setScopes(listOf("name"))
            .build()
        try {
            // The Custom Tab round trip can outlive the activity (process
            // death); Firebase then hands the result back as a pending result.
            val pending = auth.pendingAuthResult
            if (pending != null) {
                pending.await()
            } else {
                auth.startActivityForSignInWithProvider(activity, provider).await()
            }
        } catch (e: FirebaseAuthException) {
            if (e.errorCode == ERROR_WEB_CONTEXT_CANCELED) throw AuthServiceError.Cancelled()
            throw e
        }
    }

    // endregion

    // region Phone

    /**
     * Kicks off SMS verification for an E.164 number (e.g. "+9779812345678").
     * Resumes on the first of:
     * - `onCodeSent` → [PhoneVerification.CodeSent] (pass [resendToken] back
     *   for "Resend code");
     * - `onVerificationCompleted` (instant verification / SMS auto-retrieval)
     *   → signs in, then [PhoneVerification.AutoVerified];
     * - `onVerificationFailed` → throws that FirebaseException.
     *
     * If auto-retrieval completes *after* CodeSent was returned, the service
     * signs in by itself — SessionStore's listener then routes away from the
     * sign-in screen. Firebase falls back to a reCAPTCHA Custom Tab when Play
     * Integrity can't vouch for the device (iOS: the reCAPTCHA web view via
     * the app's URL scheme); [activity] hosts that.
     */
    suspend fun startPhoneVerification(
        activity: Activity,
        e164: String,
        resendToken: PhoneResendToken? = null,
    ): PhoneVerification = suspendCancellableCoroutine { continuation ->
        var codeSentDelivered = false

        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                when {
                    continuation.isActive -> AppGraph.appScope.launch {
                        try {
                            auth.signInWithCredential(credential).await()
                            if (continuation.isActive) continuation.resume(PhoneVerification.AutoVerified)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                    // Code step is showing; the auto-retrieved SMS beats the
                    // user to it. A failure here is harmless — they can still
                    // type the code.
                    codeSentDelivered -> AppGraph.appScope.launch {
                        try {
                            auth.signInWithCredential(credential).await()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Auto-retrieved phone sign-in failed", e)
                        }
                    }
                    // The caller gave up (screen dismissed) — don't sign in behind its back.
                    else -> Unit
                }
            }

            override fun onVerificationFailed(e: FirebaseException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) {
                if (continuation.isActive) {
                    codeSentDelivered = true
                    continuation.resume(PhoneVerification.CodeSent(verificationId, token))
                }
            }
        }

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(e164)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
            .apply { if (resendToken != null) setForceResendingToken(resendToken) }
            .build()
        try {
            PhoneAuthProvider.verifyPhoneNumber(options)
        } catch (e: Exception) {
            // e.g. IllegalArgumentException for an empty number.
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    }

    suspend fun signInWithPhone(verificationId: String, code: String) {
        val credential = PhoneAuthProvider.getCredential(verificationId, code)
        auth.signInWithCredential(credential).await()
    }

    // endregion
}
