package dev.glowcam

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.Images.Media as M
import android.util.Size

/** A saved photo; [takenMillis] is null when the date is unknown, [place] when no location was recorded. */
data class Photo(val uri: Uri, val takenMillis: Long?, val place: String?)

/** Reads the photos GlowCam saved in Pictures/GlowCam (no storage permission needed for own files). */
object PhotoStore {

    /** Newest first. */
    fun list(context: Context): List<Photo> {
        val result = ArrayList<Photo>()
        context.contentResolver.query(
            M.EXTERNAL_CONTENT_URI,
            arrayOf(M._ID, M.DATE_TAKEN, M.DATE_ADDED, M.DESCRIPTION),
            "${M.RELATIVE_PATH} LIKE ?",
            arrayOf("Pictures/GlowCam%"),
            "${M.DATE_ADDED} DESC, ${M._ID} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val taken = if (c.isNull(1)) 0L else c.getLong(1)
                val added = if (c.isNull(2)) 0L else c.getLong(2) * 1000
                val millis = (if (taken > 0) taken else added).takeIf { it > 0 }
                result.add(Photo(ContentUris.withAppendedId(M.EXTERNAL_CONTENT_URI, c.getLong(0)), millis, c.getString(3)))
            }
        }
        return result
    }

    fun thumbnail(context: Context, uri: Uri, sizePx: Int): Bitmap? = try {
        context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
    } catch (e: Exception) {
        null
    }

    /** Decode for full-screen viewing, long side capped at [maxSide]. */
    fun load(context: Context, uri: Uri, maxSide: Int): Bitmap? = try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            var sample = 1
            while (longest / sample > maxSide) sample *= 2
            decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: Exception) {
        null
    }
}
