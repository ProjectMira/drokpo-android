package app.drokpo.android.features.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.drokpo.android.core.AppConfig
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.AppearanceMode
import app.drokpo.android.navigation.backOrClose
import app.drokpo.android.ui.components.ActionSheet
import app.drokpo.android.ui.components.ActionSheetItem
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.GroupedButtonRow
import app.drokpo.android.ui.components.GroupedExternalLinkRow
import app.drokpo.android.ui.components.GroupedList
import app.drokpo.android.ui.components.GroupedNavigationRow
import app.drokpo.android.ui.components.GroupedSection
import app.drokpo.android.ui.components.GroupedValueRow
import app.drokpo.android.ui.components.LoadingState
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.components.SegmentedPicker
import app.drokpo.android.ui.components.rememberInAppBrowser
import app.drokpo.android.ui.theme.DrokpoTheme
import kotlinx.serialization.Serializable

/** SettingsScreen's own back stack (iOS: SettingsView's NavigationStack). */
@Serializable
internal sealed interface SettingsRoute {
    @Serializable
    data object Settings : SettingsRoute

    @Serializable
    data object BlockedUsers : SettingsRoute

    @Serializable
    data object SentMessages : SettingsRoute
}

internal object SettingsCopy {
    const val DELETE_TITLE = "Delete your account?"
    const val DELETE_MESSAGE =
        "Your profile, photos, likes, and matches will be permanently removed. This cannot be undone."
}

/**
 * Port of SettingsView, presented by ProfileScreen in a FullScreenCover. Owns
 * a NavHost so "Blocked users" and "Messages you've sent" push inside the
 * cover; system back pops them first, then closes Settings.
 */
@Composable
internal fun SettingsScreen(onDismiss: () -> Unit) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = SettingsRoute.Settings, modifier = Modifier.fillMaxSize()) {
        composable<SettingsRoute.Settings> { entry ->
            SettingsHome(
                onDismiss = onDismiss,
                onOpenBlockedUsers = { if (entry.isResumed()) nav.navigate(SettingsRoute.BlockedUsers) },
                onOpenSentMessages = { if (entry.isResumed()) nav.navigate(SettingsRoute.SentMessages) },
            )
        }
        composable<SettingsRoute.BlockedUsers> { entry ->
            BlockedUsersScreen(onBack = { if (entry.isResumed()) nav.backOrClose(onDismiss) })
        }
        composable<SettingsRoute.SentMessages> { entry ->
            SentMessagesScreen(onBack = { if (entry.isResumed()) nav.backOrClose(onDismiss) })
        }
    }
}

/** Ignore a second tap while a push/pop transition is already running (it would double-navigate). */
private fun NavBackStackEntry.isResumed(): Boolean = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

@Composable
private fun SettingsHome(onDismiss: () -> Unit, onOpenBlockedUsers: () -> Unit, onOpenSentMessages: () -> Unit) {
    val model = viewModel { SettingsModel() }
    val state by model.state.collectAsStateWithLifecycle()
    val appearance by AppGraph.prefs.appearance.collectAsStateWithLifecycle()
    val openUrl = rememberInAppBrowser()
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(state.dismissRequested) {
        if (state.dismissRequested) currentOnDismiss()
    }
    SettingsContent(
        state = state,
        appearance = appearance,
        versionLabel = AppConfig.versionLabel,
        showDeleteConfirmation = showDeleteConfirmation,
        onDone = onDismiss,
        onAppearanceChange = { AppGraph.prefs.setAppearance(it) },
        onOpenPrivacyPolicy = { openUrl(AppConfig.PRIVACY_POLICY_URL) },
        onOpenBlockedUsers = onOpenBlockedUsers,
        onOpenSentMessages = onOpenSentMessages,
        onSignOut = {
            onDismiss()
            model.signOut()
        },
        onDeleteRequest = { showDeleteConfirmation = true },
        onDeleteConfirmationDismiss = { showDeleteConfirmation = false },
        onConfirmDelete = model::deleteAccount,
        onDismissError = model::dismissError,
    )
}

/** Stateless Settings list. */
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    appearance: AppearanceMode,
    versionLabel: String,
    showDeleteConfirmation: Boolean,
    onDone: () -> Unit,
    onAppearanceChange: (AppearanceMode) -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onOpenBlockedUsers: () -> Unit,
    onOpenSentMessages: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteRequest: () -> Unit,
    onDeleteConfirmationDismiss: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DrokpoTheme.colors
    Scaffold(
        modifier = modifier,
        containerColor = colors.groupedBackground,
        topBar = {
            DrokpoTopBar(
                title = "Settings",
                navIcon = NavIcon.Close,
                onNavIcon = onDone,
                containerColor = colors.groupedBackground,
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            GroupedList(contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp + padding.calculateBottomPadding())) {
                item(key = "appearance") {
                    GroupedSection(header = "Appearance") {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            SegmentedPicker(
                                options = AppearanceMode.entries,
                                selected = appearance,
                                onSelect = onAppearanceChange,
                                label = { it.label },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                item(key = "about") {
                    GroupedSection(header = "About") {
                        GroupedExternalLinkRow("Privacy policy", onClick = onOpenPrivacyPolicy)
                        GroupedValueRow("Version", versionLabel)
                    }
                }
                item(key = "privacy") {
                    GroupedSection(header = "Privacy & activity") {
                        GroupedNavigationRow("Blocked users", onClick = onOpenBlockedUsers)
                        GroupedNavigationRow("Messages you've sent", onClick = onOpenSentMessages)
                    }
                }
                item(key = "account") {
                    GroupedSection(header = "Account") {
                        GroupedButtonRow("Sign out", onClick = onSignOut)
                        GroupedButtonRow(
                            "Delete account",
                            onClick = onDeleteRequest,
                            destructive = true,
                            enabled = !state.isDeleting,
                        )
                    }
                }
            }
            if (state.isDeleting) LoadingState()
        }
    }

    if (showDeleteConfirmation) {
        ActionSheet(
            onDismissRequest = onDeleteConfirmationDismiss,
            title = SettingsCopy.DELETE_TITLE,
            message = SettingsCopy.DELETE_MESSAGE,
            items = listOf(ActionSheetItem("Delete everything", destructive = true, onClick = onConfirmDelete)),
        )
    }

    ErrorAlert(message = state.errorMessage, onDismiss = onDismissError, title = "Couldn't delete account")
}
