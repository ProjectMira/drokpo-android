package app.drokpo.android.features.communityonboarding

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
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
 * Port of CommunityOnboardingFlow (+ CommunityOnboardingModel), CONTRACT §B.3 /
 * §F.3: "Register your community" in five steps — basics, contact, contact
 * person, address (which creates the community), photos (which finishes and
 * hands control back to SessionStore).
 */
@Composable
fun CommunityOnboardingFlowScreen(modifier: Modifier = Modifier) {
    val model: CommunityOnboardingModel = viewModel { CommunityOnboardingModel() }
    val state = model.uiState

    val pickPhotos = rememberPhotoPicker(maxItems = state.remainingPhotoSlots) { uris ->
        model.addPickedPhotos(uris.map(Uri::toString))
    }

    // System back = the toolbar's "Back". On the first step there is no Back,
    // so it falls through and leaves the app, as with every root screen.
    BackHandler(enabled = state.step != CommunityOnboardingStep.Basics) { model.back() }

    CommunityOnboardingContent(
        state = state,
        onFormChange = model::updateForm,
        onBack = model::back,
        onSignOut = { AppGraph.session.signOut() },
        onAdvance = model::advance,
        onAddPhotos = pickPhotos,
        onRemovePhoto = model::removePhoto,
        onDismissError = model::dismissError,
        modifier = modifier,
    )
}

/** Stateless flow shell: top bar, step progress, the current step and the Continue/Finish button. */
@Composable
internal fun CommunityOnboardingContent(
    state: CommunityOnboardingUiState,
    onFormChange: FormChange,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    onAdvance: () -> Unit,
    onAddPhotos: () -> Unit,
    onRemovePhoto: (PickedPhoto) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DrokpoTheme.colors
    Scaffold(
        modifier = modifier,
        containerColor = colors.background,
        topBar = {
            DrokpoTopBar(
                title = "Register your community",
                navigationIcon = if (state.step != CommunityOnboardingStep.Basics) {
                    { PlainTextButton("Back", onClick = onBack, enabled = !state.isSubmitting) }
                } else {
                    null
                },
                actions = { PlainTextButton("Sign out", onClick = onSignOut) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                // The keyboard pushes the Continue button up with it (iOS keyboard avoidance).
                .imePadding(),
        ) {
            StepProgressBar(
                progress = state.progress,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when (state.step) {
                    CommunityOnboardingStep.Basics -> CommunityBasicsStep(state.form, onFormChange)
                    CommunityOnboardingStep.Contact -> CommunityContactStep(state.form, onFormChange)
                    CommunityOnboardingStep.ContactPerson -> CommunityContactPersonStep(state.form, onFormChange)
                    CommunityOnboardingStep.Address -> CommunityAddressStep(state.form, onFormChange)
                    CommunityOnboardingStep.Photos -> CommunityPhotosStep(
                        photos = state.photos,
                        onAddPhotos = onAddPhotos,
                        onRemovePhoto = onRemovePhoto,
                        editable = !state.isSubmitting,
                    )
                }
            }
            PrimaryButton(
                text = if (state.step == CommunityOnboardingStep.Photos) "Finish" else "Continue",
                onClick = onAdvance,
                enabled = state.canAdvance,
                // White spinner in place of the label; the button ignores taps meanwhile.
                loading = state.isSubmitting,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
    ErrorAlert(message = state.errorMessage, onDismiss = onDismissError)
}

/** iOS linear `ProgressView(value:total:)`: a 4dp rounded track with the accent fill. */
@Composable
private fun StepProgressBar(progress: Float, modifier: Modifier = Modifier) {
    val colors = DrokpoTheme.colors
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(shape)
            .background(colors.systemGray5)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) },
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress)
                .fillMaxHeight()
                .clip(shape)
                .background(colors.accent),
        )
    }
}
