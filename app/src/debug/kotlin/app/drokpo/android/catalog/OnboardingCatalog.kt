package app.drokpo.android.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.drokpo.android.core.model.GeoLocation
import app.drokpo.android.core.model.Photo
import app.drokpo.android.core.model.Vocabulary
import app.drokpo.android.features.onboarding.DateOfBirthPickerDialog
import app.drokpo.android.features.onboarding.OnboardingContent
import app.drokpo.android.features.onboarding.OnboardingEdit
import app.drokpo.android.features.onboarding.OnboardingState
import app.drokpo.android.features.onboarding.OnboardingStep
import app.drokpo.android.features.onboarding.PickedPhoto
import app.drokpo.android.features.onboarding.ProfileQuestionFields
import app.drokpo.android.features.onboarding.previousStep
import app.drokpo.android.features.onboarding.reduce
import app.drokpo.android.features.onboarding.withAttachedPhotos
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.theme.DrokpoTheme
import java.time.LocalDate

// Group 2 (onboarding) owns this file (CONTRACT.md §E). Every entry renders
// OnboardingContent from a fixture OnboardingState — no ViewModel, network or
// AppGraph. Form edits run through the real reducer, so each entry is also
// interactive (type, toggle, pick); "Continue" only moves between steps.

/** Today, so the 18+ / default-25 birthday bounds match what the app shows. */
private val today: LocalDate = LocalDate.now()

private val blank: OnboardingState = OnboardingState.initial(today)

private val basicsFilled: OnboardingState = blank.copy(
    displayName = "Tenzin Dolma",
    dob = LocalDate.of(1996, 4, 12),
    gender = "female",
)

private val detailsFilled: OnboardingState = basicsFilled.copy(
    step = OnboardingStep.Details,
    region = "North America",
    languages = setOf("Tibetan", "English", "Hindi"),
    interests = setOf("Momo cooking", "Hiking", "Photography", "Music", "Language exchange"),
    bio = Fixtures.profile.bio.orEmpty(),
)

private val aboutYouFilled: OnboardingState = detailsFilled.copy(
    step = OnboardingStep.AboutYou,
    occupation = "Product designer",
    education = "Master's",
    answers = Fixtures.profile.answers.orEmpty(),
)

private val socialsFilled: OnboardingState = aboutYouFilled.copy(
    step = OnboardingStep.Socials,
    instagram = "tenzin.dolma",
    acceptedTerms = true,
)

private val locationInitial: OnboardingState = socialsFilled.copy(step = OnboardingStep.Location)

private val locationSaved: OnboardingState = locationInitial.copy(location = GeoLocation(lat = 43.65, lng = -79.38))

private fun pickedPhotos(count: Int): List<PickedPhoto> =
    (0 until count).map { PickedPhoto(id = it.toLong(), uri = Fixtures.imageUrl("drokpo-onboarding-$it")) }

private fun photosState(count: Int): OnboardingState = locationSaved.copy(
    step = OnboardingStep.Photos,
    photos = pickedPhotos(count),
    nextPhotoId = count.toLong(),
)

/**
 * OnboardingContent over local state: edits go through the real reducer,
 * Back through the real previous-step rule, and Continue just steps forward
 * (setting a fake location when leaving Location). "+" adds a fixture photo.
 */
@Composable
private fun OnboardingPreview(initial: OnboardingState, datePickerOpen: Boolean = false) {
    var state by remember { mutableStateOf(initial) }
    var showDatePicker by remember { mutableStateOf(datePickerOpen) }
    OnboardingContent(
        state = state,
        onEdit = { state = state.reduce(it) },
        onContinue = {
            if (state.canAdvance && !state.isSubmitting) {
                state = when (state.step) {
                    OnboardingStep.Photos -> state.copy(isSubmitting = true)
                    OnboardingStep.Location -> state.copy(
                        step = OnboardingStep.Photos,
                        location = state.location ?: Vocabulary.regionCoordinates[state.region],
                    )
                    else -> state.copy(step = OnboardingStep.entries[state.step.ordinal + 1])
                }
            }
        },
        onBack = { if (!state.isSubmitting) state = state.previousStep() },
        onSignOut = {},
        onAddPhotos = {
            val seed = "drokpo-onboarding-${state.nextPhotoId}"
            state = state.reduce(OnboardingEdit.AddPhotos(listOf(Fixtures.imageUrl(seed))))
        },
        onDismissError = { state = state.copy(errorMessage = null) },
    )
    if (showDatePicker) {
        DateOfBirthPickerDialog(
            selected = state.dob,
            latestAllowed = state.latestAllowedDob,
            onConfirm = {
                showDatePicker = false
                state = state.reduce(OnboardingEdit.Dob(it))
            },
            onDismiss = { showDatePicker = false },
        )
    }
}

/** ProfileQuestionFields alone, as Edit profile hosts it (a scrolling grouped column). */
@Composable
private fun QuestionFieldsPreview(initial: Map<String, String>) {
    var answers by remember { mutableStateOf(initial) }
    Scaffold(
        topBar = { DrokpoTopBar("Prompts", containerColor = DrokpoTheme.colors.groupedBackground) },
        containerColor = DrokpoTheme.colors.groupedBackground,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(DrokpoTheme.colors.groupedBackground)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
        ) {
            ProfileQuestionFields(
                answers = answers,
                onAnswerChange = { key, value -> answers = answers + (key to value) },
            )
        }
    }
}

val onboardingCatalogEntries: List<CatalogEntry> = listOf(
    CatalogEntry("onboarding.flow", "Onboarding — full flow from scratch (interactive)") {
        OnboardingPreview(blank)
    },

    // Step 1 — Basics
    CatalogEntry("onboarding.basics.empty", "Basics — empty (Continue disabled)") { OnboardingPreview(blank) },
    CatalogEntry("onboarding.basics.filled", "Basics — name, birthday, gender") { OnboardingPreview(basicsFilled) },
    CatalogEntry("onboarding.basics.datepicker", "Basics — date of birth picker (18+ bound)") {
        OnboardingPreview(basicsFilled, datePickerOpen = true)
    },

    // Step 2 — Details
    CatalogEntry("onboarding.details.empty", "Details — nothing chosen (Continue disabled)") {
        OnboardingPreview(basicsFilled.copy(step = OnboardingStep.Details))
    },
    CatalogEntry("onboarding.details.filled", "Details — region, languages, interests, bio") {
        OnboardingPreview(detailsFilled)
    },

    // Step 3 — About you
    CatalogEntry("onboarding.aboutyou.empty", "About you — all optional, untouched") {
        OnboardingPreview(detailsFilled.copy(step = OnboardingStep.AboutYou))
    },
    CatalogEntry("onboarding.aboutyou.filled", "About you — work, study and prompts answered") {
        OnboardingPreview(aboutYouFilled)
    },

    // Step 4 — Socials
    CatalogEntry("onboarding.socials.empty", "Socials — terms not accepted (Continue disabled)") {
        OnboardingPreview(aboutYouFilled.copy(step = OnboardingStep.Socials))
    },
    CatalogEntry("onboarding.socials.filled", "Socials — Instagram + terms accepted") {
        OnboardingPreview(socialsFilled)
    },
    CatalogEntry("onboarding.socials.noinstagram", "Socials — terms accepted, no Instagram (optional)") {
        OnboardingPreview(socialsFilled.copy(instagram = ""))
    },

    // Step 5 — Location
    CatalogEntry("onboarding.location.initial", "Location — before Continue (outline icon)") {
        OnboardingPreview(locationInitial)
    },
    CatalogEntry("onboarding.location.requesting", "Location — asking / creating profile (spinner)") {
        OnboardingPreview(locationInitial.copy(isSubmitting = true))
    },
    CatalogEntry("onboarding.location.saved", "Location — location saved (filled icon)") {
        OnboardingPreview(locationSaved)
    },
    CatalogEntry("onboarding.location.error", "Location — profile creation failed (alert)") {
        OnboardingPreview(locationSaved.copy(errorMessage = "The Internet connection appears to be offline."))
    },

    // Step 6 — Photos
    CatalogEntry("onboarding.photos.empty", "Photos — none yet (Finish disabled)") { OnboardingPreview(photosState(0)) },
    CatalogEntry("onboarding.photos.some", "Photos — three picked") { OnboardingPreview(photosState(3)) },
    CatalogEntry("onboarding.photos.full", "Photos — six picked (no + tile)") { OnboardingPreview(photosState(6)) },
    CatalogEntry("onboarding.photos.uploading", "Photos — uploading (Finish spinner, tiles locked)") {
        OnboardingPreview(photosState(4).copy(isSubmitting = true))
    },
    CatalogEntry("onboarding.photos.error", "Photos — upload failed mid-batch (alert)") {
        val state = photosState(3)
        OnboardingPreview(
            state.copy(
                photos = state.photos.mapIndexed { index, photo ->
                    if (index == 0) photo.copy(storagePath = "users/fixture-me/photos/A.jpg", confirmed = true) else photo
                },
                errorMessage = "That photo couldn't be processed. Try a different one.",
            ),
        )
    },
    CatalogEntry("onboarding.photos.resumed", "Photos — two already on the profile from an earlier run, one new pick") {
        val attached = listOf(
            Photo("users/fixture-me/photos/A.jpg", 0, Fixtures.imageUrl("drokpo-onboarding-attached-a")),
            Photo("users/fixture-me/photos/B.jpg", 1, Fixtures.imageUrl("drokpo-onboarding-attached-b")),
        )
        OnboardingPreview(photosState(1).withAttachedPhotos(attached))
    },
    CatalogEntry("onboarding.photos.brokenimage", "Photos — a pick that can't be displayed") {
        val state = photosState(2)
        OnboardingPreview(
            state.copy(photos = state.photos + PickedPhoto(id = 2, uri = "content://invalid/photo"), nextPhotoId = 3),
        )
    },

    // Shared with Edit profile
    CatalogEntry("onboarding.questions.prefilled", "ProfileQuestionFields — prefilled") {
        QuestionFieldsPreview(Fixtures.profile.answers.orEmpty())
    },
    CatalogEntry("onboarding.questions.empty", "ProfileQuestionFields — empty (Skip / placeholders)") {
        QuestionFieldsPreview(emptyMap())
    },
)
