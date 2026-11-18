package dev.glowcam

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * User-defined settings live in their own files, separate from the program: one JSON file per key
 * under [dir] (inside the app's files folder, which an app update never touches and which is
 * included in Android's backup so a reinstall can restore it).
 *
 * - writes are atomic (temp file, fsync, rename) so a crash cannot leave half a file
 * - the previous version of a file is kept as `<key>.json.bak`
 */
class FileStorage(private val dir: File) : KeyValueStorage {

    private fun file(key: String) = File(dir, "$key.json")
    private fun backup(key: String) = File(dir, "$key.json.bak")

    override fun getString(key: String): String? = read(file(key))

    override fun getBackupString(key: String): String? = read(backup(key))

    override fun putString(key: String, value: String) {
        dir.mkdirs()
        val target = file(key)
        val tmp = File(dir, "$key.json.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(value.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (target.exists()) runCatching { target.copyTo(backup(key), overwrite = true) }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun read(f: File): String? = try {
        if (f.isFile) f.readText(Charsets.UTF_8).takeIf { it.isNotEmpty() } else null
    } catch (e: java.io.IOException) {
        null
    }
}

/**
 * Reads from [primary]; if it has nothing yet, adopts the value an older app version kept in
 * [legacy] and copies it over. The legacy copy is never deleted.
 */
class MigratingStorage(private val primary: KeyValueStorage, private val legacy: KeyValueStorage) : KeyValueStorage {

    override fun getString(key: String): String? {
        primary.getString(key)?.let { return it }
        primary.getBackupString(key)?.let { return it }
        val old = legacy.getString(key) ?: return null
        primary.putString(key, old)
        return old
    }

    override fun getBackupString(key: String): String? = primary.getBackupString(key)

    override fun putString(key: String, value: String) = primary.putString(key, value)
}
