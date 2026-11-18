package dev.glowcam

import java.text.SimpleDateFormat
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoInfoTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val millis = ZonedDateTime.of(2026, 5, 7, 14, 32, 10, 0, zone).toInstant().toEpochMilli()
    private val early = ZonedDateTime.of(2026, 1, 2, 3, 4, 0, 0, zone).toInstant().toEpochMilli()

    private fun fmt(pattern: String, locale: Locale = Locale.US) =
        SimpleDateFormat(pattern, locale).apply { timeZone = TimeZone.getTimeZone(zone) }

    private val date = fmt("M/d")
    private val time24 = fmt("HH:mm")
    private val time12 = fmt("h:mm a")

    @Test
    fun dateTimeAndPlace() =
        assertEquals("5/7 14:32・台北市 信義區", PhotoInfo.caption(millis, "台北市 信義區", date, time24))

    @Test
    fun followsTheGivenTimeFormat() {
        assertEquals("5/7 14:32", PhotoInfo.caption(millis, null, date, time24))
        assertEquals("5/7 2:32 PM", PhotoInfo.caption(millis, null, date, time12))
        assertEquals("1/2 3:04 AM", PhotoInfo.caption(early, null, date, time12))
    }

    @Test
    fun followsTheGivenDateFormat() {
        assertEquals("07/05 14:32", PhotoInfo.caption(millis, null, fmt("dd/MM"), time24)) // day first locales
        assertEquals("2026年5月7日 14:32", PhotoInfo.caption(millis, null, fmt("y年M月d日", Locale.TAIWAN), time24))
    }

    @Test
    fun blankPlaceIsOmitted() {
        assertEquals("5/7 14:32", PhotoInfo.caption(millis, "  ", date, time24))
    }

    @Test
    fun emptyWhenNoDate() {
        assertEquals("", PhotoInfo.caption(null, "台北市", date, time24))
        assertEquals("", PhotoInfo.caption(0L, "台北市", date, time24))
        assertEquals("", PhotoInfo.caption(null, null, date, time24))
    }

    @Test
    fun yearIsShownOnlyForOtherYears() {
        val now = ZonedDateTime.of(2026, 11, 19, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val sameYear = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val lastYear = ZonedDateTime.of(2025, 12, 31, 23, 59, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(false, PhotoInfo.showsYear(sameYear, now, zone))
        assertEquals(true, PhotoInfo.showsYear(lastYear, now, zone))
        assertEquals(false, PhotoInfo.showsYear(null, now, zone))
        assertEquals(false, PhotoInfo.showsYear(0L, now, zone))
    }

    @Test
    fun yearBoundaryUsesTheGivenZone() {
        // 2026-01-01 00:30 in Taipei is still 2025 in UTC.
        val t = ZonedDateTime.of(2026, 1, 1, 0, 30, 0, 0, zone).toInstant().toEpochMilli()
        val now = ZonedDateTime.of(2026, 6, 1, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(false, PhotoInfo.showsYear(t, now, zone))
        assertEquals(true, PhotoInfo.showsYear(t, now, ZoneId.of("UTC")))
    }

    @Test
    fun placeNameJoinsDistinctParts() {
        assertEquals("台北市 信義區", PhotoInfo.placeName("台北市", "信義區"))
        assertEquals("台北市", PhotoInfo.placeName("台北市", null))
        assertEquals("信義區", PhotoInfo.placeName(null, "信義區"))
        assertEquals("東京", PhotoInfo.placeName("東京", "東京"))
        assertNull(PhotoInfo.placeName(null, " "))
    }
}
