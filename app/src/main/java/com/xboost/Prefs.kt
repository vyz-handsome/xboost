package com.xboost

import android.content.Context

/** Semua state disimpan di disk supaya aman kalau app crash / hp restart. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("xboost", Context.MODE_PRIVATE)

    fun isActive(c: Context) = sp(c).getBoolean("active", false)
    fun setActive(c: Context, v: Boolean) = sp(c).edit().putBoolean("active", v).commit()

    fun target(c: Context): String? = sp(c).getString("target", null)
    fun setTarget(c: Context, v: String?) = sp(c).edit().putString("target", v).apply()

    fun animOff(c: Context) = sp(c).getBoolean("anim_off", true)
    fun setAnimOff(c: Context, v: Boolean) = sp(c).edit().putBoolean("anim_off", v).apply()

    @Synchronized fun suspended(c: Context): Set<String> =
        sp(c).getStringSet("suspended", emptySet())!!.toSet()
    @Synchronized fun addSuspended(c: Context, p: String) {
        sp(c).edit().putStringSet("suspended", suspended(c) + p).commit()
    }
    @Synchronized fun removeSuspended(c: Context, p: String) {
        sp(c).edit().putStringSet("suspended", suspended(c) - p).commit()
    }
    @Synchronized fun clearSuspended(c: Context) = sp(c).edit().remove("suspended").commit()

    fun whitelist(c: Context): Set<String> =
        sp(c).getStringSet("whitelist", emptySet())!!.toSet()
    fun toggleWhitelist(c: Context, p: String) {
        val w = whitelist(c)
        sp(c).edit().putStringSet("whitelist", if (p in w) w - p else w + p).apply()
    }

    // nilai asli setting sistem (animasi, freeform) untuk dikembalikan
    fun orig(c: Context, key: String): String? = sp(c).getString("orig_$key", null)
    fun setOrig(c: Context, key: String, v: String) = sp(c).edit().putString("orig_$key", v).commit()
    fun origKeys(c: Context): List<String> =
        sp(c).all.keys.filter { it.startsWith("orig_") }.map { it.removePrefix("orig_") }
    fun clearOrig(c: Context) {
        val e = sp(c).edit()
        origKeys(c).forEach { e.remove("orig_$it") }
        e.commit()
    }
}
