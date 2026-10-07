package app.drokpo.android.features.auth

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.drokpo.android.core.AuthService
import app.drokpo.android.core.AuthServiceError
import app.drokpo.android.core.userMessage
import app.drokpo.android.ui.components.ControlSize
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.FullScreenCover
import app.drokpo.android.ui.components.LoadingState
import app.drokpo.android.ui.components.SecondaryButton
import app.drokpo.android.ui.theme.DrokpoLogo
import app.drokpo.android.ui.theme.DrokpoPreviews
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold
import app.drokpo.android.ui.theme.medium
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Port of SignInView: logo, name and tagline centred in the space above a
 * bottom stack of providers — Apple (only when [AuthService.isAppleSignInEnabled]),
 * Google, phone — and the 18+ notice. On success it does nothing: SessionStore's
 * auth-state listener routes away from this screen (CONTRACT §F.1).
 */
@Composable
fun SignInScreen(modifier: Modifier = Modifier) {
    // The open phone cover survives process death (the user often leaves for
    // Messages to read the SMS); PhoneSignInScreen restores its own step. A
    // ViewModel created after a restore starts with the cover open.
    val phoneCoverOpen = rememberSaveable { mutableStateOf(false) }
    val model: SignInViewModel = viewModel { SignInViewModel(initialShowPhoneSignIn = phoneCoverOpen.value) }
    val state by model.state.collectAsStateWithLifecycle()
    SideEffect { phoneCoverOpen.value = state.showPhoneSignIn }
    // Credential Manager and Firebase's web OAuth flow both present from an Activity.
    val activity = LocalActivity.current

    SignInContent(
        state = state,
        showAppleButton = AuthService.isAppleSignInEnabled,
        onSignInWithApple = { model.signInWithApple(activity) },
        onSignInWithGoogle = { model.signInWithGoogle(activity) },
        onContinueWithPhone = model::showPhoneSignIn,
        onDismissError = model::dismissError,
        modifier = modifier,
    )

    // iOS `.sheet(isPresented: $showPhoneSignIn) { PhoneSignInView() }` — a
    // form sheet, which the contract maps to a FullScreenCover (§C.3 #2).
    if (state.showPhoneSignIn) {
        FullScreenCover(onDismissRequest = model::dismissPhoneSignIn) {
            PhoneSignInScreen(onDismiss = model::dismissPhoneSignIn)
        }
    }
}

@Immutable
internal data class SignInUiState(
    val isSigningIn: Boolean = false,
    /** Non-null → the "Sign-in failed" alert. */
    val errorMessage: String? = null,
    val showPhoneSignIn: Boolean = false,
)

/**
 * SignInView's `@State` + its `signIn(_:)` helper. Providers are injected so
 * JVM tests can fake them; the Activity is handed in per tap and only lives as
 * long as that sign-in attempt.
 */
internal class SignInViewModel(
    private val googleSignIn: suspend (Activity) -> Unit = AuthService::signInWithGoogle,
    private val appleSignIn: suspend (Activity) -> Unit = AuthService::signInWithApple,
    /** True when SignInScreen restores an open phone cover after process death. */
    initialShowPhoneSignIn: Boolean = false,
) : ViewModel() {
    private val _state = MutableStateFlow(SignInUiState(showPhoneSignIn = initialShowPhoneSignIn))
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    fun signInWithGoogle(activity: Activity?) = signIn(activity, googleSignIn)

    fun signInWithApple(activity: Activity?) = signIn(activity, appleSignIn)

    fun showPhoneSignIn() {
        // The whole button stack is disabled while signing in (iOS `.disabled(isSigningIn)`).
        if (_state.value.isSigningIn) return
        _state.update { it.copy(showPhoneSignIn = true) }
    }

    fun dismissPhoneSignIn() = _state.update { it.copy(showPhoneSignIn = false) }

    fun dismissError() = _state.update { it.copy(errorMessage = null) }

    private fun signIn(activity: Activity?, provider: suspend (Activity) -> Unit) {
        if (_state.value.isSigningIn) return
        _state.update { it.copy(isSigningIn = true) }
        viewModelScope.launch {
            try {
                // No Activity to present from ≙ iOS's missing key window.
                provider(activity ?: throw AuthServiceError.NoPresenter())
                // SessionStore's auth listener takes over from here.
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthServiceError.Cancelled) {
                // The user backed out of the Google/Apple UI — not an error
                // (iOS ignores ASAuthorizationError.canceled the same way).
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.userMessage()) }
            } finally {
                _state.update { it.copy(isSigningIn = false) }
            }
        }
    }
}

/**
 * Stateless body of [SignInScreen]. Layout mirrors the iOS VStack: a Spacer,
 * the 96dp logo + "Drokpo" + tagline (12dp apart), a second Spacer, then the
 * provider stack (12dp apart, 24dp sides, 32dp above the gesture area). While
 * [SignInUiState.isSigningIn] every button is disabled under a centred spinner.
 * Without the Apple button the stack simply starts at Google — the header
 * stays centred in whatever space is left, so the screen keeps its balance.
 */
@Composable
internal fun SignInContent(
    state: SignInUiState,
    showAppleButton: Boolean,
    onSignInWithApple: () -> Unit,
    onSignInWithGoogle: () -> Unit,
    onContinueWithPhone: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    val enabled = !state.isSigningIn
    Box(
        modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        FillViewportColumn(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Spacer(Modifier.weight(1f))

            Column(
                // SwiftUI Spacers never shrink below the 8pt system spacing.
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DrokpoLogo(size = 96.dp)
                Text("Drokpo", style = typography.largeTitle.bold(), color = colors.label)
                Text(
                    "Make friends and find your people in the Tibetan community",
                    style = typography.subheadline,
                    color = colors.secondaryLabel,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.weight(1f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (showAppleButton) {
                    AppleSignInButton(onClick = onSignInWithApple, enabled = enabled)
                }
                // SF Symbols grow with Dynamic Type, so the glyphs are sized in sp.
                val density = LocalDensity.current
                ProviderButton(text = "Sign in with Google", onClick = onSignInWithGoogle, enabled = enabled) {
                    GoogleGlyph(size = with(density) { 17.sp.toDp() })
                }
                ProviderButton(text = "Continue with phone", onClick = onContinueWithPhone, enabled = enabled) {
                    Icon(
                        Icons.Filled.Phone,
                        contentDescription = null,
                        modifier = Modifier.size(with(density) { 18.sp.toDp() }),
                    )
                }
                Text(
                    "You must be 18 or older to use Drokpo.",
                    style = typography.caption,
                    color = colors.secondaryLabel,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (state.isSigningIn) LoadingState()
    }

    ErrorAlert(message = state.errorMessage, onDismiss = onDismissError, title = "Sign-in failed")
}

/**
 * Google / phone rows: `.buttonStyle(.bordered)` (grey fill, accent label)
 * around a label framed to 50pt, so with the bordered padding the button is
 * ~64 tall on iOS (taller than the 50pt Apple button — see the App Store
 * screenshots). A minimum, not a fixed height: at large font scales the row
 * grows (and the label may wrap) instead of clipping.
 */
@Composable
private fun ProviderButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    icon: @Composable () -> Unit,
) {
    SecondaryButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = BorderedProviderButtonHeight),
        enabled = enabled,
        size = ControlSize.Large,
        spacing = ProviderIconSpacing,
    ) {
        icon()
        Text(text, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 50pt label frame + `.bordered`'s 7pt top and bottom padding. */
private val BorderedProviderButtonHeight = 64.dp

/** Glyph edge → label: ~10pt in the iOS screenshot (17pt G circle, label 10pt after it). */
private val ProviderIconSpacing = 10.dp

/**
 * `SignInWithAppleButton(.signIn)` with `.signInWithAppleButtonStyle(colorScheme
 * == .dark ? .white : .black)`, 50pt tall: Apple logo + "Sign in with Apple".
 * Android has no native Apple button, so this draws Apple's black/white style.
 */
@Composable
private fun AppleSignInButton(onClick: () -> Unit, enabled: Boolean) {
    val dark = DrokpoTheme.isDark
    val container = if (dark) Color.White else Color.Black
    val content = if (dark) Color.Black else Color.White
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(6.dp),
        color = container,
        contentColor = content,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .alpha(if (enabled) 1f else 0.4f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AppleLogo,
                    contentDescription = null,
                    modifier = Modifier.size(with(LocalDensity.current) { 16.sp.toDp() }),
                )
                Text(
                    "Sign in with Apple",
                    style = DrokpoTheme.typography.title3.medium(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * SF Symbol `g.circle.fill`: a filled circle in the label colour with the "G"
 * knocked out, so the button's own fill shows through it (as on iOS).
 */
@Composable
private fun GoogleGlyph(size: Dp = 17.dp) {
    val tint = LocalContentColor.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // In dp terms so the letter always fits the circle, whatever the font scale.
    val fontSizePx = with(density) { (size * 0.62f).toPx() }
    val style = remember(fontSizePx, density) {
        TextStyle(fontSize = with(density) { fontSizePx.toSp() }, fontWeight = FontWeight.Bold)
    }
    Canvas(
        Modifier
            .size(size)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        drawCircle(tint)
        val layout = measurer.measure("G", style)
        // Centre the capital on its cap height, not the line box (which
        // reserves room for descenders).
        val capHeight = fontSizePx * 0.71f
        val baselineY = (this.size.height + capHeight) / 2f
        drawText(
            textLayoutResult = layout,
            color = Color.Black,
            topLeft = Offset((this.size.width - layout.size.width) / 2f, baselineY - layout.firstBaseline),
            blendMode = BlendMode.DstOut,
        )
    }
}

/** The Apple logo (Simple Icons, CC0), a 24×24 single path. */
private val AppleLogo: ImageVector by lazy {
    ImageVector.Builder(
        name = "AppleLogo",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(APPLE_LOGO_PATH).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()
}

private const val APPLE_LOGO_PATH =
    "M12.152 6.896c-.948 0-2.415-1.078-3.96-1.04-2.04.027-3.91 1.183-4.961 3.014-2.117 3.675-.546 " +
        "9.103 1.519 12.09 1.013 1.454 2.208 3.09 3.792 3.039 1.52-.065 2.09-.987 3.935-.987 1.831 0 " +
        "2.35.987 3.96.948 1.637-.026 2.676-1.48 3.676-2.948 1.156-1.688 1.636-3.325 1.662-3.415-.039-.013-" +
        "3.182-1.221-3.22-4.857-.026-3.04 2.48-4.494 2.597-4.559-1.429-2.09-3.623-2.324-4.39-2.376-2-.156-" +
        "3.675 1.09-4.61 1.09zM15.53 3.83c.843-1.012 1.4-2.427 1.245-3.83-1.207.052-2.662.805-3.532 " +
        "1.818-.78.896-1.454 2.338-1.273 3.714 1.338.104 2.715-.688 3.559-1.701"

@DrokpoPreviews
@Composable
private fun SignInContentPreview() {
    DrokpoTheme {
        SignInContent(
            state = SignInUiState(),
            showAppleButton = true,
            onSignInWithApple = {},
            onSignInWithGoogle = {},
            onContinueWithPhone = {},
            onDismissError = {},
        )
    }
}
