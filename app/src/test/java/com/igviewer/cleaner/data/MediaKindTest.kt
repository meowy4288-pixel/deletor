package com.igviewer.cleaner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaKindTest {

    @Test
    fun extensionIsLowercasedAndTakenFromTheLastSegment() {
        assertEquals("jpg", extensionOf("media/photos/IMG_0001.JPG"))
        assertEquals("mp4", extensionOf("reels/clip.Mp4"))
        assertEquals("jpeg", extensionOf("holiday.2020/beach.jpeg"))
    }

    @Test
    fun namesWithoutARealExtensionHaveNone() {
        assertEquals("", extensionOf("README"))
        assertEquals("", extensionOf("archive.tar/"))
        assertEquals("", extensionOf("trailing."))
        // A leading dot is a hidden name, not an extension.
        assertEquals("", extensionOf(".nomedia"))
        assertEquals("", extensionOf("DCIM/.thumbnails"))
    }

    @Test
    fun knownMediaTypesAreClassified() {
        assertEquals(MediaKind.IMAGE, kindOf("a.heic"))
        assertEquals(MediaKind.IMAGE, kindOf("a.webp"))
        assertEquals(MediaKind.VIDEO, kindOf("a.mov"))
        assertEquals(MediaKind.AUDIO, kindOf("a.m4a"))
        assertEquals(MediaKind.OTHER, kindOf("messages/inbox/ana/message_1.json"))
        assertEquals(MediaKind.OTHER, kindOf("export.html"))
    }

    @Test
    fun onlyMediaNamesAreIndexedFromTheArchive() {
        assertTrue(isMediaName("media/photos/2020/01/a.jpg"))
        assertTrue(isMediaName("media/audio/2020/01/voice.wav"))
        assertFalse(isMediaName("messages/inbox/ana/message_1.json"))
        assertFalse(isMediaName("content/posts_1.json"))
        assertFalse(isMediaName("media/photos/.DS_Store"))
    }
}
