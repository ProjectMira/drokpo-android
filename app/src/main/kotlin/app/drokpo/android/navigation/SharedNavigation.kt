package app.drokpo.android.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.drokpo.android.core.DrokpoJson
import app.drokpo.android.core.model.CommunityProfile
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.features.communities.CommunityMembersScreen
import app.drokpo.android.features.shared.community.CommunityPageScreen
import app.drokpo.android.features.shared.profiledetail.ProfileDetailScreen
import app.drokpo.android.ui.components.NavIcon
import kotlinx.serialization.Serializable

/**
 * Destinations every NavHost can push (CONTRACT §A.13).
 *
 * iOS pushes ProfileDetailView, CommunityPageView and CommunityMembersView from
 * many different NavigationStacks (Likes, Chats, the Communities cover, the
 * directory, shared-link sheets, the community tab). Android registers them in
 * every host through [sharedDestinations], so the routes are identical
 * everywhere. Route args are primitives or DrokpoJson strings — never lambdas.
 */
@Serializable
sealed interface SharedRoute {
    /** ProfileDetailScreen, ProfileDetailContext.Plain. cardJson = DrokpoJson-encoded FeedCard. */
    @Serializable
    data class Profile(val cardJson: String) : SharedRoute

    /** CommunityPageScreen. previewJson = DrokpoJson-encoded CommunityProfile (directory/rail card). */
    @Serializable
    data class Community(
        val cid: String,
        val previewJson: String? = null,
        val ownerMode: Boolean = false,
    ) : SharedRoute

    /** CommunityMembersScreen. */
    @Serializable
    data class CommunityMembers(val cid: String) : SharedRoute
}

/**
 * Push a member. A community card opens its page instead — the iOS
 * `if card.isCommunity { CommunityPageView } else { ProfileDetailView }` pattern.
 */
fun NavController.openProfile(card: FeedCard) {
    if (card.isCommunity) {
        openCommunity(card.uid)
    } else {
        navigate(SharedRoute.Profile(DrokpoJson.encodeToString(FeedCard.serializer(), card)))
    }
}

fun NavController.openCommunity(cid: String, preview: CommunityProfile? = null) {
    navigate(
        SharedRoute.Community(
            cid = cid,
            previewJson = preview?.let { DrokpoJson.encodeToString(CommunityProfile.serializer(), it) },
        ),
    )
}

fun NavController.openCommunityMembers(cid: String) {
    navigate(SharedRoute.CommunityMembers(cid))
}

/**
 * Registers the three [SharedRoute] destinations wired to the helpers above.
 * [onCloseHost] = how to close the sheet/cover that contains this NavHost
 * (null for tab roots).
 */
fun NavGraphBuilder.sharedDestinations(navController: NavHostController, onCloseHost: (() -> Unit)? = null) {
    composable<SharedRoute.Profile> { entry ->
        val route = entry.toRoute<SharedRoute.Profile>()
        ProfileDetailScreen(
            card = DrokpoJson.decodeFromString(FeedCard.serializer(), route.cardJson),
            onBack = { navController.backOrClose(onCloseHost) },
            navIcon = navController.navIconFor(entry, onCloseHost),
        )
    }
    composable<SharedRoute.Community> { entry ->
        val route = entry.toRoute<SharedRoute.Community>()
        CommunityPageScreen(
            cid = route.cid,
            onBack = { navController.backOrClose(onCloseHost) },
            onOpenMembers = { navController.openCommunityMembers(it) },
            preview = route.previewJson?.let { DrokpoJson.decodeFromString(CommunityProfile.serializer(), it) },
            ownerMode = route.ownerMode,
            navIcon = navController.navIconFor(entry, onCloseHost),
        )
    }
    composable<SharedRoute.CommunityMembers> { entry ->
        CommunityMembersScreen(
            cid = entry.toRoute<SharedRoute.CommunityMembers>().cid,
            onBack = { navController.backOrClose(onCloseHost) },
        )
    }
}

/**
 * Pop; at the host's start destination, close the host instead
 * (`onCloseHost?.invoke()`). Use it as every destination's onBack.
 *
 * Checks [NavController.previousBackStackEntry] rather than trusting
 * popBackStack()'s result: popping the start destination succeeds and leaves
 * the NavHost empty (a blank sheet or tab).
 */
fun NavHostController.backOrClose(onCloseHost: (() -> Unit)?) {
    if (previousBackStackEntry != null) {
        popBackStack()
    } else {
        onCloseHost?.invoke()
    }
}

/** Back if [entry] has something below it on the back stack; else Close if [onCloseHost] != null; else None. */
fun NavHostController.navIconFor(entry: NavBackStackEntry, onCloseHost: (() -> Unit)?): NavIcon = when {
    !isRootEntry(entry) -> NavIcon.Back
    onCloseHost != null -> NavIcon.Close
    else -> NavIcon.None
}

private const val KEY_IS_ROOT = "drokpo.nav.isRoot"

/**
 * Whether [entry] is the bottom of its host's back stack, using public API only:
 * `NavController.currentBackStack` is restricted to the navigation library group (lint
 * RestrictedApi). An entry's position never changes while it lives (no host pops below
 * a live entry), so it is decided once, while the entry is on top and
 * [NavController.previousBackStackEntry] can answer. The answer is cached in the entry's
 * SavedStateHandle, which also survives process death.
 */
private fun NavHostController.isRootEntry(entry: NavBackStackEntry): Boolean {
    val handle = try {
        entry.savedStateHandle
    } catch (e: IllegalStateException) {
        null // Not attached yet, or already destroyed (an exiting entry).
    }
    handle?.get<Boolean>(KEY_IS_ROOT)?.let { return it }
    if (currentBackStackEntry?.id != entry.id) {
        // Never seen on top, e.g. revealed by a predictive-back gesture under a synthetic
        // stack: only the host's start destination can be the root.
        return entry.destination.id == graph.startDestinationId
    }
    val isRoot = previousBackStackEntry == null
    handle?.set(KEY_IS_ROOT, isRoot)
    return isRoot
}

/**
 * NavHost whose graph is just [sharedDestinations], starting at [start]. Pass
 * [navController] when the caller needs it, e.g. to pop to the start
 * destination on a tab reselect (MainTabs' community tab).
 */
@Composable
fun SharedNavHost(
    start: SharedRoute,
    modifier: Modifier = Modifier,
    onCloseHost: (() -> Unit)? = null,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController, startDestination = start, modifier = modifier) {
        sharedDestinations(navController, onCloseHost)
    }
}
