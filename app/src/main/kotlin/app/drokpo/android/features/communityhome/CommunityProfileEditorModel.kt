package app.drokpo.android.features.communityhome

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.model.CommunityAddress
import app.drokpo.android.core.model.CommunityProfile
import app.drokpo.android.core.model.CommunityUpdate
import app.drokpo.android.core.model.ContactPerson
import app.drokpo.android.core.model.Photo
import app.drokpo.android.core.model.Socials
import app.drokpo.android.core.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A community may show at most this many photos (the "+" tile hides at the cap). */
internal const val MAX_COMMUNITY_PHOTOS = 6

/** How long the toolbar shows "Saved ✓" after a successful save. */
internal const val SAVED_BADGE_MILLIS = 2_000L

/** The editor's text fields — every field collected at community onboarding. */
internal data class CommunityEditorFields(
    val name: String = "",
    val description: String = "",
    val website: String = "",
    val phone: String = "",
    val email: String = "",
    val contactName: String = "",
    val contactRole: String = "",
    val contactPhone: String = "",
    val contactEmail: String = "",
    val line1: String = "",
    val city: String = "",
    val state: String = "",
    val country: String = "",
    val postalCode: String = "",
    val instagram: String = "",
    val youtube: String = "",
    val tiktok: String = "",
    val facebook: String = "",
) {
    /**
     * Do the text fields differ from what [community] holds? Guards the
     * session-refresh reload (photo add/delete also refreshes the session, and
     * that must never wipe typed-but-unsaved edits). No community → not dirty.
     */
    fun isDirty(community: CommunityProfile?): Boolean = community != null && this != from(community)

    /**
     * Client-side mirror of the backend's PATCH validation, so Save can't fire
     * a request that 422s with a context-free message. Null when savable.
     */
    val saveBlocker: String?
        get() {
            val trimmedName = name.trimWhitespaces()
            // Code points, like the backend's Python len() (which is what decides).
            val length = trimmedName.codePointCount(0, trimmedName.length)
            if (length !in 2..80) return "Name must be 2–80 characters."
            if (description.trimWhitespaces().isEmpty()) return "Description can't be empty."
            val trimmedWebsite = website.trimWhitespaces()
            if (trimmedWebsite.isNotEmpty() && !trimmedWebsite.startsWith("https://")) {
                return "Website must start with https://."
            }
            return null
        }

    /**
     * The PATCH body. Clearable optionals go up as "" when emptied — the
     * backend deletes the stored value. Never-clearable fields (email, contact
     * name, city, country) stay omit-when-empty (null), i.e. "unchanged".
     * x / wechat aren't edited here, so they're omitted (unchanged).
     */
    fun toUpdate(): CommunityUpdate = CommunityUpdate(
        name = name.trimWhitespaces(),
        description = description.trimWhitespaces(),
        website = website.trimWhitespaces(),
        phone = phone.trimWhitespaces(),
        email = email.nonEmptyTrimmed(),
        contactPerson = ContactPerson(
            name = contactName.nonEmptyTrimmed(),
            role = contactRole.trimWhitespaces(),
            phone = contactPhone.trimWhitespaces(),
            email = contactEmail.trimWhitespaces(),
        ),
        address = CommunityAddress(
            line1 = line1.trimWhitespaces(),
            city = city.nonEmptyTrimmed(),
            state = state.trimWhitespaces(),
            country = country.nonEmptyTrimmed(),
            postalCode = postalCode.trimWhitespaces(),
        ),
        socials = Socials(
            instagram = instagram.trimWhitespaces(),
            youtube = youtube.trimWhitespaces(),
            tiktok = tiktok.trimWhitespaces(),
            facebook = facebook.trimWhitespaces(),
        ),
    )

    companion object {
        /** iOS `loadFromSession()`: every field from the community, missing values as "". */
        fun from(community: CommunityProfile): CommunityEditorFields = CommunityEditorFields(
            name = community.name.orEmpty(),
            description = community.description.orEmpty(),
            website = community.website.orEmpty(),
            phone = community.phone.orEmpty(),
            email = community.email.orEmpty(),
            contactName = community.contactPerson?.name.orEmpty(),
            contactRole = community.contactPerson?.role.orEmpty(),
            contactPhone = community.contactPerson?.phone.orEmpty(),
            contactEmail = community.contactPerson?.email.orEmpty(),
            line1 = community.address?.line1.orEmpty(),
            city = community.address?.city.orEmpty(),
            state = community.address?.state.orEmpty(),
            country = community.address?.country.orEmpty(),
            postalCode = community.address?.postalCode.orEmpty(),
            instagram = community.socials?.instagram.orEmpty(),
            youtube = community.socials?.youtube.orEmpty(),
            tiktok = community.socials?.tiktok.orEmpty(),
            facebook = community.socials?.facebook.orEmpty(),
        )
    }
}

/** Everything [CommunityProfileEditorContent] renders. */
internal data class CommunityEditorUiState(
    /** The session's live community (photos, verification, member count). */
    val community: CommunityProfile?,
    val fields: CommunityEditorFields,
    val isSaving: Boolean = false,
    val justSaved: Boolean = false,
    /** Photo add/delete in flight — spinner overlay. */
    val isWorking: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
) {
    val isDirty: Boolean get() = fields.isDirty(community)
    val saveBlocker: String? get() = fields.saveBlocker
    val isVerified: Boolean get() = community?.isVerified == true
    val photos: List<Photo> get() = community?.photos.orEmpty()
    val canAddPhoto: Boolean get() = photos.size < MAX_COMMUNITY_PHOTOS
}

/**
 * The "Community" tab (the community account's Profile tab root): editing
 * every field collected at onboarding, plus photo management. Unlike a
 * person's profile, none of this is gated on verification — a pending
 * community can (and should) fill everything in while it waits, per
 * docs/COMMUNITIES.md.
 *
 * Form state is Compose snapshot state rather than a StateFlow: text fields
 * must see their own edits synchronously (an asynchronously collected flow can
 * hand a TextField a stale value mid-typing and jump the cursor).
 */
internal class CommunityProfileEditorModel(
    private val sessionCommunity: StateFlow<CommunityProfile?> = AppGraph.session.myCommunity,
    private val refreshProfile: suspend () -> Unit = { AppGraph.session.refreshProfile() },
    private val api: CommunityAccountApi = RemoteCommunityAccountApi,
) : ViewModel() {
    /** Mirror of the session community this screen last synced against. */
    private var community: CommunityProfile? by mutableStateOf(sessionCommunity.value)
    private var fields by mutableStateOf(CommunityEditorFields())
    private var isSaving by mutableStateOf(false)
    private var justSaved by mutableStateOf(false)
    private var isWorking by mutableStateOf(false)
    private var isRefreshing by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)

    /** Read during composition: every snapshot field above is tracked. */
    val uiState: CommunityEditorUiState
        get() = CommunityEditorUiState(
            community = community,
            fields = fields,
            isSaving = isSaving,
            justSaved = justSaved,
            isWorking = isWorking,
            isRefreshing = isRefreshing,
            errorMessage = errorMessage,
        )

    init {
        // iOS `.task { loadFromSession() }`.
        loadFromSession()
        // iOS `.onChange(of: session.myCommunity) { if !isDirty { loadFromSession() } }`:
        // photo add/delete and pull-to-refresh update the session; only mirror
        // that into the fields when nothing is typed-but-unsaved. "Typed" is
        // judged against the community the fields were loaded from, so a
        // refresh that brings remote changes still reaches untouched fields
        // (and a community that arrives late still fills an untouched form).
        viewModelScope.launch {
            sessionCommunity.collect { latest ->
                if (latest == community) return@collect
                val hasTypedEdits = fields.isDirty(community)
                community = latest
                if (!hasTypedEdits) loadFromSession()
            }
        }
    }

    fun updateFields(newFields: CommunityEditorFields) {
        fields = newFields
    }

    private fun loadFromSession() {
        val latest = sessionCommunity.value ?: return
        community = latest
        fields = CommunityEditorFields.from(latest)
    }

    fun save() {
        if (isSaving || fields.saveBlocker != null) return
        isSaving = true
        viewModelScope.launch {
            try {
                api.updateCommunity(fields.toUpdate())
                refreshProfile()
                loadFromSession()
                justSaved = true
                delay(SAVED_BADGE_MILLIS)
                justSaved = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = e.userMessage()
            } finally {
                isSaving = false
            }
        }
    }

    /** Pull-to-refresh → `session.refreshProfile()`; the collector above re-syncs the fields. */
    fun refresh() {
        if (isRefreshing) return
        isRefreshing = true
        viewModelScope.launch {
            try {
                refreshProfile()
            } finally {
                isRefreshing = false
            }
        }
    }

    fun addPhoto(image: Uri) = addPhoto { api.uploadCommunityPhoto(image) }

    /**
     * Upload, then `POST /api/communities/me/photos {storagePath, order: count}`,
     * then refresh the session. [upload] returns the storage path (an unreadable
     * image throws PhotoUploaderError.InvalidImage, shown as its message).
     */
    internal fun addPhoto(upload: suspend () -> String) {
        isWorking = true
        viewModelScope.launch {
            try {
                val storagePath = upload()
                val order = sessionCommunity.value?.photos?.size ?: 0
                api.confirmPhoto(storagePath, order)
                refreshProfile()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = e.userMessage()
            } finally {
                isWorking = false
            }
        }
    }

    /** No confirmation, like iOS. */
    fun deletePhoto(photo: Photo) {
        isWorking = true
        viewModelScope.launch {
            try {
                api.deletePhoto(photo.storagePath)
                refreshProfile()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = e.userMessage()
            } finally {
                isWorking = false
            }
        }
    }

    fun dismissError() {
        errorMessage = null
    }
}
