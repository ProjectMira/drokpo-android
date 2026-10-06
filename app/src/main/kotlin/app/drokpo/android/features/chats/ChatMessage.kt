package app.drokpo.android.features.chats

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
}
