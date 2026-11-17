package com.igviewer.cleaner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class DeviceScanTest {

    private fun root(): File = Files.createTempDirectory("scan-root").toFile().apply { deleteOnExit() }

    private fun write(root: File, relative: String, bytes: ByteArray): File {
        val file = File(root, relative)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file
    }

    private fun photo(seed: Int): ByteArray =
        ByteArray(2048) { ((it * 17 + seed) % 251).toByte() }

    private fun indexFor(vararg contents: ByteArray): ArchiveIndex =
        ArchiveIndex.of(
            contents.mapIndexed { i, bytes ->
                ArchiveMedia("media/photos/$i.jpg", bytes.size.toLong(), Hashing.sha256(bytes))
            },
        )

    @Test
    fun findsEveryCopyAndNothingElse() {
        val root = root()
        val wanted = photo(1)
        val sameSizeDifferent = ByteArray(wanted.size) { 0x7f }
        val unrelated = photo(2)

        write(root, "DCIM/Camera/dup.jpg", wanted)
        write(root, "DCIM/Camera/same-size-different.jpg", sameSizeDifferent)
        write(root, "Download/unrelated.jpg", unrelated)
        write(root, "Pictures/nested/deeper/dup2.jpg", wanted)

        val result = DeviceScan.scan(root, indexFor(wanted))
        assertFalse(result.cancelled)
        assertEquals(
            setOf(
                File(root, "DCIM/Camera/dup.jpg").absolutePath,
                File(root, "Pictures/nested/deeper/dup2.jpg").absolutePath,
            ),
            result.copies.map { it.path }.toSet(),
        )
        assertEquals(wanted.size.toLong(), result.copies.first().size)
        // The same-size decoy was hashed (size matched) but did not become a candidate.
        assertTrue(result.filesHashed >= 2)
    }

    @Test
    fun sameSizeDifferentContentIsNotACopy() {
        val root = root()
        val wanted = photo(3)
        val decoy = ByteArray(wanted.size) { 0x01 }
        write(root, "DCIM/Camera/decoy.jpg", decoy)

        val result = DeviceScan.scan(root, indexFor(wanted))
        assertTrue(result.copies.isEmpty())
        assertEquals(1, result.filesHashed)
    }

    @Test
    fun hiddenFilesTheAndroidTreeAndSymlinksAreSkipped() {
        val root = root()
        val wanted = photo(4)

        write(root, "DCIM/Camera/real.jpg", wanted)
        write(root, "DCIM/.hidden.jpg", wanted)
        write(root, "Pictures/.thumbnails/cached.jpg", wanted)
        write(root, "Android/data/com.example/cache/copy.jpg", wanted)

        val link = File(root, "DCIM/Camera/link.jpg")
        val linkCreated = runCatching {
            Files.createSymbolicLink(link.toPath(), File(root, "DCIM/Camera/real.jpg").toPath())
        }.isSuccess
        assumeTrue("symlinks unavailable on this filesystem", linkCreated)

        val result = DeviceScan.scan(root, indexFor(wanted))
        assertEquals(
            listOf(File(root, "DCIM/Camera/real.jpg").absolutePath),
            result.copies.map { it.path },
        )
    }

    @Test
    fun cancelStopsBeforeWalkingEverything() {
        val root = root()
        val wanted = photo(5)
        repeat(20) { write(root, "DCIM/Camera/$it.jpg", wanted) }

        val result = DeviceScan.scan(
            root = root,
            index = indexFor(wanted),
            isCancelled = { true },
        )
        assertTrue(result.cancelled)
        assertTrue(result.copies.isEmpty())
    }

    @Test
    fun anExtensionlessPhotoOnTheDeviceIsStillFound() {
        // Device files are never filtered by name: only size + hash decide. This pins
        // that a bare-timestamp copy is offered while an unrelated extensionless file
        // is not.
        val root = root()
        val wanted = photo(8)
        write(root, "DCIM/Camera/384312553936495", wanted)
        write(root, "Download/notes", ByteArray(wanted.size) { 0x22 })

        val result = DeviceScan.scan(root, indexFor(wanted))
        assertEquals(
            listOf(File(root, "DCIM/Camera/384312553936495").absolutePath),
            result.copies.map { it.path },
        )
        assertEquals(2, result.filesHashed)
    }

    @Test
    fun zipFilesAreNeverCandidatesEvenWhenTheirBytesMatch() {
        // A zip is never media. It is refused by name before any hashing, so it can
        // never reach the review list -- not even a zip whose contents happen to be
        // byte-identical to an archived photo.
        val root = root()
        val wanted = photo(9)
        write(root, "Download/export.zip", wanted)
        write(root, "Download/photo.jpg", wanted)

        val result = DeviceScan.scan(root, indexFor(wanted))
        assertEquals(
            listOf(File(root, "Download/photo.jpg").absolutePath),
            result.copies.map { it.path },
        )
        assertEquals(1, result.filesHashed)

        assertTrue(DeviceScan.shouldSkipFile(File(root, "a.ZIP")))
        assertTrue(DeviceScan.shouldSkipFile(File(root, "backup.7z")))
        assertTrue(DeviceScan.shouldSkipFile(File(root, "archive.tar.gz")))
        // A media name is not an archive, whatever else it contains. (The file has to
        // exist: shouldSkipFile also rejects paths that are not regular files.)
        assertTrue(!DeviceScan.shouldSkipFile(File(root, "Download/photo.jpg")))
    }

    @Test
    fun scanAllCoversEveryVolumeAndRemembersWhichOne() {
        val internal = root()
        val sd = root()
        val wanted = photo(10)
        write(internal, "DCIM/Camera/a.jpg", wanted)
        write(sd, "Download/instagram-export/photos/b.jpg", wanted)

        val result = DeviceScan.scanAll(listOf(internal, sd), indexFor(wanted))
        assertEquals(
            setOf(
                File(internal, "DCIM/Camera/a.jpg").absolutePath,
                File(sd, "Download/instagram-export/photos/b.jpg").absolutePath,
            ),
            result.copies.map { it.path }.toSet(),
        )
        assertEquals(
            setOf(internal.absolutePath, sd.absolutePath),
            result.copies.map { it.root }.toSet(),
        )
        assertEquals(2, result.filesSeen)
    }

    @Test
    fun scanAllStopsWhenCancelledBetweenVolumes() {
        val internal = root()
        val sd = root()
        val wanted = photo(11)
        write(internal, "DCIM/a.jpg", wanted)
        write(sd, "DCIM/b.jpg", wanted)

        val result = DeviceScan.scanAll(
            roots = listOf(internal, sd),
            index = indexFor(wanted),
            isCancelled = { true },
        )
        assertTrue(result.cancelled)
        assertTrue(result.copies.isEmpty())
    }

    @Test
    fun eachVolumeSkipsItsOwnAndroidTree() {
        val sd = root()
        assertTrue(DeviceScan.shouldSkipDir(sd, File(sd, "Android")))
        assertTrue(DeviceScan.shouldSkipDir(sd, File(sd, "Android/data")))
        assertFalse(DeviceScan.shouldSkipDir(sd, File(sd, "DCIM")))
    }

    @Test
    fun filesWithoutASizeMatchAreNeverHashed() {
        val root = root()
        write(root, "DCIM/Camera/odd.jpg", ByteArray(3) { 1 })

        val result = DeviceScan.scan(root, indexFor(photo(6)))
        assertEquals(1, result.filesSeen)
        assertEquals(0, result.filesHashed)
    }

    @Test
    fun everyCopyOfTheSameArchiveFileIsReported() {
        val root = root()
        val wanted = photo(7)
        write(root, "DCIM/Camera/a.jpg", wanted)
        write(root, "Download/b.jpg", wanted)

        val result = DeviceScan.scan(root, indexFor(wanted))
        assertEquals(2, result.copies.size)
        assertTrue(result.copies.all { it.archivePaths == listOf("media/photos/0.jpg") })
    }

    @Test
    fun skipPolicyCoversHiddenAndAndroidDirectories() {
        val root = root()
        assertTrue(DeviceScan.shouldSkipDir(root, File(root, ".thumbnails")))
        assertTrue(DeviceScan.shouldSkipDir(root, File(root, "Android")))
        // Only the shared-storage Android/ tree is off limits; a folder that merely
        // reuses the name deeper in the library is still the user's own data.
        assertFalse(DeviceScan.shouldSkipDir(root, File(root, "DCIM/Android")))
        assertFalse(DeviceScan.shouldSkipDir(root, File(root, "DCIM")))
        assertFalse(DeviceScan.shouldSkipDir(root, File(root, "DCIM/AndroidStuff")))
    }

    @Test
    fun relativePathIsLexicalAndPrefixSafe() {
        val root = File("/tmp/roots/a")
        assertEquals("DCIM/x.jpg", DeviceScan.relativePath(root, File("/tmp/roots/a/DCIM/x.jpg")))
        assertEquals("", DeviceScan.relativePath(root, File("/tmp/roots/a")))
        assertNull(DeviceScan.relativePath(root, File("/tmp/roots/ab/x.jpg")))
        assertNull(DeviceScan.relativePath(root, File("/tmp/roots")))
        assertTrue(DeviceScan.isInside(root, File("/tmp/roots/a/DCIM/x.jpg")))
        assertFalse(DeviceScan.isInside(root, File("/tmp/roots/a")))
    }

    @Test
    fun dotDotSegmentsCannotEscapeTheRoot() {
        val root = File("/tmp/roots/a")
        assertNull(DeviceScan.relativePath(root, File("/tmp/roots/a/../b/x.jpg")))
        assertEquals(
            "x.jpg",
            DeviceScan.relativePath(root, File("/tmp/roots/a/./x.jpg")),
        )
    }

    @Test
    fun normalisedPathsDoNotDependOnThePlatformSeparator() {
        // The scan root is always produced by File(), so this pins the '/'-based
        // normaliser the UI uses for labels.
        val root = Paths.get("/tmp/roots/a").toFile()
        assertEquals("DCIM/x.jpg", displayPath(root, "/tmp/roots/a/DCIM/x.jpg"))
        assertEquals("/somewhere/else/x.jpg", displayPath(root, "/somewhere/else/x.jpg"))
    }
}
