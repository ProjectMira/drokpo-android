package app.drokpo.android.features.communities

import app.drokpo.android.features.communityhome.isSelectableDay
import app.drokpo.android.features.communityhome.pickedDay
import app.drokpo.android.features.communityhome.pickerMillisFor
import app.drokpo.android.features.communityhome.withPickedDay
import app.drokpo.android.features.communityhome.withPickedTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The event date/time picker conversions. The Material DatePicker speaks
 * UTC-midnight millis; the event is an Instant shown in the device zone. Using
 * the wrong zone on either side shifts the day by one for users away from UTC.
 */
class CommunityPostComposerDatesTest {
    private val losAngeles: ZoneId = ZoneId.of("America/Los_Angeles")
    private val kolkata: ZoneId = ZoneId.of("Asia/Kolkata")

    private fun utcMidnight(day: String): Long =
        LocalDate.parse(day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun at(iso: String): Instant = Instant.parse(iso)

    @Test
    fun pickerMillisAreTheLocalDayNotTheUtcDay() {
        // 20:00Z is already the 8th in Kolkata (01:30 IST).
        assertEquals(utcMidnight("2026-10-08"), pickerMillisFor(at("2026-10-07T20:00:00Z"), kolkata))
        // 03:00Z on the 8th is still the 7th in Los Angeles (20:00 PDT).
        assertEquals(utcMidnight("2026-10-07"), pickerMillisFor(at("2026-10-08T03:00:00Z"), losAngeles))
        // The 25-hour day DST ends on (23:30 PST is 07:30Z the next day).
        assertEquals(utcMidnight("2026-11-01"), pickerMillisFor(at("2026-11-02T07:30:00Z"), losAngeles))
    }

    @Test
    fun pickedDayRoundTripsThroughThePickerMillis() {
        listOf(losAngeles, kolkata, ZoneOffset.UTC).forEach { zone ->
            listOf("2026-10-07T20:00:00Z", "2026-10-08T03:00:00Z", "2026-03-08T10:30:00Z").forEach { iso ->
                val date = at(iso)
                assertEquals("$zone $iso", date.atZone(zone).toLocalDate(), pickedDay(pickerMillisFor(date, zone)))
            }
        }
    }

    @Test
    fun pickingADayKeepsTheLocalTime() {
        // 20:00 PDT on the 7th → 20:00 PDT on the 20th.
        assertEquals(
            at("2026-10-21T03:00:00Z"),
            withPickedDay(at("2026-10-08T03:00:00Z"), utcMidnight("2026-10-20"), losAngeles),
        )
        // 01:30 IST on the 8th → 01:30 IST on the 10th.
        assertEquals(
            at("2026-10-09T20:00:00Z"),
            withPickedDay(at("2026-10-07T20:00:00Z"), utcMidnight("2026-10-10"), kolkata),
        )
    }

    @Test
    fun pickingADayAcrossADstChangeKeepsTheWallClockTime() {
        // 20:00 PDT (UTC−7) → 20:00 PST (UTC−8) once DST has ended.
        assertEquals(
            at("2026-11-06T04:00:00Z"),
            withPickedDay(at("2026-10-08T03:00:00Z"), utcMidnight("2026-11-05"), losAngeles),
        )
        // 02:30 doesn't exist on the spring-forward day: it lands on 03:30 PDT, same day.
        val sprung = withPickedDay(at("2026-03-01T10:30:00Z"), utcMidnight("2026-03-08"), losAngeles)
        assertEquals(at("2026-03-08T10:30:00Z"), sprung)
        assertEquals(LocalDate.parse("2026-03-08"), sprung.atZone(losAngeles).toLocalDate())
        assertEquals(LocalTime.of(3, 30), sprung.atZone(losAngeles).toLocalTime())
    }

    @Test
    fun pickingATimeKeepsTheLocalDay() {
        // 01:30 IST on the 8th → 23:45 IST on the 8th (not the UTC day's 23:45).
        assertEquals(at("2026-10-08T18:15:00Z"), withPickedTime(at("2026-10-07T20:00:00Z"), 23, 45, kolkata))
        // 20:00 PDT on the 7th → 09:05 PDT on the 7th.
        assertEquals(at("2026-10-07T16:05:00Z"), withPickedTime(at("2026-10-08T03:00:00Z"), 9, 5, losAngeles))
        // On the spring-forward day: noon PDT → 01:15 PST, still the 8th.
        assertEquals(at("2026-03-08T09:15:00Z"), withPickedTime(at("2026-03-08T19:00:00Z"), 1, 15, losAngeles))
    }

    @Test
    fun pickingATimeDropsSeconds() {
        val picked = withPickedTime(at("2026-10-07T20:00:42.5Z"), 21, 0, ZoneOffset.UTC)
        assertEquals(at("2026-10-07T21:00:00Z"), picked)
    }

    @Test
    fun daysBeforeTodayAreNotSelectable() {
        val today = LocalDate.parse("2026-10-07")
        assertFalse(isSelectableDay(utcMidnight("2026-10-06"), today))
        assertTrue(isSelectableDay(utcMidnight("2026-10-07"), today))
        assertTrue(isSelectableDay(utcMidnight("2027-01-01"), today))
    }

    @Test
    fun todayIsTheLocalDayEvenWhereUtcIsStillYesterday() {
        // 20:00Z on the 7th: Kolkata is already on the 8th, so the 7th is in the past there.
        val now = at("2026-10-07T20:00:00Z")
        val today = now.atZone(kolkata).toLocalDate()
        assertTrue(isSelectableDay(pickerMillisFor(now, kolkata), today))
        assertFalse(isSelectableDay(utcMidnight("2026-10-07"), today))
    }
}
