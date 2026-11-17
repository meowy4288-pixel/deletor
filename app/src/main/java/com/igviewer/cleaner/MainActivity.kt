package com.igviewer.cleaner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.igviewer.cleaner.data.ArchiveIndex
import com.igviewer.cleaner.data.ArchiveReadException
import com.igviewer.cleaner.data.ArchiveZip
import com.igviewer.cleaner.data.DeleteOutcome
import com.igviewer.cleaner.data.DeleteResult
import com.igviewer.cleaner.data.Deletions
import com.igviewer.cleaner.data.DeviceCopy
import com.igviewer.cleaner.data.DeviceScan
import com.igviewer.cleaner.data.MediaKind
import com.igviewer.cleaner.data.StorageRoots
import com.igviewer.cleaner.data.displayPath
import com.igviewer.cleaner.data.humanBytes
import com.igviewer.cleaner.data.humanCount
import com.igviewer.cleaner.data.kindOf
import com.igviewer.cleaner.data.sampleSizeFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CleanerTheme { CleanerApp() } }
    }
}

internal object Palette {
    val Bg = Color(0xFF0E0E10)
    val Surface = Color(0xFF18181C)
    val Line = Color(0xFF2C2C33)
    val Text = Color(0xFFEDEDEF)
    val Muted = Color(0xFF9A9AA2)
    val Accent = Color(0xFF7CC4FF)
    val Danger = Color(0xFFFF6B6B)
}

@Composable
private fun CleanerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            onPrimary = Color.Black,
            background = Palette.Bg,
            onBackground = Palette.Text,
            surface = Palette.Surface,
            onSurface = Palette.Text,
            error = Palette.Danger,
        ),
        content = content,
    )
}

private enum class Phase { IDLE, INDEXING, READY, SCANNING, REVIEW, DELETING }

/** Everything the one screen needs. Plain mutable state, so no ViewModel plumbing. */
private class CleanerState {
    var phase by mutableStateOf(Phase.IDLE)
    var zipName by mutableStateOf("")
    var archive by mutableStateOf<ArchiveIndex?>(null)
    var entryCount by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)

    var scanRoots by mutableStateOf<List<File>>(emptyList())
    /** Volume currently being walked, for the progress pane. */
    var scanningRoot by mutableStateOf<File?>(null)
    var filesSeen by mutableIntStateOf(0)
    var found by mutableIntStateOf(0)
    var scanCancelled by mutableStateOf(false)
    var unreadableDirs by mutableIntStateOf(0)
    // Diagnostics for the review screen: what the scan actually saw, and why the
    // rest of it could not be matched. Kept as counts, never file paths.
    var mediaSeen by mutableIntStateOf(0)
    var mediaBytes by mutableLongStateOf(0L)
    var sizeAbsentFiles by mutableIntStateOf(0)
    var sizeAbsentBytes by mutableLongStateOf(0L)
    var sizeOnlyFiles by mutableIntStateOf(0)
    var sizeOnlyBytes by mutableLongStateOf(0L)
    var cancelFlag = AtomicBoolean(false)

    var candidates by mutableStateOf<List<DeviceCopy>>(emptyList())
    var selected by mutableStateOf<Set<String>>(emptySet())
    /** Index into [candidates] of the file open in the preview, or -1 for none. */
    var previewAt by mutableIntStateOf(-1)

    var confirmDelete by mutableStateOf(false)
    var deleteDone by mutableIntStateOf(0)
    var deleteTotal by mutableIntStateOf(0)
    var results by mutableStateOf<List<DeleteResult>?>(null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CleanerApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { CleanerState() }

    var hasAccess by remember { mutableStateOf(hasStorageAccess(context)) }

    // The all-files grant happens in system Settings, outside this app, so the state
    // is refreshed whenever we come back rather than assumed after launching it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasAccess = hasStorageAccess(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { hasAccess = hasStorageAccess(context) }

    fun toggle(path: String) {
        state.selected = if (path in state.selected) {
            state.selected - path
        } else {
            state.selected + path
        }
    }

    fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            val opened = runCatching { context.startActivity(intent) }.isSuccess ||
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }.isSuccess
            if (!opened) {
                state.error = "Open Settings → Apps → Special access → All files access and allow " +
                    "Archive Cleaner, then come back."
            }
        } else {
            legacyPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
            )
        }
    }

    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        state.error = null
        state.results = null
        state.candidates = emptyList()
        state.selected = emptySet()
        state.previewAt = -1
        state.entryCount = 0
        state.phase = Phase.INDEXING
        state.zipName = displayName(context, uri)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        ArchiveZip.index(stream) { count -> state.entryCount = count }
                    } ?: throw ArchiveReadException("That file could not be opened.")
                }
            }
            outcome.onSuccess { index ->
                state.archive = index
                state.phase = Phase.READY
                if (index.isEmpty) {
                    state.error = "No photos, videos or voice notes were found in ${state.zipName}."
                }
            }.onFailure { e ->
                state.archive = null
                state.phase = Phase.IDLE
                state.error = (e as? ArchiveReadException)?.message
                    ?: "Could not read that archive. Pick the original .zip download."
            }
        }
    }

    fun startScan() {
        val index = state.archive ?: return
        if (!hasStorageAccess(context)) {
            requestAccess()
            return
        }
        // Internal storage plus any mounted SD card. A card is a separate volume,
        // so scanning only Environment.getExternalStorageDirectory() would miss it.
        val roots = StorageRoots.shared(context)
        if (roots.isEmpty()) {
            state.error = "No readable storage volume was found."
            return
        }
        state.previewAt = -1
        state.scanRoots = roots
        state.scanningRoot = roots.firstOrNull()
        state.phase = Phase.SCANNING
        state.filesSeen = 0
        state.found = 0
        state.error = null
        state.cancelFlag = AtomicBoolean(false)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                DeviceScan.scanAll(
                    roots = roots,
                    index = index,
                    isCancelled = { state.cancelFlag.get() },
                    onProgress = { seen, found, root ->
                        state.filesSeen = seen
                        state.found = found
                        state.scanningRoot = root
                    },
                )
            }
            state.candidates = result.copies.sortedBy { it.path }
            state.selected = emptySet()
            state.scanCancelled = result.cancelled
            state.unreadableDirs = result.unreadableDirs
            state.mediaSeen = result.mediaSeen
            state.mediaBytes = result.mediaBytes
            state.sizeAbsentFiles = result.sizeAbsentFiles
            state.sizeAbsentBytes = result.sizeAbsentBytes
            state.sizeOnlyFiles = result.sizeOnlyFiles
            state.sizeOnlyBytes = result.sizeOnlyBytes
            state.filesSeen = result.filesSeen
            state.found = result.copies.size
            state.phase = Phase.REVIEW
        }
    }

    fun deleteSelected() {
        val chosen = state.candidates.filter { it.path in state.selected }
        if (chosen.isEmpty()) return
        state.confirmDelete = false
        state.phase = Phase.DELETING
        state.deleteDone = 0
        state.deleteTotal = chosen.size
        scope.launch {
            val results = ArrayList<DeleteResult>(chosen.size)
            withContext(Dispatchers.IO) {
                for (copy in chosen) {
                    results.add(Deletions.deleteVerified(copy))
                    state.deleteDone = results.size
                }
            }
            val removed = results
                .filter { it.outcome == DeleteOutcome.DELETED }
                .map { it.path }
                .toSet()
            // Let MediaStore forget the removed files, so the system gallery does not
            // keep showing grey placeholders. Local scan only; no data leaves the device.
            if (removed.isNotEmpty()) {
                MediaScannerConnection.scanFile(context, removed.toTypedArray(), null, null)
            }
            state.results = results
            state.candidates = state.candidates.filter { it.path !in removed }
            state.selected = state.selected - removed
            state.previewAt = -1
            state.phase = Phase.REVIEW
        }
    }

    // Back cancels a scan, but is swallowed while deleting: walking out mid-delete
    // would leave files removed with no result summary shown.
    BackHandler(enabled = state.phase == Phase.SCANNING || state.phase == Phase.DELETING) {
        if (state.phase == Phase.SCANNING) state.cancelFlag.set(true)
    }

    // Registered after the scan handler, so with a preview open Back closes the
    // preview rather than cancelling anything.
    val previewCopy = state.candidates.getOrNull(state.previewAt)
    BackHandler(enabled = previewCopy != null) { state.previewAt = -1 }

    Scaffold(
        containerColor = Palette.Bg,
        topBar = {
            TopAppBar(
                title = { Text("Archive Cleaner", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Palette.Bg,
                    titleContentColor = Palette.Text,
                ),
                actions = {
                    if (state.phase == Phase.READY || state.phase == Phase.REVIEW) {
                        TextButton(onClick = { pickZip.launch(arrayOf("*/*")) }) {
                            Text("New .zip", color = Palette.Accent, fontSize = 13.sp)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            state.error?.let { ErrorCard(it) { state.error = null } }

            when (state.phase) {
                Phase.IDLE -> IdlePane(
                    zipName = state.zipName,
                    onPick = { pickZip.launch(arrayOf("*/*")) },
                )

                Phase.INDEXING -> BusyPane(
                    title = "Reading ${state.zipName.ifBlank { "the archive" }}",
                    detail = "${state.entryCount} zip entries examined",
                    note = "Nothing is extracted: files are hashed as they stream out of the zip.",
                )

                Phase.READY -> {
                    val index = state.archive
                    if (index != null) {
                        ReadyPane(
                            zipName = state.zipName,
                            index = index,
                            hasAccess = hasAccess,
                            onScan = { startScan() },
                            onGrant = { requestAccess() },
                        )
                    }
                }

                Phase.SCANNING -> ScanningPane(
                    filesSeen = state.filesSeen,
                    found = state.found,
                    volume = state.scanningRoot?.let { rootLabel(it) } ?: "storage",
                    onCancel = { state.cancelFlag.set(true) },
                )

                Phase.REVIEW -> ReviewPane(
                    state = state,
                    hasAccess = hasAccess,
                    onGrant = { requestAccess() },
                    onToggle = { path -> toggle(path) },
                    onPreview = { index -> state.previewAt = index },
                    onSelectAll = { state.selected = state.candidates.map { it.path }.toSet() },
                    onClear = { state.selected = emptySet() },
                    onRescan = { startScan() },
                    onDelete = { state.confirmDelete = true },
                )

                Phase.DELETING -> BusyPane(
                    title = "Deleting ${state.deleteDone} of ${state.deleteTotal}",
                    detail = "Each file is re-hashed just before it is removed.",
                    note = "Files that changed since the scan are kept.",
                    progress = if (state.deleteTotal > 0) {
                        state.deleteDone.toFloat() / state.deleteTotal
                    } else {
                        null
                    },
                )
            }
        }
    }

    if (state.confirmDelete) {
        DeleteConfirmDialog(
            count = state.selected.size,
            bytes = state.candidates.filter { it.path in state.selected }.sumOf { it.size },
            onConfirm = { deleteSelected() },
            onDismiss = { state.confirmDelete = false },
        )
    }

    state.results?.let { results ->
        DeleteResultDialog(
            results = results,
            onDismiss = { state.results = null },
        )
    }

    if (previewCopy != null) {
        PreviewOverlay(
            copy = previewCopy,
            position = "${state.previewAt + 1} of ${state.candidates.size}",
            subtitle = rowPath(previewCopy),
            checked = previewCopy.path in state.selected,
            onToggle = { toggle(previewCopy.path) },
            onClose = { state.previewAt = -1 },
            onPrev = if (state.previewAt > 0) {
                { state.previewAt -= 1 }
            } else {
                null
            },
            onNext = if (state.previewAt < state.candidates.lastIndex) {
                { state.previewAt += 1 }
            } else {
                null
            },
        )
    }
}

@Composable
private fun IdlePane(zipName: String, onPick: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Delete the copies your export already has",
            color = Palette.Text,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Pick the Instagram export .zip. This app hashes the media inside it, " +
                "then walks this phone looking for files with exactly the same bytes.",
            color = Palette.Muted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(18.dp))
        Step("1", "Choose the .zip — it is read in place and never modified.")
        Step("2", "Review every copy found: path, size, thumbnail.")
        Step("3", "Tick what to delete, then confirm. Nothing is automatic.")
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onPick,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black),
        ) {
            Text("Choose export .zip", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        if (zipName.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text("Last archive: $zipName", color = Palette.Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(18.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = Palette.Surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("What counts as a copy", color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Only files whose SHA-256 is identical to a media file in the archive. " +
                        "A photo that was re-saved from Instagram is usually re-compressed, so it " +
                        "will not match — that is deliberate, because a hash match cannot be wrong.",
                    color = Palette.Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

@Composable
private fun Step(number: String, text: String) {
    Row(modifier = Modifier.padding(bottom = 10.dp)) {
        Text(number, color = Palette.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Text(text, color = Palette.Text, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun ReadyPane(
    zipName: String,
    index: ArchiveIndex,
    hasAccess: Boolean,
    onScan: () -> Unit,
    onGrant: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(8.dp))
        Text("Archive ready", color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = Palette.Surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(zipName, color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${humanCount(index.media.size)} media files · ${humanBytes(index.totalBytes)}",
                    color = Palette.Muted,
                    fontSize = 13.sp,
                )
                Text(
                    "${humanCount(index.sizes.size)} distinct sizes to look for",
                    color = Palette.Muted,
                    fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        if (!hasAccess) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2118)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("All-files access is needed", color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "To look beyond the folders this app can normally see, Android asks you " +
                            "to allow \"All files access\" in Settings. It is used only to read and " +
                            "delete the files you tick.",
                        color = Palette.Muted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onGrant,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black),
            ) { Text("Open all-files access settings") }
        } else {
            Button(
                onClick = onScan,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black),
            ) { Text("Scan this device for copies", fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
            Spacer(Modifier.height(10.dp))
            Text(
                "Internal storage and any SD card are scanned. Hidden files, the Android/ " +
                    "folder, zips and empty files are skipped, and only files whose size matches " +
                    "something in the archive are read.",
                color = Palette.Muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun ScanningPane(filesSeen: Int, found: Int, volume: String, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        CircularProgressIndicator(color = Palette.Accent, modifier = Modifier.size(42.dp))
        Spacer(Modifier.height(18.dp))
        Text("Scanning $volume", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("$filesSeen files checked", color = Palette.Muted, fontSize = 14.sp)
        Text(
            if (found == 0) "No copies yet" else "$found ${if (found == 1) "copy" else "copies"} found",
            color = if (found > 0) Palette.Accent else Palette.Muted,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(22.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Palette.Accent)
        Spacer(Modifier.height(22.dp))
        TextButton(onClick = onCancel) { Text("Cancel scan", color = Palette.Muted) }
    }
}

@Composable
private fun ReviewPane(
    state: CleanerState,
    hasAccess: Boolean,
    onGrant: () -> Unit,
    onToggle: (String) -> Unit,
    onPreview: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onRescan: () -> Unit,
    onDelete: () -> Unit,
) {
    val selectedBytes = state.candidates.filter { it.path in state.selected }.sumOf { it.size }
    val totalBytes = state.candidates.sumOf { it.size }

    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(Modifier.height(6.dp))
        if (state.scanCancelled) {
            Text(
                "Scan cancelled — these are the copies found up to that point.",
                color = Palette.Danger,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(6.dp))
        }
        if (state.unreadableDirs > 0) {
            Text(
                "${state.unreadableDirs} folders could not be read and were skipped.",
                color = Palette.Muted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            if (state.candidates.isEmpty()) {
                "No identical copies found"
            } else {
                "${state.candidates.size} ${if (state.candidates.size == 1) "copy" else "copies"} · ${humanBytes(totalBytes)}"
            },
            color = Palette.Text,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "${humanCount(state.filesSeen)} files checked${if (state.candidates.isEmpty()) "" else " · ${state.selected.size} ticked (${humanBytes(selectedBytes)})"}",
            color = Palette.Muted,
            fontSize = 12.sp,
        )
        Text(
            "${humanCount(state.mediaSeen)} photos, videos or voice notes on this device (${humanBytes(state.mediaBytes)})",
            color = Palette.Muted,
            fontSize = 12.sp,
        )
        // Always named, not only when there are two: "internal storage only" is the
        // difference between a short scan and a short archive, and it used to be
        // invisible unless a card happened to be found.
        Text(
            "Scanned ${state.scanRoots.joinToString(" + ") { rootLabel(it) }}",
            color = Palette.Muted,
            fontSize = 12.sp,
        )
        if (state.sizeAbsentFiles > 0 || state.sizeOnlyFiles > 0) {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Palette.Surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    if (state.sizeAbsentFiles > 0) {
                        Text(
                            "${humanCount(state.sizeAbsentFiles)} of those (${humanBytes(state.sizeAbsentBytes)}) " +
                                "have a size the archive does not contain at all, so no exact copy of them is in this .zip.",
                            color = Palette.Muted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                    if (state.sizeOnlyFiles > 0) {
                        Text(
                            "${humanCount(state.sizeOnlyFiles)} (${humanBytes(state.sizeOnlyBytes)}) " +
                                "share a size with an archive file but the bytes differ, so they are not the same file.",
                            color = Palette.Muted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
            }
        }
        if (state.candidates.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Tap a thumbnail to view it full screen; tap the row to tick it.",
                color = Palette.Muted,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(10.dp))

        if (!hasAccess) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2118)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("All-files access was turned off", color = Palette.Text, fontSize = 13.sp)
                    Text("Deleting needs it. Re-grant it, then scan again.", color = Palette.Muted, fontSize = 12.sp)
                    TextButton(onClick = onGrant) { Text("Open settings", color = Palette.Accent, fontSize = 13.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (state.candidates.isEmpty()) {
            Spacer(Modifier.height(14.dp))
            val explanation = when {
                state.mediaSeen == 0 ->
                    "The scan found no photos, videos or voice notes on this device. " +
                        "Grant All files access, check that the SD card is mounted, then scan again."
                state.sizeAbsentFiles == state.mediaSeen ->
                    "Every media file the scan could see has a size this archive does not contain, " +
                        "so none of their bytes are in the .zip. That usually means the export was " +
                        "requested with only some chats or categories ticked."
                state.sizeOnlyFiles > 0 ->
                    "The files that share a size with the archive differ in content, so they are " +
                        "re-compressed or otherwise different copies, not the same bytes."
                else ->
                    "No media file on this device is byte-identical to anything in the archive."
            }
            Text(
                explanation,
                color = Palette.Muted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRescan,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black),
            ) { Text("Scan again") }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(state.candidates, key = { _, copy -> copy.path }) { index, copy ->
                    CopyRow(
                        copy = copy,
                        checked = copy.path in state.selected,
                        onToggle = { onToggle(copy.path) },
                        onPreview = { onPreview(index) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onSelectAll) {
                    Text("Select all", color = Palette.Accent, fontSize = 13.sp)
                }
                if (state.selected.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Text("Clear", color = Palette.Muted, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRescan) {
                    Text("Rescan", color = Palette.Muted, fontSize = 13.sp)
                }
            }
            Button(
                onClick = onDelete,
                enabled = hasAccess && state.selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.Danger,
                    contentColor = Color.Black,
                    disabledContainerColor = Palette.Line,
                    disabledContentColor = Palette.Muted,
                ),
            ) {
                Text(
                    if (state.selected.isEmpty()) {
                        "Tick files to delete"
                    } else {
                        "Delete ${state.selected.size} (${humanBytes(selectedBytes)})"
                    },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun CopyRow(
    copy: DeviceCopy,
    checked: Boolean,
    onToggle: () -> Unit,
    onPreview: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Thumbnail(copy.path, onPreview)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = File(copy.path).name,
                color = Palette.Text,
                fontSize = 14.sp,
                maxLines = 1,
            )
            Text(
                text = rowPath(copy),
                color = Palette.Muted,
                fontSize = 11.sp,
                maxLines = 1,
            )
            Text(
                text = "${humanBytes(copy.size)} · in archive as ${copy.archivePaths.firstOrNull() ?: "?"}",
                color = Palette.Muted,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

/**
 * A small preview for images only. Decoded at the size it is shown at, so a
 * 4000px photo does not put a 48 MB bitmap in the row.
 *
 * The tile is its own tap target: tapping it opens the full-screen preview, while
 * tapping anywhere else in the row ticks the file. The border is what makes that
 * distinction visible in a dense list.
 */
@Composable
private fun Thumbnail(path: String, onOpen: () -> Unit) {
    val kind = kindOf(path)
    // An extensionless photo (bare timestamp, as some exports produce) is kind OTHER,
    // so decoding is attempted for anything that is not clearly audio. A failed decode
    // is cheap and simply leaves the glyph.
    val mayBeImage = kind != MediaKind.AUDIO && kind != MediaKind.VIDEO
    val bitmap by produceState<Bitmap?>(initialValue = null, path) {
        value = if (mayBeImage) withContext(Dispatchers.IO) { decodeThumb(path) } else null
    }
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Surface)
            .border(1.dp, Palette.Line, RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = when (kind) {
                    MediaKind.VIDEO -> "▶"
                    MediaKind.AUDIO -> "♪"
                    else -> "•"
                },
                color = Palette.Muted,
                fontSize = 16.sp,
            )
        }
    }
}

private fun decodeThumb(path: String): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, 192)
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

@Composable
private fun BusyPane(title: String, detail: String, note: String, progress: Float? = null) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        CircularProgressIndicator(color = Palette.Accent, modifier = Modifier.size(42.dp))
        Spacer(Modifier.height(18.dp))
        Text(title, color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(detail, color = Palette.Muted, fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))
        if (progress == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Palette.Accent)
        } else {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = Palette.Accent,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(note, color = Palette.Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2E1618)),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = Palette.Text, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Dismiss", color = Palette.Accent, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun DeleteConfirmDialog(count: Int, bytes: Long, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $count ${if (count == 1) "file" else "files"}?") },
        text = {
            Text(
                "${humanBytes(bytes)} will be removed from this device. This cannot be undone " +
                    "and the files do not go to the system trash. Every file is re-hashed first, " +
                    "so anything that changed since the scan is kept.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete permanently", color = Palette.Danger) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep them", color = Palette.Muted) }
        },
    )
}

@Composable
private fun DeleteResultDialog(results: List<DeleteResult>, onDismiss: () -> Unit) {
    val deleted = results.count { it.outcome == DeleteOutcome.DELETED }
    val kept = results.count { it.outcome != DeleteOutcome.DELETED }
    val bytes = results.filter { it.outcome == DeleteOutcome.DELETED }.sumOf { it.size }
    val problems = results.filter { it.outcome != DeleteOutcome.DELETED }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Done") },
        text = {
            Column {
                Text(
                    "Deleted $deleted ${if (deleted == 1) "file" else "files"} (${humanBytes(bytes)}).",
                    color = Palette.Text,
                    fontSize = 14.sp,
                )
                if (kept > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text("$kept kept:", color = Palette.Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    problems.take(12).forEach { r ->
                        Text(
                            "${File(r.path).name} — ${Deletions.describe(r.outcome)}",
                            color = Palette.Muted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                    if (problems.size > 12) {
                        Text("…and ${problems.size - 12} more", color = Palette.Muted, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Palette.Accent) }
        },
    )
}

/**
 * A volume as the UI names it: primary storage or the card's label/UUID. Compared
 * by canonical path because `Environment` and `getExternalFilesDirs` may spell the
 * same volume differently.
 */
private fun rootLabel(root: File): String {
    val primary = runCatching { Environment.getExternalStorageDirectory().canonicalPath }.getOrNull()
    val path = runCatching { root.canonicalPath }.getOrNull() ?: root.absolutePath
    return if (path == primary) "internal storage" else "SD card ${root.name}"
}

/**
 * A candidate's location as the review list shows it. The path is relative to the
 * volume it was found on; a file on the card is prefixed with the card's name, so
 * two identically-named folders on internal storage and the SD card are never
 * confused.
 */
private fun rowPath(copy: DeviceCopy): String {
    val root = File(copy.root)
    val relative = displayPath(root, copy.path)
    val label = rootLabel(root)
    return if (label == "internal storage") relative else "$label · $relative"
}

private fun hasStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

private fun displayName(context: Context, uri: Uri): String {
    val cursor = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    }.getOrNull() ?: return uri.lastPathSegment ?: "export.zip"
    cursor.use {
        if (it.moveToFirst()) {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
                val name = it.getString(index)
                if (!name.isNullOrBlank()) return name
            }
        }
    }
    return uri.lastPathSegment ?: "export.zip"
}
