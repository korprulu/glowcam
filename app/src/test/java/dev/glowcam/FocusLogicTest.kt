package dev.glowcam

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusLogicTest {

    // ---- AF events ----
    @Test
    fun focusEventsAreTransitionsIntoScanOrLockedStates() {
        assertTrue(AfState.isFocusEvent(0, AfState.PASSIVE_SCAN))
        assertTrue(AfState.isFocusEvent(AfState.PASSIVE_SCAN, AfState.FOCUSED_LOCKED))
        assertTrue(AfState.isFocusEvent(null, AfState.ACTIVE_SCAN))
        assertFalse(AfState.isFocusEvent(AfState.PASSIVE_SCAN, AfState.PASSIVE_SCAN)) // no change
        assertFalse(AfState.isFocusEvent(AfState.PASSIVE_SCAN, 2)) // passive focused: settled
        assertFalse(AfState.isFocusEvent(1, null))
    }

    // ---- controller ----
    @Test
    fun defaultStateShowsAtCentreAndFadesAfterThreeSeconds() {
        val c = FocusController()
        assertFalse(c.visible)
        c.onFocusEvent(1000)
        assertTrue(c.visible); assertFalse(c.custom)
        assertEquals(0.5f, c.nx, 0f); assertEquals(0.5f, c.ny, 0f)
        assertFalse(c.tick(3999))
        assertTrue(c.visible)
        assertTrue(c.tick(4000))
        assertFalse(c.visible)
    }

    @Test
    fun newEventRestartsFadeTimer() {
        val c = FocusController()
        c.onFocusEvent(0)
        c.onFocusEvent(2500)
        assertFalse(c.tick(5000))
        assertTrue(c.tick(5500))
    }

    @Test
    fun customFocusStaysVisibleAndFollowsTrack() {
        val c = FocusController()
        c.onUserTap(0.2f, 0.7f, 0)
        assertTrue(c.custom); assertTrue(c.visible)
        assertFalse(c.tick(60_000))
        c.onTrack(0.25f, 0.6f)
        assertEquals(0.25f, c.nx, 0f); assertEquals(0.6f, c.ny, 0f)
        c.onFocusEvent(100) // AF events don't move a custom point
        assertEquals(0.25f, c.nx, 0f)
        assertTrue(c.custom)
    }

    @Test
    fun lostTrackResetsToCentreAndCancelsCustom() {
        val c = FocusController()
        c.onUserTap(0.9f, 0.1f, 0)
        c.onTrackLost(500)
        assertFalse(c.custom)
        assertEquals(0.5f, c.nx, 0f); assertEquals(0.5f, c.ny, 0f)
        assertTrue(c.visible)
        assertTrue(c.tick(3500)) // then fades like the default state
    }

    @Test
    fun trackIgnoredWhenNotCustom() {
        val c = FocusController()
        c.onFocusEvent(0)
        c.onTrack(0.1f, 0.1f)
        assertEquals(0.5f, c.nx, 0f)
    }

    // ---- mapping ----
    @Test
    fun fitMappingRoundTripsAndRejectsTapsOutsidePicture() {
        // 480x640 frame in a 1080x2400 view, top inset 100: scale 2.25, picture 1080x1440 from y=100.
        val (fx, fy) = FocusMapping.viewToFrame(540f, 100f + 720f, 1080f, 2400f, 480f, 640f, 100f)!!
        assertEquals(0.5f, fx, 1e-4f); assertEquals(0.5f, fy, 1e-4f)
        val (vx, vy) = FocusMapping.frameToView(0.3f, 0.8f, 1080f, 2400f, 480f, 640f, 100f)
        val (bx, by) = FocusMapping.viewToFrame(vx, vy, 1080f, 2400f, 480f, 640f, 100f)!!
        assertEquals(0.3f, bx, 1e-4f); assertEquals(0.8f, by, 1e-4f)
        assertNull(FocusMapping.viewToFrame(540f, 50f, 1080f, 2400f, 480f, 640f, 100f))   // under the status bar
        assertNull(FocusMapping.viewToFrame(540f, 2000f, 1080f, 2400f, 480f, 640f, 100f)) // below the picture
    }

    // ---- tracker ----
    private fun noise(w: Int, h: Int, seed: Long): IntArray {
        val r = Random(seed)
        return IntArray(w * h) { r.nextInt(256) }
    }

    private fun shift(src: IntArray, w: Int, h: Int, dx: Int, dy: Int) = IntArray(w * h) {
        val x = (it % w - dx).coerceIn(0, w - 1)
        val y = (it / w - dy).coerceIn(0, h - 1)
        src[y * w + x]
    }

    @Test
    fun trackerFollowsMovingScene() {
        val w = 160; val h = 200
        val a = noise(w, h, 1)
        val t = FocusTracker()
        t.start(a, w, h, 80, 100)
        val p = t.update(shift(a, w, h, 6, -4), w, h)
        assertNotNull(p)
        assertEquals(86, p!!.first); assertEquals(96, p.second)
        assertTrue(t.active)
    }

    @Test
    fun trackerLosesPointWhenSceneChanges() {
        val w = 160; val h = 200
        val t = FocusTracker()
        t.start(noise(w, h, 1), w, h, 80, 100)
        // A couple of bad frames are tolerated (point stays where it was) ...
        assertEquals(80 to 100, t.update(noise(w, h, 2), w, h))
        assertEquals(80 to 100, t.update(noise(w, h, 3), w, h))
        assertTrue(t.active)
        // ... the third in a row loses it.
        assertNull(t.update(noise(w, h, 5), w, h))
        assertFalse(t.active)
    }

    @Test
    fun trackerLosesPointLeavingFrame() {
        val w = 160; val h = 200
        val a = noise(w, h, 3)
        val t = FocusTracker()
        t.start(a, w, h, 20, 100)
        // scene slides left so the point ends within the edge margin
        assertNull(t.update(shift(a, w, h, -16, 0), w, h))
        assertFalse(t.active)
    }

    @Test
    fun trackerLosesPointMovingFasterThanSearchWindow() {
        val w = 200; val h = 200
        val a = noise(w, h, 4)
        val t = FocusTracker()
        t.start(a, w, h, 60, 100)
        val moved = shift(a, w, h, 50, 0)
        t.update(moved, w, h)
        t.update(moved, w, h)
        assertNull(t.update(moved, w, h))
    }

    @Test
    fun goodFrameResetsMissCount() {
        val w = 160; val h = 200
        val a = noise(w, h, 1)
        val t = FocusTracker()
        t.start(a, w, h, 80, 100)
        t.update(noise(w, h, 2), w, h)
        t.update(noise(w, h, 3), w, h)
        assertNotNull(t.update(a, w, h)) // matches again
        t.update(noise(w, h, 4), w, h)
        t.update(noise(w, h, 5), w, h)
        assertTrue(t.active)
    }

    @Test
    fun grayConversion() {
        val p = Pixels(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 2, 1)
        val g = FocusTracker.toGray(p)
        assertTrue(g[0] in 254..255); assertEquals(0, g[1])
    }
}
