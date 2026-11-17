package com.igviewer.cleaner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveIndexTest {

    private val photoA = ArchiveMedia("media/photos/a.jpg", 12, Hashing.sha256("aaa".toByteArray()))
    private val photoB = ArchiveMedia("media/photos/b.jpg", 12, Hashing.sha256("bbb".toByteArray()))

    private val index = ArchiveIndex.of(listOf(photoA, photoB))

    @Test
    fun sizeAndHashTogetherSelectTheEntry() {
        assertEquals(listOf(photoA), index.matches(12, photoA.sha256))
        assertEquals(listOf(photoB), index.matches(12, photoB.sha256))
    }

    @Test
    fun equalSizeAloneIsNeverAMatch() {
        // Same byte count, different content: this is exactly the case a size-only
        // implementation would get catastrophically wrong.
        assertTrue(index.matches(12, Hashing.sha256("ccc".toByteArray())).isEmpty())
    }

    @Test
    fun aDifferentSizeIsNeverAMatch() {
        assertTrue(index.matches(13, photoA.sha256).isEmpty())
    }

    @Test
    fun mayMatchIsTheCheapPrefilter() {
        assertTrue(index.mayMatch(12))
        assertFalse(index.mayMatch(11))
        // Zero-byte files are not hashed at all; every empty file would "match".
        assertFalse(index.mayMatch(0))
    }

    @Test
    fun indexingDropsOnlyZeroByteEntries() {
        // Media selection happens while the zip is read (extension or sniffing), so
        // the index itself only refuses entries with nothing to compare.
        val namedNonMedia = ArchiveMedia("messages/inbox/ana/message_1.json", 12, photoA.sha256)
        val built = ArchiveIndex.of(
            listOf(
                ArchiveMedia("media/photos/empty.jpg", 0, Hashing.sha256(ByteArray(0))),
                namedNonMedia,
                photoA,
            ),
        )
        assertEquals(listOf(namedNonMedia, photoA), built.media)
    }

    @Test
    fun duplicateArchiveEntriesAllMatch() {
        val copy = photoA.copy(path = "media/photos/a-copy.jpg")
        val built = ArchiveIndex.of(listOf(photoA, copy))
        assertEquals(listOf(photoA, copy), built.matches(12, photoA.sha256))
    }

    @Test
    fun totalsAndSizeCountsAreDistinct() {
        val built = ArchiveIndex.of(
            listOf(photoA, photoA.copy(path = "media/photos/a2.jpg"), photoB),
        )
        assertEquals(36, built.totalBytes)
        assertEquals(1, built.sizes.size)
    }
}
