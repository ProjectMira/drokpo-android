package app.drokpo.android.features.chats

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * What to render around the message at an index: an optional day-separator chip above, and an
 * optional time caption below (iOS ChatThreadView `RowMetadata`).
 */
internal data class RowMetadata(val daySeparator: Instant? = null, val timestamp: Instant? = null)

/** End of a run when the next message is more than 10 minutes later. */
private val RUN_GAP: Duration = Duration.ofMinutes(10)

/**
 * iOS `metadata(at:)`: a day separator above the first message of each calendar day (in [zone]);
 * a time caption after the last message overall, at the end of a consecutive run from the same
 * sender, or before a gap of more than 10 minutes to the next message.
 */
internal fun rowMetadata(messages: List<ChatMessage>, index: Int, zone: ZoneId): RowMetadata {
    val message = messages[index]
    val day = if (index == 0 || !sameDay(message.createdAt, messages[index - 1].createdAt, zone)) {
        message.createdAt
    } else {
        null
    }
    val time = if (index == messages.lastIndex) {
        message.createdAt
    } else {
        val next = messages[index + 1]
        if (next.senderId != message.senderId || Duration.between(message.createdAt, next.createdAt) > RUN_GAP) {
            message.createdAt
        } else {
            null
        }
    }
    return RowMetadata(daySeparator = day, timestamp = time)
}

/** One row of the thread's lazy list: a day chip, a bubble, or a time caption. */
internal sealed interface ThreadRow {
    val key: String

    data class Day(val date: Instant, val firstMessageId: String) : ThreadRow {
        override val key: String get() = "day-$firstMessageId"
    }

    data class Bubble(val message: ChatMessage) : ThreadRow {
        override val key: String get() = message.id
    }

    data class Time(val date: Instant, val message: ChatMessage) : ThreadRow {
        override val key: String get() = "time-${message.id}"
    }
}

/** The thread flattened into list rows, oldest first (iOS builds the same ForEach inline). */
internal fun threadRows(messages: List<ChatMessage>, zone: ZoneId): List<ThreadRow> = buildList {
    messages.indices.forEach { index ->
        val message = messages[index]
        val meta = rowMetadata(messages, index, zone)
        meta.daySeparator?.let { add(ThreadRow.Day(it, message.id)) }
        add(ThreadRow.Bubble(message))
        meta.timestamp?.let { add(ThreadRow.Time(it, message)) }
    }
}

/**
 * "Today", "Yesterday", or the date with an abbreviated month, a day and a year in the user's
 * locale — iOS `.dateTime.month(.abbreviated).day().year()` ("Oct 5, 2026" in en-US).
 */
internal fun daySeparatorText(
    date: Instant,
    now: Instant,
    zone: ZoneId,
    locale: Locale = Locale.getDefault(),
): String {
    val day = date.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(day)
    }
}

private fun sameDay(a: Instant, b: Instant, zone: ZoneId): Boolean =
    a.atZone(zone).toLocalDate() == b.atZone(zone).toLocalDate()

/**
 * The text shown in the conversation row: "You: …" when the last message is mine (iOS
 * `entry.lastMessageSenderId == session.uid`).
 */
internal fun previewText(entry: ChatStore.Entry, myUid: String?): String? {
    val text = entry.lastMessageText ?: return null
    return if (entry.lastMessageSenderId == myUid) "You: $text" else text
}

/** A push-tap waiting in DeepLinkRouter, as ChatsScreen consumes it (CONTRACT §C.4 step 3). */
internal data class ChatsDeepLink(val openThread: String?)

/**
 * A "message" push opens the thread; a "match" push just lands on the list. Null = nothing pending
 * (leave the router alone); otherwise the router is cleared whatever the outcome.
 */
internal fun chatsDeepLink(pendingType: String?, pendingMatchId: String?): ChatsDeepLink? {
    if (pendingMatchId == null && pendingType == null) return null
    return ChatsDeepLink(openThread = pendingMatchId.takeIf { pendingType == "message" })
}

/**
 * ChatsScreen's push deep link wants the stack to be list → that thread (iOS `path = [matchId]`).
 * True when it already is — the top entry is that thread, right above the list — so nothing is
 * pushed or popped (the thread keeps its state and scroll position).
 */
internal fun isThreadOnTopOfList(
    topIsThread: Boolean,
    topMatchId: String?,
    previousIsList: Boolean,
    matchId: String,
): Boolean = topIsThread && topMatchId == matchId && previousIsList
