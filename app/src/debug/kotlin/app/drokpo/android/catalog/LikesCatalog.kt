package app.drokpo.android.catalog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.drokpo.android.MainTab
import app.drokpo.android.MainTabsScaffold
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.LikedContent
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.features.likes.Direction
import app.drokpo.android.features.likes.GivenFilter
import app.drokpo.android.features.likes.LikeRow
import app.drokpo.android.features.likes.LikedNewsRow
import app.drokpo.android.features.likes.LikedPostDetailContent
import app.drokpo.android.features.likes.LikedPostRow
import app.drokpo.android.features.likes.LikesActions
import app.drokpo.android.features.likes.LikesAlerts
import app.drokpo.android.features.likes.LikesContent
import app.drokpo.android.features.likes.LikesUiState
import app.drokpo.android.features.likes.MatchedAlert
import app.drokpo.android.features.likes.RemovableRow
import app.drokpo.android.features.likes.RemoveMenu
import app.drokpo.android.features.likes.RowRemoval
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.PlainSectionHeader
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.visibleTabs

// Group 5 (likes). Every state of the Likes tab rendered from fixtures through the stateless
// LikesContent / LikedPostDetailContent (no ViewModel, network or AppGraph). The segment and the
// filter pills are live in the list entries, so one entry can be clicked through by hand.

/** Populated: people + saved content (one item without likedAt sorts last) and likers. */
private val populated = LikesUiState(
    received = Fixtures.receivedLikes,
    given = Fixtures.givenLikes,
    likedContent = Fixtures.likedContent,
    isLoading = false,
)

/** Only posts are saved — the "News" pill has nothing to show. */
private val populatedNoNews = populated.copy(
    likedContent = Fixtures.likedContent.filterIsInstance<LikedContent.Post>(),
)

private val nothing = LikesUiState(isLoading = false)

/** A name-only liker and a news story / post with every optional field missing. */
private val sparseNews = NewsCard(newsId = "n-sparse", sourceUrl = "https://example.org/news/sparse")
private val sparsePost = CommunityPostCard(postId = "p-sparse", kind = "announcement")
private val longNames = FeedCard(
    uid = "u-long",
    displayName = "Tenzin Choedon Lhamo Dolkar Tsering",
    age = 41,
    region = "United States of America",
    photos = listOf(Fixtures.photo("drokpo-long")),
)

private val offlineMessage = "The Internet connection appears to be offline."

@Composable
private fun LikesEntry(initial: LikesUiState) {
    // Segment and pills are interactive; everything else is fixture data.
    var state by remember { mutableStateOf(initial) }
    LikesContent(
        state = state,
        actions = LikesActions(
            onSelectDirection = { state = state.copy(direction = it) },
            onSelectFilter = { state = state.copy(givenFilter = it) },
        ),
    )
    LikesAlerts(
        matched = state.matched,
        errorMessage = state.errorMessage,
        onSayHi = {},
        onDismissMatch = { state = state.copy(matched = null) },
        onDismissError = { state = state.copy(errorMessage = null) },
    )
}

/** The Likes root inside the real tab chrome (Likes selected), for side-by-side with iOS. */
@Composable
private fun LikesInTabs(initial: LikesUiState) {
    MainTabsScaffold(
        tabs = visibleTabs(isCommunity = false),
        selected = MainTab.Likes,
        chatsUnread = 2,
        onSelect = {},
    ) { LikesEntry(initial) }
}

/** Every row variant, stacked (photo / no photo, community capsule, long names, sparse content). */
@Composable
private fun LikesRowsGallery() {
    Scaffold(
        topBar = { DrokpoTopBar("Likes rows") },
        containerColor = DrokpoTheme.colors.background,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            PlainSectionHeader("Liked you (heart)")
            Fixtures.receivedLikes.mapNotNull { it.otherUser }.forEach { card ->
                LikeRow(card = card, showLikeBack = true, onClick = {}, onLikeBack = {})
            }
            LikeRow(card = longNames, showLikeBack = true, onClick = {}, onLikeBack = {})
            PlainSectionHeader("You liked — people")
            LikeRow(card = Fixtures.feedCard, showLikeBack = false, onClick = {}, onLikeBack = {})
            LikeRow(card = Fixtures.communityCard, showLikeBack = false, onClick = {}, onLikeBack = {})
            PlainSectionHeader("You liked — news")
            LikedNewsRow(item = Fixtures.news, onOpen = {})
            LikedNewsRow(item = Fixtures.newsNoImage, onOpen = {})
            LikedNewsRow(item = sparseNews, onOpen = {})
            PlainSectionHeader("You liked — posts")
            listOf(Fixtures.announcementPost, Fixtures.linkPost, Fixtures.pollPost, Fixtures.eventPost, sparsePost)
                .forEach { post -> LikedPostRow(post = post, onClick = {}) }
        }
    }
}

/**
 * The real swipe rows: the first two start with their red "Remove" button revealed, and the
 * third is closed. All three are live, so the reveal, close and full-swipe gestures can be tried
 * by hand. A remove "fails" here (returns false), so the row slides back instead of going.
 */
@Composable
private fun LikesSwipeRemove() {
    Scaffold(
        topBar = { DrokpoTopBar("Swipe to remove") },
        containerColor = DrokpoTheme.colors.background,
    ) { padding ->
        Column(Modifier.padding(padding)) {
            RemovableRow(id = "news", onRemove = { false }, initiallyRevealed = true) { removal ->
                LikedNewsRow(item = Fixtures.news, onOpen = {}, removal = removal)
            }
            RemovableRow(id = "event", onRemove = { false }, initiallyRevealed = true) { removal ->
                LikedPostRow(post = Fixtures.eventPost, onClick = {}, removal = removal)
            }
            RemovableRow(id = "link", onRemove = { false }) { removal ->
                LikedPostRow(post = Fixtures.linkPost, onClick = {}, removal = removal)
            }
        }
    }
}

/** The Android long-press menu on a saved row, open. Long-press the row to reopen it. */
@Composable
private fun LikesRemoveMenu() {
    var menuOpen by remember { mutableStateOf(true) }
    val removal = remember { RowRemoval(onLongPress = { menuOpen = true }, onRemove = {}) }
    Scaffold(
        topBar = { DrokpoTopBar("Long-press to remove") },
        containerColor = DrokpoTheme.colors.background,
    ) { padding ->
        Column(Modifier.padding(padding)) {
            LikedPostRow(post = Fixtures.announcementPost, onClick = {})
            Box {
                LikedNewsRow(item = Fixtures.news, onOpen = {}, removal = removal)
                RemoveMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, onRemove = { menuOpen = false })
            }
            LikedPostRow(post = Fixtures.linkPost, onClick = {})
        }
    }
}

@Composable
private fun SavedPostEntry(post: CommunityPostCard) {
    LikedPostDetailContent(
        post = post,
        onBack = {},
        // Same test as the real screen: post.url treats a blank linkUrl as no link (iOS post.url != nil).
        onOpenLink = if (post.url != null) ({}) else null,
        onOpenComments = {},
        onOpenCommunity = {},
    )
}

val likesCatalogEntries: List<CatalogEntry> = listOf(
    // "You liked" (default segment, left) with each pill.
    CatalogEntry("likes.given.all", "Likes — You liked · All (people + saved, newest first)") {
        LikesEntry(populated)
    },
    CatalogEntry("likes.given.friends", "Likes — You liked · Friends") {
        LikesEntry(populated.copy(givenFilter = GivenFilter.Friends))
    },
    CatalogEntry("likes.given.communities", "Likes — You liked · Communities (saved posts)") {
        LikesEntry(populated.copy(givenFilter = GivenFilter.Communities))
    },
    CatalogEntry("likes.given.news", "Likes — You liked · News") {
        LikesEntry(populated.copy(givenFilter = GivenFilter.News))
    },
    CatalogEntry("likes.given.empty", "Likes — You liked · empty (\"Nothing saved yet\")") {
        LikesEntry(nothing)
    },
    CatalogEntry("likes.given.news.empty", "Likes — You liked · News pill with nothing to show") {
        LikesEntry(populatedNoNews.copy(givenFilter = GivenFilter.News))
    },
    // "Liked you".
    CatalogEntry("likes.received.list", "Likes — Liked you (hearts, community capsule, no-photo row)") {
        LikesEntry(populated.copy(direction = Direction.Received))
    },
    CatalogEntry("likes.received.empty", "Likes — Liked you · empty (\"No likes yet\")") {
        LikesEntry(nothing.copy(direction = Direction.Received))
    },
    CatalogEntry("likes.received.match", "Likes — Liked you · \"It's a match!\" alert (row heart)") {
        LikesEntry(
            populated.copy(
                direction = Direction.Received,
                received = Fixtures.receivedLikes.drop(1),
                matched = MatchedAlert(name = "Yangchen Dolkar", matchId = "m-yangchen"),
            ),
        )
    },
    // Loading / refresh / error.
    CatalogEntry("likes.loading", "Likes — first load (pills + spinner)") {
        LikesEntry(LikesUiState())
    },
    CatalogEntry("likes.loading.received", "Likes — first load on Liked you (from a like push)") {
        LikesEntry(LikesUiState(direction = Direction.Received))
    },
    CatalogEntry("likes.refreshing", "Likes — pull-to-refresh in progress") {
        LikesEntry(populated.copy(isRefreshing = true))
    },
    CatalogEntry("likes.error", "Likes — reload failed over existing lists (alert)") {
        LikesEntry(populated.copy(errorMessage = offlineMessage))
    },
    CatalogEntry("likes.error.empty", "Likes — first load failed (empty + alert)") {
        LikesEntry(nothing.copy(errorMessage = offlineMessage))
    },
    CatalogEntry("likes.error.partial", "Likes — likers loaded, saved content failed (alert)") {
        LikesEntry(
            nothing.copy(
                received = Fixtures.receivedLikes,
                given = Fixtures.givenLikes,
                errorMessage = "Server error (500).",
            ),
        )
    },
    // Chrome and rows.
    CatalogEntry("likes.tabbar", "Likes — in the tab bar (person account)") {
        LikesInTabs(populated)
    },
    CatalogEntry("likes.rows", "Likes — every row variant") { LikesRowsGallery() },
    CatalogEntry("likes.rows.swipe", "Likes — swipe-to-remove revealed on saved rows") { LikesSwipeRemove() },
    CatalogEntry("likes.rows.menu", "Likes — long-press \"Remove\" menu on a saved row") { LikesRemoveMenu() },
    // Saved post detail (read-only, pushed from "You liked").
    CatalogEntry("likes.savedpost.event", "Saved post — event (link, comments, share)") {
        SavedPostEntry(Fixtures.eventPost)
    },
    CatalogEntry("likes.savedpost.link", "Saved post — link") { SavedPostEntry(Fixtures.linkPost) },
    CatalogEntry("likes.savedpost.poll", "Saved post — poll (read-only)") { SavedPostEntry(Fixtures.pollPostVoted) },
    CatalogEntry("likes.savedpost.announcement", "Saved post — announcement") {
        SavedPostEntry(Fixtures.announcementPost)
    },
    CatalogEntry("likes.savedpost.untitled", "Saved post — no community name (\"Saved post\")") {
        SavedPostEntry(sparsePost)
    },
)
