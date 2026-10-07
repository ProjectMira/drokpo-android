package app.drokpo.android.features.communityhome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.drokpo.android.core.AppConfig
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.AppearanceMode
import app.drokpo.android.core.userMessage
import app.drokpo.android.ui.components.ActionSheet
import app.drokpo.android.ui.components.ActionSheetItem
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.GroupedButtonRow
import app.drokpo.android.ui.components.GroupedExternalLinkRow
import app.drokpo.android.ui.components.GroupedForm
import app.drokpo.android.ui.components.GroupedSection
import app.drokpo.android.ui.components.GroupedValueRow
import app.drokpo.android.ui.components.LoadingState
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.components.SegmentedPicker
import app.drokpo.android.ui.components.rememberInAppBrowser
import app.drokpo.android.ui.theme.DrokpoTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class CommunitySettingsUiState(
    val isDeleting: Boolean = false,
    val errorMessage: String? = null,
)

/** Deletion half of CommunitySettingsView. */
internal class CommunitySettingsModel(
    private val api: CommunityAccountApi = RemoteCommunityAccountApi,
    private val signOut: () -> Unit = { AppGraph.session.signOut() },
) : ViewModel() {
    private val _state = MutableStateFlow(CommunitySettingsUiState())
    val state: StateFlow<CommunitySettingsUiState> = _state.asStateFlow()

    /**
     * `DELETE /api/communities/me` (the backend also deletes the Auth user),
     * then sign out. Finishes even if Settings is closed meanwhile — like the
     * iOS Task — so a deleted account is never left signed in.
     */
    fun deleteCommunity() {
        if (_state.value.isDeleting) return
        _state.update { it.copy(isDeleting = true) }
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    api.deleteCommunity()
                    signOut()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(errorMessage = e.userMessage()) }
                } finally {
                    _state.update { it.copy(isDeleting = false) }
                }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(errorMessage = null) }
}

/**
 * Port of CommunitySettingsView: the same appearance/about/account pieces as
 * the person-side SettingsScreen, minus anything person-specific (blocked
 * users, sent messages) that doesn't apply to a community. Presented by
 * [CommunityProfileEditorScreen] in a FullScreenCover; [onDismiss] closes it
 * (iOS relies on swipe-down; Android gets an explicit X).
 */
@Composable
internal fun CommunitySettingsScreen(onDismiss: () -> Unit) {
    val model = viewModel { CommunitySettingsModel() }
    val state by model.state.collectAsStateWithLifecycle()
    val appearance by AppGraph.prefs.appearance.collectAsStateWithLifecycle()
    val openUrl = rememberInAppBrowser()
    val session = AppGraph.session
    CommunitySettingsContent(
        state = state,
        appearance = appearance,
        signedInAs = session.email ?: session.phone ?: "—",
        versionLabel = AppConfig.versionLabel,
        onClose = onDismiss,
        onAppearanceChange = AppGraph.prefs::setAppearance,
        onOpenPrivacyPolicy = { openUrl(AppConfig.PRIVACY_POLICY_URL) },
        onSignOut = session::signOut,
        onDeleteConfirmed = model::deleteCommunity,
        onDismissError = model::dismissError,
    )
}

@Composable
internal fun CommunitySettingsContent(
    state: CommunitySettingsUiState,
    appearance: AppearanceMode,
    signedInAs: String,
    versionLabel: String,
    onClose: () -> Unit,
    onAppearanceChange: (AppearanceMode) -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteConfirmed: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    initiallyConfirmingDelete: Boolean = false,
) {
    val colors = DrokpoTheme.colors
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(initiallyConfirmingDelete) }
    Scaffold(
        modifier = modifier,
        topBar = {
            DrokpoTopBar(
                title = "Settings",
                navIcon = NavIcon.Close,
                onNavIcon = onClose,
                containerColor = colors.groupedBackground,
            )
        },
        containerColor = colors.groupedBackground,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            GroupedForm(
                contentPadding = PaddingValues(top = 16.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            ) {
                GroupedSection(header = "Appearance") {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        SegmentedPicker(
                            options = AppearanceMode.entries,
                            selected = appearance,
                            onSelect = onAppearanceChange,
                            label = { it.label },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                GroupedSection(header = "About") {
                    GroupedExternalLinkRow("Privacy policy", onClick = onOpenPrivacyPolicy)
                    GroupedValueRow("Version", versionLabel)
                }
                GroupedSection(header = "Account") {
                    GroupedValueRow("Signed in as", signedInAs)
                    GroupedButtonRow("Sign out", onClick = onSignOut)
                    GroupedButtonRow(
                        "Delete community",
                        onClick = { showDeleteConfirmation = true },
                        destructive = true,
                        enabled = !state.isDeleting,
                    )
                }
            }
            if (state.isDeleting) LoadingState()
        }
    }

    if (showDeleteConfirmation) {
        ActionSheet(
            onDismissRequest = { showDeleteConfirmation = false },
            title = "Delete this community?",
            message = "Your community's profile, photos, and posts will be permanently removed. This cannot be undone.",
            items = listOf(ActionSheetItem("Delete everything", destructive = true, onClick = onDeleteConfirmed)),
        )
    }
    ErrorAlert(message = state.errorMessage, onDismiss = onDismissError, title = "Couldn't delete community")
}
