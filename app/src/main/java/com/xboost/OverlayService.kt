package com.xboost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*

/**
 * Service foreground selama XBoost in-game aktif:
 * - pasang 2 handle tipis di pojok atas kiri & kanan layar (swipe ke dalam = buka panel)
 * - panel: info real time + tombol + daftar app floating
 * - memantau game: kalau game ditutup, mode dihentikan dan semua dipulihkan
 */
class OverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private lateinit var reader: StatsReader
    private val handles = mutableListOf<View>()
    private var scrim: FrameLayout? = null
    private var statsJob: Job? = null
    private var tickJob: Job? = null
    private var statsText: TextView? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        reader = StatsReader(this)
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTORE) {
            scope.launch { shutdown() }
            return START_NOT_STICKY
        }
        if (!Prefs.isActive(this)) { stopSelf(); return START_NOT_STICKY }
        startFg()
        if (handles.isEmpty()) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Izin overlay belum diberikan", Toast.LENGTH_LONG).show()
            } else {
                addHandle(true); addHandle(false)
            }
        }
        if (tickJob == null) tickJob = scope.launch {
            while (isActive) {
                delay(2500)
                if (FocusEngine.tick(applicationContext)) { shutdown(); break }
            }
        }
        return START_STICKY
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    // ---------- handle swipe ----------
    private fun addHandle(left: Boolean) {
        val v = View(this)
        v.background = GradientDrawable().apply { setColor(0x44FFFFFF); cornerRadius = dp(7).toFloat() }
        var downX = 0f
        var fired = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; fired = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    if (!fired && ((left && dx > dp(36)) || (!left && dx < -dp(36)))) {
                        fired = true
                        showPanel(left)
                    }
                }
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            dp(14), dp(180), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or (if (left) Gravity.START else Gravity.END)
            y = dp(60)
        }
        wm.addView(v, lp)
        handles += v
    }

    // ---------- panel ----------
    private fun label(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun showPanel(left: Boolean) {
        if (scrim != null) return
        val game = Prefs.target(this) ?: return

        val sc = FrameLayout(this)
        sc.setBackgroundColor(0x66000000)
        sc.setOnClickListener { closePanel() }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xF2171A21.toInt()); cornerRadius = dp(20).toFloat() }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
        }
        val clp = FrameLayout.LayoutParams(dp(300), MATCH_PARENT, if (left) Gravity.START else Gravity.END)
        clp.setMargins(dp(8), dp(28), dp(8), dp(12))

        card.addView(TextView(this).apply {
            text = "XBoost · ${label(game)}"; setTextColor(Color.WHITE); textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        val st = TextView(this).apply {
            text = "Memuat..."; setTextColor(0xFFB8C0CC.toInt()); textSize = 13f
            typeface = Typeface.MONOSPACE; setPadding(0, dp(10), 0, dp(10))
        }
        statsText = st
        card.addView(st)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Kosongkan RAM"; textSize = 12f
            setOnClickListener { scope.launch { toast(FocusEngine.boostMemory(applicationContext)) } }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "Stop"; textSize = 12f
            setOnClickListener { scope.launch { shutdown() } }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        card.addView(row)

        card.addView(TextView(this).apply {
            text = "App Floating"; setTextColor(Color.WHITE); textSize = 14f
            setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(12), 0, dp(6))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val apps = Prefs.floatApps(this).filter { it != game }
        if (apps.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Belum ada app. Tambahkan di XBoost > tab Floating."
                setTextColor(0xFF8A93A0.toInt()); textSize = 12f
            })
        }
        for (p in apps) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener {
                    scope.launch { toast(FocusEngine.launchFloating(applicationContext, p)) }
                    closePanel()
                }
            }
            runCatching {
                r.addView(ImageView(this).apply { setImageDrawable(packageManager.getApplicationIcon(p)) },
                    LinearLayout.LayoutParams(dp(32), dp(32)))
            }
            r.addView(TextView(this).apply {
                text = label(p); setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(10), 0, 0, 0)
            })
            list.addView(r)
        }
        card.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        sc.addView(card, clp)
        card.translationX = (if (left) -dp(320) else dp(320)).toFloat()
        card.animate().translationX(0f).setDuration(180).start()

        val lp = WindowManager.LayoutParams(
            MATCH_PARENT, MATCH_PARENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(sc, lp)
        scrim = sc

        statsJob = scope.launch {
            while (isActive) {
                val s = withContext(Dispatchers.IO) { runCatching { reader.read(game) }.getOrNull() }
                if (s != null) statsText?.text = fmt(s)
                delay(1000)
            }
        }
    }

    private fun closePanel() {
        statsJob?.cancel(); statsJob = null
        scrim?.let { runCatching { wm.removeView(it) } }
        scrim = null; statsText = null
    }

    private fun f(v: Float?, suf: String) = if (v == null) "-" else "%.0f%s".format(v, suf)
    private fun fmt(s: Stats): String {
        val ghz = if (s.cpuGhz == null) "-" else "%.1fGHz".format(s.cpuGhz)
        return buildString {
            appendLine("FPS      ${s.fps ?: "-"}")
            appendLine("CPU      ${s.cpuUse?.toString() ?: "-"}% · $ghz · ${f(s.cpuTemp, "°C")}")
            appendLine("GPU      ${s.gpuUse?.toString() ?: "-"}% · ${f(s.gpuTemp, "°C")}")
            appendLine("RAM      %.1f / %.1f GB".format(s.ramUsedMb / 1024f, s.ramTotalMb / 1024f))
            appendLine("Baterai  ${s.battPct}% · ${"%.1f".format(s.battTemp)}°C${if (s.charging) " · charging" else ""}")
            append("Memori   %.1f GB bebas".format(s.storageFreeGb))
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ---------- lifecycle ----------
    private suspend fun shutdown() {
        closePanel()
        handles.forEach { runCatching { wm.removeView(it) } }
        handles.clear()
        withContext(Dispatchers.IO) { FocusEngine.restoreAll(applicationContext) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("focus", "XBoost", NotificationManager.IMPORTANCE_LOW))
        val restore = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_RESTORE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, "focus")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("XBoost aktif")
            .setContentText("Swipe dari pojok atas layar untuk buka panel")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop & pulihkan", restore).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    override fun onDestroy() {
        closePanel()
        handles.forEach { runCatching { wm.removeView(it) } }
        handles.clear()
        scope.cancel()
        super.onDestroy()
    }

    companion object { const val ACTION_RESTORE = "com.xboost.RESTORE" }
}
