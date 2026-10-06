package app.drokpo.android.core.model

import android.net.Uri
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

/**
 * GET /api/feed response: real profiles plus every active content queue —
 * ads, news, and community posts — that the Discover deck interleaves in.
 */
@Serializable
data class FeedResponse(
    val candidates: List<FeedCard>? = null,
    val ads: List<AdCard>? = null,
    val news: List<NewsCard>? = null,
    val communityPosts: List<CommunityPostCard>? = null,
)

// MARK: - Typed feed items

/** One entry in a server-ordered feed: `{"type": ..., "data": {...}}`. */
@Serializable(with = FeedItemSerializer::class)
sealed interface FeedItem {
    val id: String

    data class Person(val card: FeedCard) : FeedItem {
        override val id: String get() = "person-${card.uid}"
    }

    data class Ad(val ad: AdCard) : FeedItem {
        override val id: String get() = "ad-${ad.adId}"
    }

    data class News(val item: NewsCard) : FeedItem {
        override val id: String get() = "news-${item.newsId}"
    }

    data class Post(val post: CommunityPostCard) : FeedItem {
        override val id: String get() = "post-${post.postId}"
    }
}

/** Reads a required string discriminator — `container.decode(String.self, forKey:)`. */
private fun JsonObject.requiredString(key: String): String {
    val value = this[key]
    if (value is JsonPrimitive && value.isString) return value.content
    throw SerializationException("Expected a string '$key', found ${value ?: "nothing"}")
}

/** `decodeIfPresent(String.self, forKey:)`: absent/null → null, non-string → error. */
private fun JsonObject.optionalString(key: String): String? = when (val value = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> if (value.isString) value.content else throw SerializationException("Expected a string '$key', found $value")
    else -> throw SerializationException("Expected a string '$key', found $value")
}

object FeedItemSerializer : KSerializer<FeedItem> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("app.drokpo.FeedItem") {
        element("type", String.serializer().descriptor)
        element("data", JsonElement.serializer().descriptor)
    }

    override fun deserialize(decoder: Decoder): FeedItem {
        val input = decoder.asJsonDecoder()
        val obj = input.decodeJsonElement().asJsonObject("a feed item")
        return when (val type = obj.requiredString("type")) {
            "person" -> FeedItem.Person(input.decodeRequired(FeedCard.serializer(), obj, "data"))
            "ad" -> FeedItem.Ad(input.decodeRequired(AdCard.serializer(), obj, "data"))
            "news" -> FeedItem.News(input.decodeRequired(NewsCard.serializer(), obj, "data"))
            "communityPost" -> FeedItem.Post(input.decodeRequired(CommunityPostCard.serializer(), obj, "data"))
            // FailableItem maps this to null, so unknown card kinds are skipped.
            else -> throw SerializationException("Unknown feed item type '$type'")
        }
    }

    override fun serialize(encoder: Encoder, value: FeedItem) {
        val output = encoder.asJsonEncoder()
        val json = output.json
        val (type, data) = when (value) {
            is FeedItem.Person -> "person" to json.encodeToJsonElement(FeedCard.serializer(), value.card)
            is FeedItem.Ad -> "ad" to json.encodeToJsonElement(AdCard.serializer(), value.ad)
            is FeedItem.News -> "news" to json.encodeToJsonElement(NewsCard.serializer(), value.item)
            is FeedItem.Post -> "communityPost" to json.encodeToJsonElement(CommunityPostCard.serializer(), value.post)
        }
        output.encodeJsonElement(
            buildJsonObject {
                put("type", JsonPrimitive(type))
                put("data", data)
            },
        )
    }
}

/** `[FailableItem<FeedItem>]` + `compactMap(\.value)`. */
object FeedItemListSerializer : FailableListSerializer<FeedItem>(FeedItemSerializer)

/**
 * GET /api/feed decoded shape-agnostically: `items` when the server mixes
 * (?shape=items), or the legacy parallel arrays from an older backend —
 * FeedModel falls back to client-side mixing in that case.
 *
 * Only `items` is per-element tolerant; the legacy arrays decode strictly,
 * as in Swift.
 */
@Serializable
data class FeedPage(
    @Serializable(with = FeedItemListSerializer::class)
    val items: List<FeedItem>? = null,
    val candidates: List<FeedCard>? = null,
    val ads: List<AdCard>? = null,
    val news: List<NewsCard>? = null,
    val communityPosts: List<CommunityPostCard>? = null,
)

/**
 * GET /api/communities/home — the joined-communities rail plus a typed feed
 * of their posts with sponsored cards interleaved.
 */
@Serializable
data class CommunitiesHomeResponse(
    val communities: List<CommunityProfile>? = null,
    @Serializable(with = FeedItemListSerializer::class)
    val items: List<FeedItem>? = null,
)

/** One saved (liked) content card from GET /api/likes/content. */
@Serializable(with = LikedContentSerializer::class)
sealed interface LikedContent {
    val id: String
    val likedAt: String?

    data class News(val item: NewsCard, override val likedAt: String?) : LikedContent {
        override val id: String get() = "liked-news-${item.newsId}"
    }

    data class Post(val post: CommunityPostCard, override val likedAt: String?) : LikedContent {
        override val id: String get() = "liked-post-${post.postId}"
    }
}

object LikedContentSerializer : KSerializer<LikedContent> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("app.drokpo.LikedContent") {
        element("type", String.serializer().descriptor)
        element("likedAt", String.serializer().descriptor, isOptional = true)
        element("data", JsonElement.serializer().descriptor)
    }

    override fun deserialize(decoder: Decoder): LikedContent {
        val input = decoder.asJsonDecoder()
        val obj = input.decodeJsonElement().asJsonObject("a liked item")
        val likedAt = obj.optionalString("likedAt")
        return when (val type = obj.requiredString("type")) {
            "news" -> LikedContent.News(input.decodeRequired(NewsCard.serializer(), obj, "data"), likedAt)
            "communityPost" -> LikedContent.Post(input.decodeRequired(CommunityPostCard.serializer(), obj, "data"), likedAt)
            else -> throw SerializationException("Unknown liked content type '$type'")
        }
    }

    override fun serialize(encoder: Encoder, value: LikedContent) {
        val output = encoder.asJsonEncoder()
        val json = output.json
        val (type, data) = when (value) {
            is LikedContent.News -> "news" to json.encodeToJsonElement(NewsCard.serializer(), value.item)
            is LikedContent.Post -> "communityPost" to json.encodeToJsonElement(CommunityPostCard.serializer(), value.post)
        }
        output.encodeJsonElement(
            buildJsonObject {
                put("type", JsonPrimitive(type))
                value.likedAt?.let { put("likedAt", JsonPrimitive(it)) }
                put("data", data)
            },
        )
    }
}

object LikedContentListSerializer : FailableListSerializer<LikedContent>(LikedContentSerializer)

@Serializable
data class LikedContentResponse(
    @Serializable(with = LikedContentListSerializer::class)
    val items: List<LikedContent>? = null,
)

/**
 * A summarized news card for the Discover feed (see backend docs/DATA_SCHEMA.md
 * `news/{newsId}`) — authored by the news-digest skill, never by the app.
 */
@Serializable
data class NewsCard(
    val newsId: String,
    val title: String? = null,
    val gist: String? = null,
    val summary: String? = null,
    val sourceUrl: String? = null,
    val sourceName: String? = null,
    val imageUrl: String? = null,
    val publishedAt: String? = null,
) {
    val id: String get() = newsId

    val url: Uri? get() = sourceUrl.toUriOrNull()

    val displayPhotos: List<Photo>
        get() {
            val imageUrl = imageUrl ?: return emptyList()
            return listOf(Photo(storagePath = "news-image-$newsId", order = 0, url = imageUrl))
        }

    /**
     * `publishedAt` arrives as full ISO 8601 (with or without offset) or a
     * bare date, depending on what the source article exposed.
     */
    val publishedDate: Instant? get() = publishedAt?.let { parsePublishedDate(it) }

    /**
     * "2d ago"-style label for cards and the detail sheet; null when the
     * published date is missing or unparseable.
     */
    val relativePublished: String? get() = publishedDate?.let { abbreviatedRelativeString(it) }
}
