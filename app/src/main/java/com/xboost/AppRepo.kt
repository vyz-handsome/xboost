package com.xboost

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager

data class AppEntry(val label: String, val pkg: String)

/** App di user tertentu (user 0 = utama, user lain = clone / dual apps / work profile). */
data class Target(val user: Int, val pkg: String) {
    val key get() = "$user:$pkg"
}

object AppRepo {
    private val PKG_OK = Regex("^[A-Za-z0-9._]+$")
    fun safe(p: String) = PKG_OK.matches(p)

    fun launchable(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    /** Semua user di hp: 0 = utama, sisanya clone / dual apps / secure folder / work profile. */
    fun users(): List<Int> {
        val out = ShizukuShell.exec("pm list users").out
        val ids = Regex("UserInfo\\{(\\d+):").findAll(out).map { it.groupValues[1].toInt() }.toList()
        return ids.ifEmpty { listOf(0) }
    }

    /**
     * Semua app yang bisa dibekukan, di SEMUA user:
     * app terinstall (pm list -3) + app sistem yang punya ikon launcher (Chrome, Galeri, dll).
     */
    fun targets(): List<Target> {
        val out = LinkedHashSet<Target>()
        for (u in users()) {
            ShizukuShell.exec("pm list packages -3 --user $u").out.lineSequence()
                .map { it.removePrefix("package:").trim() }
                .filter { it.isNotEmpty() && safe(it) }
                .forEach { out += Target(u, it) }
            ShizukuShell.exec(
                "cmd package query-activities --brief --user $u " +
                    "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
            ).out.lineSequence()
                .map { it.trim() }
                .filter { '/' in it && ' ' !in it }
                .map { it.substringBefore('/') }
                .filter { safe(it) }
                .forEach { out += Target(u, it) }
        }
        return out.toList()
    }

    /** App yang TIDAK boleh dibekukan: sistem penting, Shizuku, launcher, keyboard, telepon, SMS, jam, whitelist. */
    fun protectedPackages(ctx: Context): Set<String> {
        val s = mutableSetOf(
            ctx.packageName, "moe.shizuku.privileged.api",
            "com.android.systemui", "com.android.settings", "com.android.phone",
            // Play Store/Play Services dipakai login & lisensi game, WebView dipakai iklan/login di dalam game
            "com.android.vending", "com.google.android.gms", "com.google.android.gsf",
            "com.google.android.webview", "com.android.webview", "com.google.android.trichromelibrary"
        )
        val pm = ctx.packageManager
        runCatching {
            pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                ?.activityInfo?.packageName?.let { s += it }
        }
        runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.let { ComponentName.unflattenFromString(it)?.packageName }?.let { s += it }
        }
        runCatching {
            (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager)
                .defaultDialerPackage?.let { s += it }
        }
        runCatching { Telephony.Sms.getDefaultSmsPackage(ctx)?.let { s += it } }
        runCatching {
            pm.resolveActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS), 0)
                ?.activityInfo?.packageName?.let { s += it }
        }
        s += Prefs.whitelist(ctx)
        return s
    }
}
