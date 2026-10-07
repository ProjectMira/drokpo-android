package app.drokpo.android.features.chats

import app.drokpo.android.core.model.Match
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

// The chat group's direct Firestore access, behind small seams so ChatStore and
// ChatThreadModel run in JVM tests with fakes. Match membership is decided by the
// backend (the swipe transaction), but once matches exist the client listens to
// them directly — the Firestore rules let participants read their own match docs
// and messages, and create messages in an active match as themselves.

/** A registered snapshot listener (Firestore `ListenerRegistration`). */
internal fun interface ListenerHandle {
    fun remove()
}

/** Live list of the signed-in user's active matches. */
internal fun interface MatchesListener {
    fun listen(uid: String, onMatches: (List<Match>) -> Unit, onError: (Exception) -> Unit): ListenerHandle
}

/** Live list of a match's latest messages, oldest first. */
internal fun interface MessagesListener {
    fun listen(matchId: String, onMessages: (List<ChatMessage>) -> Unit, onError: (Exception) -> Unit): ListenerHandle
}

/** Writes one message doc into `matches/{matchId}/messages`. */
internal fun interface MessageWriter {
    suspend fun add(matchId: String, senderId: String, message: OutgoingMessage)
}

/**
 * What a send writes, before the sender and server timestamp are filled in. A media
 * message's [text] also carries a placeholder ("📷 Photo" / "🎤 Voice message") so a build
 * that predates media rendering still shows a readable bubble instead of an empty one.
 */
internal data class OutgoingMessage(
    val text: String,
    val imageUrl: String? = null,
    val audioUrl: String? = null,
    val audioDurationSec: Int? = null,
) {
    /**
     * The Firestore document. The null keys are written EXPLICITLY (iOS `NSNull()`): the
     * on_message_created Cloud Function and the Sent-messages screen expect every field to be
     * present. [createdAt] is `FieldValue.serverTimestamp()` in production.
     */
    fun firestoreData(senderId: String, createdAt: Any): Map<String, Any?> = linkedMapOf(
        "senderId" to senderId,
        "text" to text,
        "imageUrl" to imageUrl,
        "audioUrl" to audioUrl,
        "audioDurationSec" to audioDurationSec,
        "createdAt" to createdAt,
        "readAt" to null,
    )
}

/** Production implementations (FirebaseFirestore is only touched when a listener starts). */
internal object FirestoreChats {
    /**
     * `matches` where `users` array-contains uid AND `status == "active"`: an unmatched or
     * blocked match drops out of the snapshot on its own once the backend flips its status.
     */
    val matches = MatchesListener { uid, onMatches, onError ->
        val registration = FirebaseFirestore.getInstance()
            .collection("matches")
            .whereArrayContains("users", uid)
            .whereEqualTo("status", "active")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error)
                    return@addSnapshotListener
                }
                val docs = snapshot?.documents.orEmpty()
                onMatches(docs.map { doc -> Match.fromFirestore(doc.id, doc.data.orEmpty()) })
            }
        ListenerHandle { registration.remove() }
    }

    /** `matches/{id}/messages` ordered by `createdAt`, the latest 100. */
    val messages = MessagesListener { matchId, onMessages, onError ->
        val registration = FirebaseFirestore.getInstance()
            .collection("matches").document(matchId)
            .collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .limitToLast(100)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error)
                    return@addSnapshotListener
                }
                val docs = snapshot?.documents.orEmpty()
                onMessages(
                    docs.mapNotNull { doc ->
                        // ESTIMATE resolves pending server timestamps so our own just-sent
                        // messages don't jump around when the write lands.
                        val data = doc.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE).orEmpty()
                        ChatMessage.fromFirestore(doc.id, data)
                    },
                )
            }
        ListenerHandle { registration.remove() }
    }

    /**
     * Direct Firestore write — the security rules verify participation, active status, and
     * senderId; the on_message_created Cloud Function handles lastMessage/unreadCount
     * denormalization and the push.
     */
    val writer = MessageWriter { matchId, senderId, message ->
        FirebaseFirestore.getInstance()
            .collection("matches").document(matchId)
            .collection("messages")
            .add(message.firestoreData(senderId, FieldValue.serverTimestamp()))
            .await()
    }
}
