package app.drokpo.android.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.drokpo.android.MainTab
import app.drokpo.android.MainTabsScaffold
import app.drokpo.android.RootFailedContent
import app.drokpo.android.RootLoadingContent
import app.drokpo.android.SetupNoticeScreen
import app.drokpo.android.core.RemotePhotoView
import app.drokpo.android.label
import app.drokpo.android.ui.components.Avatar
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.visibleTabs

/** The shell (foundation): RootScreen states, the setup notice, MainTabs chrome, fixture sanity. */
val shellCatalogEntries: List<CatalogEntry> = listOf(
    CatalogEntry("shell.root.loading", "Root — Loading") { RootLoadingContent() },
    CatalogEntry("shell.root.failed", "Root — Failed") {
        RootFailedContent(
            message = "The Internet connection appears to be offline.",
            onRetry = {},
            onSignOut = {},
        )
    },
    CatalogEntry("shell.root.failed.nomessage", "Root — Failed (no lastError)") {
        RootFailedContent(message = null, onRetry = {}, onSignOut = {})
    },
    CatalogEntry("shell.setupnotice", "Setup notice (no google-services.json)") { SetupNoticeScreen() },
    CatalogEntry("shell.tabs.person", "MainTabs — person (4 tabs, 3 unread)") {
        TabsPreview(isCommunity = false, unread = 3)
    },
    CatalogEntry("shell.tabs.community", "MainTabs — community (5 tabs)") {
        TabsPreview(isCommunity = true, unread = 0)
    },
    CatalogEntry("shell.tabs.chats.badge", "MainTabs — Chats selected, 128 unread") {
        TabsPreview(isCommunity = false, unread = 128, initial = MainTab.Chats)
    },
    CatalogEntry("shell.fixtures.people", "Fixtures — people & photos") { FixturePeople() },
)

/** Tab chrome with placeholder tab content (the real tab roots reach AppGraph). Tabs are tappable. */
@Composable
private fun TabsPreview(isCommunity: Boolean, unread: Int, initial: MainTab = MainTab.Discover) {
    var selected by remember { mutableStateOf(initial) }
    MainTabsScaffold(
        tabs = visibleTabs(isCommunity),
        selected = selected,
        chatsUnread = unread,
        onSelect = { selected = it },
    ) { tab ->
        val title = if (tab == MainTab.Profile && isCommunity) "Community" else tab.label
        val large = tab in setOf(MainTab.Likes, MainTab.Chats) || (tab == MainTab.Profile && !isCommunity)
        Scaffold(
            topBar = { DrokpoTopBar(title, large = large) },
            containerColor = DrokpoTheme.colors.background,
        ) { padding ->
            Text(
                "${tab.label} tab content",
                style = DrokpoTheme.typography.subheadline,
                color = DrokpoTheme.colors.secondaryLabel,
                modifier = Modifier.padding(padding).padding(16.dp),
            )
        }
    }
}

/** Checks the remote fixture photos load (picsum) and the person fixtures read well. */
@Composable
private fun FixturePeople() {
    Scaffold(
        topBar = { DrokpoTopBar("Fixtures") },
        containerColor = DrokpoTheme.colors.background,
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(Fixtures.feedCards + Fixtures.communityCard + Fixtures.feedCardMinimal, key = { it.uid }) { card ->
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Avatar(photo = card.photos?.firstOrNull(), name = card.displayName, size = 44.dp)
                        Column {
                            Text(
                                listOfNotNull(card.displayName, card.displayAge?.toString()).joinToString(", "),
                                style = DrokpoTheme.typography.headline,
                                color = DrokpoTheme.colors.label,
                            )
                            Text(
                                listOfNotNull(card.region, card.kind).joinToString(" · "),
                                style = DrokpoTheme.typography.caption,
                                color = DrokpoTheme.colors.secondaryLabel,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        card.photos.orEmpty().forEach { photo ->
                            RemotePhotoView(
                                photo,
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(3f / 4f)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                        }
                    }
                    card.bio?.let {
                        Text(it, style = DrokpoTheme.typography.footnote, color = DrokpoTheme.colors.label)
                    }
                }
            }
        }
    }
}
