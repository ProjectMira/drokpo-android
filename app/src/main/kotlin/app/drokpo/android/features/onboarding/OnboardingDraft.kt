package app.drokpo.android.features.onboarding

import app.drokpo.android.core.model.GeoLocation
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The onboarding form as it is saved across process death
 * (OnboardingFlowScreen keeps it in `rememberSaveable`). Android may kill the
 * backgrounded app while the person looks up their Instagram handle or sits
 * in the system photo picker, and without this they would come back to an
 * empty Basics step. iOS doesn't need it, since it doesn't kill apps for a
 * short switch.
 *
 * Photos are left out on purpose. The picker's URI grants end with the
 * process, and any photo already confirmed is on the profile, so the 409
 * path brings it back (see [withAttachedPhotos]). The spinner, the error
 * and `completed` aren't saved either.
 */
@Serializable
internal data class OnboardingDraft(
    /** Firebase uid the form belongs to, so it never pre-fills another account's onboarding. */
    val uid: String? = null,
    val step: String = OnboardingStep.Basics.name,
    val displayName: String = "",
    /** ISO-8601 calendar day (yyyy-MM-dd). */
    val dob: String? = null,
    val gender: String = "",
    val region: String = "",
    val languages: List<String> = emptyList(),
    val interests: List<String> = emptyList(),
    val bio: String = "",
    val occupation: String = "",
    val education: String = "",
    val answers: Map<String, String> = emptyMap(),
    val instagram: String = "",
    val acceptedTerms: Boolean = false,
    val location: GeoLocation? = null,
) {
    fun encode(): String = DraftJson.encodeToString(serializer(), this)

    companion object {
        /** Null for anything that isn't a draft this build can read. */
        fun decode(json: String): OnboardingDraft? =
            try {
                DraftJson.decodeFromString(serializer(), json)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
    }
}

private val DraftJson = Json { ignoreUnknownKeys = true }

internal fun OnboardingState.toDraft(uid: String?): OnboardingDraft = OnboardingDraft(
    uid = uid,
    step = step.name,
    displayName = displayName,
    dob = dob.toString(),
    gender = gender,
    region = region,
    languages = languages.toList(),
    interests = interests.toList(),
    bio = bio,
    occupation = occupation,
    education = education,
    answers = answers,
    instagram = instagram,
    acceptedTerms = acceptedTerms,
    location = location,
)

/**
 * A fresh form for [today] with [draft]'s answers. The 18+ bound is worked
 * out again for today. A draft saved on Photos resumes on Location, because
 * the picks are gone: Continue saves the profile again (the 409 → PATCH path)
 * and brings back any photos already attached to it.
 */
internal fun OnboardingState.Companion.restoring(draft: OnboardingDraft, today: LocalDate): OnboardingState {
    val fresh = initial(today)
    val step = OnboardingStep.entries.firstOrNull { it.name == draft.step } ?: OnboardingStep.Basics
    val dob = draft.dob?.let {
        try {
            LocalDate.parse(it)
        } catch (e: DateTimeParseException) {
            null
        }
    } ?: fresh.dob
    return fresh.copy(
        step = minOf(step, OnboardingStep.Location),
        displayName = draft.displayName,
        dob = minOf(dob, fresh.latestAllowedDob),
        gender = draft.gender,
        region = draft.region,
        languages = draft.languages.toSet(),
        interests = draft.interests.toSet(),
        bio = draft.bio,
        occupation = draft.occupation,
        education = draft.education,
        answers = draft.answers,
        instagram = draft.instagram,
        acceptedTerms = draft.acceptedTerms,
        location = draft.location,
    )
}
