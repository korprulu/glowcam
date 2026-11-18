package dev.glowcam

/**
 * Pager layout for the photo viewer, left to right:
 * `[camera page][newest photo][...][oldest photo]`.
 * Swiping left therefore shows the previous (older) photo, swiping right the next (newer) one,
 * and swiping right past the newest lands on the camera page.
 */
object GalleryPages {
    const val CAMERA_POSITION = 0

    fun count(photos: Int): Int = photos + 1

    fun isCamera(position: Int): Boolean = position == CAMERA_POSITION

    /** Index into the newest-first photo list, or -1 for the camera page. */
    fun photoIndex(position: Int): Int = position - 1

    /** Page to open when the viewer starts on the newest photo. */
    fun newestPosition(photos: Int): Int = if (photos > 0) 1 else CAMERA_POSITION
}
