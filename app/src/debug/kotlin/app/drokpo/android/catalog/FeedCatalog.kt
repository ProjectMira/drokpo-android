package app.drokpo.android.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.drokpo.android.features.feed.FeedScreen
import app.drokpo.android.features.feed.SwipeActionButtons
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.theme.DrokpoTheme

// Group 4 (feed) owns this file. The screen entry renders the shell's stub — replace it with
// FeedContent states (deck per card kind, empty, loading, match overlay…) + fixtures
// (CONTRACT.md §E): the real FeedScreen loads from the API.
val feedCatalogEntries: List<CatalogEntry> = listOf(
    CatalogEntry("feed.screen", "Discover (stub)") { FeedScreen() },
    CatalogEntry("feed.swipebuttons", "SwipeActionButtons — variants") {
        Scaffold(
            topBar = { DrokpoTopBar("SwipeActionButtons") },
            containerColor = DrokpoTheme.colors.background,
        ) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Deck: undo · pass · like · share", color = DrokpoTheme.colors.secondaryLabel)
                SwipeActionButtons(onPass = {}, onLike = {}, onUndo = {}, onShare = {})
                Text("Nothing to undo, ad on top (share disabled)", color = DrokpoTheme.colors.secondaryLabel)
                SwipeActionButtons(onPass = {}, onLike = {}, onUndo = {}, undoDisabled = true, onShare = {}, shareDisabled = true)
                Text("Profile detail (Discover context): pass · like", color = DrokpoTheme.colors.secondaryLabel)
                SwipeActionButtons(onPass = {}, onLike = {})
            }
        }
    },
)
