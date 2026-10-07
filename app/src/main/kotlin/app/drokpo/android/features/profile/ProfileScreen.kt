package app.drokpo.android.features.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.drokpo.android.MainTab
import app.drokpo.android.TabReselectEffect
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.model.Photo
import app.drokpo.android.core.model.Preferences
import app.drokpo.android.features.settings.SettingsScreen
import app.drokpo.android.features.shared.profiledetail.ProfileDetailScreen
import app.drokpo.android.ui.components.DrokpoSheet
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.FullScreenCover
import app.drokpo.android.ui.components.GroupedList
import app.drokpo.android.ui.components.GroupedRow
import app.drokpo.android.ui.components.GroupedSection
import app.drokpo.android.ui.components.GroupedToggleRow
import app.drokpo.android.ui.components.GroupedValueRow
import app.drokpo.android.ui.components.LoadingState
import app.drokpo.android.ui.components.LocalSheetDismiss
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.components.PlainTextButton
import app.drokpo.android.ui.components.rememberPhotoPicker
import app.drokpo.android.ui.theme.DrokpoTheme
import kotlinx.coroutines.launch

/** Port of ProfileView (person Profile tab root). (CONTRACT §B.7.) */
@Composable
fun ProfileScreen(modifier: Modifier = Modifier) {
    val model = viewModel { ProfileModel() }
    val state by model.state.collectAsStateWithLifecycle()
    var showEdit by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showPreview by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()

    // iOS `.onAppear { syncPhotos() }` — also runs again when the tab is re-entered.
    LaunchedEffect(Unit) { model.syncPhotos() }
    // Tapping the selected Profile tab scrolls back to the top (no NavHost to pop) and
    // re-expands the large title, like iOS.
    TabReselectEffect(MainTab.Profile) {
        scope.launch {
            listState.animateScrollToItem(0)
            scrollBehavior.state.heightOffset = 0f
        }
    }

    val pickPhoto = rememberPhotoPicker(maxItems = 1) { uris ->
        uris.firstOrNull()?.let { model.addPhoto(it) }
    }

    ProfileContent(
        state = state,
        onOpenSettings = { showSettings = true },
        onPreview = { if (state.profile != null) showPreview = true },
        onEdit = { if (state.profile != null) showEdit = true },
        onRefresh = model::refresh,
        onAddPhoto = pickPhoto,
        onDeletePhoto = model::deletePhoto,
        onDragStart = model::beginDrag,
        onDragOver = model::dragOver,
        onDragEnd = model::endDrag,
        onDiscoverableChange = model::setDiscoverable,
        onDismissError = model::dismissError,
        modifier = modifier,
        listState = listState,
        scrollBehavior = scrollBehavior,
    )

    val profile = state.profile
    if (showEdit && profile != null) {
        FullScreenCover(onDismissRequest = { showEdit = false }) {
            EditProfileScreen(
                profile = profile,
                onSaved = { AppGraph.session.refreshProfile() },
                onDismiss = { showEdit = false },
            )
        }
    }
    if (showSettings) {
        FullScreenCover(onDismissRequest = { showSettings = false }) {
            SettingsScreen(onDismiss = { showSettings = false })
        }
    }
    if (showPreview && profile != null) {
        DrokpoSheet(onDismissRequest = { showPreview = false }) {
            ProfileDetailScreen(
                card = profile.asFeedCard,
                onBack = LocalSheetDismiss.current ?: { showPreview = false },
                navIcon = NavIcon.Close,
                title = "Preview",
            )
        }
    }
}

/** Stateless Profile tab: the iOS List of Photos / About / Prompts / Socials / Discovery / Account. */
@Composable
internal fun ProfileContent(
    state: ProfileUiState,
    onOpenSettings: () -> Unit,
    onPreview: () -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onAddPhoto: () -> Unit,
    onDeletePhoto: (Photo) -> Unit,
    onDragStart: (String) -> Unit,
    onDragOver: (String) -> Unit,
    onDragEnd: () -> Unit,
    onDiscoverableChange: (Boolean) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    scrollBehavior: TopAppBarScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
) {
    val colors = DrokpoTheme.colors
    val profile = state.profile

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = colors.groupedBackground,
        topBar = {
            DrokpoTopBar(
                title = "Profile",
                large = true,
                containerColor = colors.groupedBackground,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
                actions = {
                    IconButton(onClick = onPreview, enabled = profile != null) {
                        Icon(Icons.Outlined.Visibility, contentDescription = "Preview")
                    }
                    PlainTextButton("Edit", onClick = onEdit)
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            GroupedList(
                state = listState,
                contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp + padding.calculateBottomPadding()),
            ) {
                item(key = "photos") {
                    GroupedSection(header = "Photos", footer = ProfileCopy.PHOTOS_FOOTER) {
                        PhotoStrip(
                            photos = state.orderedPhotos,
                            canAdd = state.canAddPhoto,
                            onAddPhoto = onAddPhoto,
                            onDeletePhoto = onDeletePhoto,
                            onDragStart = onDragStart,
                            onDragOver = onDragOver,
                            onDragEnd = onDragEnd,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                item(key = "about") {
                    GroupedSection(header = "About") {
                        GroupedValueRow("Name", profile?.displayName)
                        GroupedValueRow("Age", profile?.age?.toString())
                        GroupedValueRow("Region", profile?.region)
                        GroupedValueRow("Languages", profile?.languages.joinedOrNull())
                        GroupedValueRow("Interests", profile?.interests.joinedOrNull())
                        GroupedValueRow("Occupation", profile?.occupation)
                        GroupedValueRow("Education", profile?.education)
                        val bio = profile?.bio
                        if (!bio.isNullOrEmpty()) {
                            GroupedRow {
                                Text(bio, style = DrokpoTheme.typography.subheadline)
                            }
                        }
                    }
                }
                item(key = "prompts") {
                    PromptsSection(answeredPrompts(profile?.answers))
                }
                item(key = "socials") {
                    GroupedSection(header = "Socials") {
                        socialRows(profile?.socials).forEach { (title, value) -> GroupedValueRow(title, value) }
                    }
                }
                item(key = "preferences") {
                    val preferences = profile?.preferences ?: Preferences()
                    GroupedSection(header = "Discovery preferences", footer = discoveryFooter(state.isDiscoverable)) {
                        GroupedToggleRow(
                            title = "Show me in Discover",
                            checked = state.isDiscoverable,
                            onCheckedChange = onDiscoverableChange,
                            enabled = state.canToggleDiscoverable,
                        )
                        GroupedValueRow("Age range", "${preferences.ageMin}–${preferences.ageMax}")
                        GroupedValueRow("Distance", "${preferences.distanceKm} km")
                    }
                }
                item(key = "account") {
                    GroupedSection(header = "Account", footer = ProfileCopy.ACCOUNT_FOOTER) {
                        GroupedValueRow("Email", state.email)
                    }
                }
            }
            // `.overlay { if isWorking { ProgressView() } }` — doesn't block the list.
            if (state.isWorking) LoadingState()
        }
    }

    ErrorAlert(message = state.errorMessage, onDismiss = onDismissError)
}

@Composable
private fun PromptsSection(answered: List<Pair<String, String>>) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    GroupedSection(header = "Prompts") {
        if (answered.isEmpty()) {
            GroupedRow {
                Text(ProfileCopy.NO_PROMPTS, style = typography.subheadline, color = colors.secondaryLabel)
            }
        } else {
            answered.forEach { (label, answer) ->
                GroupedRow {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(label, style = typography.caption, color = colors.secondaryLabel)
                        Text(answer)
                    }
                }
            }
        }
    }
}
