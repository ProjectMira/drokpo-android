@file:OptIn(ExperimentalCoroutinesApi::class)

package app.drokpo.android.features.profile

import app.drokpo.android.core.ApiError
import app.drokpo.android.core.BlockedUser
import app.drokpo.android.core.model.SentMessage
import app.drokpo.android.features.settings.BlockedUsersModel
import app.drokpo.android.features.settings.SentMessagesModel
import app.drokpo.android.features.settings.SettingsModel
import app.drokpo.android.features.settings.blockedDateLabel
import app.drokpo.android.features.settings.namedRelativeString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

class SettingsModelsTest {
    @get:Rule
    val main = ProfileMainDispatcherRule()

    // region SettingsModel

    @Test
    fun deleteAccountDismissesThenSignsOut() = runTest(main.dispatcher) {
        val events = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val model = SettingsModel(
            deleteAccountRequest = {
                events += "DELETE /api/profile/me"
                gate.await()
            },
            requestSignOut = { events += "signOut" },
            workScope = this,
        )
        model.deleteAccount()
        model.deleteAccount() // double tap on "Delete everything"
        advanceUntilIdle()
        assertTrue(model.state.value.isDeleting)
        assertEquals(listOf("DELETE /api/profile/me"), events)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("DELETE /api/profile/me", "signOut"), events)
        assertTrue(model.state.value.dismissRequested)
        assertFalse(model.state.value.isDeleting)
    }

    @Test
    fun failedDeleteAlertsAndStaysSignedIn() = runTest(main.dispatcher) {
        var signedOut = false
        val model = SettingsModel(
            deleteAccountRequest = { throw ApiError.Http(500, "") },
            requestSignOut = { signedOut = true },
            workScope = this,
        )
        model.deleteAccount()
        advanceUntilIdle()
        assertEquals("Server error (500).", model.state.value.errorMessage)
        assertFalse(signedOut)
        assertFalse(model.state.value.dismissRequested)
        assertFalse(model.state.value.isDeleting)
        model.dismissError()
        assertNull(model.state.value.errorMessage)
    }

    @Test
    fun signOutDelegates() {
        var signedOut = 0
        SettingsModel(deleteAccountRequest = {}, requestSignOut = { signedOut++ }).signOut()
        assertEquals(1, signedOut)
    }

    // endregion

    // region BlockedUsersModel

    private val jigme = BlockedUser(uid = "u1", displayName = "Jigme", blockedAt = 1_700_000_000_000)
    private val anon = BlockedUser(uid = "u2", displayName = null, blockedAt = 1_600_000_000_000)

    @Test
    fun unblockRunsOneAtATime() = runTest(main.dispatcher) {
        val list = MutableStateFlow(listOf(jigme, anon))
        val gate = CompletableDeferred<Unit>()
        val unblocked = mutableListOf<String>()
        val model = BlockedUsersModel(
            blocked = list,
            unblockUser = { user ->
                unblocked += user.uid
                gate.await()
                list.value = list.value - user
            },
        )
        advanceUntilIdle()
        model.unblock(jigme)
        model.unblock(anon) // every button is disabled while one runs
        advanceUntilIdle()
        assertEquals("u1", model.state.value.workingUid)
        assertEquals(listOf("u1"), unblocked)

        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(model.state.value.workingUid)
        assertEquals(listOf(anon), model.state.value.blocked)
    }

    @Test
    fun failedUnblockKeepsTheEntryAndAlerts() = runTest(main.dispatcher) {
        val list = MutableStateFlow(listOf(jigme))
        val model = BlockedUsersModel(blocked = list, unblockUser = { throw ApiError.Http(404, "Not blocked") })
        advanceUntilIdle()
        model.unblock(jigme)
        advanceUntilIdle()
        assertEquals("Not blocked", model.state.value.errorMessage)
        assertEquals(listOf(jigme), model.state.value.blocked)
        assertNull(model.state.value.workingUid)
        model.dismissError()
        advanceUntilIdle()
        assertNull(model.state.value.errorMessage)
    }

    @Test
    fun blockedDateUsesTheLongDateStyle() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("November 14, 2023", blockedDateLabel(1_700_000_000_000, ZoneOffset.UTC))
        } finally {
            Locale.setDefault(previous)
        }
    }

    // endregion

    // region SentMessagesModel

    private val sent = listOf(SentMessage(messageId = "m1", text = "Tashi delek!", createdAt = "2026-10-07T10:00:00+00:00"))

    @Test
    fun loadsOnceOnStart() = runTest(main.dispatcher) {
        var fetches = 0
        val model = SentMessagesModel(fetch = {
            fetches++
            sent
        })
        assertTrue(model.state.value.isLoading)
        advanceUntilIdle()
        assertEquals(1, fetches)
        assertFalse(model.state.value.isLoading)
        assertEquals(sent, model.state.value.messages)
        assertNull(model.state.value.errorMessage)
    }

    @Test
    fun errorReplacesTheListUntilARefreshSucceeds() = runTest(main.dispatcher) {
        var fail = true
        val gate = CompletableDeferred<Unit>()
        var first = true
        val model = SentMessagesModel(fetch = {
            if (!first) gate.await()
            first = false
            if (fail) throw ApiError.InvalidResponse
            sent
        })
        advanceUntilIdle()
        assertEquals("Unexpected response from the server.", model.state.value.errorMessage)
        assertFalse(model.state.value.isLoading)

        fail = false
        model.refresh()
        model.refresh() // ignored while refreshing
        advanceUntilIdle()
        assertTrue(model.state.value.isRefreshing)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.state.value.isRefreshing)
        assertNull(model.state.value.errorMessage)
        assertEquals(sent, model.state.value.messages)
    }

    // endregion

    // region Relative dates

    // Expected strings are Foundation's own output (macOS, `Date.AnchoredRelativeFormatStyle(anchor:
    // sent, presentation: .named, unitsStyle: .wide)` formatted at `now`, UTC calendar), the same
    // algorithm as iOS `Text(date, format: .relative(presentation: .named))`.

    private val now: Instant = Instant.parse("2026-10-07T12:00:00Z") // a Wednesday

    private fun ago(seconds: Double, firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY) = namedRelativeString(
        now.minusMillis((seconds * 1000).toLong()),
        relativeTo = now,
        zone = ZoneOffset.UTC,
        firstDayOfWeek = firstDayOfWeek,
    )

    private fun hours(h: Double) = h * 3600

    private fun days(d: Double) = d * 86400

    @Test
    fun namedRelativeSecondsMinutesAndHoursRoundHalfUp() {
        assertEquals("now", ago(0.0))
        assertEquals("now", ago(0.4))
        assertEquals("1 second ago", ago(0.6))
        assertEquals("30 seconds ago", ago(30.0))
        assertEquals("59 seconds ago", ago(59.4))
        assertEquals("1 minute ago", ago(59.5))
        assertEquals("1 minute ago", ago(89.0))
        assertEquals("2 minutes ago", ago(90.0))
        assertEquals("8 minutes ago", ago(480.0))
        assertEquals("29 minutes ago", ago(1769.0))
        assertEquals("30 minutes ago", ago(1771.0))
        assertEquals("59 minutes ago", ago(3569.0))
        assertEquals("1 hour ago", ago(3570.0))
        assertEquals("1 hour ago", ago(5399.0))
        assertEquals("2 hours ago", ago(5400.0))
        assertEquals("13 hours ago", ago(hours(13.0))) // across midnight, still hours
        assertEquals("23 hours ago", ago(hours(23.0) + 29 * 60))
        assertEquals("yesterday", ago(hours(23.5))) // rounds up to a day
    }

    @Test
    fun namedRelativeDaysAndLargerCountCalendarBoundaries() {
        assertEquals("yesterday", ago(hours(27.0)))
        assertEquals("yesterday", ago(hours(36.0))) // midnight yesterday
        assertEquals("2 days ago", ago(hours(37.0))) // 11 pm the day before
        assertEquals("3 days ago", ago(days(3.0)))
        assertEquals("6 days ago", ago(days(6.0)))
        assertEquals("last week", ago(days(7.0)))
        assertEquals("2 weeks ago", ago(days(15.0)))
        assertEquals("4 weeks ago", ago(days(25.0)))
        assertEquals("last month", ago(days(29.0)))
        assertEquals("last month", ago(days(36.0))) // 1 September
        assertEquals("2 months ago", ago(days(37.0))) // 31 August
        assertEquals("5 months ago", ago(days(150.0)))
        assertEquals("10 months ago", ago(days(280.0)))
        assertEquals("last year", ago(days(400.0)))
        assertEquals("3 years ago", ago(days(1100.0)))
    }

    @Test
    fun namedRelativeWeeksFollowTheCalendarsFirstWeekday() {
        // Sunday 27 September is in last week for a Sunday-start calendar, two weeks back for Monday-start.
        assertEquals("last week", ago(days(10.0), DayOfWeek.SUNDAY))
        assertEquals("2 weeks ago", ago(days(10.0), DayOfWeek.MONDAY))
    }

    @Test
    fun namedRelativeFutureDates() {
        assertEquals("in 2 minutes", ago(-90.0))
        assertEquals("in 5 minutes", ago(-300.0))
        assertEquals("tomorrow", ago(days(-1.0)))
        assertEquals("next week", ago(days(-7.0)))
    }

    // endregion
}
