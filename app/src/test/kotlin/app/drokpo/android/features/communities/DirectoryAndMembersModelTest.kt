package app.drokpo.android.features.communities

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.drokpo.android.core.ApiError
import app.drokpo.android.core.model.CommunityListResponse
import app.drokpo.android.core.model.CommunityMember
import app.drokpo.android.core.model.CommunityMembersResponse
import app.drokpo.android.core.model.CommunityProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DirectoryAndMembersModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val joined = CommunityProfile(uid = "c1", name = "Joined", joined = true, memberCount = 10)
    private val notJoined = CommunityProfile(uid = "c2", name = "Open", joined = false, memberCount = 3)
    private val unknownCount = CommunityProfile(uid = "c3", name = "New", joined = true, memberCount = null)

    private fun directoryApi(vararg communities: CommunityProfile) = FakeCommunitiesApi().apply {
        directory = { CommunityListResponse(communities.toList()) }
    }

    // region Directory

    @Test
    fun loadsFiftyCommunities() = runTest {
        val api = directoryApi(joined, notJoined)
        val model = DirectoryModel(api)
        assertTrue(model.state.value.showsSpinner)
        advanceUntilIdle()

        assertEquals(listOf("directory(50)"), api.calls)
        assertEquals(listOf(joined, notJoined), model.state.value.communities)
        assertFalse(model.state.value.showsEmpty)
        assertFalse(model.state.value.showsSpinner)
    }

    @Test
    fun emptyDirectoryShowsTheEmptyState() = runTest {
        val model = DirectoryModel(directoryApi())
        advanceUntilIdle()
        assertTrue(model.state.value.showsEmpty)
    }

    @Test
    fun reappearingReloadsAfterTheFirstLoad() = runTest {
        val api = directoryApi(joined)
        val model = DirectoryModel(api)
        model.onAppear()
        advanceUntilIdle()
        model.onAppear()
        advanceUntilIdle()
        assertEquals(listOf("directory(50)", "directory(50)"), api.calls)
    }

    @Test
    fun joiningFlipsJoinedAndAddsAMember() = runTest {
        val api = directoryApi(joined, notJoined)
        val model = DirectoryModel(api)
        advanceUntilIdle()

        model.toggleJoin(notJoined)
        assertEquals("c2", model.state.value.workingCid)
        advanceUntilIdle()

        assertEquals("join(c2)", api.calls.last())
        val updated = model.state.value.communities[1]
        assertEquals(true, updated.joined)
        assertEquals(4, updated.memberCount)
        assertEquals(joined, model.state.value.communities[0])
        assertNull(model.state.value.workingCid)
    }

    @Test
    fun leavingRemovesAMemberButNeverGoesBelowZero() = runTest {
        val api = directoryApi(joined, unknownCount)
        val model = DirectoryModel(api)
        advanceUntilIdle()

        model.toggleJoin(joined)
        advanceUntilIdle()
        assertEquals(false, model.state.value.communities[0].joined)
        assertEquals(9, model.state.value.communities[0].memberCount)

        model.toggleJoin(unknownCount)
        advanceUntilIdle()
        assertEquals(listOf("directory(50)", "leave(c1)", "leave(c3)"), api.calls)
        assertEquals(false, model.state.value.communities[1].joined)
        assertEquals(0, model.state.value.communities[1].memberCount)
    }

    @Test
    fun onlyOneJoinRunsAtATime() = runTest {
        val gate = Gate<Unit>()
        val api = directoryApi(joined, notJoined).apply { setJoined = { _, _ -> gate.await() } }
        val model = DirectoryModel(api)
        advanceUntilIdle()

        model.toggleJoin(notJoined)
        advanceUntilIdle()
        model.toggleJoin(joined) // ignored: every button is disabled while one runs
        advanceUntilIdle()
        gate.open(Unit)
        advanceUntilIdle()

        assertEquals(listOf("directory(50)", "join(c2)"), api.calls)
        assertNull(model.state.value.workingCid)
    }

    @Test
    fun aJoinInFlightFinishesAfterTheDirectoryIsPopped() = runTest {
        val gate = Gate<Unit>()
        var finished = false
        val api = directoryApi(joined, notJoined).apply {
            setJoined = { _, _ ->
                gate.await()
                finished = true
            }
        }
        val store = ViewModelStore()
        val model = ViewModelProvider.create(
            store,
            viewModelFactory { initializer { DirectoryModel(api) } },
        )[DirectoryModel::class]
        advanceUntilIdle()

        model.toggleJoin(notJoined)
        advanceUntilIdle()
        store.clear() // the Directory was popped (or the cover closed)
        gate.open(Unit)
        advanceUntilIdle()

        assertEquals("join(c2)", api.calls.last())
        assertTrue(finished)
        assertEquals(true, model.state.value.communities[1].joined)
        assertNull(model.state.value.workingCid)
    }

    @Test
    fun failedJoinAlertsAndKeepsTheRow() = runTest {
        val api = directoryApi(notJoined).apply {
            setJoined = { _, _ -> throw ApiError.Http(403, "Only person accounts can join communities.") }
        }
        val model = DirectoryModel(api)
        advanceUntilIdle()

        model.toggleJoin(notJoined)
        advanceUntilIdle()

        assertEquals("Only person accounts can join communities.", model.state.value.errorMessage)
        assertEquals(notJoined, model.state.value.communities.single())
        assertNull(model.state.value.workingCid)
        model.dismissError()
        assertNull(model.state.value.errorMessage)
    }

    @Test
    fun refreshKeepsTheListUpWithoutTheCentreSpinner() = runTest {
        val gate = Gate<CommunityListResponse>()
        var first = true
        val api = FakeCommunitiesApi().apply {
            directory = {
                if (first) {
                    first = false
                    CommunityListResponse(emptyList())
                } else {
                    gate.await()
                }
            }
        }
        val model = DirectoryModel(api)
        advanceUntilIdle()
        model.refresh()
        advanceUntilIdle()

        assertTrue(model.state.value.isRefreshing)
        assertTrue(model.state.value.showsEmpty)
        assertFalse(model.state.value.showsSpinner)

        gate.open(CommunityListResponse(listOf(joined)))
        advanceUntilIdle()
        assertFalse(model.state.value.isRefreshing)
        assertEquals(listOf(joined), model.state.value.communities)
    }

    // endregion

    // region Members

    @Test
    fun membersLoadFiftyForTheCommunity() = runTest {
        val members = listOf(CommunityMember(uid = "u1", displayName = "Pema"), CommunityMember(uid = "u2"))
        val api = FakeCommunitiesApi().apply { this.members = { _, _ -> CommunityMembersResponse(members) } }
        val model = CommunityMembersModel("c-tat", api)
        assertTrue(model.state.value.showsSpinner)
        advanceUntilIdle()

        assertEquals(listOf("members(c-tat,50)"), api.calls)
        assertEquals(members, model.state.value.members)
        assertFalse(model.state.value.showsEmpty)
    }

    @Test
    fun noMembersShowsTheEmptyState() = runTest {
        val model = CommunityMembersModel("c-tat", FakeCommunitiesApi())
        advanceUntilIdle()
        assertTrue(model.state.value.showsEmpty)
        assertFalse(model.state.value.showsSpinner)
    }

    @Test
    fun membersOnlyFailureAlerts() = runTest {
        val api = FakeCommunitiesApi().apply {
            members = { _, _ -> throw ApiError.Http(403, "Only members can view this community's member list") }
        }
        val model = CommunityMembersModel("c-tat", api)
        advanceUntilIdle()
        assertEquals("Only members can view this community's member list", model.state.value.errorMessage)
        assertFalse(model.state.value.isLoading)

        model.refresh()
        advanceUntilIdle()
        assertEquals(listOf("members(c-tat,50)", "members(c-tat,50)"), api.calls)
    }

    // endregion
}
