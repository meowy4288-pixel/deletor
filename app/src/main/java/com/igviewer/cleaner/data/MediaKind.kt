package com.igviewer.cleaner.data

/**
 * What a file's name says it is.
 *
 * Used to decide whether a named archive entry is media and which glyph a review
 * row shows. Extensionless archive entries are classified by [Sniff] instead, and
 * neither is ever used to decide whether two files match -- that is the SHA-256's
 * job, exclusively. Device files are not filtered by name at all.
 */
enum class MediaKind { IMAGE, VIDEO, AUDIO, OTHER }

private val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff", "dng",
)

private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mov", "m4v", "3gp", "mkv", "webm", "avi", "mpg", "mpeg",
)

private val AUDIO_EXTENSIONS = setOf(
    "mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "amr", "oga",
)

/**
 * The lowercase extension of [name], or "" when there is none.
 *
 * Only the last path segment is considered, so a directory such as `holiday.2020/`
 * does not make its children look like they end in `.2020`, and a leading dot
 * (`.nomedia`, `.thumbnails`) is a hidden name, not an extension.
 */
fun extensionOf(name: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
    val dot = base.lastIndexOf('.')
    if (dot <= 0 || dot == base.lastIndex) return ""
    return base.substring(dot + 1).lowercase()
}

fun kindOf(name: String): MediaKind = when (extensionOf(name)) {
    in IMAGE_EXTENSIONS -> MediaKind.IMAGE
    in VIDEO_EXTENSIONS -> MediaKind.VIDEO
    in AUDIO_EXTENSIONS -> MediaKind.AUDIO
    else -> MediaKind.OTHER
}

/**
 * True when an archive entry with this name could ever have a copy on the
 * device. Message JSON, HTML and the rest of an export are not media, and
 * matching them would only add noise to a delete list.
 */
fun isMediaName(name: String): Boolean = kindOf(name) != MediaKind.OTHER
