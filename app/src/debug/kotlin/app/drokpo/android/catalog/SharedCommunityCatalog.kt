package app.drokpo.android.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.CommunityProfile
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.core.model.Vocabulary
import app.drokpo.android.features.shared.community.CommunityPageContent
import app.drokpo.android.features.shared.community.CommunityPageState
import app.drokpo.android.features.shared.community.CommunityPostContentView
import app.drokpo.android.features.shared.community.CommunityPostDetailContent
import app.drokpo.android.features.shared.community.PostTile
import app.drokpo.android.features.shared.news.NewsDetailContent
import app.drokpo.android.features.shared.news.NewsDetailSheetContent
import app.drokpo.android.ui.components.ActionSheet
import app.drokpo.android.ui.components.ActionSheetItem
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.theme.DrokpoTheme

// Group 9 (shared community + news) — CONTRACT.md §E. Every entry renders the stateless *Content
// composables with fixtures: CommunityPageContent (visitor / owner, every header + grid state),
// PostTile variants, CommunityPostContentView for every kind, the post detail sheet's page and the
// news detail sheet's page (sheet content rendered full-screen, not the ModalBottomSheet).

// ---------------------------------------------------------------- private fixtures

private val tat: CommunityProfile = Fixtures.community
private val tatId: String = tat.uid ?: "c-tat"

/** A fuller grid: every kind, with and without photos, plus the owner-only unpublished draft. */
private val gridPosts: List<CommunityPostCard> = run {
    val base = Fixtures.posts.filter { it.active != false }
    val extras = listOf(
        Fixtures.announcementPost.copy(
            postId = "p-grid-dance",
            title = "Youth dance troupe rehearsals",
            imageUrl = Fixtures.imageUrl("drokpo-post-dance", 800, 800),
        ),
        Fixtures.linkPost.copy(
            postId = "p-grid-donate",
            title = "Donate to the flood relief fund",
            imageUrl = Fixtures.imageUrl("drokpo-post-relief", 800, 800),
        ),
        Fixtures.pollPost.copy(postId = "p-grid-poll-2", title = "Which momo filling wins?"),
        Fixtures.eventPost.copy(postId = "p-grid-event-2", title = "Saga Dawa prayer evening", imageUrl = null),
        Fixtures.announcementPost.copy(
            postId = "p-grid-library",
            title = "The community library now lends Tibetan e-books to members",
            imageUrl = null,
        ),
        Fixtures.announcementPost.copy(
            postId = "p-grid-football",
            title = "Football tournament results",
            imageUrl = Fixtures.imageUrl("drokpo-post-football", 800, 800),
        ),
    )
    base + extras
}

private val ownerGridPosts: List<CommunityPostCard> = listOf(Fixtures.unpublishedPost) + gridPosts

private val visitorNotJoined: CommunityProfile = tat.copy(joined = false, memberCount = 127)

private val minimalCommunity: CommunityProfile = CommunityProfile(
    uid = "c-minimal",
    name = "Lhasa Kitchen Supper Club",
    verification = "verified",
    memberCount = 1,
    joined = false,
)

private val pendingOwner: CommunityProfile = Fixtures.communityPending

private val postNoCommunity: CommunityPostCard = Fixtures.announcementPost.copy(
    postId = "p-no-community",
    communityId = null,
    communityName = null,
    communityLogoUrl = null,
    imageUrl = null,
    commentCount = null,
)

private val newsMinimal: NewsCard = NewsCard(
    newsId = "n-minimal",
    gist = "A story with no title, no source name, no date and no image — only its gist.",
    sourceUrl = "https://example.org/news/minimal",
)

// ---------------------------------------------------------------- entries

val sharedCommunityCatalogEntries: List<CatalogEntry> = listOf(
    // Community page — visitor
    CatalogEntry("sharedcommunity.page.visitor", "Community page — visitor, joined, populated") {
        PagePreview(visitorState(tat))
    },
    CatalogEntry("sharedcommunity.page.visitor.notjoined", "Community page — visitor, not joined (Join, count only)") {
        PagePreview(visitorState(visitorNotJoined))
    },
    CatalogEntry("sharedcommunity.page.visitor.joining", "Community page — Join in flight (spinner)") {
        PagePreview(visitorState(visitorNotJoined).copy(isJoining = true))
    },
    CatalogEntry("sharedcommunity.page.visitor.leaving", "Community page — Joined, leave in flight") {
        PagePreview(visitorState(tat).copy(isJoining = true))
    },
    CatalogEntry("sharedcommunity.page.visitor.minimal", "Community page — no logo/description/website, 1 member") {
        PagePreview(visitorState(minimalCommunity, posts = Fixtures.posts.take(2)))
    },
    CatalogEntry("sharedcommunity.page.visitor.communityaccount", "Community page — viewed by a community account (no Join)") {
        PagePreview(visitorState(visitorNotJoined).copy(isCommunityAccount = true))
    },
    CatalogEntry("sharedcommunity.page.visitor.loading", "Community page — preview header, posts loading") {
        PagePreview(visitorState(visitorNotJoined, posts = emptyList()).copy(isLoadingHeader = true, isLoadingPosts = true))
    },
    CatalogEntry("sharedcommunity.page.visitor.loadingheader", "Community page — no preview, header loading") {
        PagePreview(visitorState(null, posts = emptyList()).copy(isLoadingHeader = true, isLoadingPosts = true))
    },
    CatalogEntry("sharedcommunity.page.visitor.empty", "Community page — visitor, no posts yet") {
        PagePreview(visitorState(visitorNotJoined, posts = emptyList()))
    },
    CatalogEntry("sharedcommunity.page.visitor.loadingmore", "Community page — next page loading") {
        PagePreview(visitorState(tat).copy(isLoadingMore = true))
    },
    CatalogEntry("sharedcommunity.page.visitor.refreshing", "Community page — pull to refresh") {
        PagePreview(visitorState(tat).copy(isRefreshing = true))
    },
    CatalogEntry("sharedcommunity.page.visitor.menu", "Community page — Report or block menu open") {
        PagePreview(visitorState(visitorNotJoined), menuExpanded = true)
    },
    CatalogEntry("sharedcommunity.page.visitor.report", "Community page — report reasons sheet") {
        PagePreview(visitorState(visitorNotJoined))
        ActionSheet(
            onDismissRequest = {},
            title = "Why are you reporting this community?",
            items = Vocabulary.reportReasons.map { ActionSheetItem(it, destructive = true) {} },
        )
    },
    CatalogEntry("sharedcommunity.page.visitor.block", "Community page — block confirmation") {
        PagePreview(visitorState(visitorNotJoined))
        ActionSheet(
            onDismissRequest = {},
            title = "Block ${visitorNotJoined.name ?: "this community"}?",
            message = "You won't see this community or its posts.",
            items = listOf(ActionSheetItem("Block", destructive = true) {}),
        )
    },
    CatalogEntry("sharedcommunity.page.visitor.error", "Community page — load failed (alert; header spinner stays, like iOS)") {
        PagePreview(visitorState(null, posts = emptyList()).copy(isLoadingHeader = true))
        ErrorAlert(message = "The Internet connection appears to be offline.", onDismiss = {})
    },
    CatalogEntry("sharedcommunity.page.visitor.error.preview", "Community page — posts failed under a preview header") {
        PagePreview(visitorState(visitorNotJoined, posts = emptyList()))
        ErrorAlert(message = "Server error (500).", onDismiss = {})
    },

    // Community page — owner (community tab root)
    CatalogEntry("sharedcommunity.page.owner", "Community page — owner tab root (New post, unpublished badge)") {
        PagePreview(ownerState(tat), navIcon = NavIcon.None)
    },
    CatalogEntry("sharedcommunity.page.owner.pending", "Community page — owner, awaiting verification banner") {
        PagePreview(ownerState(pendingOwner), navIcon = NavIcon.None)
    },
    CatalogEntry("sharedcommunity.page.owner.empty", "Community page — owner, no posts yet") {
        PagePreview(ownerState(pendingOwner, posts = emptyList()), navIcon = NavIcon.None)
    },
    CatalogEntry("sharedcommunity.page.owner.loading", "Community page — owner, posts loading") {
        PagePreview(ownerState(tat, posts = emptyList()).copy(isLoadingPosts = true), navIcon = NavIcon.None)
    },

    // Grid tiles
    CatalogEntry("sharedcommunity.tiles", "Post tiles — every kind, photo + placeholder, unpublished") {
        TilesPreview()
    },

    // CommunityPostContentView
    CatalogEntry("sharedcommunity.post.announcement", "Post content — announcement with photo") {
        PostPreview(Fixtures.announcementPost)
    },
    CatalogEntry("sharedcommunity.post.link", "Post content — link (CTA)") {
        PostPreview(Fixtures.linkPost)
    },
    CatalogEntry("sharedcommunity.post.poll", "Post content — poll, not voted") {
        PostPreview(Fixtures.pollPost)
    },
    CatalogEntry("sharedcommunity.post.poll.voted", "Post content — poll, voted (percentages)") {
        PostPreview(Fixtures.pollPostVoted)
    },
    CatalogEntry("sharedcommunity.post.poll.novotes", "Post content — poll, no votes yet") {
        PostPreview(
            Fixtures.pollPost.copy(
                postId = "p-poll-empty",
                poll = Fixtures.pollPost.poll?.copy(counts = emptyMap()),
            ),
        )
    },
    CatalogEntry("sharedcommunity.post.event", "Post content — event, not going (Join)") {
        PostPreview(Fixtures.eventPost)
    },
    CatalogEntry("sharedcommunity.post.event.going", "Post content — event, going (Can't come)") {
        PostPreview(Fixtures.eventPostGoing)
    },
    CatalogEntry("sharedcommunity.post.readonly", "Post content — read-only (no callbacks)") {
        PostPreview(Fixtures.eventPost, interactive = false)
    },
    CatalogEntry("sharedcommunity.post.readonly.poll", "Post content — read-only poll (voted)") {
        PostPreview(Fixtures.pollPostVoted, interactive = false)
    },
    CatalogEntry("sharedcommunity.post.nocommunity", "Post content — no community id/logo, no comment count") {
        PostPreview(postNoCommunity)
    },
    CatalogEntry("sharedcommunity.posts", "Post content — every kind, in a list") {
        AllPostsPreview()
    },

    // CommunityPostDetailSheet (its root page, full-screen)
    CatalogEntry("sharedcommunity.postdetail", "Post detail sheet — event (visitor)") {
        PostDetailPreview(Fixtures.eventPost)
    },
    CatalogEntry("sharedcommunity.postdetail.poll", "Post detail sheet — poll") {
        PostDetailPreview(Fixtures.pollPost)
    },
    CatalogEntry("sharedcommunity.postdetail.owner", "Post detail sheet — owner, published (Unpublish)") {
        PostDetailPreview(Fixtures.announcementPost, ownerMode = true)
    },
    CatalogEntry("sharedcommunity.postdetail.owner.unpublished", "Post detail sheet — owner, unpublished (Republish)") {
        PostDetailPreview(Fixtures.unpublishedPost, ownerMode = true)
    },
    CatalogEntry("sharedcommunity.postdetail.error", "Post detail sheet — vote failed alert") {
        PostDetailPreview(Fixtures.pollPost)
        ErrorAlert(message = "Server error (500).", onDismiss = {})
    },

    // NewsDetailSheet (its page, full-screen) and the bare NewsDetailContent
    CatalogEntry("sharedcommunity.news.detail", "News detail sheet — image, source, summary") {
        NewsDetailSheetContent(item = Fixtures.news, onReadFullStory = {}, onClose = {})
    },
    CatalogEntry("sharedcommunity.news.detail.noimage", "News detail sheet — no image, gist only, bare date") {
        NewsDetailSheetContent(item = Fixtures.newsNoImage, onReadFullStory = {}, onClose = {})
    },
    CatalogEntry("sharedcommunity.news.detail.minimal", "News detail sheet — no title/source/date") {
        NewsDetailSheetContent(item = newsMinimal, onReadFullStory = {}, onClose = {})
    },
    CatalogEntry("sharedcommunity.news.content", "NewsDetailContent — as embedded by ShareDestinationSheet") {
        NewsContentPreview(Fixtures.newsList[2])
    },
)

// ---------------------------------------------------------------- helpers

private fun visitorState(community: CommunityProfile?, posts: List<CommunityPostCard> = gridPosts) = CommunityPageState(
    cid = community?.uid ?: tatId,
    community = community,
    ownerMode = false,
    isCommunityAccount = false,
    posts = posts,
)

private fun ownerState(community: CommunityProfile, posts: List<CommunityPostCard> = ownerGridPosts) = CommunityPageState(
    cid = community.uid ?: tatId,
    community = community,
    ownerMode = true,
    isCommunityAccount = true,
    posts = posts,
)

@Composable
private fun PagePreview(state: CommunityPageState, navIcon: NavIcon = NavIcon.Back, menuExpanded: Boolean = false) {
    CommunityPageContent(
        state = state,
        navIcon = navIcon,
        onNavIcon = {},
        onNewPost = {},
        onReport = {},
        onBlock = {},
        onToggleJoin = {},
        onOpenWebsite = {},
        onOpenMembers = {},
        onRefresh = {},
        onSelectPost = {},
        onTileAppear = {},
        initialMenuExpanded = menuExpanded,
    )
}

@Composable
private fun TilesPreview() {
    val tiles = listOf(
        Fixtures.announcementPost,
        Fixtures.eventPost,
        gridPosts.first { it.postId == "p-grid-donate" },
        Fixtures.announcementPost.copy(postId = "t-ann", imageUrl = null),
        Fixtures.linkPost,
        Fixtures.pollPost,
        Fixtures.eventPost.copy(postId = "t-event", imageUrl = null),
        Fixtures.unpublishedPost,
        Fixtures.unpublishedPost.copy(postId = "t-unpub-photo", imageUrl = Fixtures.imageUrl("drokpo-post-draft", 800, 800)),
        Fixtures.announcementPost.copy(postId = "t-untitled", title = null, imageUrl = null),
    )
    Scaffold(
        topBar = { DrokpoTopBar("Post tiles", navIcon = NavIcon.Back) },
        containerColor = DrokpoTheme.colors.background,
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(tiles, key = { it.postId }) { post ->
                PostTile(post = post, showUnpublishedBadge = true, onClick = {})
            }
        }
    }
}

@Composable
private fun PostPreview(post: CommunityPostCard, interactive: Boolean = true) {
    Scaffold(
        topBar = { DrokpoTopBar(post.kind ?: "post", navIcon = NavIcon.Back) },
        containerColor = DrokpoTheme.colors.background,
    ) { padding ->
        CommunityPostContentView(
            post = post,
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            onVote = if (interactive) ({}) else null,
            onRsvp = if (interactive) ({}) else null,
            onOpenLink = if (interactive) ({}) else null,
            onOpenComments = if (interactive) ({}) else null,
            onOpenCommunity = if (interactive) ({}) else null,
        )
    }
}

@Composable
private fun AllPostsPreview() {
    Scaffold(
        topBar = { DrokpoTopBar("Posts") },
        containerColor = DrokpoTheme.colors.background,
    ) { padding ->
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(Fixtures.posts, key = { it.postId }) { post ->
                CommunityPostContentView(
                    post = post,
                    onVote = {},
                    onRsvp = {},
                    onOpenLink = {},
                    onOpenComments = {},
                    onOpenCommunity = {},
                )
                HorizontalDivider(Modifier.padding(top = 16.dp), color = DrokpoTheme.colors.separator)
            }
        }
    }
}

@Composable
private fun PostDetailPreview(post: CommunityPostCard, ownerMode: Boolean = false) {
    CommunityPostDetailContent(
        post = post,
        ownerMode = ownerMode,
        onVote = if (post.kind == "poll") ({}) else null,
        onRsvp = if (post.kind == "event") ({}) else null,
        onOpenLink = if (!post.linkUrl.isNullOrBlank()) ({}) else null,
        onTogglePublish = if (ownerMode) ({}) else null,
        onClose = {},
        onOpenComments = {},
        onOpenCommunity = {},
    )
}

@Composable
private fun NewsContentPreview(item: NewsCard) {
    Scaffold(
        topBar = { DrokpoTopBar("News", navIcon = NavIcon.Close) },
        containerColor = DrokpoTheme.colors.background,
    ) { padding ->
        NewsDetailContent(
            item = item,
            onReadFullStory = {},
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        )
    }
}
