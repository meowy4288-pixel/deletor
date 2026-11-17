package com.igviewer.cleaner.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Content sniffing exists for the extensionless media some exports contain.
 * The signatures are the same ones the viewer uses to render those files.
 */
class SniffTest {

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    private fun sniff(vararg parts: ByteArray): MediaKind {
        val head = parts.reduce { acc, part -> acc + part }
        return Sniff.kindOf(head)
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun images() {
        assertEquals(MediaKind.IMAGE, sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0), ByteArray(8)))
        assertEquals(MediaKind.IMAGE, sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(MediaKind.IMAGE, sniff(ascii("GIF89a"), ByteArray(4)))
        assertEquals(MediaKind.IMAGE, sniff(ascii("RIFF"), ByteArray(4), ascii("WEBP")))
        assertEquals(MediaKind.IMAGE, sniff(bytes(0x42, 0x4D), ByteArray(8)))
        assertEquals(MediaKind.IMAGE, sniff(ByteArray(4), ascii("ftypheic"), ByteArray(8)))
    }

    @Test
    fun videos() {
        assertEquals(MediaKind.VIDEO, sniff(ByteArray(4), ascii("ftypisom"), ByteArray(8)))
        assertEquals(MediaKind.VIDEO, sniff(ByteArray(4), ascii("ftypqt  "), ByteArray(8)))
        assertEquals(MediaKind.VIDEO, sniff(ascii("RIFF"), ByteArray(4), ascii("AVI ")))
        assertEquals(MediaKind.VIDEO, sniff(bytes(0x1A, 0x45, 0xDF, 0xA3), ByteArray(8)))
    }

    @Test
    fun audio() {
        assertEquals(MediaKind.AUDIO, sniff(ascii("RIFF"), ByteArray(4), ascii("WAVE")))
        assertEquals(MediaKind.AUDIO, sniff(ByteArray(4), ascii("ftypM4A "), ByteArray(8)))
        assertEquals(MediaKind.AUDIO, sniff(ascii("ID3"), ByteArray(8)))
        assertEquals(MediaKind.AUDIO, sniff(ascii("OggS"), ByteArray(8)))
        assertEquals(MediaKind.AUDIO, sniff(ascii("fLaC"), ByteArray(8)))
    }

    @Test
    fun everythingElseIsNotMedia() {
        assertEquals(MediaKind.OTHER, sniff(ascii("""{"title":"ana"}""")))
        assertEquals(MediaKind.OTHER, sniff(ascii("plain text file")))
        assertEquals(MediaKind.OTHER, sniff(ByteArray(0)))
        // A truncated head must not be mistaken for a signature.
        assertEquals(MediaKind.OTHER, sniff(bytes(0xFF)))
        // The MP3 frame-sync check needs two bytes; one lone 0xFF is not enough.
        assertEquals(MediaKind.OTHER, sniff(bytes(0xFF, 0x00)))
    }

    @Test
    fun aHeadShorterThanTheSignatureIsNotReportedAsOne() {
        // "RIFF" alone, with no room for the form type.
        assertEquals(MediaKind.OTHER, sniff(ascii("RIFF")))
        // "ftyp" without a brand.
        assertEquals(MediaKind.OTHER, sniff(ByteArray(4), ascii("ftyp")))
    }
}
