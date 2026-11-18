package dev.glowcam

object VersionInfo {
    /** `1.6.0 (11)`; missing parts are left out, nothing known gives an em dash. */
    fun format(versionName: String?, versionCode: Long?): String {
        val name = versionName?.trim().orEmpty()
        return when {
            name.isNotEmpty() && versionCode != null -> "$name ($versionCode)"
            name.isNotEmpty() -> name
            versionCode != null -> "($versionCode)"
            else -> "—"
        }
    }
}
