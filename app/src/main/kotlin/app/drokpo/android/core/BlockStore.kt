package app.drokpo.android.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class BlockedUser(
    val uid: String,
    val displayName: String? = null,
    /** Epoch milliseconds. */
    val blockedAt: Long,
) {
    val id: String get() = uid
}

/**
 * Local record of who you've blocked. The backend stores blocks but exposes
 * no list endpoint, so the app remembers them on-device; unblocking calls
 * DELETE /api/blocks/{uid} and forgets the entry.
 *
 * The list lives in [AppPreferences] (`drokpo.blockedUsers`, the iOS
 * UserDefaults key) and every change is an atomic DataStore edit; [blocked]
 * mirrors what's on disk.
 */
class BlockStore internal constructor(
    private val prefs: AppPreferences,
    private val scope: CoroutineScope = AppGraph.appScope,
    private val unblockRequest: suspend (uid: String) -> Unit = { uid ->
        ApiClient.delete<EmptyResponse>("/api/blocks/$uid")
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Newest first. */
    val blocked: StateFlow<List<BlockedUser>> = prefs.blockedUsersJson
        .map(::decode)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** No-op if [uid] is already recorded; otherwise inserts at the top. */
    fun record(uid: String, displayName: String?) {
        val entry = BlockedUser(uid = uid, displayName = displayName, blockedAt = clock())
        scope.launch {
            prefs.updateBlockedUsersJson { json -> encode(recording(decode(json), entry)) }
        }
    }

    /** DELETE /api/blocks/{uid}, then forget the entry. Throws — the entry is kept on failure. */
    suspend fun unblock(user: BlockedUser) {
        unblockRequest(user.uid)
        prefs.updateBlockedUsersJson { json -> encode(decode(json).filterNot { it.uid == user.uid }) }
    }

    /** Clear local state on sign-out; the entries belong to the old account. */
    fun reset() {
        scope.launch { prefs.updateBlockedUsersJson { encode(emptyList()) } }
    }

    internal companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(BlockedUser.serializer())

        /** iOS `guard !blocked.contains(where:)` + `insert(at: 0)`. */
        fun recording(list: List<BlockedUser>, entry: BlockedUser): List<BlockedUser> =
            if (list.any { it.uid == entry.uid }) list else listOf(entry) + list

        /** Unreadable data reads as an empty list (iOS: `try?` decode). */
        fun decode(raw: String?): List<BlockedUser> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                json.decodeFromString(serializer, raw)
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun encode(list: List<BlockedUser>): String = json.encodeToString(serializer, list)
    }
}
