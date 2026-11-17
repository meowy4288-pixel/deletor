package com.igviewer.cleaner.data

import java.io.File
import java.nio.file.Files

/** A device file whose contents are identical to something in the archive. */
data class DeviceCopy(
    /** Absolute path of the file on shared storage. Never leaves the app. */
    val path: String,
    val size: Long,
    val sha256: String,
    /** Archive entry names with these exact bytes; the first is shown to the user. */
    val archivePaths: List<String>,
    /**
     * The shared-storage volume root this file was found under (internal storage or
     * an SD card). Deletion re-checks containment against this, so a candidate can
     * never be deleted through a different volume's path.
     */
    val root: String,
)

data class ScanResult(
    val copies: List<DeviceCopy>,
    val filesSeen: Int,
    val filesHashed: Int,
    /** Directories that could not be listed (permissions, vanished media). */
    val unreadableDirs: Int,
    val cancelled: Boolean,
    /**
     * Media-named device files considered, and their total size. Archives, hidden
     * files and empty files are excluded, so this is what the scan actually had a
     * chance to match. Extensionless files are not counted here (nothing about
     * their name says they are media) even though they are still hashed and matched.
     */
    val mediaSeen: Int = 0,
    val mediaBytes: Long = 0L,
    /**
     * Media-named files whose exact byte size occurs nowhere in the archive. Their
     * contents can never match, so they are never hashed: this is the count that
     * separates "the archive is missing these files" from "the bytes differ".
     */
    val sizeAbsentFiles: Int = 0,
    val sizeAbsentBytes: Long = 0L,
    /** Media-named files whose size matched an archive entry but whose bytes did not. */
    val sizeOnlyFiles: Int = 0,
    val sizeOnlyBytes: Long = 0L,
)

/**
 * Walks shared storage looking for byte-identical copies of the archive's media.
 *
 * Order of checks matters for cost: name and size are free, hashing is not, and
 * only a size that actually occurs in the archive is ever hashed. Nothing is
 * deleted here; this only produces candidates for review.
 *
 * Skipped on purpose: hidden names, the `Android/` tree (other apps' private
 * files plus our own cache), symlinks, empty files, and archives/zips. An archive
 * can never be one of the export's media files, so it is refused by name before
 * any I/O, and refused again at delete time.
 */
object DeviceScan {

    /**
     * Archive formats that must never appear in a delete list, whatever their
     * bytes. The picked export zip itself is one of these.
     */
    private val ARCHIVE_EXTENSIONS = setOf(
        "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz",
        "zst", "lz", "lzma", "cab", "iso", "apk", "aab", "jar",
    )

    fun isArchiveName(name: String): Boolean = extensionOf(name) in ARCHIVE_EXTENSIONS

    /** Directories whose contents are never scanned. */
    fun shouldSkipDir(root: File, dir: File): Boolean {
        if (dir.name.startsWith(".")) return true
        val rel = relativePath(root, dir) ?: return true
        return rel == "Android" || rel.startsWith("Android/")
    }

    /** Files that are never even considered. */
    fun shouldSkipFile(file: File): Boolean =
        file.name.startsWith(".") ||
            isArchiveName(file.name) ||
            !file.isFile ||
            file.length() <= 0L

    /**
     * [file]'s path relative to [root] using `/` separators, or null when it is
     * not strictly inside [root]. Purely lexical; symlinks are refused separately.
     */
    fun relativePath(root: File, file: File): String? {
        val rootPath = normalise(root)
        val filePath = normalise(file)
        if (filePath == rootPath) return ""
        val prefix = if (rootPath.endsWith("/")) rootPath else "$rootPath/"
        if (!filePath.startsWith(prefix)) return null
        return filePath.substring(prefix.length)
    }

    fun isInside(root: File, file: File): Boolean {
        val relative = relativePath(root, file) ?: return false
        return relative.isNotEmpty()
    }

    private fun normalise(file: File): String {
        val raw = file.absolutePath.replace(File.separatorChar, '/')
        val out = ArrayList<String>()
        for (part in raw.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                else -> out.add(part)
            }
        }
        return "/" + out.joinToString("/")
    }

    private fun isSymlink(file: File): Boolean =
        try {
            Files.isSymbolicLink(file.toPath())
        } catch (_: Exception) {
            // If it cannot even be inspected, treat it as unsafe.
            true
        }

    /**
     * Scan [root]. [isCancelled] is checked between every directory and file, so
     * a cancel is honoured within one file, not after the whole device.
     */
    fun scan(
        root: File,
        index: ArchiveIndex,
        isCancelled: () -> Boolean = { false },
        onProgress: (filesSeen: Int, copiesFound: Int) -> Unit = { _, _ -> },
    ): ScanResult {
        val copies = ArrayList<DeviceCopy>()
        var filesSeen = 0
        var filesHashed = 0
        var unreadableDirs = 0
        var mediaSeen = 0
        var mediaBytes = 0L
        var sizeAbsentFiles = 0
        var sizeAbsentBytes = 0L
        var sizeOnlyFiles = 0
        var sizeOnlyBytes = 0L
        val pending = ArrayDeque<File>()
        pending.addLast(root)

        fun finish(cancelled: Boolean) = ScanResult(
            copies = copies.toList(),
            filesSeen = filesSeen,
            filesHashed = filesHashed,
            unreadableDirs = unreadableDirs,
            cancelled = cancelled,
            mediaSeen = mediaSeen,
            mediaBytes = mediaBytes,
            sizeAbsentFiles = sizeAbsentFiles,
            sizeAbsentBytes = sizeAbsentBytes,
            sizeOnlyFiles = sizeOnlyFiles,
            sizeOnlyBytes = sizeOnlyBytes,
        )

        while (pending.isNotEmpty()) {
            if (isCancelled()) return finish(true)
            val dir = pending.removeLast()
            val children = dir.listFiles()
            if (children == null) {
                unreadableDirs++
                continue
            }
            for (child in children) {
                if (isCancelled()) return finish(true)
                // Checked before isDirectory/isFile because those follow symlinks.
                if (isSymlink(child)) continue
                if (child.isDirectory) {
                    if (!shouldSkipDir(root, child)) pending.addLast(child)
                    continue
                }
                filesSeen++
                if (shouldSkipFile(child)) continue
                val size = child.length()
                // Diagnostics count media-named files only: a .json or .txt of the
                // same size is not something the user expects to be told about.
                val mediaNamed = isMediaName(child.name)
                if (mediaNamed) {
                    mediaSeen++
                    mediaBytes += size
                }
                // The prefilter: this one line is what makes a whole-device scan cheap.
                if (!index.mayMatch(size)) {
                    if (mediaNamed) {
                        sizeAbsentFiles++
                        sizeAbsentBytes += size
                    }
                    continue
                }
                val hash = Hashing.sha256(child) ?: continue
                filesHashed++
                val matches = index.matches(size, hash)
                if (matches.isEmpty()) {
                    if (mediaNamed) {
                        sizeOnlyFiles++
                        sizeOnlyBytes += size
                    }
                    continue
                }
                copies.add(
                    DeviceCopy(
                        path = child.absolutePath,
                        size = size,
                        sha256 = hash,
                        archivePaths = matches.map { it.path }.distinct(),
                        root = root.absolutePath,
                    ),
                )
                onProgress(filesSeen, copies.size)
            }
            onProgress(filesSeen, copies.size)
        }
        return finish(false)
    }

    /**
     * Scan every volume in [roots] (internal storage and any mounted SD card),
     * merging the results. Counts stay cumulative and [onProgress] also reports
     * which volume is being walked, so the UI can say where it is.
     */
    fun scanAll(
        roots: List<File>,
        index: ArchiveIndex,
        isCancelled: () -> Boolean = { false },
        onProgress: (filesSeen: Int, copiesFound: Int, root: File) -> Unit = { _, _, _ -> },
    ): ScanResult {
        val copies = ArrayList<DeviceCopy>()
        var filesSeen = 0
        var filesHashed = 0
        var unreadableDirs = 0
        var mediaSeen = 0
        var mediaBytes = 0L
        var sizeAbsentFiles = 0
        var sizeAbsentBytes = 0L
        var sizeOnlyFiles = 0
        var sizeOnlyBytes = 0L

        fun finish(cancelled: Boolean) = ScanResult(
            copies = copies.toList(),
            filesSeen = filesSeen,
            filesHashed = filesHashed,
            unreadableDirs = unreadableDirs,
            cancelled = cancelled,
            mediaSeen = mediaSeen,
            mediaBytes = mediaBytes,
            sizeAbsentFiles = sizeAbsentFiles,
            sizeAbsentBytes = sizeAbsentBytes,
            sizeOnlyFiles = sizeOnlyFiles,
            sizeOnlyBytes = sizeOnlyBytes,
        )

        for (root in roots) {
            if (isCancelled()) return finish(true)
            val seenBase = filesSeen
            val copiesBase = copies.size
            val result = scan(root, index, isCancelled) { rootSeen, rootFound ->
                onProgress(seenBase + rootSeen, copiesBase + rootFound, root)
            }
            copies.addAll(result.copies)
            filesSeen += result.filesSeen
            filesHashed += result.filesHashed
            unreadableDirs += result.unreadableDirs
            mediaSeen += result.mediaSeen
            mediaBytes += result.mediaBytes
            sizeAbsentFiles += result.sizeAbsentFiles
            sizeAbsentBytes += result.sizeAbsentBytes
            sizeOnlyFiles += result.sizeOnlyFiles
            sizeOnlyBytes += result.sizeOnlyBytes
            if (result.cancelled) return finish(true)
        }
        return finish(false)
    }
}
