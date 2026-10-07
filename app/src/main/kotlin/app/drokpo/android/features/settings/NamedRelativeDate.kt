package app.drokpo.android.features.settings

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs

// Port of Foundation's `Date.RelativeFormatStyle(presentation: .named, unitsStyle: .wide)`
// (swift-foundation Date+RelativeFormatStyle.swift, `_largestNonZeroComponent` and helpers),
// English only. Checked against macOS Foundation output for ~128k (date, now) pairs across
// three time zones and both week starts.

/** `ICURelativeDateFormatter.sortedAllowedComponents`: year, month, weekOfMonth, day, hour, minute, second. */
private val relativeUnits = listOf(
    ChronoUnit.YEARS,
    ChronoUnit.MONTHS,
    ChronoUnit.WEEKS,
    ChronoUnit.DAYS,
    ChronoUnit.HOURS,
    ChronoUnit.MINUTES,
    ChronoUnit.SECONDS,
)

/** One calendar component and its signed value (negative = [date] is in the past). */
internal data class RelativeComponent(val unit: ChronoUnit, val value: Long)

/**
 * What iOS `Text(date, format: .relative(presentation: .named))` shows at [relativeTo]:
 * "now", "30 seconds ago", "2 hours ago", "yesterday", "3 days ago", "last week",
 * "2 months ago", "last year", "in 5 minutes", ….
 *
 * Foundation does not simply truncate to the largest unit:
 * - hours, minutes and seconds round half up into the next unit, so 1h30m is "2 hours ago"
 *   and anything from 23h30m is "yesterday";
 * - days, weeks, months and years count calendar boundaries crossed, so 9 pm yesterday
 *   seen at 1 pm today is still "16 hours ago", but 37 hours ago at noon is "2 days ago",
 *   and a date in an earlier calendar week is "last week" (weeks start on [firstDayOfWeek]).
 */
internal fun namedRelativeString(
    date: Instant,
    relativeTo: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    firstDayOfWeek: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek,
): String = formatNamed(namedRelativeComponent(date, relativeTo, zone, firstDayOfWeek))

internal fun namedRelativeComponent(
    date: Instant,
    relativeTo: Instant,
    zone: ZoneId,
    firstDayOfWeek: DayOfWeek,
): RelativeComponent {
    val dest = date.atZone(zone)
    // `refDate = destDate + (refDate − destDate).rounded(toNearestOrAwayFromZero)`: whole seconds apart.
    val ref = date.plusSeconds(Duration.between(date, relativeTo).roundedSeconds()).atZone(zone)
    val largest = components(ref, dest, relativeUnits).firstNonZero() ?: RelativeComponent(ChronoUnit.SECONDS, 0)
    return when (largest.unit) {
        ChronoUnit.HOURS, ChronoUnit.MINUTES, ChronoUnit.SECONDS -> roundedLargest(ref, dest, largest.unit)
        else -> alignedLargest(largest.unit, ref, dest, firstDayOfWeek)
    } ?: largest
}

/** `_roundedLargestComponentValue`: round the time unit by the next smaller one, carrying upwards. */
private fun roundedLargest(ref: ZonedDateTime, dest: ZonedDateTime, largestUnit: ChronoUnit): RelativeComponent? {
    val units = relativeUnits.subList(relativeUnits.indexOf(largestUnit), relativeUnits.size)
    val comps = components(ref, dest, units)
    // ref and dest are whole seconds apart, so the nanosecond remainder Foundation also checks is 0.
    val unit = comps.firstNonZero()?.unit ?: ChronoUnit.SECONDS
    var value = comps.first { it.unit == unit }.value
    val smaller = comps.getOrNull(comps.indexOfFirst { it.unit == unit } + 1)?.value ?: 0L
    // `range(of: smaller, in: unit).count` is 60 for minutes-in-an-hour and seconds-in-a-minute.
    if (abs(smaller) * 2 >= 60) value += if (smaller > 0) 1 else -1
    val shifted = dest.minus(value, unit)
    val carried = components(shifted, dest, relativeUnits).firstNonZero()
    return if (carried != null && carried.unit != unit) carried else RelativeComponent(unit, value)
}

/**
 * `_alignedComponentValue`: measure from the start (future) or the last second (past) of the
 * reference's day / week / month / year.
 */
private fun alignedLargest(
    unit: ChronoUnit,
    ref: ZonedDateTime,
    dest: ZonedDateTime,
    firstDayOfWeek: DayOfWeek,
): RelativeComponent? {
    val day = ref.toLocalDate()
    val startDay = when (unit) {
        ChronoUnit.WEEKS -> day.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        ChronoUnit.MONTHS -> day.withDayOfMonth(1)
        ChronoUnit.YEARS -> day.withDayOfYear(1)
        else -> day
    }
    val start = startDay.atStartOfDay(ref.zone)
    val from = if (ref.isBefore(dest)) start else startDay.plus(1, unit).atStartOfDay(ref.zone).minusSeconds(1)
    return components(from, dest, relativeUnits).firstNonZero()
}

/**
 * `Calendar.dateComponents(_:from:to:)`: for each unit in turn, the most whole units that can
 * be added (subtracted, going back) without passing [to], then on to the next unit from there.
 */
private fun components(from: ZonedDateTime, to: ZonedDateTime, units: List<ChronoUnit>): List<RelativeComponent> {
    var cursor = from
    return units.map { unit ->
        val amount = wholeUnitsBetween(cursor, to, unit)
        cursor = cursor.plus(amount, unit)
        RelativeComponent(unit, amount)
    }
}

private fun wholeUnitsBetween(from: ZonedDateTime, to: ZonedDateTime, unit: ChronoUnit): Long {
    if (from.isEqual(to)) return 0
    val forward = to.isAfter(from)
    val step = if (forward) 1L else -1L
    fun fits(amount: Long): Boolean {
        val reached = from.plus(amount, unit)
        return if (forward) !reached.isAfter(to) else !reached.isBefore(to)
    }
    var amount = unit.between(from, to)
    while (amount != 0L && !fits(amount)) amount -= step
    while (fits(amount + step)) amount += step
    return amount
}

private fun List<RelativeComponent>.firstNonZero(): RelativeComponent? = firstOrNull { it.value != 0L }

/** Whole seconds, half away from zero (`rounded(increment: 1, rule: .toNearestOrAwayFromZero)`). */
private fun Duration.roundedSeconds(): Long {
    val nanos = nano.toLong() // the value is seconds + nanos / 1e9, with 0 ≤ nanos < 1e9
    if (nanos == 0L) return seconds
    return if (!isNegative) {
        if (nanos >= 500_000_000L) seconds + 1 else seconds
    } else {
        // Negative and between `seconds` and `seconds + 1`: away from zero is `seconds`.
        if (1_000_000_000L - nanos >= 500_000_000L) seconds else seconds + 1
    }
}

/** ICU `ureldatefmt_format` (English, wide, named). */
private fun formatNamed(component: RelativeComponent): String {
    val (unit, value) = component
    val name = when (unit) {
        ChronoUnit.YEARS -> "year"
        ChronoUnit.MONTHS -> "month"
        ChronoUnit.WEEKS -> "week"
        ChronoUnit.DAYS -> "day"
        ChronoUnit.HOURS -> "hour"
        ChronoUnit.MINUTES -> "minute"
        else -> "second"
    }
    when {
        value == 0L -> return when (unit) {
            ChronoUnit.SECONDS -> "now"
            ChronoUnit.DAYS -> "today"
            else -> "this $name"
        }
        abs(value) == 1L && unit == ChronoUnit.DAYS -> return if (value < 0) "yesterday" else "tomorrow"
        abs(value) == 1L && (unit == ChronoUnit.WEEKS || unit == ChronoUnit.MONTHS || unit == ChronoUnit.YEARS) ->
            return if (value < 0) "last $name" else "next $name"
    }
    val count = abs(value)
    val phrase = if (count == 1L) "1 $name" else "$count ${name}s"
    return if (value < 0) "$phrase ago" else "in $phrase"
}
