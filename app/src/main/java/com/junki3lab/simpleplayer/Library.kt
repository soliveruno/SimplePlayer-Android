package com.junki3lab.simpleplayer

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class Track(
    val path: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val added: Long,
)

fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(path)
    .setUri(Uri.fromFile(File(path)))
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).build())
    .build()

/** All music lives in the app's own Music folder: no storage permission needed. */
object Library {
    lateinit var dir: File
        private set

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks = _tracks.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanLock = Mutex()
    private val meta = ConcurrentHashMap<String, Track>()
    private val art = LruCache<String, ImageBitmap>(60)
    private val noArt: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private val audioExt = setOf("mp3", "m4a", "aac", "opus", "ogg", "flac", "wav", "mka")

    fun init(context: Context) {
        dir = (context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "music"))
            .apply { mkdirs() }
        refresh()
    }

    fun refresh() {
        scope.launch {
            scanLock.withLock {
                val files = dir.listFiles()
                    ?.filter { it.isFile && it.extension.lowercase() in audioExt }
                    .orEmpty()
                _tracks.value = files
                    .map { f -> meta.getOrPut("${f.path}:${f.lastModified()}") { read(f) } }
                    .sortedByDescending { it.added }
            }
        }
    }

    private fun read(f: File): Track {
        var title: String? = null
        var artist: String? = null
        var duration = 0L
        runCatching {
            MediaMetadataRetriever().apply {
                setDataSource(f.path)
                title = extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                artist = extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                duration = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                release()
            }
        }
        return Track(
            path = f.path,
            title = title?.takeIf { it.isNotBlank() } ?: f.nameWithoutExtension,
            artist = artist?.takeIf { it.isNotBlank() } ?: "Unknown artist",
            durationMs = duration,
            added = f.lastModified(),
        )
    }

    fun delete(track: Track) {
        File(track.path).delete()
        art.remove(track.path)
        refresh()
    }

    /** Copies files picked from the phone into the library. Returns how many were added. */
    suspend fun import(context: Context, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (uri in uris) {
            runCatching {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    ?: "track_${System.currentTimeMillis()}.mp3"
                var target = File(dir, name.replace('/', '_'))
                var n = 1
                while (target.exists()) {
                    target = File(dir, "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.', "mp3")}")
                    n++
                }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                    count++
                }
            }
        }
        refresh()
        count
    }

    fun cachedArt(path: String?): ImageBitmap? = path?.let { art.get(it) }

    /** Cover art embedded in the file (yt-dlp embeds the video thumbnail into MP3s). */
    fun artwork(path: String): ImageBitmap? {
        art.get(path)?.let { return it }
        if (path in noArt) return null
        val bytes = runCatching {
            MediaMetadataRetriever().run {
                setDataSource(path)
                embeddedPicture.also { release() }
            }
        }.getOrNull()
        if (bytes == null) {
            noArt += path
            return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 400) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.asImageBitmap()
        if (bmp == null) noArt += path else art.put(path, bmp)
        return bmp
    }
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}
