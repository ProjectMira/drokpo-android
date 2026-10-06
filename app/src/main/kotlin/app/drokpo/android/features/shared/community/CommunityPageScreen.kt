package app.drokpo.android.features.shared.community

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.drokpo.android.core.model.CommunityProfile
import app.drokpo.android.features.StubScreen
import app.drokpo.android.ui.components.NavIcon

/** Port of CommunityPageView (Instagram-style page; visitor or ownerMode). Registered by
 *  sharedDestinations; also the community tab root via SharedNavHost(ownerMode = true, navIcon None).
 *  onBack = leading button AND iOS dismiss() after blocking. preview = directory/rail data shown
 *  immediately (ignored in ownerMode, which reads AppGraph.session.myCommunity).
 *  (CONTRACT §B.9 — stub.) */
@Composable
fun CommunityPageScreen(
    cid: String,
    onBack: () -> Unit,
    onOpenMembers: (cid: String) -> Unit,
    modifier: Modifier = Modifier,
    preview: CommunityProfile? = null,
    ownerMode: Boolean = false,
    navIcon: NavIcon = NavIcon.Back,
) {
    StubScreen(
        name = "CommunityPageScreen($cid${if (ownerMode) ", owner" else ""})",
        title = preview?.name ?: "Community",
        modifier = modifier,
        navIcon = navIcon,
        onNavIcon = onBack,
    )
}
