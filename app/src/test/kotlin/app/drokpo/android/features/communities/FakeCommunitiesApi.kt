package app.drokpo.android.features.communities

import app.drokpo.android.core.model.CommunitiesHomeResponse
import app.drokpo.android.core.model.CommunityListResponse
import app.drokpo.android.core.model.CommunityMembersResponse
import app.drokpo.android.core.model.RsvpResult
import app.drokpo.android.core.model.VoteResult
import kotlinx.coroutines.CompletableDeferred

/** Scriptable [CommunitiesApi]: each call is logged; responses (or failures) are swappable lambdas. */
internal class FakeCommunitiesApi : CommunitiesApi {
    val calls = mutableListOf<String>()

    var home: suspend () -> CommunitiesHomeResponse = { CommunitiesHomeResponse() }
    var directory: suspend (Int) -> CommunityListResponse = { CommunityListResponse() }
    var setJoined: suspend (String, Boolean) -> Unit = { _, _ -> }
    var members: suspend (String, Int) -> CommunityMembersResponse = { _, _ -> CommunityMembersResponse() }
    var vote: suspend (String, String) -> VoteResult = { _, _ -> VoteResult() }
    var rsvp: suspend (String, Boolean) -> RsvpResult = { _, _ -> RsvpResult() }

    override suspend fun home(): CommunitiesHomeResponse {
        calls += "home"
        return home.invoke()
    }

    override suspend fun directory(limit: Int): CommunityListResponse {
        calls += "directory($limit)"
        return directory.invoke(limit)
    }

    override suspend fun setJoined(cid: String, joined: Boolean) {
        calls += if (joined) "join($cid)" else "leave($cid)"
        setJoined.invoke(cid, joined)
    }

    override suspend fun members(cid: String, limit: Int): CommunityMembersResponse {
        calls += "members($cid,$limit)"
        return members.invoke(cid, limit)
    }

    override suspend fun vote(postId: String, optionId: String): VoteResult {
        calls += "vote($postId,$optionId)"
        return vote.invoke(postId, optionId)
    }

    override suspend fun rsvp(postId: String, going: Boolean): RsvpResult {
        calls += "rsvp($postId,$going)"
        return rsvp.invoke(postId, going)
    }
}

/** A suspension point a test completes by hand, to observe in-flight state. */
internal class Gate<T> {
    private val deferred = CompletableDeferred<T>()
    suspend fun await(): T = deferred.await()
    fun open(value: T) {
        deferred.complete(value)
    }
}
