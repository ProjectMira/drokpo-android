package app.drokpo.android.features.communityonboarding

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.drokpo.android.core.ApiClient
import app.drokpo.android.core.ApiError
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.EmptyResponse
import app.drokpo.android.core.PhotoUploader
import app.drokpo.android.core.model.CommunityAddress
import app.drokpo.android.core.model.CommunityOnboardingIn
import app.drokpo.android.core.model.CommunityPhotoConfirm
import app.drokpo.android.core.model.CommunityUpdate
import app.drokpo.android.core.model.ContactPerson
import app.drokpo.android.core.model.Socials
import app.drokpo.android.core.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Port of CommunityOnboardingModel.swift (+ the photo-loading half of
// CommunityPhotosStep, which iOS keeps in the view).

/** The five screens of the flow, in order (iOS `Step: Int, CaseIterable`). */
internal enum class CommunityOnboardingStep {
    Basics, Contact, ContactPerson, Address, Photos;

    val previous: CommunityOnboardingStep? get() = entries.getOrNull(ordinal - 1)
    val next: CommunityOnboardingStep? get() = entries.getOrNull(ordinal + 1)
}

/** At most this many photos can be picked (iOS `pickedImages.count < 6`). */
internal const val MAX_COMMUNITY_PHOTOS = 6

/** Every text field the flow collects, exactly as typed (trimming happens when the body is built). */
@Immutable
internal data class CommunityOnboardingForm(
    // Basics
    val name: String = "",
    val communityDescription: String = "",
    // Contact
    val website: String = "",
    val phone: String = "",
    val email: String = "",
    val instagram: String = "",
    val youtube: String = "",
    val tiktok: String = "",
    val facebook: String = "",
    // Contact person
    val contactName: String = "",
    val contactRole: String = "",
    val contactPhone: String = "",
    val contactEmail: String = "",
    // Address
    val line1: String = "",
    val city: String = "",
    val state: String = "",
    val country: String = "",
    val postalCode: String = "",
) {
    fun canAdvance(step: CommunityOnboardingStep): Boolean = when (step) {
        CommunityOnboardingStep.Basics -> name.trimmed().isNotEmpty() && communityDescription.trimmed().isNotEmpty()
        // The backend requires a community email (the verification
        // outcome is mailed there); everything else is optional.
        CommunityOnboardingStep.Contact -> email.trimmed().contains("@")
        CommunityOnboardingStep.ContactPerson -> contactName.trimmed().isNotEmpty()
        CommunityOnboardingStep.Address -> city.trimmed().isNotEmpty() && country.trimmed().isNotEmpty()
        CommunityOnboardingStep.Photos -> true // no photo is required to finish
    }

    /** POST /api/communities/onboarding: required fields trimmed, empty optionals omitted (null). */
    fun toOnboardingIn(): CommunityOnboardingIn = CommunityOnboardingIn(
        name = name.trimmed(),
        description = communityDescription.trimmed(),
        website = website.nonEmpty(),
        phone = phone.nonEmpty(),
        email = email.trimmed(),
        contactPerson = ContactPerson(
            name = contactName.trimmed(),
            role = contactRole.nonEmpty(),
            phone = contactPhone.nonEmpty(),
            email = contactEmail.nonEmpty(),
        ),
        address = CommunityAddress(
            line1 = line1.nonEmpty(),
            city = city.trimmed(),
            state = state.nonEmpty(),
            country = country.trimmed(),
            postalCode = postalCode.nonEmpty(),
        ),
        socials = Socials(
            instagram = instagram.nonEmpty(),
            youtube = youtube.nonEmpty(),
            tiktok = tiktok.nonEmpty(),
            facebook = facebook.nonEmpty(),
        ),
    )

    /**
     * PATCH /api/communities/me after the community already exists. Every
     * field goes up trimmed — optional fields as "" when emptied (the backend
     * clears them).
     */
    fun toUpdate(): CommunityUpdate = CommunityUpdate(
        name = name.trimmed(),
        description = communityDescription.trimmed(),
        website = website.trimmed(),
        phone = phone.trimmed(),
        email = email.trimmed(),
        contactPerson = ContactPerson(
            name = contactName.trimmed(),
            role = contactRole.trimmed(),
            phone = contactPhone.trimmed(),
            email = contactEmail.trimmed(),
        ),
        address = CommunityAddress(
            line1 = line1.trimmed(),
            city = city.trimmed(),
            state = state.trimmed(),
            country = country.trimmed(),
            postalCode = postalCode.trimmed(),
        ),
        socials = Socials(
            instagram = instagram.trimmed(),
            youtube = youtube.trimmed(),
            tiktok = tiktok.trimmed(),
            facebook = facebook.trimmed(),
        ),
    )
}

/**
 * One picked photo. [uri] is the photo picker's content Uri as a string (any
 * Coil model string renders, which lets the debug catalog use https
 * fixtures). [key] is unique per pick — the stand-in for iOS's per-UIImage
 * `ObjectIdentifier`, so picking the same picture twice gives two photos.
 */
@Immutable
internal data class PickedPhoto(val key: Long, val uri: String)

/** Everything [CommunityOnboardingContent] renders. */
@Immutable
internal data class CommunityOnboardingUiState(
    val step: CommunityOnboardingStep = CommunityOnboardingStep.Basics,
    val form: CommunityOnboardingForm = CommunityOnboardingForm(),
    val photos: List<PickedPhoto> = emptyList(),
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
) {
    val canAdvance: Boolean get() = form.canAdvance(step)

    /** iOS `ProgressView(value: step + 1, total: Step.allCases.count)`. */
    val progress: Float get() = (step.ordinal + 1).toFloat() / CommunityOnboardingStep.entries.size

    /** How many more photos the picker may return (0 hides the "+" tile). */
    val remainingPhotoSlots: Int get() = (MAX_COMMUNITY_PHOTOS - photos.size).coerceAtLeast(0)
}

/** iOS's alert copy after picked items fail to load; null when nothing failed. */
internal fun photoLoadFailureMessage(failed: Int): String? = when {
    failed <= 0 -> null
    failed == 1 -> "One photo couldn't be loaded — try picking it again."
    else -> "$failed photos couldn't be loaded — try picking them again."
}

/**
 * The backend's 409 `detail` when POST /api/communities/onboarding finds the
 * community doc already there (routers/communities.py). The other 409 on that
 * route, "This account is already registered as a person", is a real error.
 */
internal const val COMMUNITY_ALREADY_EXISTS = "Community already exists"

/** Swift `trimmingCharacters(in: .whitespacesAndNewlines)`. */
internal fun String.trimmed(): String = trim()

private fun String.nonEmpty(): String? = trimmed().ifEmpty { null }

/** The flow's side effects, injectable so JVM tests run against fakes. */
internal interface CommunityOnboardingService {
    /** POST /api/communities/onboarding. */
    suspend fun createCommunity(body: CommunityOnboardingIn)

    /** PATCH /api/communities/me. */
    suspend fun updateCommunity(body: CommunityUpdate)

    /** Downscale + upload to communities/{uid}/photos/… and return the storage path. */
    suspend fun uploadPhoto(uri: String): String

    /** POST /api/communities/me/photos. */
    suspend fun confirmPhoto(body: CommunityPhotoConfirm)

    /** DELETE /api/communities/me/photos?storage_path=… (removes the doc entry and the blob; idempotent). */
    suspend fun deletePhoto(storagePath: String)

    /** iOS `loadTransferable(type: Data.self)` + `UIImage(data:)`: can this pick be read as an image? */
    suspend fun canLoadPhoto(uri: String): Boolean
}

internal object DefaultCommunityOnboardingService : CommunityOnboardingService {
    override suspend fun createCommunity(body: CommunityOnboardingIn) {
        ApiClient.post<EmptyResponse>("/api/communities/onboarding", body)
    }

    override suspend fun updateCommunity(body: CommunityUpdate) {
        ApiClient.patch<EmptyResponse>("/api/communities/me", body)
    }

    override suspend fun uploadPhoto(uri: String): String = PhotoUploader.uploadCommunityPhoto(uri.toUri())

    override suspend fun confirmPhoto(body: CommunityPhotoConfirm) {
        ApiClient.post<EmptyResponse>("/api/communities/me/photos", body)
    }

    override suspend fun deletePhoto(storagePath: String) {
        ApiClient.delete<EmptyResponse>("/api/communities/me/photos", listOf("storage_path" to storagePath))
    }

    /**
     * Reads just the image header — enough to reject what PhotoUploader could
     * never decode (an unreadable or revoked Uri, a format this Android version
     * doesn't support) at pick time, the way iOS fails `UIImage(data:)`, instead
     * of at Finish.
     */
    override suspend fun canLoadPhoto(uri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val opened = AppGraph.app.contentResolver.openInputStream(uri.toUri())?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
                true
            } ?: false
            opened && bounds.outWidth > 0 && bounds.outHeight > 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Port of `CommunityOnboardingModel` (CONTRACT §B.3 / §F.3). Branch-scoped by
 * RootScreen, so it lives exactly as long as the NeedsCommunityOnboarding state.
 *
 * State is Compose snapshot state rather than a StateFlow: it backs text
 * fields, and a StateFlow round trip between `onValueChange` and `value`
 * makes fast typing drop characters / jump the cursor.
 */
internal class CommunityOnboardingModel(
    private val service: CommunityOnboardingService = DefaultCommunityOnboardingService,
    /** iOS `if model.completed { await session.refreshProfile() }` — hands control back to SessionStore. */
    private val refreshSession: suspend () -> Unit = { AppGraph.session.refreshProfile() },
) : ViewModel() {

    var uiState by mutableStateOf(CommunityOnboardingUiState())
        private set

    /**
     * True once POST /api/communities/onboarding (and any picked photos)
     * succeed; the session refresh then routes away from this flow.
     */
    var completed: Boolean = false
        private set

    /**
     * Set once POST /api/communities/onboarding succeeds — the doc now
     * exists, so going Back and Continuing again must PATCH, never re-POST
     * (that would 409).
     */
    private var communityCreated = false

    /**
     * Photos already uploaded+confirmed with the backend (pick key → storage
     * path), tracked by pick identity — positional counting breaks as soon as
     * the user edits the grid between a partial failure and the retry. The
     * path lets Finish delete a confirmed photo the user has since removed.
     */
    private val confirmedPhotos = mutableMapOf<Long, String>()
    private var nextPhotoKey = 0L

    /**
     * Picks still being read (one per picker result; each yields its failure
     * count). Finish waits for the running ones so a photo that is still
     * loading when it's tapped is uploaded, not silently dropped.
     */
    private val photoLoads = mutableListOf<Deferred<Int>>()

    fun updateForm(transform: CommunityOnboardingForm.() -> CommunityOnboardingForm) {
        uiState = uiState.copy(form = uiState.form.transform())
    }

    fun dismissError() {
        uiState = uiState.copy(errorMessage = null)
    }

    /** Toolbar "Back" and system back. iOS disables the button while submitting. */
    fun back() {
        val current = uiState
        if (current.isSubmitting) return
        current.step.previous?.let { uiState = current.copy(step = it) }
    }

    /**
     * Leaving the address step creates the community on the backend (all
     * text fields collected so far); leaving the photos step just finishes —
     * any picked photos upload first. If the user goes Back after the
     * community was created and comes forward again, the edits are saved
     * with a PATCH instead of re-POSTing onboarding (which would 409).
     */
    fun advance() {
        val current = uiState
        if (!current.canAdvance || current.isSubmitting) return
        when (current.step) {
            CommunityOnboardingStep.Address -> submit {
                if (communityCreated) updateCommunity() else createCommunity()
            }
            CommunityOnboardingStep.Photos -> submit { uploadPhotosAndFinish() }
            else -> current.step.next?.let { uiState = current.copy(step = it) }
        }
    }

    /**
     * Appends the picker's results in pick order, skipping the ones that
     * can't be read as images, then reports how many failed (iOS
     * `onChange(of: selection)`).
     */
    fun addPickedPhotos(uris: List<String>) {
        if (uris.isEmpty()) return
        photoLoads.removeAll { it.isCompleted }
        photoLoads += viewModelScope.async {
            var failed = 0
            for (uri in uris) {
                val loaded = try {
                    service.canLoadPhoto(uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                when {
                    !loaded -> failed += 1
                    // The legacy (pre-photo-picker) document fallback ignores the
                    // selection limit the "+" tile asked for; never exceed six.
                    uiState.photos.size >= MAX_COMMUNITY_PHOTOS -> Unit
                    else -> uiState = uiState.copy(photos = uiState.photos + PickedPhoto(nextPhotoKey++, uri))
                }
            }
            photoLoadFailureMessage(failed)?.let { uiState = uiState.copy(errorMessage = it) }
            failed
        }
    }

    fun removePhoto(photo: PickedPhoto) {
        uiState = uiState.copy(photos = uiState.photos.filterNot { it.key == photo.key })
    }

    private fun submit(block: suspend () -> Unit) {
        uiState = uiState.copy(isSubmitting = true)
        viewModelScope.launch {
            try {
                block()
            } finally {
                uiState = uiState.copy(isSubmitting = false)
            }
        }
    }

    private suspend fun createCommunity() {
        try {
            service.createCommunity(uiState.form.toOnboardingIn())
            communityCreated = true
            uiState = uiState.copy(step = CommunityOnboardingStep.Photos)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError.Http) {
            if (e.status == 409 && e.message == COMMUNITY_ALREADY_EXISTS) {
                // An earlier POST landed but its response was lost (timeout,
                // dropped connection): the community exists, so save the form
                // over it the way a second pass does. iOS errors out here on
                // every retry until relaunch.
                communityCreated = true
                updateCommunity()
            } else {
                uiState = uiState.copy(errorMessage = e.userMessage())
            }
        } catch (e: Exception) {
            uiState = uiState.copy(errorMessage = e.userMessage())
        }
    }

    /**
     * Second pass over the address step after the community already exists:
     * persist whatever the user changed on the earlier steps via PATCH.
     * Optional fields go up as "" when emptied (the backend clears them).
     */
    private suspend fun updateCommunity() {
        try {
            service.updateCommunity(uiState.form.toUpdate())
            uiState = uiState.copy(step = CommunityOnboardingStep.Photos)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            uiState = uiState.copy(errorMessage = e.userMessage())
        }
    }

    private suspend fun uploadPhotosAndFinish() {
        try {
            // Picks still loading belong in the upload (the grid is locked from
            // here on, so nothing new can start). If one of them fails, stop:
            // its alert says "try picking it again", which Finish would skip.
            val loadFailures = photoLoads.filter { it.isActive }.awaitAll().sum()
            if (loadFailures > 0) return

            // Snapshot: `order` is the photo's grid position once loads are in.
            val photos = uiState.photos
            // A photo confirmed by an earlier, partly failed Finish and then
            // removed from the grid would otherwise stay on the community
            // (as its logo, if it was first) and use up one of the six slots.
            val keys = photos.mapTo(HashSet()) { it.key }
            for ((key, storagePath) in confirmedPhotos.entries.filter { it.key !in keys }) {
                service.deletePhoto(storagePath)
                confirmedPhotos -= key
            }
            for ((index, photo) in photos.withIndex()) {
                if (photo.key in confirmedPhotos) continue
                val storagePath = service.uploadPhoto(photo.uri)
                service.confirmPhoto(CommunityPhotoConfirm(storagePath = storagePath, order = index))
                confirmedPhotos[photo.key] = storagePath
            }
            completed = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            uiState = uiState.copy(errorMessage = e.userMessage())
            return
        }
        // The spinner stays up while SessionStore re-routes (iOS re-enabled
        // "Finish" for that moment, inviting a second tap).
        try {
            refreshSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            uiState = uiState.copy(errorMessage = e.userMessage())
        }
    }
}
