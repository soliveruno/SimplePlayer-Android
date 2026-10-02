package com.junki3lab.simpleplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- theme

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFFF8A65), secondaryContainer = Color(0xFF3B2F2B))
        else -> lightColorScheme(primary = Color(0xFFD84315), secondaryContainer = Color(0xFFFFE0D6))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// ---------------------------------------------------------------- root

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(player: PlayerUi, nav: NavState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tracks by Library.tracks.collectAsState()

    // Needed on Android 13+ for the playback and download notifications.
    if (Build.VERSION.SDK_INT >= 33) {
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        while (true) { player.tick(); delay(500) }
    }
    LaunchedEffect(nav.sharedUrl) { if (nav.sharedUrl != null) nav.showPlayer = false }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val n = Library.import(ctx, uris)
            Toast.makeText(ctx, "Imported $n song${if (n == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (nav.tab == 0) "Library" else "Download", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        if (nav.tab == 0) IconButton(onClick = { importer.launch(arrayOf("audio/*")) }) {
                            Icon(Icons.Rounded.Add, "Import songs from phone")
                        }
                    },
                )
            },
            bottomBar = {
                Column {
                    if (player.path != null) MiniPlayer(player) { nav.showPlayer = true }
                    NavigationBar {
                        NavigationBarItem(
                            selected = nav.tab == 0, onClick = { nav.tab = 0 },
                            icon = { Icon(Icons.Rounded.LibraryMusic, null) }, label = { Text("Library") },
                        )
                        val jobs by Downloader.jobs.collectAsState()
                        val active = jobs.count { it.active }
                        NavigationBarItem(
                            selected = nav.tab == 1, onClick = { nav.tab = 1 },
                            icon = {
                                BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) {
                                    Icon(Icons.Rounded.Download, null)
                                }
                            },
                            label = { Text("Download") },
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                if (nav.tab == 0) LibraryScreen(tracks, player, onGoDownload = { nav.tab = 1 }, onImport = { importer.launch(arrayOf("audio/*")) })
                else DownloadScreen(nav)
            }
        }

        AnimatedVisibility(
            visible = nav.showPlayer && player.path != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            FullPlayer(player) { nav.showPlayer = false }
        }
    }
    BackHandler(nav.showPlayer) { nav.showPlayer = false }
}

// ---------------------------------------------------------------- shared bits

@Composable
fun Artwork(path: String?, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    val art by produceState<ImageBitmap?>(Library.cachedArt(path), path) {
        value = path?.let { withContext(Dispatchers.IO) { Library.artwork(it) } }
    }
    Box(
        modifier.clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = art
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(
            Icons.Rounded.MusicNote, null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.fillMaxSize(0.45f),
        )
    }
}

// ---------------------------------------------------------------- library

@Composable
fun LibraryScreen(tracks: List<Track>, player: PlayerUi, onGoDownload: () -> Unit, onImport: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<Track?>(null) }
    val shown = remember(tracks, query) {
        if (query.isBlank()) tracks
        else tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
    }

    if (tracks.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("No music yet", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Paste a link in Download, share a link to SimplePlayer from another app, or import songs from your phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onGoDownload) { Text("Download music") }
            TextButton(onClick = onImport) { Text("Import from phone") }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("Search songs") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${shown.size} song${if (shown.size == 1) "" else "s"}",
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = { player.play(shown, 0, shuffled = true) }, enabled = shown.isNotEmpty()) {
                Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Shuffle")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { player.play(shown, 0) }, enabled = shown.isNotEmpty()) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Play")
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
            items(shown, key = { it.path }) { t ->
                TrackRow(
                    track = t,
                    current = t.path == player.path,
                    playing = player.isPlaying,
                    onClick = { player.play(shown, shown.indexOf(t)) },
                    onPlayNext = { player.playNext(t) },
                    onDelete = { toDelete = t },
                )
            }
        }
    }

    toDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete song?") },
            text = { Text("\"${t.title}\" will be removed from your phone.") },
            confirmButton = {
                TextButton(onClick = { player.removeFromQueue(t.path); Library.delete(t); toDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun TrackRow(track: Track, current: Boolean, playing: Boolean, onClick: () -> Unit, onPlayNext: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track.path, Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                listOf(track.artist, formatTime(track.durationMs)).joinToString(" · "),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (current) Icon(
            if (playing) Icons.Rounded.GraphicEq else Icons.Rounded.Pause, null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp),
        )
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Play next") },
                    leadingIcon = { Icon(Icons.Rounded.QueueMusic, null) },
                    onClick = { menu = false; onPlayNext() },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- players

@Composable
fun MiniPlayer(player: PlayerUi, onOpen: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column {
            LinearProgressIndicator(
                progress = { if (player.duration > 0) player.position.toFloat() / player.duration else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                drawStopIndicator = {},
            )
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(player.path, Modifier.size(44.dp), corner = 6.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(player.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(
                        player.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { player.toggle() }) {
                    Icon(if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", Modifier.size(32.dp))
                }
                IconButton(onClick = { player.next() }) { Icon(Icons.Rounded.SkipNext, "Next") }
            }
        }
    }
}

@Composable
fun FullPlayer(player: PlayerUi, onClose: () -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Close", Modifier.size(32.dp)) }
                Text(
                    "Now playing", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(48.dp))
            }
            Spacer(Modifier.weight(0.6f))
            Artwork(player.path, Modifier.fillMaxWidth().aspectRatio(1f), corner = 20.dp)
            Spacer(Modifier.weight(0.6f))
            Text(
                player.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
            )
            Text(
                player.artist, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            val dur = player.duration.coerceAtLeast(1L).toFloat()
            Slider(
                value = dragging ?: player.position.toFloat().coerceIn(0f, dur),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { player.seekTo(it.toLong()) }; dragging = null },
                valueRange = 0f..dur,
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatTime((dragging ?: player.position.toFloat()).toLong()), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(formatTime(player.duration), style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val on = MaterialTheme.colorScheme.primary
                val off = MaterialTheme.colorScheme.onSurfaceVariant
                IconButton(onClick = { player.toggleShuffle() }) {
                    Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (player.shuffle) on else off)
                }
                IconButton(onClick = { player.previous() }, Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(40.dp))
                }
                FilledIconButton(onClick = { player.toggle() }, Modifier.size(76.dp), shape = CircleShape) {
                    Icon(if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", Modifier.size(44.dp))
                }
                IconButton(onClick = { player.next() }, Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(40.dp))
                }
                IconButton(onClick = { player.cycleRepeat() }) {
                    Icon(
                        if (player.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        "Repeat", tint = if (player.repeat == Player.REPEAT_MODE_OFF) off else on,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

// ---------------------------------------------------------------- download

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(nav: NavState) {
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var url by rememberSaveable { mutableStateOf("") }
    var format by rememberSaveable { mutableStateOf("mp3") }
    var playlist by rememberSaveable { mutableStateOf(false) }

    val ready by Downloader.ready.collectAsState()
    val version by Downloader.version.collectAsState()
    val updating by Downloader.updating.collectAsState()
    val updateMsg by Downloader.updateMessage.collectAsState()
    val jobs by Downloader.jobs.collectAsState()

    LaunchedEffect(nav.sharedUrl) {
        nav.sharedUrl?.let { url = it; nav.sharedUrl = null }
    }

    fun start() {
        if (url.isBlank()) return
        Downloader.enqueue(url, format, playlist)
        url = ""
        keyboard?.hide()
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Link") },
                placeholder = { Text("https://…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { start() }),
                trailingIcon = {
                    if (url.isEmpty()) IconButton(onClick = { clipboard.getText()?.text?.let { url = it.trim() } }) {
                        Icon(Icons.Rounded.ContentPaste, "Paste")
                    } else IconButton(onClick = { url = "" }) { Icon(Icons.Rounded.Close, "Clear") }
                },
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SingleChoiceSegmentedButtonRow {
                    listOf("mp3" to "MP3", "m4a" to "M4A").forEachIndexed { i, (key, label) ->
                        SegmentedButton(
                            selected = format == key, onClick = { format = key },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(label) }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("Whole playlist", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Switch(checked = playlist, onCheckedChange = { playlist = it })
            }
        }
        item {
            Button(
                onClick = { start() },
                enabled = url.isNotBlank() && ready != false,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Rounded.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("Download")
            }
        }
        item {
            Text(
                if (format == "mp3") "MP3 works everywhere and gets cover art. M4A keeps the original audio with no re-encoding."
                else "M4A keeps the original audio with no re-encoding (no cover art). MP3 works everywhere and gets cover art.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { EngineCard(ready, version, updating, updateMsg) }

        if (jobs.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloads", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (jobs.any { !it.active }) TextButton(onClick = { Downloader.clearFinished() }) { Text("Clear finished") }
                }
            }
            items(jobs.reversed(), key = { it.id }) { JobCard(it) }
        }
    }
}

@Composable
fun EngineCard(ready: Boolean?, version: String?, updating: Boolean, message: String?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Downloader engine", style = MaterialTheme.typography.labelLarge)
                Text(
                    when (ready) {
                        null -> "Getting ready…"
                        false -> "Couldn't start: ${Downloader.initError}"
                        true -> "yt-dlp ${version ?: ""}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (message != null) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            when {
                ready == null || updating -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                ready == true -> TextButton(onClick = { Downloader.updateYtDlp() }) { Text("Update") }
            }
        }
    }
}

@Composable
fun JobCard(job: DlJob) {
    Card {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(job.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    val status = when (job.status) {
                        DlStatus.Queued -> "Waiting…"
                        DlStatus.Running -> buildString {
                            if (job.item != null) append("Item ${job.item} · ")
                            append(if (job.progress >= 0f) "${(job.progress * 100).toInt()}%" else "Working…")
                        }
                        DlStatus.Done -> "Saved to Library · ${job.format.uppercase()}"
                        DlStatus.Failed -> job.error ?: "Failed"
                        DlStatus.Canceled -> "Canceled"
                    }
                    Text(
                        status, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis,
                        color = if (job.status == DlStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (job.status) {
                    DlStatus.Queued, DlStatus.Running ->
                        IconButton(onClick = { Downloader.cancel(job) }) { Icon(Icons.Rounded.Close, "Cancel") }
                    DlStatus.Failed, DlStatus.Canceled ->
                        IconButton(onClick = { Downloader.retry(job) }) { Icon(Icons.Rounded.Refresh, "Retry") }
                    DlStatus.Done ->
                        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp))
                }
            }
            if (job.status == DlStatus.Running) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.padding(end = 12.dp)) {
                    if (job.progress >= 0f) LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}
