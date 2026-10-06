package app.drokpo.android.features.communities

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.drokpo.android.features.StubScreen
import app.drokpo.android.ui.components.NavIcon

/** Port of CommunitiesView — a person's community browsing, presented by FeedScreen inside a
 *  FullScreenCover. Owns its NavHost (Home → Directory / SharedRoute.*). "Close" → onClose.
 *  (CONTRACT §B.8 — stub.) */
@Composable
fun CommunitiesScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    StubScreen("CommunitiesScreen", "Communities", modifier, navIcon = NavIcon.Close, onNavIcon = onClose, large = true)
}
