package app.drokpo.android.features.communities

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.drokpo.android.core.model.CommunityProfile
import app.drokpo.android.ui.components.ButtonMetrics
import app.drokpo.android.ui.components.ControlSize
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.EmptyState
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.GroupedList
import app.drokpo.android.ui.components.GroupedRow
import app.drokpo.android.ui.components.GroupedSection
import app.drokpo.android.ui.components.LoadingState
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.components.SecondaryButton
import app.drokpo.android.ui.theme.DrokpoTheme

/**
 * Port of CommunityDirectoryView: "Discover communities", every community with
 * an inline Join/Joined button. A NavHost destination (pushed from
 * CommunitiesScreen, or the root of [CommunityDirectoryCoverScreen]).
 */
@Composable
internal fun CommunityDirectoryScreen(
    onBack: () -> Unit,
    onOpenCommunity: (cid: String, preview: CommunityProfile) -> Unit,
    navIcon: NavIcon,
) {
    val model = viewModel { DirectoryModel() }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { model.onAppear() }
    CommunityDirectoryContent(
        state = state,
        navIcon = navIcon,
        onBack = onBack,
        onOpenCommunity = onOpenCommunity,
        onToggleJoin = model::toggleJoin,
        onRefresh = model::refresh,
        onDismissError = model::dismissError,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommunityDirectoryContent(
    state: DirectoryUiState,
    navIcon: NavIcon,
    onBack: () -> Unit,
    onOpenCommunity: (cid: String, preview: CommunityProfile) -> Unit,
    onToggleJoin: (CommunityProfile) -> Unit,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DrokpoTheme.colors
    Scaffold(
        modifier = modifier,
        topBar = {
            DrokpoTopBar(
                title = "Discover communities",
                navIcon = navIcon,
                onNavIcon = onBack,
                containerColor = colors.groupedBackground,
            )
        },
        containerColor = colors.groupedBackground,
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            GroupedList(
                contentPadding = PaddingValues(top = 16.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            ) {
                if (state.showsEmpty) {
                    item(key = "empty") {
                        GroupedSection {
                            EmptyState(
                                icon = Icons.Outlined.Groups,
                                title = "No communities yet",
                                message = "Communities will show up here as they're created.",
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        }
                    }
                } else if (state.communities.isNotEmpty()) {
                    item(key = "communities") {
                        // Separators start at the row text, past the 48dp logo (16 + 48 + 12).
                        GroupedSection(separatorInset = 76.dp) {
                            state.communities.forEach { community ->
                                DirectoryRow(
                                    community = community,
                                    isWorking = state.workingCid == community.id,
                                    joinEnabled = state.workingCid == null,
                                    onOpen = { onOpenCommunity(community.id, community) },
                                    onToggleJoin = { onToggleJoin(community) },
                                )
                            }
                        }
                    }
                }
            }
            if (state.showsSpinner) LoadingState()
        }
    }
    ErrorAlert(message = state.errorMessage, onDismiss = onDismissError)
}

/** A NavigationLink row: CommunityRow, the Join/Joined capsule, and the disclosure chevron. */
@Composable
private fun DirectoryRow(
    community: CommunityProfile,
    isWorking: Boolean,
    joinEnabled: Boolean,
    onOpen: () -> Unit,
    onToggleJoin: () -> Unit,
) {
    val colors = DrokpoTheme.colors
    GroupedRow(
        onClick = onOpen,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        trailing = {
            JoinButton(
                joined = community.joined == true,
                isWorking = isWorking,
                enabled = joinEnabled,
                onClick = onToggleJoin,
            )
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = null,
                tint = colors.tertiaryLabel,
                modifier = Modifier.size(14.dp),
            )
        },
    ) {
        CommunityRow(community)
    }
}

/**
 * Inline join/leave — a bordered (tonal) capsule keeps the button
 * independently tappable inside the row's link. "Joined" uses the secondary
 * tint; a spinner replaces the label while its request runs.
 */
@Composable
private fun JoinButton(joined: Boolean, isWorking: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = DrokpoTheme.colors
    SecondaryButton(
        text = if (joined) "Joined" else "Join",
        onClick = onClick,
        enabled = enabled,
        loading = isWorking,
        size = ControlSize.Small,
        shape = ButtonMetrics.Capsule,
        tint = if (joined) colors.secondaryLabel else colors.accent,
        textStyle = DrokpoTheme.typography.subheadline.copy(fontWeight = FontWeight.Bold),
    )
}
