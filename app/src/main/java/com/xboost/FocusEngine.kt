package com.xboost

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object FocusEngine {
    /** pkg floating yang sedang dibuka -> jumlah cek berturut-turut yang tidak terlihat */
    val floating = ConcurrentHashMap<String, Int>()

    private fun freeRamMb(ctx: Context): Long {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.availMem / (1024 * 1024)
    }

    private fun freeze(ctx: Context, pkg: String) {
        if (!AppRepo.safe(pkg)) return
        Prefs.addSuspended(ctx, pkg) // dicatat dulu, baru dibekukan (aman kalau crash)
        ShizukuShell.exec("am force-stop $pkg; pm suspend --user 0 $pkg")
    }

    suspend fun start(ctx: Context, target: String, animOff: Boolean): String = withContext(Dispatchers.IO) {
        if (!ShizukuShell.isReady()) return@withContext "Shizuku belum siap"
        if (!AppRepo.safe(target)) return@withContext "Nama package tidak valid"
        val keep = AppRepo.protectedPackages(ctx) + target
        val before = freeRamMb(ctx)
        Prefs.setTarget(ctx, target)
        Prefs.setActive(ctx, true)
        if (animOff) SettingsGuard.ANIM_KEYS.forEach { SettingsGuard.set(ctx, it, "0") }
        val victims = AppRepo.userPackages().filter { it !in keep }
        victims.forEach { freeze(ctx, it) }
        if (Build.VERSION.SDK_INT >= 31) ShizukuShell.exec("cmd game mode performance $target")
        delay(500)
        "Mode Fokus aktif: ${victims.size} app dibekukan. RAM bebas ${before}MB -> ${freeRamMb(ctx)}MB"
    }

    /** Kembalikan semuanya: unsuspend, animasi, freeform. Aman dipanggil berkali-kali. */
    suspend fun restoreAll(ctx: Context): String = withContext(Dispatchers.IO) {
        if (!ShizukuShell.isReady()) return@withContext "Shizuku belum siap, nyalakan Shizuku lalu coba lagi"
        Prefs.suspended(ctx).forEach {
            if (AppRepo.safe(it)) ShizukuShell.exec("pm unsuspend --user 0 $it")
        }
        Prefs.clearSuspended(ctx)
        SettingsGuard.restoreAll(ctx)
        floating.clear()
        Prefs.setActive(ctx, false)
        "Semua dipulihkan"
    }

    suspend fun boostMemory(ctx: Context): String = withContext(Dispatchers.IO) {
        if (!ShizukuShell.isReady()) return@withContext "Shizuku belum siap"
        val before = freeRamMb(ctx)
        val keep = AppRepo.protectedPackages(ctx) + (Prefs.target(ctx)?.let { setOf(it) } ?: emptySet())
        val victims = AppRepo.userPackages().filter { it !in keep }
        victims.forEach { ShizukuShell.exec("am force-stop $it") }
        ShizukuShell.exec("am kill-all")
        delay(800)
        "${victims.size} app dihentikan. RAM bebas ${before}MB -> ${freeRamMb(ctx)}MB"
    }

    /** Buka app dalam jendela mengambang; app dibebaskan sementara dari pembekuan. */
    suspend fun launchFloating(ctx: Context, pkg: String): String = withContext(Dispatchers.IO) {
        if (!ShizukuShell.isReady()) return@withContext "Shizuku belum siap"
        if (!AppRepo.safe(pkg)) return@withContext "Nama package tidak valid"
        val comp = ctx.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()
            ?: return@withContext "App tidak punya launcher activity"
        SettingsGuard.set(ctx, "enable_freeform_support", "1")
        SettingsGuard.set(ctx, "force_resizable_activities", "1")
        ShizukuShell.exec("pm unsuspend --user 0 $pkg")
        Prefs.removeSuspended(ctx, pkg)
        floating[pkg] = 0
        val r = ShizukuShell.exec("am start --user 0 --windowingMode 5 -n $comp")
        if (r.code != 0) "Gagal buka floating: ${r.err.take(120)}" else "Floating dibuka"
    }

    /** Dipanggil berkala oleh FocusService: bekukan lagi app floating yang sudah ditutup. */
    suspend fun tickFloating(ctx: Context) = withContext(Dispatchers.IO) {
        for ((pkg, miss) in floating.entries.toList()) {
            if (isVisible(pkg)) { floating[pkg] = 0; continue }
            if (miss + 1 >= 2) { floating.remove(pkg); freeze(ctx, pkg) }
            else floating[pkg] = miss + 1
        }
    }

    /**
     * Cek apakah ada ActivityRecord milik pkg yang visible.
     * Format dumpsys beda antar versi Android, bagian ini yang paling perlu dites di hp.
     */
    private fun isVisible(pkg: String): Boolean {
        val dump = ShizukuShell.exec("dumpsys activity activities").out
        val parts = dump.split("ActivityRecord{").drop(1)
        return parts.any { seg ->
            val head = seg.take(200)
            head.contains(" $pkg/") &&
                Regex("visible(Requested)?=true", RegexOption.IGNORE_CASE).containsMatchIn(seg.take(1500))
        }
    }
}
