package com.xboost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*

class FocusService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTORE) {
            scope.launch {
                FocusEngine.restoreAll(applicationContext)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        startFg()
        if (job == null) job = scope.launch {
            while (isActive) {
                delay(2500)
                FocusEngine.tickFloating(applicationContext)
            }
        }
        return START_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("focus", "Mode Fokus", NotificationManager.IMPORTANCE_LOW))
        val restore = PendingIntent.getService(
            this, 1, Intent(this, FocusService::class.java).setAction(ACTION_RESTORE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, "focus")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Mode Fokus aktif")
            .setContentText("App lain dibekukan")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Pulihkan semua", restore).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object { const val ACTION_RESTORE = "com.xboost.RESTORE" }
}
