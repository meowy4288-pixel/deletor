package com.igviewer.cleaner.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun byteCountsReadNaturally() {
        assertEquals("0 B", humanBytes(0))
        assertEquals("999 B", humanBytes(999))
        assertEquals("2 KB", humanBytes(2000))
        assertEquals("5.5 MB", humanBytes(5_500_000))
        assertEquals("2.0 GB", humanBytes(2_000_000_000))
    }

    @Test
    fun countsReadWithThousandsSeparators() {
        assertEquals("0", humanCount(0))
        assertEquals("999", humanCount(999))
        assertEquals("12,909", humanCount(12_909))
        assertEquals("1,234,567", humanCount(1_234_567))
    }

    @Test
    fun displayPathIsRelativeToTheScanRoot() {
        val root = java.io.File("/storage/emulated/0")
        assertEquals("DCIM/Camera/a.jpg", displayPath(root, "/storage/emulated/0/DCIM/Camera/a.jpg"))
        // Outside the root it is shown unchanged rather than silently mangled.
        assertEquals("/mnt/sdcard/x.jpg", displayPath(root, "/mnt/sdcard/x.jpg"))
    }

    @Test
    fun durationsReadLikeAClock() {
        assertEquals("0:07", humanDuration(7_400))
        assertEquals("3:42", humanDuration(222_000))
        assertEquals("1:02:09", humanDuration(3_729_000))
        // Sub-second rounds down rather than showing a misleading 0:01.
        assertEquals("0:00", humanDuration(999))
    }

    @Test
    fun anUnknownDurationIsBlankRatherThanZero() {
        // A video whose header did not carry a duration must not be labelled 0:00.
        assertEquals("", humanDuration(0))
        assertEquals("", humanDuration(-1))
    }
}
