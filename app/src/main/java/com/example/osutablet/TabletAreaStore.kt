package com.example.osutablet

import android.content.Context
import android.graphics.RectF

/**
 * Persists the active area as fractions of the surface rather than raw pixels.
 *
 * Pixel coordinates are only meaningful for the exact surface size that
 * produced them, so a rotation or a display-size change would silently restore
 * a wrong — possibly off-screen — area.
 */
class TabletAreaStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns the saved area, or null when nothing valid has been stored. */
    fun load(): RectF? {
        if (!prefs.contains(KEY_LEFT)) return null
        val area = RectF(
            prefs.getFloat(KEY_LEFT, 0f),
            prefs.getFloat(KEY_TOP, 0f),
            prefs.getFloat(KEY_RIGHT, 0f),
            prefs.getFloat(KEY_BOTTOM, 0f),
        )
        return area.takeIf { it.width() > 0f && it.height() > 0f }
    }

    fun save(normalized: RectF) {
        prefs.edit()
            .putFloat(KEY_LEFT, normalized.left)
            .putFloat(KEY_TOP, normalized.top)
            .putFloat(KEY_RIGHT, normalized.right)
            .putFloat(KEY_BOTTOM, normalized.bottom)
            .putInt(KEY_SCHEMA, SCHEMA_VERSION)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "OsuTabletPrefs"

        // v2 keys are deliberately distinct from the v1 pixel keys so an
        // upgraded install falls back to the default area instead of loading
        // pixel values as if they were fractions.
        const val KEY_LEFT = "areaLeftNorm"
        const val KEY_TOP = "areaTopNorm"
        const val KEY_RIGHT = "areaRightNorm"
        const val KEY_BOTTOM = "areaBottomNorm"
        const val KEY_SCHEMA = "areaSchema"
        const val SCHEMA_VERSION = 2
    }
}
