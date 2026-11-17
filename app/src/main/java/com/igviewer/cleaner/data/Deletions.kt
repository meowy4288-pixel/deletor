package com.igviewer.cleaner.data

import java.io.File
import java.nio.file.Files

enum class DeleteOutcome {
    /** The file was removed. */
    DELETED,

    /** Gone before we got to it; nothing was removed by us. */
    MISSING,

    /** Size or content changed since the scan, so it is no longer a known copy. */
    CHANGED,

    /** Outside the scan root, a symlink, an archive, or otherwise not a file we scanned. */
    REFUSED,

    /** The filesystem said no (read-only volume, locked file, ...). */
    FAILED,
}

data class DeleteResult(val path: String, val size: Long, val outcome: DeleteOutcome)

/**
 * Deletes a reviewed copy, after proving it is still the file that was reviewed.
 *
 * The scan -> review -> confirm gap can be hours, and photos change. So the size
 * and SHA-256 are recomputed immediately before removal; a file that changed is
 * refused rather than deleted. The path is also re-resolved through its real
 * parent and required to stay inside the real scan root that produced it, so a
 * directory swapped for a symlink after the scan cannot redirect the delete.
 *
 * Archives are refused by name here as well as in the scanner. A zip can never be
 * one of the export's media files, and "never delete a zip" should not depend on
 * the scanner being the only caller.
 */
object Deletions {

    fun deleteVerified(copy: DeviceCopy): DeleteResult {
        val target = File(copy.path)
        if (DeviceScan.isArchiveName(target.name)) {
            return DeleteResult(copy.path, copy.size, DeleteOutcome.REFUSED)
        }

        val root = File(copy.root)
        val rootReal = try {
            root.canonicalFile
        } catch (_: Exception) {
            return DeleteResult(copy.path, copy.size, DeleteOutcome.REFUSED)
        }
        val parentReal = try {
            target.parentFile?.canonicalFile
        } catch (_: Exception) {
            null
        } ?: return DeleteResult(copy.path, copy.size, DeleteOutcome.REFUSED)

        val inside = parentReal.path == rootReal.path ||
            parentReal.path.startsWith(rootReal.path + File.separator)
        if (!inside) return DeleteResult(copy.path, copy.size, DeleteOutcome.REFUSED)

        val file = File(parentReal, target.name)
        val symlink = try {
            Files.isSymbolicLink(file.toPath())
        } catch (_: Exception) {
            true
        }
        if (symlink) return DeleteResult(copy.path, copy.size, DeleteOutcome.REFUSED)
        if (!file.exists()) return DeleteResult(copy.path, copy.size, DeleteOutcome.MISSING)
        if (!file.isFile) return DeleteResult(copy.path, copy.size, DeleteOutcome.REFUSED)

        if (file.length() != copy.size) {
            return DeleteResult(copy.path, copy.size, DeleteOutcome.CHANGED)
        }
        val hash = Hashing.sha256(file)
            ?: return DeleteResult(copy.path, copy.size, DeleteOutcome.FAILED)
        if (hash != copy.sha256) {
            return DeleteResult(copy.path, copy.size, DeleteOutcome.CHANGED)
        }

        return if (file.delete()) {
            DeleteResult(copy.path, copy.size, DeleteOutcome.DELETED)
        } else {
            DeleteResult(copy.path, copy.size, DeleteOutcome.FAILED)
        }
    }

    /** Plain-language reason shown next to a file in the result list. */
    fun describe(outcome: DeleteOutcome): String = when (outcome) {
        DeleteOutcome.DELETED -> "Deleted"
        DeleteOutcome.MISSING -> "Already gone"
        DeleteOutcome.CHANGED -> "Changed since the scan — kept"
        DeleteOutcome.REFUSED -> "Not deleted for safety"
        DeleteOutcome.FAILED -> "Could not delete"
    }
}
