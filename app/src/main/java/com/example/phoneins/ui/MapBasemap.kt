package com.example.phoneins.ui

import android.content.Context

/** Basemap shown behind the position plot: cached online street tiles, or the plain grid. */
internal enum class MapBasemap(val label: String) {
    TILES("Street map"),
    GRID("Grid"),
}

private const val UI_PREFS = "phoneins_ui"
private const val KEY_MAP_BASEMAP = "map_basemap"

internal fun readStoredMapBasemap(context: Context): MapBasemap =
    runCatching {
        context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MAP_BASEMAP, null)
            ?.let { stored -> MapBasemap.entries.firstOrNull { it.name == stored } }
    }.getOrNull() ?: MapBasemap.TILES

internal fun storeMapBasemap(context: Context, basemap: MapBasemap) {
    runCatching {
        context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MAP_BASEMAP, basemap.name)
            .apply()
    }
}

internal fun clearStoredMapBasemap(context: Context) {
    runCatching {
        context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).edit().remove(KEY_MAP_BASEMAP).commit()
    }
}
