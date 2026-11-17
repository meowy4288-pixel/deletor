package com.igviewer.cleaner.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** A zip this app refuses to read, with a message safe to show the user. */
class ArchiveReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Streams an export zip and hashes its media, without extracting anything.
 *
 * Nothing is written to disk: the zip is read entry by entry, so a 2 GB export
 * never lands in app storage and can be read straight from a SAF URI.
 *
 * A media entry is one whose name has a media extension, or -- because Instagram
 * sometimes stores a photo under a bare timestamp -- one with no extension whose
 * leading bytes sniff as image/video/audio. Message JSON and the rest of the
 * export are never indexed.
 *
 * Refused outright:
 *   - more than [MAX_ENTRIES] entries (zip-bomb directory).
 *   - an entry that expands past [MAX_ENTRY_BYTES].
 *   - more than [MAX_TOTAL_BYTES] uncompressed in one archive.
 *   - entry names containing a backslash, which are inert on Android but would
 *     be a separator where '\' is one. They are skipped, not renamed.
 */
object ArchiveZip {
    const val MAX_ENTRIES = 2_000_000
    const val MAX_ENTRY_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024 * 1024

    /** How often [onEntries] is called while indexing. */
    private const val PROGRESS_EVERY = 50

    private class EntryRead(val size: Long, val sha256: String, val isMedia: Boolean)

    /**
     * Index a zip on disk.
     *
     * The normal path is a streaming read. Some zips write entry sizes only in a
     * trailing data descriptor; if the stream reader rejects one, it is retried
     * through [ZipFile], which reads the central directory instead.
     */
    fun index(file: File, onEntries: (Int) -> Unit = {}): ArchiveIndex {
        // readStream is called directly (not through the stream overload) so that a
        // ZipException from the local headers can still be retried through the
        // central directory before it is reported as a failure.
        try {
            return file.inputStream().use { readStream(requireZipSignature(it), onEntries) }
        } catch (e: ZipException) {
            return try {
                ZipFile(file).use { readCentralDirectory(it, onEntries) }
            } catch (inner: ZipException) {
                throw ArchiveReadException("This file is not a readable .zip archive.", inner)
            }
        } catch (e: IOException) {
            throw ArchiveReadException("The archive could not be read to the end.", e)
        }
    }

    /** Index a zip from any stream, e.g. a content:// URI from the file picker. */
    fun index(input: InputStream, onEntries: (Int) -> Unit = {}): ArchiveIndex = try {
        readStream(requireZipSignature(input), onEntries)
    } catch (e: ZipException) {
        throw ArchiveReadException("This file is not a readable .zip archive.", e)
    } catch (e: IOException) {
        throw ArchiveReadException("The archive could not be read to the end.", e)
    }

    private fun readStream(input: InputStream, onEntries: (Int) -> Unit): ArchiveIndex {
        val entries = ArrayList<ArchiveMedia>()
        var seen = 0
        var totalBytes = 0L

        ZipInputStream(input.buffered(Hashing.BUFFER_SIZE)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                seen++
                if (seen > MAX_ENTRIES) {
                    throw ArchiveReadException(
                        "This archive has more than $MAX_ENTRIES entries; refusing to read it.",
                    )
                }
                // Every entry is drained, media or not, so the byte caps cover the
                // whole archive. Draining is what closeEntry() would do anyway; doing
                // it here means a hostile archive cannot make us inflate gigabytes of
                // JSON outside the cap.
                val read = readEntry(zip, entry.name) { bytes ->
                    totalBytes += bytes
                    if (totalBytes > MAX_TOTAL_BYTES) {
                        throw ArchiveReadException(
                            "This archive expands past 64 GiB; refusing to read it.",
                        )
                    }
                }
                if (read.isMedia && read.size > 0) {
                    entries.add(ArchiveMedia(entry.name, read.size, read.sha256))
                }
                if (seen % PROGRESS_EVERY == 0) onEntries(seen)
            }
        }
        onEntries(seen)
        return ArchiveIndex.of(entries)
    }

    private fun readCentralDirectory(zip: ZipFile, onEntries: (Int) -> Unit): ArchiveIndex {
        val entries = ArrayList<ArchiveMedia>()
        var totalBytes = 0L
        val names = zip.entries()
        var seen = 0
        while (names.hasMoreElements()) {
            val entry = names.nextElement()
            seen++
            if (seen > MAX_ENTRIES) {
                throw ArchiveReadException(
                    "This archive has more than $MAX_ENTRIES entries; refusing to read it.",
                )
            }
            if (entry.size > MAX_ENTRY_BYTES) {
                throw ArchiveReadException(
                    "An entry in this archive expands past 4 GiB; refusing to read it.",
                )
            }
            val read = zip.getInputStream(entry).use { stream ->
                readEntry(stream, entry.name) { bytes ->
                    totalBytes += bytes
                    if (totalBytes > MAX_TOTAL_BYTES) {
                        throw ArchiveReadException(
                            "This archive expands past 64 GiB; refusing to read it.",
                        )
                    }
                }
            }
            if (read.isMedia && read.size > 0) {
                entries.add(ArchiveMedia(entry.name, read.size, read.sha256))
            }
            if (seen % PROGRESS_EVERY == 0) onEntries(seen)
        }
        onEntries(seen)
        return ArchiveIndex.of(entries)
    }

    /**
     * Drain one entry, hashing it and keeping its first bytes for classification.
     * [countBytes] is called with every chunk so the caller can enforce a total cap
     * that spans the whole archive.
     */
    private fun readEntry(
        stream: InputStream,
        name: String,
        countBytes: (Int) -> Unit,
    ): EntryRead {
        val digest = MessageDigest.getInstance("SHA-256")
        val head = ByteArray(Sniff.HEAD_BYTES)
        var headLength = 0
        val buffer = ByteArray(Hashing.BUFFER_SIZE)
        var size = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            size += read
            if (size > MAX_ENTRY_BYTES) {
                throw ArchiveReadException(
                    "An entry in this archive expands past 4 GiB; refusing to read it.",
                )
            }
            countBytes(read)
            if (headLength < head.size) {
                val take = minOf(head.size - headLength, read)
                System.arraycopy(buffer, 0, head, headLength, take)
                headLength += take
            }
            digest.update(buffer, 0, read)
        }
        return EntryRead(
            size = size,
            sha256 = Hashing.hex(digest.digest()),
            isMedia = isMediaEntry(name, head, headLength),
        )
    }

    /**
     * Name first, content second. A known extension is authoritative (so `.json`
     * is never indexed, whatever its bytes look like); an extensionless entry is
     * sniffed, which is the case Instagram produces with bare timestamps.
     */
    private fun isMediaEntry(name: String, head: ByteArray, headLength: Int): Boolean {
        if (name.contains('\\')) return false
        if (isMediaName(name)) return true
        if (extensionOf(name).isNotEmpty()) return false
        return Sniff.kindOf(head, headLength) != MediaKind.OTHER
    }

    /**
     * Peek at the first four bytes and insist on a zip signature.
     *
     * ZipInputStream is deliberately lenient: handed a text file it reports "no
     * entries" instead of failing, which would reach the UI as "this archive has
     * no media" -- a wrong and confusing answer. Accepts an ordinary local header
     * and the end-of-central-directory record (an empty zip). Anything else is
     * refused before the expensive read starts.
     */
    private fun requireZipSignature(input: InputStream): InputStream {
        val buffered = PushbackInputStream(input, 4)
        val head = ByteArray(4)
        var filled = 0
        while (filled < head.size) {
            val read = buffered.read(head, filled, head.size - filled)
            if (read < 0) break
            filled += read
        }
        if (filled > 0) buffered.unread(head, 0, filled)
        val isZip = filled == head.size &&
            head[0] == 0x50.toByte() && head[1] == 0x4b.toByte() &&
            (
                (head[2] == 0x03.toByte() && head[3] == 0x04.toByte()) ||
                    (head[2] == 0x05.toByte() && head[3] == 0x06.toByte())
                )
        if (!isZip) throw ArchiveReadException("That file is not a .zip archive.")
        return buffered
    }
}
