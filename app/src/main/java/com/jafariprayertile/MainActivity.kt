package com.jafariprayertile

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.wear.tiles.TileService
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var prefs: PrayerPrefs
    private lateinit var statusText: TextView
    private lateinit var methodBtn: Button
    private lateinit var rowsBtn: Button
    private lateinit var formatBtn: Button
    private lateinit var gpsBtn: Button

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            fetchGpsLocation()
        } else {
            statusText.text = "Location permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PrayerPrefs(this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(34), dp(22), dp(42))
        }

        val title = TextView(this).apply {
            text = "Ja'fari Prayer Tile"
            setTextColor(Color.parseColor("#F5C542"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        container.addView(title)

        statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#B0BEC5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(10))
        }
        container.addView(statusText)

        gpsBtn = createPillButton("#12352C", "#69F0AE") {
            requestOrFetchLocation()
        }
        container.addView(gpsBtn)

        methodBtn = createPillButton("#1E2226", "#FFFFFF") {
            prefs.method = if (prefs.method == CalcMethod.LEVA) CalcMethod.TEHRAN else CalcMethod.LEVA
            refreshUiAndTile()
        }
        container.addView(methodBtn)

        rowsBtn = createPillButton("#1E2226", "#FFFFFF") {
            prefs.showSevenRows = !prefs.showSevenRows
            refreshUiAndTile()
        }
        container.addView(rowsBtn)

        formatBtn = createPillButton("#1E2226", "#FFFFFF") {
            prefs.use24Hour = !prefs.use24Hour
            refreshUiAndTile()
        }
        container.addView(formatBtn)

        scroll.addView(container)
        setContentView(scroll)

        refreshUi()

        if (!prefs.hasLocation) {
            requestOrFetchLocation()
        }
    }

    private fun createPillButton(bgHex: String, textHex: String, onClick: () -> Unit): Button {
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor(bgHex))
            cornerRadius = dp(18).toFloat()
        }
        return Button(this).apply {
            background = bg
            setTextColor(Color.parseColor(textHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isAllCaps = false
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(7)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun refreshUi() {
        statusText.text = if (prefs.hasLocation) {
            String.format(Locale.US, "GPS: %.3f, %.3f", prefs.latitude, prefs.longitude)
        } else {
            "GPS not set yet"
        }
        gpsBtn.text = "↻ Update GPS Now"
        methodBtn.text = "Method: ${prefs.method.shortLabel}"
        rowsBtn.text = if (prefs.showSevenRows) "Rows: 7 (+Isha/Mid)" else "Rows: 5 (Fajr–Maghrib)"
        formatBtn.text = if (prefs.use24Hour) "Clock: 24-Hour" else "Clock: 12-Hour"
    }

    private fun refreshUiAndTile() {
        refreshUi()
        TileService.getUpdater(this).requestUpdate(PrayerTileService::class.java)
    }

    private fun requestOrFetchLocation() {
        val hasFine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasFine || hasCoarse) {
            fetchGpsLocation()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun fetchGpsLocation() {
        statusText.text = "Getting GPS fix..."
        val client = LocationServices.getFusedLocationProviderClient(this)
        val cts = CancellationTokenSource()
        try {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        prefs.saveLocation(loc.latitude, loc.longitude)
                        refreshUiAndTile()
                    } else {
                        client.lastLocation.addOnSuccessListener { last ->
                            if (last != null) {
                                prefs.saveLocation(last.latitude, last.longitude)
                                refreshUiAndTile()
                            } else {
                                statusText.text = "GPS fix failed — try near window"
                            }
                        }
                    }
                }
                .addOnFailureListener {
                    statusText.text = "GPS error — try again"
                }
        } catch (_: SecurityException) {
            statusText.text = "Location permission needed"
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
