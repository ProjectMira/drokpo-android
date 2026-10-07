package app.drokpo.android.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.drokpo.android.MainTab
import app.drokpo.android.MainTabsScaffold
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.Photo
import app.drokpo.android.features.feed.AdCardView
import app.drokpo.android.features.feed.CardView
import app.drokpo.android.features.feed.CommunityPostCardView
import app.drokpo.android.features.feed.DeckItem
import app.drokpo.android.features.feed.FeedActions
import app.drokpo.android.features.feed.FeedContent
import app.drokpo.android.features.feed.FeedState
import app.drokpo.android.features.feed.MatchOverlay
import app.drokpo.android.features.feed.NewsCardView
import app.drokpo.android.features.feed.SafetyPrompt
import app.drokpo.android.features.feed.SwipeActionButtons
import app.drokpo.android.features.feed.SwipeActionButtonsDefaults
import app.drokpo.android.label
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.visibleTabs

// Group 4 (feed): the Discover tab rendered through FeedContent + fixtures (CONTRACT §E) — every
// card kind on top of the deck, mid-drag stamps, loading / empty / error, the match overlay, the
// safety sheets, the pending-verification banner, and an interactive deck to try the gestures.
// The real FeedScreen loads from the API, so it isn't rendered here.

private val profile = DeckItem.Profile(Fixtures.feedCard)
private val behind = Fixtures.feedCards.drop(1).map { DeckItem.Profile(it) }

/**
 * A person whose photos can't load (unroutable URLs): the broken-image
 * placeholder's tap-to-retry must not swallow the page / expand tap zones.
 */
private val brokenPhotosProfile = DeckItem.Profile(
    Fixtures.feedCard.copy(
        uid = "u-broken-photos",
        photos = (0..2).map { Photo(storagePath = "fixtures/broken/$it.jpg", order = it, url = "https://invalid.invalid/$it.jpg") },
    ),
)

/** [top] with two other cards stacked behind it. */
private fun deckWith(top: DeckItem): List<DeckItem> = listOf(top) + behind.take(2)

/** A realistic server-ordered deck: people with content mixed in. */
private val mixedDeck: List<DeckItem> = listOf(
    profile,
    behind[0],
    DeckItem.News(Fixtures.news),
    behind[1],
    DeckItem.Ad(Fixtures.ad),
    behind[2],
    DeckItem.Post(Fixtures.eventPost),
    behind[3],
    DeckItem.Post(Fixtures.pollPost),
    DeckItem.Profile(Fixtures.communityCard),
    DeckItem.News(Fixtures.newsNoImage),
    DeckItem.Post(Fixtures.linkPost),
    DeckItem.Ad(Fixtures.adNoImage),
    DeckItem.Profile(Fixtures.feedCardMinimal),
    DeckItem.Post(Fixtures.announcementPost),
)

@Composable
private fun Feed(
    state: FeedState,
    showPendingBanner: Boolean = false,
    initialSafetyPrompt: SafetyPrompt? = null,
    previewDrag: DpOffset? = null,
) {
    FeedContent(
        state = state,
        actions = FeedActions(),
        showPendingBanner = showPendingBanner,
        initialSafetyPrompt = initialSafetyPrompt,
        previewDrag = previewDrag,
    )
}

private fun deckEntry(id: String, title: String, top: DeckItem, canUndo: Boolean = false) =
    CatalogEntry(id, title) {
        Feed(FeedState(deck = deckWith(top), lastSwipedProfile = if (canUndo) Fixtures.feedCards[1] else null))
    }

private fun dragEntry(id: String, title: String, top: DeckItem, dx: Int) =
    CatalogEntry(id, title) { Feed(FeedState(deck = deckWith(top)), previewDrag = DpOffset(dx.dp, 12.dp)) }

val feedCatalogEntries: List<CatalogEntry> = listOf(
    // Deck — each card kind on top (top-card affordances: expand chevron, CTA, arrow, hints).
    deckEntry("feed.deck.profile", "Deck — person on top (3 photos)", profile),
    deckEntry("feed.deck.profile.undo", "Deck — person, undo available", profile, canUndo = true),
    deckEntry("feed.deck.profile.minimal", "Deck — person, no photos / details", DeckItem.Profile(Fixtures.feedCardMinimal)),
    deckEntry("feed.deck.community", "Deck — community account card", DeckItem.Profile(Fixtures.communityCard)),
    deckEntry("feed.deck.ad", "Deck — sponsored (image, CTA, share disabled)", DeckItem.Ad(Fixtures.ad)),
    deckEntry("feed.deck.ad.noimage", "Deck — sponsored, no image (\"Learn more\")", DeckItem.Ad(Fixtures.adNoImage)),
    deckEntry("feed.deck.news", "Deck — news (band, arrow, hint)", DeckItem.News(Fixtures.news)),
    deckEntry("feed.deck.news.noimage", "Deck — news, no image (brand gradient)", DeckItem.News(Fixtures.newsNoImage)),
    deckEntry("feed.deck.post.announcement", "Deck — community announcement", DeckItem.Post(Fixtures.announcementPost)),
    deckEntry("feed.deck.post.link", "Deck — community link post (CTA)", DeckItem.Post(Fixtures.linkPost)),
    deckEntry("feed.deck.post.poll", "Deck — community poll (\"Tap to vote\")", DeckItem.Post(Fixtures.pollPost)),
    deckEntry("feed.deck.post.event", "Deck — event (\"Swipe right to join\")", DeckItem.Post(Fixtures.eventPost)),
    deckEntry("feed.deck.post.event.going", "Deck — event, going (\"You're going ✓\")", DeckItem.Post(Fixtures.eventPostGoing)),
    deckEntry("feed.deck.profile.brokenphotos", "Deck — person, photos fail to load (tap zones still page)", brokenPhotosProfile),
    CatalogEntry("feed.deck.single", "Deck — last card (nothing behind)") { Feed(FeedState(deck = listOf(profile))) },
    CatalogEntry("feed.deck.interactive", "Deck — interactive (swipe, buttons, undo)") { InteractiveDeck() },

    // Mid-drag: rotation, the stamp for each card kind, the next card scaling up.
    dragEntry("feed.deck.drag.like", "Dragging right — LIKE", profile, dx = 130),
    dragEntry("feed.deck.drag.pass", "Dragging left — PASS", profile, dx = -130),
    dragEntry("feed.deck.drag.visit", "Dragging an ad right — VISIT", DeckItem.Ad(Fixtures.ad), dx = 130),
    dragEntry("feed.deck.drag.save", "Dragging news right — SAVE", DeckItem.News(Fixtures.news), dx = 130),
    dragEntry("feed.deck.drag.join", "Dragging an event right — JOIN", DeckItem.Post(Fixtures.eventPost), dx = 130),
    dragEntry("feed.deck.drag.slight", "Dragging slightly (no stamp yet)", profile, dx = 30),
    dragEntry("feed.deck.drag.threshold", "Dragging right to the 110dp threshold (arc: card drops ~12dp)", profile, dx = 110),

    // Screen states.
    CatalogEntry("feed.loading", "Initial load") { Feed(FeedState(isLoading = true)) },
    CatalogEntry("feed.empty", "Empty — \"No one new right now\"") { Feed(FeedState()) },
    CatalogEntry("feed.error", "Fetch failed (alert over the empty state)") {
        Feed(FeedState())
        ErrorAlert(message = "The Internet connection appears to be offline.", onDismiss = {})
    },
    CatalogEntry("feed.error.swipe", "Swipe failed (alert over the deck)") {
        Feed(FeedState(deck = deckWith(behind[0])))
        ErrorAlert(message = "Only verified communities can like people", onDismiss = {})
    },
    CatalogEntry("feed.community.pending", "Unverified community — banner above the deck") {
        Feed(FeedState(deck = deckWith(profile)), showPendingBanner = true)
    },
    CatalogEntry("feed.community.pending.empty", "Unverified community — banner, empty deck") {
        Feed(FeedState(), showPendingBanner = true)
    },

    // Overlays and sheets.
    CatalogEntry("feed.match", "It's a match!") { Feed(FeedState(deck = deckWith(behind[0]), matchedCard = Fixtures.feedCard)) },
    CatalogEntry("feed.match.nophoto", "It's a match! — no photo, no name") {
        Feed(FeedState(deck = deckWith(behind[0]), matchedCard = FeedCard(uid = "u-anon")))
    },
    CatalogEntry("feed.match.standalone", "Match overlay alone") { MatchOverlay(card = Fixtures.feedCard, onDismiss = {}) },
    CatalogEntry("feed.safety.actions", "Profile \"…\" — Report / Block") {
        Feed(FeedState(deck = deckWith(profile)), initialSafetyPrompt = SafetyPrompt.Actions(Fixtures.feedCard))
    },
    CatalogEntry("feed.safety.reasons", "Profile \"…\" — Why are you reporting this profile?") {
        Feed(FeedState(deck = deckWith(profile)), initialSafetyPrompt = SafetyPrompt.ReportReasons(Fixtures.feedCard))
    },

    // In the shell, cards not on top, the button row.
    CatalogEntry("feed.intabs", "Discover inside the tab bar (person)") { FeedInTabs(isCommunity = false) },
    CatalogEntry("feed.intabs.community", "Discover inside the tab bar (unverified community)") { FeedInTabs(isCommunity = true) },
    CatalogEntry("feed.cards.behind", "Every card kind, not on top (no affordances)") { CardsNotOnTop() },
    CatalogEntry("feed.swipebuttons", "SwipeActionButtons — variants") { SwipeButtonsVariants() },
)

/** A deck that really changes: swipes remove cards, undo brings the last profile back, Refresh reloads. */
@Composable
private fun InteractiveDeck() {
    var deck by remember { mutableStateOf(mixedDeck) }
    var lastSwiped by remember { mutableStateOf<FeedCard?>(null) }
    var matched by remember { mutableStateOf<FeedCard?>(null) }
    FeedContent(
        state = FeedState(deck = deck, lastSwipedProfile = lastSwiped, matchedCard = matched),
        actions = FeedActions(
            onRefresh = { deck = mixedDeck },
            onSwipe = { item, liked ->
                deck = deck.filterNot { it.id == item.id }
                if (item is DeckItem.Profile) {
                    lastSwiped = item.card
                    // Pema always likes you back, so the overlay can be tried too.
                    if (liked && item.card.uid == Fixtures.feedCard.uid) {
                        matched = item.card
                        lastSwiped = null
                    }
                }
            },
            onUndo = {
                lastSwiped?.let { card ->
                    deck = listOf(DeckItem.Profile(card)) + deck
                    lastSwiped = null
                }
            },
            onReport = { card, _ -> deck = deck.filterNot { it.id == "profile-${card.uid}" } },
            onBlock = { card -> deck = deck.filterNot { it.id == "profile-${card.uid}" } },
            onDismissMatch = { matched = null },
        ),
    )
}

@Composable
private fun FeedInTabs(isCommunity: Boolean) {
    var selected by remember { mutableStateOf(MainTab.Discover) }
    MainTabsScaffold(
        tabs = visibleTabs(isCommunity),
        selected = selected,
        chatsUnread = 2,
        onSelect = { selected = it },
    ) { tab ->
        if (tab == MainTab.Discover) {
            Feed(FeedState(deck = mixedDeck), showPendingBanner = isCommunity)
        } else {
            Scaffold(topBar = { DrokpoTopBar(tab.label) }, containerColor = DrokpoTheme.colors.background) { padding ->
                Text(
                    "${tab.label} tab",
                    color = DrokpoTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(padding).padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun CardsNotOnTop() {
    val cardModifier = Modifier
        .fillMaxWidth()
        .height(560.dp)
    Scaffold(
        topBar = { DrokpoTopBar("Cards behind the top one") },
        containerColor = DrokpoTheme.colors.background,
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { CardView(card = Fixtures.feedCard, onSafetyTapped = {}, modifier = cardModifier.padding(horizontal = 16.dp)) }
            item { AdCardView(ad = Fixtures.ad, modifier = cardModifier.padding(horizontal = 16.dp)) }
            items(listOf(Fixtures.news, Fixtures.newsNoImage)) { news ->
                NewsCardView(item = news, modifier = cardModifier.padding(horizontal = 16.dp))
            }
            items(listOf(Fixtures.announcementPost, Fixtures.linkPost, Fixtures.pollPost, Fixtures.eventPostGoing)) { post ->
                CommunityPostCardView(post = post, modifier = cardModifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun SwipeButtonsVariants() {
    Scaffold(
        topBar = { DrokpoTopBar("SwipeActionButtons") },
        containerColor = DrokpoTheme.colors.secondaryBackground,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Deck: undo · pass · like · share", color = DrokpoTheme.colors.secondaryLabel)
            SwipeActionButtons(onPass = {}, onLike = {}, onUndo = {}, onShare = {})
            Text("Nothing to undo, ad on top (share disabled)", color = DrokpoTheme.colors.secondaryLabel)
            SwipeActionButtons(onPass = {}, onLike = {}, onUndo = {}, undoDisabled = true, onShare = {}, shareDisabled = true)
            Text("Profile detail (Discover context): pass · like", color = DrokpoTheme.colors.secondaryLabel)
            SwipeActionButtons(onPass = {}, onLike = {})
            Text(
                "Deck cards keep ${SwipeActionButtonsDefaults.DeckClearance.value.toInt()}dp clear at the bottom",
                style = DrokpoTheme.typography.footnote,
                color = DrokpoTheme.colors.secondaryLabel,
            )
        }
    }
}
