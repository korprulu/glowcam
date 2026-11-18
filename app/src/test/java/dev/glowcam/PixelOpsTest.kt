package dev.glowcam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PixelOpsTest {
    // 3 wide x 2 high:  1 2 3 / 4 5 6
    private val p = Pixels(intArrayOf(1, 2, 3, 4, 5, 6), 3, 2)

    @Test
    fun rotate90Clockwise() {
        val r = p.rotated(90)
        assertEquals(2, r.width); assertEquals(3, r.height)
        assertArrayEquals(intArrayOf(4, 1, 5, 2, 6, 3), r.data)
    }

    @Test
    fun rotate270Clockwise() {
        val r = p.rotated(270)
        assertEquals(2, r.width); assertEquals(3, r.height)
        assertArrayEquals(intArrayOf(3, 6, 2, 5, 1, 4), r.data)
    }

    @Test
    fun rotate180AndFullTurn() {
        assertArrayEquals(intArrayOf(6, 5, 4, 3, 2, 1), p.rotated(180).data)
        assertArrayEquals(p.data, p.rotated(360).data)
        assertArrayEquals(p.data, p.rotated(90).rotated(270).data)
        assertArrayEquals(p.rotated(180).data, p.rotated(-180).data)
    }

    @Test
    fun rotateRejectsNonRightAngles() {
        assertThrows(IllegalArgumentException::class.java) { p.rotated(45) }
    }

    @Test
    fun mirrorFlipsRows() {
        val m = p.mirrored()
        assertArrayEquals(intArrayOf(3, 2, 1, 6, 5, 4), m.data)
        assertArrayEquals(p.data, m.mirrored().data)
    }

    @Test
    fun rgbaConversionHonoursRowStride() {
        // 2x2 image, rowStride 12 (4 bytes padding per row)
        val bytes = ByteArray(24)
        fun put(x: Int, y: Int, r: Int, g: Int, b: Int) {
            val i = y * 12 + x * 4
            bytes[i] = r.toByte(); bytes[i + 1] = g.toByte(); bytes[i + 2] = b.toByte(); bytes[i + 3] = 0x7F
        }
        put(0, 0, 255, 0, 0); put(1, 0, 0, 255, 0); put(0, 1, 0, 0, 255); put(1, 1, 10, 20, 30)
        val out = Pixels.fromRgba(bytes, 12, 2, 2)
        assertArrayEquals(
            intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF0A141E.toInt()),
            out.data,
        )
    }

    @Test
    fun paramsFromProgressClamps() {
        val p = BeautyParams.fromProgress(150, -5, 50)
        assertEquals(1f, p.smooth, 0f)
        assertEquals(0f, p.whiten, 0f)
        assertEquals(0.5f, p.rosy, 0f)
        assertEquals(true, BeautyParams(0f, 0f, 0f).isIdentity)
    }
}
