package app.drokpo.android.core

import app.drokpo.android.features.shared.sharing.ShareDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Carries a pending push-notification tap from MainActivity to the UI. Set on
 * tap (including cold-start), consumed by MainTabs/ChatsScreen which then
 * clear it. `type` distinguishes a "match" (land on the Chats list) from a
 * "message" (open the thread) and a "like" (open Likes on "Liked you").
 *
 * The state waits here until the UI that consumes it exists, so a tap that
 * arrives while signed out (or before MainTabs composes) isn't lost.
 */
class DeepLinkRouter internal constructor() {
    private val _pendingMatchId = MutableStateFlow<String?>(null)
    private val _pendingType = MutableStateFlow<String?>(null)

    /** Push-tap payload. `type` = "match" | "message" | "like". */
    val pendingMatchId: StateFlow<String?> = _pendingMatchId.asStateFlow()
    val pendingType: StateFlow<String?> = _pendingType.asStateFlow()

    /**
     * A shared-content link waiting to be shown — set by a chat-bubble tap
     * or an incoming drokpo://s/... (or https://…/s/…) intent, consumed by
     * MainTabs (which presents ShareDestinationSheet and clears it).
     */
    val pendingShare: MutableStateFlow<ShareDestination?> = MutableStateFlow(null)

    /**
     * Set when a "like" push lands, consumed by LikesScreen: open on the
     * "Liked you" segment instead of the default "You liked".
     */
    val focusLikedYou: MutableStateFlow<Boolean> = MutableStateFlow(false)

    fun handle(type: String?, matchId: String?) {
        _pendingType.value = type
        _pendingMatchId.value = matchId
    }

    fun clear() {
        _pendingType.value = null
        _pendingMatchId.value = null
    }

    /**
     * MainActivity's launch/new-intent routing (iOS `onOpenURL` + the
     * notification-tap delegate). Share links take precedence, exactly like
     * iOS returning early from onOpenURL:
     * 1. [dataString] parses as a share link → [pendingShare].
     * 2. else a push payload ([type] and/or [matchId]) → [handle].
     *
     * Returns true when something was routed.
     */
    internal fun handleLaunch(
        dataString: String?,
        type: String?,
        matchId: String?,
        parse: (String) -> ShareDestination? = { ShareDestination.parse(it) },
    ): Boolean {
        val destination = dataString?.takeIf { it.isNotBlank() }?.let(parse)
        if (destination != null) {
            pendingShare.value = destination
            return true
        }
        // iOS: `guard type != nil || matchId != nil else { return }`.
        if (type == null && matchId == null) return false
        handle(type, matchId)
        return true
    }
}
