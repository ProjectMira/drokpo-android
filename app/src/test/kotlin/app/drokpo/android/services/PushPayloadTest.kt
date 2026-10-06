package app.drokpo.android.services

import app.drokpo.android.core.PushContent
import app.drokpo.android.core.PushPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Payloads as functions/main.py sends them. */
class PushPayloadTest {
    @Test
    fun matchMessageAndLikePayloads() {
        assertEquals(PushPayload("match", "a_b"), PushPayload.from(mapOf("type" to "match", "matchId" to "a_b")))
        assertEquals(PushPayload("message", "a_b"), PushPayload.from(mapOf("type" to "message", "matchId" to "a_b")))
        assertEquals(PushPayload("like", null), PushPayload.from(mapOf("type" to "like")))
    }

    @Test
    fun noRoutingKeysMeansNoPayload() {
        assertNull(PushPayload.from(emptyMap()))
        assertNull(PushPayload.from(mapOf("google.message_id" to "0:123", "from" to "sender")))
        assertNull(PushPayload.from(mapOf("type" to "", "matchId" to "")))
    }

    @Test
    fun readsIntentExtrasThroughALookup() {
        val extras = mapOf("type" to "message", "matchId" to "m1", "google.sent_time" to "1")
        assertEquals(PushPayload("message", "m1"), PushPayload.from { extras[it] })
        assertEquals(PushPayload(null, "m1"), PushPayload.from { if (it == "matchId") "m1" else null })
    }

    @Test
    fun contentPrefersTheNotificationBlock() {
        val data = mapOf("type" to "message", "matchId" to "m1")
        assertEquals(
            PushContent("New message", "hello"),
            PushContent.from("New message", "hello", data),
        )
        assertEquals(
            PushContent("You made a new friend!", "You have a new connection on Drokpo — say tashi delek!"),
            PushContent.from(
                "You made a new friend!",
                "You have a new connection on Drokpo — say tashi delek!",
                mapOf("type" to "match", "matchId" to "m1"),
            ),
        )
    }

    @Test
    fun contentFallsBackToDataKeysAndSkipsSilentPushes() {
        assertEquals(PushContent("T", "B"), PushContent.from(null, null, mapOf("title" to "T", "body" to "B")))
        // A media message's preview can be blank only if the sender wrote no text; keep the title.
        assertEquals(PushContent("New message", ""), PushContent.from("New message", "", emptyMap()))
        assertNull(PushContent.from(null, null, mapOf("type" to "like")))
        assertNull(PushContent.from(" ", null, emptyMap()))
    }
}
