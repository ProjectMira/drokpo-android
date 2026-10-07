package app.drokpo.android.features.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.model.GeoLocation
import app.drokpo.android.core.model.Profile
import app.drokpo.android.core.model.ProfileUpdate
import app.drokpo.android.core.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneOffset

/** Everything EditProfileContent renders. */
internal data class EditProfileUiState(
    val form: EditProfileForm,
    val isSaving: Boolean = false,
    val isLocating: Boolean = false,
    /** Footer under "Update my location"; null → the default explanation. */
    val locationStatus: String? = null,
    /** Set by "Update my location"; sent with the next save only if present. */
    val updatedLocation: GeoLocation? = null,
    /** Location permission is off — the footer offers a Settings shortcut. */
    val locationDenied: Boolean = false,
    val errorMessage: String? = null,
    /** Saved and refreshed: the screen dismisses itself. */
    val finished: Boolean = false,
) {
    val canSave: Boolean get() = canSave(form, isSaving)
}

/**
 * Port of EditProfileView's state and `save()` / `refreshLocation()`.
 * Presentation-scoped (created inside the FullScreenCover), so a fresh form
 * is built from [profile] every time the editor opens.
 *
 * State is Compose snapshot state rather than a StateFlow: text fields must
 * see their own edits synchronously or fast typing drops characters.
 */
internal class EditProfileModel(
    profile: Profile,
    /** ProfileScreen passes `session.refreshProfile()`. */
    private val onSaved: suspend () -> Unit,
    private val saveProfile: suspend (ProfileUpdate) -> Unit = { ProfileApi.Live.updateProfile(it) },
    /**
     * Where the save runs. iOS's save Task keeps going if the sheet is dismissed mid-request
     * (and still refreshes the profile), so by default it runs on the app scope rather than
     * being cancelled with this model.
     */
    private val saveScope: CoroutineScope? = null,
    today: LocalDate = LocalDate.now(ZoneOffset.UTC),
) : ViewModel() {
    var state: EditProfileUiState by mutableStateOf(EditProfileUiState(form = EditProfileForm.from(profile, today)))
        private set

    fun updateForm(form: EditProfileForm) {
        state = state.copy(form = form)
    }

    /**
     * "Update my location". [request] is LocationFetcher.requestLocation (permission dialog,
     * then one fix); [isDenied] is read after it returns.
     */
    fun refreshLocation(request: suspend () -> GeoLocation?, isDenied: () -> Boolean) {
        if (state.isLocating) return
        state = state.copy(isLocating = true)
        viewModelScope.launch {
            try {
                val location = request()
                val outcome = locationOutcome(
                    location = location,
                    isDenied = location == null && isDenied(),
                    previousLocation = state.updatedLocation,
                    previouslyDenied = state.locationDenied,
                )
                state = state.copy(
                    locationStatus = outcome.status,
                    updatedLocation = outcome.updatedLocation,
                    locationDenied = outcome.denied,
                )
            } finally {
                state = state.copy(isLocating = false)
            }
        }
    }

    fun save() {
        val current = state
        if (!current.canSave) return
        state = current.copy(isSaving = true)
        val body = buildProfileUpdate(current.form, current.updatedLocation)
        (saveScope ?: AppGraph.appScope).launch {
            try {
                saveProfile(body)
                onSaved()
                state = state.copy(finished = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(errorMessage = e.userMessage())
            } finally {
                state = state.copy(isSaving = false)
            }
        }
    }

    fun dismissError() {
        state = state.copy(errorMessage = null)
    }
}
