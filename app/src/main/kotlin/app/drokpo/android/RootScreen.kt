package app.drokpo.android

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.drokpo.android.core.AppConfig
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.SessionState
import app.drokpo.android.features.auth.AccountTypeChoiceScreen
import app.drokpo.android.features.auth.SignInScreen
import app.drokpo.android.features.communityonboarding.CommunityOnboardingFlowScreen
import app.drokpo.android.features.onboarding.OnboardingFlowScreen
import app.drokpo.android.ui.components.LoadingState
import app.drokpo.android.ui.components.PlainTextButton
import app.drokpo.android.ui.components.ProminentButton
import app.drokpo.android.ui.components.ScopedViewModels
import app.drokpo.android.ui.theme.DrokpoTheme
import kotlinx.coroutines.launch

/**
 * Port of RootView (DrokpoApp.swift): the session-state router. Not a NavHost —
 * a `when` on [SessionState] (CONTRACT §A.14, §C.1).
 *
 * - `!AppConfig.hasFirebaseConfig` → [SetupNoticeScreen] (iOS: no
 *   GoogleService-Info.plist → setup notice, so the app still runs before
 *   Firebase is set up).
 * - Otherwise a Crossfade on the state (iOS `.animation(.default, value:
 *   session.state)`). ActivePerson and ActiveCommunity share one branch key,
 *   so MainTabs isn't recreated between them.
 *
 * Each branch is wrapped in its own [ScopedViewModels] (§D.3, "branch scope"):
 * leaving a state (sign-out, onboarding done, account switch) clears that
 * branch's ViewModels — including MainTabs' session scope, which drops
 * ChatStore's listener.
 */
@Composable
fun RootScreen() {
    if (!AppConfig.hasFirebaseConfig) {
        SetupNoticeScreen()
        return
    }
    val session = AppGraph.session
    val state by session.state.collectAsStateWithLifecycle()
    val lastError by session.lastError.collectAsStateWithLifecycle()

    Crossfade(
        targetState = RootBranch.of(state),
        modifier = Modifier
            .fillMaxSize()
            .background(DrokpoTheme.colors.background),
        label = "RootScreen",
    ) { branch ->
        // Crossfade keys each target's content, so every branch gets its own scope.
        ScopedViewModels {
            when (branch) {
                RootBranch.Loading -> RootLoadingContent()
                RootBranch.SignedOut -> SignInScreen()
                RootBranch.ChoosingAccountType -> AccountTypeChoiceScreen()
                RootBranch.NeedsOnboarding -> OnboardingFlowScreen()
                RootBranch.Main -> MainTabs()
                RootBranch.NeedsCommunityOnboarding -> CommunityOnboardingFlowScreen()
                RootBranch.Failed -> RootFailedContent(
                    message = lastError,
                    // iOS `Task { await session.refreshProfile() }` — unstructured, so it
                    // runs on the app scope rather than this branch's composition.
                    onRetry = { AppGraph.appScope.launch { session.refreshProfile() } },
                    onSignOut = { session.signOut() },
                )
            }
        }
    }
}

/** Crossfade keys. Both active states map to [Main] so MainTabs survives a person ↔ community refresh. */
internal enum class RootBranch {
    Loading, SignedOut, ChoosingAccountType, NeedsOnboarding, Main, NeedsCommunityOnboarding, Failed;

    companion object {
        fun of(state: SessionState): RootBranch = when (state) {
            SessionState.Loading -> Loading
            SessionState.SignedOut -> SignedOut
            SessionState.ChoosingAccountType -> ChoosingAccountType
            SessionState.NeedsOnboarding -> NeedsOnboarding
            SessionState.ActivePerson, SessionState.ActiveCommunity -> Main
            SessionState.NeedsCommunityOnboarding -> NeedsCommunityOnboarding
            SessionState.Failed -> Failed
        }
    }
}

/** iOS `case .loading: ProgressView()`. */
@Composable
internal fun RootLoadingContent(modifier: Modifier = Modifier) {
    LoadingState(
        modifier
            .fillMaxSize()
            .background(DrokpoTheme.colors.background),
    )
}

/**
 * iOS `case .failed`: VStack(spacing: 16) { "Couldn't load your profile.",
 * lastError (footnote, secondary), "Retry" (borderedProminent), "Sign out" }.padding().
 */
@Composable
internal fun RootFailedContent(
    message: String?,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Box(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Couldn't load your profile.", style = typography.body, color = colors.label)
            if (message != null) {
                Text(message, style = typography.footnote, color = colors.secondaryLabel)
            }
            ProminentButton("Retry", onClick = onRetry)
            PlainTextButton("Sign out", onClick = onSignOut)
        }
    }
}

/**
 * Port of SetupNoticeView: shown when google-services.json is missing so the
 * app still runs before Firebase is set up (the build succeeds without it —
 * see app/build.gradle.kts).
 */
@Composable
fun SetupNoticeScreen() {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // iOS "gearshape.2" at .largeTitle.
            Icon(
                Icons.Outlined.Settings,
                contentDescription = null,
                tint = colors.secondaryLabel,
                modifier = Modifier.size(40.dp),
            )
            Text("Firebase not configured", style = typography.headline, color = colors.label)
            Text(
                "Add google-services.json to app/ and rebuild.",
                style = typography.footnote,
                color = colors.secondaryLabel,
                textAlign = TextAlign.Center,
            )
        }
    }
}
