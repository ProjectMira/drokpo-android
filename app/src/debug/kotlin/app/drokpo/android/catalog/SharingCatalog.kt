package app.drokpo.android.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.drokpo.android.features.shared.profiledetail.ProfileDetailScreen
import app.drokpo.android.features.shared.sharing.ShareDestination
import app.drokpo.android.features.shared.sharing.ShareableContent
import app.drokpo.android.features.shared.sharing.SharedLinkMessage
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold

// Group 11 (sharing + profiledetail) owns this file. The profile-detail entries render the shell's
// stub — replace them with ProfileDetailContent (Plain / Discover / LikedYou / matched) and add
// ShareSheetContent / shared-content loader states (CONTRACT.md §E). "sharing.kit" exercises the
// pure ShareKit port and can stay.
val sharingCatalogEntries: List<CatalogEntry> = listOf(
    CatalogEntry("sharing.kit", "ShareKit — links, parsing, chat cards") { ShareKitDebug() },
    CatalogEntry("profiledetail.full", "Profile detail — full card (stub)") {
        ProfileDetailScreen(card = Fixtures.feedCard, onBack = {})
    },
    CatalogEntry("profiledetail.minimal", "Profile detail — name only (stub)") {
        ProfileDetailScreen(card = Fixtures.feedCardMinimal, onBack = {}, navIcon = NavIcon.Close)
    },
    CatalogEntry("profiledetail.preview", "Profile detail — own preview (stub)") {
        ProfileDetailScreen(card = Fixtures.profile.asFeedCard, onBack = {}, navIcon = NavIcon.Close, title = "Preview")
    },
)

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
            items(shareables, key = { "out-${it.id}-${it.title}" }) { content ->
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
