package dev.glowcam

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileStorageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun storage() = FileStorage(File(tmp.root, "settings"))

    @Test
    fun missingKeyIsNull() {
        assertNull(storage().getString("k"))
        assertNull(storage().getBackupString("k"))
    }

    @Test
    fun valueSurvivesANewInstance() {
        storage().putString("k", "{\"a\":1}")
        assertEquals("{\"a\":1}", storage().getString("k"))
    }

    @Test
    fun previousVersionIsKeptAsBackup() {
        val s = storage()
        s.putString("k", "one")
        assertNull(s.getBackupString("k"))
        s.putString("k", "two")
        assertEquals("two", s.getString("k"))
        assertEquals("one", s.getBackupString("k"))
        s.putString("k", "three")
        assertEquals("two", s.getBackupString("k"))
    }

    @Test
    fun noTempFileIsLeftBehind() {
        val s = storage()
        s.putString("k", "v")
        s.putString("k", "w")
        assertFalse(File(tmp.root, "settings/k.json.tmp").exists())
    }

    @Test
    fun unicodeRoundTrips() {
        storage().putString("k", "人像・寵物")
        assertEquals("人像・寵物", storage().getString("k"))
    }

    @Test
    fun migratingStorageAdoptsLegacyValueOnceAndNeverDeletesIt() {
        val legacy = object : KeyValueStorage {
            val map = hashMapOf("k" to "old")
            override fun getString(key: String) = map[key]
            override fun putString(key: String, value: String) { map[key] = value }
        }
        val primary = storage()
        val m = MigratingStorage(primary, legacy)
        assertEquals("old", m.getString("k"))
        assertEquals("old", primary.getString("k")) // copied over
        assertEquals("old", legacy.map["k"])          // legacy untouched
        m.putString("k", "new")
        assertEquals("new", MigratingStorage(storage(), legacy).getString("k")) // primary wins from now on
    }

    @Test
    fun migratingStorageWithNothingAnywhereIsNull() {
        val legacy = object : KeyValueStorage {
            override fun getString(key: String): String? = null
            override fun putString(key: String, value: String) = Unit
        }
        assertNull(MigratingStorage(storage(), legacy).getString("k"))
    }
}
