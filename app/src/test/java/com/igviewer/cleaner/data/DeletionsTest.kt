package com.igviewer.cleaner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeletionsTest {

    private fun root(): File = Files.createTempDirectory("delete-root").toFile().apply { deleteOnExit() }

    private fun write(root: File, relative: String, bytes: ByteArray): File {
        val file = File(root, relative)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file
    }

    private fun copyOf(file: File, root: File): DeviceCopy {
        val bytes = file.readBytes()
        return DeviceCopy(
            path = file.absolutePath,
            size = bytes.size.toLong(),
            sha256 = Hashing.sha256(bytes),
            archivePaths = listOf("media/photos/x.jpg"),
            root = root.absolutePath,
        )
    }

    @Test
    fun deletesAnUnchangedCopy() {
        val root = root()
        val file = write(root, "DCIM/Camera/dup.jpg", ByteArray(1024) { (it % 200).toByte() })
        val result = Deletions.deleteVerified(copyOf(file, root))
        assertEquals(DeleteOutcome.DELETED, result.outcome)
        assertFalse(file.exists())
    }

    @Test
    fun refusesAFileWhoseContentChanged() {
        val root = root()
        val file = write(root, "DCIM/Camera/dup.jpg", ByteArray(64) { 1 })
        val snapshot = copyOf(file, root)
        // Same length, different bytes: only the hash can catch this.
        file.writeBytes(ByteArray(64) { 2 })

        val result = Deletions.deleteVerified(snapshot)
        assertEquals(DeleteOutcome.CHANGED, result.outcome)
        assertTrue(file.exists())
    }

    @Test
    fun refusesAFileWhoseSizeChanged() {
        val root = root()
        val file = write(root, "DCIM/Camera/dup.jpg", ByteArray(64) { 1 })
        val snapshot = copyOf(file, root)
        file.writeBytes(ByteArray(65) { 1 })

        val result = Deletions.deleteVerified(snapshot)
        assertEquals(DeleteOutcome.CHANGED, result.outcome)
        assertTrue(file.exists())
    }

    @Test
    fun aFileThatVanishedIsReportedAsMissingNotDeleted() {
        val root = root()
        val file = write(root, "DCIM/Camera/dup.jpg", ByteArray(16) { 3 })
        val snapshot = copyOf(file, root)
        file.delete()

        val result = Deletions.deleteVerified(snapshot)
        assertEquals(DeleteOutcome.MISSING, result.outcome)
    }

    @Test
    fun refusesAnythingOutsideTheScanRoot() {
        val root = root()
        val otherVolume = root()
        val outside = write(otherVolume, "elsewhere/dup.jpg", ByteArray(16) { 4 })

        // The copy claims the first root but lives on the second volume.
        val result = Deletions.deleteVerified(copyOf(outside, root))
        assertEquals(DeleteOutcome.REFUSED, result.outcome)
        assertTrue(outside.exists())
    }

    @Test
    fun refusesARootItself() {
        val root = root()
        val result = Deletions.deleteVerified(
            DeviceCopy(
                path = root.absolutePath,
                size = 0,
                sha256 = Hashing.sha256(ByteArray(0)),
                archivePaths = listOf("x.jpg"),
                root = root.absolutePath,
            ),
        )
        assertEquals(DeleteOutcome.REFUSED, result.outcome)
        assertTrue(root.exists())
    }

    @Test
    fun refusesASymlinkEvenWhenItsTargetLooksLikeACopy() {
        val root = root()
        val target = write(root, "DCIM/Camera/real.jpg", ByteArray(32) { 5 })
        val link = File(root, "DCIM/Camera/link.jpg")
        val ok = runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess
        assumeTrue("symlinks unavailable on this filesystem", ok)

        val result = Deletions.deleteVerified(
            copyOf(target, root).copy(path = link.absolutePath),
        )
        assertEquals(DeleteOutcome.REFUSED, result.outcome)
        assertTrue(target.exists())
        assertTrue(link.exists())
    }

    @Test
    fun refusesWhenAParentDirectoryWasSwappedForASymlink() {
        val root = root()
        val outsideRoot = root()
        val outsideFile = write(outsideRoot, "trojan.jpg", ByteArray(32) { 6 })
        // root/DCIM is a symlink to the outside directory.
        val ok = runCatching {
            Files.createSymbolicLink(File(root, "DCIM").toPath(), outsideRoot.toPath())
        }.isSuccess
        assumeTrue("symlinks unavailable on this filesystem", ok)

        val claimed = File(root, "DCIM/trojan.jpg")
        val result = Deletions.deleteVerified(
            DeviceCopy(
                path = claimed.absolutePath,
                size = 32,
                sha256 = Hashing.sha256(ByteArray(32) { 6 }),
                archivePaths = listOf("a.jpg"),
                root = root.absolutePath,
            ),
        )
        assertEquals(DeleteOutcome.REFUSED, result.outcome)
        assertTrue(outsideFile.exists())
    }

    @Test
    fun refusesAnArchiveEvenWhenTheHashMatches() {
        // The scanner excludes archives, but deletion enforces it again so "no zips"
        // does not depend on the scanner being the only caller.
        val root = root()
        val bytes = ByteArray(128) { 7 }
        val zip = write(root, "Download/instagram-export.zip", bytes)
        val result = Deletions.deleteVerified(copyOf(zip, root))
        assertEquals(DeleteOutcome.REFUSED, result.outcome)
        assertTrue(zip.exists())
    }

    @Test
    fun outcomeLabelsAreUserFacing() {
        assertEquals("Deleted", Deletions.describe(DeleteOutcome.DELETED))
        assertEquals("Changed since the scan — kept", Deletions.describe(DeleteOutcome.CHANGED))
    }
}
