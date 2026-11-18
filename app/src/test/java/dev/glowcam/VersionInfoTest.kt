package dev.glowcam

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionInfoTest {
    @Test
    fun nameAndCode() = assertEquals("1.6.0 (11)", VersionInfo.format("1.6.0", 11L))

    @Test
    fun nameOnly() = assertEquals("1.6.0", VersionInfo.format(" 1.6.0 ", null))

    @Test
    fun codeOnly() = assertEquals("(11)", VersionInfo.format(null, 11L))

    @Test
    fun nothingKnown() {
        assertEquals("—", VersionInfo.format(null, null))
        assertEquals("—", VersionInfo.format("  ", null))
    }
}
