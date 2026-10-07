package app.drokpo.android.features.settings

import androidx.lifecycle.ViewModel
import app.drokpo.android.core.ApiClient
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.EmptyResponse
import app.drokpo.android.core.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class SettingsUiState(
    val isDeleting: Boolean = false,
    /** "Couldn't delete account" alert. */
    val errorMessage: String? = null,
    /** The account is gone: dismiss Settings (sign-out has been requested). */
    val dismissRequested: Boolean = false,
)

/**
 * SettingsView's account actions. Appearance needs no model (it is
 * `AppGraph.prefs.appearance`, iOS `@AppStorage("drokpo.appearance")`).
 */
internal class SettingsModel(
    private val deleteAccountRequest: suspend () -> Unit = { ApiClient.delete<EmptyResponse>("/api/profile/me") },
    private val requestSignOut: () -> Unit = { AppGraph.session.signOut() },
    /**
     * Where the deletion runs. iOS's Task outlives the sheet; here too — a DELETE cancelled by
     * dismissing Settings could leave the account deleted but the device still signed in.
     */
    private val workScope: CoroutineScope? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** "Sign out" (the screen dismisses first, like iOS `dismiss(); session.signOut()`). */
    fun signOut() = requestSignOut()

    /** "Delete everything": DELETE /api/profile/me, then dismiss and sign out. */
    fun deleteAccount() {
        if (_state.value.isDeleting) return
        _state.update { it.copy(isDeleting = true) }
        (workScope ?: AppGraph.appScope).launch {
            try {
                deleteAccountRequest()
                // The backend already deleted the Firebase Auth user; signing out
                // clears the now-invalid local session.
                _state.update { it.copy(dismissRequested = true) }
                requestSignOut()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.userMessage()) }
            } finally {
                _state.update { it.copy(isDeleting = false) }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }
}
