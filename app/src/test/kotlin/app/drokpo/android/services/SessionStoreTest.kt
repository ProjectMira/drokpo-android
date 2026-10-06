package app.drokpo.android.services

import app.drokpo.android.core.AccountType
import app.drokpo.android.core.ApiError
import app.drokpo.android.core.SessionState
import app.drokpo.android.core.SessionStore
import app.drokpo.android.core.model.AccountResponse
import app.drokpo.android.core.model.CommunityProfile
import app.drokpo.android.core.model.Profile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStoreTest {
    private val person = Profile(uid = "u1", displayName = "Tenzin", onboardingComplete = true)
    private val community = CommunityProfile(uid = "c1", name = "TCV", verification = "verified")

    private class Harness(scope: CoroutineScope) {
        var uid: String? = "u1"
        var account: suspend () -> AccountResponse = { AccountResponse(accountType = "none") }
        val events = mutableListOf<String>()
        val store = SessionStore(
            scope = scope,
            firebaseEnabled = true,
            fetchAccount = { account() },
            currentUid = { uid },
            onActive = { events += "push.enable" },
            onSignedOutReset = { events += "blocks.reset" },
            beforeSignOut = { events += "beforeSignOut" },
            firebaseSignOut = { events += "firebase.signOut" },
        )
    }

    // region Routing rule

    @Test
    fun personRoutesOnOnboardingFlag() {
        val active = SessionStore.route(AccountResponse("person", profile = person))
        assertEquals(SessionState.ActivePerson, active.state)
        assertEquals(person, active.profile)
        assertNull(active.community)

        val incomplete = SessionStore.route(AccountResponse("person", profile = person.copy(onboardingComplete = false)))
        assertEquals(SessionState.NeedsOnboarding, incomplete.state)

        // A missing flag (or missing profile) counts as complete — iOS `?? true`.
        assertEquals(SessionState.ActivePerson, SessionStore.route(AccountResponse("person", profile = person.copy(onboardingComplete = null))).state)
        assertEquals(SessionState.ActivePerson, SessionStore.route(AccountResponse("person", profile = null)).state)
    }

    @Test
    fun communityIsActiveWithoutOnboardingCheck() {
        val route = SessionStore.route(AccountResponse("community", profile = person, community = community))
        assertEquals(SessionState.ActiveCommunity, route.state)
        assertNull(route.profile)
        assertEquals(community, route.community)
    }

    @Test
    fun anythingElseAsksForAccountType() {
        for (type in listOf("none", null, "admin", "")) {
            val route = SessionStore.route(AccountResponse(type, profile = person, community = community))
            assertEquals(SessionState.ChoosingAccountType, route.state)
            assertNull(route.profile)
            assertNull(route.community)
        }
    }

    // endregion

    @Test
    fun startsLoadingOrSignedOutWithoutFirebase() = runTest {
        assertEquals(SessionState.Loading, Harness(this).store.state.value)
        val noFirebase = SessionStore(
            scope = this,
            firebaseEnabled = false,
            fetchAccount = { error("must not be called") },
            currentUid = { error("must not touch FirebaseAuth") },
            onActive = {},
            onSignedOutReset = {},
            beforeSignOut = {},
            firebaseSignOut = { error("must not touch FirebaseAuth") },
        )
        assertEquals(SessionState.SignedOut, noFirebase.state.value)
        assertNull(noFirebase.uid)
        noFirebase.refreshAccount()
        noFirebase.signOut()
        advanceUntilIdle()
        assertEquals(SessionState.SignedOut, noFirebase.state.value)
    }

    @Test
    fun signInFetchesAccountAndEnablesPushWhenActive() = runTest {
        val h = Harness(this)
        h.account = { AccountResponse("person", profile = person) }
        h.store.onAuthStateChanged("u1")
        advanceUntilIdle()
        assertEquals(SessionState.ActivePerson, h.store.state.value)
        assertEquals(person, h.store.myProfile.value)
        assertEquals(listOf("push.enable"), h.events)
    }

    @Test
    fun onboardingAndChoosingDoNotEnablePush() = runTest {
        val h = Harness(this)
        h.account = { AccountResponse("person", profile = person.copy(onboardingComplete = false)) }
        h.store.refreshAccount()
        assertEquals(SessionState.NeedsOnboarding, h.store.state.value)
        h.account = { AccountResponse("none") }
        h.store.refreshProfile()
        assertEquals(SessionState.ChoosingAccountType, h.store.state.value)
        assertEquals(emptyList<String>(), h.events)

        h.account = { AccountResponse("community", community = community) }
        h.store.refreshProfile()
        assertEquals(SessionState.ActiveCommunity, h.store.state.value)
        assertEquals(community, h.store.myCommunity.value)
        assertNull(h.store.myProfile.value)
        assertEquals(listOf("push.enable"), h.events)
    }

    @Test
    fun failureSetsFailedAndKeepsLastErrorAfterSuccess() = runTest {
        val h = Harness(this)
        h.account = { throw ApiError.Http(500, "Backend down") }
        h.store.refreshAccount()
        assertEquals(SessionState.Failed, h.store.state.value)
        assertEquals("Backend down", h.store.lastError.value)

        h.account = { AccountResponse("person", profile = person) }
        h.store.refreshProfile()
        assertEquals(SessionState.ActivePerson, h.store.state.value)
        // iOS never clears lastError on success.
        assertEquals("Backend down", h.store.lastError.value)
    }

    @Test
    fun signOutResetsProfileAndBlocks() = runTest {
        val h = Harness(this)
        h.account = { AccountResponse("person", profile = person) }
        h.store.refreshAccount()
        h.events.clear()

        h.uid = null
        h.store.onAuthStateChanged(null)
        assertEquals(SessionState.SignedOut, h.store.state.value)
        assertNull(h.store.myProfile.value)
        assertNull(h.store.myCommunity.value)
        assertEquals(listOf("blocks.reset"), h.events)
    }

    @Test
    fun signOutDetachesDeviceBeforeFirebaseSignOut() = runTest {
        val h = Harness(this)
        h.store.signOut()
        advanceUntilIdle()
        assertEquals(listOf("beforeSignOut", "firebase.signOut"), h.events)
    }

    @Test
    fun signOutStillSignsOutWhenCleanupFails() = runTest {
        val events = mutableListOf<String>()
        val store = SessionStore(
            scope = this,
            firebaseEnabled = true,
            fetchAccount = { AccountResponse("none") },
            currentUid = { "u1" },
            onActive = {},
            onSignedOutReset = {},
            beforeSignOut = { throw IllegalStateException("offline") },
            firebaseSignOut = { events += "firebase.signOut" },
        )
        store.signOut()
        advanceUntilIdle()
        assertEquals(listOf("firebase.signOut"), events)
    }

    @Test
    fun answerForAStaleSessionIsDropped() {
        val scope = TestScope(StandardTestDispatcher())
        val h = Harness(scope)
        val gate = CompletableDeferred<AccountResponse>()
        h.account = { gate.await() }
        h.store.onAuthStateChanged("u1")
        scope.advanceUntilIdle()

        // Signed out while GET /api/account was in flight.
        h.uid = null
        h.store.onAuthStateChanged(null)
        gate.complete(AccountResponse("person", profile = person))
        scope.advanceUntilIdle()

        assertEquals(SessionState.SignedOut, h.store.state.value)
        assertNull(h.store.myProfile.value)
        assertEquals(listOf("blocks.reset"), h.events)
    }

    @Test
    fun chooseAccountTypeIsLocalRouting() = runTest {
        val h = Harness(this)
        h.store.chooseAccountType(AccountType.Person)
        assertEquals(SessionState.NeedsOnboarding, h.store.state.value)
        h.store.chooseAccountType(AccountType.Community)
        assertEquals(SessionState.NeedsCommunityOnboarding, h.store.state.value)
    }
}
