package app.drokpo.android.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit
import java.util.Locale

// Timestamps travel as strings on the wire: the backend returns Firestore
// timestamps via FastAPI's jsonable_encoder (`datetime.isoformat()`, e.g.
// "2026-10-06T12:34:56.123456+00:00", or without the fraction when it is
// zero), and client-authored values (eventAt, publishedAt, dob) are ISO
// strings too. The models keep them as String exactly like the Swift
// structs; these helpers are the ports of the Swift formatters that parse them.

/**
 * Swift `ISO8601DateFormatter()` (default `.withInternetDateTime`): full date,
 * `T`, HH:mm:ss, and a mandatory zone (`Z` or ±HH:MM). No fractional seconds.
 */
private val internetDateTime: DateTimeFormatter = DateTimeFormatterBuilder()
    .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
    .appendOffset("+HH:MM", "Z")
    .toFormatter(Locale.US)
    .withResolverStyle(ResolverStyle.STRICT)

/** Swift `ISO8601DateFormatter` with `[.withInternetDateTime, .withFractionalSeconds]`. */
private val internetDateTimeFractional: DateTimeFormatter = DateTimeFormatterBuilder()
    .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
    .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
    .appendOffset("+HH:MM", "Z")
    .toFormatter(Locale.US)
    .withResolverStyle(ResolverStyle.STRICT)

private val localDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss", Locale.US).withResolverStyle(ResolverStyle.STRICT)

private val localDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.US).withResolverStyle(ResolverStyle.STRICT)

private fun parseWith(formatter: DateTimeFormatter, text: String): Instant? =
    try {
        formatter.parse(text, Instant::from)
    } catch (e: DateTimeParseException) {
        null
    } catch (e: java.time.DateTimeException) {
        null
    }

/** `isoFormatter.date(from:)` — ISO 8601 without fractional seconds. */
internal fun parseInternetDateTime(text: String): Instant? = parseWith(internetDateTime, text)

/**
 * `isoFormatter.date(from:) ?? isoFractionalFormatter.date(from:)` — the pair
 * every Swift model with a server timestamp uses (eventAt, createdAt).
 */
fun parseIso8601(text: String): Instant? =
    parseWith(internetDateTime, text) ?: parseWith(internetDateTimeFractional, text)

/**
 * `publishedAt` arrives as full ISO 8601 (with or without offset) or a bare
 * date, depending on what the source article exposed. Port of
 * `NewsCard.publishedDate`: offset form first, then a zone-less date-time and
 * a bare date, both read in the device's time zone (DateFormatter's default).
 */
internal fun parsePublishedDate(text: String, zone: ZoneId = ZoneId.systemDefault()): Instant? {
    parseInternetDateTime(text)?.let { return it }
    try {
        return LocalDateTime.parse(text, localDateTimeFormatter).atZone(zone).toInstant()
    } catch (e: DateTimeParseException) {
        // fall through
    }
    try {
        return LocalDate.parse(text, localDateFormatter).atStartOfDay(zone).toInstant()
    } catch (e: DateTimeParseException) {
        // fall through
    }
    return null
}

// MARK: - Date of birth

/** `Profile.dobFormatter`: "yyyy-MM-dd" in UTC. */
internal val dobDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.US)
        .withResolverStyle(ResolverStyle.STRICT)
        .withZone(ZoneOffset.UTC)

/** `Profile.dobFormatter.date(from:)` — midnight UTC of the given day. */
internal fun parseDob(dob: String): Instant? =
    try {
        LocalDate.parse(dob, dobDateFormatter).atStartOfDay(ZoneOffset.UTC).toInstant()
    } catch (e: DateTimeParseException) {
        null
    }

/**
 * `Calendar.current.dateComponents([.year], from: date, to: .now).year`:
 * whole years between the UTC-midnight birth instant and now, counted in the
 * device's calendar/time zone.
 */
internal fun ageInYears(
    dob: String?,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): Int? {
    val date = dob?.let(::parseDob) ?: return null
    return ChronoUnit.YEARS.between(date.atZone(zone), now.atZone(zone)).toInt()
}

// MARK: - Relative dates

private val relativeUnits = listOf(
    ChronoUnit.YEARS to "y",
    ChronoUnit.MONTHS to "mo",
    ChronoUnit.WEEKS to "w",
    ChronoUnit.DAYS to "d",
    ChronoUnit.HOURS to "h",
    ChronoUnit.MINUTES to "m",
    ChronoUnit.SECONDS to "s",
)

/**
 * Port of `RelativeDateTimeFormatter` with `unitsStyle = .abbreviated`
 * (`localizedString(for:relativeTo:)`, English): the largest non-zero calendar
 * unit among year/month/week/day/hour/minute/second, rendered "2d ago" for the
 * past and "in 2d" for the future ("in 0s" when the two are equal).
 */
fun abbreviatedRelativeString(
    date: Instant,
    relativeTo: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val past = date.isBefore(relativeTo)
    val start = (if (past) date else relativeTo).atZone(zone)
    val end = (if (past) relativeTo else date).atZone(zone)
    for ((unit, suffix) in relativeUnits) {
        val amount = unit.between(start, end)
        if (amount > 0) return if (past) "$amount$suffix ago" else "in $amount$suffix"
    }
    return "in 0s"
}
