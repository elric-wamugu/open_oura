package org.openoura.android.ui.theme

import android.content.Context
import android.os.Build

/**
 * The appearance setting: brand teal, or Material You colours from the wallpaper.
 *
 * Lives in the same "ui" preferences as the panel fold state. The widgets read it too —
 * their composition runs in this app's process — so a change here has to be followed by
 * a widget refresh to reach the home screen.
 */
object ThemePrefs {
    private const val PREFS = "ui"
    private const val KEY_DYNAMIC = "dynamic_color"

    /** Wallpaper colours exist from Android 12 (API 31); below that the toggle is off. */
    val dynamicSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun dynamicColor(context: Context): Boolean =
        dynamicSupported &&
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DYNAMIC, false)

    fun setDynamicColor(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DYNAMIC, on).apply()
    }
}
