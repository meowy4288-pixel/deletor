package com.igviewer.cleaner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveZipTest {

    private fun zipOf(vararg entries: Pair<String, ByteArray>): File {
        val file = Files.createTempFile("fake-export", ".zip").toFile()
        file.deleteOnExit()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun photo(seed: Int): ByteArray =
        ByteArray(4096) { ((it * 31 + seed) % 251).toByte() }

    @Test
    fun hashesOnlyMediaEntriesAndRecordsTheirRealSize() {
        val a = photo(1)
        val b = photo(2)
        val zip = zipOf(
            "media/photos/2020/01/a.jpg" to a,
            "media/photos/2020/01/b.jpg" to b,
            "messages/inbox/ana/message_1.json" to """{"title":"ana"}""".toByteArray(),
            "media/audio/2020/01/voice.wav" to photo(3),
        )

        val index = ArchiveZip.index(zip)
        assertEquals(
            listOf(
                "media/photos/2020/01/a.jpg",
                "media/photos/2020/01/b.jpg",
                "media/audio/2020/01/voice.wav",
            ),
            index.media.map { it.path },
        )
        assertEquals(a.size.toLong(), index.media[0].size)
        assertEquals(Hashing.sha256(a), index.media[0].sha256)
        assertEquals(Hashing.sha256(b), index.media[1].sha256)
    }

    @Test
    fun identicalEntriesInsideTheArchiveAreBothKept() {
        val bytes = photo(9)
        val zip = zipOf(
            "media/photos/a.jpg" to bytes,
            "media/photos/again.jpg" to bytes,
        )
        val index = ArchiveZip.index(zip)
        assertEquals(2, index.media.size)
        assertEquals(
            listOf("media/photos/a.jpg", "media/photos/again.jpg"),
            index.matches(bytes.size.toLong(), Hashing.sha256(bytes)).map { it.path },
        )
    }

    @Test
    fun backslashNamesAreSkippedRatherThanRenamed() {
        val zip = zipOf(
            "media/photos/a.jpg" to photo(1),
            "media\\photos\\windows.jpg" to photo(1),
        )
        val index = ArchiveZip.index(zip)
        assertEquals(listOf("media/photos/a.jpg"), index.media.map { it.path })
    }

    @Test
    fun directoryEntriesAreIgnored() {
        val zip = zipOf(
            "media/photos/" to ByteArray(0),
            "media/photos/a.jpg" to photo(1),
        )
        assertEquals(listOf("media/photos/a.jpg"), ArchiveZip.index(zip).media.map { it.path })
    }

    @Test
    fun readingFromAStreamMatchesReadingFromTheFile() {
        val zip = zipOf("media/photos/a.jpg" to photo(4))
        val fromFile = ArchiveZip.index(zip)
        val fromStream = ByteArrayInputStream(zip.readBytes()).use { ArchiveZip.index(it) }
        assertEquals(fromFile.media, fromStream.media)
    }

    @Test
    fun progressIsReportedAtLeastOnce() {
        val zip = zipOf("media/photos/a.jpg" to photo(1))
        var last = 0
        ArchiveZip.index(zip) { last = it }
        assertTrue(last >= 1)
    }

    @Test
    fun aFileThatIsNotAZipIsRejectedWithAReadableMessage() {
        val notZip = Files.createTempFile("not-a-zip", ".zip").toFile()
        notZip.deleteOnExit()
        notZip.writeText("this is not a zip at all")

        val failure = assertThrows(ArchiveReadException::class.java) {
            ArchiveZip.index(notZip)
        }
        assertTrue(failure.message!!.contains(".zip"))

        // The stream path must refuse it too, not report an empty archive.
        assertThrows(ArchiveReadException::class.java) {
            ArchiveZip.index(ByteArrayInputStream("plain text".toByteArray()))
        }
    }

    @Test
    fun anExtensionlessArchiveEntryIsIndexedWhenItsContentIsMedia() {
        // The case the viewer documents: Instagram stores a photo under a bare
        // timestamp while the JSON refers to it as ....jpg.
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(2048) { (it % 97).toByte() }
        val zip = zipOf(
            "your_instagram_activity/messages/inbox/ana/photos/384312553936495" to jpeg,
            "your_instagram_activity/messages/inbox/ana/photos/1118482956833413" to
                """{"not":"a photo"}""".toByteArray(),
        )

        val index = ArchiveZip.index(zip)
        assertEquals(
            listOf("your_instagram_activity/messages/inbox/ana/photos/384312553936495"),
            index.media.map { it.path },
        )
        assertEquals(Hashing.sha256(jpeg), index.media[0].sha256)
    }

    @Test
    fun aKnownNonMediaExtensionIsNotRescuedByItsContent() {
        // Content sniffing only applies when there is no extension at all.
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(256) { 1 }
        val zip = zipOf("messages/inbox/ana/message_1.json" to jpeg)
        assertTrue(ArchiveZip.index(zip).media.isEmpty())
    }

    @Test
    fun anEmptyButValidZipIndexesToNothing() {
        val empty = zipOf()
        val index = ArchiveZip.index(empty)
        assertTrue(index.isEmpty)
        assertTrue(index.media.isEmpty())
    }
}
