package app.drokpo.android.features.communities

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.drokpo.android.core.PhotoBand
import app.drokpo.android.core.RemotePhotoView
import app.drokpo.android.core.model.AdCard
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.CommunityProfile
import app.drokpo.android.core.model.FeedItem
import app.drokpo.android.features.shared.comments.CommentsSheet
import app.drokpo.android.features.shared.community.CommunityPostContentView
import app.drokpo.android.navigation.backOrClose
import app.drokpo.android.navigation.navIconFor
import app.drokpo.android.navigation.openCommunity
import app.drokpo.android.navigation.sharedDestinations
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.ListDivider
import app.drokpo.android.ui.components.LoadingState
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.components.PlainSectionHeader
import app.drokpo.android.ui.components.ProminentButton
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.rememberInAppBrowser
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold
import kotlinx.serialization.Serializable

/** Routes of the Communities covers' own NavHosts (the shared routes come from sharedDestinations). */
@Serializable
internal sealed interface CommunitiesRoute {
    /** CommunitiesView's list: rail + feed. */
    @Serializable
    data object Home : CommunitiesRoute

    /** CommunityDirectoryView ("Discover communities"). */
    @Serializable
    data object Directory : CommunitiesRoute
}

/**
 * Port of CommunitiesView — a person's community browsing, presented by
 * FeedScreen inside a FullScreenCover (there's no dedicated Communities tab
 * for persons; it's reached from a button overlaid on the Discover deck, hence
 * the explicit Close). Owns its NavHost: Home → Directory / SharedRoute.*
 * (CONTRACT §B.8, §C.2). "Close" → [onClose].
 */
@Composable
fun CommunitiesScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = CommunitiesRoute.Home, modifier = modifier) {
        composable<CommunitiesRoute.Home> {
            CommunitiesHomeScreen(
                onClose = onClose,
                onOpenDirectory = { nav.navigate(CommunitiesRoute.Directory) },
                onOpenCommunity = { cid, preview -> nav.openCommunity(cid, preview) },
            )
        }
        composable<CommunitiesRoute.Directory> { entry ->
            CommunityDirectoryScreen(
                onBack = { nav.backOrClose(onClose) },
                onOpenCommunity = { cid, preview -> nav.openCommunity(cid, preview) },
                navIcon = nav.navIconFor(entry, onClose),
            )
        }
        sharedDestinations(nav, onCloseHost = onClose)
    }
}

/** The Home destination: wires [CommunitiesModel], the in-app browser and the comments sheet. */
@Composable
private fun CommunitiesHomeScreen(
    onClose: () -> Unit,
    onOpenDirectory: () -> Unit,
    onOpenCommunity: (cid: String, preview: CommunityProfile?) -> Unit,
) {
    val model = viewModel { CommunitiesModel() }
    val state by model.state.collectAsStateWithLifecycle()
    val openUrl = rememberInAppBrowser()
    var commentsPost by remember { mutableStateOf<CommunityPostCard?>(null) }

    // Re-fires when popping back from a pushed page (the destination re-enters
    // composition), so a join/leave there is reflected here immediately.
    LaunchedEffect(Unit) { model.onAppear() }

    CommunitiesContent(
        state = state,
        onClose = onClose,
        onOpenDirectory = onOpenDirectory,
        onOpenCommunity = onOpenCommunity,
        onRefresh = model::refresh,
        onVote = model::vote,
        onRsvp = model::rsvp,
        onOpenPostLink = { post ->
            post.url?.let { url ->
                openUrl(url.toString())
                model.linkOpened("posts/${post.postId}")
            }
        },
        onOpenAd = { ad ->
            ad.url?.let { url ->
                openUrl(url.toString())
                model.linkOpened("ads/${ad.adId}")
            }
        },
        onOpenComments = { commentsPost = it },
        onDismissError = model::dismissError,
    )

    commentsPost?.let { post ->
        CommentsSheet(post = post, onDismissRequest = { commentsPost = null })
    }
}

/**
 * Stateless body of CommunitiesView: large "Communities" title with Close and
 * the "Discover communities" button, then a plain list — the joined rail (or,
 * with nothing joined, "Communities to discover") and the feed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommunitiesContent(
    state: CommunitiesUiState,
    onClose: () -> Unit,
    onOpenDirectory: () -> Unit,
    onOpenCommunity: (cid: String, preview: CommunityProfile?) -> Unit,
    onRefresh: () -> Unit,
    onVote: (post: CommunityPostCard, optionId: String) -> Unit,
    onRsvp: (post: CommunityPostCard, going: Boolean) -> Unit,
    onOpenPostLink: (CommunityPostCard) -> Unit,
    onOpenAd: (AdCard) -> Unit,
    onOpenComments: (CommunityPostCard) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DrokpoTheme.colors
    val listState = rememberLazyListState()
    val topBarState = rememberTopAppBarState()
    // iOS large titles never collapse over content that doesn't scroll; a
    // partly collapsed bar still takes drags so it can always re-expand.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = topBarState,
        canScroll = { listState.canScrollForward || listState.canScrollBackward || topBarState.heightOffset < 0f },
    )
    Scaffold(
        modifier = modifier,
        topBar = {
            DrokpoTopBar(
                title = "Communities",
                navIcon = NavIcon.Close,
                onNavIcon = onClose,
                large = true,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = onOpenDirectory) {
                        Icon(Icons.Outlined.AddCircle, contentDescription = "Discover communities")
                    }
                },
            )
        },
        containerColor = colors.background,
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            LazyColumn(
                // The bar's connection sits *inside* the pull-to-refresh box: the
                // list's post-scroll reaches it first, so dragging back to the top
                // re-expands the large title before the refresh indicator engages
                // (and pushing a pulled indicator up retracts it before collapsing).
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                state = listState,
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp),
            ) {
                if (state.showsDiscover) {
                    discoverSection(state.discover, onOpenCommunity)
                } else if (state.showsRail) {
                    item(key = "rail") { JoinedRail(state.mine, onOpenCommunity) }
                }
                feedSection(
                    state = state,
                    onOpenCommunity = onOpenCommunity,
                    onVote = onVote,
                    onRsvp = onRsvp,
                    onOpenPostLink = onOpenPostLink,
                    onOpenAd = onOpenAd,
                    onOpenComments = onOpenComments,
                )
            }
            if (state.showsSpinner) LoadingState()
        }
    }
    ErrorAlert(message = state.errorMessage, onDismiss = onDismissError)
}

/** Shown instead of the rail while the member hasn't joined anything. */
private fun LazyListScope.discoverSection(
    discover: List<CommunityProfile>,
    onOpenCommunity: (cid: String, preview: CommunityProfile?) -> Unit,
) {
    item(key = "discover-header") { PlainSectionHeader("Communities to discover") }
    item(key = "discover-row") {
        if (discover.isEmpty()) {
            PlainRowText("No communities to discover yet.")
        } else {
            LazyRow(
                modifier = Modifier.padding(vertical = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(discover.distinctBy { it.id }, key = { it.id }) { community ->
                    DiscoverCommunityCard(
                        community = community,
                        modifier = Modifier.clickable(role = Role.Button) { onOpenCommunity(community.id, community) },
                    )
                }
            }
        }
    }
    item(key = "discover-footer") {
        Text(
            "Join a community to see its posts in your feed here.",
            style = DrokpoTheme.typography.footnote,
            color = DrokpoTheme.colors.secondaryLabel,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
        )
    }
}

private fun LazyListScope.feedSection(
    state: CommunitiesUiState,
    onOpenCommunity: (cid: String, preview: CommunityProfile?) -> Unit,
    onVote: (post: CommunityPostCard, optionId: String) -> Unit,
    onRsvp: (post: CommunityPostCard, going: Boolean) -> Unit,
    onOpenPostLink: (CommunityPostCard) -> Unit,
    onOpenAd: (AdCard) -> Unit,
    onOpenComments: (CommunityPostCard) -> Unit,
) {
    if (state.showsFeedHeader) {
        stickyHeader(key = "feed-header") { PlainSectionHeader("From your communities") }
    }
    if (state.showsFeedEmpty) {
        item(key = "feed-empty") { PlainRowText("Posts from your communities will show up here.") }
        return
    }
    itemsIndexed(state.feed, key = { _, item -> item.id }) { index, item ->
        Column {
            if (index > 0) ListDivider()
            when (item) {
                is FeedItem.Post -> {
                    val post = item.post
                    CommunityPostContentView(
                        post = post,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        onVote = if (post.kind == "poll") ({ optionId -> onVote(post, optionId) }) else null,
                        onRsvp = if (post.kind == "event") ({ going -> onRsvp(post, going) }) else null,
                        onOpenLink = if (post.url != null) ({ onOpenPostLink(post) }) else null,
                        onOpenComments = { onOpenComments(post) },
                        onOpenCommunity = { cid -> onOpenCommunity(cid, null) },
                    )
                }
                is FeedItem.Ad -> SponsoredFeedRow(
                    ad = item.ad,
                    onOpen = { onOpenAd(item.ad) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
                // The communities feed never serves these (filtered out by `feed`).
                is FeedItem.Person, is FeedItem.News -> Unit
            }
        }
    }
}

/** A single secondary-text row of a plain list (the section empty texts). */
@Composable
private fun PlainRowText(text: String) {
    Text(
        text,
        style = DrokpoTheme.typography.subheadline,
        color = DrokpoTheme.colors.secondaryLabel,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** Joined communities as a horizontal avatar rail (YouTube-style). */
@Composable
private fun JoinedRail(
    mine: List<CommunityProfile>,
    onOpenCommunity: (cid: String, preview: CommunityProfile?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.padding(vertical = 6.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(mine.distinctBy { it.id }, key = { it.id }) { community ->
            JoinedCommunityAvatar(
                community = community,
                modifier = Modifier.clickable(role = Role.Button) { onOpenCommunity(community.id, community) },
            )
        }
    }
}

/**
 * A joined community in the rail: circular logo with the name underneath,
 * like a YouTube subscription avatar.
 */
@Composable
internal fun JoinedCommunityAvatar(community: CommunityProfile, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.width(72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Decorative: the name underneath already labels the avatar.
        RemotePhotoView(
            photo = community.photos?.firstOrNull(),
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape),
        )
        Text(
            community.name ?: "—",
            style = DrokpoTheme.typography.caption,
            color = DrokpoTheme.colors.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(72.dp),
        )
    }
}

/** A suggested community in the "Communities to discover" strip. */
@Composable
internal fun DiscoverCommunityCard(community: CommunityProfile, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.width(120.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Decorative: the name underneath already labels the card.
        RemotePhotoView(
            photo = community.photos?.firstOrNull(),
            modifier = Modifier
                .size(width = 120.dp, height = 90.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Text(
            community.name ?: "Community",
            style = DrokpoTheme.typography.subheadline.bold(),
            color = DrokpoTheme.colors.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            memberCountLabel(community.memberCount),
            style = DrokpoTheme.typography.caption2,
            color = DrokpoTheme.colors.secondaryLabel,
        )
    }
}

/** A sponsored card inside the communities feed. */
@Composable
internal fun SponsoredFeedRow(ad: AdCard, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sponsored", style = typography.caption2.bold(), color = colors.secondaryLabel)
        ad.displayPhotos.firstOrNull()?.let { photo ->
            PhotoBand(
                photo = photo,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
        Text(ad.title ?: "—", style = typography.headline, color = colors.label)
        ad.body?.takeIf { it.isNotEmpty() }?.let { body ->
            Text(
                body,
                style = typography.subheadline,
                color = colors.secondaryLabel,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ProminentButton(text = ad.ctaLabel?.takeIf { it.isNotEmpty() } ?: "Learn more", onClick = onOpen)
    }
}
