package com.igviewer.cleaner.data

/**
 * Recognises media from its leading bytes.
 *
 * Instagram exports *usually* name every media file with a real extension, but not
 * always: the viewer's own test suite covers exports where a photo is stored under a
 * bare timestamp (`384312553936495`) while the JSON refers to it as `....jpg`. A name
 * allowlist alone would silently skip those entries, so an entry with no extension is
 * classified by content instead of being declared "not media".
 *
 * This is used ONLY to decide whether an archive entry is worth indexing. It never
 * decides a match or a deletion -- that remains size + SHA-256.
 */
object Sniff {
    /** How many leading bytes are needed for every signature below. */
    const val HEAD_BYTES = 32

    fun kindOf(head: ByteArray, length: Int = head.size): MediaKind {
        val n = minOf(length, head.size)
        fun ascii(offset: Int, len: Int): String =
            if (offset + len <= n) String(head, offset, len, Charsets.US_ASCII) else ""
        fun byteAt(i: Int): Int = if (i < n) head[i].toInt() and 0xff else -1

        // JPEG
        if (byteAt(0) == 0xff && byteAt(1) == 0xd8 && byteAt(2) == 0xff) return MediaKind.IMAGE
        // PNG
        if (byteAt(0) == 0x89 && byteAt(1) == 0x50 && byteAt(2) == 0x4e && byteAt(3) == 0x47) {
            return MediaKind.IMAGE
        }
        if (ascii(0, 4) == "GIF8") return MediaKind.IMAGE
        if (ascii(0, 4) == "RIFF") {
            when (ascii(8, 4)) {
                "WEBP" -> return MediaKind.IMAGE
                "WAVE" -> return MediaKind.AUDIO
                "AVI " -> return MediaKind.VIDEO
            }
        }
        if (byteAt(0) == 0x42 && byteAt(1) == 0x4d) return MediaKind.IMAGE // BMP
        // A real ftyp box carries a 4-byte brand; a truncated one is not evidence.
        if (n >= 12 && ascii(4, 4) == "ftyp") {
            val brand = ascii(8, 4)
            return when {
                brand in setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1") ->
                    MediaKind.IMAGE
                brand.startsWith("M4A") -> MediaKind.AUDIO
                else -> MediaKind.VIDEO // qt, M4V and the common mp4 brands
            }
        }
        if (ascii(0, 3) == "ID3") return MediaKind.AUDIO
        if (n >= 2 && byteAt(0) == 0xff && (byteAt(1) and 0xe0) == 0xe0) {
            return MediaKind.AUDIO // MPEG frame sync
        }
        if (ascii(0, 4) == "OggS") return MediaKind.AUDIO
        if (ascii(0, 4) == "fLaC") return MediaKind.AUDIO
        if (byteAt(0) == 0x1a && byteAt(1) == 0x45 && byteAt(2) == 0xdf && byteAt(3) == 0xa3) {
            return MediaKind.VIDEO // Matroska / WebM
        }
        return MediaKind.OTHER
    }
}
