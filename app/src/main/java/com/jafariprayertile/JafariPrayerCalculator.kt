package com.jafariprayertile

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

data class PrayerEntry(
    val name: String,
    val epochMillis: Long
)

data class DayPrayers(
    val fajr: Long,
    val sunrise: Long,
    val dhuhr: Long,
    val asr: Long,
    val sunset: Long,
    val maghrib: Long,
    val isha: Long
) {
    fun asList(includeSeven: Boolean): List<PrayerEntry> {
        return if (includeSeven) {
            listOf(
                PrayerEntry("Fajr", fajr),
                PrayerEntry("Sunrise", sunrise),
                PrayerEntry("Dhuhr", dhuhr),
                PrayerEntry("Asr", asr),
                PrayerEntry("Sunset", sunset),
                PrayerEntry("Maghrib", maghrib),
                PrayerEntry("Isha", isha)
            )
        } else {
            listOf(
                PrayerEntry("Fajr", fajr),
                PrayerEntry("Sunrise", sunrise),
                PrayerEntry("Dhuhr", dhuhr),
                PrayerEntry("Sunset", sunset),
                PrayerEntry("Maghrib", maghrib)
            )
        }
    }
}

object JafariPrayerCalculator {

    private fun dtr(d: Double): Double = d * Math.PI / 180.0
    private fun rtd(r: Double): Double = r * 180.0 / Math.PI
    private fun fixAngle(a: Double): Double = a - 360.0 * floor(a / 360.0)
    private fun fixHour(h: Double): Double = h - 24.0 * floor(h / 24.0)

    private fun julianDate(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2.0 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private fun sunPosition(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * sin(dtr(g)) + 0.020 * sin(dtr(2.0 * g)))
        val e = 23.439 - 0.00000036 * d
        val ra = rtd(atan2(cos(dtr(e)) * sin(dtr(l)), cos(dtr(l)))) / 15.0
        val eqt = q / 15.0 - fixHour(ra)
        val decl = rtd(asin(sin(dtr(e)) * sin(dtr(l))))
        return Pair(decl, eqt)
    }

    private fun midDay(jd: Double, t: Double): Double {
        val (_, eqt) = sunPosition(jd + t)
        return fixHour(12.0 - eqt)
    }

    private fun sunAngleTime(
        jd: Double,
        lat: Double,
        angle: Double,
        t: Double,
        ccw: Boolean
    ): Double {
        val (decl, _) = sunPosition(jd + t)
        val noon = midDay(jd, t)
        val cosVal = ((-sin(dtr(angle)) - sin(dtr(lat)) * sin(dtr(decl))) /
                (cos(dtr(lat)) * cos(dtr(decl)))).coerceIn(-1.0, 1.0)
        val deltaT = rtd(acos(cosVal)) / 15.0
        return if (ccw) noon - deltaT else noon + deltaT
    }

    private fun asrTime(jd: Double, lat: Double, t: Double): Double {
        val (decl, _) = sunPosition(jd + t)
        val angle = -rtd(atan(1.0 / (1.0 + tan(abs(dtr(lat - decl))))))
        return sunAngleTime(jd, lat, angle, t, false)
    }

    private fun computeRawHours(
        year: Int,
        month: Int,
        day: Int,
        lat: Double,
        lng: Double,
        tzHours: Double,
        method: CalcMethod
    ): DoubleArray {
        val fajrAngle = when (method) {
            CalcMethod.LEVA -> 16.0
            CalcMethod.TEHRAN -> 17.7
        }
        val maghribAngle = when (method) {
            CalcMethod.LEVA -> 4.0
            CalcMethod.TEHRAN -> 4.5
        }
        val ishaAngle = 14.0

        val jd = julianDate(year, month, day) - lng / 360.0
        var fajr = 5.0 / 24.0
        var sunrise = 6.0 / 24.0
        var dhuhr = 12.0 / 24.0
        var asr = 13.0 / 24.0
        var sunset = 18.0 / 24.0
        var maghrib = 18.0 / 24.0
        var isha = 18.0 / 24.0

        repeat(2) {
            fajr = sunAngleTime(jd, lat, fajrAngle, fajr, true) / 24.0
            sunrise = sunAngleTime(jd, lat, 0.833, sunrise, true) / 24.0
            dhuhr = midDay(jd, dhuhr) / 24.0
            asr = asrTime(jd, lat, asr) / 24.0
            sunset = sunAngleTime(jd, lat, 0.833, sunset, false) / 24.0
            maghrib = sunAngleTime(jd, lat, maghribAngle, maghrib, false) / 24.0
            isha = sunAngleTime(jd, lat, ishaAngle, isha, false) / 24.0
        }

        val shift = tzHours - lng / 15.0
        return doubleArrayOf(
            fixHour(fajr * 24.0 + shift),
            fixHour(sunrise * 24.0 + shift),
            fixHour(dhuhr * 24.0 + shift),
            fixHour(asr * 24.0 + shift),
            fixHour(sunset * 24.0 + shift),
            fixHour(maghrib * 24.0 + shift),
            fixHour(isha * 24.0 + shift)
        )
    }

    fun calculateForDate(
        cal: Calendar,
        lat: Double,
        lng: Double,
        method: CalcMethod
    ): DayPrayers {
        val dayStart = (cal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val tzHours = TimeZone.getDefault().getOffset(dayStart.timeInMillis) / 3_600_000.0
        val year = dayStart.get(Calendar.YEAR)
        val month = dayStart.get(Calendar.MONTH) + 1
        val day = dayStart.get(Calendar.DAY_OF_MONTH)

        val today = computeRawHours(year, month, day, lat, lng, tzHours, method)

        fun hoursToMillis(baseCal: Calendar, hours: Double): Long {
            val totalMinutes = (hours * 60.0).roundToInt()
            return baseCal.timeInMillis + totalMinutes * 60_000L
        }

        return DayPrayers(
            fajr = hoursToMillis(dayStart, today[0]),
            sunrise = hoursToMillis(dayStart, today[1]),
            dhuhr = hoursToMillis(dayStart, today[2]),
            asr = hoursToMillis(dayStart, today[3]),
            sunset = hoursToMillis(dayStart, today[4]),
            maghrib = hoursToMillis(dayStart, today[5]),
            isha = hoursToMillis(dayStart, today[6])
        )
    }

    fun formatTime(epochMillis: Long, use24Hour: Boolean): String {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
        val hour24 = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        return if (use24Hour) {
            String.format(Locale.US, "%02d:%02d", hour24, minute)
        } else {
            val amPm = if (hour24 < 12) "AM" else "PM"
            val hour12 = when (val h = hour24 % 12) {
                0 -> 12
                else -> h
            }
            String.format(Locale.US, "%d:%02d %s", hour12, minute, amPm)
        }
    }

    fun formatCountdown(deltaMillis: Long): String {
        val totalMins = ((deltaMillis + 59_999L) / 60_000L).coerceAtLeast(1L)
        val hours = totalMins / 60L
        val mins = totalMins % 60L
        return when {
            hours > 0 && mins > 0 -> "${hours}h ${mins}m"
            hours > 0 -> "${hours}h"
            else -> "${mins}m"
        }
    }
}
