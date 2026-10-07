package app.drokpo.android.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.drokpo.android.core.ApiError
import app.drokpo.android.core.model.CommunityOnboardingIn
import app.drokpo.android.core.model.CommunityPhotoConfirm
import app.drokpo.android.core.model.CommunityProfile
import app.drokpo.android.core.model.CommunityUpdate
import app.drokpo.android.features.communityonboarding.COMMUNITY_ALREADY_EXISTS
import app.drokpo.android.features.communityonboarding.CommunityOnboardingContent
import app.drokpo.android.features.communityonboarding.CommunityOnboardingForm
import app.drokpo.android.features.communityonboarding.CommunityOnboardingModel
import app.drokpo.android.features.communityonboarding.CommunityOnboardingService
import app.drokpo.android.features.communityonboarding.CommunityOnboardingStep
import app.drokpo.android.features.communityonboarding.CommunityOnboardingUiState
import app.drokpo.android.features.communityonboarding.MAX_COMMUNITY_PHOTOS
import app.drokpo.android.features.communityonboarding.PickedPhoto
import app.drokpo.android.features.communityonboarding.photoLoadFailureMessage
import kotlinx.coroutines.delay

// Group 3 (communityonboarding): every step of "Register your community" and its
// meaningful states, rendered through CommunityOnboardingContent with fixtures —
// no network, Firebase or AppGraph. Text fields in the static entries are
// editable locally so the keyboard/IME behaviour can be checked too.

val communityOnboardingCatalogEntries: List<CatalogEntry> = listOf(
    CatalogEntry("communityonboarding.basics.empty", "Basics — empty (Continue disabled)") {
        OnboardingPreview(CommunityOnboardingUiState())
    },
    CatalogEntry("communityonboarding.basics.filled", "Basics — filled") {
        OnboardingPreview(state(CommunityOnboardingStep.Basics, basicsOnly))
    },
    CatalogEntry("communityonboarding.contact.empty", "Contact — empty (email required, Continue disabled)") {
        OnboardingPreview(state(CommunityOnboardingStep.Contact, basicsOnly))
    },
    CatalogEntry("communityonboarding.contact.filled", "Contact — website, phone, email and socials") {
        OnboardingPreview(state(CommunityOnboardingStep.Contact, filledForm))
    },
    CatalogEntry("communityonboarding.contact.invalidemail", "Contact — email without @ (Continue disabled)") {
        OnboardingPreview(state(CommunityOnboardingStep.Contact, filledForm.copy(email = "hello.example.org")))
    },
    CatalogEntry("communityonboarding.contactperson.empty", "Contact person — empty (name required)") {
        OnboardingPreview(state(CommunityOnboardingStep.ContactPerson, throughContact))
    },
    CatalogEntry("communityonboarding.contactperson.filled", "Contact person — filled") {
        OnboardingPreview(state(CommunityOnboardingStep.ContactPerson, filledForm))
    },
    CatalogEntry("communityonboarding.address.empty", "Address — empty (city and country required)") {
        OnboardingPreview(state(CommunityOnboardingStep.Address, throughContactPerson))
    },
    CatalogEntry("communityonboarding.address.filled", "Address — filled") {
        OnboardingPreview(state(CommunityOnboardingStep.Address, filledForm))
    },
    CatalogEntry("communityonboarding.address.submitting", "Address — creating the community (spinner)") {
        OnboardingPreview(state(CommunityOnboardingStep.Address, filledForm, isSubmitting = true))
    },
    CatalogEntry("communityonboarding.address.error", "Address — backend validation error alert") {
        OnboardingPreview(
            state(
                CommunityOnboardingStep.Address,
                filledForm.copy(website = "tibetan-toronto.example.org"),
                errorMessage = "Value error, must be an https:// URL",
            ),
        )
    },
    CatalogEntry("communityonboarding.address.offline", "Address — offline error alert") {
        OnboardingPreview(
            state(
                CommunityOnboardingStep.Address,
                filledForm,
                errorMessage = "The Internet connection appears to be offline.",
            ),
        )
    },
    CatalogEntry("communityonboarding.photos.empty", "Photos — none picked (Finish still enabled)") {
        OnboardingPreview(state(CommunityOnboardingStep.Photos, filledForm))
    },
    CatalogEntry("communityonboarding.photos.one", "Photos — logo only") {
        OnboardingPreview(state(CommunityOnboardingStep.Photos, filledForm, photos = pickedPhotos(1)))
    },
    CatalogEntry("communityonboarding.photos.some", "Photos — 4 picked") {
        OnboardingPreview(state(CommunityOnboardingStep.Photos, filledForm, photos = pickedPhotos(4)))
    },
    CatalogEntry("communityonboarding.photos.full", "Photos — 6 picked (no + tile)") {
        OnboardingPreview(state(CommunityOnboardingStep.Photos, filledForm, photos = pickedPhotos(MAX_COMMUNITY_PHOTOS)))
    },
    CatalogEntry("communityonboarding.photos.submitting", "Photos — uploading on Finish (spinner, grid locked)") {
        OnboardingPreview(
            state(CommunityOnboardingStep.Photos, filledForm, photos = pickedPhotos(3), isSubmitting = true),
        )
    },
    CatalogEntry("communityonboarding.photos.loadfailed.one", "Photos — one pick couldn't be loaded") {
        OnboardingPreview(
            state(
                CommunityOnboardingStep.Photos,
                filledForm,
                photos = pickedPhotos(2),
                errorMessage = photoLoadFailureMessage(1),
            ),
        )
    },
    CatalogEntry("communityonboarding.photos.loadfailed.many", "Photos — three picks couldn't be loaded") {
        OnboardingPreview(
            state(
                CommunityOnboardingStep.Photos,
                filledForm,
                photos = pickedPhotos(1),
                errorMessage = photoLoadFailureMessage(3),
            ),
        )
    },
    CatalogEntry("communityonboarding.photos.uploaderror", "Photos — upload failed mid-batch (retry resumes)") {
        OnboardingPreview(
            state(
                CommunityOnboardingStep.Photos,
                filledForm,
                photos = pickedPhotos(3),
                errorMessage = "That photo couldn't be processed. Try a different one.",
            ),
        )
    },
    CatalogEntry("communityonboarding.flow.interactive", "Interactive walkthrough (fake backend)") {
        InteractiveFlow()
    },
)

/** Fully filled form, taken from the shared community fixture. */
private val filledForm: CommunityOnboardingForm = Fixtures.community.toForm()

private val basicsOnly = CommunityOnboardingForm(
    name = filledForm.name,
    communityDescription = filledForm.communityDescription,
)

private val throughContact = basicsOnly.copy(
    website = filledForm.website,
    phone = filledForm.phone,
    email = filledForm.email,
    instagram = filledForm.instagram,
    youtube = filledForm.youtube,
)

private val throughContactPerson = throughContact.copy(
    contactName = filledForm.contactName,
    contactRole = filledForm.contactRole,
)

private fun CommunityProfile.toForm() = CommunityOnboardingForm(
    name = name.orEmpty(),
    communityDescription = description.orEmpty(),
    website = website.orEmpty(),
    phone = phone.orEmpty(),
    email = email.orEmpty(),
    instagram = socials?.instagram.orEmpty(),
    youtube = socials?.youtube.orEmpty(),
    tiktok = socials?.tiktok.orEmpty(),
    facebook = socials?.facebook.orEmpty(),
    contactName = contactPerson?.name.orEmpty(),
    contactRole = contactPerson?.role.orEmpty(),
    contactPhone = contactPerson?.phone.orEmpty(),
    contactEmail = contactPerson?.email.orEmpty(),
    line1 = address?.line1.orEmpty(),
    city = address?.city.orEmpty(),
    state = address?.state.orEmpty(),
    country = address?.country.orEmpty(),
    postalCode = address?.postalCode.orEmpty(),
)

private fun pickedPhotos(count: Int): List<PickedPhoto> =
    (0 until count).map { PickedPhoto(key = it.toLong(), uri = Fixtures.imageUrl("drokpo-community-pick-$it")) }

private fun state(
    step: CommunityOnboardingStep,
    form: CommunityOnboardingForm,
    photos: List<PickedPhoto> = emptyList(),
    isSubmitting: Boolean = false,
    errorMessage: String? = null,
) = CommunityOnboardingUiState(step, form, photos, isSubmitting, errorMessage)

/**
 * A static state whose text fields (and photo removal / alert dismissal) still
 * respond locally, so typing and the IME can be checked; navigation is inert.
 */
@Composable
private fun OnboardingPreview(initial: CommunityOnboardingUiState) {
    var state by remember { mutableStateOf(initial) }
    CommunityOnboardingContent(
        state = state,
        onFormChange = { transform -> state = state.copy(form = state.form.transform()) },
        onBack = {},
        onSignOut = {},
        onAdvance = {},
        onAddPhotos = {},
        onRemovePhoto = { photo -> state = state.copy(photos = state.photos - photo) },
        onDismissError = { state = state.copy(errorMessage = null) },
    )
}

/**
 * The real CommunityOnboardingModel driven by an in-memory backend: walk every
 * step, validation, the create → Back → PATCH path, photo picks ("+" adds a
 * fixture photo; every third pick "fails to load") and Finish. Held in a
 * private ViewModelStore that is cleared when the entry closes.
 */
@Composable
private fun InteractiveFlow() {
    val store = remember { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    val model = remember(store) {
        val factory = viewModelFactory {
            initializer { CommunityOnboardingModel(service = CatalogOnboardingService(), refreshSession = { delay(600) }) }
        }
        ViewModelProvider.create(store, factory)[CommunityOnboardingModel::class]
    }
    var picks by remember { mutableIntStateOf(0) }
    val state = model.uiState
    BackHandler(enabled = state.step != CommunityOnboardingStep.Basics) { model.back() }
    CommunityOnboardingContent(
        state = state,
        onFormChange = model::updateForm,
        onBack = model::back,
        onSignOut = {},
        onAdvance = model::advance,
        onAddPhotos = {
            val batch = (0 until minOf(2, state.remainingPhotoSlots)).map { offset ->
                val n = picks + offset
                if (n % 3 == 2) "broken://pick-$n" else Fixtures.imageUrl("drokpo-community-pick-$n")
            }
            picks += batch.size
            model.addPickedPhotos(batch)
        },
        onRemovePhoto = model::removePhoto,
        onDismissError = model::dismissError,
    )
}

/** In-memory backend: short delays, POST once (then 409 like the real API), "broken://" picks don't load. */
private class CatalogOnboardingService : CommunityOnboardingService {
    private var created = false

    override suspend fun createCommunity(body: CommunityOnboardingIn) {
        delay(800)
        if (created) throw ApiError.Http(409, COMMUNITY_ALREADY_EXISTS)
        created = true
    }

    override suspend fun updateCommunity(body: CommunityUpdate) {
        delay(800)
    }

    override suspend fun uploadPhoto(uri: String): String {
        delay(500)
        return "communities/fixture-me/photos/${uri.hashCode()}.jpg"
    }

    override suspend fun confirmPhoto(body: CommunityPhotoConfirm) {
        delay(150)
    }

    override suspend fun deletePhoto(storagePath: String) {
        delay(150)
    }

    override suspend fun canLoadPhoto(uri: String): Boolean {
        delay(100)
        return !uri.startsWith("broken://")
    }
}
