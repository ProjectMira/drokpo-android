package app.drokpo.android.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.drokpo.android.MainActivity
import app.drokpo.android.R

/**
 * The data half of a Drokpo push (functions/main.py `_send`):
 * - match:   `{type: "match", matchId}`
 * - like:    `{type: "like"}`
 * - message: `{type: "message", matchId}`
 *
 * The same keys travel as MainActivity intent extras — FCM copies data keys
 * into the launch intent for tray taps, and [PushNotifications] does the same
 * for the notifications it posts itself.
 */
internal data class PushPayload(val type: String?, val matchId: String?) {
    companion object {
        const val KEY_TYPE = "type"
        const val KEY_MATCH_ID = "matchId"

        /** Null when neither key is present (iOS: `guard type != nil || matchId != nil`). */
        fun from(data: Map<String, String>): PushPayload? = from { data[it] }

        /** From intent extras (`intent.getStringExtra`) or any other key lookup. */
        fun from(lookup: (String) -> String?): PushPayload? {
            val type = lookup(KEY_TYPE)?.takeIf { it.isNotEmpty() }
            val matchId = lookup(KEY_MATCH_ID)?.takeIf { it.isNotEmpty() }
            if (type == null && matchId == null) return null
            return PushPayload(type, matchId)
        }
    }
}

/** What a foreground push shows. */
internal data class PushContent(val title: String, val body: String) {
    companion object {
        /**
         * The FCM notification block's title/body (what the backend sends),
         * falling back to `title`/`body` data keys; null when there's nothing
         * to show (a silent data message).
         */
        fun from(notificationTitle: String?, notificationBody: String?, data: Map<String, String>): PushContent? {
            val title = notificationTitle?.takeIf { it.isNotBlank() } ?: data["title"]?.takeIf { it.isNotBlank() }
            val body = notificationBody?.takeIf { it.isNotBlank() } ?: data["body"]?.takeIf { it.isNotBlank() }
            if (title == null && body == null) return null
            return PushContent(title = title ?: "Drokpo", body = body.orEmpty())
        }
    }
}

/** Notification channel + posting for pushes that arrive while the app is in the foreground. */
internal object PushNotifications {
    private const val TAG = "PushNotifications"

    /**
     * The one channel every push uses. The backend sends no Android channel
     * id, so background pushes land on the manifest's
     * `default_notification_channel_id` — this same channel — and foreground
     * ones are posted to it too. High importance = heads-up banners (iOS
     * shows banners even in the foreground).
     */
    const val CHANNEL_DEFAULT = "drokpo_default"
    private const val CHANNEL_DEFAULT_NAME = "Notifications"

    /** DrokpoApplication.onCreate. Safe to call repeatedly. */
    fun createChannels(context: Context) {
        val channel = NotificationChannel(CHANNEL_DEFAULT, CHANNEL_DEFAULT_NAME, NotificationManager.IMPORTANCE_HIGH)
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** Intent that opens MainActivity carrying the push payload (cold start or onNewIntent). */
    fun launchIntent(context: Context, payload: PushPayload?): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            payload?.type?.let { putExtra(PushPayload.KEY_TYPE, it) }
            payload?.matchId?.let { putExtra(PushPayload.KEY_MATCH_ID, it) }
        }

    /**
     * Posts [content] as a heads-up notification whose tap routes like a tray
     * tap: MainActivity with `type` / `matchId` extras.
     */
    fun show(context: Context, content: PushContent, payload: PushPayload?, notificationId: Int) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannels(context)

        val tap = PendingIntent.getActivity(
            context,
            notificationId,
            launchIntent(context, payload),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_DEFAULT)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(
                if (payload?.type == "message") NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_SOCIAL,
            )
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        try {
            manager.notify(notificationId, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission revoked mid-flight", e)
        }
    }
}
