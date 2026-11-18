package dev.glowcam

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoInfoTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val millis = ZonedDateTime.of(2026, 5, 7, 14, 32, 10, 0, zone).toInstant().toEpochMilli()
    private val morning = ZonedDateTime.of(2026, 1, 2, 3, 4, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun dateAndPlace() = assertEquals("05/07 14:32・台北市 信義區", PhotoInfo.caption(millis, "台北市 信義區", zone))

    @Test
    fun dateOnlyWhenNoPlace() {
        assertEquals("05/07 14:32", PhotoInfo.caption(millis, null, zone))
        assertEquals("05/07 14:32", PhotoInfo.caption(millis, "  ", zone))
    }

    @Test
    fun emptyWhenNoDate() {
        assertEquals("", PhotoInfo.caption(null, "台北市", zone))
        assertEquals("", PhotoInfo.caption(0L, "台北市", zone))
        assertEquals("", PhotoInfo.caption(null, null, zone))
    }

    @Test
    fun zeroPaddedFields() = assertEquals("01/02 03:04", PhotoInfo.caption(morning, null, zone))

    @Test
    fun placeNameJoinsDistinctParts() {
        assertEquals("台北市 信義區", PhotoInfo.placeName("台北市", "信義區"))
        assertEquals("台北市", PhotoInfo.placeName("台北市", null))
        assertEquals("信義區", PhotoInfo.placeName(null, "信義區"))
        assertEquals("東京", PhotoInfo.placeName("東京", "東京"))
        assertNull(PhotoInfo.placeName(null, " "))
    }
}
