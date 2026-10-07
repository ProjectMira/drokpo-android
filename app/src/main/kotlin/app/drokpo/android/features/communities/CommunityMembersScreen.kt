package app.drokpo.android.features.communities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.drokpo.android.core.RemotePhotoView
import app.drokpo.android.core.model.CommunityMember
import app.drokpo.android.core.userMessage
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.EmptyState
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.GroupedList
import app.drokpo.android.ui.components.GroupedRow
import app.drokpo.android.ui.components.GroupedSection
import app.drokpo.android.ui.components.LoadingState
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How many members the list asks for. */
internal const val MEMBERS_LIMIT = 50

internal data class MembersUiState(
    val members: List<CommunityMember> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
) {
    val showsEmpty: Boolean get() = members.isEmpty() && !isLoading
    val showsSpinner: Boolean get() = isLoading && members.isEmpty()
}

/** Data half of iOS CommunityMembersView: one GET on start, again on pull-to-refresh. */
internal class CommunityMembersModel(
    private val cid: String,
    private val api: CommunitiesApi = RemoteCommunitiesApi,
) : ViewModel() {
    private val _state = MutableStateFlow(MembersUiState())
    val state: StateFlow<MembersUiState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var loadGeneration = 0

    init {
        load(refreshing = false)
    }

    fun refresh() = load(refreshing = true)

    private fun load(refreshing: Boolean) {
        loadJob?.cancel()
        val generation = ++loadGeneration
        _state.update { it.copy(isLoading = true, isRefreshing = refreshing) }
        loadJob = viewModelScope.launch {
            try {
                val members = api.members(cid, MEMBERS_LIMIT).members.orEmpty()
                _state.update { it.copy(members = members) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.userMessage()) }
            } finally {
                if (generation == loadGeneration) {
                    _state.update { it.copy(isLoading = false, isRefreshing = false) }
                }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(errorMessage = null) }
}

/**
 * Port of CommunityMembersView (CONTRACT §B.8): a community's member list —
 * only reachable from CommunityPageScreen once you've joined (the endpoint
 * itself also enforces this: members-only, see backend docs/COMMUNITIES.md).
 * Slim rows only: name, photo, region — never the full dating-card view.
 * Always pushed, so the leading button is Back. Registered by sharedDestinations.
 */
@Composable
fun CommunityMembersScreen(cid: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val model = viewModel(key = "members-$cid") { CommunityMembersModel(cid) }
    val state by model.state.collectAsStateWithLifecycle()
    CommunityMembersContent(
        state = state,
        onBack = onBack,
        onRefresh = model::refresh,
        onDismissError = model::dismissError,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommunityMembersContent(
    state: MembersUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DrokpoTheme.colors
    Scaffold(
        modifier = modifier,
        topBar = {
            DrokpoTopBar(
                title = "Members",
                navIcon = NavIcon.Back,
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
                                title = "No members yet",
                                message = "Members will show up here once people join.",
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        }
                    }
                } else if (state.members.isNotEmpty()) {
                    item(key = "members") {
                        // Separators start at the name, past the 44dp photo (16 + 44 + 12).
                        GroupedSection(separatorInset = 72.dp) {
                            state.members.forEach { MemberRow(it) }
                        }
                    }
                }
            }
            if (state.showsSpinner) LoadingState()
        }
    }
    ErrorAlert(message = state.errorMessage, onDismiss = onDismissError)
}

@Composable
private fun MemberRow(member: CommunityMember) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    GroupedRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        leading = {
            // Decorative: the name beside it already labels the row.
            RemotePhotoView(
                photo = member.photo,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape),
            )
        },
    ) {
        Column(Modifier.padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(member.displayName ?: "Member", style = typography.subheadline.bold(), color = colors.label)
            member.region?.takeIf { it.isNotEmpty() }?.let { region ->
                Text(region, style = typography.caption, color = colors.secondaryLabel)
            }
        }
    }
}
