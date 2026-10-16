package com.xboost

import android.content.Context

/** Ubah setting global (animasi, freeform) dan ingat nilai aslinya untuk dikembalikan. */
object SettingsGuard {
    val ANIM_KEYS = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
    val FREEFORM_KEYS = listOf("enable_freeform_support", "force_resizable_activities")

    fun set(ctx: Context, key: String, value: String) {
        if (Prefs.orig(ctx, key) == null) {
            val cur = ShizukuShell.exec("settings get global $key").out.trim()
            Prefs.setOrig(ctx, key, if (cur.isEmpty()) "null" else cur)
        }
        ShizukuShell.exec("settings put global $key $value")
    }

    fun restoreAll(ctx: Context) {
        Prefs.origKeys(ctx).forEach { k ->
            val v = Prefs.orig(ctx, k)
            if (v == null || v == "null") ShizukuShell.exec("settings delete global $k")
            else ShizukuShell.exec("settings put global $k $v")
        }
        Prefs.clearOrig(ctx)
    }
}
