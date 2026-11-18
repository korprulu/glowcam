package dev.glowcam

/** ARGB pixel buffer with dimensions. */
class Pixels(val data: IntArray, val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0 && data.size == width * height) { "bad pixel buffer" }
    }

    /** Rotate clockwise by a multiple of 90 degrees. */
    fun rotated(degrees: Int): Pixels {
        val d = ((degrees % 360) + 360) % 360
        require(d % 90 == 0) { "degrees must be a multiple of 90" }
        return when (d) {
            0 -> this
            90 -> {
                val out = IntArray(data.size)
                for (y in 0 until height) for (x in 0 until width)
                    out[x * height + (height - 1 - y)] = data[y * width + x]
                Pixels(out, height, width)
            }
            180 -> Pixels(IntArray(data.size) { data[data.size - 1 - it] }, width, height)
            else -> {
                val out = IntArray(data.size)
                for (y in 0 until height) for (x in 0 until width)
                    out[(width - 1 - x) * height + y] = data[y * width + x]
                Pixels(out, height, width)
            }
        }
    }

    fun mirrored(): Pixels {
        val out = IntArray(data.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) out[row + x] = data[row + width - 1 - x]
        }
        return Pixels(out, width, height)
    }

    companion object {
        /**
         * Convert an RGBA_8888 byte buffer (as delivered by CameraX ImageAnalysis, possibly with
         * row padding) into ARGB ints.
         */
        fun fromRgba(bytes: ByteArray, rowStride: Int, width: Int, height: Int): Pixels {
            require(rowStride >= width * 4) { "rowStride too small" }
            val out = IntArray(width * height)
            for (y in 0 until height) {
                var i = y * rowStride
                val o = y * width
                for (x in 0 until width) {
                    val r = bytes[i].toInt() and 0xFF
                    val g = bytes[i + 1].toInt() and 0xFF
                    val b = bytes[i + 2].toInt() and 0xFF
                    out[o + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    i += 4
                }
            }
            return Pixels(out, width, height)
        }
    }
}
