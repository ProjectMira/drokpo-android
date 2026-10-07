package app.drokpo.android.features.onboarding

import app.drokpo.android.core.DrokpoJson
import app.drokpo.android.core.model.GeoLocation
import app.drokpo.android.core.model.OnboardingIn
import app.drokpo.android.core.model.Photo
import app.drokpo.android.core.model.Preferences
import app.drokpo.android.core.model.Socials
import app.drokpo.android.core.model.Vocabulary
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStateTest {
    private val today = LocalDate.of(2026, 10, 7)
    private val blank = OnboardingState.initial(today)

    // region Defaults & progress

    @Test
    fun birthdayDefaultsTo25AndIsBoundedAt18() {
        assertEquals(LocalDate.of(2001, 10, 7), blank.dob)
        assertEquals(LocalDate.of(2008, 10, 7), blank.latestAllowedDob)
        assertEquals(OnboardingStep.Basics, blank.step)
    }

    @Test
    fun leapDayBirthdayBoundFallsBackToFebruary28() {
        val state = OnboardingState.initial(LocalDate.of(2024, 2, 29))
        assertEquals(LocalDate.of(2006, 2, 28), state.latestAllowedDob)
        assertEquals(LocalDate.of(1999, 2, 28), state.dob)
    }

    @Test
    fun progressIsStepPlusOneOverSix() {
        val expected = listOf(1f / 6, 2f / 6, 3f / 6, 4f / 6, 5f / 6, 1f)
        OnboardingStep.entries.forEachIndexed { index, step ->
            assertEquals(expected[index], blank.copy(step = step).progress, 0.0001f)
        }
    }

    // endregion

    // region canAdvance

    @Test
    fun basicsNeedsTrimmedNameAndGender() {
        assertFalse(blank.canAdvance)
        assertFalse(blank.copy(displayName = "   ", gender = "male").canAdvance)
        assertFalse(blank.copy(displayName = "Tenzin").canAdvance)
        assertTrue(blank.copy(displayName = " Tenzin ", gender = "male").canAdvance)
    }

    @Test
    fun detailsNeedsRegionAndALanguage() {
        val details = blank.copy(step = OnboardingStep.Details)
        assertFalse(details.canAdvance)
        assertFalse(details.copy(region = "Nepal").canAdvance)
        assertFalse(details.copy(languages = setOf("Tibetan")).canAdvance)
        assertTrue(details.copy(region = "Nepal", languages = setOf("Tibetan")).canAdvance)
    }

    @Test
    fun aboutYouAndLocationAlwaysAdvance() {
        assertTrue(blank.copy(step = OnboardingStep.AboutYou).canAdvance)
        assertTrue(blank.copy(step = OnboardingStep.Location).canAdvance)
    }

    @Test
    fun socialsNeedsTermsButNotInstagram() {
        val socials = blank.copy(step = OnboardingStep.Socials)
        assertFalse(socials.copy(instagram = "tenzin").canAdvance)
        assertTrue(socials.copy(acceptedTerms = true).canAdvance)
    }

    @Test
    fun photosNeedsAtLeastOnePhoto() {
        val photos = blank.copy(step = OnboardingStep.Photos)
        assertFalse(photos.canAdvance)
        assertTrue(photos.reduce(OnboardingEdit.AddPhotos(listOf("content://1"))).canAdvance)
    }

    @Test
    fun photosOverSixAfterAMergeBlockFinish() {
        val seven = blank.copy(
            step = OnboardingStep.Photos,
            photos = (0L until 7L).map { PickedPhoto(id = it, uri = "content://$it") },
            nextPhotoId = 7,
        )
        assertFalse(seven.canAdvance)
        assertFalse(seven.canAddPhotos)
        assertEquals(0, seven.remainingPhotoSlots)
        assertTrue(seven.reduce(OnboardingEdit.RemovePhoto(3)).canAdvance)
    }

    // endregion

    // region Reducer

    @Test
    fun togglesAddAndRemoveLanguagesAndInterests() {
        val once = blank
            .reduce(OnboardingEdit.ToggleLanguage("Tibetan"))
            .reduce(OnboardingEdit.ToggleLanguage("English"))
            .reduce(OnboardingEdit.ToggleInterest("Hiking"))
        assertEquals(setOf("Tibetan", "English"), once.languages)
        assertEquals(setOf("Hiking"), once.interests)
        val twice = once.reduce(OnboardingEdit.ToggleLanguage("Tibetan")).reduce(OnboardingEdit.ToggleInterest("Hiking"))
        assertEquals(setOf("English"), twice.languages)
        assertTrue(twice.interests.isEmpty())
    }

    @Test
    fun birthdayIsClampedToTheAdultBound() {
        val tooYoung = blank.reduce(OnboardingEdit.Dob(today.minusYears(10)))
        assertEquals(blank.latestAllowedDob, tooYoung.dob)
        val adult = blank.reduce(OnboardingEdit.Dob(LocalDate.of(1990, 1, 1)))
        assertEquals(LocalDate.of(1990, 1, 1), adult.dob)
    }

    @Test
    fun photosAreCappedAtSixWithUniqueIds() {
        val four = blank.reduce(OnboardingEdit.AddPhotos((1..4).map { "content://$it" }))
        assertEquals(4, four.photos.size)
        assertEquals(2, four.remainingPhotoSlots)
        // A document-picker fallback can return more than asked for.
        val full = four.reduce(OnboardingEdit.AddPhotos((5..9).map { "content://$it" }))
        assertEquals((1..6).map { "content://$it" }, full.photos.map { it.uri })
        assertFalse(full.canAddPhotos)
        assertEquals(0, full.remainingPhotoSlots)
        assertEquals(6, full.photos.map { it.id }.toSet().size)

        val removed = full.reduce(OnboardingEdit.RemovePhoto(full.photos[1].id))
        assertEquals(listOf(1, 3, 4, 5, 6).map { "content://$it" }, removed.photos.map { it.uri })
        val again = removed.reduce(OnboardingEdit.AddPhotos(listOf("content://new")))
        // Ids keep counting up, so a new tile never reuses a removed one's key.
        assertEquals(6L, again.photos.last().id)
        assertEquals(6, again.photos.map { it.id }.toSet().size)
    }

    @Test
    fun removingAnUploadedPhotoQueuesItsDeletion() {
        val state = blank.copy(
            photos = listOf(
                PickedPhoto(id = 0, uri = "content://a", storagePath = "users/u1/photos/a.jpg", confirmed = true),
                PickedPhoto(id = 1, uri = "content://b", storagePath = "users/u1/photos/b.jpg"),
                PickedPhoto(id = 2, uri = "content://c"),
            ),
            nextPhotoId = 3,
        )
        val removed = state
            .reduce(OnboardingEdit.RemovePhoto(0))
            .reduce(OnboardingEdit.RemovePhoto(1))
            .reduce(OnboardingEdit.RemovePhoto(2)) // never uploaded: nothing to delete
            .reduce(OnboardingEdit.RemovePhoto(0)) // already gone
        assertTrue(removed.photos.isEmpty())
        assertEquals(listOf("users/u1/photos/a.jpg", "users/u1/photos/b.jpg"), removed.pendingPhotoDeletes)
    }

    @Test
    fun attachedPhotosLeadAndKeepLocalTiles() {
        val state = blank.copy(
            step = OnboardingStep.Location,
            photos = listOf(
                PickedPhoto(id = 4, uri = "content://a", storagePath = "users/u1/photos/a.jpg", confirmed = true),
                // Its confirm reached the backend but the reply didn't reach us.
                PickedPhoto(id = 5, uri = "content://b", storagePath = "users/u1/photos/b.jpg"),
                PickedPhoto(id = 6, uri = "content://c"),
                // Thought confirmed, but no longer on the profile.
                PickedPhoto(id = 7, uri = "content://d", storagePath = "users/u1/photos/d.jpg", confirmed = true),
            ),
            nextPhotoId = 8,
            pendingPhotoDeletes = listOf("users/u1/photos/gone.jpg"),
        )
        val merged = state.withAttachedPhotos(
            listOf(
                Photo("users/u1/photos/a.jpg", 0, "https://cdn/a.jpg"),
                Photo("users/u1/photos/gone.jpg", 1, "https://cdn/gone.jpg"),
                Photo("users/u1/photos/b.jpg", 2, "https://cdn/b.jpg"),
                Photo("users/u1/photos/x.jpg", 3, null),
                Photo("users/u1/photos/x.jpg", 3, null),
            ),
        )
        assertEquals(listOf(4L, 5L, 8L, 6L, 7L), merged.photos.map { it.id })
        assertEquals(listOf("content://a", "content://b", "", "content://c", "content://d"), merged.photos.map { it.uri })
        assertEquals(listOf(true, true, true, false, false), merged.photos.map { it.confirmed })
        assertEquals("users/u1/photos/x.jpg", merged.photos[2].storagePath)
        assertEquals("users/u1/photos/d.jpg", merged.photos[4].storagePath) // re-confirmed, not re-uploaded
        assertEquals(9L, merged.nextPhotoId)
        assertEquals(listOf("users/u1/photos/gone.jpg"), merged.pendingPhotoDeletes)

        // Nothing on the profile and nothing picked: unchanged.
        assertEquals(blank, blank.withAttachedPhotos(emptyList()))
    }

    @Test
    fun answersMergeByKey() {
        val state = blank
            .reduce(OnboardingEdit.Answer("teaChoice", "Chai"))
            .reduce(OnboardingEdit.Answer("teaChoice", "Butter tea"))
            .reduce(OnboardingEdit.Answer("travelledTo", "Lhasa"))
        assertEquals(mapOf("teaChoice" to "Butter tea", "travelledTo" to "Lhasa"), state.answers)
    }

    @Test
    fun previousStepStopsAtBasics() {
        assertEquals(OnboardingStep.Basics, blank.previousStep().step)
        assertEquals(OnboardingStep.Location, blank.copy(step = OnboardingStep.Photos).previousStep().step)
        assertEquals(OnboardingStep.Basics, blank.copy(step = OnboardingStep.Details).previousStep().step)
    }

    // endregion

    // region Request bodies

    private val filled = blank.copy(
        step = OnboardingStep.Location,
        displayName = "  Tenzin Dolma ",
        dob = LocalDate.of(1996, 4, 12),
        gender = "female",
        region = "Nepal",
        languages = setOf("English", "Tibetan"),
        interests = setOf("Music", "Momo cooking"),
        bio = "  Momos and mountains.\n",
        occupation = " Designer ",
        education = "Master's",
        answers = mapOf("teaChoice" to "Butter tea", "travelledTo" to "  Lhasa \n", "favoriteMovies" to "   ", "lookingFor" to ""),
        instagram = " @tenzin.dolma ",
        acceptedTerms = true,
    )

    @Test
    fun onboardingBodyTrimsAndCleans() {
        val body = filled.toOnboardingIn()
        assertEquals(
            OnboardingIn(
                displayName = "Tenzin Dolma",
                dob = "1996-04-12",
                gender = "female",
                bio = "  Momos and mountains.\n", // sent raw, like iOS
                occupation = "Designer",
                education = "Master's",
                region = "Nepal",
                languages = listOf("Tibetan", "English"), // vocabulary order
                interests = listOf("Momo cooking", "Music"),
                answers = mapOf("teaChoice" to "Butter tea", "travelledTo" to "Lhasa"),
                socials = Socials(instagram = "tenzin.dolma"),
                location = GeoLocation(lat = 27.72, lng = 85.32), // Nepal's centre: no fix
                preferences = Preferences(),
            ),
            body,
        )
    }

    @Test
    fun genderAndInstagramAreOmittedWhenEmpty() {
        val body = filled.copy(gender = "", instagram = " @ ").toOnboardingIn()
        assertNull(body.gender)
        assertNull(body.socials.instagram)
        val json = DrokpoJson.encodeToString(OnboardingIn.serializer(), body)
        val obj = Json.parseToJsonElement(json).jsonObject
        assertFalse("gender" in obj)
        assertEquals(JsonObject(emptyMap()), obj["socials"])
        assertEquals("1996-04-12", obj["dob"]?.jsonPrimitive?.content)
        assertEquals(setOf("ageMin", "ageMax", "distanceKm"), obj["preferences"]?.jsonObject?.keys)
    }

    @Test
    fun locationPrefersTheFixThenTheRegionThenZero() {
        val fix = GeoLocation(lat = 43.65, lng = -79.38)
        assertEquals(fix, filled.copy(location = fix).toOnboardingIn().location)
        assertEquals(Vocabulary.regionCoordinates.getValue("Europe"), filled.copy(region = "Europe").toOnboardingIn().location)
        assertEquals(GeoLocation(0.0, 0.0), filled.copy(region = "Atlantis").toOnboardingIn().location)
    }

    @Test
    fun profileUpdateMirrorsTheOnboardingBody() {
        val update = filled.toProfileUpdate()
        val body = filled.toOnboardingIn()
        assertEquals(body.displayName, update.displayName)
        assertEquals(body.dob, update.dob)
        assertEquals(body.gender, update.gender)
        assertEquals(body.languages, update.languages)
        assertEquals(body.answers, update.answers)
        assertEquals(body.location, update.location)
        assertEquals(Socials(instagram = "tenzin.dolma"), update.socials)
        assertNull(update.preferences)
        // An emptied handle goes up as "" so the backend clears it.
        assertEquals(Socials(instagram = ""), filled.copy(instagram = "").toProfileUpdate().socials)
    }

    @Test
    fun genderLabelsAreCapitalized() {
        assertEquals("Male", "male".capitalizedWords())
        assertEquals("Female", "female".capitalizedWords())
        assertEquals("North America", "north america".capitalizedWords())
    }

    // endregion

    // region Draft (process death)

    @Test
    fun draftRoundTripsTheForm() {
        val form = filled.copy(step = OnboardingStep.Socials, location = GeoLocation(lat = 43.65, lng = -79.38))
        val json = form.toDraft(uid = "u1").encode()
        val draft = OnboardingDraft.decode(json)!!
        assertEquals("u1", draft.uid)
        assertEquals(form, OnboardingState.restoring(draft, today))
    }

    @Test
    fun draftLeavesOutPhotosAndTransientState() {
        val form = filled.copy(
            step = OnboardingStep.Photos,
            photos = listOf(PickedPhoto(id = 0, uri = "content://a", storagePath = "users/u1/photos/a.jpg", confirmed = true)),
            nextPhotoId = 1,
            pendingPhotoDeletes = listOf("users/u1/photos/b.jpg"),
            isSubmitting = true,
            errorMessage = "Try again",
        )
        val restored = OnboardingState.restoring(OnboardingDraft.decode(form.toDraft(uid = null).encode())!!, today)
        assertEquals(OnboardingStep.Location, restored.step) // the picks are gone
        assertTrue(restored.photos.isEmpty())
        assertTrue(restored.pendingPhotoDeletes.isEmpty())
        assertFalse(restored.isSubmitting)
        assertNull(restored.errorMessage)
        assertEquals(filled.displayName, restored.displayName)
    }

    @Test
    fun draftRestoreToleratesBadValues() {
        assertNull(OnboardingDraft.decode("not json"))
        assertNull(OnboardingDraft.decode("[1, 2]"))
        val odd = OnboardingDraft.decode("""{"step":"Somewhere","dob":"12/04/1996","extra":1}""")!!
        val restored = OnboardingState.restoring(odd, today)
        assertEquals(OnboardingStep.Basics, restored.step)
        assertEquals(blank.dob, restored.dob)
        // A birthday past today's 18+ bound is clamped like a picker edit.
        val young = OnboardingState.restoring(OnboardingDraft(dob = "2015-01-01"), today)
        assertEquals(blank.latestAllowedDob, young.dob)
    }

    // endregion
}
