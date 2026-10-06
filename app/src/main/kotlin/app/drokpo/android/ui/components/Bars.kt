package app.drokpo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.drokpo.android.ui.theme.DrokpoPreviews
import app.drokpo.android.ui.theme.DrokpoTheme

/**
 * Leading top-bar button (CONTRACT.md §A.12): [Back] = arrow (pushed screen),
 * [Close] = X (root of a sheet/cover; iOS "Close"/"Cancel"/"Done" dismiss
 * buttons), [None] = nothing (tab roots).
 */
enum class NavIcon { Back, Close, None }

/**
 * Navigation bar for a screen (`.navigationTitle` + `.toolbar`), CONTRACT.md §A.12.
 * - inline (`.navigationBarTitleDisplayMode(.inline)`) → CenterAlignedTopAppBar,
 *   17sp semibold title
 * - [large] (default iOS large title: "Profile", "Chats", "Likes") →
 *   LargeTopAppBar: 34sp bold title that collapses on scroll with [scrollBehavior]
 *
 * [navIcon] picks the leading button and [onNavIcon] handles it. For a text
 * button instead (iOS "Cancel", "Back", "Sign out"), pass [navigationIcon],
 * which overrides [navIcon]. Inside a [DrokpoSheet] the status-bar inset is
 * dropped automatically. Match [containerColor] to the content behind it:
 * `groupedBackground` over grouped lists, `background` over plain screens.
 */
@Composable
fun DrokpoTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navIcon: NavIcon = NavIcon.None,
    onNavIcon: () -> Unit = {},
    large: Boolean = false,
    containerColor: Color = DrokpoTheme.colors.background,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = DrokpoTheme.colors
    val nav: @Composable () -> Unit = {
        when {
            navigationIcon != null -> navigationIcon()
            navIcon == NavIcon.Back -> BackButton(onNavIcon)
            navIcon == NavIcon.Close -> CloseButton(onNavIcon)
            else -> Unit
        }
    }
    val insets = if (LocalInsideSheet.current) WindowInsets(0, 0, 0, 0) else TopAppBarDefaults.windowInsets
    val barColors = TopAppBarDefaults.topAppBarColors(
        containerColor = containerColor,
        scrolledContainerColor = if (large) colors.bar else containerColor,
        navigationIconContentColor = colors.accent,
        titleContentColor = colors.label,
        actionIconContentColor = colors.accent,
    )
    if (large) {
        LargeTopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = modifier,
            navigationIcon = nav,
            actions = actions,
            // iOS: 44pt bar + 52pt large-title row; M3's 152dp leaves a gap.
            collapsedHeight = 56.dp,
            expandedHeight = 112.dp,
            windowInsets = insets,
            colors = barColors,
            scrollBehavior = scrollBehavior,
        )
    } else {
        CenterAlignedTopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = modifier,
            navigationIcon = nav,
            actions = actions,
            expandedHeight = 56.dp,
            windowInsets = insets,
            colors = barColors,
            scrollBehavior = scrollBehavior,
        )
    }
}

/** Platform back arrow (iOS shows `chevron.left` + "Back"; Android convention wins). */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
    }
}

/** X button that dismisses a sheet or cover ([NavIcon.Close]). */
@Composable
fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Filled.Close, contentDescription = "Close")
    }
}

/**
 * Bottom bar pinned under scrolling content (`.safeAreaInset(edge: .bottom)
 * { … .padding().background(.bar) }`): translucent bar colour, top hairline,
 * 16dp padding, clears the navigation bar. Hosts PrimaryButton CTAs or the
 * swipe action buttons.
 */
@Composable
fun ActionBar(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(DrokpoTheme.colors.bar),
    ) {
        HorizontalDivider(color = DrokpoTheme.colors.separator, thickness = 0.5.dp)
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(contentPadding),
            content = content,
        )
    }
}

/**
 * Row separator for `.listStyle(.plain)` lists (Likes, Chats, members): a
 * hairline inset from the leading edge — 16dp, or past the avatar
 * (16 + avatar + 12) to line up with the row text like iOS.
 */
@Composable
fun ListDivider(modifier: Modifier = Modifier, startIndent: Dp = 16.dp) {
    HorizontalDivider(
        modifier = modifier.padding(start = startIndent),
        thickness = 0.5.dp,
        color = DrokpoTheme.colors.separator,
    )
}

/**
 * Section header in a plain list (Chats' "New matches"): `.headline` on the
 * screen background, so it can also be used as a sticky header.
 */
@Composable
fun PlainSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = DrokpoTheme.typography.headline,
        color = DrokpoTheme.colors.label,
        modifier = modifier
            .fillMaxWidth()
            .background(DrokpoTheme.colors.background)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@DrokpoPreviews
@Composable
private fun BarsPreview() {
    DrokpoTheme {
        Column {
            DrokpoTopBar(
                title = "Settings",
                navigationIcon = { PlainTextButton("Done", onClick = {}, emphasized = true) },
                containerColor = DrokpoTheme.colors.groupedBackground,
            )
            DrokpoTopBar(
                title = "Profile",
                large = true,
                actions = {
                    IconButton(onClick = {}) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                    PlainTextButton("Edit", onClick = {})
                },
            )
            DrokpoTopBar(title = "Blocked users", navIcon = NavIcon.Back)
            DrokpoTopBar(title = "Preview", navIcon = NavIcon.Close)
            PlainSectionHeader("New matches")
            Text("Row", modifier = Modifier.padding(16.dp))
            ListDivider()
            Text("Row", modifier = Modifier.padding(16.dp))
            Spacer(Modifier.height(24.dp))
            Box {
                ActionBar { PrimaryButton("Like back", onClick = {}, tint = DrokpoTheme.colors.brandRed) }
            }
        }
    }
}
