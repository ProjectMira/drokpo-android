package app.drokpo.android.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.features.shared.community.CommunityPageScreen
import app.drokpo.android.features.shared.community.CommunityPostContentView
import app.drokpo.android.features.shared.news.NewsDetailContent
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.theme.DrokpoTheme

// Group 9 (shared community + news) owns this file. The page entries render the shell's stub —
// replace them with CommunityPageContent (visitor / owner / empty / loading) + fixtures
// (CONTRACT.md §E). CommunityPostContentView and NewsDetailContent are stateless.
val sharedCommunityCatalogEntries: List<CatalogEntry> = listOf(
    CatalogEntry("sharedcommunity.page.visitor", "Community page — visitor (stub)") {
        CommunityPageScreen(cid = Fixtures.community.uid!!, onBack = {}, onOpenMembers = {}, preview = Fixtures.community)
    },
    CatalogEntry("sharedcommunity.page.owner", "Community page — owner tab root (stub)") {
        CommunityPageScreen(
            cid = Fixtures.community.uid!!,
            onBack = {},
            onOpenMembers = {},
            ownerMode = true,
            navIcon = NavIcon.None,
        )
    },
    CatalogEntry("sharedcommunity.posts", "CommunityPostContentView — every kind") {
        Scaffold(
            topBar = { DrokpoTopBar("Posts") },
            containerColor = DrokpoTheme.colors.background,
        ) { padding ->
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
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
    },
    CatalogEntry("news.detail", "News detail content") { NewsDetailPreview(Fixtures.news) },
    CatalogEntry("news.detail.noimage", "News detail content — no image, gist only") { NewsDetailPreview(Fixtures.newsNoImage) },
)

@Composable
private fun NewsDetailPreview(item: NewsCard) {
    Scaffold(
        topBar = { DrokpoTopBar("News", navIcon = NavIcon.Close) },
        containerColor = DrokpoTheme.colors.background,
    ) { padding ->
        NewsDetailContent(
            item = item,
            onReadFullStory = {},
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()),
        )
    }
}
