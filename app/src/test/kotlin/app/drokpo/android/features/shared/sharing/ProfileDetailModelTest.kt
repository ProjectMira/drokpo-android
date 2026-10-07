package app.drokpo.android.features.shared.sharing

import app.drokpo.android.core.ApiError
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.Match
import app.drokpo.android.core.model.SwipeResult
import app.drokpo.android.features.shared.profiledetail.ProfileDetailModel
import app.drokpo.android.features.shared.profiledetail.ProfileDetailState
import app.drokpo.android.features.shared.profiledetail.answeredQuestions
import app.drokpo.android.features.shared.profiledetail.distanceLabel
import app.drokpo.android.features.shared.profiledetail.isSelfProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** ProfileDetailModel + the pure helpers behind ProfileDetailContent (group 11 owns both packages). */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileDetailModelTest {
    @get:Rule val main = SharingMainDispatcherRule()

    private val pema = FeedCard(uid = "u-pema", displayName = "Pema")

    private class Recorder {
        val reports = mutableListOf<Pair<String, String>>()
        val blocks = mutableListOf<Pair<String, String?>>()
        val threads = mutableListOf<String>()
    }

    private fun model(
        recorder: Recorder = Recorder(),
        reportError: Exception? = null,
        blockError: Exception? = null,
    ) = ProfileDetailModel(
        reportProfile = { uid, reason -> reportError?.let { throw it }; recorder.reports += uid to reason },
        blockProfile = { uid, name -> blockError?.let { throw it }; recorder.blocks += uid to name },
        openMessageThread = { recorder.threads += it },
        workScope = CoroutineScope(SupervisorJob() + main.dispatcher),
    )

    // ------------------------------------------------------------------ LikedYou

    @Test fun likeBackMatchStoresMatchIdAndAlerts() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<SwipeResult?>()
        val model = model()
        model.likeBack { gate.await() }
        runCurrent()
        assertTrue(model.state.value.isLiking)

        gate.complete(SwipeResult(matched = true, matchId = "m1"))
        advanceUntilIdle()
        assertEquals(ProfileDetailState(localMatchId = "m1", showMatchAlert = true), model.state.value)
    }

    @Test fun likeBackFallsBackToNestedMatchId() = runTest(main.dispatcher) {
        val model = model()
        model.likeBack { SwipeResult(match = Match(matchId = "m-nested")) }
        advanceUntilIdle()
        assertEquals("m-nested", model.state.value.localMatchId)
        assertTrue(model.state.value.showMatchAlert)
    }

    @Test fun likeBackWithoutMatchOrFailureChangesNothing() = runTest(main.dispatcher) {
        val model = model()
        model.likeBack { SwipeResult(matched = false) }
        advanceUntilIdle()
        assertEquals(ProfileDetailState(), model.state.value)

        // Likes reports its own errors and returns nil.
        model.likeBack { null }
        advanceUntilIdle()
        assertEquals(ProfileDetailState(), model.state.value)
    }

    @Test fun likeBackIgnoresTapsWhileInFlight() = runTest(main.dispatcher) {
        var calls = 0
        val gate = CompletableDeferred<SwipeResult?>()
        val model = model()
        model.likeBack { calls++; gate.await() }
        model.likeBack { calls++; gate.await() }
        runCurrent()
        gate.complete(null)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertFalse(model.state.value.isLiking)
    }

    @Test fun likeBackThrowingSurfacesError() = runTest(main.dispatcher) {
        val model = model()
        model.likeBack { throw ApiError.Http(500, "Server error") }
        advanceUntilIdle()
        assertEquals("Server error", model.state.value.errorMessage)
        assertFalse(model.state.value.isLiking)
        assertNull(model.state.value.localMatchId)
    }

    @Test fun openThreadUsesExplicitOrLocalMatchId() = runTest(main.dispatcher) {
        val recorder = Recorder()
        val model = model(recorder)
        model.openThread() // nothing matched yet → guard
        assertTrue(recorder.threads.isEmpty())

        model.likeBack { SwipeResult(matchId = "m1") }
        advanceUntilIdle()
        model.openThread() // "Say hi" / "Send message"
        model.openThread("m-explicit")
        assertEquals(listOf("m1", "m-explicit"), recorder.threads)
    }

    @Test fun dismissMatchAlertKeepsSendMessage() = runTest(main.dispatcher) {
        val model = model()
        model.likeBack { SwipeResult(matchId = "m1") }
        advanceUntilIdle()
        model.dismissMatchAlert()
        assertFalse(model.state.value.showMatchAlert)
        assertEquals("m1", model.state.value.localMatchId)
    }

    // ------------------------------------------------------------------ safety

    @Test fun reportStaysOnProfile() = runTest(main.dispatcher) {
        val recorder = Recorder()
        val model = model(recorder)
        model.report(pema, "Spam")
        advanceUntilIdle()
        assertEquals(listOf("u-pema" to "Spam"), recorder.reports)
        assertEquals(ProfileDetailState(), model.state.value)
    }

    @Test fun reportFailureAlerts() = runTest(main.dispatcher) {
        val model = model(reportError = ApiError.Http(429, "Too many reports"))
        model.report(pema, "Spam")
        advanceUntilIdle()
        assertEquals("Too many reports", model.state.value.errorMessage)
        model.dismissError()
        assertNull(model.state.value.errorMessage)
    }

    @Test fun blockRecordsNameAndDismisses() = runTest(main.dispatcher) {
        val recorder = Recorder()
        val model = model(recorder)
        model.block(pema)
        advanceUntilIdle()
        assertEquals(listOf("u-pema" to "Pema"), recorder.blocks)
        assertTrue(model.state.value.dismissAfterBlock)
        model.consumeDismiss()
        assertFalse(model.state.value.dismissAfterBlock)
    }

    @Test fun blockFailureAlertsAndStays() = runTest(main.dispatcher) {
        val model = model(blockError = ApiError.NotAuthenticated)
        model.block(pema)
        advanceUntilIdle()
        assertFalse(model.state.value.dismissAfterBlock)
        assertEquals("You need to sign in again.", model.state.value.errorMessage)
    }

    // ------------------------------------------------------------------ helpers

    @Test fun answeredQuestionsFollowVocabularyOrderAndSkipUnknownOrBlank() {
        val card = FeedCard(
            uid = "u",
            answers = mapOf(
                "perfectWeekend" to "Hiking",
                "retired" to "never shown",
                "teaChoice" to "Butter tea",
                "travelledTo" to " \t ",
                "lookingFor" to "New friends",
                "favoriteMusic" to "\n", // Swift trims .whitespaces, not newlines
            ),
        )
        val answered = answeredQuestions(card).map { it.question.key to it.answer }
        assertEquals(
            listOf("lookingFor" to "New friends", "teaChoice" to "Butter tea", "favoriteMusic" to "\n", "perfectWeekend" to "Hiking"),
            answered,
        )
        assertEquals("I'm here for", answeredQuestions(card).first().question.label)
        assertTrue(answeredQuestions(FeedCard(uid = "u")).isEmpty())
    }

    @Test fun answersKeepTheirOwnWhitespace() {
        val card = FeedCard(uid = "u", answers = mapOf("teaChoice" to "  Chai  "))
        assertEquals("  Chai  ", answeredQuestions(card).single().answer)
    }

    @Test fun distanceRoundsToWholeKilometres() {
        assertEquals("~12 km away", distanceLabel(12.4))
        assertEquals("~13 km away", distanceLabel(12.5))
        assertEquals("~0 km away", distanceLabel(0.3))
        assertEquals("~250 km away", distanceLabel(249.6))
    }

    @Test fun isSelfMatchesSessionUidOrPreviewSentinel() {
        assertTrue(isSelfProfile(FeedCard(uid = "fixture-me"), "fixture-me"))
        assertTrue(isSelfProfile(FeedCard(uid = "me"), null)) // Profile.asFeedCard without a uid
        assertFalse(isSelfProfile(FeedCard(uid = "u-pema"), "fixture-me"))
        assertFalse(isSelfProfile(FeedCard(uid = "u-pema"), null))
    }
}
