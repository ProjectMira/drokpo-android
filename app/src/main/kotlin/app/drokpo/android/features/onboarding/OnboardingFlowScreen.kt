package app.drokpo.android.features.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.drokpo.android.core.AppGraph
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.PlainTextButton
import app.drokpo.android.ui.components.PrimaryButton
import app.drokpo.android.ui.components.rememberPhotoPicker
import app.drokpo.android.ui.theme.DrokpoTheme

/**
 * Port of OnboardingFlow (+ OnboardingModel), CONTRACT.md §B.2 / §F.2: the
 * six-step "Create profile" flow RootScreen shows for NeedsOnboarding. When
 * Finish succeeds the model refreshes the session, which routes away.
 */
@Composable
fun OnboardingFlowScreen(modifier: Modifier = Modifier) {
    // The form survives process death (see OnboardingDraft); a config change
    // doesn't need this, since MainActivity handles those in place.
    val draft = rememberSaveable(saver = DraftHolder.Saver) { DraftHolder(restored = null) }
    val model = viewModel {
        OnboardingModel(initial = draft.restored?.takeIf { it.uid == AppGraph.session.uid })
    }
    SideEffect { draft.current = { model.state.toDraft(uid = AppGraph.session.uid) } }
    val locationFetcher = rememberLocationFetcher()
    val state = model.state
    val pickPhotos = rememberPhotoPicker(maxItems = state.remainingPhotoSlots) { uris ->
        model.onEdit(OnboardingEdit.AddPhotos(uris.map { it.toString() }))
    }

    // System back mirrors the leading "Back" button past the first step (and
    // is swallowed, not passed to the Activity, while a submission runs). On
    // Basics it falls through and leaves the app, as there's nowhere to go back to.
    BackHandler(enabled = state.step != OnboardingStep.Basics) { model.back() }

    OnboardingContent(
        state = state,
        onEdit = model::onEdit,
        onContinue = { model.advance(locationFetcher) },
        onBack = model::back,
        onSignOut = { AppGraph.session.signOut() },
        onAddPhotos = pickPhotos,
        onDismissError = model::dismissError,
        modifier = modifier,
    )
}

/**
 * Hands the saved form to [OnboardingModel] after process death ([restored])
 * and saves the live form when the Activity saves its state ([current] is read
 * then, not on every keystroke).
 */
private class DraftHolder(val restored: OnboardingDraft?) {
    var current: (() -> OnboardingDraft)? = null

    companion object {
        val Saver: Saver<DraftHolder, String> = Saver(
            save = { holder -> holder.current?.invoke()?.encode() },
            restore = { json -> DraftHolder(restored = OnboardingDraft.decode(json)) },
        )
    }
}

/**
 * Stateless "Create profile" shell: inline title, Back / Sign out, the
 * progress bar, the current step, and the Continue / Finish button. Renders
 * from [state] alone (debug catalog).
 */
@Composable
internal fun OnboardingContent(
    state: OnboardingState,
    onEdit: (OnboardingEdit) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    onAddPhotos: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DrokpoTheme.colors
    Scaffold(
        modifier = modifier,
        topBar = {
            DrokpoTopBar(
                title = "Create profile",
                navigationIcon = if (state.step != OnboardingStep.Basics) {
                    { PlainTextButton("Back", onClick = onBack, enabled = !state.isSubmitting) }
                } else {
                    null
                },
                actions = { PlainTextButton("Sign out", onClick = onSignOut) },
            )
        },
        containerColor = colors.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                // The keyboard pushes the Continue button up with it, like
                // SwiftUI's keyboard avoidance on the iOS VStack.
                .imePadding(),
        ) {
            OnboardingProgressBar(
                progress = state.progress,
                step = state.step,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when (state.step) {
                    OnboardingStep.Basics -> BasicsStep(state, onEdit)
                    OnboardingStep.Details -> DetailsStep(state, onEdit)
                    OnboardingStep.AboutYou -> AboutYouStep(state, onEdit)
                    OnboardingStep.Socials -> SocialsStep(state, onEdit)
                    OnboardingStep.Location -> LocationStep(state)
                    OnboardingStep.Photos -> PhotosStep(state, onEdit, onAddPhotos)
                }
            }

            PrimaryButton(
                text = if (state.step == OnboardingStep.Photos) "Finish" else "Continue",
                onClick = onContinue,
                // Not clickable while loading; stays accent-filled so the white
                // spinner reads (iOS tints its ProgressView white).
                enabled = state.canAdvance,
                loading = state.isSubmitting,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    ErrorAlert(message = state.errorMessage, onDismiss = onDismissError)
}

/** iOS linear `ProgressView(value: step + 1, total: 6)`: 4dp accent bar on a grey track. */
@Composable
private fun OnboardingProgressBar(progress: Float, step: OnboardingStep, modifier: Modifier = Modifier) {
    val colors = DrokpoTheme.colors
    LinearProgressIndicator(
        progress = { progress },
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .semantics { contentDescription = "Step ${step.ordinal + 1} of ${OnboardingStep.entries.size}" },
        color = colors.accent,
        trackColor = colors.systemGray5,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}
