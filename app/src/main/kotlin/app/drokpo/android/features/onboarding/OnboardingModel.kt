package app.drokpo.android.features.onboarding

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
import app.drokpo.android.core.model.GeoLocation
import app.drokpo.android.core.model.OnboardingIn
import app.drokpo.android.core.model.Photo
import app.drokpo.android.core.model.PhotoConfirm
import app.drokpo.android.core.model.Preferences
import app.drokpo.android.core.model.Profile
import app.drokpo.android.core.model.ProfileUpdate
import app.drokpo.android.core.model.Socials
import app.drokpo.android.core.model.Vocabulary
import app.drokpo.android.core.userMessage
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Port of OnboardingModel.swift. The iOS @Observable class becomes an
// immutable [OnboardingState] (rendered by OnboardingContent, buildable from
// fixtures), a pure reducer for the form edits, and [OnboardingModel] — the
// ViewModel that owns the network side effects.

/** The six screens of person onboarding, in order (iOS `OnboardingModel.Step`). */
internal enum class OnboardingStep { Basics, Details, AboutYou, Socials, Location, Photos }

/**
 * One photo picked in the Photos step. [uri] is the content:// string the
 * system photo picker returned (any Coil model string in the catalog).
 */
@Immutable
internal data class PickedPhoto(
    /** Stable list key (pick order counter), so removing a tile never re-binds another's image. */
    val id: Long,
    val uri: String,
    /** Storage path once uploaded, so a retry re-confirms instead of uploading a second copy. */
    val storagePath: String? = null,
    /** POST /api/onboarding/photos/confirm accepted it — never upload or confirm it again. */
    val confirmed: Boolean = false,
)

@Immutable
internal data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.Basics,

    // Basics
    val displayName: String = "",
    /** Date of birth as the calendar day the picker shows. */
    val dob: LocalDate,
    /** Latest selectable birthday: today − 18 years (iOS `latestAllowedDOB`). */
    val latestAllowedDob: LocalDate,
    /** "" until chosen, else a [Vocabulary.genders] value ("male" / "female"). */
    val gender: String = "",

    // Details
    val region: String = "",
    val languages: Set<String> = emptySet(),
    val interests: Set<String> = emptySet(),
    val bio: String = "",

    // About you — work, study, and the optional friendship prompts.
    val occupation: String = "",
    val education: String = "",
    val answers: Map<String, String> = emptyMap(),

    // Socials
    val instagram: String = "",
    val acceptedTerms: Boolean = false,

    // Location
    val location: GeoLocation? = null,

    // Photos
    val photos: List<PickedPhoto> = emptyList(),
    val nextPhotoId: Long = 0,
    /**
     * Storage paths of photos the person removed after they were uploaded
     * (and maybe already attached to the profile, mid-batch). Finish deletes
     * them first, so a removed photo neither stays on the profile — where an
     * early one would remain the main photo — nor counts toward the six.
     */
    val pendingPhotoDeletes: List<String> = emptyList(),

    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    /**
     * True once POST /api/onboarding/complete succeeds; the model then hands
     * control back to SessionStore (refreshProfile) so RootScreen routes into
     * the main app.
     */
    val completed: Boolean = false,
) {
    val canAdvance: Boolean
        get() = when (step) {
            OnboardingStep.Basics -> displayName.trim().isNotEmpty() && gender.isNotEmpty()
            OnboardingStep.Details -> region.isNotEmpty() && languages.isNotEmpty()
            OnboardingStep.AboutYou -> true // every prompt is optional
            OnboardingStep.Socials -> acceptedTerms // Instagram is optional
            OnboardingStep.Location -> true // falls back to region coordinates
            // Over six only after photos already on the profile were merged
            // in (see withAttachedPhotos); the backend would refuse the extras.
            OnboardingStep.Photos -> photos.isNotEmpty() && photos.size <= MAX_PHOTOS
        }

    /** Linear progress: (step + 1) / 6. */
    val progress: Float
        get() = (step.ordinal + 1).toFloat() / OnboardingStep.entries.size

    /**
     * The handle without any "@" the person typed (the field already shows
     * one) and without surrounding spaces.
     */
    val trimmedInstagram: String
        get() = instagram.replace("@", "").trim()

    /** The "+" tile shows while fewer than [MAX_PHOTOS] are picked. */
    val canAddPhotos: Boolean
        get() = photos.size < MAX_PHOTOS

    val remainingPhotoSlots: Int
        get() = (MAX_PHOTOS - photos.size).coerceAtLeast(0)

    /** Answers trimmed, empty ones dropped (iOS `compactMapValues`). */
    val cleanedAnswers: Map<String, String>
        get() = answers.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }

    /** Profile.dobFormatter: "yyyy-MM-dd" in UTC. */
    val dobString: String
        get() = Profile.formatDob(dob.atStartOfDay(ZoneOffset.UTC).toInstant())

    /** POST /api/onboarding body. */
    fun toOnboardingIn(): OnboardingIn = OnboardingIn(
        displayName = displayName.trim(),
        dob = dobString,
        gender = gender.ifEmpty { null },
        bio = bio,
        occupation = occupation.trim(),
        education = education,
        region = region,
        // iOS sends `Array(Set)` (arbitrary order); vocabulary order keeps the
        // profile's chips stable instead.
        languages = Vocabulary.languages.filter { it in languages } + (languages - Vocabulary.languages.toSet()),
        interests = Vocabulary.interests.filter { it in interests } + (interests - Vocabulary.interests.toSet()),
        answers = cleanedAnswers,
        socials = Socials(instagram = trimmedInstagram.ifEmpty { null }),
        location = resolvedLocation,
        preferences = Preferences(),
    )

    /**
     * Same fields as [toOnboardingIn] as a PATCH /api/profile/me body, for a
     * profile that already exists (see [OnboardingModel.createProfile]).
     * An emptied Instagram goes up as "" so the backend clears it.
     */
    fun toProfileUpdate(): ProfileUpdate {
        val body = toOnboardingIn()
        return ProfileUpdate(
            displayName = body.displayName,
            bio = body.bio,
            dob = body.dob,
            gender = body.gender,
            occupation = body.occupation,
            education = body.education,
            region = body.region,
            languages = body.languages,
            interests = body.interests,
            answers = body.answers,
            socials = Socials(instagram = trimmedInstagram),
            location = body.location,
        )
    }

    /** The fix, else the centre of the chosen region, else (0, 0). */
    val resolvedLocation: GeoLocation
        get() = location ?: Vocabulary.regionCoordinates[region] ?: GeoLocation(lat = 0.0, lng = 0.0)

    companion object {
        const val MAX_PHOTOS = 6

        /** A fresh form: birthday defaults to today − 25 years. */
        fun initial(today: LocalDate): OnboardingState = OnboardingState(
            dob = today.minusYears(25),
            latestAllowedDob = today.minusYears(18),
        )
    }
}

/** The form edits OnboardingContent reports. All pure — see [reduce]. */
internal sealed interface OnboardingEdit {
    data class DisplayName(val value: String) : OnboardingEdit
    data class Dob(val value: LocalDate) : OnboardingEdit
    data class Gender(val value: String) : OnboardingEdit
    data class Region(val value: String) : OnboardingEdit
    data class ToggleLanguage(val value: String) : OnboardingEdit
    data class ToggleInterest(val value: String) : OnboardingEdit
    data class Bio(val value: String) : OnboardingEdit
    data class Occupation(val value: String) : OnboardingEdit
    data class Education(val value: String) : OnboardingEdit
    data class Answer(val key: String, val value: String) : OnboardingEdit
    data class Instagram(val value: String) : OnboardingEdit
    data class AcceptedTerms(val value: Boolean) : OnboardingEdit

    /** Picked from the system photo picker, in pick order. */
    data class AddPhotos(val uris: List<String>) : OnboardingEdit
    data class RemovePhoto(val id: Long) : OnboardingEdit
}

/** Applies one form edit. */
internal fun OnboardingState.reduce(edit: OnboardingEdit): OnboardingState = when (edit) {
    is OnboardingEdit.DisplayName -> copy(displayName = edit.value)
    // The picker can't go past the 18+ bound; clamp anyway so no other path can.
    is OnboardingEdit.Dob -> copy(dob = minOf(edit.value, latestAllowedDob))
    is OnboardingEdit.Gender -> copy(gender = edit.value)
    is OnboardingEdit.Region -> copy(region = edit.value)
    is OnboardingEdit.ToggleLanguage -> copy(languages = languages.toggled(edit.value))
    is OnboardingEdit.ToggleInterest -> copy(interests = interests.toggled(edit.value))
    is OnboardingEdit.Bio -> copy(bio = edit.value)
    is OnboardingEdit.Occupation -> copy(occupation = edit.value)
    is OnboardingEdit.Education -> copy(education = edit.value)
    is OnboardingEdit.Answer -> copy(answers = answers + (edit.key to edit.value))
    is OnboardingEdit.Instagram -> copy(instagram = edit.value)
    is OnboardingEdit.AcceptedTerms -> copy(acceptedTerms = edit.value)
    is OnboardingEdit.AddPhotos -> {
        // The system picker enforces the limit, but its pre-Android-11
        // fallback (document picker) doesn't — never go past 6.
        val added = edit.uris.take(remainingPhotoSlots).mapIndexed { index, uri ->
            PickedPhoto(id = nextPhotoId + index, uri = uri)
        }
        copy(photos = photos + added, nextPhotoId = nextPhotoId + added.size)
    }
    is OnboardingEdit.RemovePhoto -> {
        val removed = photos.firstOrNull { it.id == edit.id }
        copy(
            photos = photos.filterNot { it.id == edit.id },
            pendingPhotoDeletes = removed?.storagePath
                ?.takeIf { it !in pendingPhotoDeletes }
                ?.let { pendingPhotoDeletes + it }
                ?: pendingPhotoDeletes,
        )
    }
}

/**
 * Merges in the photos already attached to an existing profile (GET
 * /api/profile/me), for the 409 path of [OnboardingModel.createProfile]: an
 * earlier run (since killed, or signed out) may have confirmed some before
 * Finish failed. They lead the list as confirmed — the backend appends each
 * confirm, so they really are photos[0…] — count toward the six and can be
 * removed like any other; the photos not on the profile follow in pick order.
 * Photos the person already removed ([OnboardingState.pendingPhotoDeletes])
 * stay removed.
 */
internal fun OnboardingState.withAttachedPhotos(attached: List<Photo>): OnboardingState {
    val localByPath = photos.filter { it.storagePath != null }.associateBy { it.storagePath }
    var nextId = nextPhotoId
    val onProfile = attached
        .filter { it.storagePath !in pendingPhotoDeletes }
        .distinctBy { it.storagePath }
        .map { photo ->
            // Keep a local tile's id and picker URI, so its image doesn't reload.
            localByPath[photo.storagePath]?.copy(confirmed = true)
                ?: PickedPhoto(id = nextId++, uri = photo.url.orEmpty(), storagePath = photo.storagePath, confirmed = true)
        }
    val attachedPaths = onProfile.mapTo(HashSet()) { it.storagePath }
    // Anything else isn't on the profile (whatever this run thought): confirm it again.
    val notAttached = photos
        .filter { it.storagePath == null || it.storagePath !in attachedPaths }
        .map { if (it.confirmed) it.copy(confirmed = false) else it }
    return copy(photos = onProfile + notAttached, nextPhotoId = nextId)
}

/** iOS `back()`: the previous step, or stay on Basics. */
internal fun OnboardingState.previousStep(): OnboardingState =
    if (step.ordinal > 0) copy(step = OnboardingStep.entries[step.ordinal - 1]) else this

private fun Set<String>.toggled(value: String): Set<String> = if (value in this) this - value else this + value

/** The onboarding endpoints, behind an interface so JVM tests can fake them. */
internal interface OnboardingApi {
    /** POST /api/onboarding. */
    suspend fun createProfile(body: OnboardingIn)

    /** PATCH /api/profile/me. */
    suspend fun updateProfile(body: ProfileUpdate)

    /** POST /api/onboarding/photos/confirm. */
    suspend fun confirmPhoto(body: PhotoConfirm)

    /** POST /api/onboarding/complete. */
    suspend fun complete()

    /** DELETE /api/profile/me/photos — detaches the photo (if attached) and deletes its file. */
    suspend fun deletePhoto(storagePath: String)

    /** GET /api/profile/me → the photos already attached, in profile order. */
    suspend fun attachedPhotos(): List<Photo>

    object Live : OnboardingApi {
        override suspend fun createProfile(body: OnboardingIn) {
            ApiClient.post<EmptyResponse>("/api/onboarding", body)
        }

        override suspend fun updateProfile(body: ProfileUpdate) {
            ApiClient.patch<EmptyResponse>("/api/profile/me", body)
        }

        override suspend fun confirmPhoto(body: PhotoConfirm) {
            ApiClient.post<EmptyResponse>("/api/onboarding/photos/confirm", body)
        }

        override suspend fun complete() {
            ApiClient.post<EmptyResponse>("/api/onboarding/complete")
        }

        override suspend fun deletePhoto(storagePath: String) {
            ApiClient.delete<EmptyResponse>("/api/profile/me/photos", listOf("storage_path" to storagePath))
        }

        override suspend fun attachedPhotos(): List<Photo> =
            ApiClient.get<Profile>("/api/profile/me").photos.orEmpty()
    }
}

/**
 * Port of `OnboardingModel`. Lives in RootScreen's NeedsOnboarding branch
 * scope (CONTRACT.md §D.3), so a sign-out or a finished onboarding drops it.
 *
 * State is Compose snapshot state rather than a StateFlow: the form is
 * mostly text fields, and Compose requires their value to update
 * synchronously inside `onValueChange` (an asynchronously collected flow
 * makes fast typing drop or reorder characters).
 */
internal class OnboardingModel(
    private val api: OnboardingApi = OnboardingApi.Live,
    /** PhotoUploader.upload → storage path (users/{uid}/photos/{uuid}.jpg). */
    private val uploadPhoto: suspend (uri: String) -> String = { PhotoUploader.upload(it.toUri()) },
    /** Hands control back to SessionStore once onboarding has fully completed. */
    private val refreshProfile: suspend () -> Unit = { AppGraph.session.refreshProfile() },
    today: LocalDate = LocalDate.now(),
    /** The form saved before the process was killed (OnboardingFlowScreen), or null for a fresh one. */
    initial: OnboardingDraft? = null,
) : ViewModel() {
    var state: OnboardingState by mutableStateOf(
        initial?.let { OnboardingState.restoring(it, today) } ?: OnboardingState.initial(today),
    )
        private set

    fun onEdit(edit: OnboardingEdit) {
        // The tiles are disabled while Finish runs; whatever the source, never
        // change the list it is uploading.
        val photoEdit = edit is OnboardingEdit.AddPhotos || edit is OnboardingEdit.RemovePhoto
        if (photoEdit && state.isSubmitting) return
        state = state.reduce(edit)
    }

    /** Leading "Back" and system back: the previous step (the button is disabled while submitting). */
    fun back() {
        if (state.isSubmitting) return
        state = state.previousStep()
    }

    fun dismissError() {
        state = state.copy(errorMessage = null)
    }

    /**
     * Advances to the next step; leaving the location step creates the
     * profile on the backend so the photo confirm endpoint has something to
     * attach to, and Finish on the photos step uploads and completes.
     */
    fun advance(locationFetcher: LocationFetcher) {
        val current = state
        // The button is disabled in both cases; a double tap within one frame
        // must not start a second submission.
        if (!current.canAdvance || current.isSubmitting) return
        when (current.step) {
            OnboardingStep.Location -> {
                state = current.copy(isSubmitting = true)
                viewModelScope.launch {
                    // Continue leads straight into the system location dialog —
                    // the Allow/Don't Allow choice must live there, not on a
                    // button of our own (App Review guideline 5.1.1(iv)
                    // rejected a custom "Allow" button on this step). Denial
                    // falls back to region coordinates.
                    if (state.location == null) {
                        val fix = locationFetcher.requestLocation()
                        state = state.copy(location = fix)
                    }
                    createProfile()
                }
            }
            OnboardingStep.Photos -> {
                state = current.copy(isSubmitting = true)
                viewModelScope.launch { uploadPhotosAndComplete() }
            }
            else -> state = current.copy(step = OnboardingStep.entries[current.step.ordinal + 1])
        }
    }

    private suspend fun createProfile() {
        state = state.copy(isSubmitting = true)
        try {
            try {
                api.createProfile(state.toOnboardingIn())
            } catch (e: ApiError.Http) {
                // The profile already exists: this flow created it earlier
                // (Back from Photos, then Continue again) or a previous run
                // stopped before Finish, and the backend refuses a second POST.
                // Save the form over it instead of stranding the person here.
                if (!e.isProfileAlreadyExists) throw e
                api.updateProfile(state.toProfileUpdate())
                // Photos an earlier run confirmed are still on the profile:
                // show them, so they count toward the six and new confirms
                // follow them, instead of hiding them until the backend's
                // "Maximum of 6 photos allowed" blocks Finish.
                state = state.withAttachedPhotos(api.attachedPhotos())
            }
            state = state.copy(step = OnboardingStep.Photos)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state = state.copy(errorMessage = e.userMessage())
        } finally {
            state = state.copy(isSubmitting = false)
        }
    }

    /**
     * Uploads run in pick order, and each photo remembers its upload and
     * confirmation, so a retry after a mid-batch failure resumes from the
     * first unconfirmed photo instead of re-uploading (and duplicating) the
     * earlier ones. Photos removed after their upload are deleted first.
     */
    private suspend fun uploadPhotosAndComplete() {
        state = state.copy(isSubmitting = true)
        try {
            // Only the refresh is left when an earlier attempt completed.
            if (!state.completed) {
                for (path in state.pendingPhotoDeletes) {
                    api.deletePhoto(path)
                    state = state.copy(pendingPhotoDeletes = state.pendingPhotoDeletes - path)
                }
                // Add/remove are refused while submitting, so this is the list
                // the person sees; `order` is each photo's position in it.
                state.photos.forEachIndexed { index, photo ->
                    if (photo.confirmed) return@forEachIndexed
                    val storagePath = photo.storagePath ?: uploadPhoto(photo.uri).also { path ->
                        updatePhoto(photo.id) { it.copy(storagePath = path) }
                    }
                    api.confirmPhoto(PhotoConfirm(storagePath = storagePath, order = index))
                    updatePhoto(photo.id) { it.copy(confirmed = true) }
                }
                api.complete()
                state = state.copy(completed = true)
            }
            // Refresh so RootScreen routes into the main app once onboarding
            // has fully completed. The spinner deliberately stays up, through
            // the refresh and RootScreen's crossfade (this screen stays
            // composed and clickable while it fades out), so Finish can't be
            // tapped again.
            refreshProfile()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state = state.copy(isSubmitting = false, errorMessage = e.userMessage())
        }
    }

    private fun updatePhoto(id: Long, transform: (PickedPhoto) -> PickedPhoto) {
        state = state.copy(photos = state.photos.map { if (it.id == id) transform(it) else it })
    }
}

/** POST /api/onboarding's 409 for a person profile that already exists (backend routers/onboarding.py). */
private val ApiError.Http.isProfileAlreadyExists: Boolean
    get() = status == 409 && message == "Profile already exists"
