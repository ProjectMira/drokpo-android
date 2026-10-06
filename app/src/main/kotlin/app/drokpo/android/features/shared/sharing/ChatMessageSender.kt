package app.drokpo.android.features.shared.sharing

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Writes chat messages from outside ChatThreadScreen (the share sheet's
 * "send to a match" rows). Same direct-Firestore contract as
 * ChatThreadView.send: the security rules verify participation, active
 * status, and senderId; the on_message_created Cloud Function handles
 * lastMessage/unreadCount denormalization and the push.
 *
 * The null keys are written EXPLICITLY (iOS NSNull()) — the rules and the
 * function expect every field to be present.
 */
object ChatMessageSender {
    suspend fun sendText(text: String, matchId: String, senderId: String) {
        val data = hashMapOf<String, Any?>(
            "senderId" to senderId,
            "text" to text,
            "imageUrl" to null,
            "audioUrl" to null,
            "audioDurationSec" to null,
            "createdAt" to FieldValue.serverTimestamp(),
            "readAt" to null,
        )
        FirebaseFirestore.getInstance()
            .collection("matches").document(matchId)
            .collection("messages")
            .add(data)
            .await()
    }
}
