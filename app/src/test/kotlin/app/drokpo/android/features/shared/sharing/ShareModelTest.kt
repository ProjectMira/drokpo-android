package app.drokpo.android.features.shared.sharing

import app.drokpo.android.core.ApiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShareModelTest {
    @get:Rule val main = SharingMainDispatcherRule()

    private data class Sent(val text: String, val matchId: String, val senderId: String)

    @Test fun sendMarksSendingThenSent() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val sent = mutableListOf<Sent>()
        val model = ShareModel(currentUid = { "me" }, sendText = { t, m, s -> gate.await(); sent += Sent(t, m, s) })

        model.send("Pema\nhttps://drokpo-backend.web.app/s/user/u-pema", "m1")
        runCurrent()
        assertEquals(setOf("m1"), model.state.value.sendingMatchIds)
        assertTrue(model.state.value.sentMatchIds.isEmpty())

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(Sent("Pema\nhttps://drokpo-backend.web.app/s/user/u-pema", "m1", "me")), sent)
        assertEquals(setOf("m1"), model.state.value.sentMatchIds)
        assertTrue(model.state.value.sendingMatchIds.isEmpty())
        assertNull(model.state.value.errorMessage)
    }

    @Test fun failureShowsErrorAndRestoresSendButton() = runTest(main.dispatcher) {
        val model = ShareModel(
            currentUid = { "me" },
            sendText = { _, _, _ -> throw ApiError.Http(403, "Missing or insufficient permissions.") },
        )
        model.send("text", "m1")
        advanceUntilIdle()
        assertEquals("Missing or insufficient permissions.", model.state.value.errorMessage)
        assertTrue(model.state.value.sendingMatchIds.isEmpty())
        assertTrue(model.state.value.sentMatchIds.isEmpty())

        model.dismissError()
        assertNull(model.state.value.errorMessage)
    }

    @Test fun signedOutIsANoOp() = runTest(main.dispatcher) {
        var calls = 0
        val model = ShareModel(currentUid = { null }, sendText = { _, _, _ -> calls++ })
        model.send("text", "m1")
        advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals(ShareSheetState(), model.state.value)
    }

    @Test fun doubleTapAndResendAreIgnored() = runTest(main.dispatcher) {
        var calls = 0
        val model = ShareModel(currentUid = { "me" }, sendText = { _, _, _ -> calls++ })
        model.send("text", "m1")
        model.send("text", "m1") // while sending
        advanceUntilIdle()
        model.send("text", "m1") // after "Sent"
        advanceUntilIdle()
        assertEquals(1, calls)
    }

    @Test fun matchesAreIndependent() = runTest(main.dispatcher) {
        val model = ShareModel(
            currentUid = { "me" },
            sendText = { _, matchId, _ -> if (matchId == "bad") error("boom") },
        )
        model.send("text", "good")
        model.send("text", "bad")
        advanceUntilIdle()
        assertEquals(setOf("good"), model.state.value.sentMatchIds)
        assertEquals("boom", model.state.value.errorMessage)
    }
}
