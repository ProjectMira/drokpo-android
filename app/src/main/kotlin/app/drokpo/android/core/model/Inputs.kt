package app.drokpo.android.core.model

import kotlinx.serialization.Serializable

// MARK: - Request bodies
//
// Encoded with DrokpoJson: null → key omitted (Swift's synthesized
// `encodeIfPresent`), non-optional defaults always written. "Clearing" a field
// is never an explicit JSON null in this API: clearable optionals go up as ""
// and the backend deletes the stored value (see CommunityUpdate/ProfileUpdate),
// while omitted keys mean "unchanged".

@Serializable
data class CommunityOnboardingIn(
    val name: String,
    val description: String,
    val website: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val contactPerson: ContactPerson,
    val address: CommunityAddress,
    val socials: Socials? = null,
)

/**
 * PATCH /api/communities/me. Clearable optionals go up as "" when emptied —
 * the backend deletes the stored value. Never-clearable fields (email, contact
 * name, city, country) stay omit-when-empty (null), i.e. "unchanged".
 */
@Serializable
data class CommunityUpdate(
    val name: String? = null,
    val description: String? = null,
    val website: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val contactPerson: ContactPerson? = null,
    val address: CommunityAddress? = null,
    val socials: Socials? = null,
)

@Serializable
data class CommunityPhotoConfirm(
    val storagePath: String,
    val order: Int,
)

@Serializable
data class CommunityPhotoOrderUpdate(
    val storagePaths: List<String>,
)

@Serializable
data class CommunityPostIn(
    val kind: String,
    val title: String,
    val body: String = "",
    val imageUrl: String? = null,
    val photoStoragePath: String? = null,
    val linkUrl: String? = null,
    val ctaLabel: String? = null,
    val pollOptions: List<String>? = null,
    val eventAt: String? = null,
    val location: String? = null,
)

@Serializable
data class CommunityPostUpdate(
    val title: String? = null,
    val body: String? = null,
    val imageUrl: String? = null,
    val linkUrl: String? = null,
    val ctaLabel: String? = null,
    val active: Boolean? = null,
)

@Serializable
data class VoteIn(
    val optionId: String,
)

@Serializable
data class CommentIn(
    val text: String? = null,
    val audioStoragePath: String? = null,
    val audioDurationSec: Int? = null,
    val parentId: String? = null,
)

@Serializable
data class CommentVoteIn(
    val value: String, // "like" | "dislike"
)

@Serializable
data class OnboardingIn(
    val displayName: String,
    val dob: String,
    val gender: String? = null,
    val bio: String,
    val occupation: String,
    val education: String,
    val region: String,
    val languages: List<String>,
    val interests: List<String>,
    val answers: Map<String, String>,
    val socials: Socials,
    val location: GeoLocation,
    val preferences: Preferences,
)

@Serializable
data class PhotoConfirm(
    val storagePath: String,
    val order: Int,
)

@Serializable
data class PhotoOrderUpdate(
    val storagePaths: List<String>,
)

/**
 * PATCH /api/profile/me. Omitted (null) = unchanged; an emptied social handle
 * goes up as "" and the backend deletes it.
 */
@Serializable
data class ProfileUpdate(
    val displayName: String? = null,
    val bio: String? = null,
    val dob: String? = null,
    val gender: String? = null,
    val occupation: String? = null,
    val education: String? = null,
    val region: String? = null,
    val languages: List<String>? = null,
    val interests: List<String>? = null,
    val answers: Map<String, String>? = null,
    val socials: Socials? = null,
    val location: GeoLocation? = null,
    val preferences: Preferences? = null,
    val discoverable: Boolean? = null,
)

@Serializable
data class SwipeIn(
    val action: SwipeAction,
)

/** Constant names match the Swift cases (and the wire values) exactly. */
@Suppress("EnumEntryName")
@Serializable
enum class SwipeAction {
    like,
    pass,
    superlike,
    ;

    val rawValue: String get() = name
}

@Serializable
data class MessageIn(
    val text: String,
)

@Serializable
data class FcmTokenIn(
    val token: String,
)

@Serializable
data class ReportIn(
    val reportedUid: String,
    val reason: String,
    val note: String,
)

/**
 * POST /api/{ads,news,posts}/{id}/events — fire-and-forget content analytics,
 * same shape for all three content-card types the Discover deck shows.
 */
@Serializable
data class ContentEventIn(
    val event: String, // "impression" | "click"
)
