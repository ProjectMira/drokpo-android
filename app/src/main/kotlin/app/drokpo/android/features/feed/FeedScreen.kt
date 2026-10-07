package app.drokpo.android.features.feed

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.RemotePhotoView
import app.drokpo.android.core.SessionState
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.core.model.Vocabulary
import app.drokpo.android.features.communities.CommunitiesScreen
import app.drokpo.android.features.communities.CommunityDirectoryCoverScreen
import app.drokpo.android.features.communityhome.PendingVerificationBanner
import app.drokpo.android.features.shared.community.CommunityPostDetailSheet
import app.drokpo.android.features.shared.news.NewsDetailSheet
import app.drokpo.android.features.shared.profiledetail.ProfileDetailContext
import app.drokpo.android.features.shared.profiledetail.ProfileDetailScreen
import app.drokpo.android.features.shared.sharing.ShareSheet
import app.drokpo.android.features.shared.sharing.ShareableContent
import app.drokpo.android.ui.components.ActionSheet
import app.drokpo.android.ui.components.ActionSheetItem
import app.drokpo.android.ui.components.DrokpoSheet
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.EmptyState
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.FullScreenCover
import app.drokpo.android.ui.components.LoadingState
import app.drokpo.android.ui.components.LocalSheetDismiss
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.components.ProminentButton
import app.drokpo.android.ui.components.SecondaryButton
import app.drokpo.android.ui.components.rememberInAppBrowser
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold

/**
 * Port of FeedView (Discover tab root). No NavHost needed — iOS FeedView pushes nothing; everything
 * it shows is a sheet/cover (§C.2). (CONTRACT §B.4.)
 */
@Composable
fun FeedScreen(modifier: Modifier = Modifier) {
    val model: FeedModel = viewModel { FeedModel() }
    val state by model.state.collectAsStateWithLifecycle()
    val sessionState by AppGraph.session.state.collectAsStateWithLifecycle()
    val myCommunity by AppGraph.session.myCommunity.collectAsStateWithLifecycle()
    val isCommunityAccount = sessionState == SessionState.ActiveCommunity
    val isUnverifiedCommunity = isCommunityAccount && myCommunity?.isVerified != true

    val openUrl = rememberInAppBrowser()
    val deckState = rememberDeckState()
    var expandedCard by remember { mutableStateOf<FeedCard?>(null) }
    var expandedNews by remember { mutableStateOf<NewsCard?>(null) }
    var showCommunityBrowse by remember { mutableStateOf(false) }
    /** The card being shared from the deck's share button. */
    var shareContent by remember { mutableStateOf<ShareableContent?>(null) }

    // iOS `.task { await model.loadInitial() }` — re-runs when the tab is re-entered, a no-op
    // unless the deck is empty.
    LaunchedEffect(Unit) { model.loadInitial() }

    // Ad swipes, news arrows and link CTAs open in the in-app browser. A detail sheet that opens a
    // link closes itself first (no iOS pendingURL dance: a Custom Tab isn't a sheet).
    val urlToOpen = state.urlToOpen
    LaunchedEffect(urlToOpen) {
        if (urlToOpen != null) {
            openUrl(urlToOpen)
            model.consumeUrlToOpen()
        }
    }

    val actions = remember(model, deckState) {
        FeedActions(
            onBrowseCommunities = { showCommunityBrowse = true },
            onRefresh = model::refresh,
            onSwipe = model::swipe,
            onUndo = model::undoLastSwipe,
            onShare = { shareContent = it },
            onExpandProfile = { expandedCard = it },
            onExpandNews = { expandedNews = it },
            onExpandPost = model::openPost,
            onOpenNewsSource = model::openNews,
            onOpenPostLink = model::openPostLink,
            onReport = model::reportAndRemove,
            onBlock = model::blockAndRemove,
            onDismissMatch = model::dismissMatch,
        )
    }

    FeedContent(
        state = state,
        actions = actions,
        modifier = modifier,
        showPendingBanner = isUnverifiedCommunity,
        deckState = deckState,
    )

    expandedCard?.let { card ->
        val close = { expandedCard = null }
        DrokpoSheet(onDismissRequest = close) {
            ProfileDetailScreen(
                card = card,
                // Close (X) slides the sheet down like iOS dismiss(); Like/Pass/Report/Block
                // drop it at once because the deck animates the card away right after.
                onBack = LocalSheetDismiss.current ?: close,
                context = ProfileDetailContext.Discover(
                    onLike = {
                        close()
                        deckState.swipe(DeckItem.Profile(card), liked = true)
                    },
                    onPass = {
                        close()
                        deckState.swipe(DeckItem.Profile(card), liked = false)
                    },
                ),
                // Caller-owned safety actions: the card must also leave the
                // deck, which only the model can do.
                onReport = { reason ->
                    close()
                    model.reportAndRemove(card, reason)
                },
                onBlock = {
                    close()
                    model.blockAndRemove(card)
                },
                navIcon = NavIcon.Close,
            )
        }
    }

    shareContent?.let { content ->
        ShareSheet(content = content, onDismissRequest = { shareContent = null })
    }

    expandedNews?.let { item ->
        NewsDetailSheet(
            item = item,
            onReadFullStory = {
                model.reportNewsClick(item)
                expandedNews = null
                item.link?.let(openUrl)
            },
            onDismissRequest = { expandedNews = null },
        )
    }

    state.expandedPost?.let { post ->
        val link = post.link
        CommunityPostDetailSheet(
            post = post,
            onVote = { optionId -> model.vote(post, optionId) },
            onRsvp = { going -> model.rsvp(post, going) },
            onOpenLink = link?.let {
                {
                    model.reportPostClick(post)
                    model.closePost()
                    openUrl(it)
                }
            },
            onDismissRequest = model::closePost,
            allowPartialHeight = true,
        )
    }

    if (showCommunityBrowse) {
        val close = { showCommunityBrowse = false }
        FullScreenCover(onDismissRequest = close) {
            // A person browses joined + suggested communities (the old
            // Communities tab, now reachable only from here); a community
            // account browses the directory instead — communities don't join
            // communities.
            if (isCommunityAccount) CommunityDirectoryCoverScreen(onClose = close) else CommunitiesScreen(onClose = close)
        }
    }

    // Composed after the sheets so its window stacks on top: a failed vote/RSVP
    // while the post sheet is up must surface, not silently no-op.
    ErrorAlert(message = state.errorMessage, onDismiss = model::clearError)
}

/** Everything the Discover UI can ask for; [FeedScreen] wires them to the model and its sheets. */
@Immutable
internal class FeedActions(
    val onBrowseCommunities: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onSwipe: (DeckItem, liked: Boolean) -> Unit = { _, _ -> },
    val onUndo: () -> Unit = {},
    val onShare: (ShareableContent) -> Unit = {},
    val onExpandProfile: (FeedCard) -> Unit = {},
    val onExpandNews: (NewsCard) -> Unit = {},
    val onExpandPost: (CommunityPostCard) -> Unit = {},
    val onOpenNewsSource: (NewsCard) -> Unit = {},
    val onOpenPostLink: (CommunityPostCard) -> Unit = {},
    val onReport: (FeedCard, reason: String) -> Unit = { _, _ -> },
    val onBlock: (FeedCard) -> Unit = {},
    val onDismissMatch: () -> Unit = {},
)

/** The profile card's "…" flow: Report / Block, then the report reasons. */
internal sealed interface SafetyPrompt {
    val card: FeedCard

    data class Actions(override val card: FeedCard) : SafetyPrompt

    data class ReportReasons(override val card: FeedCard) : SafetyPrompt
}

/**
 * Stateless Discover tab: "Discover" bar with the communities button, the
 * pending-verification banner, then the spinner / empty state / deck, and
 * the match overlay on top. [initialSafetyPrompt] and [previewDrag] exist
 * for the debug catalog.
 */
@Composable
internal fun FeedContent(
    state: FeedState,
    actions: FeedActions,
    modifier: Modifier = Modifier,
    showPendingBanner: Boolean = false,
    deckState: DeckState = rememberDeckState(),
    initialSafetyPrompt: SafetyPrompt? = null,
    previewDrag: DpOffset? = null,
) {
    val colors = DrokpoTheme.colors
    var safetyPrompt by remember { mutableStateOf(initialSafetyPrompt) }

    Box(modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                DrokpoTopBar(
                    title = "Discover",
                    actions = {
                        // Community browsing lives in the top bar, not floating over
                        // the cards (undo moved down into the deck's button row).
                        IconButton(onClick = actions.onBrowseCommunities) {
                            Icon(Icons.Filled.Groups, contentDescription = "Browse communities")
                        }
                    },
                )
            },
            containerColor = colors.background,
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                if (showPendingBanner) PendingVerificationBanner()
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        state.isLoading -> LoadingState()
                        // Keep the deck up while the last card is still flying off.
                        state.deck.isEmpty() && deckState.departing.isEmpty() -> FeedEmptyState(onRefresh = actions.onRefresh)
                        else -> SwipeDeck(
                            deck = state.deck,
                            deckState = deckState,
                            canUndo = state.canUndo,
                            onSwipe = actions.onSwipe,
                            onUndo = actions.onUndo,
                            onShare = actions.onShare,
                            previewDrag = previewDrag,
                        ) { item, isTop ->
                            DeckCard(
                                item = item,
                                isTop = isTop,
                                actions = actions,
                                onSafety = { safetyPrompt = SafetyPrompt.Actions(it) },
                                // An ad's CTA (and a link-less event's affordance) is the same as a right swipe.
                                onSwipeRight = { deckState.swipe(item, liked = true) },
                            )
                        }
                    }
                }
            }
        }

        MatchOverlayHost(card = state.matchedCard, onDismiss = actions.onDismissMatch)
    }

    when (val prompt = safetyPrompt) {
        is SafetyPrompt.Actions -> ActionSheet(
            onDismissRequest = { safetyPrompt = null },
            items = listOf(
                ActionSheetItem("Report", destructive = true) { safetyPrompt = SafetyPrompt.ReportReasons(prompt.card) },
                // No confirmation on the deck (iOS parity).
                ActionSheetItem("Block", destructive = true) { actions.onBlock(prompt.card) },
            ),
        )
        is SafetyPrompt.ReportReasons -> ActionSheet(
            onDismissRequest = { safetyPrompt = null },
            title = "Why are you reporting this profile?",
            items = Vocabulary.reportReasons.map { reason ->
                ActionSheetItem(reason, destructive = true) { actions.onReport(prompt.card, reason) }
            },
        )
        null -> Unit
    }
}

/** One deck card by kind; affordances (expand, CTA, arrow) only on the top card. */
@Composable
private fun DeckCard(
    item: DeckItem,
    isTop: Boolean,
    actions: FeedActions,
    onSafety: (FeedCard) -> Unit,
    onSwipeRight: () -> Unit,
) {
    when (item) {
        is DeckItem.Profile -> CardView(
            card = item.card,
            onSafetyTapped = { onSafety(item.card) },
            onExpand = if (isTop) ({ actions.onExpandProfile(item.card) }) else null,
        )
        is DeckItem.Ad -> AdCardView(
            ad = item.ad,
            onOpen = if (isTop) onSwipeRight else null,
        )
        is DeckItem.News -> NewsCardView(
            item = item.item,
            onOpen = if (isTop) ({ actions.onOpenNewsSource(item.item) }) else null,
            onExpand = if (isTop) ({ actions.onExpandNews(item.item) }) else null,
        )
        is DeckItem.Post -> {
            val post = item.post
            val hasLink = post.link != null
            CommunityPostCardView(
                post = post,
                onOpen = when {
                    !isTop -> null
                    hasLink -> ({ actions.onOpenPostLink(post) })
                    post.kind == "event" -> onSwipeRight
                    else -> null
                },
                onExpand = if (isTop) ({ actions.onExpandPost(post) }) else null,
            )
        }
    }
}

@Composable
private fun FeedEmptyState(onRefresh: () -> Unit) {
    EmptyState(
        icon = Icons.Filled.AutoAwesome,
        title = "No one new right now",
        message = "Check back later, or widen your preferences in your profile.",
        compact = true,
        action = { SecondaryButton("Refresh", onClick = onRefresh) },
    )
}

/** Fades the match overlay in and out (it keeps the last card while fading out). */
@Composable
private fun MatchOverlayHost(card: FeedCard?, onDismiss: () -> Unit) {
    var last by remember { mutableStateOf(card) }
    LaunchedEffect(card) { if (card != null) last = card }
    AnimatedVisibility(visible = card != null, enter = fadeIn(), exit = fadeOut()) {
        (card ?: last)?.let { MatchOverlay(card = it, onDismiss = onDismiss) }
    }
}

/** "It's a match!" over a dark scrim; tapping anywhere (or system back) dismisses. */
@Composable
internal fun MatchOverlay(card: FeedCard, onDismiss: () -> Unit) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.dimmingScrim)
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("It's a match!", style = typography.largeTitle.bold(), color = colors.onPhoto)
            RemotePhotoView(
                photo = card.photos?.firstOrNull(),
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape),
            )
            Text(
                "You and ${card.displayName ?: "they"} like each other.",
                style = typography.body,
                color = colors.onPhoto,
                textAlign = TextAlign.Center,
            )
            ProminentButton("Keep swiping", onClick = onDismiss)
        }
    }
}
