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

/**
 * Service foreground selama XBoost aktif (tanpa bubble/overlay).
 * Tugasnya cuma memantau game: kalau game ditutup atau user tekan Stop di notifikasi,
 * semua pengaturan dipulihkan.
 */
class BoostService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickJob: Job? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTORE) {
            scope.launch { shutdown() }
            return START_NOT_STICKY
        }
        if (!Prefs.isActive(this)) { stopSelf(); return START_NOT_STICKY }
        startFg()
        if (tickJob == null) tickJob = scope.launch {
            while (isActive) {
                delay(2500)
                if (FocusEngine.tick(applicationContext)) { shutdown(); break }
            }
        }
        return START_STICKY
    }

    private suspend fun shutdown() {
        withContext(Dispatchers.IO) { FocusEngine.restoreAll(applicationContext) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("focus", "XBoost", NotificationManager.IMPORTANCE_LOW))
        val restore = PendingIntent.getService(
            this, 1, Intent(this, BoostService::class.java).setAction(ACTION_RESTORE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, "focus")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("XBoost aktif")
            .setContentText("Mode performa berjalan. Stop untuk pulihkan.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop & pulihkan", restore).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object { const val ACTION_RESTORE = "com.xboost.RESTORE" }
}
