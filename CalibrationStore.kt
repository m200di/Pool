package com.example.poolaimassist

import android.content.Context

/**
 * Persists the 6 pocket positions (tapped once by the user during calibration) in
 * screen-pixel coordinates. Re-calibrate any time the game window/table layout changes.
 */
object CalibrationStore {
    private const val PREFS = "pool_aim_assist_calibration"
    private const val KEY_POCKETS = "pockets"

    fun savePockets(context: Context, pockets: List<Pt>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val serialized = pockets.joinToString(";") { "${it.x},${it.y}" }
        prefs.edit().putString(KEY_POCKETS, serialized).apply()
    }

    fun loadPockets(context: Context): List<Pt> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_POCKETS, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split(",")
            if (parts.size != 2) return@mapNotNull null
            val x = parts[0].toFloatOrNull() ?: return@mapNotNull null
            val y = parts[1].toFloatOrNull() ?: return@mapNotNull null
            Pt(x, y)
        }
    }

    fun hasCalibration(context: Context): Boolean = loadPockets(context).size == 6
}
