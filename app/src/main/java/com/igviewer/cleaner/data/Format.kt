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
 * A path as the review list shows it: relative to the scan root when it is
 * inside it, so `/storage/emulated/0/DCIM/Camera/x.jpg` reads `DCIM/Camera/x.jpg`.
 */
fun displayPath(root: java.io.File, path: String): String =
    DeviceScan.relativePath(root, java.io.File(path)) ?: path
