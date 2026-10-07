package app.drokpo.android.features.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.model.Photo
import app.drokpo.android.core.model.Profile
import app.drokpo.android.core.model.ProfileUpdate
import app.drokpo.android.core.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything ProfileContent renders. */
internal data class ProfileUiState(
    val profile: Profile? = null,
    /** `session.email` — null for phone sign-in (the row shows "—"). */
    val email: String? = null,
    /**
     * Optimistic mirror of `profile?.photos` so drag-to-reorder feels instant;
     * re-synced from the server profile after each commit (or on failure), and
     * left alone while a drag or a reorder save is in flight.
     */
    val orderedPhotos: List<Photo> = emptyList(),
    /** The photo being dragged; while set, profile refreshes don't touch [orderedPhotos]. */
    val draggingPhotoId: String? = null,
    /** Photo upload / delete in flight — spinner overlay. */
    val isWorking: Boolean = false,
    /** Pull-to-refresh in flight. */
    val isRefreshing: Boolean = false,
    /**
     * Optimistic value for the "Show me in Discover" switch while its save
     * is in flight; null means "use the server profile".
     */
    val pendingDiscoverable: Boolean? = null,
    val errorMessage: String? = null,
) {
    val isDiscoverable: Boolean get() = pendingDiscoverable ?: profile?.discoverable ?: true

    val canToggleDiscoverable: Boolean get() = profile != null && pendingDiscoverable == null

    val canAddPhoto: Boolean get() = orderedPhotos.size < MAX_PROFILE_PHOTOS
}

/**
 * Port of ProfileView's `@State` + actions. Lives in the session scope (the
 * Profile tab root), so it survives tab switches and is cleared on sign-out.
 * Dependencies default to the app singletons; tests pass fakes.
 */
internal class ProfileModel(
    private val myProfile: StateFlow<Profile?> = AppGraph.session.myProfile,
    private val refreshProfile: suspend () -> Unit = { AppGraph.session.refreshProfile() },
    private val email: () -> String? = { AppGraph.session.email },
    private val api: ProfileApi = ProfileApi.Live,
) : ViewModel() {
    private val _state = MutableStateFlow(
        ProfileUiState(
            profile = myProfile.value,
            email = email(),
            orderedPhotos = myProfile.value?.photos.orEmpty(),
        ),
    )
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    /**
     * Reorder PATCHes still in flight. Until they land the server profile holds the old order,
     * so re-syncing from it (another emission, a pull-to-refresh, an upload finishing) would
     * flash the strip back to the old order. A counter, so a second drop made while the first
     * is saving keeps its order on screen too.
     */
    private var commitsInFlight = 0

    init {
        // iOS `.onChange(of: profile?.photos) { syncPhotos() }`.
        viewModelScope.launch {
            myProfile.collect { profile ->
                _state.update { it.copy(profile = profile, email = email()) }
                syncPhotos()
            }
        }
    }

    /**
     * Re-mirror the server photos into the local order, unless a drag or a
     * reorder save is in flight (so a profile refresh then doesn't clobber the
     * order on screen). Also iOS `.onAppear { syncPhotos() }`.
     */
    fun syncPhotos() {
        if (commitsInFlight > 0) return
        // Read the session's profile, not the state's copy: right after refreshProfile() returns,
        // the collector may not have delivered the new value yet, and syncing from the stale
        // copy would flash the old order for a frame.
        val profile = myProfile.value
        _state.update { state ->
            if (state.draggingPhotoId != null) {
                state
            } else {
                state.copy(profile = profile, orderedPhotos = profile?.photos.orEmpty())
            }
        }
    }

    /** `.refreshable { await session.refreshProfile() }`. */
    fun refresh() {
        if (_state.value.isRefreshing) return
        _state.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            try {
                refreshProfile()
            } finally {
                _state.update { it.copy(isRefreshing = false) }
                syncPhotos()
            }
        }
    }

    // region Photos

    fun addPhoto(uri: Uri) = addPhotoFrom { api.uploadPhoto(uri) }

    /** Upload (via [upload], which returns the storage path), confirm at the end of the list, refresh. */
    internal fun addPhotoFrom(upload: suspend () -> String) {
        _state.update { it.copy(isWorking = true) }
        viewModelScope.launch {
            try {
                val storagePath = upload()
                val order = _state.value.profile?.photos?.size ?: 0
                api.confirmPhoto(storagePath, order)
                refreshProfile()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An unreadable pick surfaces PhotoUploaderError.InvalidImage's message here.
                _state.update { it.copy(errorMessage = e.userMessage()) }
            } finally {
                _state.update { it.copy(isWorking = false) }
                syncPhotos()
            }
        }
    }

    /** No confirmation, like iOS: the X deletes right away. */
    fun deletePhoto(photo: Photo) {
        _state.update { it.copy(isWorking = true) }
        viewModelScope.launch {
            try {
                api.deletePhoto(photo.storagePath)
                refreshProfile()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.userMessage()) }
            } finally {
                _state.update { it.copy(isWorking = false) }
                syncPhotos()
            }
        }
    }

    // endregion

    // region Reorder

    /** Long-press picked up [photoId]. */
    fun beginDrag(photoId: String) {
        _state.update { it.copy(draggingPhotoId = photoId) }
    }

    /** The dragged photo is over [targetId]: move it onto that slot (optimistic, local only). */
    fun dragOver(targetId: String) {
        _state.update { state ->
            val dragged = state.draggingPhotoId ?: return@update state
            state.copy(orderedPhotos = movePhoto(state.orderedPhotos, dragged, targetId))
        }
    }

    /** Drop (or a cancelled gesture): commit the order shown on screen. */
    fun endDrag() {
        if (_state.value.draggingPhotoId == null) return
        commitOrder()
    }

    private fun commitOrder() {
        _state.update { it.copy(draggingPhotoId = null) }
        val newOrder = _state.value.orderedPhotos.storagePaths()
        if (newOrder == _state.value.profile?.photos.orEmpty().storagePaths()) return
        commitsInFlight++
        viewModelScope.launch {
            try {
                try {
                    api.reorderPhotos(newOrder)
                    refreshProfile()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(errorMessage = e.userMessage()) }
                    refreshProfile() // revert the optimistic order
                }
            } finally {
                commitsInFlight--
            }
            // Syncs were held off while saving, and a refresh that returns an unchanged profile
            // doesn't re-emit, so re-sync explicitly — on failure this is what puts the server
            // order back.
            syncPhotos()
        }
    }

    // endregion

    /** "Show me in Discover": optimistic while the PATCH is in flight, then the server value. */
    fun setDiscoverable(value: Boolean) {
        if (!_state.value.canToggleDiscoverable) return
        _state.update { it.copy(pendingDiscoverable = value) }
        viewModelScope.launch {
            try {
                api.updateProfile(ProfileUpdate(discoverable = value))
                refreshProfile()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.userMessage()) }
            } finally {
                // Clearing the pending value is the rollback on failure: the switch
                // falls back to the (unchanged) server profile.
                _state.update { it.copy(pendingDiscoverable = null) }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }
}
