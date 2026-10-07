package app.drokpo.android.features.chats

import app.drokpo.android.core.model.firestoreInstant
import app.drokpo.android.core.model.firestoreInt
import app.drokpo.android.core.model.firestoreString
import java.time.Instant

/** Port of ChatMessage (one Firestore message doc). (CONTRACT §B.6.) */
data class ChatMessage(
    val id: String,
    val senderId: String,
    val text: String,
    val imageUrl: String? = null,
    val audioUrl: String? = null,
    val audioDurationSec: Int? = null,
    val createdAt: Instant,
) {
    val hasMedia: Boolean get() = imageUrl != null || audioUrl != null

    companion object {
        /**
         * One `matches/{matchId}/messages/{messageId}` doc, as ChatThreadView's listener reads it.
         * The caller reads the data with `ServerTimestampBehavior.ESTIMATE`: that resolves pending
         * server timestamps so our own just-sent messages don't jump around when the write lands.
         *
         * Docs without a `senderId` or `text` are skipped (null). A missing `createdAt` falls back to
         * [now], like iOS `?? .now`. Android's Firestore SDK returns integers as Long, hence the
         * Number-tolerant `audioDurationSec` read.
         */
        internal fun fromFirestore(
            id: String,
            data: Map<String, Any?>,
            now: () -> Instant = Instant::now,
        ): ChatMessage? {
            val senderId = data.firestoreString("senderId") ?: return null
            val text = data.firestoreString("text") ?: return null
            return ChatMessage(
                id = id,
                senderId = senderId,
                text = text,
                imageUrl = data.firestoreString("imageUrl"),
                audioUrl = data.firestoreString("audioUrl"),
                audioDurationSec = data.firestoreInt("audioDurationSec"),
                createdAt = data.firestoreInstant("createdAt") ?: now(),
            )
        }
    }
}
