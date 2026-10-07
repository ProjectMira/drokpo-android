package app.drokpo.android.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.drokpo.android.core.ApiClient
import app.drokpo.android.core.model.SentMessage
import app.drokpo.android.core.model.TolerantList
import app.drokpo.android.core.userMessage
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.EmptyState
import app.drokpo.android.ui.components.GroupedList
import app.drokpo.android.ui.components.GroupedRow
import app.drokpo.android.ui.components.GroupedSection
import app.drokpo.android.ui.components.LoadingState
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.theme.DrokpoTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

internal data class SentMessagesUiState(
    val messages: List<SentMessage> = emptyList(),
    /** First load only (iOS `isLoading = true` until the first answer); refreshes use [isRefreshing]. */
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    /** Replaces the list with secondary text. */
    val errorMessage: String? = null,
)

/** Recent messages you've sent across all conversations (GET /api/messages/sent). */
internal class SentMessagesModel(
    private val fetch: suspend () -> List<SentMessage> = {
        ApiClient.get<TolerantList<SentMessage>>("/api/messages/sent", listOf("limit" to "100")).items
    },
) : ViewModel() {
    private val _state = MutableStateFlow(SentMessagesUiState())
    val state: StateFlow<SentMessagesUiState> = _state.asStateFlow()

    init {
        // `.task { await load() }` — once per push.
        viewModelScope.launch { load() }
    }

    /** `.refreshable { await load() }`. */
    fun refresh() {
        if (_state.value.isRefreshing) return
        _state.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            try {
                load()
            } finally {
                _state.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private suspend fun load() {
        try {
            val messages = fetch()
            _state.update { it.copy(messages = messages, errorMessage = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(errorMessage = e.userMessage()) }
        }
        _state.update { it.copy(isLoading = false) }
    }
}

/** Port of SentMessagesView, pushed inside Settings. */
@Composable
internal fun SentMessagesScreen(onBack: () -> Unit) {
    val model = viewModel { SentMessagesModel() }
    val state by model.state.collectAsStateWithLifecycle()
    SentMessagesContent(state = state, onRefresh = model::refresh, onBack = onBack)
}

@Composable
internal fun SentMessagesContent(
    state: SentMessagesUiState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    navIcon: NavIcon = NavIcon.Back,
    now: Instant = Instant.now(),
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Scaffold(
        modifier = modifier,
        containerColor = colors.groupedBackground,
        topBar = {
            DrokpoTopBar(
                title = "Sent messages",
                navIcon = navIcon,
                onNavIcon = onBack,
                containerColor = colors.groupedBackground,
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            GroupedList(contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp + padding.calculateBottomPadding())) {
                val error = state.errorMessage
                when {
                    error != null -> item(key = "error") {
                        GroupedSection {
                            GroupedRow { Text(error, color = colors.secondaryLabel) }
                        }
                    }
                    state.messages.isEmpty() && !state.isLoading -> item(key = "empty") {
                        GroupedSection {
                            EmptyState(
                                icon = Icons.AutoMirrored.Outlined.Send,
                                title = "Nothing sent yet",
                                message = "Messages you send in your chats will show up here.",
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        }
                    }
                    state.messages.isNotEmpty() -> item(key = "messages") {
                        GroupedSection {
                            state.messages.forEach { message ->
                                GroupedRow {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(message.text ?: "", maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        message.sentDate?.let { date ->
                                            Text(
                                                namedRelativeString(date, relativeTo = now),
                                                style = typography.caption,
                                                color = colors.secondaryLabel,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // `.overlay { if isLoading { ProgressView() } }`.
            if (state.isLoading) LoadingState()
        }
    }
}
