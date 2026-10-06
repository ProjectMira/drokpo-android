package app.drokpo.android.core

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM entry point (declared in the manifest for `MESSAGING_EVENT`):
 * - [onNewToken]: hands rotated tokens to PushService (uploaded if signed in
 *   and notifications are allowed) — iOS `messaging(_:didReceiveRegistrationToken:)`.
 * - [onMessageReceived]: only reached while the app is in the foreground
 *   (the backend's pushes carry a notification block, which the system tray
 *   shows by itself in the background). iOS presents banners in the
 *   foreground too (`willPresent` → `.banner`), so post one here.
 *
 * Taps — tray or ours — open MainActivity with the `type`/`matchId` extras,
 * which it routes through DeepLinkRouter.
 */
class DrokpoMessagingService : FirebaseMessagingService() {
    // Deprecated in firebase-messaging 25.1 in favour of onRegistered(), but
    // that "V1" flow hands out Firebase Installation IDs, while the backend
    // (functions/main.py send_each_for_multicast) and the iOS app use FCM
    // registration tokens. Stay on tokens until the backend moves.
    @Deprecated("FCM registration tokens; see the comment above.")
    @Suppress("DEPRECATION")
    override fun onNewToken(token: String) {
        AppGraph.push.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val content = PushContent.from(
            notificationTitle = message.notification?.title,
            notificationBody = message.notification?.body,
            data = message.data,
        ) ?: return
        val payload = PushPayload.from(message.data)
        // Distinct ids so a burst of pushes doesn't collapse into one banner.
        val id = message.messageId?.hashCode() ?: System.currentTimeMillis().toInt()
        PushNotifications.show(applicationContext, content, payload, id)
    }
}
