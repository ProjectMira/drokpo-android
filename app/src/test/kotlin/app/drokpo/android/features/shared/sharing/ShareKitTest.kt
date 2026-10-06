package app.drokpo.android.features.shared.sharing

import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.NewsCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareKitTest {
    @Test fun parsesCustomScheme() {
        assertEquals(ShareDestination.User("u1"), ShareDestination.parse("drokpo://s/user/u1"))
        assertEquals(ShareDestination.Community("c1"), ShareDestination.parse("drokpo://s/community/c1"))
        assertEquals(ShareDestination.Post("p1"), ShareDestination.parse("drokpo://s/post/p1/extra"))
    }

    @Test fun parsesHostedLinks() {
        assertEquals(ShareDestination.News("n1"), ShareDestination.parse("https://drokpo-backend.web.app/s/news/n1?utm=x"))
        assertEquals(ShareDestination.User("a b"), ShareDestination.parse("https://drokpo-backend.web.app/s/user/a%20b"))
    }

    @Test fun rejectsEverythingElse() {
        assertNull(ShareDestination.parse("https://example.org/s/user/u1"))
        assertNull(ShareDestination.parse("drokpo://s/unknown/x"))
        assertNull(ShareDestination.parse("https://drokpo-backend.web.app/s/user/"))
        assertNull(ShareDestination.parse("https://drokpo-backend.web.app/x/user/u1"))
        assertNull(ShareDestination.parse("not a url"))
        assertNull(ShareDestination.parse(""))
        assertNull(ShareDestination.make("user", ""))
    }

    @Test fun shareableContentLinks() {
        val person = ShareableContent.Profile(FeedCard(uid = "u1", displayName = "Pema"))
        assertEquals("user", person.pathType)
        assertEquals("https://drokpo-backend.web.app/s/user/u1", person.webUrl)
        assertEquals("Pema\nhttps://drokpo-backend.web.app/s/user/u1", person.messageText)
        assertEquals("user-u1", person.id)
        val community = ShareableContent.Profile(FeedCard(uid = "c1", kind = "community"))
        assertEquals("community", community.pathType)
        assertEquals("A Drokpo member", community.title)
        assertEquals("A community on Drokpo", ShareableContent.Community("c1", null).title)
        assertEquals("TAT", ShareableContent.Post(CommunityPostCard(postId = "p", communityName = "TAT")).title)
        assertEquals("A community post", ShareableContent.Post(CommunityPostCard(postId = "p")).title)
        assertEquals("A news story", ShareableContent.News(NewsCard(newsId = "n")).title)
    }

    @Test fun sharedLinkMessageRoundTrip() {
        val post = ShareableContent.Post(CommunityPostCard(postId = "p9", title = "Losar party"))
        val message = SharedLinkMessage.from(post.messageText)!!
        assertEquals(ShareDestination.Post("p9"), message.destination)
        assertEquals("Losar party", message.caption)
        assertEquals("a community post", message.kindLabel)
        val bare = SharedLinkMessage.from("  drokpo://s/user/u1  \n\n")!!
        assertNull(bare.caption)
        assertNull(SharedLinkMessage.from("hello\nhttps://example.org"))
        assertEquals("one\ntwo", SharedLinkMessage.from("one\nhttps://drokpo-backend.web.app/s/news/n\ntwo")!!.caption)
    }
}
