package app.drokpo.android.features.shared.profiledetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Pending
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.drokpo.android.core.RemotePhotoView
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.Photo
import app.drokpo.android.core.model.Vocabulary
import app.drokpo.android.features.feed.SwipeActionButtons
import app.drokpo.android.features.shared.sharing.ShareButton
import app.drokpo.android.features.shared.sharing.ShareableContent
import app.drokpo.android.ui.components.ActionBar
import app.drokpo.android.ui.components.ActionRole
import app.drokpo.android.ui.components.ActionSheet
import app.drokpo.android.ui.components.ActionSheetItem
import app.drokpo.android.ui.components.AlertButton
import app.drokpo.android.ui.components.DrokpoAlert
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.components.PrimaryButton
import app.drokpo.android.ui.components.TagFlow
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold

/** Which safety UI is up: the toolbar Menu, the report-reason dialog, or the block confirmation. */
internal enum class ProfileDetailDialog { None, Menu, ReportReasons, BlockConfirm }

/** Every user action ProfileDetailContent can raise (no-op defaults for the debug catalog). */
@Immutable
internal class ProfileDetailActions(
    val onBack: () -> Unit = {},
    val onLikeBack: () -> Unit = {},
    /** "Send message" (after a LikedYou match) and the match alert's "Say hi". */
    val onOpenThread: () -> Unit = {},
    val onReport: (reason: String) -> Unit = {},
    val onBlock: () -> Unit = {},
    val onDismissMatchAlert: () -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * Stateless ProfileDetailView: photo pager, name + age, labelled facts, bio,
 * interests, answered prompts; the top bar's ShareButton and Report/Block
 * menu; the context's bottom action bar; and the confirmations/alerts.
 */
@Composable
internal fun ProfileDetailContent(
    card: FeedCard,
    title: String,
    navIcon: NavIcon,
    isSelf: Boolean,
    context: ProfileDetailContext,
    state: ProfileDetailState,
    dialog: ProfileDetailDialog,
    onDialogChange: (ProfileDetailDialog) -> Unit,
    actions: ProfileDetailActions,
    modifier: Modifier = Modifier,
) {
    val colors = DrokpoTheme.colors
    val hasActionBar = context !is ProfileDetailContext.Plain
    Scaffold(
        modifier = modifier,
        topBar = {
            DrokpoTopBar(
                title = title,
                navIcon = navIcon,
                onNavIcon = actions.onBack,
                actions = {
                    ShareButton(
                        if (card.isCommunity) {
                            ShareableContent.Community(cid = card.uid, name = card.displayName)
                        } else {
                            ShareableContent.Profile(card)
                        },
                    )
                    if (!isSelf) {
                        ReportOrBlockMenu(
                            expanded = dialog == ProfileDetailDialog.Menu,
                            onDialogChange = onDialogChange,
                        )
                    }
                },
            )
        },
        bottomBar = { ProfileDetailActionBar(context = context, state = state, actions = actions) },
        containerColor = colors.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp)
                // iOS adds this on top of the bar's own safe-area inset, so the last
                // answer card clears the action bar with room to spare.
                .padding(bottom = if (hasActionBar) 72.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            card.photos?.takeIf { it.isNotEmpty() }?.let { PhotoPager(it) }
            ProfileFacts(card)
        }
    }

    when (dialog) {
        ProfileDetailDialog.ReportReasons -> ActionSheet(
            onDismissRequest = { onDialogChange(ProfileDetailDialog.None) },
            title = "Why are you reporting this profile?",
            items = Vocabulary.reportReasons.map { reason ->
                ActionSheetItem(reason, destructive = true) { actions.onReport(reason) }
            },
        )
        ProfileDetailDialog.BlockConfirm -> ActionSheet(
            onDismissRequest = { onDialogChange(ProfileDetailDialog.None) },
            title = "Block ${card.displayName ?: "this member"}?",
            message = "You won't see each other anywhere in Drokpo.",
            items = listOf(ActionSheetItem("Block", destructive = true) { actions.onBlock() }),
        )
        ProfileDetailDialog.None, ProfileDetailDialog.Menu -> Unit
    }

    if (state.showMatchAlert) {
        DrokpoAlert(
            title = "It's a match!",
            message = "You and ${card.displayName ?: "they"} liked each other.",
            onDismissRequest = actions.onDismissMatchAlert,
            confirmButton = AlertButton("Say hi") { actions.onOpenThread() },
            dismissButton = AlertButton("Later", ActionRole.Cancel),
        )
    }
    ErrorAlert(message = state.errorMessage, onDismiss = actions.onDismissError)
}

/** Toolbar `Menu` on `ellipsis.circle` (a11y "Report or block"): two destructive items. */
@Composable
private fun ReportOrBlockMenu(expanded: Boolean, onDialogChange: (ProfileDetailDialog) -> Unit) {
    val destructive = DrokpoTheme.colors.destructive
    Box {
        IconButton(onClick = { onDialogChange(ProfileDetailDialog.Menu) }) {
            Icon(Icons.Outlined.Pending, contentDescription = "Report or block")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onDialogChange(ProfileDetailDialog.None) }) {
            DropdownMenuItem(
                text = { Text("Report", color = destructive) },
                onClick = { onDialogChange(ProfileDetailDialog.ReportReasons) },
            )
            DropdownMenuItem(
                text = { Text("Block", color = destructive) },
                onClick = { onDialogChange(ProfileDetailDialog.BlockConfirm) },
            )
        }
    }
}

/** iOS `TabView` with `.tabViewStyle(.page)`: 420dp tall, 16dp corners, page dots over the photo. */
@Composable
private fun PhotoPager(photos: List<Photo>) {
    val pagerState = rememberPagerState { photos.size }
    Box(
        Modifier
            .fillMaxWidth()
            .height(420.dp)
            .clip(RoundedCornerShape(16.dp)),
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            RemotePhotoView(photo = photos[page], modifier = Modifier.fillMaxSize())
        }
        // The page control hides itself for a single page (indexDisplayMode .automatic).
        if (photos.size > 1) {
            PageDots(
                count = photos.size,
                current = pagerState.currentPage,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val onPhoto = DrokpoTheme.colors.onPhoto
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { index ->
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (index == current) onPhoto else onPhoto.copy(alpha = 0.4f)),
            )
        }
    }
}

/** Name + age, the labelled facts, bio, interests and answered prompts (iOS's inner VStack, spacing 8). */
@Composable
private fun ProfileFacts(card: FeedCard) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                card.displayName ?: "—",
                style = typography.title.bold(),
                color = colors.label,
                modifier = Modifier.alignByBaseline().weight(1f, fill = false),
            )
            card.displayAge?.let { age ->
                Text("$age", style = typography.title2, color = colors.label, modifier = Modifier.alignByBaseline())
            }
        }
        card.region?.let { DetailLabel(it, Icons.Filled.Place) }
        card.distanceKm?.let { DetailLabel(distanceLabel(it), Icons.Filled.NearMe) }
        card.occupation?.takeIf { it.isNotEmpty() }?.let { DetailLabel(it, Icons.Filled.Work) }
        card.education?.takeIf { it.isNotEmpty() }?.let { DetailLabel(it, Icons.Filled.School) }
        card.languages?.takeIf { it.isNotEmpty() }?.let { DetailLabel(it.joinToString(", "), Icons.Filled.Language) }
        card.socials?.instagram?.takeIf { it.isNotEmpty() }?.let { DetailLabel("@$it", Icons.Outlined.CameraAlt) }
        card.bio?.takeIf { it.isNotEmpty() }?.let {
            Text(it, style = typography.body, color = colors.label, modifier = Modifier.padding(top = 4.dp))
        }
        card.interests?.takeIf { it.isNotEmpty() }?.let { interests ->
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailLabel("Interests", Icons.Filled.AutoAwesome, style = typography.subheadline, iconSize = 16.dp)
                TagFlow(interests, spacing = 8.dp)
            }
        }
        val answered = answeredQuestions(card)
        if (answered.isNotEmpty()) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                answered.forEach { item -> AnswerCard(item) }
            }
        }
    }
}

/** SwiftUI `Label(text, systemImage:)` in `.secondary`. */
@Composable
private fun DetailLabel(
    text: String,
    icon: ImageVector,
    style: TextStyle = DrokpoTheme.typography.body,
    iconSize: Dp = 18.dp,
) {
    val secondary = DrokpoTheme.colors.secondaryLabel
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = secondary, modifier = Modifier.size(iconSize))
        Text(text, style = style, color = secondary)
    }
}

@Composable
private fun AnswerCard(item: AnsweredQuestion) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.fillSubtle)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(item.question.label, style = typography.caption, color = colors.secondaryLabel)
        Text(item.answer, style = typography.body, color = colors.label)
    }
}

/** iOS `.safeAreaInset(edge: .bottom) { actionBar }` — nothing for Plain. */
@Composable
private fun ProfileDetailActionBar(context: ProfileDetailContext, state: ProfileDetailState, actions: ProfileDetailActions) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    when (context) {
        ProfileDetailContext.Plain -> Unit
        is ProfileDetailContext.LikedYou -> ActionBar {
            if (state.localMatchId != null) {
                PrimaryButton(
                    text = "Send message",
                    onClick = actions.onOpenThread,
                    icon = Icons.AutoMirrored.Filled.Chat,
                    textStyle = typography.headline,
                )
            } else {
                PrimaryButton(
                    text = "Like back",
                    onClick = actions.onLikeBack,
                    enabled = !state.isLiking,
                    icon = Icons.Filled.Favorite,
                    tint = colors.brandRed,
                    textStyle = typography.headline,
                )
            }
        }
        is ProfileDetailContext.Discover -> ActionBar(contentPadding = PaddingValues(vertical = 12.dp)) {
            // SwipeActionButtons centres itself (`.frame(maxWidth: .infinity)`); the Box keeps it
            // centred whatever width the component takes.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                SwipeActionButtons(onPass = context.onPass, onLike = context.onLike)
            }
        }
    }
}
