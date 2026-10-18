package com.xboost

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

object FocusEngine {
    private const val GRACE_MS = 15_000L   // jeda awal sebelum cek game masih jalan
    private const val MISS_LIMIT = 8       // 8 x 2,5 dtk = 20 dtk game tidak terlihat -> mode berhenti
    @Volatile private var startedAt = 0L
    @Volatile private var gameMiss = 0
    @Volatile private var tickCount = 0

    private val VISIBLE = Regex("visible(Requested)?=true", RegexOption.IGNORE_CASE)
    private val PKG_IN_HEAD = Regex(" ([A-Za-z0-9._]+)/")
    private val SUSPEND_OK = Regex("Package (\\S+) new suspended state: true")
    private val GOV_OK = Regex("^[A-Za-z0-9_.-]+$")

    private fun freeRamMb(ctx: Context): Long {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.availMem / (1024 * 1024)
    }

    // ---------- bekukan / pulihkan app (semua user, termasuk clone) ----------

    /** Return jumlah app yang gagal dikonfirmasi terbekukan. */
    private fun freezeAll(ctx: Context, victims: List<Target>): Int {
        Prefs.addSuspendedAll(ctx, victims.map { it.key }) // dicatat dulu, baru dibekukan (aman kalau crash)
        var failed = 0
        victims.groupBy { it.user }.forEach { (u, list) ->
            list.chunked(25).forEach { chunk ->
                val cmd = chunk.joinToString("; ") {
                    "am force-stop --user $u ${it.pkg}; pm suspend --user $u ${it.pkg}"
                }
                val ok = SUSPEND_OK.findAll(ShizukuShell.exec(cmd).out).map { it.groupValues[1] }.toSet()
                failed += chunk.count { it.pkg !in ok }
            }
        }
        return failed
    }

    private fun unfreezeAll(ctx: Context) {
        Prefs.suspended(ctx).mapNotNull { k ->
            val user = if (':' in k) k.substringBefore(':').toIntOrNull() ?: 0 else 0
            val pkg = k.substringAfter(':')
            if (AppRepo.safe(pkg)) Target(user, pkg) else null
        }.groupBy { it.user }.forEach { (u, list) ->
            list.chunked(25).forEach { chunk ->
                ShizukuShell.exec(chunk.joinToString("; ") { "pm unsuspend --user $u ${it.pkg}" })
            }
        }
        Prefs.clearSuspended(ctx)
    }

    // ---------- notifikasi ----------

    private fun silenceNotifications(ctx: Context) {
        SettingsGuard.set(ctx, "zen_mode", "2") // catat nilai asli dulu
        ShizukuShell.exec("cmd notification set_dnd none")
        SettingsGuard.set(ctx, "heads_up_notifications_enabled", "0")
    }

    private fun restoreNotifications(ctx: Context) {
        val orig = Prefs.orig(ctx, "zen_mode") ?: return
        val mode = when (orig) { "1" -> "priority"; "2" -> "none"; "3" -> "alarms"; else -> "off" }
        ShizukuShell.exec("cmd notification set_dnd $mode")
    }

    // ---------- performa ----------

    /**
     * Lock governor CPU + GPU ke "performance". Hanya jalan kalau Shizuku dijalankan sebagai root
     * (file sysfs tidak bisa ditulis oleh shell biasa). Return jumlah node yang berhasil.
     */
    private fun lockCpuGpu(ctx: Context): Int {
        val nodes = ShizukuShell.exec(
            "for f in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor " +
                "/sys/class/devfreq/*gpu*/governor /sys/class/devfreq/*mali*/governor " +
                "/sys/class/kgsl/kgsl-3d0/devfreq/governor; " +
                "do [ -w \"\$f\" ] && echo \"\$f \$(cat \"\$f\")\"; done"
        ).out.lines().map { it.trim() }.filter { it.startsWith("/") && ' ' in it }
        var n = 0
        for (line in nodes) {
            val path = line.substringBefore(' ')
            val gov = line.substringAfter(' ').trim()
            if (!GOV_OK.matches(gov)) continue
            if (Prefs.gov(ctx, path) == null) Prefs.setGov(ctx, path, gov)
            ShizukuShell.exec("echo performance > \"$path\"")
            if (ShizukuShell.exec("cat \"$path\"").out.trim() == "performance") n++
        }
        return n
    }

    private fun unlockCpuGpu(ctx: Context) {
        Prefs.govKeys(ctx).forEach { path ->
            val g = Prefs.gov(ctx, path)
            if (g != null && GOV_OK.matches(g)) ShizukuShell.exec("echo $g > \"$path\"")
        }
        Prefs.clearGov(ctx)
    }

    /** Mode performa: fixed performance mode + lock CPU/GPU (kalau root). Return keterangan. */
    private fun lockPerformance(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= 31) ShizukuShell.exec("cmd power set-fixed-performance-mode-enabled true")
        val n = lockCpuGpu(ctx)
        return if (n > 0) "CPU/GPU di-lock ($n node)"
        else "CPU/GPU tidak bisa di-lock tanpa root, pakai fixed performance mode"
    }

    /** Optimasi game: Game Mode performance, thermal override, battery saver off, refresh rate maksimum. */
    private fun optimizeGame(ctx: Context, game: String) {
        val cmds = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) cmds += "cmd game mode performance $game"
        if (Build.VERSION.SDK_INT >= 29) cmds += "cmd thermalservice override-status 0"
        if (cmds.isNotEmpty()) ShizukuShell.exec(cmds.joinToString("; "))
        SettingsGuard.set(ctx, "low_power", "0")
        SettingsGuard.set(ctx, "adaptive_battery_management_enabled", "0")
        val hz = runCatching {
            (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY).supportedModes.maxOf { it.refreshRate }.roundToInt()
        }.getOrNull()
        if (hz != null && hz > 0) {
            SettingsGuard.set(ctx, "system:min_refresh_rate", hz.toString())
            SettingsGuard.set(ctx, "system:peak_refresh_rate", hz.toString())
        }
    }

    // ---------- start / stop ----------

    suspend fun start(ctx: Context, game: String): String = withContext(Dispatchers.IO) {
        if (!ShizukuShell.isReady()) return@withContext "Shizuku belum siap"
        if (!AppRepo.safe(game)) return@withContext "Nama package tidak valid"
        val keep = AppRepo.protectedPackages(ctx) + game
        val before = freeRamMb(ctx)
        Prefs.setTarget(ctx, game)
        Prefs.setActive(ctx, true)
        startedAt = System.currentTimeMillis()
        gameMiss = 0
        tickCount = 0

        if (Prefs.animOff(ctx)) SettingsGuard.ANIM_KEYS.forEach { SettingsGuard.set(ctx, it, "0") }
        if (Prefs.notifOff(ctx)) silenceNotifications(ctx)

        val victims = AppRepo.targets().filter { it.pkg !in keep }
        val failed = freezeAll(ctx, victims)

        val perfMsg = if (Prefs.lockPerf(ctx)) lockPerformance(ctx) else ""
        if (Prefs.maxPerf(ctx)) optimizeGame(ctx, game)
        delay(500)

        buildString {
            append("XBoost aktif: ${victims.size - failed} app dibekukan")
            if (failed > 0) append(" ($failed gagal)")
            append(". RAM bebas ${before}MB -> ${freeRamMb(ctx)}MB")
            if (perfMsg.isNotEmpty()) append(". $perfMsg")
        }
    }

    /** Kembalikan semuanya: unsuspend, animasi, notifikasi, governor, setting. Aman dipanggil berkali-kali. */
    suspend fun restoreAll(ctx: Context): String = withContext(Dispatchers.IO) {
        if (!ShizukuShell.isReady()) return@withContext "Shizuku belum siap, nyalakan Shizuku lalu coba lagi"
        unfreezeAll(ctx)
        unlockCpuGpu(ctx)
        restoreNotifications(ctx) // harus sebelum SettingsGuard.restoreAll (butuh nilai asli zen_mode)
        val resetGame = Prefs.target(ctx)?.takeIf { AppRepo.safe(it) }?.let { "; cmd game mode standard $it" } ?: ""
        ShizukuShell.exec("cmd power set-fixed-performance-mode-enabled false; cmd thermalservice reset$resetGame")
        SettingsGuard.restoreAll(ctx)
        Prefs.setActive(ctx, false)
        "Semua dipulihkan"
    }

    suspend fun boostMemory(ctx: Context): String = withContext(Dispatchers.IO) {
        if (!ShizukuShell.isReady()) return@withContext "Shizuku belum siap"
        val before = freeRamMb(ctx)
        val keep = AppRepo.protectedPackages(ctx) + (Prefs.target(ctx)?.let { setOf(it) } ?: emptySet())
        val victims = AppRepo.targets().filter { it.pkg !in keep }
        victims.groupBy { it.user }.forEach { (u, list) ->
            list.chunked(25).forEach { chunk ->
                ShizukuShell.exec(chunk.joinToString("; ") { "am force-stop --user $u ${it.pkg}" })
            }
        }
        ShizukuShell.exec("am kill-all")
        delay(800)
        "${victims.size} app dihentikan. RAM bebas ${before}MB -> ${freeRamMb(ctx)}MB"
    }

    /**
     * Dipanggil tiap 2,5 dtk oleh BoostService.
     * Return true kalau game sudah ditutup (mode harus dihentikan).
     */
    suspend fun tick(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val game = Prefs.target(ctx) ?: return@withContext true
        tickCount++
        if (tickCount % 24 == 0) { // ~tiap 1 menit: tegaskan ulang mode performa + bersihkan background
            val re = if (Prefs.lockPerf(ctx) && Build.VERSION.SDK_INT >= 31)
                "cmd power set-fixed-performance-mode-enabled true; " else ""
            ShizukuShell.exec(re + "am kill-all")
        }
        val dump = ShizukuShell.exec("dumpsys activity activities").out
        if (dump.isBlank()) return@withContext false // Shizuku/dumpsys gagal, jangan ambil keputusan
        val vis = visiblePackages(dump)

        if (System.currentTimeMillis() - startedAt < GRACE_MS) return@withContext false
        if (game in vis) gameMiss = 0 else gameMiss++
        gameMiss >= MISS_LIMIT
    }

    /**
     * Kumpulkan package yang punya ActivityRecord visible.
     * Format dumpsys beda antar versi Android, bagian ini yang paling perlu dites di hp.
     */
    private fun visiblePackages(dump: String): Set<String> {
        val out = mutableSetOf<String>()
        for (seg in dump.split("ActivityRecord{").drop(1)) {
            val pkg = PKG_IN_HEAD.find(seg.take(200))?.groupValues?.get(1) ?: continue
            if (VISIBLE.containsMatchIn(seg.take(1500))) out += pkg
        }
        return out
    }
}
