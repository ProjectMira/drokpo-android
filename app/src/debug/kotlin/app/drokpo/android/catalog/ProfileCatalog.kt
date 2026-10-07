package app.drokpo.android.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.drokpo.android.core.AppConfig
import app.drokpo.android.core.AppearanceMode
import app.drokpo.android.core.model.GeoLocation
import app.drokpo.android.core.model.Photo
import app.drokpo.android.core.model.Profile
import app.drokpo.android.features.profile.EditProfileContent
import app.drokpo.android.features.profile.EditProfileCopy
import app.drokpo.android.features.profile.EditProfileForm
import app.drokpo.android.features.profile.EditProfileUiState
import app.drokpo.android.features.profile.ProfileContent
import app.drokpo.android.features.profile.ProfileUiState
import app.drokpo.android.features.profile.movePhoto
import app.drokpo.android.features.settings.BlockedUsersContent
import app.drokpo.android.features.settings.BlockedUsersUiState
import app.drokpo.android.features.settings.SentMessagesContent
import app.drokpo.android.features.settings.SentMessagesUiState
import app.drokpo.android.features.settings.SettingsContent
import app.drokpo.android.features.settings.SettingsUiState

// Group 7 (profile + settings): every screen and meaningful state, rendered from the stateless
// *Content composables with Fixtures — no ViewModels, network or AppGraph session access. The
// entries keep a little local state so toggles, pickers, sliders and the photo reorder can be
// exercised by hand during visual QA.

private const val FIXTURE_EMAIL = "tenzin.dolma@example.com"

/** Six photos: the "+" tile disappears at the cap. */
private val sixPhotos: List<Photo> = (0 until 6).map { Fixtures.photo("tenzin-$it", order = it) }

private val profileHidden: Profile = Fixtures.profile.copy(discoverable = false)

/** Stored values from before today's vocabularies: both pickers must still show them. */
private val profileLegacy: Profile = Fixtures.profile.copy(region = "Amdo", education = "BSc Computer Science")

private fun profileState(profile: Profile?, email: String? = FIXTURE_EMAIL): ProfileUiState =
    ProfileUiState(profile = profile, email = email, orderedPhotos = profile?.photos.orEmpty())

private fun editState(profile: Profile = Fixtures.profile): EditProfileUiState =
    EditProfileUiState(form = EditProfileForm.from(profile))

val profileCatalogEntries: List<CatalogEntry> = listOf(
    // ---------------------------------------------------------------- Profile tab
    CatalogEntry("profile.tab", "Profile tab — complete profile") { ProfileTab(profileState(Fixtures.profile)) },
    CatalogEntry("profile.tab.empty", "Profile tab — name only (no photos, prompts, socials)") {
        ProfileTab(profileState(Fixtures.profileEmpty))
    },
    CatalogEntry("profile.tab.loading", "Profile tab — no profile yet (rows —, eye + switch disabled)") {
        ProfileTab(profileState(profile = null, email = null))
    },
    CatalogEntry("profile.tab.phone", "Profile tab — phone sign-in (Email —)") {
        ProfileTab(profileState(Fixtures.profile, email = null))
    },
    CatalogEntry("profile.tab.hidden", "Profile tab — hidden from Discover") { ProfileTab(profileState(profileHidden)) },
    CatalogEntry("profile.tab.discoverable.saving", "Profile tab — Discover switch saving (optimistic off)") {
        ProfileTab(profileState(Fixtures.profile).copy(pendingDiscoverable = false))
    },
    CatalogEntry("profile.tab.photos.full", "Profile tab — six photos (no + tile)") {
        ProfileTab(profileState(Fixtures.profile.copy(photos = sixPhotos)))
    },
    CatalogEntry("profile.tab.working", "Profile tab — photo upload in flight (spinner overlay)") {
        ProfileTab(profileState(Fixtures.profile).copy(isWorking = true))
    },
    CatalogEntry("profile.tab.refreshing", "Profile tab — pull to refresh") {
        ProfileTab(profileState(Fixtures.profile).copy(isRefreshing = true))
    },
    CatalogEntry("profile.tab.error", "Profile tab — error alert") {
        ProfileTab(profileState(Fixtures.profile).copy(errorMessage = "That photo couldn't be processed. Try a different one."))
    },

    // ---------------------------------------------------------------- Edit profile
    CatalogEntry("profile.edit", "Edit profile — complete profile") { EditProfile(editState()) },
    CatalogEntry("profile.edit.empty", "Edit profile — name only (defaults)") { EditProfile(editState(Fixtures.profileEmpty)) },
    CatalogEntry("profile.edit.blankname", "Edit profile — blank name (Save disabled)") {
        EditProfile(editState().let { it.copy(form = it.form.copy(displayName = "   ")) })
    },
    CatalogEntry("profile.edit.saving", "Edit profile — saving") { EditProfile(editState().copy(isSaving = true)) },
    CatalogEntry("profile.edit.legacy", "Edit profile — legacy region/education prepended") { EditProfile(editState(profileLegacy)) },
    CatalogEntry("profile.edit.location.locating", "Edit profile — locating (inline spinner)") {
        EditProfile(editState().copy(isLocating = true))
    },
    CatalogEntry("profile.edit.location.updated", "Edit profile — location updated") {
        EditProfile(
            editState().copy(
                locationStatus = EditProfileCopy.LOCATION_UPDATED,
                updatedLocation = GeoLocation(lat = 43.65, lng = -79.38),
            ),
        )
    },
    CatalogEntry("profile.edit.location.denied", "Edit profile — location denied (Open Settings)") {
        EditProfile(editState().copy(locationStatus = EditProfileCopy.LOCATION_DENIED, locationDenied = true))
    },
    CatalogEntry("profile.edit.location.failed", "Edit profile — location failed") {
        EditProfile(editState().copy(locationStatus = EditProfileCopy.LOCATION_FAILED))
    },
    CatalogEntry("profile.edit.birthday", "Edit profile — birthday picker") {
        EditProfile(editState(), showDatePicker = true)
    },
    CatalogEntry("profile.edit.error", "Edit profile — Couldn't save alert") {
        EditProfile(editState().copy(errorMessage = "The Internet connection appears to be offline."))
    },

    // ---------------------------------------------------------------- Settings
    CatalogEntry("profile.settings", "Settings") { Settings(SettingsUiState()) },
    CatalogEntry("profile.settings.deleteconfirm", "Settings — delete confirmation") {
        Settings(SettingsUiState(), showDeleteConfirmation = true)
    },
    CatalogEntry("profile.settings.deleting", "Settings — deleting account") { Settings(SettingsUiState(isDeleting = true)) },
    CatalogEntry("profile.settings.error", "Settings — Couldn't delete account alert") {
        Settings(SettingsUiState(errorMessage = "The Internet connection appears to be offline."))
    },

    // ---------------------------------------------------------------- Blocked users
    CatalogEntry("profile.blocked", "Blocked users — list") { Blocked(BlockedUsersUiState(blocked = Fixtures.blockedUsers)) },
    CatalogEntry("profile.blocked.empty", "Blocked users — empty") { Blocked(BlockedUsersUiState()) },
    CatalogEntry("profile.blocked.working", "Blocked users — unblock in flight (buttons disabled)") {
        Blocked(BlockedUsersUiState(blocked = Fixtures.blockedUsers, workingUid = Fixtures.blockedUsers.first().uid))
    },
    CatalogEntry("profile.blocked.error", "Blocked users — Couldn't unblock alert") {
        Blocked(BlockedUsersUiState(blocked = Fixtures.blockedUsers, errorMessage = "Server error (500)."))
    },

    // ---------------------------------------------------------------- Sent messages
    CatalogEntry("profile.sent", "Sent messages — list") {
        Sent(SentMessagesUiState(messages = Fixtures.sentMessages, isLoading = false))
    },
    CatalogEntry("profile.sent.empty", "Sent messages — empty") { Sent(SentMessagesUiState(isLoading = false)) },
    CatalogEntry("profile.sent.loading", "Sent messages — first load") { Sent(SentMessagesUiState(isLoading = true)) },
    CatalogEntry("profile.sent.refreshing", "Sent messages — pull to refresh") {
        Sent(SentMessagesUiState(messages = Fixtures.sentMessages, isLoading = false, isRefreshing = true))
    },
    CatalogEntry("profile.sent.error", "Sent messages — load failed") {
        Sent(SentMessagesUiState(isLoading = false, errorMessage = "The Internet connection appears to be offline."))
    },
)

/** ProfileContent with local state: the Discover switch, photo delete and drag-reorder all respond. */
@Composable
private fun ProfileTab(initial: ProfileUiState) {
    var state by remember { mutableStateOf(initial) }
    ProfileContent(
        state = state,
        onOpenSettings = {},
        onPreview = {},
        onEdit = {},
        onRefresh = {},
        onAddPhoto = {},
        onDeletePhoto = { photo -> state = state.copy(orderedPhotos = state.orderedPhotos - photo) },
        onDragStart = { id -> state = state.copy(draggingPhotoId = id) },
        onDragOver = { target ->
            val dragged = state.draggingPhotoId
            if (dragged != null) state = state.copy(orderedPhotos = movePhoto(state.orderedPhotos, dragged, target))
        },
        onDragEnd = { state = state.copy(draggingPhotoId = null) },
        onDiscoverableChange = { value ->
            state = state.copy(profile = state.profile?.copy(discoverable = value))
        },
        onDismissError = { state = state.copy(errorMessage = null) },
    )
}

@Composable
private fun EditProfile(initial: EditProfileUiState, showDatePicker: Boolean = false) {
    var state by remember { mutableStateOf(initial) }
    EditProfileContent(
        state = state,
        onEdit = { transform -> state = state.copy(form = transform(state.form)) },
        onUpdateLocation = {},
        onOpenSettings = {},
        onSave = {},
        onCancel = {},
        onDismissError = { state = state.copy(errorMessage = null) },
        initiallyShowDatePicker = showDatePicker,
    )
}

@Composable
private fun Settings(initial: SettingsUiState, showDeleteConfirmation: Boolean = false) {
    var state by remember { mutableStateOf(initial) }
    var appearance by remember { mutableStateOf(AppearanceMode.System) }
    var confirming by remember { mutableStateOf(showDeleteConfirmation) }
    SettingsContent(
        state = state,
        appearance = appearance,
        versionLabel = AppConfig.versionLabel,
        showDeleteConfirmation = confirming,
        onDone = {},
        onAppearanceChange = { appearance = it },
        onOpenPrivacyPolicy = {},
        onOpenBlockedUsers = {},
        onOpenSentMessages = {},
        onSignOut = {},
        onDeleteRequest = { confirming = true },
        onDeleteConfirmationDismiss = { confirming = false },
        onConfirmDelete = { state = state.copy(isDeleting = true) },
        onDismissError = { state = state.copy(errorMessage = null) },
    )
}

@Composable
private fun Blocked(initial: BlockedUsersUiState) {
    var state by remember { mutableStateOf(initial) }
    BlockedUsersContent(
        state = state,
        onUnblock = { user -> state = state.copy(blocked = state.blocked - user) },
        onDismissError = { state = state.copy(errorMessage = null) },
        onBack = {},
    )
}

@Composable
private fun Sent(state: SentMessagesUiState) {
    SentMessagesContent(state = state, onRefresh = {}, onBack = {})
}
