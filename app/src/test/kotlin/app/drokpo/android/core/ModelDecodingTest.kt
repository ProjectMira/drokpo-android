package app.drokpo.android.core

import app.drokpo.android.core.model.AccountResponse
import app.drokpo.android.core.model.AdCard
import app.drokpo.android.core.model.CommentCard
import app.drokpo.android.core.model.CommentVoteResult
import app.drokpo.android.core.model.CommentsResponse
import app.drokpo.android.core.model.CommunitiesHomeResponse
import app.drokpo.android.core.model.CommunityListResponse
import app.drokpo.android.core.model.CommunityMembersResponse
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.CommunityPostsResponse
import app.drokpo.android.core.model.CommunityProfile
import app.drokpo.android.core.model.FailableItem
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.FeedItem
import app.drokpo.android.core.model.FeedPage
import app.drokpo.android.core.model.FeedResponse
import app.drokpo.android.core.model.LikedContent
import app.drokpo.android.core.model.LikedContentResponse
import app.drokpo.android.core.model.Match
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.core.model.Photo
import app.drokpo.android.core.model.Poll
import app.drokpo.android.core.model.PollOption
import app.drokpo.android.core.model.Preferences
import app.drokpo.android.core.model.Profile
import app.drokpo.android.core.model.RepliesResponse
import app.drokpo.android.core.model.RsvpResult
import app.drokpo.android.core.model.SentMessage
import app.drokpo.android.core.model.SwipeEntry
import app.drokpo.android.core.model.SwipeResult
import app.drokpo.android.core.model.TolerantList
import app.drokpo.android.core.model.VoteResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDecodingTest {
    private inline fun <reified T> decode(json: String): T = DrokpoJson.decodeFromString(json)

    // MARK: - Account / profile

    @Test
    fun accountPersonDecodesRawFirestoreDoc() {
        val account = decode<AccountResponse>(Fixtures.ACCOUNT_PERSON)
        assertEquals("person", account.accountType)
        assertNull(account.community)
        val profile = account.profile!!
        assertEquals("u1", profile.uid)
        assertEquals("u1", profile.id)
        assertEquals("Tenzin", profile.displayName)
        assertEquals("1998-04-12", profile.dob)
        assertEquals(listOf("Tibetan", "English"), profile.languages)
        assertEquals(mapOf("teaChoice" to "Butter tea", "lookingFor" to "New friends"), profile.answers)
        assertEquals("tenzin", profile.socials?.instagram)
        assertEquals("tenzin-yt", profile.socials?.youtube)
        assertNull(profile.socials?.tiktok)
        assertEquals(Preferences(ageMin = 21, ageMax = 35, distanceKm = 100), profile.preferences)
        assertEquals(
            listOf(Photo("users/u1/photos/a.jpg", 0, "https://firebasestorage.googleapis.com/v0/b/drokpo/o/a.jpg?alt=media")),
            profile.photos,
        )
        assertEquals(true, profile.onboardingComplete)
        assertEquals(false, profile.discoverable)
    }

    @Test
    fun accountCommunityAndNone() {
        val community = decode<AccountResponse>(Fixtures.ACCOUNT_COMMUNITY)
        assertEquals("community", community.accountType)
        assertNull(community.profile)
        val c = community.community!!
        assertEquals("c1", c.id)
        assertEquals("Dolma", c.contactPerson?.name)
        assertEquals("Secretary", c.contactPerson?.role)
        assertEquals("Toronto", c.address?.city)
        assertEquals("M5V 1A1", c.address?.postalCode)
        assertEquals("tat", c.socials?.facebook)
        assertEquals(0, c.memberCount)
        assertNull(c.joined)
        assertTrue(c.isPending)
        assertFalse(c.isVerified)

        val none = decode<AccountResponse>(Fixtures.ACCOUNT_NONE)
        assertEquals("none", none.accountType)
        assertNull(none.profile)
        assertNull(none.community)
    }

    @Test
    fun profileToleratesMissingAndNullFields() {
        val profile = decode<Profile>("""{"uid":null,"preferences":{"ageMax":40}}""")
        assertEquals("me", profile.id)
        assertEquals(Preferences(ageMin = 18, ageMax = 40, distanceKm = 50), profile.preferences)
        assertNull(profile.photos)
    }

    @Test
    fun userCardPersonAndCommunity() {
        val person = decode<FeedCard>(Fixtures.PERSON_CARD)
        assertEquals("u2", person.uid)
        assertEquals(12.3, person.distanceKm!!, 0.0)
        assertFalse(person.isCommunity)
        assertNull(person.kind)

        val community = decode<FeedCard>(Fixtures.COMMUNITY_COUNTERPART)
        assertTrue(community.isCommunity)
        assertEquals("Tibetan Association of Toronto", community.displayName)
        assertEquals("Toronto, Canada", community.region)
        assertNull(community.socials)
        assertEquals(emptyList<Photo>(), community.photos)
    }

    @Test
    fun feedCardRequiresUid() {
        assertThrows(SerializationException::class.java) { decode<FeedCard>("""{"displayName":"x"}""") }
        assertThrows(SerializationException::class.java) { decode<FeedCard>("""{"uid":null}""") }
    }

    // MARK: - Feed

    @Test
    fun feedItemsSkipUnknownAndMalformedEntries() {
        val page = decode<FeedPage>(Fixtures.FEED_ITEMS)
        val items = page.items!!
        assertEquals(
            listOf("person-u2", "person-c1", "ad-ad1", "news-n1", "post-p1", "post-p2"),
            items.map { it.id },
        )
        val person = items[0] as FeedItem.Person
        assertEquals("Pema", person.card.displayName)
        val community = items[1] as FeedItem.Person
        assertTrue(community.card.isCommunity)
        val ad = items[2] as FeedItem.Ad
        assertEquals("https://example.org/learn", ad.ad.linkUrl)
        val news = items[3] as FeedItem.News
        assertEquals("Phayul", news.item.sourceName)
        val poll = items[4] as FeedItem.Post
        assertEquals("opt1", poll.post.myVote)
        assertEquals(4, poll.post.poll?.totalVotes)
        val event = items[5] as FeedItem.Post
        assertEquals(true, event.post.myRsvp)
        assertNull(page.candidates)
        assertNull(page.ads)
    }

    @Test
    fun feedLegacyShape() {
        val page = decode<FeedPage>(Fixtures.FEED_LEGACY)
        assertNull(page.items)
        assertEquals(listOf("u2"), page.candidates?.map { it.uid })
        assertEquals(listOf("ad1", "ad2"), page.ads?.map { it.adId })
        assertEquals(listOf("n1"), page.news?.map { it.newsId })
        assertEquals(listOf("p1", "p2"), page.communityPosts?.map { it.postId })

        val response = decode<FeedResponse>(Fixtures.FEED_LEGACY)
        assertEquals(1, response.candidates?.size)
        assertEquals(2, response.ads?.size)
        assertEquals(1, response.news?.size)
        assertEquals(2, response.communityPosts?.size)
    }

    @Test
    fun feedLegacyArraysStayStrict() {
        // Only `items` is per-element tolerant, exactly like Swift.
        assertThrows(SerializationException::class.java) {
            decode<FeedPage>("""{"candidates":[{"uid":"ok"},{"displayName":"no uid"}]}""")
        }
    }

    @Test
    fun feedItemsMustBeAnArray() {
        assertThrows(SerializationException::class.java) { decode<FeedPage>("""{"items":{"type":"person"}}""") }
        assertNull(decode<FeedPage>("""{"items":null}""").items)
        assertEquals(emptyList<FeedItem>(), decode<FeedPage>("""{"items":[]}""").items)
    }

    @Test
    fun feedItemRoundTrips() {
        val items = decode<FeedPage>(Fixtures.FEED_ITEMS).items!!
        val encoded = DrokpoJson.encodeToString(ListSerializer(FeedItem.serializer()), items)
        val again = DrokpoJson.decodeFromString(ListSerializer(FeedItem.serializer()), encoded)
        assertEquals(items, again)
    }

    @Test
    fun tolerantDecodingAlsoWorksFromAJsonTree() {
        // Same results when decoding from a parsed JsonElement (tree decoder)
        // instead of the streaming parser ApiClient uses.
        val page = DrokpoJson.decodeFromJsonElement(FeedPage.serializer(), DrokpoJson.parseToJsonElement(Fixtures.FEED_ITEMS))
        assertEquals(decode<FeedPage>(Fixtures.FEED_ITEMS), page)
        val liked = DrokpoJson.decodeFromJsonElement(
            LikedContentResponse.serializer(),
            DrokpoJson.parseToJsonElement(Fixtures.LIKED_CONTENT),
        )
        assertEquals(3, liked.items?.size)
        val matches = DrokpoJson.decodeFromJsonElement(
            TolerantList.serializer(Match.serializer()),
            DrokpoJson.parseToJsonElement(Fixtures.MATCHES),
        )
        assertEquals(2, matches.items.size)
    }

    @Test
    fun failableItemWrapsFailuresAsNull() {
        val list = decode<List<FailableItem<FeedCard>>>("""[{"uid":"a"},{"nope":1},null,"x"]""")
        assertEquals(listOf("a", null, null, null), list.map { it.value?.uid })
    }

    @Test
    fun communitiesHome() {
        val home = decode<CommunitiesHomeResponse>(Fixtures.COMMUNITIES_HOME)
        assertEquals(listOf("c1"), home.communities?.map { it.id })
        assertEquals(true, home.communities?.first()?.joined)
        assertEquals(listOf("post-p4", "post-p3", "ad-ad1"), home.items?.map { it.id })
    }

    @Test
    fun likedContentSkipsUnknownAndMalformedEntries() {
        val response = decode<LikedContentResponse>(Fixtures.LIKED_CONTENT)
        val items = response.items!!
        assertEquals(listOf("liked-news-n1", "liked-post-p1", "liked-news-n10"), items.map { it.id })
        val news = items[0] as LikedContent.News
        assertEquals("2026-10-05T09:00:00.654321+00:00", news.likedAt)
        assertEquals("Losar", news.item.title)
        val post = items[1] as LikedContent.Post
        assertEquals("2026-10-04T09:00:00+00:00", post.likedAt)
        assertEquals("poll", post.post.kind)
        assertNull(items[2].likedAt)
    }

    @Test
    fun likedContentRoundTrips() {
        val items = decode<LikedContentResponse>(Fixtures.LIKED_CONTENT).items!!
        val encoded = DrokpoJson.encodeToString(ListSerializer(LikedContent.serializer()), items)
        assertEquals(items, DrokpoJson.decodeFromString(ListSerializer(LikedContent.serializer()), encoded))
    }

    @Test
    fun adCards() {
        val ad = decode<AdCard>(Fixtures.AD)
        assertEquals("ad1", ad.id)
        assertEquals(listOf("ads/ad1/1.jpg"), ad.displayPhotos.map { it.storagePath })

        val imageOnly = decode<AdCard>(Fixtures.AD_IMAGE_ONLY)
        assertEquals(listOf(Photo("ad-image-ad2", 0, "https://example.org/momo.jpg")), imageOnly.displayPhotos)
        assertEquals(emptyList<Photo>(), decode<AdCard>("""{"adId":"ad3","photos":[]}""").displayPhotos)
    }

    @Test
    fun newsCard() {
        val news = decode<NewsCard>(Fixtures.NEWS)
        assertEquals("n1", news.id)
        assertEquals("Losar celebrations", news.title)
        assertEquals("2026-10-05T08:00:00+05:30", news.publishedAt)
        assertEquals(listOf(Photo("news-image-n1", 0, "https://phayul.com/a.jpg")), news.displayPhotos)
        assertEquals(emptyList<Photo>(), decode<NewsCard>("""{"newsId":"n2"}""").displayPhotos)
    }

    // MARK: - Communities

    @Test
    fun communityDirectoryAndDetail() {
        val directory = decode<CommunityListResponse>(Fixtures.DIRECTORY)
        assertEquals(listOf("c1", "c2"), directory.communities?.map { it.id })
        assertEquals(listOf(true, false), directory.communities?.map { it.joined })
        assertTrue(directory.communities!![0].isVerified)
        assertTrue(directory.communities[1].isPending)

        val detail = decode<CommunityProfile>(Fixtures.DIRECTORY_COMMUNITY)
        assertEquals(12, detail.memberCount)
        assertEquals("tat", detail.socials?.instagram)
    }

    @Test
    fun communityPostsAllKinds() {
        val posts = decode<CommunityPostsResponse>(Fixtures.POSTS).posts!!
        assertEquals(listOf("announcement", "link", "poll", "event"), posts.map { it.kind })

        val announcement = posts[0]
        assertEquals(listOf(Photo("post-image-p4", 0, "https://example.org/welcome.jpg")), announcement.displayPhotos)

        val link = posts[1]
        assertEquals(false, link.active)
        assertEquals("Read", link.ctaLabel)
        assertEquals(emptyList<Photo>(), link.displayPhotos)

        val poll = posts[2]
        assertEquals(
            Poll(listOf(PollOption("opt1", "Saturday"), PollOption("opt2", "Sunday")), mapOf("opt1" to 3, "opt2" to 1)),
            poll.poll,
        )
        assertEquals(4, poll.commentCount)
        assertEquals("2026-10-01T10:00:00.123456+00:00", poll.createdAt)

        val event = posts[3]
        assertNull(event.poll)
        assertEquals("Community hall", event.location)
        assertEquals(5, event.attendeeCount)
        assertNotNull(event.eventDate)
    }

    @Test
    fun pollDegradesToEmptyInsteadOfFailingThePage() {
        val missing = decode<CommunityPostCard>("""{"postId":"p","kind":"poll","poll":{}}""")
        assertEquals(Poll(emptyList(), emptyMap()), missing.poll)

        val malformed = decode<CommunityPostCard>(
            """{"postId":"p","poll":{"options":[{"id":"opt1"}],"counts":{"opt1":"three"}}}""",
        )
        assertEquals(Poll(emptyList(), emptyMap()), malformed.poll)

        val partial = decode<CommunityPostCard>(
            """{"postId":"p","poll":{"options":[{"id":"opt1","label":"Yes"}],"counts":null}}""",
        )
        assertEquals(Poll(listOf(PollOption("opt1", "Yes")), emptyMap()), partial.poll)

        // A non-object poll still fails, as Swift's container(keyedBy:) would.
        assertThrows(SerializationException::class.java) {
            decode<CommunityPostCard>("""{"postId":"p","poll":"broken"}""")
        }
    }

    @Test
    fun pollRoundTrips() {
        val poll = decode<VoteResult>(Fixtures.VOTE_RESULT).poll!!
        assertEquals(poll, decode<Poll>(DrokpoJson.encodeToString(poll)))
    }

    @Test
    fun voteAndRsvpResults() {
        val vote = decode<VoteResult>(Fixtures.VOTE_RESULT)
        assertEquals("opt1", vote.myVote)
        assertEquals(mapOf("opt1" to 4, "opt2" to 1), vote.poll?.counts)

        val rsvp = decode<RsvpResult>(Fixtures.RSVP_RESULT)
        assertEquals(6, rsvp.attendeeCount)
        assertEquals(true, rsvp.going)
    }

    @Test
    fun communityMembers() {
        val members = decode<CommunityMembersResponse>(Fixtures.MEMBERS).members!!
        assertEquals(listOf("u1", "u2"), members.map { it.id })
        assertEquals("users/u1/photos/a.jpg", members[0].photo?.storagePath)
        assertNull(members[1].photo)
        assertNull(members[1].displayName)
    }

    // MARK: - Comments

    @Test
    fun commentsRepliesAndVotes() {
        val comments = decode<CommentsResponse>(Fixtures.COMMENTS).comments!!
        assertEquals(listOf("cm1", "cm2", "cm3"), comments.map { it.id })
        assertEquals("like", comments[0].myVote)
        assertTrue(comments[0].isTopLevel)
        assertFalse(comments[0].isCommunityAuthor)
        assertTrue(comments[1].isCommunityAuthor)
        assertEquals(12, comments[1].audioDurationSec)
        assertNull(comments[1].text)
        // Account-deletion tombstone: decodes like any other comment.
        assertEquals("Deleted account", comments[2].authorName)
        assertNull(comments[2].authorUid)

        val replies = decode<RepliesResponse>(Fixtures.REPLIES).replies!!
        assertEquals("cm1", replies.single().parentId)
        assertFalse(replies.single().isTopLevel)

        val created = decode<CommentCard>(Fixtures.COMMENT)
        assertEquals("Count me in!", created.text)

        assertEquals(CommentVoteResult(4, 0, "like"), decode<CommentVoteResult>(Fixtures.COMMENT_VOTE))
        assertEquals(CommentVoteResult(3, 0, null), decode<CommentVoteResult>(Fixtures.COMMENT_VOTE_CLEARED))
    }

    // MARK: - Matches / swipes / messages (TolerantList endpoints)

    @Test
    fun matchesFromWrapperObject() {
        val matches = decode<TolerantList<Match>>(Fixtures.MATCHES).items
        assertEquals(listOf("u1_u2", "c1_u1"), matches.map { it.id })
        val first = matches[0]
        assertEquals("Tashi delek!", first.lastMessage?.text)
        assertEquals("u2", first.lastMessage?.senderId)
        assertEquals(2, first.unread("u1"))
        assertEquals(0, first.unread("u2"))
        assertEquals(0, first.unread(null))
        assertEquals(0, first.unread("someone-else"))
        assertEquals("Pema", first.otherUser?.displayName)
        assertNull(matches[1].lastMessage)
        assertTrue(matches[1].otherUser!!.isCommunity)
    }

    @Test
    fun swipesFromWrapperObject() {
        val swipes = decode<TolerantList<SwipeEntry>>(Fixtures.SWIPES).items
        assertEquals(listOf("u2", "c1", "u3"), swipes.map { it.id })
        assertFalse(swipes[0].isMatched)
        assertTrue(swipes[1].isMatched)
        assertEquals("active", swipes[1].matchStatus)
        assertEquals("superlike", swipes[1].action)
        assertFalse(swipes[2].isMatched)
    }

    @Test
    fun sentMessagesFromWrapperObject() {
        val sent = decode<TolerantList<SentMessage>>(Fixtures.SENT_MESSAGES).items
        assertEquals(listOf("m1", "m2"), sent.map { it.id })
        assertEquals("u1_u2", sent[0].matchId)
        assertNotNull(sent[0].sentDate)
    }

    @Test
    fun tolerantListAcceptsBareArrays() {
        val list = decode<TolerantList<Match>>("""[{"matchId":"a"},{"matchId":"b"}]""").items
        assertEquals(listOf("a", "b"), list.map { it.matchId })
    }

    @Test
    fun tolerantListShapes() {
        // A bare array decodes strictly (Swift's unkeyed branch rethrows).
        assertThrows(SerializationException::class.java) {
            decode<TolerantList<FeedCard>>("""[{"uid":"a"},{"displayName":"no uid"}]""")
        }
        // In a wrapper, an undecodable list is skipped (try?) …
        assertEquals(
            emptyList<FeedCard>(),
            decode<TolerantList<FeedCard>>("""{"cards":[{"uid":"a"},{"displayName":"no uid"}]}""").items,
        )
        // … and the first key that does decode wins; non-list values are ignored.
        assertEquals(
            listOf("b"),
            decode<TolerantList<FeedCard>>("""{"ok":true,"bad":[{"x":1}],"cards":[{"uid":"b"}]}""").items.map { it.uid },
        )
        assertEquals(emptyList<FeedCard>(), decode<TolerantList<FeedCard>>("""{}""").items)
        assertEquals(emptyList<FeedCard>(), decode<TolerantList<FeedCard>>("""{"cards":null}""").items)
        assertThrows(SerializationException::class.java) { decode<TolerantList<FeedCard>>("\"nope\"") }
        assertThrows(SerializationException::class.java) { decode<TolerantList<FeedCard>>("null") }
    }

    @Test
    fun swipeResults() {
        val matched = decode<SwipeResult>(Fixtures.SWIPE_MATCHED)
        assertEquals(true, matched.matched)
        assertEquals("u1_u2", matched.matchId)
        assertTrue(matched.isMatch)

        val unmatched = decode<SwipeResult>(Fixtures.SWIPE_UNMATCHED)
        assertFalse(unmatched.isMatch)
        assertNull(unmatched.matchId)

        // `matched` missing: falls back to matchId/match presence.
        assertTrue(decode<SwipeResult>("""{"matchId":"x"}""").isMatch)
        assertTrue(decode<SwipeResult>("""{"match":{"matchId":"x"}}""").isMatch)
        assertFalse(decode<SwipeResult>("""{}""").isMatch)
        // An explicit `matched` wins over matchId.
        assertFalse(decode<SwipeResult>("""{"matched":false,"matchId":"x"}""").isMatch)
    }

    // MARK: - EmptyResponse

    @Test
    fun emptyResponseDecodesFromAnyObject() {
        for (body in listOf("""{}""", """{"ok":true}""", """{"uid":"u1"}""", """{"postId":"p1"}""", """{"messageId":"m","nested":{"a":[1,2]}}""")) {
            assertEquals(EmptyResponse, decode<EmptyResponse>(body))
        }
        assertThrows(SerializationException::class.java) { decode<EmptyResponse>("[]") }
        assertThrows(SerializationException::class.java) { decode<EmptyResponse>("") }
        assertEquals("{}", DrokpoJson.encodeToString(EmptyResponse))
    }

    @Test
    fun wrongTypesStillFail() {
        // Same strictness as JSONDecoder for a present-but-mistyped value.
        assertThrows(SerializationException::class.java) { decode<NewsCard>("""{"newsId":"n","title":7}""") }
        assertThrows(SerializationException::class.java) { decode<CommunityProfile>("""{"memberCount":"many"}""") }
    }
}
