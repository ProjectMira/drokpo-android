package app.drokpo.android.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Instant
import java.util.UUID

@Serializable
data class LastMessage(
    val text: String? = null,
    val senderId: String? = null,
    /**
     * When the last message was sent (ISO 8601) — the chat list's primary sort
     * key ([Match.sortDate]). iOS's `LastMessage` struct has no such field, but
     * ChatStore reads `lastMessage.createdAt` straight off the Firestore doc;
     * GET /api/matches returns it too (the raw match doc passes through).
     */
    val createdAt: String? = null,
) {
    companion object {
        /**
         * The `lastMessage` map on a `matches/{matchId}` Firestore doc (written
         * by the on_message_created Cloud Function). Mirrors the field reads in
         * iOS ChatStore.apply(documents:).
         */
        fun fromFirestore(data: Map<String, Any?>?): LastMessage? {
            data ?: return null
            return LastMessage(
                text = data.firestoreString("text"),
                senderId = data.firestoreString("senderId"),
                createdAt = data.firestoreInstant("createdAt")?.toString(),
            )
        }
    }
}

@Serializable
data class Match(
    val matchId: String? = null,
    val users: List<String>? = null,
    val status: String? = null,
    val otherUser: FeedCard? = null,
    val lastMessage: LastMessage? = null,
    val unreadCount: Map<String, Int>? = null,
    val createdAt: String? = null,
) {
    // Swift falls back to a fresh UUID on every access; one per instance keeps
    // Compose list keys stable while still never colliding.
    @Transient
    private val fallbackId: String = UUID.randomUUID().toString()

    val id: String get() = matchId ?: otherUser?.uid ?: fallbackId

    fun unread(uid: String?): Int {
        uid ?: return 0
        return unreadCount?.get(uid) ?: 0
    }

    /**
     * Chat-list ordering (newest first), as iOS ChatStore.apply(documents:):
     * the last message's time, else when the match was made, else the distant
     * past (iOS `.distantPast`; [Instant.EPOCH] sorts the same way here).
     */
    val sortDate: Instant
        get() = lastMessage?.createdAt?.let(::parseIso8601)
            ?: createdAt?.let(::parseIso8601)
            ?: Instant.EPOCH

    companion object {
        /**
         * A `matches/{matchId}` Firestore doc, as the chat-list listener sees it
         * (the Firestore rules let participants read their own match docs).
         * `otherUser` is never on the doc — profiles aren't client-readable and
         * come from GET /api/matches. Firestore Timestamps become the same ISO
         * 8601 string the REST endpoint returns. Android's Firestore SDK hands
         * integers back as Long, hence the Number-tolerant reads.
         */
        fun fromFirestore(documentId: String, data: Map<String, Any?>): Match = Match(
            matchId = documentId,
            users = data.firestoreStringList("users"),
            status = data.firestoreString("status"),
            lastMessage = LastMessage.fromFirestore(data.firestoreMap("lastMessage")),
            unreadCount = data.firestoreIntMap("unreadCount"),
            createdAt = data.firestoreInstant("createdAt")?.toString(),
        )
    }
}

/** One entry from GET /api/swipes or GET /api/swipes/received. */
@Serializable
data class SwipeEntry(
    val uid: String? = null,
    val action: String? = null,
    val createdAt: String? = null,
    val otherUser: FeedCard? = null,
    val matchId: String? = null,
    val matchStatus: String? = null,
) {
    @Transient
    private val fallbackId: String = UUID.randomUUID().toString()

    val id: String get() = uid ?: otherUser?.uid ?: fallbackId
    val isMatched: Boolean get() = matchId != null
}

@Serializable
data class SwipeResult(
    val matched: Boolean? = null,
    val matchId: String? = null,
    val match: Match? = null,
) {
    val isMatch: Boolean get() = matched ?: (matchId != null || match != null)
}

/** One entry from GET /api/messages/sent. */
@Serializable
data class SentMessage(
    val messageId: String? = null,
    val matchId: String? = null,
    val senderId: String? = null,
    val text: String? = null,
    val createdAt: String? = null,
) {
    @Transient
    private val fallbackId: String = UUID.randomUUID().toString()

    val id: String get() = messageId ?: fallbackId

    val sentDate: Instant? get() = createdAt?.let(::parseIso8601)
}
