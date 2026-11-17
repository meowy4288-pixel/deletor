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
    fun displayPathIsRelativeToTheScanRoot() {
        val root = java.io.File("/storage/emulated/0")
        assertEquals("DCIM/Camera/a.jpg", displayPath(root, "/storage/emulated/0/DCIM/Camera/a.jpg"))
        // Outside the root it is shown unchanged rather than silently mangled.
        assertEquals("/mnt/sdcard/x.jpg", displayPath(root, "/mnt/sdcard/x.jpg"))
    }
}
