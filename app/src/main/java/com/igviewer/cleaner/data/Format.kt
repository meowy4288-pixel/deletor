package com.igviewer.cleaner.data

import java.util.Locale

/** Human-readable byte counts for totals and per-file sizes. */
fun humanBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}

/**
 * A file count with thousands separators: 12909 -> "12,909". Device counts reach
 * five digits, where a bare number is hard to compare at a glance.
 */
fun humanCount(count: Int): String = String.format(Locale.US, "%,d", count)

/**
 * A path as the review list shows it: relative to the scan root when it is
 * inside it, so `/storage/emulated/0/DCIM/Camera/x.jpg` reads `DCIM/Camera/x.jpg`.
 */
fun displayPath(root: java.io.File, path: String): String =
    DeviceScan.relativePath(root, java.io.File(path)) ?: path

/**
 * A media duration as a person reads it: `0:07`, `3:42`, `1:02:09`.
 * Returns "" when the duration is unknown, so callers can leave it out instead
 * of printing `0:00` for a file whose header did not say.
 */
fun humanDuration(milliseconds: Long): String {
    if (milliseconds <= 0) return ""
    val totalSeconds = milliseconds / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
