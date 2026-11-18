package dev.glowcam

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Formats the caption shown above a photo: `MM/DD hh:mm・place`. */
object PhotoInfo {
    private val FORMAT = DateTimeFormatter.ofPattern("MM/dd HH:mm")

    /**
     * - date and place  -> `05/07 14:32・台北市 信義區`
     * - date only       -> `05/07 14:32`
     * - no date         -> empty (nothing is shown, even when a place exists)
     */
    fun caption(takenMillis: Long?, place: String?, zone: ZoneId = ZoneId.systemDefault()): String {
        if (takenMillis == null || takenMillis <= 0L) return ""
        val date = FORMAT.format(Instant.ofEpochMilli(takenMillis).atZone(zone))
        val p = place?.trim().orEmpty()
        return if (p.isEmpty()) date else "$date・$p"
    }

    /** Short place label from geocoder address parts: city, then district, no duplicates. */
    fun placeName(city: String?, district: String?): String? {
        val parts = listOf(city, district).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        return parts.joinToString(" ").ifEmpty { null }
    }
}
