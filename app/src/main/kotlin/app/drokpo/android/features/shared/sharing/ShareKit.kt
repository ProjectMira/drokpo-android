package app.drokpo.android.features.shared.sharing

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.ui.graphics.vector.ImageVector
import app.drokpo.android.core.AppConfig
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.NewsCard
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException

// Port of Features/Shared/Sharing/ShareKit.swift (CONTRACT §B.11). Pure logic —
// no Android framework calls except the `parse(Uri)` convenience overload, so it
// is unit-testable on the JVM (android.net.Uri is stubbed there).

/**
 * Something a member can share: a person's profile, a community page, a
 * community post, or a news story. Produces the hosted share link
 * (https://drokpo-backend.web.app/s/{type}/{id} — served by the backend's
 * public/share.html via a Hosting rewrite) and the message text dropped
 * into a chat when sharing in-app.
 */
sealed interface ShareableContent {
    data class Profile(val card: FeedCard) : ShareableContent
    data class Community(val cid: String, val name: String?) : ShareableContent
    data class Post(val post: CommunityPostCard) : ShareableContent
    data class News(val news: NewsCard) : ShareableContent

    /**
     * Path segment mirrored by share.html, the /s/{type}/{id} Hosting rewrite, and
     * ShareDestination.parse — keep all of them in sync.
     */
    val pathType: String
        get() = when (this) {
            is Profile -> if (card.isCommunity) "community" else "user"
            is Community -> "community"
            is Post -> "post"
            is News -> "news"
        }

    val contentId: String
        get() = when (this) {
            is Profile -> card.uid
            is Community -> cid
            is Post -> post.postId
            is News -> news.newsId
        }

    /** Caption used as the share-message headline and system-share subject. */
    val title: String
        get() = when (this) {
            is Profile -> card.displayName ?: "A Drokpo member"
            is Community -> name ?: "A community on Drokpo"
            is Post -> post.title ?: post.communityName ?: "A community post"
            is News -> news.title ?: "A news story"
        }

    /** "${AppConfig.API_BASE_URL}/s/$pathType/$contentId" (iOS `appendingPathComponent`). */
    val webUrl: String
        get() = "${AppConfig.API_BASE_URL.trimEnd('/')}/s/${encodePathSegment(pathType)}/${encodePathSegment(contentId)}"

    /**
     * What lands in the chat: a readable caption line above the link. The
     * recipient's bubble renders this as a tappable shared-content card
     * (SharedLinkMessage splits it back apart).
     */
    val messageText: String get() = "$title\n$webUrl"

    val id: String get() = "$pathType-$contentId"
}

/**
 * A parsed incoming share link — from a chat-bubble tap, the drokpo://
 * scheme, or a universal (App) link. MainTabs resolves and presents it.
 */
@Serializable
sealed interface ShareDestination {
    @Serializable data class User(val uid: String) : ShareDestination
    @Serializable data class Community(val cid: String) : ShareDestination
    @Serializable data class Post(val postId: String) : ShareDestination
    @Serializable data class News(val newsId: String) : ShareDestination

    /** "user-{id}" | "community-{id}" | "post-{id}" | "news-{id}" */
    val id: String
        get() = when (this) {
            is User -> "user-$uid"
            is Community -> "community-$cid"
            is Post -> "post-$postId"
            is News -> "news-$newsId"
        }

    companion object {
        /** type ∈ user/community/post/news and id non-empty, else null. */
        fun make(type: String, id: String): ShareDestination? {
            if (id.isEmpty()) return null
            return when (type) {
                "user" -> User(id)
                "community" -> Community(id)
                "post" -> Post(id)
                "news" -> News(id)
                else -> null
            }
        }

        /**
         * Accepts both link forms: https://drokpo-backend.web.app/s/{type}/{id}
         * and drokpo://s/{type}/{id} (where "s" arrives as the URL host).
         * Anything else → null. java.net.URI, not android.net.Uri, so it runs in JVM tests.
         */
        fun parse(url: String): ShareDestination? {
            val uri = try {
                URI(url)
            } catch (e: URISyntaxException) {
                return null
            }
            val segments: List<String> = when {
                uri.scheme.equals("drokpo", ignoreCase = true) -> listOfNotNull(uri.host) + uri.pathSegments()
                uri.host != null && uri.host.equals(AppConfig.SHARE_HOST, ignoreCase = true) -> uri.pathSegments()
                else -> return null
            }
            if (segments.size < 3 || segments[0] != "s") return null
            return make(type = segments[1], id = segments[2])
        }

        /** = parse(uri.toString()) — for intent data (MainActivity). */
        fun parse(uri: Uri): ShareDestination? = parse(uri.toString())
    }
}

/**
 * A chat message that carries a share link: the parsed destination plus the
 * caption line(s) around it, for rendering as a tappable card instead of a
 * wall of URL.
 */
data class SharedLinkMessage(val destination: ShareDestination, val caption: String?) {
    /** "Shared a profile" / "… community" / "… community post" / "… news story". */
    val kindLabel: String
        get() = when (destination) {
            is ShareDestination.User -> "a profile"
            is ShareDestination.Community -> "a community"
            is ShareDestination.Post -> "a community post"
            is ShareDestination.News -> "a news story"
        }

    /** person.crop.circle / person.3 / megaphone / newspaper. */
    val icon: ImageVector
        get() = when (destination) {
            is ShareDestination.User -> Icons.Outlined.AccountCircle
            is ShareDestination.Community -> Icons.Outlined.Groups
            is ShareDestination.Post -> Icons.Outlined.Campaign
            is ShareDestination.News -> Icons.Outlined.Newspaper
        }

    companion object {
        /**
         * Lines split on \n and trimmed; the first line that parses as a ShareDestination is the link;
         * caption = the remaining non-empty lines joined with \n (null if none). Null when no link line.
         * (Swift's `split(separator:)` drops empty lines, so this does too.)
         */
        fun from(text: String): SharedLinkMessage? {
            val lines = text.split('\n').filter { it.isNotEmpty() }.map { it.trim() }
            var destination: ShareDestination? = null
            val linkLine = lines.firstOrNull { line ->
                destination = ShareDestination.parse(line)
                destination != null
            } ?: return null
            val rest = lines.filter { it != linkLine && it.isNotEmpty() }.joinToString("\n")
            return SharedLinkMessage(destination = destination ?: return null, caption = rest.ifEmpty { null })
        }
    }
}

/** Decoded, non-empty path segments (Foundation's `URL.pathComponents` minus the "/" entries). */
private fun URI.pathSegments(): List<String> =
    rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.map(::percentDecode)

private fun percentDecode(segment: String): String {
    if ('%' !in segment) return segment
    val out = ByteArrayOutputStream()
    var i = 0
    while (i < segment.length) {
        val c = segment[i]
        if (c == '%' && i + 2 < segment.length) {
            val byte = segment.substring(i + 1, i + 3).toIntOrNull(16)
            if (byte != null) {
                out.write(byte)
                i += 3
                continue
            }
        }
        out.write(c.toString().toByteArray(Charsets.UTF_8))
        i++
    }
    return out.toString(Charsets.UTF_8.name())
}

/** Percent-encodes everything outside RFC 3986 `pchar`, like `URL.appendingPathComponent`. */
private fun encodePathSegment(segment: String): String {
    val allowed = "-._~!$&'()*+,;=:@"
    val sb = StringBuilder()
    for (byte in segment.toByteArray(Charsets.UTF_8)) {
        val ch = (byte.toInt() and 0xFF).toChar()
        if ((ch.code < 0x80 && ch.isLetterOrDigit()) || ch in allowed) {
            sb.append(ch)
        } else {
            sb.append('%').append("%02X".format(byte.toInt() and 0xFF))
        }
    }
    return sb.toString()
}
