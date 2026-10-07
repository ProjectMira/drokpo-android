package app.drokpo.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.drokpo.android.ui.theme.DrokpoColors
import app.drokpo.android.ui.theme.DrokpoPreviews
import app.drokpo.android.ui.theme.DrokpoTheme

/** SwiftUI `.controlSize(_:)`. */
enum class ControlSize { Small, Regular, Large }

/** Per-size metrics shared by [ProminentButton] and [SecondaryButton]. */
object ButtonMetrics {
    fun minHeight(size: ControlSize): Dp = when (size) {
        ControlSize.Small -> 28.dp
        ControlSize.Regular -> 34.dp
        ControlSize.Large -> 50.dp
    }

    fun contentPadding(size: ControlSize): PaddingValues = when (size) {
        ControlSize.Small -> PaddingValues(horizontal = 10.dp, vertical = 4.dp)
        ControlSize.Regular -> PaddingValues(horizontal = 14.dp, vertical = 7.dp)
        ControlSize.Large -> PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    }

    /** iOS 17 bordered buttons: rounded rect whose radius grows with control size. */
    fun shape(size: ControlSize): Shape = when (size) {
        ControlSize.Small -> RoundedCornerShape(6.dp)
        ControlSize.Regular -> RoundedCornerShape(8.dp)
        ControlSize.Large -> RoundedCornerShape(12.dp)
    }

    /** `.buttonBorderShape(.capsule)`. */
    val Capsule: Shape = CircleShape
}

@Composable
private fun textStyleFor(size: ControlSize): TextStyle =
    if (size == ControlSize.Small) DrokpoTheme.typography.subheadline else DrokpoTheme.typography.body

/**
 * Shared button body: a Surface with iOS sizing whose label is swapped for a
 * spinner while [loading] (the label stays laid out, invisible, so the
 * button doesn't change size).
 */
@Composable
private fun DrokpoButtonBase(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    loading: Boolean,
    size: ControlSize,
    shape: Shape,
    containerColor: Color,
    contentColor: Color,
    spacing: Dp = 6.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier
            .heightIn(min = ButtonMetrics.minHeight(size))
            .semantics { role = Role.Button },
    ) {
        CompositionLocalProvider(LocalTextStyle provides textStyleFor(size)) {
            Box(
                Modifier.padding(ButtonMetrics.contentPadding(size)),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier.alpha(if (loading) 0f else 1f),
                    horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content,
                )
                if (loading) {
                    Spinner(color = contentColor, size = if (size == ControlSize.Small) 14.dp else 20.dp)
                }
            }
        }
    }
}

@Composable
private fun ButtonLabel(text: String, icon: ImageVector?, textStyle: TextStyle?) {
    if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Text(
        text = text,
        style = textStyle ?: LocalTextStyle.current,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

/**
 * `.buttonStyle(.borderedProminent)`: filled with [tint] (accent by default;
 * pass `DrokpoTheme.colors.brandRed` for like actions like "Like back"), white
 * label. Disabled = grey fill with tertiary label, like iOS.
 */
@Composable
fun ProminentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    size: ControlSize = ControlSize.Regular,
    tint: Color = DrokpoTheme.colors.accent,
    shape: Shape = ButtonMetrics.shape(size),
    textStyle: TextStyle? = null,
) {
    ProminentButton(onClick, modifier, enabled, loading, size, tint, shape) {
        ButtonLabel(text, icon, textStyle)
    }
}

/** [ProminentButton] with custom label content. */
@Composable
fun ProminentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    size: ControlSize = ControlSize.Regular,
    tint: Color = DrokpoTheme.colors.accent,
    shape: Shape = ButtonMetrics.shape(size),
    content: @Composable RowScope.() -> Unit,
) {
    val colors = DrokpoTheme.colors
    DrokpoButtonBase(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        loading = loading,
        size = size,
        shape = shape,
        containerColor = if (enabled) tint else colors.tertiaryFill,
        contentColor = if (enabled) colors.onAccent else colors.tertiaryLabel,
        content = content,
    )
}

/**
 * The full-width bottom call to action — onboarding's "Continue"/"Finish",
 * profile "Like back"/"Send message": a large [ProminentButton] filling the
 * width, with a spinner while [loading].
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    tint: Color = DrokpoTheme.colors.accent,
    textStyle: TextStyle? = null,
) {
    ProminentButton(
        text = text,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        loading = loading,
        icon = icon,
        size = ControlSize.Large,
        tint = tint,
        textStyle = textStyle,
    )
}

/**
 * `.buttonStyle(.bordered)`. With no [tint] (iOS: no `.tint()`), an accent
 * label on the grey secondarySystemFill ([DrokpoColors.secondaryFill]). With
 * an explicit [tint] (iOS `.tint(x)`), an x-coloured label on a translucent x
 * fill. `.tint(.secondary)` (e.g. a "Joined" button) →
 * `tint = DrokpoTheme.colors.secondaryLabel`; `.tint(.accentColor)` →
 * `tint = DrokpoTheme.colors.accent` (blue fill, not grey).
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    size: ControlSize = ControlSize.Regular,
    /** [Color.Unspecified] = untinted (grey fill, accent label). */
    tint: Color = Color.Unspecified,
    shape: Shape = ButtonMetrics.shape(size),
    textStyle: TextStyle? = null,
) {
    SecondaryButton(onClick, modifier, enabled, loading, size, tint, shape) {
        ButtonLabel(text, icon, textStyle)
    }
}

/** [SecondaryButton] with custom label content (e.g. the Google sign-in row). */
@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    size: ControlSize = ControlSize.Regular,
    /** [Color.Unspecified] = untinted (grey fill, accent label). */
    tint: Color = Color.Unspecified,
    shape: Shape = ButtonMetrics.shape(size),
    /** Gap between label children (the sign-in provider rows use 10). */
    spacing: Dp = 6.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = DrokpoTheme.colors
    val fillAlpha = if (colors.isDark) 0.24f else 0.14f
    val container = if (tint.isSpecified) tint.copy(alpha = tint.alpha * fillAlpha) else colors.secondaryFill
    DrokpoButtonBase(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        loading = loading,
        size = size,
        shape = shape,
        containerColor = if (enabled) container else colors.tertiaryFill,
        contentColor = if (enabled) tint.takeOrElse { colors.accent } else colors.tertiaryLabel,
        spacing = spacing,
        content = content,
    )
}

/** Borderless text button (`Button(…)` with `.plain`/toolbar look) in [color]. */
@Composable
fun PlainTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = DrokpoTheme.colors.accent,
    emphasized: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = color,
            disabledContentColor = DrokpoTheme.colors.tertiaryLabel,
        ),
    ) {
        Text(
            text,
            style = DrokpoTheme.typography.body.copy(
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
    }
}

/** `Button("…", role: .destructive)` without a border: system-red text. */
@Composable
fun DestructiveTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    PlainTextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        color = DrokpoTheme.colors.destructive,
    )
}

@DrokpoPreviews
@Composable
private fun ButtonsPreview() {
    DrokpoTheme {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrimaryButton("Continue", onClick = {})
            PrimaryButton("Continue", onClick = {}, loading = true)
            PrimaryButton("Finish", onClick = {}, enabled = false)
            PrimaryButton(
                "Like back",
                onClick = {},
                icon = Icons.Filled.Favorite,
                tint = DrokpoTheme.colors.brandRed,
                textStyle = DrokpoTheme.typography.headline,
            )
            SecondaryButton(
                "Continue with phone",
                onClick = {},
                icon = Icons.Filled.Phone,
                size = ControlSize.Large,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ProminentButton("Retry", onClick = {})
                SecondaryButton("Unblock", onClick = {})
                SecondaryButton(
                    "Join",
                    onClick = {},
                    size = ControlSize.Small,
                    shape = ButtonMetrics.Capsule,
                    textStyle = DrokpoTheme.typography.subheadline.copy(fontWeight = FontWeight.Bold),
                )
                SecondaryButton(
                    "Joined",
                    onClick = {},
                    size = ControlSize.Small,
                    shape = ButtonMetrics.Capsule,
                    tint = DrokpoTheme.colors.secondaryLabel,
                )
                SecondaryButton("Join", onClick = {}, size = ControlSize.Small, shape = ButtonMetrics.Capsule, loading = true)
            }
            Row {
                PlainTextButton("Done", onClick = {}, emphasized = true)
                PlainTextButton("Sign out", onClick = {})
                DestructiveTextButton("Remove", onClick = {})
            }
            CompositionLocalProvider(LocalContentColor provides DrokpoTheme.colors.label) {
                Text("Plain text for contrast")
            }
        }
    }
}
