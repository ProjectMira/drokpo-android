package app.drokpo.android.core

import app.drokpo.android.core.model.ContentEventIn
import kotlinx.coroutines.launch

/**
 * Fire-and-forget content analytics (POST /api/{path}/events) for the
 * ad/news/post cards — iOS `reportContentEvent` / `reportClick`.
 */
object ContentEvents {
    /** [path] = "ads/{adId}" | "news/{newsId}" | "posts/{postId}". */
    fun send(path: String, event: String) {
        AppGraph.appScope.launch {
            // Fire-and-forget analytics; failures must never surface to the user.
            tryOrNull { ApiClient.post<EmptyResponse>("/api/$path/events", ContentEventIn(event = event)) }
        }
    }

    fun click(path: String) = send(path, "click")

    fun impression(path: String) = send(path, "impression")
}
