package dev.glowcam

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.util.Size

/** Reads the photos GlowCam saved in Pictures/GlowCam (no storage permission needed for own files). */
object PhotoStore {

    /** Newest first. */
    fun list(context: Context): List<Uri> {
        val result = ArrayList<Uri>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("Pictures/GlowCam%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                result.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)))
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
