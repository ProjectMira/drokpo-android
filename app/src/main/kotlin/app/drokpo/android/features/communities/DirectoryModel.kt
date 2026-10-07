package app.drokpo.android.features.communities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.drokpo.android.core.model.CommunityProfile
import app.drokpo.android.core.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How many communities the "Discover communities" directory lists (the backend's max). */
internal const val DIRECTORY_LIMIT = 50

/** State of CommunityDirectoryView. */
internal data class DirectoryUiState(
    val communities: List<CommunityProfile> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    /** The community whose join/leave is in flight — every Join button is disabled meanwhile. */
    val workingCid: String? = null,
    val errorMessage: String? = null,
) {
    /** "No communities yet" — kept up while a reload runs once something has loaded (no blink). */
    val showsEmpty: Boolean get() = communities.isEmpty() && (!isLoading || hasLoaded)

    /** Centre spinner: only while the first load runs with nothing to show. */
    val showsSpinner: Boolean get() = isLoading && !hasLoaded && communities.isEmpty()
}

/**
 * Full "see all" list of communities (any registration state), with
 * join/leave inline. Port of the data half of iOS CommunityDirectoryView.
 */
internal class DirectoryModel(
    private val api: CommunitiesApi = RemoteCommunitiesApi,
) : ViewModel() {
    private val _state = MutableStateFlow(DirectoryUiState())
    val state: StateFlow<DirectoryUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loadGeneration = 0

    init {
        load(refreshing = false)
    }

    /** iOS `.onAppear { if hasLoaded { load() } }` — picks up a join/leave made on a pushed page. */
    fun onAppear() {
        if (_state.value.hasLoaded) load(refreshing = false)
    }

    fun refresh() = load(refreshing = true)

    private fun load(refreshing: Boolean) {
        loadJob?.cancel()
        val generation = ++loadGeneration
        _state.update { it.copy(isLoading = true, isRefreshing = refreshing) }
        loadJob = viewModelScope.launch {
            try {
                val communities = api.directory(DIRECTORY_LIMIT).communities.orEmpty()
                _state.update { it.copy(communities = communities, hasLoaded = true) }
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

    /**
     * Join or leave, then flip `joined` and move `memberCount` by ±1 (never
     * below 0) — the server's answer carries no counts. One at a time. Like
     * the iOS `Task {}`, it finishes even if the Directory is popped (or the
     * cover closed) meanwhile, so the next Home reload matches what was tapped.
     */
    fun toggleJoin(community: CommunityProfile) {
        if (_state.value.workingCid != null) return
        val cid = community.id
        val wasJoined = community.joined == true
        _state.update { it.copy(workingCid = cid) }
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    api.setJoined(cid, joined = !wasJoined)
                    _state.update { state ->
                        state.copy(
                            communities = state.communities.map { c ->
                                if (c.id != cid) {
                                    c
                                } else {
                                    val delta = if (wasJoined) -1 else 1
                                    c.copy(joined = !wasJoined, memberCount = maxOf(0, (c.memberCount ?: 0) + delta))
                                }
                            },
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(errorMessage = e.userMessage()) }
                } finally {
                    _state.update { it.copy(workingCid = null) }
                }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(errorMessage = null) }
}
