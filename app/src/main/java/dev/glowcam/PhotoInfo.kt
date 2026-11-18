package dev.glowcam

import java.text.DateFormat
import java.util.Date

/** Builds the caption shown above a photo: `<date> <time>・<place>` in the system's date/time format. */
object PhotoInfo {

    /**
     * - date and place  -> `11/19 下午2:32・台北市 信義區`
     * - date only       -> `11/19 下午2:32`
     * - no date         -> empty (nothing is shown, even when a place exists)
     *
     * [dateFormat] and [timeFormat] come from the system (locale, 12/24-hour setting).
     */
    fun caption(takenMillis: Long?, place: String?, dateFormat: DateFormat, timeFormat: DateFormat): String {
        if (takenMillis == null || takenMillis <= 0L) return ""
        val when_ = Date(takenMillis)
        val date = dateFormat.format(when_) + " " + timeFormat.format(when_)
        val p = place?.trim().orEmpty()
        return if (p.isEmpty()) date else "$date・$p"
    }

    /** Short place label from geocoder address parts: city, then district, no duplicates. */
    fun placeName(city: String?, district: String?): String? {
        val parts = listOf(city, district).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        return parts.joinToString(" ").ifEmpty { null }
    }
}
