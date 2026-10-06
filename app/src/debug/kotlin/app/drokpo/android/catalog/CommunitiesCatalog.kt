package app.drokpo.android.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.drokpo.android.features.communities.CommunitiesScreen
import app.drokpo.android.features.communities.CommunityDirectoryCoverScreen
import app.drokpo.android.features.communities.CommunityMembersScreen
import app.drokpo.android.features.communities.CommunityRow
import app.drokpo.android.features.communityhome.CommunityProfileEditorScreen
import app.drokpo.android.features.communityhome.PendingVerificationBanner
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.theme.DrokpoTheme

// Group 8 (communities + communityhome) owns this file. Screen entries render the shell's stubs —
// replace them with the *Content composables + Fixtures.community / communities / members /
// communitiesHomeItems (CONTRACT.md §E). CommunityRow and PendingVerificationBanner are stateless.
val communitiesCatalogEntries: List<CatalogEntry> = listOf(
    CatalogEntry("communities.home", "Communities cover (stub)") { CommunitiesScreen(onClose = {}) },
    CatalogEntry("communities.directorycover", "Directory cover — community account (stub)") {
        CommunityDirectoryCoverScreen(onClose = {})
    },
    CatalogEntry("communities.members", "Members (stub)") { CommunityMembersScreen(cid = Fixtures.community.uid!!, onBack = {}) },
    CatalogEntry("communities.rows", "CommunityRow — all fixtures") {
        Scaffold(
            topBar = { DrokpoTopBar("CommunityRow") },
            containerColor = DrokpoTheme.colors.background,
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Fixtures.communities.forEach { CommunityRow(it) }
            }
        }
    },
    CatalogEntry("communityhome.editor", "Community profile editor (stub)") { CommunityProfileEditorScreen() },
    CatalogEntry("communityhome.banner", "PendingVerificationBanner") {
        Scaffold(
            topBar = { DrokpoTopBar("Discover") },
            containerColor = DrokpoTheme.colors.background,
        ) { padding ->
            PendingVerificationBanner(Modifier.padding(padding))
        }
    },
)
