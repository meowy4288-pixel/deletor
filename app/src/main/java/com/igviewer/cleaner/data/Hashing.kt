package com.igviewer.cleaner.data

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * SHA-256 helpers. Hashing is the only thing that decides a match, so the hex
 * encoding must be stable and lowercase everywhere.
 */
object Hashing {
    /** Read buffer. 64 KiB keeps syscalls low without holding a whole video in RAM. */
    const val BUFFER_SIZE = 64 * 1024

    /** Files larger than this are not hashed at all (no phone photo/video needs it). */
    const val MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024

    /** Lowercase hex SHA-256 of an in-memory blob. */
    fun sha256(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /**
     * Hash [file] without loading it into memory.
     *
     * Returns null when the file cannot be read, has changed under us, or is
     * bigger than [maxBytes] -- callers treat null as "unknown, never a match".
     */
    fun sha256(file: File, maxBytes: Long = MAX_FILE_BYTES): String? {
        if (!file.isFile) return null
        if (file.length() > maxBytes) return null
        return try {
            file.inputStream().use { sha256(it, maxBytes) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Hash [stream] to EOF, refusing anything over [maxBytes].
     *
     * The cap is enforced while reading, not from a declared size, because a zip
     * entry can lie about its size (or declare none at all).
     */
    fun sha256(stream: InputStream, maxBytes: Long = MAX_FILE_BYTES): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            digest.update(buffer, 0, read)
        }
        return hex(digest.digest())
    }

    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = digits[v ushr 4]
            out[i * 2 + 1] = digits[v and 0x0f]
        }
        return String(out)
    }
}
