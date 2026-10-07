package app.drokpo.android.features.communities

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.drokpo.android.core.ApiError
import app.drokpo.android.core.model.CommunityPostIn
import app.drokpo.android.features.communityhome.CommunityPostComposerModel
import app.drokpo.android.features.communityhome.CommunityPostDraft
import app.drokpo.android.features.communityhome.PollOptionDraft
import app.drokpo.android.features.communityhome.PostKind
import app.drokpo.android.features.communityhome.iso8601
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityPostComposerTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val now: Instant = Instant.parse("2026-10-06T10:30:00Z")
    private val later: Instant = Instant.parse("2026-10-06T12:00:00Z")

    private fun draft(kind: PostKind, title: String = "Title") =
        CommunityPostDraft(kind = kind, title = title, eventDate = later)

    private fun options(vararg texts: String) = texts.mapIndexed { i, t -> PollOptionDraft(id = "o$i", text = t) }

    // region canSave

    @Test
    fun everyKindNeedsATitle() {
        PostKind.entries.forEach { kind ->
            assertFalse(kind.name, draft(kind, title = "  ").canSave(now, isSaving = false))
        }
        assertTrue(draft(PostKind.Announcement).canSave(now, isSaving = false))
        assertFalse(draft(PostKind.Announcement).canSave(now, isSaving = true))
    }

    @Test
    fun linkPostsNeedAnHttpsLink() {
        val link = draft(PostKind.Link)
        assertFalse(link.canSave(now, false))
        assertFalse(link.copy(linkUrl = "http://example.org").canSave(now, false))
        assertTrue(link.copy(linkUrl = "  https://example.org ").canSave(now, false))
    }

    @Test
    fun pollsNeedTwoUniqueFilledOptions() {
        val poll = draft(PostKind.Poll)
        assertFalse(poll.canSave(now, false))
        assertFalse(poll.copy(pollOptions = options("Sat", " ")).canSave(now, false))
        assertFalse(poll.copy(pollOptions = options("Sat", " Sat ")).canSave(now, false))
        assertTrue(poll.copy(pollOptions = options("Sat", "Sun")).canSave(now, false))
        assertTrue(poll.copy(pollOptions = options("Sat", "", "Sun")).canSave(now, false))
    }

    @Test
    fun eventsNeedAFutureDateAndAnOptionalHttpsLink() {
        val event = draft(PostKind.Event)
        assertTrue(event.canSave(now, false))
        assertFalse(event.copy(eventDate = now).canSave(now, false))
        assertFalse(event.copy(eventDate = now.minusSeconds(60)).canSave(now, false))
        assertFalse(event.copy(linkUrl = "www.example.org").canSave(now, false))
        assertTrue(event.copy(linkUrl = "https://example.org").canSave(now, false))
    }

    // endregion

    // region Poll options

    @Test
    fun pollOptionsStayBetweenTwoAndFourWithStableIds() {
        var poll = draft(PostKind.Poll).copy(pollOptions = options("A", "B"))
        assertFalse(poll.canRemovePollOption)
        assertEquals(poll, poll.removingPollOption("o0"))

        poll = poll.addingPollOption().addingPollOption()
        assertEquals(4, poll.pollOptions.size)
        assertFalse(poll.canAddPollOption)
        assertEquals(poll, poll.addingPollOption())
        assertEquals(4, poll.pollOptions.map { it.id }.toSet().size)

        val thirdId = poll.pollOptions[2].id
        poll = poll.updatingPollOption(thirdId, "C").removingPollOption("o0")
        assertEquals(listOf("B", "C", ""), poll.pollOptions.map { it.text })
        assertEquals("o1", poll.pollOptions[0].id)
        assertEquals(thirdId, poll.pollOptions[1].id)
    }

    // endregion

    // region Payload

    @Test
    fun announcementPayload() {
        val payload = draft(PostKind.Announcement, title = "  Saga Dawa  ")
            .copy(body = " Prayers at 9 ", linkUrl = "https://leftover.example", ctaLabel = "Go")
            .toPayload(photoStoragePath = null)
        assertEquals(
            CommunityPostIn(kind = "announcement", title = "Saga Dawa", body = " Prayers at 9 "),
            payload,
        )
    }

    @Test
    fun linkPayloadTrimsTheLinkAndDropsAnEmptyLabel() {
        val payload = draft(PostKind.Link)
            .copy(linkUrl = " https://example.org/volunteer ", ctaLabel = "  ")
            .toPayload("communities/c1/photos/x.jpg")
        assertEquals("link", payload.kind)
        assertEquals("https://example.org/volunteer", payload.linkUrl)
        assertNull(payload.ctaLabel)
        assertEquals("communities/c1/photos/x.jpg", payload.photoStoragePath)
        assertNull(payload.pollOptions)
        assertNull(payload.eventAt)
        assertNull(payload.location)
    }

    @Test
    fun pollPayloadSendsAnEmptyBodyAndTheFilledOptions() {
        val payload = draft(PostKind.Poll, title = "Picnic?")
            .copy(body = "typed under another kind", pollOptions = options(" Sat ", "", "Sun"))
            .toPayload(null)
        assertEquals("", payload.body)
        assertEquals(listOf("Sat", "Sun"), payload.pollOptions)
        assertNull(payload.linkUrl)
        assertNull(payload.ctaLabel)
    }

    @Test
    fun eventPayloadCarriesUtcTimeLocationAndOptionalLink() {
        val withoutLink = draft(PostKind.Event, title = "Losar")
            .copy(eventDate = Instant.parse("2026-12-15T12:30:45.678Z"), eventLocation = "  ", ctaLabel = "Tickets")
            .toPayload(null)
        assertEquals("2026-12-15T12:30:45Z", withoutLink.eventAt)
        assertNull(withoutLink.linkUrl)
        assertNull(withoutLink.location)
        assertEquals("Tickets", withoutLink.ctaLabel)

        val withLink = draft(PostKind.Event, title = "Losar")
            .copy(linkUrl = " https://e.org ", eventLocation = " Hall ")
            .toPayload(null)
        assertEquals("https://e.org", withLink.linkUrl)
        assertEquals("Hall", withLink.location)
        assertEquals("2026-10-06T12:00:00Z", withLink.eventAt)
    }

    @Test
    fun iso8601IsWholeSecondsUtc() {
        assertEquals("2026-10-06T12:00:00Z", iso8601(Instant.parse("2026-10-06T12:00:00.999Z")))
    }

    // endregion

    // region Model

    private fun model(
        api: FakeCommunityAccountApi = FakeCommunityAccountApi(),
        canLoad: Boolean = true,
    ) = CommunityPostComposerModel(api = api, now = { now }, canLoadImage = { canLoad })

    @Test
    fun defaultsToAnAnnouncementWithTwoOptionsAndAnEventInAnHour() {
        val model = model()
        assertEquals(PostKind.Announcement, model.draft.kind)
        assertEquals(2, model.draft.pollOptions.size)
        assertEquals(now.plusSeconds(3_600), model.draft.eventDate)
        assertFalse(model.canSave)
    }

    @Test
    fun eventDatesClampToNow() {
        val model = model()
        model.setEventDate(now.minusSeconds(3_600))
        assertEquals(now, model.draft.eventDate)
        model.setEventDate(later)
        assertEquals(later, model.draft.eventDate)
    }

    @Test
    fun saveCreatesThePostThenAwaitsOnSavedThenDismisses() = runTest {
        val api = FakeCommunityAccountApi()
        val model = model(api)
        val events = mutableListOf<String>()
        api.onCreatePost = { events += "createPost" }
        model.updateDraft(model.draft.copy(title = " Hello "))

        model.save(onSaved = { events += "onSaved" }, onDone = { events += "dismiss" })
        assertTrue(model.isSaving)
        assertFalse(model.canSave)
        advanceUntilIdle()

        assertEquals(listOf("createPost", "onSaved", "dismiss"), events)
        assertEquals("Hello", api.posts.single().title)
        assertNull(api.posts.single().photoStoragePath)
        assertFalse(model.isSaving)
    }

    @Test
    fun aPickedPhotoIsUploadedFirst() = runTest {
        val api = FakeCommunityAccountApi()
        val model = model(api)
        val order = mutableListOf<String>()
        api.onCreatePost = { order += "createPost" }
        model.updateDraft(model.draft.copy(title = "Hall"))

        model.save(upload = { order += "upload"; "communities/c1/photos/up.jpg" }, onSaved = {}, onDone = {})
        advanceUntilIdle()

        assertEquals(listOf("upload", "createPost"), order)
        assertEquals("communities/c1/photos/up.jpg", api.posts.single().photoStoragePath)
    }

    @Test
    fun saveDoesNothingWhileInvalid() = runTest {
        val api = FakeCommunityAccountApi()
        val model = model(api)
        var dismissed = false
        model.save(onSaved = {}, onDone = { dismissed = true })
        advanceUntilIdle()
        assertTrue(api.calls.isEmpty())
        assertFalse(dismissed)
    }

    @Test
    fun failedPostAlertsAndStaysOpen() = runTest {
        val api = FakeCommunityAccountApi().apply { failWith = ApiError.Http(400, "eventAt must be in the future") }
        val model = model(api)
        model.updateDraft(model.draft.copy(title = "Hello"))
        var saved = false
        var dismissed = false

        model.save(onSaved = { saved = true }, onDone = { dismissed = true })
        advanceUntilIdle()

        assertEquals("eventAt must be in the future", model.errorMessage)
        assertFalse(saved)
        assertFalse(dismissed)
        assertFalse(model.isSaving)
        model.dismissError()
        assertNull(model.errorMessage)
    }

    @Test
    fun closingMidSaveStillPostsAndReloadsButDoesNotDismissAgain() = runTest {
        val gate = Gate<Unit>()
        val api = FakeCommunityAccountApi().apply { onCreatePost = { gate.await() } }
        val store = ViewModelStore()
        val model = ViewModelProvider.create(
            store,
            viewModelFactory { initializer { CommunityPostComposerModel(api = api, now = { now }, canLoadImage = { true }) } },
        )[CommunityPostComposerModel::class]
        model.updateDraft(model.draft.copy(title = "Hello"))
        var saved = false
        var dismissed = false

        model.save(onSaved = { saved = true }, onDone = { dismissed = true })
        advanceUntilIdle()
        store.clear() // the cover was closed
        gate.open(Unit)
        advanceUntilIdle()

        assertEquals("Hello", api.posts.single().title)
        assertTrue(saved)
        assertFalse(dismissed)
    }

    // endregion
}
