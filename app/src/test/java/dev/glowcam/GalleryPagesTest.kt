package dev.glowcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryPagesTest {
    @Test
    fun cameraPageIsFirstAndAddsOneToCount() {
        assertEquals(4, GalleryPages.count(3))
        assertEquals(1, GalleryPages.count(0))
        assertTrue(GalleryPages.isCamera(0))
        assertFalse(GalleryPages.isCamera(1))
    }

    @Test
    fun newestPhotoIsAtPositionOne() {
        assertEquals(0, GalleryPages.photoIndex(1)) // index 0 of newest-first list
        assertEquals(2, GalleryPages.photoIndex(3)) // oldest of 3
        assertEquals(1, GalleryPages.newestPosition(3))
    }

    @Test
    fun withNoPhotosViewerStartsOnCameraPage() {
        assertEquals(GalleryPages.CAMERA_POSITION, GalleryPages.newestPosition(0))
    }

    @Test
    fun oldestPhotoIsLastPageSoLeftSwipeStopsThere() {
        val photos = 5
        assertEquals(photos, GalleryPages.count(photos) - 1)
        assertEquals(photos - 1, GalleryPages.photoIndex(GalleryPages.count(photos) - 1))
    }
}
