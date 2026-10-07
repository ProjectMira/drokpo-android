package app.drokpo.android.features.auth

import android.os.Bundle
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.BundleCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.drokpo.android.core.PhoneResendToken
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.LoadingState
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.components.ProminentButton
import app.drokpo.android.ui.components.RoundedTextField
import app.drokpo.android.ui.components.SecondaryButton
import app.drokpo.android.ui.theme.DrokpoPreviews
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.monospacedDigit

/**
 * Port of PhoneSignInView, presented by SignInScreen in a FullScreenCover
 * (CONTRACT §C.3 #2). Firebase Phone Auth: country code + number, then a
 * 6-digit SMS code. Success — typed code, instant verification or SMS
 * auto-retrieval — signs in through FirebaseAuth, and SessionStore's auth
 * listener routes away from the sign-in screen; this screen just dismisses.
 */
@Composable
internal fun PhoneSignInScreen(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    // Process death while the user reads the SMS in another app must not cost
    // a second SMS: the slot pulls a fresh snapshot from the model at save
    // time, and a model created after a restore is seeded from it.
    val saveSlot = rememberSaveable(saver = PhoneSignInSaveSlot.Saver) { PhoneSignInSaveSlot(restored = null) }
    val model: PhoneSignInModel = viewModel {
        PhoneSignInModel().apply { saveSlot.restored?.let { restore(it) } }
    }
    SideEffect { saveSlot.model = model }
    val state by model.state.collectAsStateWithLifecycle()
    // Hosts Firebase's reCAPTCHA fallback; inside the cover's Dialog the
    // context is a wrapper, which LocalActivity unwraps.
    val activity = LocalActivity.current

    // iOS `dismiss()` after verifyCode succeeds; on Android also after
    // AutoVerified or a later auto sign-in (the model watches FirebaseAuth).
    LaunchedEffect(state.finished) {
        if (state.finished) onDismiss()
    }

    PhoneSignInContent(
        state = state,
        onCancel = onDismiss,
        onSelectCountry = model::selectCountry,
        onSelectOtherCountry = model::selectOtherCountry,
        onCustomDialCodeChange = model::updateCustomDialCode,
        onPhoneNumberChange = model::updatePhoneNumber,
        onContinue = { model.sendCode(activity) },
        onCodeChange = model::updateCode,
        onResend = { model.sendCode(activity) },
        onDismissError = model::dismissError,
        modifier = modifier,
    )
}

/**
 * Stateless body of [PhoneSignInScreen]: "Phone number" inline bar with an X
 * (iOS "Cancel"), the current step at the top of the screen (16dp padding,
 * 24dp spacing), a centred spinner while working, and the "Couldn't sign in"
 * alert. [initialCountryMenuExpanded] lets the debug catalog show the menu open.
 */
@Composable
internal fun PhoneSignInContent(
    state: PhoneSignInState,
    onCancel: () -> Unit,
    onSelectCountry: (CountryCode) -> Unit,
    onSelectOtherCountry: () -> Unit,
    onCustomDialCodeChange: (String) -> Unit,
    onPhoneNumberChange: (String) -> Unit,
    onContinue: () -> Unit,
    onCodeChange: (String) -> Unit,
    onResend: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    initialCountryMenuExpanded: Boolean = false,
) {
    val colors = DrokpoTheme.colors
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { DrokpoTopBar(title = "Phone number", navIcon = NavIcon.Close, onNavIcon = onCancel) },
        containerColor = colors.background,
        contentColor = colors.label,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                when (state.step) {
                    PhoneSignInStep.EnterNumber -> NumberEntry(
                        state = state,
                        onSelectCountry = onSelectCountry,
                        onSelectOtherCountry = onSelectOtherCountry,
                        onCustomDialCodeChange = onCustomDialCodeChange,
                        onPhoneNumberChange = onPhoneNumberChange,
                        onContinue = onContinue,
                        initialCountryMenuExpanded = initialCountryMenuExpanded,
                    )
                    is PhoneSignInStep.EnterCode -> CodeEntry(
                        state = state,
                        onCodeChange = onCodeChange,
                        onResend = onResend,
                    )
                }
            }
            if (state.isWorking) LoadingState()
        }
    }
    ErrorAlert(message = state.errorMessage, onDismiss = onDismissError, title = "Couldn't sign in")
}

/**
 * rememberSaveable holder for [PhoneSignInSnapshot]. Not snapshot state: it is
 * only read when the registry saves (→ the live model) or restores.
 */
private class PhoneSignInSaveSlot(val restored: PhoneSignInSnapshot?) {
    var model: PhoneSignInModel? = null

    companion object {
        private const val KEY_COUNTRY = "country"
        private const val KEY_USE_CUSTOM = "useCustomCode"
        private const val KEY_CUSTOM_DIAL_CODE = "customDialCode"
        private const val KEY_PHONE_NUMBER = "phoneNumber"
        private const val KEY_VERIFICATION_ID = "verificationId"
        private const val KEY_RESEND_TOKEN = "resendToken"

        val Saver: Saver<PhoneSignInSaveSlot, Bundle> = Saver(
            save = { slot ->
                (slot.model?.snapshot() ?: slot.restored)?.let { snapshot ->
                    Bundle().apply {
                        putString(KEY_COUNTRY, snapshot.countryDialCode)
                        putBoolean(KEY_USE_CUSTOM, snapshot.useCustomCode)
                        putString(KEY_CUSTOM_DIAL_CODE, snapshot.customDialCode)
                        putString(KEY_PHONE_NUMBER, snapshot.phoneNumber)
                        putString(KEY_VERIFICATION_ID, snapshot.verificationId)
                        // ForceResendingToken is Parcelable.
                        putParcelable(KEY_RESEND_TOKEN, snapshot.resendToken)
                    }
                }
            },
            restore = { bundle ->
                PhoneSignInSaveSlot(
                    PhoneSignInSnapshot(
                        countryDialCode = bundle.getString(KEY_COUNTRY).orEmpty(),
                        useCustomCode = bundle.getBoolean(KEY_USE_CUSTOM),
                        customDialCode = bundle.getString(KEY_CUSTOM_DIAL_CODE).orEmpty(),
                        phoneNumber = bundle.getString(KEY_PHONE_NUMBER).orEmpty(),
                        verificationId = bundle.getString(KEY_VERIFICATION_ID),
                        resendToken = BundleCompat.getParcelable(bundle, KEY_RESEND_TOKEN, PhoneResendToken::class.java),
                    ),
                )
            },
        )
    }
}

@Composable
private fun NumberEntry(
    state: PhoneSignInState,
    onSelectCountry: (CountryCode) -> Unit,
    onSelectOtherCountry: () -> Unit,
    onCustomDialCodeChange: (String) -> Unit,
    onPhoneNumberChange: (String) -> Unit,
    onContinue: () -> Unit,
    initialCountryMenuExpanded: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CountryCodeMenu(
                label = state.countryChipLabel,
                onSelectCountry = onSelectCountry,
                onSelectOtherCountry = onSelectOtherCountry,
                initiallyExpanded = initialCountryMenuExpanded,
            )
            if (state.useCustomCode) {
                RoundedTextField(
                    value = state.customDialCode,
                    onValueChange = onCustomDialCodeChange,
                    placeholder = "+xx",
                    modifier = Modifier.width(64.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                )
            }
            RoundedTextField(
                value = state.phoneNumber,
                onValueChange = onPhoneNumberChange,
                placeholder = "Phone number",
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Go),
                // Android phone keyboards have an action key (iOS's phone pad
                // doesn't): let it do what "Continue" does once that's enabled.
                keyboardActions = KeyboardActions(onGo = { if (state.canContinue) onContinue() }),
            )
        }
        // iOS `.frame(maxWidth: .infinity)` on the button: natural size, centred.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ProminentButton(text = "Continue", onClick = onContinue, enabled = state.canContinue)
        }
    }
}

/**
 * iOS `Menu` whose label is the dial code + `chevron.down` (caption2) on a
 * rounded `.quaternary` chip. Rows are plain buttons ("India (+91)" …
 * "Other…"), no checkmark — iOS uses a Menu of Buttons, not a Picker.
 */
@Composable
private fun CountryCodeMenu(
    label: String,
    onSelectCountry: (CountryCode) -> Unit,
    onSelectOtherCountry: () -> Unit,
    initiallyExpanded: Boolean,
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colors.quaternaryLabel)
                .clickable(role = Role.DropdownList, onClickLabel = "Choose country code") { expanded = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = typography.body, color = colors.accent, maxLines = 1)
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            countryCodes.forEach { code ->
                DropdownMenuItem(
                    text = { Text(code.menuLabel, style = typography.body) },
                    onClick = {
                        expanded = false
                        onSelectCountry(code)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Other…", style = typography.body) },
                onClick = {
                    expanded = false
                    onSelectOtherCountry()
                },
            )
        }
    }
}

@Composable
private fun CodeEntry(
    state: PhoneSignInState,
    onCodeChange: (String) -> Unit,
    onResend: () -> Unit,
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Enter the 6-digit code sent to ${state.e164}",
            style = typography.subheadline,
            color = colors.secondaryLabel,
            textAlign = TextAlign.Center,
        )
        // iOS `TextField("123456", …).textFieldStyle(.roundedBorder)` with
        // `.multilineTextAlignment(.center)` and `.font(.title2.monospacedDigit())`.
        RoundedTextField(
            value = state.code,
            onValueChange = onCodeChange,
            placeholder = "123456",
            modifier = Modifier
                .fillMaxWidth()
                // iOS `.textContentType(.oneTimeCode)`: lets autofill offer the SMS code.
                .semantics { contentType = ContentType.SmsOtpCode },
            textStyle = typography.title2.monospacedDigit(),
            textAlign = TextAlign.Center,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        )
        // iOS `.buttonStyle(.bordered)`, no tint: accent label on the grey fill.
        SecondaryButton(onClick = onResend, enabled = state.canResend) {
            Text(state.resendLabel, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

@DrokpoPreviews
@Composable
private fun PhoneSignInContentPreview() {
    DrokpoTheme {
        PhoneSignInContent(
            state = PhoneSignInState(
                step = PhoneSignInStep.EnterCode("preview"),
                phoneNumber = "98765 43210",
                code = "482",
                resendCooldown = 24,
            ),
            onCancel = {},
            onSelectCountry = {},
            onSelectOtherCountry = {},
            onCustomDialCodeChange = {},
            onPhoneNumberChange = {},
            onContinue = {},
            onCodeChange = {},
            onResend = {},
            onDismissError = {},
        )
    }
}
