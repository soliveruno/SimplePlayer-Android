package com.junki3lab.simpleplayer

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps downloads alive when the app is in the background, with a progress notification. */
class DownloadService : Service() {
    companion object {
        const val CHANNEL = "downloads"
        private const val ID = 42
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, ID, build("Starting download…", null, -1f), type)

        if (watcher == null) watcher = scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            Downloader.jobs.collect { list ->
                val active = list.filter { it.active }
                if (active.isEmpty()) {
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                val now = System.currentTimeMillis()
                if (now - lastNotify < 700) return@collect
                lastNotify = now
                val current = active.firstOrNull { it.status == DlStatus.Running } ?: active.first()
                val more = if (active.size > 1) " (+${active.size - 1} queued)" else ""
                nm.notify(ID, build(current.title + more, current.item, current.progress))
            }
        }
        return START_NOT_STICKY
    }

    private fun build(title: String, sub: String?, progress: Float) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(sub ?: if (progress >= 0) "${(progress * 100).toInt()}%" else "Working…")
            .setProgress(100, (progress * 100).toInt().coerceIn(0, 100), progress < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 1,
                    Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 1),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
