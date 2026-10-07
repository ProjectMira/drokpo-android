package app.drokpo.android.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.core.model.Socials
import app.drokpo.android.features.chats.ChatStore
import app.drokpo.android.features.shared.profiledetail.ProfileDetailActions
import app.drokpo.android.features.shared.profiledetail.ProfileDetailContent
import app.drokpo.android.features.shared.profiledetail.ProfileDetailContext
import app.drokpo.android.features.shared.profiledetail.ProfileDetailDialog
import app.drokpo.android.features.shared.profiledetail.ProfileDetailState
import app.drokpo.android.features.shared.sharing.ShareDestination
import app.drokpo.android.features.shared.sharing.ShareModalSheet
import app.drokpo.android.features.shared.sharing.ShareSheetContent
import app.drokpo.android.features.shared.sharing.ShareSheetState
import app.drokpo.android.features.shared.sharing.ShareableContent
import app.drokpo.android.features.shared.sharing.SharedContentUnavailable
import app.drokpo.android.features.shared.sharing.SharedLinkMessage
import app.drokpo.android.features.shared.sharing.SharedLoadingContent
import app.drokpo.android.features.shared.sharing.SharedNewsContent
import app.drokpo.android.features.shared.sharing.SharedPostContent
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.components.ProminentButton
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold

// Group 11 (sharing + profiledetail) — CONTRACT.md §E. Every entry renders a stateless *Content
// composable from fixtures (no ViewModel, network or AppGraph at render time). Sheets render their
// content full-screen. Taps that only change local UI state (Send, Report/Block menu, like back,
// poll votes, RSVP) are simulated in-place so the flows can be clicked through.
val sharingCatalogEntries: List<CatalogEntry> = listOf(
    CatalogEntry("sharing.kit", "ShareKit — links, parsing, chat cards") { ShareKitDebug() },

    // ShareSheet (iOS ShareSheetView)
    CatalogEntry("sharing.sheet.matches", "Share sheet — matches (one sending, one sent)") {
        ShareSheetEntry(
            entries = Fixtures.chatEntries,
            initial = ShareSheetState(sentMatchIds = setOf("m-karma"), sendingMatchIds = setOf("m-yangchen")),
        )
    },
    CatalogEntry("sharing.sheet.empty", "Share sheet — no matches yet") {
        ShareSheetEntry(entries = emptyList())
    },
    CatalogEntry("sharing.sheet.unnamed", "Share sheet — match without profile (\"—\", placeholder photo)") {
        ShareSheetEntry(entries = listOf(ChatStore.Entry(matchId = "m-unknown", otherUid = "u-unknown")) + Fixtures.chatEntries.take(1))
    },
    CatalogEntry("sharing.sheet.many", "Share sheet — 40 matches (lazy rows, card corners, hairlines)") {
        ShareSheetEntry(entries = manyChatEntries)
    },
    CatalogEntry("sharing.sheet.presented", "Share sheet — presented (half height, grabber on grey, animated Close)") {
        PresentedShareSheetEntry()
    },
    CatalogEntry("sharing.sheet.error", "Share sheet — send failed alert") {
        ShareSheetEntry(
            entries = Fixtures.chatEntries,
            initial = ShareSheetState(errorMessage = "Missing or insufficient permissions."),
        )
    },

    // ShareDestinationSheet (iOS ShareDestinationView + loaders)
    CatalogEntry("sharing.destination.loading", "Shared link — loading") {
        SharedLoadingContent(navIcon = NavIcon.Close, onBack = {})
    },
    CatalogEntry("sharing.destination.unavailable", "Shared link — content unavailable") {
        SharedContentUnavailable(navIcon = NavIcon.Close, onBack = {})
    },
    CatalogEntry("sharing.destination.user", "Shared link — profile (sheet root, Close)") {
        ProfileDetailEntry(card = Fixtures.feedCard, navIcon = NavIcon.Close)
    },
    CatalogEntry("sharing.destination.post.event", "Shared link — event post (RSVP, link, comments)") {
        SharedPostEntry(Fixtures.eventPost)
    },
    CatalogEntry("sharing.destination.post.poll", "Shared link — poll post, not voted") {
        SharedPostEntry(Fixtures.pollPost)
    },
    CatalogEntry("sharing.destination.post.voted", "Shared link — poll post, voted") {
        SharedPostEntry(Fixtures.pollPostVoted)
    },
    CatalogEntry("sharing.destination.post.link", "Shared link — link post") {
        SharedPostEntry(Fixtures.linkPost)
    },
    CatalogEntry("sharing.destination.post.announcement", "Shared link — announcement (no community name → \"Community post\")") {
        SharedPostEntry(Fixtures.announcementPost.copy(communityName = null))
    },
    CatalogEntry("sharing.destination.post.error", "Shared link — vote failed alert") {
        SharedPostEntry(Fixtures.pollPost, initialError = "The Internet connection appears to be offline.")
    },
    CatalogEntry("sharing.destination.news", "Shared link — news story") {
        SharedNewsContent(item = Fixtures.news, navIcon = NavIcon.Close, onBack = {}, onReadFullStory = {})
    },
    CatalogEntry("sharing.destination.news.noimage", "Shared link — news story without image") {
        SharedNewsContent(item = Fixtures.newsNoImage, navIcon = NavIcon.Close, onBack = {}, onReadFullStory = {})
    },
    CatalogEntry("sharing.destination.news.minimal", "Shared link — news story, no title/summary/source") {
        SharedNewsContent(item = NewsCard(newsId = "n-bare"), navIcon = NavIcon.Close, onBack = {}, onReadFullStory = {})
    },

    // ProfileDetailScreen (iOS ProfileDetailView)
    CatalogEntry("sharing.profiledetail.full", "Profile detail — full card (pushed)") {
        ProfileDetailEntry(card = Fixtures.feedCard)
    },
    CatalogEntry("sharing.profiledetail.partial", "Profile detail — one photo, bio only") {
        ProfileDetailEntry(card = partialCard)
    },
    CatalogEntry("sharing.profiledetail.minimal", "Profile detail — name only") {
        ProfileDetailEntry(card = Fixtures.feedCardMinimal, navIcon = NavIcon.Close)
    },
    CatalogEntry("sharing.profiledetail.unnamed", "Profile detail — no name (\"Profile\" / \"—\")") {
        ProfileDetailEntry(card = FeedCard(uid = "u-unnamed"))
    },
    CatalogEntry("sharing.profiledetail.community", "Profile detail — community card (shares the community)") {
        ProfileDetailEntry(card = Fixtures.communityCard)
    },
    CatalogEntry("sharing.profiledetail.preview", "Profile detail — own preview (no Report/Block)") {
        ProfileDetailEntry(
            card = Fixtures.profile.asFeedCard,
            navIcon = NavIcon.Close,
            title = "Preview",
            isSelf = true,
        )
    },
    CatalogEntry("sharing.profiledetail.discover", "Profile detail — Discover (pass / like bar)") {
        ProfileDetailEntry(
            card = Fixtures.feedCard,
            context = ProfileDetailContext.Discover(onLike = {}, onPass = {}),
            navIcon = NavIcon.Close,
        )
    },
    CatalogEntry("sharing.profiledetail.likedyou", "Profile detail — Liked you (Like back)") {
        ProfileDetailEntry(card = Fixtures.feedCard, context = likedYou)
    },
    CatalogEntry("sharing.profiledetail.likedyou.liking", "Profile detail — Like back in flight (disabled)") {
        ProfileDetailEntry(card = Fixtures.feedCard, context = likedYou, state = ProfileDetailState(isLiking = true))
    },
    CatalogEntry("sharing.profiledetail.likedyou.matched", "Profile detail — matched (Send message)") {
        ProfileDetailEntry(card = Fixtures.feedCard, context = likedYou, state = ProfileDetailState(localMatchId = "m-pema"))
    },
    CatalogEntry("sharing.profiledetail.matchalert", "Profile detail — \"It's a match!\" alert") {
        ProfileDetailEntry(
            card = Fixtures.feedCard,
            context = likedYou,
            state = ProfileDetailState(localMatchId = "m-pema", showMatchAlert = true),
        )
    },
    CatalogEntry("sharing.profiledetail.menu", "Profile detail — Report / Block menu open") {
        ProfileDetailEntry(card = Fixtures.feedCard, dialog = ProfileDetailDialog.Menu)
    },
    CatalogEntry("sharing.profiledetail.report", "Profile detail — report reasons") {
        ProfileDetailEntry(card = Fixtures.feedCard, dialog = ProfileDetailDialog.ReportReasons)
    },
    CatalogEntry("sharing.profiledetail.block", "Profile detail — block confirmation") {
        ProfileDetailEntry(card = Fixtures.feedCard, dialog = ProfileDetailDialog.BlockConfirm)
    },
    CatalogEntry("sharing.profiledetail.block.unnamed", "Profile detail — block confirmation, no name") {
        ProfileDetailEntry(card = FeedCard(uid = "u-unnamed"), dialog = ProfileDetailDialog.BlockConfirm)
    },
    CatalogEntry("sharing.profiledetail.error", "Profile detail — report failed alert") {
        ProfileDetailEntry(
            card = Fixtures.feedCard,
            state = ProfileDetailState(errorMessage = "The Internet connection appears to be offline."),
        )
    },
)

// ------------------------------------------------------------------ private fixtures

/** One photo (no page dots), bio and region only — the partially filled profile. */
private val partialCard = FeedCard(
    uid = "u-dawa",
    displayName = "Dawa Tsering",
    age = 34,
    region = "India",
    bio = "Teacher in Bylakuppe. Ask me about the best thukpa in town.",
    socials = Socials(instagram = ""),
    photos = listOf(Fixtures.photo("dawa")),
    answers = mapOf("teaChoice" to "   ", "retiredQuestion" to "Hidden — not in the vocabulary"),
)

private val likedYou = ProfileDetailContext.LikedYou(onLikeBack = { null })

/** Enough matches to scroll: the fixture entries repeated under unique match ids. */
private val manyChatEntries: List<ChatStore.Entry> = (1..40).map { i ->
    Fixtures.chatEntries[i % Fixtures.chatEntries.size].copy(matchId = "m-many-$i")
}

// ------------------------------------------------------------------ interactive wrappers

@Composable
private fun ShareSheetEntry(
    entries: List<ChatStore.Entry>,
    initial: ShareSheetState = ShareSheetState(),
    onClose: () -> Unit = {},
) {
    var state by remember { mutableStateOf(initial) }
    ShareSheetContent(
        entries = entries,
        state = state,
        onClose = onClose,
        onSend = { entry -> state = state.copy(sentMatchIds = state.sentMatchIds + entry.matchId) },
        onShareOutside = {},
        onDismissError = { state = state.copy(errorMessage = null) },
    )
}

/**
 * The real sheet host ShareSheet uses (fixture rows, no ShareModel): opens at half height with the
 * grabber on the grouped background; Close (X) slides it down; the button brings it back.
 */
@Composable
private fun PresentedShareSheetEntry() {
    var shown by remember { mutableStateOf(true) }
    Box(
        Modifier
            .fillMaxSize()
            .background(DrokpoTheme.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        ProminentButton(text = "Show share sheet", onClick = { shown = true })
    }
    if (shown) {
        ShareModalSheet(
            onDismissRequest = { shown = false },
            skipPartiallyExpanded = false,
            containerColor = DrokpoTheme.colors.groupedBackground,
        ) { close ->
            ShareSheetEntry(entries = Fixtures.chatEntries, onClose = close)
        }
    }
}

@Composable
private fun SharedPostEntry(initial: CommunityPostCard, initialError: String? = null) {
    var post by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf(initialError) }
    SharedPostContent(
        post = post,
        navIcon = NavIcon.Close,
        onBack = {},
        onVote = if (post.kind == "poll") {
            { optionId ->
                post = post.copy(
                    myVote = optionId,
                    poll = post.poll?.let { poll ->
                        val counts = poll.counts.toMutableMap()
                        post.myVote?.let { previous -> counts[previous] = ((counts[previous] ?: 1) - 1).coerceAtLeast(0) }
                        counts[optionId] = (counts[optionId] ?: 0) + 1
                        poll.copy(counts = counts)
                    },
                )
            }
        } else {
            null
        },
        onRsvp = if (post.kind == "event") {
            { going ->
                val delta = if (going == (post.myRsvp == true)) 0 else if (going) 1 else -1
                post = post.copy(myRsvp = going, attendeeCount = (post.attendeeCount ?: 0) + delta)
            }
        } else {
            null
        },
        onOpenLink = post.url?.let { { } },
        onOpenComments = {},
        onOpenCommunity = {},
        errorMessage = error,
        onDismissError = { error = null },
    )
}

@Composable
private fun ProfileDetailEntry(
    card: FeedCard,
    context: ProfileDetailContext = ProfileDetailContext.Plain,
    state: ProfileDetailState = ProfileDetailState(),
    dialog: ProfileDetailDialog = ProfileDetailDialog.None,
    navIcon: NavIcon = NavIcon.Back,
    title: String? = null,
    isSelf: Boolean = false,
) {
    var currentState by remember { mutableStateOf(state) }
    var currentDialog by remember { mutableStateOf(dialog) }
    ProfileDetailContent(
        card = card,
        title = title ?: card.displayName ?: "Profile",
        navIcon = navIcon,
        isSelf = isSelf,
        context = context,
        state = currentState,
        dialog = currentDialog,
        onDialogChange = { currentDialog = it },
        actions = ProfileDetailActions(
            onLikeBack = { currentState = currentState.copy(localMatchId = "m-pema", showMatchAlert = true) },
            onDismissMatchAlert = { currentState = currentState.copy(showMatchAlert = false) },
            onDismissError = { currentState = currentState.copy(errorMessage = null) },
        ),
    )
}

// ------------------------------------------------------------------ ShareKit debug page

private val shareables: List<ShareableContent> = listOf(
    ShareableContent.Profile(Fixtures.feedCard),
    ShareableContent.Profile(Fixtures.feedCardMinimal.copy(displayName = null)),
    ShareableContent.Profile(Fixtures.communityCard),
    ShareableContent.Community(Fixtures.community.uid!!, Fixtures.community.name),
    ShareableContent.Community("c-unnamed", null),
    ShareableContent.Post(Fixtures.eventPost),
    ShareableContent.News(Fixtures.news),
)

private val incomingLinks: List<String> = listOf(
    "drokpo://s/user/u-pema",
    "drokpo://s/community/c-tat",
    "https://drokpo-backend.web.app/s/post/p-event",
    "https://drokpo-backend.web.app/s/news/n-losar-toronto?utm_source=whatsapp",
    "https://drokpo-backend.web.app/s/user/",
    "drokpo://s/unknown/x",
    "https://example.org/s/user/u-pema",
    "not a url",
)

@Composable
private fun ShareKitDebug() {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Scaffold(
        topBar = { DrokpoTopBar("ShareKit") },
        containerColor = colors.background,
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Outgoing (ShareableContent)", style = typography.headline, color = colors.label) }
            // Index keys: a community card shared as a Profile and the same community shared as a
            // Community have the same id ("community-c-tat") and title, and LazyColumn rejects
            // duplicate keys.
            itemsIndexed(shareables, key = { index, _ -> "out-$index" }) { _, content ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(content.id, style = typography.caption.bold(), color = colors.secondaryLabel)
                    Text(content.messageText, style = typography.footnote, color = colors.label)
                    val roundTrip = ShareDestination.parse(content.webUrl)?.id ?: "— no parse —"
                    Text("parse(webUrl) → $roundTrip", style = typography.caption, color = colors.secondaryLabel)
                    SharedLinkMessage.from(content.messageText)?.let { SharedLinkCard(it) }
                    HorizontalDivider(Modifier.padding(top = 8.dp), color = colors.separator)
                }
            }
            item { Text("Incoming (ShareDestination.parse)", style = typography.headline, color = colors.label) }
            items(incomingLinks, key = { "in-$it" }) { link ->
                Column {
                    Text(link, style = typography.footnote, color = colors.label)
                    Text(
                        "→ ${ShareDestination.parse(link)?.id ?: "null"}",
                        style = typography.caption,
                        color = colors.secondaryLabel,
                    )
                }
            }
        }
    }
}

/** Roughly the chat bubble's shared-link card (group 6 owns the real one). */
@Composable
private fun SharedLinkCard(message: SharedLinkMessage) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(message.icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
            Text("Shared ${message.kindLabel}", style = typography.caption.bold(), color = colors.accent)
        }
        message.caption?.let { Text(it, style = typography.body.bold(), color = colors.label) }
        Text("Tap to view", style = typography.caption2, color = colors.secondaryLabel)
    }
}
