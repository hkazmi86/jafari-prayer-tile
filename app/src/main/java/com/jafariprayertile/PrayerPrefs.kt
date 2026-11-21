package com.jafariprayertile

import android.content.Context

enum class CalcMethod(val id: String, val label: String, val shortLabel: String) {
    LEVA("leva", "Leva Institute (Qom)", "Leva"),
    TEHRAN("tehran", "Univ. of Tehran", "Tehran");

    companion object {
        fun fromId(id: String?): CalcMethod = entries.firstOrNull { it.id == id } ?: LEVA
    }
}

class PrayerPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("jafari_prayer_prefs", Context.MODE_PRIVATE)

    var method: CalcMethod
        get() = CalcMethod.fromId(prefs.getString(KEY_METHOD, CalcMethod.LEVA.id))
        set(value) = prefs.edit().putString(KEY_METHOD, value.id).apply()

    var showSevenRows: Boolean
        get() = prefs.getBoolean(KEY_SEVEN_ROWS, false)
        set(value) = prefs.edit().putBoolean(KEY_SEVEN_ROWS, value).apply()

    var use24Hour: Boolean
        get() = prefs.getBoolean(KEY_24_HOUR, false)
        set(value) = prefs.edit().putBoolean(KEY_24_HOUR, value).apply()

    var hasLocation: Boolean
        get() = prefs.getBoolean(KEY_HAS_LOC, false)
        private set(value) = prefs.edit().putBoolean(KEY_HAS_LOC, value).apply()

    var latitude: Double
        get() = Double.fromBits(prefs.getLong(KEY_LAT, 0L))
        private set(value) = prefs.edit().putLong(KEY_LAT, value.toRawBits()).apply()

    var longitude: Double
        get() = Double.fromBits(prefs.getLong(KEY_LNG, 0L))
        private set(value) = prefs.edit().putLong(KEY_LNG, value.toRawBits()).apply()

    var lastUpdatedMillis: Long
        get() = prefs.getLong(KEY_LAST_UPDATED, 0L)
        private set(value) = prefs.edit().putLong(KEY_LAST_UPDATED, value).apply()

    fun saveLocation(lat: Double, lng: Double) {
        prefs.edit()
            .putBoolean(KEY_HAS_LOC, true)
            .putLong(KEY_LAT, lat.toRawBits())
            .putLong(KEY_LNG, lng.toRawBits())
            .putLong(KEY_LAST_UPDATED, System.currentTimeMillis())
            .apply()
    }

    companion object {
        private const val KEY_METHOD = "calc_method"
        private const val KEY_SEVEN_ROWS = "show_seven_rows"
        private const val KEY_24_HOUR = "use_24_hour"
        private const val KEY_HAS_LOC = "has_location"
        private const val KEY_LAT = "latitude"
        private const val KEY_LNG = "longitude"
        private const val KEY_LAST_UPDATED = "last_updated"
    }
}
