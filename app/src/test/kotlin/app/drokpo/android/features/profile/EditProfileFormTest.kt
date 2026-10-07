package app.drokpo.android.features.profile

import app.drokpo.android.core.model.GeoLocation
import app.drokpo.android.core.model.Preferences
import app.drokpo.android.core.model.Profile
import app.drokpo.android.core.model.Socials
import app.drokpo.android.core.model.Vocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EditProfileFormTest {
    private val today = LocalDate.of(2026, 10, 7)

    private val profile = Profile(
        uid = "me",
        displayName = "Tenzin Dolma",
        dob = "1996-04-12",
        gender = "female",
        bio = "Designer",
        occupation = "Product designer",
        education = "Master's",
        region = "North America",
        languages = listOf("Tibetan", "English"),
        interests = listOf("Hiking"),
        answers = mapOf("lookingFor" to "New friends"),
        socials = Socials(instagram = "tenzin", youtube = "TenzinTV", tiktok = "tz"),
        preferences = Preferences(ageMin = 24, ageMax = 38, distanceKm = 80),
    )

    @Test
    fun formStartsFromTheProfile() {
        val form = EditProfileForm.from(profile, today)
        assertEquals("Tenzin Dolma", form.displayName)
        assertEquals(LocalDate.of(1996, 4, 12), form.birthday)
        assertEquals("female", form.gender)
        assertEquals(listOf("Tibetan", "English"), form.languages.toList())
        assertEquals(24f..38f, form.ageRange)
        assertEquals(80f, form.distanceKm)
        assertEquals("Age: 24–38", form.ageLabel)
        assertEquals("Distance: 80 km", form.distanceLabel)
        assertEquals("tenzin", form.instagram)
    }

    @Test
    fun missingValuesFallBackLikeIos() {
        val form = EditProfileForm.from(Profile(uid = "me", displayName = "Tenzin", dob = "not-a-date"), today)
        assertEquals(today, form.birthday) // `?? .now`
        assertEquals("", form.gender)
        assertEquals(18f..99f, form.ageRange) // Preferences() defaults
        assertEquals(50f, form.distanceKm)
        assertTrue(form.languages.isEmpty())
        assertEquals("", form.instagram)
    }

    @Test
    fun legacyRegionAndEducationArePrepended() {
        assertEquals(listOf("Amdo") + Vocabulary.regions, regionOptions("Amdo"))
        assertEquals(Vocabulary.regions, regionOptions("Nepal"))
        assertEquals(Vocabulary.regions, regionOptions(""))
        assertEquals(listOf("BSc Physics") + Vocabulary.educationLevels, educationOptions("BSc Physics"))
        assertEquals(Vocabulary.educationLevels, educationOptions("PhD"))
        assertEquals(Vocabulary.educationLevels, educationOptions(""))
    }

    @Test
    fun togglesKeepTheExistingOrder() {
        val form = EditProfileForm.from(profile, today)
        assertEquals(listOf("Tibetan", "English", "Hindi"), form.toggleLanguage("Hindi").languages.toList())
        assertEquals(listOf("English"), form.toggleLanguage("Tibetan").languages.toList())
        assertEquals(listOf("Hiking", "Music"), form.toggleInterest("Music").interests.toList())
        assertEquals(emptyList<String>(), form.toggleInterest("Hiking").interests.toList())
    }

    @Test
    fun saveRequiresANonBlankName() {
        val form = EditProfileForm.from(profile, today)
        assertTrue(canSave(form, isSaving = false))
        assertFalse(canSave(form, isSaving = true))
        assertFalse(canSave(form.copy(displayName = " \t "), isSaving = false))
    }

    @Test
    fun updateBodyCleansEveryField() {
        val form = EditProfileForm.from(profile, today).copy(
            displayName = "  Tenzin  ",
            bio = "  Line one\n",
            gender = "",
            answers = mapOf("lookingFor" to "  Dating \n", "teaChoice" to "   ", "travelledTo" to ""),
            instagram = " @ten@zin ",
            youtube = "  TenzinTV ",
            tiktok = " @tz ",
            ageRange = 21.7f..40.2f,
            distanceKm = 125f,
            birthday = LocalDate.of(1990, 1, 31),
        )
        val update = buildProfileUpdate(form, updatedLocation = null)
        assertEquals("Tenzin", update.displayName)
        assertEquals("  Line one\n", update.bio) // bio goes up raw
        assertEquals("1990-01-31", update.dob)
        assertNull(update.gender) // "" → omitted (unchanged)
        assertEquals("Product designer", update.occupation)
        assertEquals("Master's", update.education)
        assertEquals("North America", update.region)
        assertEquals(listOf("Tibetan", "English"), update.languages)
        assertEquals(listOf("Hiking"), update.interests)
        assertEquals(mapOf("lookingFor" to "Dating"), update.answers)
        assertEquals(Socials(instagram = "tenzin", youtube = "TenzinTV", tiktok = "tz"), update.socials)
        assertNull(update.location) // only sent after "Update my location"
        assertEquals(Preferences(ageMin = 21, ageMax = 40, distanceKm = 125), update.preferences)
        assertNull(update.discoverable)
    }

    @Test
    fun emptiedHandlesGoUpAsEmptyStrings() {
        // "" (not null) is how the backend learns to delete a stored handle.
        val form = EditProfileForm.from(profile, today).copy(instagram = "  ", youtube = "", tiktok = "@")
        assertEquals(Socials(instagram = "", youtube = "", tiktok = ""), buildProfileUpdate(form, null).socials)
    }

    @Test
    fun updatedLocationIsIncluded() {
        val here = GeoLocation(lat = 43.65, lng = -79.38)
        assertEquals(here, buildProfileUpdate(EditProfileForm.from(profile, today), here).location)
    }

    @Test
    fun locationOutcomes() {
        val fix = GeoLocation(1.0, 2.0)
        val earlier = GeoLocation(3.0, 4.0)
        assertEquals(
            LocationOutcome("Location updated — save to apply.", fix, denied = false),
            locationOutcome(fix, isDenied = false, previousLocation = earlier, previouslyDenied = true),
        )
        assertEquals(
            LocationOutcome(
                "Location access is off for Drokpo. You can turn it on in Settings — until then your feed uses your saved location.",
                earlier,
                denied = true,
            ),
            locationOutcome(null, isDenied = true, previousLocation = earlier, previouslyDenied = false),
        )
        // A transient failure keeps whatever an earlier attempt established.
        assertEquals(
            LocationOutcome("Couldn't get your location right now. Try again in a moment.", earlier, denied = true),
            locationOutcome(null, isDenied = false, previousLocation = earlier, previouslyDenied = true),
        )
    }

    @Test
    fun ageRangeKeepsThumbsOneYearApart() {
        val bounds = 18f..99f
        // lower thumb: min(new, upper − 1)
        assertEquals(30f..38f, constrainRange(24f..38f, 30f..38f, bounds))
        assertEquals(37f..38f, constrainRange(24f..38f, 45f..38f, bounds))
        // upper thumb: max(new, lower + 1)
        assertEquals(24f..60f, constrainRange(24f..38f, 24f..60f, bounds))
        assertEquals(24f..25f, constrainRange(24f..38f, 24f..20f, bounds))
        // never outside the bounds
        assertEquals(18f..38f, constrainRange(24f..38f, 10f..38f, bounds))
        assertEquals(24f..99f, constrainRange(24f..38f, 24f..120f, bounds))
        assertEquals(98f..99f, constrainRange(30f..99f, 99f..99f, bounds))
    }

    @Test
    fun distanceSliderStopsEveryFiveKm() {
        // Material counts the stops between the ends: 5, 10, …, 500 → 98 in between.
        assertEquals(98, DISTANCE_SLIDER_STEPS)
    }

    @Test
    fun distanceStopsSnapToWholeFiveKm() {
        // Material's stepped Slider hands back float stops a hair under the multiple.
        assertEquals(90f, snapDistanceKm(89.99999f))
        assertEquals(10f, snapDistanceKm(9.999998f))
        assertEquals(175f, snapDistanceKm(174.99997f))
        assertEquals(125f, snapDistanceKm(125.00002f))
        assertEquals(5f, snapDistanceKm(0f))
        assertEquals(500f, snapDistanceKm(512f))

        val form = EditProfileForm.from(profile, today).copy(distanceKm = snapDistanceKm(89.99999f))
        assertEquals("Distance: 90 km", form.distanceLabel)
        assertEquals(90, buildProfileUpdate(form, null).preferences?.distanceKm)
        // Even an unsnapped value never truncates to the stop below.
        val raw = form.copy(distanceKm = 89.99999f)
        assertEquals("Distance: 90 km", raw.distanceLabel)
        assertEquals(90, buildProfileUpdate(raw, null).preferences?.distanceKm)
        // The age range is continuous and truncates like iOS `Int(_:)`.
        assertEquals("Age: 21–40", form.copy(ageRange = 21.9f..40.9f).ageLabel)
    }

    @Test
    fun genderLabelsAreCapitalized() {
        assertEquals("Male", capitalizedWords("male"))
        assertEquals("Female", capitalizedWords("female"))
    }

    @Test
    fun whitespaceTrimmingMatchesFoundation() {
        assertEquals("a b", "  a b\t".trimmingWhitespaces())
        assertEquals("\na\n", "\na\n".trimmingWhitespaces()) // .whitespaces keeps newlines
        assertEquals("a", " \n a \n ".trimmingWhitespacesAndNewlines())
    }
}
