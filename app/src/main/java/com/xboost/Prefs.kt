package com.xboost

import android.content.Context

/** Semua state disimpan di disk supaya aman kalau app crash / hp restart. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("xboost", Context.MODE_PRIVATE)

    fun isActive(c: Context) = sp(c).getBoolean("active", false)
    fun setActive(c: Context, v: Boolean) = sp(c).edit().putBoolean("active", v).commit()

    fun target(c: Context): String? = sp(c).getString("target", null)
    fun setTarget(c: Context, v: String?) = sp(c).edit().putString("target", v).apply()

    // ---- opsi (semua default aktif) ----
    fun lockPerf(c: Context) = sp(c).getBoolean("lock_perf", true)
    fun setLockPerf(c: Context, v: Boolean) = sp(c).edit().putBoolean("lock_perf", v).apply()

    fun maxPerf(c: Context) = sp(c).getBoolean("max_perf", true)
    fun setMaxPerf(c: Context, v: Boolean) = sp(c).edit().putBoolean("max_perf", v).apply()

    fun animOff(c: Context) = sp(c).getBoolean("anim_off", true)
    fun setAnimOff(c: Context, v: Boolean) = sp(c).edit().putBoolean("anim_off", v).apply()

    fun notifOff(c: Context) = sp(c).getBoolean("notif_off", true)
    fun setNotifOff(c: Context, v: Boolean) = sp(c).edit().putBoolean("notif_off", v).apply()

    // ---- app yang dibekukan, format "userId:package" (tanpa ":" = user 0, format lama) ----
    @Synchronized fun suspended(c: Context): Set<String> =
        sp(c).getStringSet("suspended", emptySet())!!.toSet()
    @Synchronized fun addSuspendedAll(c: Context, keys: Collection<String>) {
        sp(c).edit().putStringSet("suspended", suspended(c) + keys).commit()
    }
    @Synchronized fun clearSuspended(c: Context) = sp(c).edit().remove("suspended").commit()

    private fun getSet(c: Context, k: String): Set<String> =
        sp(c).getStringSet(k, emptySet())!!.toSet()
    private fun toggle(c: Context, k: String, p: String) {
        val w = getSet(c, k)
        sp(c).edit().putStringSet(k, if (p in w) w - p else w + p).apply()
    }

    fun whitelist(c: Context) = getSet(c, "whitelist")
    fun toggleWhitelist(c: Context, p: String) = toggle(c, "whitelist", p)
    fun games(c: Context) = getSet(c, "games")
    fun toggleGame(c: Context, p: String) = toggle(c, "games", p)

    // nilai asli setting sistem (animasi, zen_mode, dll) untuk dikembalikan
    fun orig(c: Context, key: String): String? = sp(c).getString("orig_$key", null)
    fun setOrig(c: Context, key: String, v: String) = sp(c).edit().putString("orig_$key", v).commit()
    fun origKeys(c: Context): List<String> =
        sp(c).all.keys.filter { it.startsWith("orig_") }.map { it.removePrefix("orig_") }
    fun clearOrig(c: Context) {
        val e = sp(c).edit()
        origKeys(c).forEach { e.remove("orig_$it") }
        e.commit()
    }

    // governor asli CPU/GPU (mode root) untuk dikembalikan
    fun gov(c: Context, path: String): String? = sp(c).getString("gov_$path", null)
    fun setGov(c: Context, path: String, v: String) = sp(c).edit().putString("gov_$path", v).commit()
    fun govKeys(c: Context): List<String> =
        sp(c).all.keys.filter { it.startsWith("gov_") }.map { it.removePrefix("gov_") }
    fun clearGov(c: Context) {
        val e = sp(c).edit()
        govKeys(c).forEach { e.remove("gov_$it") }
        e.commit()
    }
}
