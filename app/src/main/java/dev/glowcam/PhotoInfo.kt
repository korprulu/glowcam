package dev.glowcam

import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date

/** Builds the caption shown above a photo: `<date> <time>・<place>` in the system's date/time format. */
object PhotoInfo {

    /**
     * - date and place  -> `11月19日 下午2:32・台北市`
     * - date only       -> `11月19日 下午2:32`
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

    /** Photos from an earlier (or later) calendar year than [nowMillis] show the year as well. */
    fun showsYear(takenMillis: Long?, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (takenMillis == null || takenMillis <= 0L) return false
        return Instant.ofEpochMilli(takenMillis).atZone(zone).year != Instant.ofEpochMilli(nowMillis).atZone(zone).year
    }

    private val DISTRICT_SUFFIXES = listOf("區", "区", " District", " Ward", "-ku")

    private fun looksLikeDistrict(name: String) = DISTRICT_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }

    /**
     * Place label from geocoder address levels, [candidates] ordered most to least preferred
     * (city, county, province ...). Never goes below city: a candidate that looks like a district
     * is skipped when a better one exists.
     */
    fun cityName(candidates: List<String?>): String? {
        val names = candidates.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        return names.firstOrNull { !looksLikeDistrict(it) } ?: names.firstOrNull()
    }
}
