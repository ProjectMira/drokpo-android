package app.drokpo.android.core.model

import android.net.Uri
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.format.DateTimeFormatter

// NOTE: The backend's OpenAPI spec declares empty response schemas, so every
// response field here is optional and decoded defensively. Verify against the
// live API and tighten as the contract firms up.
//
// Port notes (apply to every model file):
// - Property names match the Swift structs 1:1; no key differs from its JSON
//   name, so there is no @SerialName except where called out.
// - Swift `var x: T?` → `val x: T? = null`. With DrokpoJson's
//   `explicitNulls = false` a null is omitted on encode, exactly like Swift's
//   synthesized `encodeIfPresent`; `encodeDefaults = true` keeps non-optional
//   defaults (e.g. `CommunityPostIn.body = ""`) on the wire like Swift does.
// - Properties are `val`: Swift structs are value types, so `var updated =
//   post; updated.myRsvp = …` ports to `post.copy(myRsvp = …)`.
// - `URL?` helpers return `android.net.Uri?` (null when the string is null or
//   blank — `URL(string:)`'s nil cases that matter here).

internal fun String?.toUriOrNull(): Uri? = this?.takeIf { it.isNotBlank() }?.let(Uri::parse)

@Serializable
data class GeoLocation(
    val lat: Double,
    val lng: Double,
)

/**
 * Swift's synthesized Decodable ignores these defaults and requires all three
 * keys; here a missing key falls back to the default instead (the backend
 * always writes all three, so this only matters for hand-edited docs).
 */
@Serializable
data class Preferences(
    val ageMin: Int = 18,
    val ageMax: Int = 99,
    val distanceKm: Int = 50,
)

/** Social handles. All optional — plenty of members have no Instagram. */
@Serializable
data class Socials(
    val instagram: String? = null,
    val youtube: String? = null,
    val tiktok: String? = null,
    val facebook: String? = null,
    val x: String? = null,
    val wechat: String? = null,
)

@Serializable
data class Photo(
    val storagePath: String,
    val order: Int? = null,
    val url: String? = null,
) {
    val id: String get() = storagePath
}

@Serializable
data class Profile(
    val uid: String? = null,
    val displayName: String? = null,
    val dob: String? = null,
    val gender: String? = null,
    val bio: String? = null,
    val occupation: String? = null,
    val education: String? = null,
    val region: String? = null,
    val languages: List<String>? = null,
    val interests: List<String>? = null,
    val answers: Map<String, String>? = null,
    val socials: Socials? = null,
    val photos: List<Photo>? = null,
    val preferences: Preferences? = null,
    val onboardingComplete: Boolean? = null,
    /**
     * `false` hides the profile from everyone's swipe deck; null (never set)
     * means shown.
     */
    val discoverable: Boolean? = null,
) {
    val id: String get() = uid ?: "me"

    val age: Int? get() = ageInYears(dob)

    /** Your own profile shaped as the card other members see, for previewing. */
    val asFeedCard: FeedCard
        get() = FeedCard(
            uid = uid ?: "me",
            displayName = displayName,
            age = age,
            dob = dob,
            region = region,
            bio = bio,
            occupation = occupation,
            education = education,
            languages = languages,
            interests = interests,
            answers = answers,
            socials = socials,
            photos = photos,
        )

    companion object {
        /**
         * Swift `Profile.dobFormatter` ("yyyy-MM-dd", UTC). Format an Instant
         * (e.g. a Material DatePicker's UTC-midnight millis) with
         * `dobFormatter.format(instant)`; parse with [parseDobDate].
         */
        val dobFormatter: DateTimeFormatter get() = dobDateFormatter

        /** `Profile.dobFormatter.date(from:)` — midnight UTC of that day, or null. */
        fun parseDobDate(dob: String): Instant? = parseDob(dob)

        /** `Profile.dobFormatter.string(from:)`. */
        fun formatDob(date: Instant): String = dobDateFormatter.format(date)
    }
}

@Serializable
data class FeedCard(
    val uid: String,
    val displayName: String? = null,
    val age: Int? = null,
    val dob: String? = null,
    val region: String? = null,
    val bio: String? = null,
    val occupation: String? = null,
    val education: String? = null,
    val languages: List<String>? = null,
    val interests: List<String>? = null,
    val answers: Map<String, String>? = null,
    val socials: Socials? = null,
    val photos: List<Photo>? = null,
    val distanceKm: Double? = null,
    /**
     * Additive discriminator: "person" | "community". Community accounts now
     * swipe/match/chat as themselves, served in this same person-shaped
     * card (name→displayName, description→bio, "City, Country"→region) so
     * they render everywhere a counterpart card already does. Missing on
     * every real person response — null defaults to "not a community".
     */
    val kind: String? = null,
) {
    val id: String get() = uid

    val isCommunity: Boolean get() = kind == "community"

    val displayAge: Int? get() = age ?: ageInYears(dob)
}

/**
 * A sponsored card served with the feed (see backend docs/ADS.md). Shown in
 * the Discover deck after every few real profiles; liking it opens `linkUrl`
 * in the in-app browser instead of recording a swipe.
 */
@Serializable
data class AdCard(
    val adId: String,
    val title: String? = null,
    val body: String? = null,
    val linkUrl: String? = null,
    val ctaLabel: String? = null,
    val imageUrl: String? = null,
    val photos: List<Photo>? = null,
) {
    val id: String get() = adId

    val url: Uri? get() = linkUrl.toUriOrNull()

    /**
     * Creative to render — `photos` if present, else `imageUrl` wrapped as a
     * single photo (the synthetic storagePath only serves as a cache key).
     */
    val displayPhotos: List<Photo>
        get() {
            if (!photos.isNullOrEmpty()) return photos
            if (imageUrl != null) return listOf(Photo(storagePath = "ad-image-$adId", order = 0, url = imageUrl))
            return emptyList()
        }
}
