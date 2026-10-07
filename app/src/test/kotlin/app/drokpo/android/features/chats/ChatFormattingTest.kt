package app.drokpo.android.features.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

class ChatFormattingTest {
    private val zone = ZoneOffset.UTC
    private val me = "me"

    private fun at(day: Int, hour: Int, minute: Int): Instant =
        LocalDateTime.of(2026, 10, day, hour, minute).toInstant(ZoneOffset.UTC)

    private fun msg(id: String, sender: String, time: Instant) = ChatMessage(id, sender, "text $id", createdAt = time)

    @Test
    fun daySeparatorAboveTheFirstMessageOfEachDay() {
        val messages = listOf(
            msg("a", "pema", at(4, 23, 50)),
            msg("b", "pema", at(4, 23, 55)),
            msg("c", me, at(5, 0, 1)),
        )
        assertEquals(at(4, 23, 50), rowMetadata(messages, 0, zone).daySeparator)
        assertNull(rowMetadata(messages, 1, zone).daySeparator)
        assertEquals(at(5, 0, 1), rowMetadata(messages, 2, zone).daySeparator)
    }

    @Test
    fun timeCaptionEndsARunOrPrecedesAGapOverTenMinutes() {
        val messages = listOf(
            msg("a", "pema", at(5, 10, 0)),
            msg("b", "pema", at(5, 10, 10)), // exactly 10 min later: same run
            msg("c", "pema", at(5, 10, 21)), // 11 min after b: b gets a caption
            msg("d", me, at(5, 10, 22)), // sender changes: c gets a caption
            msg("e", me, at(5, 10, 23)), // last overall: caption
        )
        val times = messages.indices.map { rowMetadata(messages, it, zone).timestamp }
        assertEquals(listOf(null, at(5, 10, 10), at(5, 10, 21), null, at(5, 10, 23)), times)
    }

    @Test
    fun threadRowsFlattenSeparatorsBubblesAndCaptions() {
        val messages = listOf(
            msg("a", "pema", at(4, 9, 0)),
            msg("b", me, at(5, 9, 0)),
            msg("c", me, at(5, 9, 1)),
        )
        val rows = threadRows(messages, zone)
        assertEquals(
            listOf("day-a", "a", "time-a", "day-b", "b", "c", "time-c"),
            rows.map { it.key },
        )
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        assertEquals(emptyList<ThreadRow>(), threadRows(emptyList(), zone))
    }

    @Test
    fun daySeparatorLabels() {
        val now = at(7, 15, 0)
        assertEquals("Today", daySeparatorText(at(7, 0, 0), now, zone, Locale.US))
        assertEquals("Yesterday", daySeparatorText(at(6, 23, 59), now, zone, Locale.US))
        assertEquals("Oct 5, 2026", daySeparatorText(at(5, 12, 0), now, zone, Locale.US))
        assertEquals("Oct 7, 2025", daySeparatorText(at(7, 12, 0).minusSeconds(365L * 24 * 3600), now, zone, Locale.US))
    }

    @Test
    fun previewPrefixesMyOwnLastMessage() {
        val theirs = ChatStore.Entry("m", "pema", lastMessageText = "hi", lastMessageSenderId = "pema")
        assertEquals("hi", previewText(theirs, me))
        assertEquals("You: hi", previewText(theirs.copy(lastMessageSenderId = me), me))
        assertNull(previewText(ChatStore.Entry("m", "pema"), me))
    }

    @Test
    fun pushDeepLinks() {
        // Nothing pending: leave the router alone.
        assertNull(chatsDeepLink(pendingType = null, pendingMatchId = null))
        // A "message" push opens its thread.
        assertEquals(ChatsDeepLink(openThread = "m1"), chatsDeepLink("message", "m1"))
        // A "match" push lands on the list — but is still consumed (router cleared).
        assertEquals(ChatsDeepLink(openThread = null), chatsDeepLink("match", "m1"))
        assertEquals(ChatsDeepLink(openThread = null), chatsDeepLink("message", null))
        assertEquals(ChatsDeepLink(openThread = null), chatsDeepLink(null, "m1"))
    }

    @Test
    fun pushDeepLinkLeavesTheStackAloneOnlyWhenItIsAlreadyListThenThatThread() {
        // list → m1, push for m1: nothing to do (keeps the thread's state).
        assertTrue(isThreadOnTopOfList(topIsThread = true, topMatchId = "m1", previousIsList = true, matchId = "m1"))
        // list → m2: pop to the list, push m1.
        assertFalse(isThreadOnTopOfList(topIsThread = true, topMatchId = "m2", previousIsList = true, matchId = "m1"))
        // list → m1 → profile: the top isn't a thread.
        assertFalse(isThreadOnTopOfList(topIsThread = false, topMatchId = null, previousIsList = false, matchId = "m1"))
        // list → m2 → m1 (pushed from a shared profile): m1 isn't right above the list.
        assertFalse(isThreadOnTopOfList(topIsThread = true, topMatchId = "m1", previousIsList = false, matchId = "m1"))
        // Just the list.
        assertFalse(isThreadOnTopOfList(topIsThread = false, topMatchId = null, previousIsList = false, matchId = "m1"))
    }
}
