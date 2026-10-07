package app.drokpo.android.features.profile

import app.drokpo.android.core.model.GeoLocation
import app.drokpo.android.core.model.Preferences
import app.drokpo.android.core.model.Profile
import app.drokpo.android.core.model.ProfileUpdate
import app.drokpo.android.core.model.Socials
import app.drokpo.android.core.model.Vocabulary
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.roundToInt

/** Age slider bounds (iOS `RangeSliderRow(range:bounds: 18...99)`). */
internal val AGE_BOUNDS: ClosedFloatingPointRange<Float> = 18f..99f

/** Distance slider (iOS `Slider(value:in: 5...500, step: 5)`). */
internal val DISTANCE_BOUNDS: ClosedFloatingPointRange<Float> = 5f..500f
internal const val DISTANCE_STEP_KM = 5

/** Material's `steps` = stops between the ends: (500 − 5) / 5 − 1. */
internal val DISTANCE_SLIDER_STEPS: Int =
    ((DISTANCE_BOUNDS.endInclusive - DISTANCE_BOUNDS.start) / DISTANCE_STEP_KM).toInt() - 1

/**
 * Snaps a slider value to the nearest 5 km stop. Material's stepped Slider converts its
 * pixel position back with a float lerp, so stops often arrive as e.g. 89.99999 — which
 * `toInt()` would show and save as 89. iOS `Slider(step: 5)` always yields exact multiples.
 */
internal fun snapDistanceKm(km: Float): Float =
    ((km / DISTANCE_STEP_KM).roundToInt() * DISTANCE_STEP_KM).toFloat().coerceIn(DISTANCE_BOUNDS)

internal object EditProfileCopy {
    const val LOCATION_FOOTER =
        "Your location decides who shows up in your feed. Update it after you move or travel."
    const val LOCATION_UPDATED = "Location updated — save to apply."
    const val LOCATION_DENIED =
        "Location access is off for Drokpo. You can turn it on in Settings — until then your feed uses your saved location."
    const val LOCATION_FAILED = "Couldn't get your location right now. Try again in a moment."
}

/**
 * The editor's fields — iOS EditProfileView's `@State` initialised from the
 * profile. [birthday] is the calendar date the backend stores (`dob`,
 * "yyyy-MM-dd", read and written in UTC like `Profile.dobFormatter`).
 */
internal data class EditProfileForm(
    val displayName: String = "",
    val bio: String = "",
    /** "" = not set; otherwise a `Vocabulary.genders` value ("male" / "female"). */
    val gender: String = "",
    val birthday: LocalDate,
    val occupation: String = "",
    val education: String = "",
    val region: String = "",
    /** Insertion-ordered: the profile's order first, newly checked ones appended. */
    val languages: Set<String> = emptySet(),
    val interests: Set<String> = emptySet(),
    val answers: Map<String, String> = emptyMap(),
    val instagram: String = "",
    val youtube: String = "",
    val tiktok: String = "",
    val ageRange: ClosedFloatingPointRange<Float> = 18f..99f,
    val distanceKm: Float = 50f,
) {
    /** iOS `regionOptions`. */
    val regionOptions: List<String> get() = regionOptions(region)

    /** iOS `educationOptions`. */
    val educationOptions: List<String> get() = educationOptions(education)

    val ageLabel: String get() = "Age: ${ageRange.start.toInt()}–${ageRange.endInclusive.toInt()}"

    /** Rounded, not truncated: a float stop like 89.99999 is 90 (the age range truncates like iOS `Int(_:)`). */
    val distanceLabel: String get() = "Distance: ${distanceKm.roundToInt()} km"

    fun toggleLanguage(language: String): EditProfileForm = copy(languages = languages.toggling(language))

    fun toggleInterest(interest: String): EditProfileForm = copy(interests = interests.toggling(interest))

    fun answering(key: String, value: String): EditProfileForm = copy(answers = answers + (key to value))

    companion object {
        fun from(profile: Profile, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): EditProfileForm {
            val preferences = profile.preferences ?: Preferences()
            return EditProfileForm(
                displayName = profile.displayName ?: "",
                bio = profile.bio ?: "",
                gender = profile.gender ?: "",
                // iOS: `profile.dob.flatMap { Profile.dobFormatter.date(from:) } ?? .now`.
                birthday = profile.dob?.let(::parseDobLocalDate) ?: today,
                occupation = profile.occupation ?: "",
                education = profile.education ?: "",
                region = profile.region ?: "",
                languages = profile.languages.orEmpty().toSet(),
                interests = profile.interests.orEmpty().toSet(),
                answers = profile.answers.orEmpty(),
                instagram = profile.socials?.instagram ?: "",
                youtube = profile.socials?.youtube ?: "",
                tiktok = profile.socials?.tiktok ?: "",
                ageRange = preferences.ageMin.toFloat()..preferences.ageMax.toFloat(),
                distanceKm = preferences.distanceKm.toFloat(),
            )
        }
    }
}

private fun Set<String>.toggling(value: String): Set<String> = if (value in this) this - value else this + value

/**
 * Region options, prepending the user's stored value when it's a legacy
 * region (e.g. U-Tsang/Kham/Amdo) no longer offered — otherwise a picker
 * whose selection isn't among the options can't show it.
 */
internal fun regionOptions(region: String): List<String> =
    if (region.isNotEmpty() && region !in Vocabulary.regions) listOf(region) + Vocabulary.regions else Vocabulary.regions

/**
 * Same passthrough for education: profiles created before the picker
 * existed may hold free text that isn't among the level options.
 */
internal fun educationOptions(education: String): List<String> =
    if (education.isNotEmpty() && education !in Vocabulary.educationLevels) {
        listOf(education) + Vocabulary.educationLevels
    } else {
        Vocabulary.educationLevels
    }

/** "male" → "Male" (Swift `.capitalized`). */
internal fun capitalizedWords(value: String): String =
    value.split(" ").joinToString(" ") { word ->
        word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

/** Swift `trimmingCharacters(in: .whitespaces)`: spaces and tabs, not newlines. */
internal fun String.trimmingWhitespaces(): String = trim { it != '\n' && it != '\r' && it.isWhitespace() }

/** Swift `trimmingCharacters(in: .whitespacesAndNewlines)`. */
internal fun String.trimmingWhitespacesAndNewlines(): String = trim()

private val dobFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.US)

private fun parseDobLocalDate(dob: String): LocalDate? =
    try {
        LocalDate.parse(dob, dobFormat)
    } catch (e: DateTimeParseException) {
        null
    }

/** `Profile.dobFormatter.string(from:)` for a calendar date. */
internal fun LocalDate.toDobString(): String = format(dobFormat)

/** Can the user tap Save? Not while saving, and not with a blank (trimmed) name. */
internal fun canSave(form: EditProfileForm, isSaving: Boolean): Boolean =
    !isSaving && form.displayName.trimmingWhitespaces().isNotEmpty()

/**
 * The PATCH /api/profile/me body iOS `save()` builds: every field, with
 * - the name trimmed, gender "" → null (omitted = unchanged),
 * - answers trimmed and empty ones dropped,
 * - Instagram and TikTok trimmed with every "@" removed, YouTube trimmed,
 * - location only when "Update my location" produced a fix,
 * - the age range truncated to whole numbers like `Int(ageRange.lowerBound)`; the distance
 *   rounded, since its stops are exact multiples of 5 on iOS but may be a hair under here.
 */
internal fun buildProfileUpdate(form: EditProfileForm, updatedLocation: GeoLocation?): ProfileUpdate =
    ProfileUpdate(
        displayName = form.displayName.trimmingWhitespaces(),
        bio = form.bio,
        dob = form.birthday.toDobString(),
        gender = form.gender.ifEmpty { null },
        occupation = form.occupation,
        education = form.education,
        region = form.region,
        languages = form.languages.toList(),
        interests = form.interests.toList(),
        answers = form.answers
            .mapValues { (_, answer) -> answer.trimmingWhitespacesAndNewlines() }
            .filterValues { it.isNotEmpty() },
        socials = Socials(
            instagram = form.instagram.trimmingWhitespaces().replace("@", ""),
            youtube = form.youtube.trimmingWhitespaces(),
            tiktok = form.tiktok.trimmingWhitespaces().replace("@", ""),
        ),
        location = updatedLocation,
        preferences = Preferences(
            ageMin = form.ageRange.start.toInt(),
            ageMax = form.ageRange.endInclusive.toInt(),
            distanceKm = form.distanceKm.roundToInt(),
        ),
    )

/** What one "Update my location" attempt shows. */
internal data class LocationOutcome(
    val status: String,
    val updatedLocation: GeoLocation?,
    /** Footer offers "Open Settings". */
    val denied: Boolean,
)

/**
 * iOS `refreshLocation()`: a fix → "updated" (clears denied); no fix and
 * permission off → the Settings hint; otherwise the transient failure copy,
 * leaving any earlier fix and denied flag as they were.
 */
internal fun locationOutcome(
    location: GeoLocation?,
    isDenied: Boolean,
    previousLocation: GeoLocation?,
    previouslyDenied: Boolean,
): LocationOutcome = when {
    location != null -> LocationOutcome(EditProfileCopy.LOCATION_UPDATED, location, denied = false)
    isDenied -> LocationOutcome(EditProfileCopy.LOCATION_DENIED, previousLocation, denied = true)
    else -> LocationOutcome(EditProfileCopy.LOCATION_FAILED, previousLocation, denied = previouslyDenied)
}
