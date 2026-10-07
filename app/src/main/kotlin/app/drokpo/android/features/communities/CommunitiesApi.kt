package app.drokpo.android.features.communities

import app.drokpo.android.core.ApiClient
import app.drokpo.android.core.EmptyResponse
import app.drokpo.android.core.model.CommunitiesHomeResponse
import app.drokpo.android.core.model.CommunityListResponse
import app.drokpo.android.core.model.CommunityMembersResponse
import app.drokpo.android.core.model.RsvpResult
import app.drokpo.android.core.model.VoteIn
import app.drokpo.android.core.model.VoteResult

/**
 * The REST calls the person-side community screens make (CommunitiesView,
 * CommunityDirectoryView, CommunityMembersView). An interface so the models'
 * logic runs in plain JVM tests against a fake; [RemoteCommunitiesApi] is the
 * production implementation and every model's default.
 */
internal interface CommunitiesApi {
    /** GET /api/communities/home — the joined rail plus a typed feed of their posts and ads. */
    suspend fun home(): CommunitiesHomeResponse

    /** GET /api/communities?limit= — the directory (any registration state). */
    suspend fun directory(limit: Int): CommunityListResponse

    /** POST (join) or DELETE (leave) /api/communities/{cid}/join. */
    suspend fun setJoined(cid: String, joined: Boolean)

    /** GET /api/communities/{cid}/members?limit= — members-only on the backend. */
    suspend fun members(cid: String, limit: Int): CommunityMembersResponse

    /** POST /api/posts/{postId}/vote {optionId}. */
    suspend fun vote(postId: String, optionId: String): VoteResult

    /** POST (going) or DELETE (not going) /api/posts/{postId}/rsvp. */
    suspend fun rsvp(postId: String, going: Boolean): RsvpResult
}

internal object RemoteCommunitiesApi : CommunitiesApi {
    override suspend fun home(): CommunitiesHomeResponse =
        ApiClient.get<CommunitiesHomeResponse>("/api/communities/home")

    override suspend fun directory(limit: Int): CommunityListResponse =
        ApiClient.get<CommunityListResponse>("/api/communities", listOf("limit" to limit.toString()))

    override suspend fun setJoined(cid: String, joined: Boolean) {
        if (joined) {
            ApiClient.post<EmptyResponse>("/api/communities/$cid/join")
        } else {
            ApiClient.delete<EmptyResponse>("/api/communities/$cid/join")
        }
    }

    override suspend fun members(cid: String, limit: Int): CommunityMembersResponse =
        ApiClient.get<CommunityMembersResponse>("/api/communities/$cid/members", listOf("limit" to limit.toString()))

    override suspend fun vote(postId: String, optionId: String): VoteResult =
        ApiClient.post<VoteResult>("/api/posts/$postId/vote", VoteIn(optionId = optionId))

    override suspend fun rsvp(postId: String, going: Boolean): RsvpResult =
        if (going) {
            ApiClient.post<RsvpResult>("/api/posts/$postId/rsvp")
        } else {
            ApiClient.delete<RsvpResult>("/api/posts/$postId/rsvp")
        }
}
