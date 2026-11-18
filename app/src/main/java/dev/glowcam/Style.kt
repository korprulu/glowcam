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

    fun portrait() = Style(
        PORTRAIT_ID,
        "人像",
        mapOf(StyleKeys.SMOOTH to 0.6f, StyleKeys.WHITEN to 0.3f, StyleKeys.ROSY to 0.2f),
    )
}

/** Where the repository keeps its data (SharedPreferences on Android, a map in tests). */
interface KeyValueStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

data class StyleState(val styles: List<Style>, val selectedId: String) {
    val selected: Style get() = styles.firstOrNull { it.id == selectedId } ?: styles.first()
}

/** All styles plus the one currently applied; every change is persisted immediately. */
class StyleRepository(
    private val storage: KeyValueStorage,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    var state: StyleState = load()
        private set

    private fun load(): StyleState {
        val parsed = storage.getString(KEY)?.let { parse(it) }
        if (parsed != null) return parsed
        val seeded = StyleState(listOf(DefaultStyles.portrait()), DefaultStyles.PORTRAIT_ID)
        persist(seeded)
        return seeded
    }

    fun select(id: String): StyleState {
        if (state.styles.none { it.id == id }) return state
        return update(state.copy(selectedId = id))
    }

    /** Inserts or replaces the style (matched by id) and applies it. */
    fun save(style: Style): StyleState {
        val cleaned = style.copy(name = style.name.trim().ifEmpty { DEFAULT_NAME })
        val exists = state.styles.any { it.id == cleaned.id }
        val list = if (exists) state.styles.map { if (it.id == cleaned.id) cleaned else it } else state.styles + cleaned
        return update(StyleState(list, cleaned.id))
    }

    /** The last remaining style cannot be deleted. */
    fun delete(id: String): StyleState {
        if (state.styles.size <= 1 || state.styles.none { it.id == id }) return state
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
        val arr = JSONArray()
        for (st in s.styles) {
            val vals = JSONObject()
            for ((k, v) in st.values) vals.put(k, v.toDouble())
            arr.put(JSONObject().put("id", st.id).put("name", st.name).put("values", vals))
        }
        storage.putString(KEY, JSONObject().put("version", 1).put("selected", s.selectedId).put("styles", arr).toString())
    }

    private fun parse(text: String): StyleState? = try {
        val root = JSONObject(text)
        val arr = root.getJSONArray("styles")
        val styles = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val v = o.optJSONObject("values") ?: JSONObject()
            val map = LinkedHashMap<String, Float>()
            for (k in v.keys()) map[k] = v.getDouble(k).toFloat()
            Style(o.getString("id"), o.getString("name"), map)
        }
        if (styles.isEmpty()) null else StyleState(styles, root.optString("selected"))
    } catch (e: JSONException) {
        null
    }

    companion object {
        private const val KEY = "styles_v1"
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
