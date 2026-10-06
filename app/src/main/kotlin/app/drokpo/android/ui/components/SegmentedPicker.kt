package app.drokpo.android.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.drokpo.android.ui.theme.DrokpoPreviews
import app.drokpo.android.ui.theme.DrokpoTheme

/**
 * `Picker(…).pickerStyle(.segmented)` with the iOS look: grey track, a raised
 * thumb that slides to the selected segment, 13sp labels (semibold when
 * selected). Used for Settings → Appearance (System / Light / Dark) and
 * Likes' "You liked / Liked you".
 */
@Composable
fun <T> SegmentedPicker(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: (T) -> String = { it.toString() },
) {
    if (options.isEmpty()) return
    val colors = DrokpoTheme.colors
    val selectedIndex = options.indexOf(selected).coerceAtLeast(0)
    val trackShape = RoundedCornerShape(9.dp)
    val thumbShape = RoundedCornerShape(7.dp)
    val thumbColor = if (colors.isDark) colors.systemGray2 else colors.background

    BoxWithConstraints(
        modifier = modifier
            .height(32.dp)
            .clip(trackShape)
            .background(colors.tertiaryFill)
            .alpha(if (enabled) 1f else 0.5f),
    ) {
        val segmentWidth = maxWidth / options.size
        val thumbOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = spring(dampingRatio = 0.9f, stiffness = 600f),
            label = "segmentThumb",
        )
        Box(
            Modifier
                // Lambda overload: the animated offset only re-places the thumb, no recomposition.
                .offset { IntOffset(thumbOffset.roundToPx(), 0) }
                .width(segmentWidth)
                .fillMaxHeight()
                .padding(2.dp)
                .shadow(if (colors.isDark) 0.dp else 1.dp, thumbShape)
                .background(thumbColor, thumbShape),
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = isSelected,
                            enabled = enabled,
                            role = Role.Tab,
                            onClick = { if (!isSelected) onSelect(option) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label(option),
                        style = DrokpoTheme.typography.footnote.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = colors.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }
    }
}

@DrokpoPreviews
@Composable
private fun SegmentedPickerPreview() {
    DrokpoTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SegmentedPicker(
                options = listOf("System", "Light", "Dark"),
                selected = "Light",
                onSelect = {},
                modifier = Modifier.fillMaxWidth(),
            )
            SegmentedPicker(
                options = listOf("You liked", "Liked you"),
                selected = "You liked",
                onSelect = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
