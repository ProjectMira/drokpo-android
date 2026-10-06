package app.drokpo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.drokpo.android.ui.theme.DrokpoPreviews
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold

/**
 * Unread count on a conversation row (ChatsView): `.caption.bold()` white on
 * the accent, round for one digit and a capsule beyond. Renders nothing for
 * zero or less, like iOS's `if entry.unread > 0`.
 */
@Composable
fun CountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = DrokpoTheme.colors.accent,
    contentColor: Color = DrokpoTheme.colors.onAccent,
) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
            .clip(CircleShape)
            .background(containerColor)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(count.toString(), style = DrokpoTheme.typography.caption.bold(), color = contentColor, maxLines = 1)
    }
}

/**
 * Tab-bar badge (`.badge(chats.totalUnread)`): use as the `badge` slot of a
 * `BadgedBox` around a NavigationBarItem icon. iOS tab badges are system red
 * and hidden at zero.
 */
@Composable
fun TabCountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Badge(
        modifier = modifier,
        containerColor = DrokpoTheme.colors.destructive,
        contentColor = Color.White,
    ) {
        Text(count.toString())
    }
}

@DrokpoPreviews
@Composable
private fun BadgesPreview() {
    DrokpoTheme {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CountBadge(3)
            CountBadge(12)
            CountBadge(0)
            BadgedBox(badge = { TabCountBadge(5) }) {
                Icon(Icons.Filled.Forum, contentDescription = "Chats")
            }
        }
    }
}
