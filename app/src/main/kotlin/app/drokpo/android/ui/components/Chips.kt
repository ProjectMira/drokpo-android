package app.drokpo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.drokpo.android.ui.theme.DrokpoPreviews
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold

/**
 * Port of iOS `FlowLayout`: wraps children left-to-right, moving to a new row
 * when the current one would overflow the width. Used for interest/language
 * chips. [spacing] applies both between items and between rows.
 */
@Composable
fun FlowLayout(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** Interest/language capsules in a [FlowLayout] (ProfileDetailView). */
@Composable
fun TagFlow(
    tags: List<String>,
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
) {
    FlowLayout(modifier = modifier, spacing = spacing) {
        tags.forEach { TagChip(it) }
    }
}

/**
 * Static capsule tag: `.footnote` text on the `.quaternary` fill
 * (ProfileDetailView interests, comment/chat capsules). Override colours or
 * style for variants.
 */
@Composable
fun TagChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    containerColor: Color = DrokpoTheme.colors.fill,
    contentColor: Color = DrokpoTheme.colors.label,
    textStyle: TextStyle = DrokpoTheme.typography.footnote,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(containerColor)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(14.dp))
        }
        Text(text, style = textStyle, color = contentColor, maxLines = 1)
    }
}

/**
 * Small tinted capsule — the "Community" badge on like rows:
 * `.caption2.bold()` in [tint] on [tint] at 15%.
 */
@Composable
fun TintedTag(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = DrokpoTheme.colors.accent,
) {
    TagChip(
        text = text,
        modifier = modifier,
        containerColor = tint.copy(alpha = tint.alpha * 0.15f),
        contentColor = tint,
        textStyle = DrokpoTheme.typography.caption2.bold(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * White capsule over a photo on a black scrim — Discover card badges
 * ("News", "Sponsored": `.caption.bold()` on black 55%). [small] is the
 * profile photo grid's "Primary" badge (`.caption2.bold()` on black 60%).
 */
@Composable
fun OverlayTag(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    small: Boolean = false,
) {
    val colors = DrokpoTheme.colors
    TagChip(
        text = text,
        modifier = modifier,
        icon = icon,
        containerColor = if (small) colors.photoScrimStrong else colors.photoScrim,
        contentColor = colors.onPhoto,
        textStyle = if (small) DrokpoTheme.typography.caption2.bold() else DrokpoTheme.typography.caption.bold(),
        contentPadding = if (small) {
            PaddingValues(horizontal = 6.dp, vertical = 2.dp)
        } else {
            PaddingValues(horizontal = 10.dp, vertical = 5.dp)
        },
    )
}

/**
 * Selectable pill (Likes' "All / Friends / Communities / News" filters):
 * `.subheadline`, bold when selected, on accent 18% when selected or
 * systemGray6 otherwise.
 */
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = DrokpoTheme.colors
    val style = DrokpoTheme.typography.subheadline
    Text(
        text = text,
        style = style.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
        color = if (enabled) colors.label else colors.tertiaryLabel,
        maxLines = 1,
        modifier = modifier
            .clip(CircleShape)
            .background(if (selected) colors.accent.copy(alpha = 0.18f) else colors.systemGray6)
            .clickable(enabled = enabled, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/**
 * Horizontally scrolling row of [Chip]s with single selection, 8dp apart and
 * inset like iOS `.padding(.horizontal)` + `.padding(.vertical, 8)`.
 */
@Composable
fun <T> ChipBar(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() },
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            Chip(text = label(option), selected = option == selected, onClick = { onSelect(option) })
        }
    }
}

@DrokpoPreviews
@Composable
private fun ChipsPreview() {
    DrokpoTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChipBar(
                options = listOf("All", "Friends", "Communities", "News"),
                selected = "All",
                onSelect = {},
                contentPadding = PaddingValues(0.dp),
            )
            TagFlow(listOf("Hiking", "Momo", "Music", "Dharma", "Football", "Thangka painting", "Travel"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TintedTag("Community")
                TagChip("Saved", icon = Icons.Filled.Groups)
            }
            Row(
                Modifier
                    .background(Color.DarkGray)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OverlayTag("News")
                OverlayTag("Sponsored")
                OverlayTag("Primary", small = true)
            }
        }
    }
}
