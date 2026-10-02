package com.junki3lab.simpleplayer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(DownloadService.CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
        Library.init(this)
        Downloader.init(this)
    }
}
