package dev.glowcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleRepositoryTest {

    private class MemoryStorage(val map: MutableMap<String, String> = HashMap()) : KeyValueStorage {
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    private fun repo(storage: KeyValueStorage = MemoryStorage()): StyleRepository {
        var n = 0
        return StyleRepository(storage) { "id${++n}" }
    }

    @Test
    fun firstRunHasPortraitStyleSelected() {
        val s = repo().state
        assertEquals(1, s.styles.size)
        assertEquals("人像", s.selected.name)
        val p = s.selected.toBeautyParams()
        assertEquals(0.6f, p.smooth, 1e-6f)
        assertEquals(0.3f, p.whiten, 1e-6f)
        assertEquals(0.2f, p.rosy, 1e-6f)
    }

    @Test
    fun lastAppliedStyleIsRememberedAcrossRestarts() {
        val storage = MemoryStorage()
        val r = repo(storage)
        val draft = r.draft(r.state.selected)
        r.save(draft.copy(name = "夜拍").withParams(BeautyParams(0.1f, 0.9f, 0f)))
        assertEquals("夜拍", r.state.selected.name)

        val reopened = repo(storage).state
        assertEquals(2, reopened.styles.size)
        assertEquals("夜拍", reopened.selected.name)
        assertEquals(0.9f, reopened.selected.toBeautyParams().whiten, 1e-6f)
    }

    @Test
    fun selectSwitchesAndPersists() {
        val storage = MemoryStorage()
        val r = repo(storage)
        r.save(r.draft(r.state.selected).copy(name = "B"))
        r.select(DefaultStyles.PORTRAIT_ID)
        assertEquals("人像", repo(storage).state.selected.name)
        r.select("does-not-exist")
        assertEquals("人像", r.state.selected.name)
    }

    @Test
    fun editingExistingStyleReplacesItInPlace() {
        val r = repo()
        r.save(r.draft(r.state.selected).copy(name = "B"))
        val first = r.state.styles.first()
        r.save(first.withParams(BeautyParams(1f, 0f, 0f)).copy(name = "人像 強"))
        assertEquals(listOf("人像 強", "B"), r.state.styles.map { it.name })
        assertEquals(1f, r.state.selected.toBeautyParams().smooth, 0f)
        assertEquals(first.id, r.state.selectedId)
    }

    @Test
    fun blankNameFallsBackToDefault() {
        val r = repo()
        r.save(r.state.selected.copy(name = "   "))
        assertEquals(StyleRepository.DEFAULT_NAME, r.state.selected.name)
    }

    @Test
    fun deleteFallsBackToFirstAndKeepsLastStyle() {
        val r = repo()
        r.save(r.draft(r.state.selected).copy(name = "B"))
        val b = r.state.selected
        r.delete(b.id)
        assertEquals(1, r.state.styles.size)
        assertEquals("人像", r.state.selected.name)
        r.delete(r.state.selected.id) // last one stays
        assertEquals(1, r.state.styles.size)
    }

    @Test
    fun unknownValueKeysSurviveRoundTrip() {
        val storage = MemoryStorage()
        val r = repo(storage)
        val withExtra = r.state.selected.withValues(mapOf("grain" to 0.42f))
        r.save(withExtra)
        // editing only the beauty values must not drop the extra key
        r.save(repo(storage).state.selected.withParams(BeautyParams(0.5f, 0.5f, 0.5f)))
        assertEquals(0.42f, repo(storage).state.selected.value("grain"), 1e-6f)
    }

    @Test
    fun missingValuesDefaultToZeroAndAreClamped() {
        val s = Style("x", "x", mapOf(StyleKeys.SMOOTH to 5f))
        val p = s.toBeautyParams()
        assertEquals(1f, p.smooth, 0f)
        assertEquals(0f, p.whiten, 0f)
        assertEquals(0f, p.rosy, 0f)
    }

    @Test
    fun corruptStorageFallsBackToDefaults() {
        val storage = MemoryStorage(hashMapOf("styles_v1" to "{not json"))
        val s = repo(storage).state
        assertEquals("人像", s.selected.name)
        assertTrue(s.styles.size == 1)
    }

    @Test
    fun unknownSelectedIdFallsBackToFirstStyle() {
        val storage = MemoryStorage()
        val r = repo(storage)
        r.save(r.draft(r.state.selected))
        storage.map["styles_v1"] = storage.map["styles_v1"]!!.replace("\"selected\":\"${r.state.selectedId}\"", "\"selected\":\"gone\"")
        assertEquals("人像", repo(storage).state.selected.name)
    }

    @Test
    fun newStyleNamesDoNotCollide() {
        assertEquals("新風格", StyleRepository.nextName(listOf("人像")))
        assertEquals("新風格 2", StyleRepository.nextName(listOf("人像", "新風格")))
        assertEquals("新風格 3", StyleRepository.nextName(listOf("新風格", "新風格 2")))
    }

    @Test
    fun draftCopiesValuesWithFreshIdAndUniqueName() {
        val r = repo()
        val d = r.draft(r.state.selected)
        assertEquals(r.state.selected.values, d.values)
        assertTrue(d.id != r.state.selected.id)
        assertEquals("新風格", d.name)
        assertEquals(1, r.state.styles.size) // not persisted until saved
    }
}
