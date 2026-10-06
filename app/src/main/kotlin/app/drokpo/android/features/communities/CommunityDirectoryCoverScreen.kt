package app.drokpo.android.features.communities

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.drokpo.android.features.StubScreen
import app.drokpo.android.ui.components.NavIcon

/** A community account's browse cover (iOS FeedView: NavigationStack { CommunityDirectoryView } +
 *  "Close"). Owns its NavHost (Directory(navIcon = Close) → SharedRoute.*). (CONTRACT §B.8 — stub.) */
@Composable
fun CommunityDirectoryCoverScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    StubScreen("CommunityDirectoryCoverScreen", "Discover communities", modifier, navIcon = NavIcon.Close, onNavIcon = onClose)
}
