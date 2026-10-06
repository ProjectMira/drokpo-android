package app.drokpo.android.core.model

import android.net.Uri
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.JsonNull
import java.time.Instant

// MARK: - Communities

@Serializable
data class ContactPerson(
    val name: String? = null,
    val role: String? = null,
    val phone: String? = null,
    val email: String? = null,
)

@Serializable
data class CommunityAddress(
    val line1: String? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    val postalCode: String? = null,
)

/**
 * A community/organization account — the alternative to `Profile` for the
 * same Firebase Auth uid (see backend docs/COMMUNITIES.md). `joined` is only
 * populated on directory/detail responses, not on `GET /api/communities/me`.
 */
@Serializable
data class CommunityProfile(
    val uid: String? = null,
    val name: String? = null,
    val description: String? = null,
    val website: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val contactPerson: ContactPerson? = null,
    val address: CommunityAddress? = null,
    val socials: Socials? = null,
    val photos: List<Photo>? = null,
    val verification: String? = null,
    val memberCount: Int? = null,
    val joined: Boolean? = null,
) {
    val id: String get() = uid ?: "community"

    val isVerified: Boolean get() = verification == "verified"
    val isPending: Boolean get() = verification == "pending" || verification == null
}

/**
 * GET /api/account — the single call the app makes at launch to decide
 * which experience (and which onboarding, if any) to route into.
 * `accountType` is "person" | "community" | "none".
 */
@Serializable
data class AccountResponse(
    val accountType: String? = null,
    val profile: Profile? = null,
    val community: CommunityProfile? = null,
)

/**
 * One entry in a poll post: `id` is server-assigned and stable — never
 * re-derive it client-side (votes reference it).
 */
@Serializable
data class PollOption(
    val id: String,
    val label: String,
)

@Serializable(with = PollSerializer::class)
data class Poll(
    val options: List<PollOption>,
    val counts: Map<String, Int>,
) {
    val totalVotes: Int get() = counts.values.sum()

    fun percentage(optionId: String): Double {
        if (totalVotes <= 0) return 0.0
        return (counts[optionId] ?: 0).toDouble() / totalVotes.toDouble()
    }
}

/**
 * Decode defensively like every other response model: a poll doc missing
 * counts/options (hand-edited in the console) must degrade to an empty
 * poll, not fail the decode of the entire posts page it rides in.
 * (Swift: `(try? decodeIfPresent(…)) ?? []` per field; a non-object poll
 * still fails, as `container(keyedBy:)` would.)
 */
object PollSerializer : KSerializer<Poll> {
    private val optionsSerializer = ListSerializer(PollOption.serializer())
    private val countsSerializer = MapSerializer(String.serializer(), Int.serializer())

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("app.drokpo.Poll") {
        element("options", optionsSerializer.descriptor)
        element("counts", countsSerializer.descriptor)
    }

    override fun deserialize(decoder: Decoder): Poll {
        val input = decoder.asJsonDecoder()
        val obj = input.decodeJsonElement().asJsonObject("a poll")
        val options = obj["options"]?.takeUnless { it is JsonNull }?.let { input.tryDecode(optionsSerializer, it) }
        val counts = obj["counts"]?.takeUnless { it is JsonNull }?.let { input.tryDecode(countsSerializer, it) }
        return Poll(options = options ?: emptyList(), counts = counts ?: emptyMap())
    }

    override fun serialize(encoder: Encoder, value: Poll) {
        encoder.encodeStructure(descriptor) {
            encodeSerializableElement(descriptor, 0, optionsSerializer, value.options)
            encodeSerializableElement(descriptor, 1, countsSerializer, value.counts)
        }
    }
}

/**
 * A community's post, in one of four kinds — announcement, link, poll, or
 * event. Shown on a community's page and interleaved into the Discover deck.
 */
@Serializable
data class CommunityPostCard(
    val postId: String,
    val communityId: String? = null,
    val communityName: String? = null,
    val communityLogoUrl: String? = null,
    val kind: String? = null, // "announcement" | "link" | "poll" | "event"
    val title: String? = null,
    val body: String? = null,
    val imageUrl: String? = null,
    val linkUrl: String? = null,
    val ctaLabel: String? = null,
    val poll: Poll? = null,
    /**
     * Only meaningful to the owning community viewing its own posts list —
     * everyone else's query only ever returns active == true posts anyway.
     */
    val active: Boolean? = null,
    val myVote: String? = null,
    /** ISO 8601 with a UTC offset — see `eventDate` for a parsed Instant. */
    val eventAt: String? = null,
    val location: String? = null,
    val attendeeCount: Int? = null,
    val myRsvp: Boolean? = null,
    val commentCount: Int? = null,
    val createdAt: String? = null,
) {
    val id: String get() = postId
    val url: Uri? get() = linkUrl.toUriOrNull()

    val eventDate: Instant? get() = eventAt?.let(::parseIso8601)

    val displayPhotos: List<Photo>
        get() {
            val imageUrl = imageUrl ?: return emptyList()
            return listOf(Photo(storagePath = "post-image-$postId", order = 0, url = imageUrl))
        }
}

/** POST /api/posts/{postId}/vote response. */
@Serializable
data class VoteResult(
    val poll: Poll? = null,
    val myVote: String? = null,
)

/** POST/DELETE /api/posts/{postId}/rsvp response. */
@Serializable
data class RsvpResult(
    val attendeeCount: Int? = null,
    val going: Boolean? = null,
)

/** GET /api/communities, GET /api/communities/mine */
@Serializable
data class CommunityListResponse(
    val communities: List<CommunityProfile>? = null,
)

/** GET /api/communities/{cid}/posts, GET /api/communities/feed */
@Serializable
data class CommunityPostsResponse(
    val posts: List<CommunityPostCard>? = null,
)

/**
 * A slim member profile — GET /api/communities/{cid}/members deliberately
 * never returns the full dating-card view (bio/socials/prompts stay out).
 */
@Serializable
data class CommunityMember(
    val uid: String,
    val displayName: String? = null,
    val photo: Photo? = null,
    val region: String? = null,
) {
    val id: String get() = uid
}

/** GET /api/communities/{cid}/members */
@Serializable
data class CommunityMembersResponse(
    val members: List<CommunityMember>? = null,
)
