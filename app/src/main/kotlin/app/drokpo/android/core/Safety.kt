package app.drokpo.android.core

import app.drokpo.android.core.model.ReportIn

/**
 * Report / block — the calls iOS repeats in FeedModel, ProfileDetailView,
 * ChatThreadView, CommunityPageView and CommentsSheet, kept in one place.
 * Both throw; callers surface `userMessage()` the way iOS shows
 * `error.localizedDescription`.
 */
object Safety {
    /** POST /api/reports {reportedUid, reason, note}. */
    suspend fun report(reportedUid: String, reason: String, note: String = "") {
        ApiClient.post<EmptyResponse>("/api/reports", ReportIn(reportedUid = reportedUid, reason = reason, note = note))
    }

    /**
     * POST /api/blocks/{uid}, then remember it locally (the backend has no
     * list endpoint — Settings → Blocked users reads BlockStore). Nothing is
     * recorded when the request fails.
     */
    suspend fun block(uid: String, displayName: String?) {
        ApiClient.post<EmptyResponse>("/api/blocks/$uid")
        AppGraph.blocks.record(uid, displayName)
    }
}
