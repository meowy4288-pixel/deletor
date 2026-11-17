package com.igviewer.cleaner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.igviewer.cleaner.data.DeviceCopy
import com.igviewer.cleaner.data.MediaKind
import com.igviewer.cleaner.data.humanBytes
import com.igviewer.cleaner.data.humanDuration
import com.igviewer.cleaner.data.kindOf
import com.igviewer.cleaner.data.sampleSizeFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What can be shown for a file, decided once per path off the main thread.
 *
 * A file whose name says nothing (`384312553936495`, as some exports store photos)
 * is still worth opening, so anything that is not clearly audio or video is tried
 * as an image first.
 */
internal sealed interface PreviewContent {
    data object Loading : PreviewContent
    data class Picture(val bitmap: Bitmap) : PreviewContent
    data class Movie(val frame: Bitmap?, val durationMs: Long) : PreviewContent
    data object Sound : PreviewContent
    data object Unavailable : PreviewContent
}

/** Longest side a preview bitmap is decoded at: enough for a phone screen, ~10 MB. */
private const val PREVIEW_MAX_DIMENSION = 2048

internal fun loadPreview(path: String): PreviewContent {
    val kind = kindOf(path)
    if (kind == MediaKind.AUDIO) return PreviewContent.Sound
    if (kind == MediaKind.VIDEO) {
        val info = videoInfo(path)
        return PreviewContent.Movie(info?.first, info?.second ?: 0L)
    }
    decodeImage(path, PREVIEW_MAX_DIMENSION)?.let { return PreviewContent.Picture(it) }
    // Not an image by name or by bytes: it may be an extensionless video, so ask
    // the media framework before declaring it unviewable.
    val info = videoInfo(path)
    if (info != null) return PreviewContent.Movie(info.first, info.second)
    return PreviewContent.Unavailable
}

/**
 * Decode a bitmap no larger than [maxDimension] on its longest side.
 *
 * Bounds are read first so the full-size bitmap is never allocated; without that
 * step a single 48 MP photo can exhaust the heap before `inSampleSize` is applied.
 */
internal fun decodeImage(path: String, maxDimension: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(path, options)
}.getOrNull()

/** A representative frame and the duration, or null when this is not playable video. */
private fun videoInfo(path: String): Pair<Bitmap?, Long>? = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(path)
        val duration = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?: 0L
        // Frame 0 is not always a keyframe; 1 s in is a cheap second attempt.
        val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        frame to duration
    } finally {
        runCatching { retriever.release() }
    }
}.getOrNull()

/**
 * Full-screen look at one candidate, with the delete decision available in place
 * so the list does not have to be scrolled back to.
 *
 * [position] is a "12 of 300" style label; [onPrev]/[onNext] are null at the ends
 * of the list, which disables that arrow instead of wrapping silently.
 */
@Composable
internal fun PreviewOverlay(
    copy: DeviceCopy,
    position: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
    onClose: () -> Unit,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    val content by produceState<PreviewContent>(initialValue = PreviewContent.Loading, copy.path) {
        value = withContext(Dispatchers.IO) { loadPreview(copy.path) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF70A0A0C)),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(position, color = Palette.Muted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) {
                    Text("Close", color = Palette.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                when (val shown = content) {
                    PreviewContent.Loading -> Text("Opening…", color = Palette.Muted, fontSize = 14.sp)
                    is PreviewContent.Picture -> Image(
                        bitmap = shown.bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    is PreviewContent.Movie -> MoviePreview(copy.path, shown)
                    PreviewContent.Sound -> SoundPreview(copy.path)
                    PreviewContent.Unavailable -> Text(
                        "No preview for this file type.",
                        color = Palette.Muted,
                        fontSize = 14.sp,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = File(copy.path).name,
                color = Palette.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
            )
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = Palette.Muted, fontSize = 11.sp, maxLines = 2)
            Text(
                facts(copy, content),
                color = Palette.Muted,
                fontSize = 11.sp,
                maxLines = 2,
            )

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onToggle)
                        .padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = { onToggle() })
                    Text(
                        if (checked) "Marked for deletion" else "Delete this copy",
                        color = if (checked) Palette.Danger else Palette.Text,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                ArrowButton("‹", enabled = onPrev != null, onClick = { onPrev?.invoke() })
                ArrowButton("›", enabled = onNext != null, onClick = { onNext?.invoke() })
            }
        }
    }
}

/** Size, duration when there is one, and the archive entry this file equals. */
private fun facts(copy: DeviceCopy, content: PreviewContent): String {
    val parts = ArrayList<String>(3)
    parts.add(humanBytes(copy.size))
    val duration = (content as? PreviewContent.Movie)?.durationMs?.let { humanDuration(it) }
    if (!duration.isNullOrBlank()) parts.add(duration)
    parts.add("in archive as ${copy.archivePaths.firstOrNull() ?: "?"}")
    return parts.joinToString(" · ")
}

@Composable
private fun ArrowButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            glyph,
            color = if (enabled) Palette.Accent else Palette.Line,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Videos play in place. If the device has no decoder for this file (or the file is
 * damaged) the error listener swaps in the still frame rather than showing a black
 * rectangle, so a failed preview never hides what the file is.
 */
@Composable
private fun MoviePreview(path: String, movie: PreviewContent.Movie) {
    var playable by remember(path) { mutableStateOf(true) }

    if (playable) {
        // key(path): AndroidView keeps its factory instance across recompositions,
        // so without this the previous video would keep playing after ‹ / ›.
        key(path) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    VideoView(context).apply {
                        setOnErrorListener { _, _, _ ->
                            playable = false
                            true
                        }
                        setVideoPath(path)
                        setOnPreparedListener { player ->
                            player.isLooping = true
                            start()
                        }
                    }
                },
                // Leaving the preview must not leave a video playing behind the list.
                onRelease = { view -> runCatching { view.stopPlayback() } },
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            movie.frame?.let { frame ->
                Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "This device cannot play this video — showing a still frame.",
                color = Palette.Muted,
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * Voice notes and audio have nothing to look at, so they get a play button.
 * The player is released with the preview; nothing keeps playing behind the list.
 */
@Composable
private fun SoundPreview(path: String) {
    val scope = rememberCoroutineScope()
    var playing by remember(path) { mutableStateOf(false) }
    var prepared by remember(path) { mutableStateOf(false) }
    var failed by remember(path) { mutableStateOf(false) }
    val player = remember(path) { MediaPlayer() }

    DisposableEffect(path) {
        player.setOnCompletionListener { playing = false }
        onDispose {
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("♪", color = Palette.Muted, fontSize = 56.sp)
        Spacer(Modifier.height(12.dp))
        if (failed) {
            Text("This device cannot play this audio file.", color = Palette.Muted, fontSize = 13.sp)
        } else {
            TextButton(
                onClick = {
                    if (playing) {
                        runCatching { player.pause() }
                        playing = false
                    } else {
                        scope.launch {
                            runCatching {
                                if (!prepared) {
                                    withContext(Dispatchers.IO) {
                                        player.setDataSource(path)
                                        player.prepare()
                                    }
                                    prepared = true
                                }
                                player.start()
                                playing = true
                            }.onFailure {
                                failed = true
                                playing = false
                            }
                        }
                    }
                },
            ) {
                Text(
                    if (playing) "Pause" else "Play",
                    color = Palette.Accent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
