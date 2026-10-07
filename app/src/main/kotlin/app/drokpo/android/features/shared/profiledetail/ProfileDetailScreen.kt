package app.drokpo.android.features.shared.profiledetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.SwipeResult
import app.drokpo.android.ui.components.NavIcon

/**
 * Port of ProfileDetailContext: determines the bottom action bar and CTA
 * behavior for [ProfileDetailScreen], so the same screen serves the Likes
 * list, Discover's expanded card, and plain read-only views (own profile
 * preview, matched-chat header, shared links). (CONTRACT §B.11.)
 */
sealed interface ProfileDetailContext {
    data object Plain : ProfileDetailContext

    /** "Liked you" entry, not matched yet: "Like back", which flips to "Send message" once it matches. */
    class LikedYou(val onLikeBack: suspend () -> SwipeResult?) : ProfileDetailContext

    /** Expanded card from the Discover deck: pass/like bar. */
    class Discover(val onLike: () -> Unit, val onPass: () -> Unit) : ProfileDetailContext
}

/**
 * Port of ProfileDetailView: full look at another member's profile, pushed
 * from the chats and likes lists, or presented as a sheet from Discover.
 *
 * [onReport]/[onBlock]: caller-owned report/block — Discover passes these so
 * the card also leaves the deck. When null, the screen calls the safety APIs
 * itself (Likes list, chat header, shared links); a successful block then
 * dismisses via [onBack] (iOS `dismiss()`), a report stays on the profile.
 * [title] overrides the top-bar title (ProfileScreen's preview passes "Preview").
 * (CONTRACT §B.11, §F.11.)
 */
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
    val model: ProfileDetailModel = viewModel(key = "ProfileDetail-${card.uid}") { ProfileDetailModel() }
    val state by model.state.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf(ProfileDetailDialog.None) }
    // SessionStore.uid only changes together with the session state, which replaces this whole
    // subtree (CONTRACT §A.3), so reading it once per composition is safe.
    val isSelf = remember(card.uid) { isSelfProfile(card, AppGraph.session.uid) }

    val currentOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(state.dismissAfterBlock) {
        if (state.dismissAfterBlock) {
            model.consumeDismiss()
            currentOnBack()
        }
    }

    ProfileDetailContent(
        card = card,
        title = title ?: card.displayName ?: "Profile",
        navIcon = navIcon,
        isSelf = isSelf,
        context = context,
        state = state,
        dialog = dialog,
        onDialogChange = { dialog = it },
        actions = ProfileDetailActions(
            onBack = onBack,
            onLikeBack = {
                (context as? ProfileDetailContext.LikedYou)?.let { model.likeBack(it.onLikeBack) }
            },
            onOpenThread = { model.openThread() },
            // Report through the caller when it owns cleanup (Discover removes the
            // card from the deck); otherwise call the API directly.
            onReport = { reason -> if (onReport != null) onReport(reason) else model.report(card, reason) },
            onBlock = { if (onBlock != null) onBlock() else model.block(card) },
            onDismissMatchAlert = model::dismissMatchAlert,
            onDismissError = model::dismissError,
        ),
        modifier = modifier,
    )
}
