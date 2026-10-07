package app.drokpo.android.features.shared.community

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Pending
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.RemotePhotoView
import app.drokpo.android.core.SessionState
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.CommunityProfile
import app.drokpo.android.core.model.Vocabulary
import app.drokpo.android.features.communityhome.CommunityPostComposerSheet
import app.drokpo.android.features.communityhome.PendingVerificationBanner
import app.drokpo.android.features.shared.sharing.ShareButton
import app.drokpo.android.features.shared.sharing.ShareableContent
import app.drokpo.android.ui.components.ActionSheet
import app.drokpo.android.ui.components.ActionSheetItem
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.EmptyState
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.LoadingState
import app.drokpo.android.ui.components.LocalInsideSheet
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.components.ProminentButton
import app.drokpo.android.ui.components.SecondaryButton
import app.drokpo.android.ui.components.Spinner
import app.drokpo.android.ui.components.rememberInAppBrowser
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold

/**
 * One Instagram-profile-style page for a community: header (logo, name, verified seal, member
 * count, description, website, Join for visiting persons) above a 3-column grid of its posts. The
 * owning community sees the same page in [ownerMode] — a "New post" affordance, its own unpublished
 * posts (badged, dimmed), and a publish/unpublish toggle in the tapped-post sheet. This is the
 * single community page: it replaced the old CommunityDetailView (visitor) and the
 * CommunityPostsView list (owner). Port of CommunityPageView (CONTRACT §B.9, §F.9).
 *
 * Registered by `sharedDestinations`; also the community account's tab root via
 * `SharedNavHost(ownerMode = true)` (navIcon None). [onBack] = the leading button AND iOS
 * `dismiss()` after blocking. [preview] = directory/rail card data shown immediately while the
 * fuller detail fetch is in flight — avoids a blank header on push. Ignored in owner mode (the
 * header reads live from the session instead).
 */
@Composable
fun CommunityPageScreen(
    cid: String,
    onBack: () -> Unit,
    onOpenMembers: (cid: String) -> Unit,
    modifier: Modifier = Modifier,
    preview: CommunityProfile? = null,
    ownerMode: Boolean = false,
    navIcon: NavIcon = NavIcon.Back,
) {
    val model: CommunityPageModel = viewModel(key = "community-page:$cid:$ownerMode") {
        CommunityPageModel(cid = cid, ownerMode = ownerMode, preview = preview)
    }
    val ui by model.state.collectAsStateWithLifecycle()
    val sessionState by AppGraph.session.state.collectAsStateWithLifecycle()
    val myCommunity by AppGraph.session.myCommunity.collectAsStateWithLifecycle()
    val openUrl = rememberInAppBrowser()

    var showComposer by rememberSaveable { mutableStateOf(false) }
    var showReportReasons by rememberSaveable { mutableStateOf(false) }
    var showBlockConfirm by rememberSaveable { mutableStateOf(false) }

    // The community whose header/verification this page reflects — the session's live copy in
    // owner mode (GET /communities/{cid} 404s for an unverified community, since it isn't
    // publicly listed yet), the fetched visitor snapshot otherwise.
    val community = if (ownerMode) myCommunity else ui.visitorCommunity

    val currentOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(model) {
        model.events.collect { event ->
            when (event) {
                CommunityPageEvent.Dismiss -> currentOnBack()
            }
        }
    }

    CommunityPageContent(
        state = CommunityPageState(
            cid = cid,
            community = community,
            ownerMode = ownerMode,
            isCommunityAccount = sessionState == SessionState.ActiveCommunity,
            posts = ui.posts,
            isLoadingHeader = ui.isLoadingHeader,
            isLoadingPosts = ui.isLoadingPosts,
            isLoadingMore = ui.isLoadingMore,
            isJoining = ui.isJoining,
            isRefreshing = ui.isRefreshing,
        ),
        navIcon = navIcon,
        onNavIcon = onBack,
        onNewPost = { showComposer = true },
        onReport = { showReportReasons = true },
        onBlock = { showBlockConfirm = true },
        onToggleJoin = model::toggleJoin,
        onOpenWebsite = openUrl,
        onOpenMembers = { onOpenMembers(cid) },
        onRefresh = model::refresh,
        onSelectPost = model::selectPost,
        onTileAppear = model::loadMoreIfNeeded,
        modifier = modifier,
    )

    if (showReportReasons) {
        ActionSheet(
            onDismissRequest = { showReportReasons = false },
            title = "Why are you reporting this community?",
            items = Vocabulary.reportReasons.map { reason ->
                ActionSheetItem(reason, destructive = true) { model.report(reason) }
            },
        )
    }
    if (showBlockConfirm) {
        ActionSheet(
            onDismissRequest = { showBlockConfirm = false },
            title = "Block ${community?.name ?: "this community"}?",
            message = "You won't see this community or its posts.",
            items = listOf(ActionSheetItem("Block", destructive = true) { model.block() }),
        )
    }
    if (showComposer) {
        CommunityPostComposerSheet(
            onSaved = { model.reloadAndWait() },
            onDismissRequest = { showComposer = false },
        )
    }
    ui.selectedPost?.let { post ->
        CommunityPostDetailSheet(
            post = post,
            onVote = if (post.kind == "poll") {
                { optionId -> model.vote(post.postId, optionId) }
            } else {
                null
            },
            onRsvp = if (post.kind == "event") {
                { going -> model.rsvp(post.postId, going) }
            } else {
                null
            },
            // iOS parks the URL until the sheet's onDismiss; here the sheet closes and the Custom
            // Tab opens over it straight away (CONTRACT §C.3 #5).
            onOpenLink = post.url?.let { url ->
                {
                    model.dismissPost()
                    openUrl(url.toString())
                }
            },
            onDismissRequest = model::dismissPost,
            ownerMode = ownerMode,
            onTogglePublish = if (ownerMode) {
                { model.togglePublish(post) }
            } else {
                null
            },
        )
    }
    // Composed after the sheets so its window stacks on top of an open post sheet.
    ErrorAlert(message = ui.errorMessage, onDismiss = model::dismissError)
}

/** What [CommunityPageContent] renders — plain data, no network. */
@Immutable
internal data class CommunityPageState(
    val cid: String,
    /** Owner: session.myCommunity; visitor: preview, then the fetched detail. */
    val community: CommunityProfile?,
    val ownerMode: Boolean,
    /** The viewer is a community account (session state ActiveCommunity) — no Join button. */
    val isCommunityAccount: Boolean,
    val posts: List<CommunityPostCard> = emptyList(),
    val isLoadingHeader: Boolean = false,
    val isLoadingPosts: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isJoining: Boolean = false,
    val isRefreshing: Boolean = false,
) {
    val isVerified: Boolean get() = community?.isVerified ?: false
}

@Composable
internal fun CommunityPageContent(
    state: CommunityPageState,
    navIcon: NavIcon,
    onNavIcon: () -> Unit,
    onNewPost: () -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
    onToggleJoin: () -> Unit,
    onOpenWebsite: (url: String) -> Unit,
    onOpenMembers: () -> Unit,
    onRefresh: () -> Unit,
    onSelectPost: (CommunityPostCard) -> Unit,
    onTileAppear: (CommunityPostCard) -> Unit,
    modifier: Modifier = Modifier,
    initialMenuExpanded: Boolean = false,
) {
    val colors = DrokpoTheme.colors
    val community = state.community
    // Inside a sheet (CommunityPostDetailSheet / ShareDestinationSheet) the ModalBottomSheet
    // already pads for the system bars.
    val contentInsets = if (LocalInsideSheet.current) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets
    Scaffold(
        modifier = modifier,
        topBar = {
            DrokpoTopBar(
                title = community?.name ?: "Community",
                navIcon = navIcon,
                onNavIcon = onNavIcon,
                actions = {
                    ShareButton(ShareableContent.Community(cid = state.cid, name = community?.name))
                    if (state.ownerMode) {
                        IconButton(onClick = onNewPost) {
                            Icon(Icons.Filled.Add, contentDescription = "Add")
                        }
                    } else {
                        ReportOrBlockMenu(
                            initiallyExpanded = initialMenuExpanded,
                            onReport = onReport,
                            onBlock = onBlock,
                        )
                    }
                },
            )
        },
        containerColor = colors.background,
        contentWindowInsets = contentInsets,
    ) { padding ->
        // Edge-to-edge: the grid scrolls under the navigation bar (bottom inset as content padding).
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .padding(top = padding.calculateTopPadding())
                .fillMaxSize(),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                fullWidthItem("header") {
                    Column {
                        CommunityHeader(
                            state = state,
                            onNewPost = onNewPost,
                            onToggleJoin = onToggleJoin,
                            onOpenWebsite = onOpenWebsite,
                            onOpenMembers = onOpenMembers,
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 14.dp),
                        )
                        if (state.ownerMode && !state.isVerified) {
                            PendingVerificationBanner(Modifier.padding(bottom = 10.dp))
                        }
                    }
                }
                postsGrid(state, onSelectPost, onTileAppear)
            }
            if (state.isLoadingHeader && community == null) {
                LoadingState()
            }
        }
    }
}

private fun LazyGridScope.fullWidthItem(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = key) { content() }
}

private fun LazyGridScope.postsGrid(
    state: CommunityPageState,
    onSelectPost: (CommunityPostCard) -> Unit,
    onTileAppear: (CommunityPostCard) -> Unit,
) {
    if (state.posts.isEmpty() && !state.isLoadingPosts) {
        fullWidthItem("empty") {
            EmptyState(
                icon = Icons.Outlined.GridOn,
                title = "No posts yet",
                message = if (state.ownerMode) "Share an announcement, link, poll, or event." else "Check back soon.",
                modifier = Modifier.padding(top = 40.dp),
            )
        }
    } else {
        items(state.posts, key = { it.postId }, contentType = { "tile" }) { post ->
            PostTile(
                post = post,
                showUnpublishedBadge = state.ownerMode,
                onClick = { onSelectPost(post) },
            )
            // iOS `.onAppear { loadMoreIfNeeded(currentPost: post) }`.
            LaunchedEffect(post.postId) { onTileAppear(post) }
        }
        if (state.isLoadingMore) {
            fullWidthItem("loading-more") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Spinner()
                }
            }
        }
    }
}

/** Visitor overflow (iOS `Menu` on `ellipsis.circle`, a11y "Report or block"). */
@Composable
private fun ReportOrBlockMenu(initiallyExpanded: Boolean, onReport: () -> Unit, onBlock: () -> Unit) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val destructive = DrokpoTheme.colors.destructive
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.Pending, contentDescription = "Report or block")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Report", color = destructive) },
                onClick = {
                    expanded = false
                    onReport()
                },
            )
            DropdownMenuItem(
                text = { Text("Block", color = destructive) },
                onClick = {
                    expanded = false
                    onBlock()
                },
            )
        }
    }
}

// MARK: Header

@Composable
private fun CommunityHeader(
    state: CommunityPageState,
    onNewPost: () -> Unit,
    onToggleJoin: () -> Unit,
    onOpenWebsite: (url: String) -> Unit,
    onOpenMembers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    val community = state.community
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RemotePhotoView(
                photo = community?.photos?.firstOrNull(),
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        community?.name ?: "Community",
                        style = typography.title3.bold(),
                        color = colors.label,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (community?.isVerified == true) {
                        Icon(
                            Icons.Filled.Verified,
                            contentDescription = "Verified",
                            tint = colors.accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                MemberCountRow(state, onOpenMembers)
            }
        }

        community?.description?.takeIf { it.isNotEmpty() }?.let { description ->
            Text(description, style = typography.subheadline, color = colors.label)
        }

        val action = pageAction(state.ownerMode, state.isCommunityAccount)
        val website = websiteUrl(community)
        if (action != PageAction.None || website != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (action) {
                    PageAction.NewPost -> ProminentButton(text = "New post", onClick = onNewPost, icon = Icons.Filled.AddCircle)
                    PageAction.Join -> JoinButton(joined = community?.joined == true, isJoining = state.isJoining, onClick = onToggleJoin)
                    PageAction.None -> Unit
                }
                if (website != null) {
                    SecondaryButton(text = "Website", onClick = { onOpenWebsite(website) }, icon = Icons.Filled.Language)
                }
            }
        }
    }
}

/**
 * A count-only label for non-members (member lists are members-only — see docs/COMMUNITIES.md),
 * or a link into the member list for the owner or a joined visitor.
 */
@Composable
private fun MemberCountRow(state: CommunityPageState, onOpenMembers: () -> Unit) {
    val colors = DrokpoTheme.colors
    val label = memberCountLabel(state.community?.memberCount ?: 0)
    if (showsMembersLink(state.ownerMode, state.community)) {
        // iOS: the `.secondary` label inside a NavigationLink, i.e. the tint at secondary emphasis.
        Text(
            label,
            style = DrokpoTheme.typography.subheadline,
            color = colors.accent.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(role = Role.Button, onClick = onOpenMembers),
        )
    } else {
        Text(label, style = DrokpoTheme.typography.subheadline, color = colors.secondaryLabel, maxLines = 1)
    }
}

@Composable
private fun JoinButton(joined: Boolean, isJoining: Boolean, onClick: () -> Unit) {
    if (joined) {
        // iOS `.disabled(isJoining)`: grey fill + grey spinner while the request is in flight.
        SecondaryButton(text = "Joined", onClick = onClick, enabled = !isJoining, loading = isJoining)
    } else {
        ProminentButton(text = "Join", onClick = onClick, enabled = !isJoining, loading = isJoining)
    }
}
