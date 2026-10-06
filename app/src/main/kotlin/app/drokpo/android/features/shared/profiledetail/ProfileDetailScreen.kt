package app.drokpo.android.features.shared.profiledetail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.SwipeResult
import app.drokpo.android.features.StubScreen
import app.drokpo.android.ui.components.NavIcon

/** Port of ProfileDetailContext. (CONTRACT §B.11.) */
sealed interface ProfileDetailContext {
    data object Plain : ProfileDetailContext

    /** "Liked you" entry, not matched yet: "Like back", which flips to "Send message" once it matches. */
    class LikedYou(val onLikeBack: suspend () -> SwipeResult?) : ProfileDetailContext

    /** Expanded card from the Discover deck: pass/like bar. */
    class Discover(val onLike: () -> Unit, val onPass: () -> Unit) : ProfileDetailContext
}

/** Port of ProfileDetailView. onReport/onBlock: caller-owned safety (Discover — the card must also
 *  leave the deck); when null the screen calls Safety.report / Safety.block itself and then onBack().
 *  title overrides the top-bar title (ProfileScreen preview passes "Preview"). (CONTRACT §B.11 — stub.) */
@Composable
fun ProfileDetailScreen(
    card: FeedCard,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    context: ProfileDetailContext = ProfileDetailContext.Plain,
    onReport: ((reason: String) -> Unit)? = null,
    onBlock: (() -> Unit)? = null,
    navIcon: NavIcon = NavIcon.Back,
    title: String? = null,
) {
    StubScreen(
        name = "ProfileDetailScreen(${card.uid})",
        title = title ?: card.displayName ?: "Profile",
        modifier = modifier,
        navIcon = navIcon,
        onNavIcon = onBack,
    )
}
