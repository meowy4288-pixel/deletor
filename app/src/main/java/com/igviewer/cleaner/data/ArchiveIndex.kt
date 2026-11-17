package com.igviewer.cleaner.data

/**
 * One media file inside the export zip.
 *
 * [path] is the zip entry name. It is shown to the user for context and is
 * never used as a filesystem path.
 */
data class ArchiveMedia(
    val path: String,
    val size: Long,
    val sha256: String,
)

/**
 * The archive's media, indexed by size then by content hash.
 *
 * A device file is only hashed when its size matches something in the archive,
 * so an ordinary phone with 50k files costs a handful of reads instead of 50k.
 * Size alone is never a match: [matches] requires the hash to be equal too.
 */
class ArchiveIndex(val media: List<ArchiveMedia>) {

    private val bySize: Map<Long, List<ArchiveMedia>> = media.groupBy { it.size }

    /** Sizes present in the archive; the device scanner's prefilter. */
    val sizes: Set<Long> = bySize.keys

    /** True when [size] alone is enough to justify hashing a device file. */
    fun mayMatch(size: Long): Boolean = size > 0 && size in bySize

    /**
     * Archive entries with exactly this size and content, or empty.
     *
     * An empty list is the only "no match" answer; callers must never fall back
     * to a size-only match.
     */
    fun matches(size: Long, sha256: String): List<ArchiveMedia> {
        val sameSize = bySize[size] ?: return emptyList()
        return sameSize.filter { it.sha256 == sha256 }
    }

    val totalBytes: Long get() = media.sumOf { it.size }

    val isEmpty: Boolean get() = media.isEmpty()

    companion object {
        /**
         * Build an index, dropping zero-byte entries: there is nothing to compare,
         * and every empty file would otherwise "match" every other.
         *
         * Media selection itself happens while the zip is read (extension, or content
         * sniffing for the extensionless files Instagram sometimes produces), so this
         * does not filter on names -- doing that here would throw those entries away.
         */
        fun of(entries: List<ArchiveMedia>): ArchiveIndex =
            ArchiveIndex(entries.filter { it.size > 0 })
    }
}
