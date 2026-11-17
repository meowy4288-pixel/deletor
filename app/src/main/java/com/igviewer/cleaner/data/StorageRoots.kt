package com.igviewer.cleaner.data

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * The shared-storage volumes to scan.
 *
 * Primary storage is what `Environment` returns. A removable card is discoverable
 * through the app's own per-volume directory (`/storage/XXXX-XXXX/Android/data/<pkg>/...`),
 * which exists on every mounted volume and needs no extra permission. The volume root
 * is that path with the `Android/...` tail removed, so scanning starts at the card's
 * top level and the app's own directory is then skipped by the normal `Android/` rule.
 */
object StorageRoots {

    fun shared(context: Context): List<File> {
        val seen = LinkedHashSet<String>()
        val roots = ArrayList<File>()

        fun add(candidate: File?) {
            if (candidate == null) return
            val key = runCatching { candidate.canonicalPath }.getOrNull() ?: candidate.absolutePath
            if (seen.add(key)) roots.add(candidate)
        }

        add(Environment.getExternalStorageDirectory())
        for (dir in context.getExternalFilesDirs(null)) {
            if (dir != null) add(volumeRootOf(dir))
        }
        return roots.filter { it.isDirectory }
    }

    /**
     * `/storage/1234-ABCD/Android/data/<pkg>/files` -> `/storage/1234-ABCD`.
     * Returns null when the path has no `Android` segment (nothing to derive).
     */
    fun volumeRootOf(appDir: File): File? {
        var current: File? = appDir
        while (current != null) {
            if (current.name == "Android") return current.parentFile
            current = current.parentFile
        }
        return null
    }
}
