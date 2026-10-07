package app.drokpo.android.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.BlockedUser
import app.drokpo.android.core.userMessage
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.EmptyState
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.GroupedList
import app.drokpo.android.ui.components.GroupedRow
import app.drokpo.android.ui.components.GroupedSection
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.components.SecondaryButton
import app.drokpo.android.ui.theme.DrokpoTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal data class BlockedUsersUiState(
    val blocked: List<BlockedUser> = emptyList(),
    /** The unblock in flight; every Unblock button is disabled meanwhile. */
    val workingUid: String? = null,
    val errorMessage: String? = null,
)

/** BlockedUsersView's state: the local BlockStore list plus the unblock in flight. */
internal class BlockedUsersModel(
    blocked: StateFlow<List<BlockedUser>> = AppGraph.blocks.blocked,
    private val unblockUser: suspend (BlockedUser) -> Unit = { AppGraph.blocks.unblock(it) },
) : ViewModel() {
    private val workingUid = MutableStateFlow<String?>(null)
    private val errorMessage = MutableStateFlow<String?>(null)

    val state: StateFlow<BlockedUsersUiState> =
        combine(blocked, workingUid, errorMessage) { list, working, error ->
            BlockedUsersUiState(blocked = list, workingUid = working, errorMessage = error)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, BlockedUsersUiState(blocked = blocked.value))

    /** DELETE /api/blocks/{uid}, then BlockStore forgets the entry (kept on failure). */
    fun unblock(user: BlockedUser) {
        if (workingUid.value != null) return
        workingUid.value = user.uid
        viewModelScope.launch {
            try {
                unblockUser(user)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage.value = e.userMessage()
            } finally {
                workingUid.value = null
            }
        }
    }

    fun dismissError() {
        errorMessage.value = null
    }
}

/** Port of BlockedUsersView, pushed inside Settings. */
@Composable
internal fun BlockedUsersScreen(onBack: () -> Unit) {
    val model = viewModel { BlockedUsersModel() }
    val state by model.state.collectAsStateWithLifecycle()
    BlockedUsersContent(
        state = state,
        onUnblock = model::unblock,
        onDismissError = model::dismissError,
        onBack = onBack,
    )
}

@Composable
internal fun BlockedUsersContent(
    state: BlockedUsersUiState,
    onUnblock: (BlockedUser) -> Unit,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    navIcon: NavIcon = NavIcon.Back,
) {
    val colors = DrokpoTheme.colors
    Scaffold(
        modifier = modifier,
        containerColor = colors.groupedBackground,
        topBar = {
            DrokpoTopBar(
                title = "Blocked users",
                navIcon = navIcon,
                onNavIcon = onBack,
                containerColor = colors.groupedBackground,
            )
        },
    ) { padding ->
        GroupedList(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp + padding.calculateBottomPadding()),
        ) {
            item(key = "blocked") {
                GroupedSection {
                    if (state.blocked.isEmpty()) {
                        // ContentUnavailableView as the List's only row.
                        EmptyState(
                            icon = Icons.Outlined.Block,
                            title = "No blocked users",
                            message = "People you block from the feed will show up here.",
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    } else {
                        state.blocked.forEach { user ->
                            BlockedUserRow(user = user, enabled = state.workingUid == null, onUnblock = { onUnblock(user) })
                        }
                    }
                }
            }
        }
    }

    ErrorAlert(message = state.errorMessage, onDismiss = onDismissError, title = "Couldn't unblock")
}

@Composable
private fun BlockedUserRow(user: BlockedUser, enabled: Boolean, onUnblock: () -> Unit) {
    val colors = DrokpoTheme.colors
    GroupedRow(
        trailing = { SecondaryButton("Unblock", onClick = onUnblock, enabled = enabled) },
    ) {
        Column {
            Text(user.displayName ?: "Member")
            Text(
                blockedDateLabel(user.blockedAt),
                style = DrokpoTheme.typography.caption,
                color = colors.secondaryLabel,
            )
        }
    }
}

/** `Text(user.blockedAt, style: .date)` — the long date style ("October 5, 2026"). */
internal fun blockedDateLabel(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).format(Instant.ofEpochMilli(epochMillis).atZone(zone))
