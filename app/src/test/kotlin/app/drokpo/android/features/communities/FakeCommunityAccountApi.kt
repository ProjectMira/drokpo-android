package app.drokpo.android.features.communities

import android.net.Uri
import app.drokpo.android.core.model.CommunityPostIn
import app.drokpo.android.core.model.CommunityUpdate
import app.drokpo.android.features.communityhome.CommunityAccountApi

/** Scriptable [CommunityAccountApi] that records every call and body. */
internal class FakeCommunityAccountApi : CommunityAccountApi {
    val calls = mutableListOf<String>()
    val updates = mutableListOf<CommunityUpdate>()
    val posts = mutableListOf<CommunityPostIn>()

    var failWith: Exception? = null
    var onCreatePost: suspend () -> Unit = {}

    private fun maybeFail() {
        failWith?.let { throw it }
    }

    override suspend fun updateCommunity(update: CommunityUpdate) {
        calls += "patch"
        maybeFail()
        updates += update
    }

    override suspend fun uploadCommunityPhoto(image: Uri): String {
        calls += "upload"
        maybeFail()
        return "communities/c1/photos/new.jpg"
    }

    override suspend fun confirmPhoto(storagePath: String, order: Int) {
        calls += "confirmPhoto($storagePath,$order)"
        maybeFail()
    }

    override suspend fun deletePhoto(storagePath: String) {
        calls += "deletePhoto($storagePath)"
        maybeFail()
    }

    override suspend fun createPost(post: CommunityPostIn) {
        calls += "createPost"
        onCreatePost()
        maybeFail()
        posts += post
    }

    override suspend fun deleteCommunity() {
        calls += "deleteCommunity"
        maybeFail()
    }
}
