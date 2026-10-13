package com.openautolink.app.carplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.openautolink.app.MainActivity

/** Process priority only: protocol/resource ownership stays in the coordinator. */
class CarPlaySessionService : Service() {
    override fun onCreate() {
        super.onCreate()
        CarPlaySessionLeases.Registry.created(this)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CarPlaySessionLeases.Registry.started(this, startId)
        return START_NOT_STICKY
    }
    internal fun promote() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "CarPlay connection", NotificationManager.IMPORTANCE_LOW))
        val openApp = PendingIntent.getActivity(this, NOTIFICATION, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            // Platform small icon: non-adaptive, no companion resource dependency.
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("OpenAutoLink CarPlay")
            .setContentText("Connected-device session active")
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }
    override fun onDestroy() {
        CarPlaySessionLeases.Registry.destroyed(this)
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "oal_carplay_session"
        private const val NOTIFICATION = 160
    }
}
