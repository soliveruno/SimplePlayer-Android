package com.junki3lab.simpleplayer

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

enum class DlStatus { Queued, Running, Done, Failed, Canceled }

data class DlJob(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val format: String,
    val playlist: Boolean,
    val title: String = url,
    val item: String? = null,      // "3 of 12" for playlists
    val progress: Float = -1f,     // 0..1, or -1 while unknown
    val line: String = "",
    val status: DlStatus = DlStatus.Queued,
    val error: String? = null,
) {
    val active get() = status == DlStatus.Queued || status == DlStatus.Running
}

/** Runs yt-dlp one job at a time and saves the audio straight into the library. */
object Downloader {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val cancelRequested = HashSet<String>()

    /** null = still setting up, true = ready, false = failed (see [initError]). */
    private val _ready = MutableStateFlow<Boolean?>(null)
    val ready = _ready.asStateFlow()
    var initError: String? = null
        private set

    private val _jobs = MutableStateFlow<List<DlJob>>(emptyList())
    val jobs = _jobs.asStateFlow()

    private val _version = MutableStateFlow<String?>(null)
    val version = _version.asStateFlow()

    private val _updating = MutableStateFlow(false)
    val updating = _updating.asStateFlow()
    private val _updateMessage = MutableStateFlow<String?>(null)
    val updateMessage = _updateMessage.asStateFlow()

    private val tempDir get() = File(app.cacheDir, "ytdl-temp")

    fun init(context: Context) {
        app = context.applicationContext
        scope.launch {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                _version.value = YoutubeDL.getInstance().version(app)
                _ready.value = true
            } catch (e: Throwable) {
                initError = e.message ?: e.javaClass.simpleName
                _ready.value = false
            }
        }
        scope.launch {
            for (id in queue) {
                if (ready.first { it != null } == true) run(id)
                else update(id) { it.copy(status = DlStatus.Failed, error = "Downloader failed to start: $initError") }
            }
        }
    }

    fun enqueue(url: String, format: String, playlist: Boolean) {
        val job = DlJob(url = url.trim(), format = format, playlist = playlist)
        _jobs.update { it + job }
        queue.trySend(job.id)
        ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
    }

    fun retry(job: DlJob) {
        _jobs.update { list -> list.filterNot { it.id == job.id } }
        enqueue(job.url, job.format, job.playlist)
    }

    fun cancel(job: DlJob) {
        synchronized(cancelRequested) { cancelRequested += job.id }
        if (job.status == DlStatus.Running) YoutubeDL.getInstance().destroyProcessById(job.id)
        update(job.id) { if (it.active) it.copy(status = DlStatus.Canceled) else it }
    }

    fun clearFinished() = _jobs.update { list -> list.filter { it.active } }

    fun updateYtDlp() {
        if (_updating.value) return
        scope.launch {
            _updating.value = true
            _updateMessage.value = null
            _updateMessage.value = try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(app)
                _version.value = YoutubeDL.getInstance().version(app)
                if (status?.name == "DONE") "Updated to ${_version.value}" else "Already up to date"
            } catch (e: Throwable) {
                "Update failed: ${e.message ?: e.javaClass.simpleName}"
            }
            _updating.value = false
        }
    }

    private fun update(id: String, change: (DlJob) -> DlJob) =
        _jobs.update { list -> list.map { if (it.id == id) change(it) else it } }

    private val destination = Regex("""\[(?:download|ExtractAudio)] Destination: (.+)""")
    private val already = Regex("""\[download] (.+) has already been downloaded""")
    private val item = Regex("""\[download] Downloading item (\d+) of (\d+)""")

    private fun run(id: String) {
        val job = jobs.value.find { it.id == id } ?: return
        if (job.status != DlStatus.Queued) return
        update(id) { it.copy(status = DlStatus.Running) }

        tempDir.mkdirs()
        val request = YoutubeDLRequest(job.url).apply {
            addOption("-f", "bestaudio/best")
            addOption("-x")
            addOption("--audio-format", job.format)
            addOption("--audio-quality", "0")
            addOption("--embed-metadata")
            if (job.format == "mp3") {
                // Put the video thumbnail inside the MP3 as square cover art.
                addOption("--embed-thumbnail")
                addOption("--convert-thumbnails", "jpg")
                addOption(
                    "--ppa",
                    "ThumbnailsConvertor+ffmpeg_o:-c:v mjpeg -vf crop=\"'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'\""
                )
            }
            addOption(if (job.playlist) "--yes-playlist" else "--no-playlist")
            if (job.playlist) addOption("--ignore-errors")
            addOption("--no-mtime")
            addOption("--windows-filenames")
            addOption("-P", Library.dir.absolutePath)
            addOption("--paths", "temp:${tempDir.absolutePath}")
            addOption("--cache-dir", File(app.cacheDir, "ytdl-cache").absolutePath)
            addOption("-o", "%(title).120B.%(ext)s")
        }

        try {
            YoutubeDL.getInstance().execute(request, id) { progress, _, line ->
                update(id) { j ->
                    var title = j.title
                    (destination.find(line) ?: already.find(line))?.let {
                        title = File(it.groupValues[1].trim()).nameWithoutExtension
                    }
                    val itemText = item.find(line)?.let { "${it.groupValues[1]} of ${it.groupValues[2]}" } ?: j.item
                    j.copy(
                        title = title,
                        item = itemText,
                        progress = if (progress >= 0f) progress / 100f else j.progress,
                        line = line.take(200),
                    )
                }
                if (destination.containsMatchIn(line) || item.containsMatchIn(line)) Library.refresh()
            }
            update(id) { it.copy(status = DlStatus.Done, progress = 1f) }
        } catch (e: Throwable) {
            val canceled = synchronized(cancelRequested) { id in cancelRequested }
            update(id) {
                if (canceled) it.copy(status = DlStatus.Canceled)
                else it.copy(status = DlStatus.Failed, error = cleanError(e.message, job.playlist))
            }
        } finally {
            tempDir.listFiles()?.forEach { it.deleteRecursively() }
            Library.refresh()
        }
    }

    private fun cleanError(msg: String?, playlist: Boolean): String {
        val text = msg ?: return "Unknown error"
        val err = text.lines().lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim()
            ?: text.trim().takeLast(240)
        return if (playlist) "Some items failed. Last error: $err" else err
    }
}
