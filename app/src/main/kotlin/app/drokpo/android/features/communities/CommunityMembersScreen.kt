package app.drokpo.android.features.communities

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.drokpo.android.features.StubScreen
import app.drokpo.android.ui.components.NavIcon

/** Port of CommunityMembersView (always pushed → Back icon). Registered by sharedDestinations.
 *  (CONTRACT §B.8 — stub.) */
@Composable
fun CommunityMembersScreen(cid: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    StubScreen("CommunityMembersScreen($cid)", "Members", modifier, navIcon = NavIcon.Back, onNavIcon = onBack)
}
