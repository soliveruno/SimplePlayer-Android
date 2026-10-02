package com.junki3lab.simpleplayer

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TAB = "tab"
    }

    private val player = PlayerUi()
    private val nav = NavState()
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent { AppTheme { AppRoot(player, nav) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            nav.sharedUrl = Regex("""https?://\S+""").find(text)?.value ?: text.trim()
            nav.tab = 1
        } else if (intent.hasExtra(EXTRA_TAB)) {
            nav.tab = intent.getIntExtra(EXTRA_TAB, 0)
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({ runCatching { player.attach(future.get()) } }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        player.detach()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }
}

class NavState {
    var tab by mutableIntStateOf(0)
    var sharedUrl by mutableStateOf<String?>(null)
    var showPlayer by mutableStateOf(false)
}

/** Compose-friendly mirror of the player running in [PlaybackService]. */
class PlayerUi {
    private var player: Player? = null

    var isPlaying by mutableStateOf(false)
        private set
    var path by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf("")
        private set
    var artist by mutableStateOf("")
        private set
    var duration by mutableLongStateOf(0L)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
    }

    fun attach(p: Player) {
        player = p
        p.addListener(listener)
        sync(p)
    }

    fun detach() {
        player?.removeListener(listener)
        player = null
    }

    private fun sync(p: Player) {
        isPlaying = p.isPlaying
        val item = p.currentMediaItem
        path = item?.mediaId
        title = p.mediaMetadata.title?.toString() ?: item?.mediaMetadata?.title?.toString() ?: ""
        artist = p.mediaMetadata.artist?.toString() ?: item?.mediaMetadata?.artist?.toString() ?: ""
        duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        position = p.currentPosition
        shuffle = p.shuffleModeEnabled
        repeat = p.repeatMode
    }

    fun tick() {
        player?.let { position = it.currentPosition; if (duration == 0L) sync(it) }
    }

    fun play(tracks: List<Track>, index: Int, shuffled: Boolean = false) {
        val p = player ?: return
        if (tracks.isEmpty()) return
        p.shuffleModeEnabled = shuffled
        val start = if (shuffled) tracks.indices.random() else index
        p.setMediaItems(tracks.map { it.toMediaItem() }, start, 0L)
        p.prepare()
        p.play()
    }

    fun playNext(track: Track) {
        val p = player ?: return
        if (p.mediaItemCount == 0) play(listOf(track), 0)
        else p.addMediaItem(p.currentMediaItemIndex + 1, track.toMediaItem())
    }

    fun removeFromQueue(path: String) {
        val p = player ?: return
        for (i in p.mediaItemCount - 1 downTo 0) if (p.getMediaItemAt(i).mediaId == path) p.removeMediaItem(i)
    }

    fun toggle() {
        val p = player ?: return
        when {
            p.isPlaying -> p.pause()
            p.playbackState == Player.STATE_ENDED -> { p.seekToDefaultPosition(0); p.play() }
            p.playbackState == Player.STATE_IDLE -> { p.prepare(); p.play() }
            else -> p.play()
        }
    }

    fun next() = player?.seekToNext()
    fun previous() = player?.seekToPrevious()
    fun seekTo(ms: Long) { player?.seekTo(ms); position = ms }
    fun toggleShuffle() { player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() {
        player?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }
}
