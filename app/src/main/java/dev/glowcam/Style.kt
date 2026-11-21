package dev.glowcam

import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Names of the values a style can hold. New effects only need a new key here. */
object StyleKeys {
    const val SMOOTH = "smooth"
    const val WHITEN = "whiten"
    const val ROSY = "rosy"
}

/**
 * A named preset. [values] is an open key -> number map so a style can later carry more settings
 * without changing the storage format; keys this build does not know are kept untouched.
 */
data class Style(val id: String, val name: String, val values: Map<String, Float>) {

    fun value(key: String, default: Float = 0f): Float = values[key] ?: default

    fun toBeautyParams(): BeautyParams =
        BeautyParams(value(StyleKeys.SMOOTH), value(StyleKeys.WHITEN), value(StyleKeys.ROSY)).clamped()

    fun withValues(updates: Map<String, Float>): Style = copy(values = values + updates)

    fun withParams(p: BeautyParams): Style = withValues(
        mapOf(StyleKeys.SMOOTH to p.smooth, StyleKeys.WHITEN to p.whiten, StyleKeys.ROSY to p.rosy),
    )
}

object DefaultStyles {
    const val PORTRAIT_ID = "portrait"

    /** Built-in "no effect" style: always available, never stored, cannot be edited or deleted. */
    const val ORIGINAL_ID = "original"

    fun original() = Style(ORIGINAL_ID, "原圖", emptyMap())

    /** Recommended portrait values; also what the editor's 預設 button restores. */
    const val PORTRAIT_SMOOTH = 0.55f
    const val PORTRAIT_WHITEN = 0.30f
    const val PORTRAIT_ROSY = 0.15f

    fun portrait() = Style(
        PORTRAIT_ID,
        "人像",
        mapOf(StyleKeys.SMOOTH to PORTRAIT_SMOOTH, StyleKeys.WHITEN to PORTRAIT_WHITEN, StyleKeys.ROSY to PORTRAIT_ROSY),
    )

    /** The recommended values as editor parameters. */
    fun recommended() = BeautyParams(PORTRAIT_SMOOTH, PORTRAIT_WHITEN, PORTRAIT_ROSY)
}

/** Where the repository keeps its data (SharedPreferences on Android, a map in tests). */
interface KeyValueStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)

    /** The previous version of the value, if the storage keeps one. */
    fun getBackupString(key: String): String? = null
}

data class StyleState(val styles: List<Style>, val selectedId: String) {
    val selected: Style
        get() = if (selectedId == DefaultStyles.ORIGINAL_ID) DefaultStyles.original()
        else styles.firstOrNull { it.id == selectedId } ?: styles.first()

    /** The built-in original style followed by the user's styles: what the picker shows. */
    val all: List<Style> get() = listOf(DefaultStyles.original()) + styles
}

/** All styles plus the one currently applied; every change is persisted immediately. */
class StyleRepository(
    private val storage: KeyValueStorage,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /**
     * True when the stored data was written by a newer app version (after a downgrade). It is still
     * read, but never written back, so the newer data is not damaged.
     */
    var readOnly = false
        private set

    var state: StyleState = load()
        private set

    private fun load(): StyleState {
        val raw = storage.getString(KEY)
        parse(raw)?.let { return it }
        // Main data missing or unreadable: fall back to the previous version of it before giving up.
        val backupRaw = storage.getBackupString(KEY)
        parse(backupRaw)?.let { restored ->
            persist(restored)
            return restored
        }
        // Stored data that cannot be read is kept aside instead of being silently overwritten.
        if (raw != null) storage.putString(KEY_UNREADABLE, raw)
        val seeded = StyleState(listOf(DefaultStyles.portrait()), DefaultStyles.PORTRAIT_ID)
        persist(seeded)
        return seeded
    }

    fun select(id: String): StyleState {
        if (id != DefaultStyles.ORIGINAL_ID && state.styles.none { it.id == id }) return state
        return update(state.copy(selectedId = id))
    }

    /** Inserts or replaces the style (matched by id) and applies it. */
    fun save(style: Style): StyleState {
        if (style.id == DefaultStyles.ORIGINAL_ID) return state
        val cleaned = style.copy(name = style.name.trim().ifEmpty { DEFAULT_NAME })
        val exists = state.styles.any { it.id == cleaned.id }
        val list = if (exists) state.styles.map { if (it.id == cleaned.id) cleaned else it } else state.styles + cleaned
        return update(StyleState(list, cleaned.id))
    }

    /** Only custom styles can be deleted: the built-in portrait style (and the last remaining one) stays. */
    fun delete(id: String): StyleState {
        if (id == DefaultStyles.PORTRAIT_ID || state.styles.size <= 1 || state.styles.none { it.id == id }) return state
        val list = state.styles.filter { it.id != id }
        val selected = if (state.selectedId == id) list.first().id else state.selectedId
        return update(StyleState(list, selected))
    }

    /** A new, not yet saved style that starts as a copy of [base]. */
    fun draft(base: Style): Style = Style(newId(), nextName(state.styles.map { it.name }), base.values)

    private fun update(next: StyleState): StyleState {
        state = next
        persist(next)
        return next
    }

    private fun persist(s: StyleState) {
        if (readOnly) return
        val arr = JSONArray()
        for (st in s.styles) {
            val vals = JSONObject()
            for ((k, v) in st.values) vals.put(k, v.toDouble())
            arr.put(JSONObject().put("id", st.id).put("name", st.name).put("values", vals))
        }
        storage.putString(KEY, JSONObject().put("version", SCHEMA_VERSION).put("selected", s.selectedId).put("styles", arr).toString())
    }

    /**
     * Reads any schema up to [SCHEMA_VERSION]. Unknown keys and unknown extra fields are ignored,
     * never treated as an error; only genuinely malformed data returns null.
     */
    private fun parse(text: String?): StyleState? {
        if (text == null) return null
        return try {
            val root = JSONObject(text)
            val version = root.optInt("version", 1)
            val arr = root.getJSONArray("styles")
            val styles = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val v = o.optJSONObject("values") ?: JSONObject()
                val map = LinkedHashMap<String, Float>()
                for (k in v.keys()) map[k] = v.getDouble(k).toFloat()
                Style(o.getString("id"), o.getString("name"), map)
            }
            if (styles.isEmpty()) return null
            if (version > SCHEMA_VERSION) readOnly = true
            StyleState(styles, root.optString("selected"))
        } catch (e: JSONException) {
            null
        }
    }

    companion object {
        private const val KEY = "styles_v1"
        const val SCHEMA_VERSION = 1
        const val KEY_UNREADABLE = "styles_v1_unreadable"
        const val DEFAULT_NAME = "風格"
        const val NEW_NAME = "新風格"

        /** 新風格, 新風格 2, 新風格 3 ... first one not taken. */
        fun nextName(existing: List<String>): String {
            if (NEW_NAME !in existing) return NEW_NAME
            var n = 2
            while ("$NEW_NAME $n" in existing) n++
            return "$NEW_NAME $n"
        }
    }
}
