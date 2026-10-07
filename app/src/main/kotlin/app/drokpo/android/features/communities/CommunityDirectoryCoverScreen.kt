package app.drokpo.android.features.communities

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.drokpo.android.navigation.openCommunity
import app.drokpo.android.navigation.sharedDestinations
import app.drokpo.android.ui.components.NavIcon

/**
 * A community account's browse cover (iOS FeedView's `communityBrowseCover`:
 * `NavigationStack { CommunityDirectoryView() }` + "Close" — a person browses
 * joined + suggested communities, a community account browses the directory
 * instead, since communities don't join communities). Owns its NavHost:
 * Directory (navIcon = Close) → SharedRoute.* (CONTRACT §B.8, §C.2).
 */
@Composable
fun CommunityDirectoryCoverScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = CommunitiesRoute.Directory, modifier = modifier) {
        composable<CommunitiesRoute.Directory> {
            CommunityDirectoryScreen(
                onBack = onClose,
                onOpenCommunity = { cid, preview -> nav.openCommunity(cid, preview) },
                navIcon = NavIcon.Close,
            )
        }
        sharedDestinations(nav, onCloseHost = onClose)
    }
}
