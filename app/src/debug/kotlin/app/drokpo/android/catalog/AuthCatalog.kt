package app.drokpo.android.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.drokpo.android.core.AuthServiceError
import app.drokpo.android.features.auth.AccountTypeChoiceContent
import app.drokpo.android.features.auth.OTP_LENGTH
import app.drokpo.android.features.auth.PhoneAuthMessages
import app.drokpo.android.features.auth.PhoneSignInContent
import app.drokpo.android.features.auth.PhoneSignInState
import app.drokpo.android.features.auth.PhoneSignInStep
import app.drokpo.android.features.auth.RESEND_COOLDOWN_SECONDS
import app.drokpo.android.features.auth.SignInContent
import app.drokpo.android.features.auth.SignInUiState
import app.drokpo.android.features.auth.countryCodes
import app.drokpo.android.features.auth.sanitizeOtp
import app.drokpo.android.ui.components.FullScreenCover
import app.drokpo.android.ui.components.PlainTextButton
import app.drokpo.android.ui.theme.DrokpoTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Group 1 (auth). Every entry renders the stateless *Content composables with local fixture
// state — never SignInScreen / PhoneSignInScreen / AccountTypeChoiceScreen, which reach
// AuthService and AppGraph.session. The two `.interactive` entries fake the providers with
// delays so the flows (spinner, alerts, code step, resend countdown) can be clicked through.

/** A code step as the model leaves it right after "Continue" succeeds. */
private val codeStep = PhoneSignInState(
    step = PhoneSignInStep.EnterCode(verificationId = "fixture-verification"),
    phoneNumber = "98765 43210",
    resendCooldown = RESEND_COOLDOWN_SECONDS,
)

val authCatalogEntries: List<CatalogEntry> = listOf(
    // region Sign in
    CatalogEntry("auth.signin", "Sign in — Google + phone (Apple flag off)") {
        SignInPreview(SignInUiState(), showApple = false)
    },
    CatalogEntry("auth.signin.apple", "Sign in — with Apple button") {
        SignInPreview(SignInUiState(), showApple = true)
    },
    CatalogEntry("auth.signin.loading", "Sign in — signing in (disabled + spinner)") {
        SignInPreview(SignInUiState(isSigningIn = true), showApple = false)
    },
    CatalogEntry("auth.signin.loading.apple", "Sign in — signing in, with Apple") {
        SignInPreview(SignInUiState(isSigningIn = true), showApple = true)
    },
    CatalogEntry("auth.signin.error", "Sign in — \"Sign-in failed\" (no Google account)") {
        SignInPreview(SignInUiState(errorMessage = AuthServiceError.NoGoogleAccount().message), showApple = false)
    },
    CatalogEntry("auth.signin.error.offline", "Sign in — \"Sign-in failed\" (offline)") {
        SignInPreview(
            SignInUiState(errorMessage = "The Internet connection appears to be offline."),
            showApple = true,
        )
    },
    CatalogEntry("auth.signin.interactive", "Sign in — interactive (fake providers, phone cover)") {
        InteractiveSignIn()
    },
    // endregion

    // region Phone sign-in: number step
    CatalogEntry("auth.phone.number", "Phone — number step, empty (India default)") {
        PhonePreview(PhoneSignInState())
    },
    CatalogEntry("auth.phone.number.filled", "Phone — number typed, Continue enabled") {
        PhonePreview(PhoneSignInState(phoneNumber = "98765 43210"))
    },
    CatalogEntry("auth.phone.number.short", "Phone — too short, Continue disabled") {
        PhonePreview(PhoneSignInState(countryCode = countryCodes[1], phoneNumber = "9812"))
    },
    CatalogEntry("auth.phone.number.menu", "Phone — country menu open") {
        PhonePreview(PhoneSignInState(), menuExpanded = true)
    },
    CatalogEntry("auth.phone.number.other", "Phone — Other…, custom +xx field") {
        PhonePreview(PhoneSignInState(useCustomCode = true, customDialCode = "+31", phoneNumber = "612345678"))
    },
    CatalogEntry("auth.phone.number.other.empty", "Phone — Other…, empty dial code") {
        PhonePreview(PhoneSignInState(useCustomCode = true))
    },
    CatalogEntry("auth.phone.number.sending", "Phone — sending code (spinner)") {
        PhonePreview(PhoneSignInState(phoneNumber = "98765 43210", isWorking = true))
    },
    // endregion

    // region Phone sign-in: code step
    CatalogEntry("auth.phone.code", "Phone — code step, \"Resend in 30s\"") {
        PhonePreview(codeStep)
    },
    CatalogEntry("auth.phone.code.partial", "Phone — code partly typed") {
        PhonePreview(codeStep.copy(code = "482", resendCooldown = 12))
    },
    CatalogEntry("auth.phone.code.resend", "Phone — cooldown over, \"Resend code\"") {
        PhonePreview(codeStep.copy(resendCooldown = 0))
    },
    CatalogEntry("auth.phone.code.verifying", "Phone — verifying 6 digits (spinner)") {
        PhonePreview(codeStep.copy(code = "482913", isWorking = true, resendCooldown = 21))
    },
    // endregion

    // region Phone sign-in: errors ("Couldn't sign in")
    CatalogEntry("auth.phone.error.invalidnumber", "Phone — error: invalid number") {
        PhonePreview(
            PhoneSignInState(phoneNumber = "123456", errorMessage = PhoneAuthMessages.INVALID_PHONE_NUMBER),
        )
    },
    CatalogEntry("auth.phone.error.quota", "Phone — error: too many attempts") {
        PhonePreview(
            PhoneSignInState(phoneNumber = "98765 43210", errorMessage = PhoneAuthMessages.QUOTA_EXCEEDED),
        )
    },
    CatalogEntry("auth.phone.error.wrongcode", "Phone — error: wrong code") {
        PhonePreview(
            codeStep.copy(code = "111111", resendCooldown = 17, errorMessage = PhoneAuthMessages.INVALID_VERIFICATION_CODE),
        )
    },
    CatalogEntry("auth.phone.error.expired", "Phone — error: code expired") {
        PhonePreview(
            codeStep.copy(code = "482913", resendCooldown = 0, errorMessage = PhoneAuthMessages.CODE_EXPIRED),
        )
    },
    CatalogEntry("auth.phone.interactive", "Phone — interactive (code 123456 signs in)") {
        InteractivePhoneSignIn(onDismiss = {})
    },
    // endregion

    // region Account type choice
    CatalogEntry("auth.accounttype", "Account type — person vs community") {
        AccountTypeChoiceContent(onChoose = {}, onSignOut = {})
    },
    // endregion
)

@Composable
private fun SignInPreview(state: SignInUiState, showApple: Boolean) {
    SignInContent(
        state = state,
        showAppleButton = showApple,
        onSignInWithApple = {},
        onSignInWithGoogle = {},
        onContinueWithPhone = {},
        onDismissError = {},
    )
}

@Composable
private fun PhonePreview(state: PhoneSignInState, menuExpanded: Boolean = false) {
    PhoneSignInContent(
        state = state,
        onCancel = {},
        onSelectCountry = {},
        onSelectOtherCountry = {},
        onCustomDialCodeChange = {},
        onPhoneNumberChange = {},
        onContinue = {},
        onCodeChange = {},
        onResend = {},
        onDismissError = {},
        initialCountryMenuExpanded = menuExpanded,
    )
}

/**
 * Sign in with fake providers: Google spins for a second and then fails with
 * "no Google account" (the emulator's usual answer), Apple spins and is
 * "cancelled" (no alert), phone opens the interactive phone flow in a cover.
 */
@Composable
private fun InteractiveSignIn() {
    var state by remember { mutableStateOf(SignInUiState()) }
    val scope = rememberCoroutineScope()
    fun fakeSignIn(error: String?) {
        if (state.isSigningIn) return
        state = state.copy(isSigningIn = true)
        scope.launch {
            delay(1_200)
            state = state.copy(isSigningIn = false, errorMessage = error)
        }
    }
    SignInContent(
        state = state,
        showAppleButton = true,
        onSignInWithApple = { fakeSignIn(error = null) },
        onSignInWithGoogle = { fakeSignIn(error = AuthServiceError.NoGoogleAccount().message) },
        onContinueWithPhone = { state = state.copy(showPhoneSignIn = true) },
        onDismissError = { state = state.copy(errorMessage = null) },
    )
    if (state.showPhoneSignIn) {
        FullScreenCover(onDismissRequest = { state = state.copy(showPhoneSignIn = false) }) {
            InteractivePhoneSignIn(onDismiss = { state = state.copy(showPhoneSignIn = false) })
        }
    }
}

/**
 * The phone flow driven by local state with the model's own rules
 * ([PhoneSignInState.canContinue], [sanitizeOtp], the 30 s countdown): numbers
 * starting with 0 are "invalid", code 123456 signs in, anything else is wrong.
 */
@Composable
private fun InteractivePhoneSignIn(onDismiss: () -> Unit) {
    var state by remember { mutableStateOf(PhoneSignInState()) }
    var cooldownGeneration by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(cooldownGeneration) {
        if (cooldownGeneration == 0) return@LaunchedEffect
        state = state.copy(resendCooldown = RESEND_COOLDOWN_SECONDS)
        while (state.resendCooldown > 0) {
            delay(1_000)
            state = state.copy(resendCooldown = state.resendCooldown - 1)
        }
    }

    fun sendCode() {
        if (state.isWorking) return
        state = state.copy(isWorking = true)
        scope.launch {
            delay(900)
            state = if (state.phoneNumber.trimStart().startsWith("0")) {
                state.copy(isWorking = false, errorMessage = PhoneAuthMessages.INVALID_PHONE_NUMBER)
            } else {
                cooldownGeneration++
                state.copy(isWorking = false, step = PhoneSignInStep.EnterCode("fake-${cooldownGeneration}"), code = "")
            }
        }
    }

    if (state.finished) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(
                    "Signed in — the real screen dismisses here.",
                    style = DrokpoTheme.typography.subheadline,
                    color = DrokpoTheme.colors.secondaryLabel,
                )
                PlainTextButton("Start over", onClick = { state = PhoneSignInState() })
                PlainTextButton("Close", onClick = onDismiss)
            }
        }
        return
    }

    PhoneSignInContent(
        state = state,
        onCancel = onDismiss,
        onSelectCountry = { state = state.selectingCountry(it) },
        onSelectOtherCountry = { state = state.selectingOtherCountry() },
        onCustomDialCodeChange = { state = state.copy(customDialCode = it) },
        onPhoneNumberChange = { state = state.copy(phoneNumber = it) },
        onContinue = ::sendCode,
        onCodeChange = { raw ->
            val code = sanitizeOtp(raw)
            if (code != state.code) {
                state = state.copy(code = code)
                if (code.length == OTP_LENGTH && !state.isWorking) {
                    state = state.copy(isWorking = true)
                    scope.launch {
                        delay(900)
                        state = if (code == "123456") {
                            state.copy(isWorking = false, finished = true)
                        } else {
                            state.copy(isWorking = false, errorMessage = PhoneAuthMessages.INVALID_VERIFICATION_CODE)
                        }
                    }
                }
            }
        },
        onResend = ::sendCode,
        onDismissError = { state = state.copy(errorMessage = null) },
    )
}
