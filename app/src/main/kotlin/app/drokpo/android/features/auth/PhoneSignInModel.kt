package app.drokpo.android.features.auth

import android.app.Activity
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.drokpo.android.core.AuthService
import app.drokpo.android.core.AuthServiceError
import app.drokpo.android.core.PhoneResendToken
import app.drokpo.android.core.PhoneVerification
import app.drokpo.android.core.userMessage
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row of the country-code menu (iOS `private struct CountryCode`). */
@Immutable
internal data class CountryCode(val name: String, val dialCode: String) {
    val id: String get() = dialCode

    /** Menu row label: "India (+91)". */
    val menuLabel: String get() = "$name ($dialCode)"
}

/**
 * The diaspora's usual countries first; anything else goes through "Other…"
 * and a free-form dial code. India is the default (iOS `countryCodes[0]`).
 */
internal val countryCodes: List<CountryCode> = listOf(
    CountryCode("India", "+91"),
    CountryCode("Nepal", "+977"),
    CountryCode("Bhutan", "+975"),
    CountryCode("US / Canada", "+1"),
    CountryCode("Switzerland", "+41"),
    CountryCode("France", "+33"),
    CountryCode("Germany", "+49"),
    CountryCode("United Kingdom", "+44"),
    CountryCode("Australia", "+61"),
)

/** The two steps of the flow (iOS `PhoneSignInView.Step`). */
internal sealed interface PhoneSignInStep {
    data object EnterNumber : PhoneSignInStep

    /** SMS sent; [verificationId] is what `signInWithPhone` needs alongside the typed code. */
    data class EnterCode(val verificationId: String) : PhoneSignInStep
}

/** Everything PhoneSignInContent renders — the iOS view's `@State` vars plus its computed properties. */
@Immutable
internal data class PhoneSignInState(
    val step: PhoneSignInStep = PhoneSignInStep.EnterNumber,
    val countryCode: CountryCode = countryCodes[0],
    val useCustomCode: Boolean = false,
    val customDialCode: String = "",
    val phoneNumber: String = "",
    val code: String = "",
    val isWorking: Boolean = false,
    val errorMessage: String? = null,
    /** Seconds until "Resend code" is enabled again; 0 = enabled. */
    val resendCooldown: Int = 0,
    /** Signed in (code verified or auto-verified): the screen dismisses itself (iOS `dismiss()`). */
    val finished: Boolean = false,
) {
    val dialCode: String get() = if (useCustomCode) customDialCode else countryCode.dialCode

    val e164: String get() = dialCode + phoneNumber.filter(Char::isDigit)

    val canContinue: Boolean
        get() {
            if (isWorking) return false
            if (!dialCode.startsWith("+") || dialCode.length <= 1) return false
            return phoneNumber.count(Char::isDigit) >= 6
        }

    /** The menu chip: the selected dial code, or "Other" while a custom code is in use. */
    val countryChipLabel: String get() = if (useCustomCode) "Other" else countryCode.dialCode

    val resendLabel: String get() = if (resendCooldown > 0) "Resend in ${resendCooldown}s" else "Resend code"

    val canResend: Boolean get() = resendCooldown <= 0 && !isWorking

    fun selectingCountry(code: CountryCode): PhoneSignInState = copy(useCustomCode = false, countryCode = code)

    fun selectingOtherCountry(): PhoneSignInState = copy(useCustomCode = true)
}

/** iOS `.onChange(of: code)`: digits only, at most [OTP_LENGTH]. */
internal fun sanitizeOtp(raw: String): String = raw.filter(Char::isDigit).take(OTP_LENGTH)

internal const val OTP_LENGTH = 6

/** Seconds before "Resend code" can be tapped again; restarts on every send. */
internal const val RESEND_COOLDOWN_SECONDS = 30

/**
 * Firebase phone-auth failures → the copy iOS shows (`friendlyMessage(for:)`).
 * Android reports these as [FirebaseAuthException] error codes, except the SMS
 * quota, which arrives as a [FirebaseTooManyRequestsException].
 */
internal fun phoneAuthErrorMessage(error: Throwable): String {
    if (error is FirebaseTooManyRequestsException) return PhoneAuthMessages.QUOTA_EXCEEDED
    return when ((error as? FirebaseAuthException)?.errorCode) {
        "ERROR_INVALID_PHONE_NUMBER" -> PhoneAuthMessages.INVALID_PHONE_NUMBER
        "ERROR_MISSING_PHONE_NUMBER" -> PhoneAuthMessages.MISSING_PHONE_NUMBER
        "ERROR_QUOTA_EXCEEDED", "ERROR_TOO_MANY_REQUESTS" -> PhoneAuthMessages.QUOTA_EXCEEDED
        "ERROR_INVALID_VERIFICATION_CODE" -> PhoneAuthMessages.INVALID_VERIFICATION_CODE
        "ERROR_SESSION_EXPIRED", "ERROR_INVALID_VERIFICATION_ID" -> PhoneAuthMessages.CODE_EXPIRED
        else -> error.userMessage()
    }
}

/**
 * The part of an in-progress phone sign-in worth keeping across process death:
 * on Android the user usually switches to Messages to read the code, and a
 * low-memory device may kill the app meanwhile. Restoring the code step with
 * its verification id (and the resend token) spares a second SMS — Firebase's
 * Android guide asks for exactly this. The typed code and the cooldown are not
 * kept.
 */
internal data class PhoneSignInSnapshot(
    /** [CountryCode.id] of the selected menu row. */
    val countryDialCode: String,
    val useCustomCode: Boolean,
    val customDialCode: String,
    val phoneNumber: String,
    /** Non-null once the SMS was sent: restore straight onto the code step. */
    val verificationId: String?,
    val resendToken: PhoneResendToken?,
)

/**
 * Whether anyone is signed in to Firebase, live (the listener fires once right
 * away, then on every change). PhoneSignInModel's default source.
 */
internal fun firebaseSignedIn(): Flow<Boolean> = callbackFlow {
    val auth = FirebaseAuth.getInstance()
    val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser != null) }
    auth.addAuthStateListener(listener)
    awaitClose { auth.removeAuthStateListener(listener) }
}

internal object PhoneAuthMessages {
    const val INVALID_PHONE_NUMBER = "That doesn't look like a valid phone number."
    const val MISSING_PHONE_NUMBER = "Enter a phone number first."
    const val QUOTA_EXCEEDED = "Too many attempts right now — try again in a bit."
    const val INVALID_VERIFICATION_CODE = "That code isn't right. Check and try again."
    const val CODE_EXPIRED = "This code expired — request a new one."
}

/**
 * State holder for PhoneSignInScreen (port of PhoneSignInView's `@State` and
 * its `sendCode` / `verifyCode` / `startResendCooldown`).
 *
 * Firebase Phone Auth sign-in: country code + number, then a 6-digit SMS
 * code. A sibling to Google/Apple on SignInScreen — success routes through
 * the same SessionStore auth listener as any other provider.
 *
 * Lives in the FullScreenCover's presentation scope, so dismissing the screen
 * clears it — which cancels the resend countdown (iOS `.onDisappear {
 * cooldownTask?.cancel() }`) and any verification still waiting for Firebase.
 * The Activity is passed per call (reCAPTCHA / Play Integrity host) and never
 * stored.
 *
 * Android-only: SMS auto-retrieval can sign the user in by itself after the
 * code step is already showing (AuthService does that on the app scope). The
 * first signed-in value from [signedIn] therefore finishes the flow too, and
 * from then on nothing is sent or verified — otherwise a code typed while the
 * session is still routing would hit the consumed verification and flash
 * "This code expired" over a signed-in user.
 */
internal class PhoneSignInModel(
    private val startVerification: suspend (
        activity: Activity,
        e164: String,
        resendToken: PhoneResendToken?,
    ) -> PhoneVerification = AuthService::startPhoneVerification,
    private val signInWithPhone: suspend (verificationId: String, code: String) -> Unit = AuthService::signInWithPhone,
    private val friendlyMessage: (Throwable) -> String = ::phoneAuthErrorMessage,
    signedIn: Flow<Boolean> = firebaseSignedIn(),
) : ViewModel() {
    private val _state = MutableStateFlow(PhoneSignInState())
    val state: StateFlow<PhoneSignInState> = _state.asStateFlow()

    /** From the last `CodeSent`; "Resend code" hands it back so Firebase skips re-verifying the app. */
    private var resendToken: PhoneResendToken? = null
    private var cooldownJob: Job? = null

    init {
        viewModelScope.launch {
            val didSignIn = try {
                signedIn.first { it }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No auth source (or it ended without a sign-in): the typed
                // code and AutoVerified still finish the flow.
                false
            }
            if (didSignIn) _state.update { it.copy(finished = true, errorMessage = null) }
        }
    }

    /** What to persist for process death; see [PhoneSignInSnapshot]. */
    fun snapshot(): PhoneSignInSnapshot {
        val state = _state.value
        return PhoneSignInSnapshot(
            countryDialCode = state.countryCode.id,
            useCustomCode = state.useCustomCode,
            customDialCode = state.customDialCode,
            phoneNumber = state.phoneNumber,
            verificationId = (state.step as? PhoneSignInStep.EnterCode)?.verificationId,
            resendToken = resendToken,
        )
    }

    /**
     * Seeds a freshly created model from a [snapshot] taken before process
     * death. The cooldown starts at 0, so "Resend code" works right away.
     */
    fun restore(snapshot: PhoneSignInSnapshot) {
        resendToken = snapshot.resendToken
        _state.update {
            it.copy(
                step = snapshot.verificationId?.let(PhoneSignInStep::EnterCode) ?: PhoneSignInStep.EnterNumber,
                countryCode = countryCodes.firstOrNull { code -> code.id == snapshot.countryDialCode } ?: countryCodes[0],
                useCustomCode = snapshot.useCustomCode,
                customDialCode = snapshot.customDialCode,
                phoneNumber = snapshot.phoneNumber,
                code = "",
                resendCooldown = 0,
            )
        }
    }

    fun selectCountry(code: CountryCode) = _state.update { it.selectingCountry(code) }

    fun selectOtherCountry() = _state.update { it.selectingOtherCountry() }

    fun updateCustomDialCode(value: String) = _state.update { it.copy(customDialCode = value) }

    fun updatePhoneNumber(value: String) = _state.update { it.copy(phoneNumber = value) }

    fun dismissError() = _state.update { it.copy(errorMessage = null) }

    /**
     * "Continue" on the number step and "Resend code" on the code step. A
     * successful send moves to (or stays on) the code step with a cleared
     * code and restarts the 30 s cooldown.
     */
    fun sendCode(activity: Activity?) {
        // Buttons are disabled while working; this catches a double tap that
        // lands before recomposition.
        if (_state.value.isWorking || _state.value.finished) return
        val e164 = _state.value.e164
        _state.update { it.copy(isWorking = true) }
        viewModelScope.launch {
            try {
                // iOS has no equivalent: Android's verification needs an
                // Activity to host the reCAPTCHA fallback.
                if (activity == null) throw AuthServiceError.NoPresenter()
                when (val result = startVerification(activity, e164, resendToken)) {
                    is PhoneVerification.CodeSent -> {
                        resendToken = result.resendToken
                        _state.update {
                            it.copy(step = PhoneSignInStep.EnterCode(result.verificationId), code = "")
                        }
                        startResendCooldown()
                    }
                    // Instant verification / SMS auto-retrieval already signed
                    // in; SessionStore's auth listener takes over from here.
                    PhoneVerification.AutoVerified -> _state.update { it.copy(finished = true) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Already signed in another way (auto-retrieval): nothing to report.
                _state.update { if (it.finished) it else it.copy(errorMessage = friendlyMessage(e)) }
            } finally {
                _state.update { it.copy(isWorking = false) }
            }
        }
    }

    /** The code field: digits only, max 6; the sixth digit submits by itself. */
    fun updateCode(raw: String) {
        val sanitized = sanitizeOtp(raw)
        if (sanitized == _state.value.code) return
        _state.update { it.copy(code = sanitized) }
        if (sanitized.length == OTP_LENGTH) verifyCode()
    }

    private fun verifyCode() {
        val step = _state.value.step as? PhoneSignInStep.EnterCode ?: return
        if (_state.value.isWorking || _state.value.finished) return
        val code = _state.value.code
        _state.update { it.copy(isWorking = true) }
        viewModelScope.launch {
            try {
                signInWithPhone(step.verificationId, code)
                // SessionStore's auth listener takes over from here.
                _state.update { it.copy(finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Already signed in another way (auto-retrieval): nothing to report.
                _state.update { if (it.finished) it else it.copy(errorMessage = friendlyMessage(e)) }
            } finally {
                _state.update { it.copy(isWorking = false) }
            }
        }
    }

    private fun startResendCooldown() {
        cooldownJob?.cancel()
        _state.update { it.copy(resendCooldown = RESEND_COOLDOWN_SECONDS) }
        cooldownJob = viewModelScope.launch {
            while (_state.value.resendCooldown > 0) {
                delay(1_000)
                _state.update { it.copy(resendCooldown = it.resendCooldown - 1) }
            }
        }
    }
}
