package app.drokpo.android.features.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.drokpo.android.ui.theme.DrokpoTheme

// iOS-looking sliders for the Discovery preferences section: a 4dp rounded
// track (accent fill, grey remainder), white 28dp thumbs with a soft shadow and
// no tick marks — Material's default slider looks nothing like UISlider.

/**
 * Two-thumb age range (iOS `RangeSliderRow`, which builds one from two
 * SwiftUI sliders to stay dependency-free; Material 3 has a real RangeSlider).
 * Keeps iOS's rule that the thumbs stay at least [minGap] apart:
 * minimum ≤ maximum − 1 and maximum ≥ minimum + 1.
 */
@Composable
internal fun RangeSliderRow(
    range: ClosedFloatingPointRange<Float>,
    onRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    bounds: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    minGap: Float = 1f,
    startLabel: String = "Minimum age",
    endLabel: String = "Maximum age",
) {
    RangeSlider(
        value = range,
        onValueChange = { proposed -> onRangeChange(constrainRange(range, proposed, bounds, minGap)) },
        modifier = modifier.fillMaxWidth(),
        valueRange = bounds,
        // iOS labels its two sliders "Minimum age" / "Maximum age". Material's thumb node merges
        // these after its own "Range start" / "Range end".
        startThumb = { SliderThumb(label = startLabel) },
        endThumb = { SliderThumb(label = endLabel) },
        track = { state ->
            SliderTrack(
                startFraction = fractionOf(state.activeRangeStart, state.valueRange),
                endFraction = fractionOf(state.activeRangeEnd, state.valueRange),
            )
        },
    )
}

/** `Slider(value:in:step:)` with the iOS look; [steps] = intermediate stops like Material's. */
@Composable
internal fun DrokpoSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    contentDescription: String? = null,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        valueRange = valueRange,
        steps = steps,
        thumb = { SliderThumb() },
        track = { state ->
            SliderTrack(startFraction = 0f, endFraction = fractionOf(state.value, state.valueRange))
        },
    )
}

@Composable
private fun SliderThumb(label: String? = null) {
    Box(
        Modifier
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
            .size(28.dp)
            .shadow(elevation = 3.dp, shape = CircleShape)
            .background(Color.White, CircleShape),
    )
}

@Composable
private fun SliderTrack(startFraction: Float, endFraction: Float) {
    val colors = DrokpoTheme.colors
    val active = colors.accent
    // UISlider's maximum track: systemFill (#787880 at 20% light / 36% dark).
    val inactive = if (colors.isDark) Color(0x5C787880) else Color(0x33787880)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(4.dp),
    ) {
        val y = size.height / 2f
        val stroke = size.height
        drawLine(inactive, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke, cap = StrokeCap.Round)
        val from = size.width * startFraction
        val to = size.width * endFraction
        if (to > from) {
            drawLine(active, Offset(from, y), Offset(to, y), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

private fun fractionOf(value: Float, range: ClosedFloatingPointRange<Float>): Float {
    val span = range.endInclusive - range.start
    if (span <= 0f) return 0f
    return ((value - range.start) / span).coerceIn(0f, 1f)
}

/**
 * The iOS RangeSliderRow bindings: moving the lower thumb sets
 * `min(new, upper − gap)…upper`; moving the upper thumb sets
 * `lower…max(new, lower + gap)`. Both stay inside [bounds].
 */
internal fun constrainRange(
    current: ClosedFloatingPointRange<Float>,
    proposed: ClosedFloatingPointRange<Float>,
    bounds: ClosedFloatingPointRange<Float>,
    minGap: Float = 1f,
): ClosedFloatingPointRange<Float> {
    var lower = current.start
    var upper = current.endInclusive
    if (proposed.start != current.start) {
        lower = minOf(proposed.start, upper - minGap)
    }
    if (proposed.endInclusive != current.endInclusive) {
        upper = maxOf(proposed.endInclusive, lower + minGap)
    }
    lower = lower.coerceIn(bounds.start, (bounds.endInclusive - minGap).coerceAtLeast(bounds.start))
    upper = upper.coerceIn((lower + minGap).coerceAtMost(bounds.endInclusive), bounds.endInclusive)
    return lower..upper
}
