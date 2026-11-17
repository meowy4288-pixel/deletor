package com.igviewer.cleaner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class StorageRootsTest {

    @Test
    fun volumeRootIsThePathAboveTheAndroidDirectory() {
        assertEquals(
            File("/storage/1234-ABCD"),
            StorageRoots.volumeRootOf(File("/storage/1234-ABCD/Android/data/com.igviewer.cleaner/files")),
        )
        assertEquals(
            File("/storage/emulated/0"),
            StorageRoots.volumeRootOf(File("/storage/emulated/0/Android/data/com.igviewer.cleaner/cache")),
        )
    }

    @Test
    fun aPathWithNoAndroidSegmentHasNoVolumeRoot() {
        assertNull(StorageRoots.volumeRootOf(File("/storage/1234-ABCD/DCIM")))
        assertNull(StorageRoots.volumeRootOf(File("/")))
    }
}
