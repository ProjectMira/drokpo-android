package app.drokpo.android.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.drokpo.android.ui.theme.DrokpoPreviews
import app.drokpo.android.ui.theme.DrokpoTheme

/**
 * SwiftUI `List` (`.insetGrouped`) / `Form` → rounded grouped sections on the
 * grouped background. Layout constants mirror iOS: 16dp screen margin, 10dp
 * section corners, 44dp minimum row height, hairline separators inset to the
 * row text.
 */
object GroupedDefaults {
    val SectionMargin: Dp = 16.dp
    val SectionCornerRadius: Dp = 10.dp
    val SectionSpacing: Dp = 24.dp
    val RowMinHeight: Dp = 44.dp
    val RowPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp)
    val SeparatorInset: Dp = 16.dp
}

/**
 * Scrolling container for grouped sections (`List { Section … }`): a
 * LazyColumn on `groupedBackground` with iOS section spacing. Add Scaffold
 * insets to [contentPadding] for edge-to-edge scrolling.
 *
 * ```
 * GroupedList(contentPadding = innerPadding) {
 *     item { GroupedSection(header = "About") { GroupedValueRow("Name", name) } }
 * }
 * ```
 */
@Composable
fun GroupedList(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(top = 16.dp, bottom = 32.dp),
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DrokpoTheme.colors.groupedBackground),
        state = state,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(GroupedDefaults.SectionSpacing),
        content = content,
    )
}

/**
 * Non-lazy [GroupedList] for short forms (`Form { … }` — onboarding steps,
 * editors): a vertically scrolling Column with section spacing.
 */
@Composable
fun GroupedForm(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    contentPadding: PaddingValues = PaddingValues(top = 16.dp, bottom = 32.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DrokpoTheme.colors.groupedBackground)
            .verticalScroll(scrollState)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(GroupedDefaults.SectionSpacing),
        content = content,
    )
}

/**
 * One inset-grouped `Section` (CONTRACT.md §A.12): optional header
 * (uppercased footnote, like SwiftUI's default `textCase`), a rounded
 * secondary-grouped card of rows with hairline separators drawn *between*
 * children automatically, and an optional footer ([footer] text or a custom
 * [footerContent]).
 *
 * Each direct child of [content] is one row. Use the Grouped*Row composables,
 * or any composable (e.g. a horizontal photo strip) for custom rows. Plain
 * `Text` in a row defaults to `body` in `label` colour.
 */
@Composable
fun GroupedSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    footerContent: (@Composable () -> Unit)? = null,
    headerContent: (@Composable () -> Unit)? = null,
    separatorInset: Dp = GroupedDefaults.SeparatorInset,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    val layoutDirection = LocalLayoutDirection.current
    val separators = remember { SeparatorPositions() }
    val separatorColor = colors.separator
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = GroupedDefaults.SectionMargin),
    ) {
        if (headerContent != null || header != null) {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 7.dp)) {
                CompositionLocalProvider(
                    LocalContentColor provides colors.secondaryLabel,
                    LocalTextStyle provides typography.footnote,
                ) {
                    if (headerContent != null) headerContent() else Text(header!!.uppercase())
                }
            }
        }
        // A real Column (so rows get ColumnScope — weight/align work) whose
        // arrangement records each row's top edge; the draw pass then puts a
        // hairline above every visible row except the first. Positions are
        // state read only in draw, so separators follow rows that resize.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(GroupedDefaults.SectionCornerRadius))
                .background(colors.secondaryGroupedBackground)
                .drawWithContent {
                    drawContent()
                    val stroke = 1f // one physical pixel, like UIKit's hairline
                    val inset = separatorInset.toPx()
                    val (startX, endX) = if (layoutDirection == LayoutDirection.Ltr) {
                        inset to size.width
                    } else {
                        0f to size.width - inset
                    }
                    for (y in separators.drawn) {
                        val yy = y - stroke / 2f
                        drawLine(separatorColor, Offset(startX, yy), Offset(endX, yy), strokeWidth = stroke)
                    }
                },
            verticalArrangement = separators.arrangement,
        ) {
            CompositionLocalProvider(
                LocalContentColor provides colors.label,
                LocalTextStyle provides typography.body,
            ) {
                content()
            }
        }
        if (footerContent != null || footer != null) {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp)) {
                CompositionLocalProvider(
                    LocalContentColor provides colors.secondaryLabel,
                    LocalTextStyle provides typography.footnote,
                ) {
                    if (footerContent != null) footerContent() else Text(footer!!)
                }
            }
        }
    }
}

/**
 * Top-aligned, gap-free vertical arrangement that remembers where each
 * non-empty row starts (all but the first get a separator).
 */
private class SeparatorPositions {
    var drawn by mutableStateOf(IntArray(0))
    private var last = IntArray(0)

    val arrangement = object : Arrangement.Vertical {
        override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
            val ys = ArrayList<Int>(sizes.size)
            var y = 0
            var seenVisible = false
            sizes.forEachIndexed { index, size ->
                outPositions[index] = y
                if (size > 0) {
                    if (seenVisible) ys += y
                    seenVisible = true
                }
                y += size
            }
            val newYs = ys.toIntArray()
            // Compare against a plain field so layout never reads the state
            // it writes (that would re-trigger measurement).
            if (!newYs.contentEquals(last)) {
                last = newYs
                drawn = newYs
            }
        }
    }
}

/**
 * Base grouped row: 44dp min height, 16dp side padding, optional leading
 * icon/content and trailing accessory. [content] fills the middle; plain
 * `Text` inside it uses `body` in `label` colour (`tertiaryLabel` when
 * disabled). Tappable when [onClick] is set (NavigationLink / Button rows).
 */
@Composable
fun GroupedRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    contentPadding: PaddingValues = GroupedDefaults.RowPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = DrokpoTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = GroupedDefaults.RowMinHeight)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) colors.label else colors.tertiaryLabel) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = content)
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
            }
        }
    }
}

/** Leading row icon in the accent tint (SwiftUI `Label` in a list row). */
@Composable
private fun RowIcon(icon: ImageVector, tint: Color) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
}

/**
 * "Title ……… value" row (ProfileView's `row(_:_:)`): value in `.secondary`,
 * `—` when missing.
 */
@Composable
fun GroupedValueRow(
    title: String,
    value: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    GroupedRow(modifier = modifier, onClick = onClick) {
        Text(title, modifier = Modifier.padding(end = 16.dp))
        Text(
            text = value ?: "—",
            color = DrokpoTheme.colors.secondaryLabel,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * `NavigationLink` row: title, optional secondary [value], and a tertiary
 * disclosure chevron.
 */
@Composable
fun GroupedNavigationRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = DrokpoTheme.colors
    GroupedRow(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        leading = icon?.let { { RowIcon(it, colors.accent) } },
        trailing = {
            if (value != null) {
                Text(value, color = colors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = null,
                tint = colors.tertiaryLabel,
                modifier = Modifier.size(14.dp),
            )
        },
    ) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * `Toggle("…", isOn:)` row. The whole row toggles (accessibility role Switch);
 * the switch track uses the accent colour.
 */
@Composable
fun GroupedToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    subtitle: String? = null,
) {
    GroupedToggleRow(checked, onCheckedChange, modifier, enabled) {
        Column {
            Text(title)
            if (subtitle != null) {
                Text(subtitle, style = DrokpoTheme.typography.footnote, color = DrokpoTheme.colors.secondaryLabel)
            }
        }
    }
}

/** [GroupedToggleRow] with a custom label (e.g. onboarding's long terms text in `.footnote`). */
@Composable
fun GroupedToggleRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable () -> Unit,
) {
    GroupedRow(
        modifier = modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        enabled = enabled,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                colors = drokpoSwitchColors(),
            )
        },
    ) {
        Box(Modifier.padding(vertical = 5.dp)) { label() }
    }
}

/** iOS-like switch colours: accent track when on, grey track and no outline when off. */
@Composable
fun drokpoSwitchColors() = DrokpoTheme.colors.let { c ->
    SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = c.accent,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = Color.White,
        uncheckedTrackColor = if (c.isDark) c.systemGray4 else c.systemGray5,
        uncheckedBorderColor = if (c.isDark) Color.Transparent else c.systemGray4,
    )
}

/**
 * `Button("…")` inside a List: a tinted text row. [destructive] uses the
 * system red (`role: .destructive`), e.g. "Delete account".
 */
@Composable
fun GroupedButtonRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val colors = DrokpoTheme.colors
    GroupedRow(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled && !loading,
        trailing = if (loading) ({ Spinner() }) else null,
    ) {
        Text(
            title,
            color = when {
                !enabled -> colors.tertiaryLabel
                destructive -> colors.destructive
                else -> colors.accent
            },
        )
    }
}

/**
 * `Link(destination:)` row (Settings → "Privacy policy"): accent title and a
 * secondary ↗ arrow. Pair with `openInAppBrowser`.
 */
@Composable
fun GroupedExternalLinkRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DrokpoTheme.colors
    GroupedRow(
        modifier = modifier,
        onClick = onClick,
        trailing = {
            Icon(
                Icons.Filled.NorthEast,
                contentDescription = null,
                tint = colors.secondaryLabel,
                modifier = Modifier.size(16.dp),
            )
        },
    ) {
        Text(title, color = colors.accent)
    }
}

/**
 * Multi-select row (onboarding's `MultiSelectRow`): primary title, accent
 * checkmark when selected; the whole row toggles.
 */
@Composable
fun GroupedCheckmarkRow(
    title: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    GroupedRow(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        trailing = {
            if (checked) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = DrokpoTheme.colors.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    ) {
        Text(title)
    }
}

/**
 * `Picker` in a Form (menu style): title on the left, the current choice in
 * `.secondary` with ⇕ on the right; tapping opens a menu with a checkmark on
 * the selected option. Include the "Select"/"Skip" entry in [options] the way
 * iOS tags it (e.g. `listOf("") + regions` with `optionLabel = { it.ifEmpty { "Select" } }`).
 * A null [title] mirrors `.labelsHidden()`: only the accent-coloured choice shows.
 */
@Composable
fun <T> GroupedPickerRow(
    title: String?,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionLabel: (T) -> String = { it.toString() },
    enabled: Boolean = true,
) {
    val colors = DrokpoTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val valueColor = if (title == null) colors.accent else colors.secondaryLabel
    val menuAnchor: @Composable () -> Unit = {
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(optionLabel(selected), color = if (enabled) valueColor else colors.tertiaryLabel, maxLines = 1)
                Icon(
                    Icons.Filled.UnfoldMore,
                    contentDescription = null,
                    tint = if (enabled) valueColor else colors.tertiaryLabel,
                    modifier = Modifier.padding(start = 2.dp).size(18.dp),
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        leadingIcon = {
                            if (option == selected) {
                                Icon(Icons.Filled.Check, contentDescription = "Selected", modifier = Modifier.size(20.dp))
                            } else {
                                Spacer(Modifier.size(20.dp))
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
    GroupedRow(
        modifier = modifier,
        onClick = { expanded = true },
        enabled = enabled,
        trailing = if (title != null) ({ menuAnchor() }) else null,
    ) {
        if (title != null) Text(title) else menuAnchor()
    }
}

/**
 * Borderless `TextField` in a Form row, with placeholder text. For iOS
 * `TextField(…, axis: .vertical).lineLimit(3...6)` pass `singleLine = false,
 * minLines = 3, maxLines = 6`.
 */
@Composable
fun GroupedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val colors = DrokpoTheme.colors
    val textStyle = DrokpoTheme.typography.body.copy(color = if (enabled) colors.label else colors.tertiaryLabel)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = GroupedDefaults.RowMinHeight),
        enabled = enabled,
        textStyle = textStyle,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        cursorBrush = SolidColor(colors.accent),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(GroupedDefaults.RowPadding),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, style = textStyle, color = colors.placeholderText)
                }
                inner()
            }
        },
    )
}

@DrokpoPreviews
@Composable
private fun GroupedPreview() {
    DrokpoTheme {
        GroupedForm {
            GroupedSection(header = "Appearance") {
                Box(Modifier.padding(12.dp)) {
                    SegmentedPicker(
                        options = listOf("System", "Light", "Dark"),
                        selected = "System",
                        onSelect = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            GroupedSection(header = "About") {
                GroupedExternalLinkRow("Privacy policy", onClick = {})
                GroupedValueRow("Version", "1.1 (36)")
            }
            GroupedSection(header = "Privacy & activity") {
                GroupedNavigationRow("Blocked users", onClick = {})
                GroupedNavigationRow("Messages you've sent", onClick = {}, icon = Icons.Outlined.Notifications)
            }
            GroupedSection(
                header = "Discovery preferences",
                footer = "Your profile can appear in other people's swipe deck.",
            ) {
                GroupedToggleRow("Show me in Discover", checked = true, onCheckedChange = {})
                GroupedValueRow("Age range", "18–40")
                GroupedPickerRow(
                    title = "Region",
                    options = listOf("", "Dharamshala", "Kathmandu"),
                    selected = "",
                    onSelect = {},
                    optionLabel = { it.ifEmpty { "Select" } },
                )
                GroupedCheckmarkRow("Tibetan", checked = true, onClick = {})
                GroupedTextField(value = "", onValueChange = {}, placeholder = "Your name")
            }
            GroupedSection(header = "Account") {
                GroupedButtonRow("Sign out", onClick = {})
                GroupedButtonRow("Delete account", onClick = {}, destructive = true)
            }
        }
    }
}
