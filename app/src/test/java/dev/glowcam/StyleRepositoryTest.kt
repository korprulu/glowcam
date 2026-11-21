package dev.glowcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleRepositoryTest {

    private class MemoryStorage(
        val map: MutableMap<String, String> = HashMap(),
        val backups: MutableMap<String, String> = HashMap(),
    ) : KeyValueStorage {
        override fun getString(key: String) = map[key]
        override fun getBackupString(key: String) = backups[key]
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
    fun unreadableStoredDataIsKeptAside() {
        val storage = MemoryStorage(hashMapOf("styles_v1" to "{not json"))
        repo(storage)
        assertEquals("{not json", storage.map["styles_v1_unreadable"])
    }

    @Test
    fun readableStoredDataIsNeverCopiedAside() {
        val storage = MemoryStorage()
        val r = repo(storage)
        r.save(r.draft(r.state.selected))
        repo(storage)
        assertEquals(null, storage.map["styles_v1_unreadable"])
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

    @Test
    fun brokenMainDataIsRestoredFromTheBackupCopy() {
        val good = MemoryStorage()
        val r = repo(good)
        r.save(r.draft(r.state.selected).copy(name = "寵物"))
        val goodJson = good.map["styles_v1"]!!

        val storage = MemoryStorage(hashMapOf("styles_v1" to "{broken"), hashMapOf("styles_v1" to goodJson))
        val restored = repo(storage).state
        assertEquals(listOf("人像", "寵物"), restored.styles.map { it.name })
        assertEquals(goodJson, storage.map["styles_v1"]) // main copy repaired
    }

    @Test
    fun missingMainDataIsRestoredFromTheBackupCopy() {
        val good = MemoryStorage()
        val r = repo(good)
        r.save(r.draft(r.state.selected).copy(name = "寵物"))
        val storage = MemoryStorage(hashMapOf(), hashMapOf("styles_v1" to good.map["styles_v1"]!!))
        assertEquals("寵物", repo(storage).state.selected.name)
    }

    @Test
    fun dataFromANewerSchemaIsReadButNeverOverwritten() {
        val newer = """{"version":99,"selected":"a","styles":[{"id":"a","name":"未來","values":{"smooth":0.5,"hologram":1.0}}],"future":{"x":1}}"""
        val storage = MemoryStorage(hashMapOf("styles_v1" to newer))
        val r = repo(storage)
        assertEquals("未來", r.state.selected.name)
        assertEquals(1f, r.state.selected.value("hologram"), 0f)
        assertTrue(r.readOnly)
        r.save(r.state.selected.copy(name = "改"))
        r.select("a")
        assertEquals(newer, storage.map["styles_v1"]) // untouched on disk
    }

    @Test
    fun olderSchemaWithoutVersionFieldStillLoads() {
        val old = """{"selected":"a","styles":[{"id":"a","name":"舊","values":{"smooth":0.2}}]}"""
        val r = repo(MemoryStorage(hashMapOf("styles_v1" to old)))
        assertEquals("舊", r.state.selected.name)
        assertEquals(false, r.readOnly)
    }

    @Test
    fun originalStyleIsAlwaysFirstAndHasNoEffect() {
        val r = repo()
        assertEquals(listOf("原圖", "人像"), r.state.all.map { it.name })
        assertTrue(r.state.all.first().toBeautyParams().isIdentity)
    }

    @Test
    fun originalStyleCanBeSelectedAndIsRemembered() {
        val storage = MemoryStorage()
        val r = repo(storage)
        r.select(DefaultStyles.ORIGINAL_ID)
        assertEquals("原圖", repo(storage).state.selected.name)
    }

    @Test
    fun originalStyleIsNeverStoredEditedOrDeleted() {
        val storage = MemoryStorage()
        val r = repo(storage)
        r.save(DefaultStyles.original().withParams(BeautyParams(1f, 1f, 1f)))
        r.delete(DefaultStyles.ORIGINAL_ID)
        assertEquals(1, r.state.styles.size)
        assertTrue(!storage.map["styles_v1"]!!.contains("\"original\""))
        assertTrue(DefaultStyles.original().toBeautyParams().isIdentity)
    }
}
