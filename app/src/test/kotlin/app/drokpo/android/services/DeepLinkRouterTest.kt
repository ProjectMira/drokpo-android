package app.drokpo.android.services

import app.drokpo.android.core.DeepLinkRouter
import app.drokpo.android.features.shared.sharing.ShareDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkRouterTest {
    /** Stand-in for ShareDestination.parse so the precedence logic is tested on its own. */
    private val fakeParse: (String) -> ShareDestination? = { url ->
        if (url == "drokpo://s/user/abc") ShareDestination.User("abc") else null
    }

    @Test
    fun handleSetsBothAndClearResetsBoth() {
        val router = DeepLinkRouter()
        router.handle("message", "m1")
        assertEquals("message", router.pendingType.value)
        assertEquals("m1", router.pendingMatchId.value)

        router.pendingShare.value = ShareDestination.News("n1")
        router.focusLikedYou.value = true
        router.clear()
        assertNull(router.pendingType.value)
        assertNull(router.pendingMatchId.value)
        // clear() only covers the push payload.
        assertEquals(ShareDestination.News("n1"), router.pendingShare.value)
        assertTrue(router.focusLikedYou.value)
    }

    @Test
    fun shareLinkTakesPrecedenceOverPushExtras() {
        val router = DeepLinkRouter()
        val handled = router.handleLaunch("drokpo://s/user/abc", type = "message", matchId = "m1", parse = fakeParse)
        assertTrue(handled)
        assertEquals(ShareDestination.User("abc"), router.pendingShare.value)
        assertNull(router.pendingType.value)
        assertNull(router.pendingMatchId.value)
    }

    @Test
    fun unparseableDataFallsBackToPushExtras() {
        val router = DeepLinkRouter()
        assertTrue(router.handleLaunch("https://example.com/x", type = "message", matchId = "m1", parse = fakeParse))
        assertNull(router.pendingShare.value)
        assertEquals("message", router.pendingType.value)
        assertEquals("m1", router.pendingMatchId.value)
    }

    @Test
    fun likePushCarriesTypeOnly() {
        val router = DeepLinkRouter()
        assertTrue(router.handleLaunch(null, type = "like", matchId = null, parse = fakeParse))
        assertEquals("like", router.pendingType.value)
        assertNull(router.pendingMatchId.value)
    }

    @Test
    fun plainLaunchRoutesNothing() {
        val router = DeepLinkRouter()
        router.handle("match", "m9")
        // A launcher intent must not wipe a payload that's still waiting.
        assertFalse(router.handleLaunch(null, type = null, matchId = null, parse = fakeParse))
        assertFalse(router.handleLaunch("", type = null, matchId = null, parse = fakeParse))
        assertEquals("match", router.pendingType.value)
        assertEquals("m9", router.pendingMatchId.value)
        assertNull(router.pendingShare.value)
    }
}
