package com.jafariprayertile

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

class PrayerTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build()
        )
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> = serviceScope.future {
        val prefs = PrayerPrefs(applicationContext)
        val forceFresh = requestParams.currentState.lastClickableId == CLICK_REFRESH
        maybeUpdateLocation(prefs, forceFresh)

        val timeline = if (!prefs.hasLocation) {
            TimelineBuilders.Timeline.Builder()
                .addTimelineEntry(
                    TimelineBuilders.TimelineEntry.Builder()
                        .setLayout(
                            LayoutElementBuilders.Layout.Builder()
                                .setRoot(buildNeedsLocationLayout())
                                .build()
                        )
                        .build()
                )
                .build()
        } else {
            buildPrayerTimeline(prefs)
        }

        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(15 * 60 * 1000L)
            .setTileTimeline(timeline)
            .build()
    }

    private suspend fun maybeUpdateLocation(prefs: PrayerPrefs, forceFresh: Boolean) {
        val hasFine = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) return

        val stale = (System.currentTimeMillis() - prefs.lastUpdatedMillis) > LOCATION_STALE_MS
        if (!forceFresh && prefs.hasLocation && !stale) return

        val client = LocationServices.getFusedLocationProviderClient(applicationContext)
        try {
            if (!forceFresh) {
                val last = withTimeoutOrNull(2000L) { client.lastLocation.await() }
                if (last != null) {
                    prefs.saveLocation(last.latitude, last.longitude)
                    return
                }
            }
            val cts = CancellationTokenSource()
            val fresh = withTimeoutOrNull(4000L) {
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token).await()
            }
            if (fresh != null) {
                prefs.saveLocation(fresh.latitude, fresh.longitude)
            } else {
                val fallback = withTimeoutOrNull(1500L) { client.lastLocation.await() }
                if (fallback != null) {
                    prefs.saveLocation(fallback.latitude, fallback.longitude)
                }
            }
        } catch (_: Exception) {
            // Keep existing cached coordinates if GPS is unavailable indoors
        }
    }

    private fun buildPrayerTimeline(prefs: PrayerPrefs): TimelineBuilders.Timeline {
        val now = System.currentTimeMillis()
        val todayCal = Calendar.getInstance()
        val tomorrowCal = (todayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }

        val todayPrayers = JafariPrayerCalculator.calculateForDate(
            todayCal, prefs.latitude, prefs.longitude, prefs.method
        )
        val tomorrowPrayers = JafariPrayerCalculator.calculateForDate(
            tomorrowCal, prefs.latitude, prefs.longitude, prefs.method
        )

        val todayList = todayPrayers.asList(prefs.showSevenRows)
        val tomorrowFajr = PrayerEntry("Fajr", tomorrowPrayers.fajr)

        // Generate minute-by-minute timeline entries for the next 20 minutes so the
        // countdown ticks smoothly on the watch without waking up the CPU.
        val timelineBuilder = TimelineBuilders.Timeline.Builder()
        val minuteStart = (now / 60_000L) * 60_000L

        for (i in 0 until 20) {
            val slotStart = if (i == 0) now else minuteStart + i * 60_000L
            val slotEnd = minuteStart + (i + 1) * 60_000L
            val root = buildPrayerListLayout(prefs, todayList, tomorrowFajr, slotStart)
            timelineBuilder.addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setValidity(
                        TimelineBuilders.TimeInterval.Builder()
                            .setStartMillis(slotStart)
                            .setEndMillis(slotEnd)
                            .build()
                    )
                    .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(root).build())
                    .build()
            )
        }

        // Fallback entry after the 20-minute window in case freshness refresh is delayed
        val fallbackStart = minuteStart + 20 * 60_000L
        timelineBuilder.addTimelineEntry(
            TimelineBuilders.TimelineEntry.Builder()
                .setValidity(
                    TimelineBuilders.TimeInterval.Builder()
                        .setStartMillis(fallbackStart)
                        .setEndMillis(fallbackStart + 6 * 3_600_000L)
                        .build()
                )
                .setLayout(
                    LayoutElementBuilders.Layout.Builder()
                        .setRoot(buildPrayerListLayout(prefs, todayList, tomorrowFajr, fallbackStart))
                        .build()
                )
                .build()
        )

        return timelineBuilder.build()
    }

    private fun buildPrayerListLayout(
        prefs: PrayerPrefs,
        todayList: List<PrayerEntry>,
        tomorrowFajr: PrayerEntry,
        nowMillis: Long
    ): LayoutElementBuilders.LayoutElement {
        val nextPrayer = todayList.firstOrNull { it.epochMillis > nowMillis } ?: tomorrowFajr
        val countdownStr = JafariPrayerCalculator.formatCountdown(nextPrayer.epochMillis - nowMillis)
        val sevenMode = prefs.showSevenRows

        val colBuilder = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        // Top Countdown Header
        colBuilder.addContent(
            styledText(
                text = "${nextPrayer.name} in $countdownStr",
                sizeSp = if (sevenMode) 12.5f else 14f,
                color = COLOR_GOLD,
                bold = true
            )
        )

        colBuilder.addContent(spacerV(if (sevenMode) 3f else 6f))

        // Prayer Time Rows
        val rowWidth = if (sevenMode) 148f else 156f
        val fontSize = if (sevenMode) 11f else 13f
        val padV = if (sevenMode) 1.5f else 3f

        for (entry in todayList) {
            val isNext = entry.epochMillis == nextPrayer.epochMillis
            val isPast = entry.epochMillis <= nowMillis
            val textColor = when {
                isNext -> COLOR_MINT
                isPast -> COLOR_DIM
                else -> COLOR_WHITE
            }
            val timeStr = JafariPrayerCalculator.formatTime(entry.epochMillis, prefs.use24Hour)

            val rowModifiers = ModifiersBuilders.Modifiers.Builder()
                .setPadding(
                    ModifiersBuilders.Padding.Builder()
                        .setStart(DimensionBuilders.dp(8f))
                        .setEnd(DimensionBuilders.dp(8f))
                        .setTop(DimensionBuilders.dp(padV))
                        .setBottom(DimensionBuilders.dp(padV))
                        .build()
                )
                .apply {
                    if (isNext) {
                        setBackground(
                            ModifiersBuilders.Background.Builder()
                                .setColor(ColorBuilders.argb(COLOR_HIGHLIGHT_BG))
                                .setCorner(
                                    ModifiersBuilders.Corner.Builder()
                                        .setRadius(DimensionBuilders.dp(8f))
                                        .build()
                                )
                                .build()
                        )
                    }
                }
                .build()

            val row = LayoutElementBuilders.Row.Builder()
                .setWidth(DimensionBuilders.dp(rowWidth))
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .setModifiers(rowModifiers)
                .addContent(styledText(entry.name, fontSize, textColor, bold = isNext))
                .addContent(
                    LayoutElementBuilders.Spacer.Builder()
                        .setWidth(DimensionBuilders.ExpandedDimensionProp.Builder().setLayoutWeight(androidx.wear.protolayout.TypeBuilders.FloatProp.Builder(1f).build()).build())
                        .build()
                )
                .addContent(styledText(timeStr, fontSize, textColor, bold = isNext))
                .build()

            colBuilder.addContent(row)
        }

        colBuilder.addContent(spacerV(if (sevenMode) 4f else 7f))

        // Bottom Controls: Refresh GPS + Settings
        val bottomRow = LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(
                pillButton(
                    label = "↻ GPS",
                    clickable = ModifiersBuilders.Clickable.Builder()
                        .setId(CLICK_REFRESH)
                        .setOnClick(ActionBuilders.LoadAction.Builder().build())
                        .build()
                )
            )
            .addContent(
                LayoutElementBuilders.Spacer.Builder()
                    .setWidth(DimensionBuilders.dp(8f))
                    .build()
            )
            .addContent(
                pillButton(
                    label = "⚙ ${prefs.method.shortLabel}",
                    clickable = ModifiersBuilders.Clickable.Builder()
                        .setId(CLICK_SETTINGS)
                        .setOnClick(
                            ActionBuilders.LaunchAction.Builder()
                                .setAndroidActivity(
                                    ActionBuilders.AndroidActivity.Builder()
                                        .setPackageName(packageName)
                                        .setClassName(MainActivity::class.java.name)
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
            )
            .build()

        colBuilder.addContent(bottomRow)

        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(COLOR_BLACK))
                            .build()
                    )
                    .build()
            )
            .addContent(colBuilder.build())
            .build()
    }

    private fun buildNeedsLocationLayout(): LayoutElementBuilders.LayoutElement {
        val openSettings = ModifiersBuilders.Clickable.Builder()
            .setId(CLICK_SETTINGS)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build()
                    )
                    .build()
            )
            .build()

        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(styledText("Ja'fari Prayer Times", 14f, COLOR_GOLD, bold = true))
            .addContent(spacerV(8f))
            .addContent(styledText("Location needed for GPS times", 11.5f, COLOR_WHITE, bold = false))
            .addContent(spacerV(12f))
            .addContent(pillButton("Tap to Enable GPS", openSettings, bgColor = COLOR_HIGHLIGHT_BG, textColor = COLOR_MINT))
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(COLOR_BLACK))
                            .build()
                    )
                    .setClickable(openSettings)
                    .build()
            )
            .addContent(col)
            .build()
    }

    private fun pillButton(
        label: String,
        clickable: ModifiersBuilders.Clickable,
        bgColor: Int = COLOR_CHIP_BG,
        textColor: Int = COLOR_LIGHT_GRAY
    ): LayoutElementBuilders.LayoutElement {
        return LayoutElementBuilders.Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(bgColor))
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(DimensionBuilders.dp(12f))
                                    .build()
                            )
                            .build()
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(DimensionBuilders.dp(8f))
                            .setEnd(DimensionBuilders.dp(8f))
                            .setTop(DimensionBuilders.dp(3.5f))
                            .setBottom(DimensionBuilders.dp(3.5f))
                            .build()
                    )
                    .setClickable(clickable)
                    .build()
            )
            .addContent(styledText(label, 10.5f, textColor, bold = false))
            .build()
    }

    private fun styledText(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean
    ): LayoutElementBuilders.Text {
        return LayoutElementBuilders.Text.Builder()
            .setText(text)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(DimensionBuilders.sp(sizeSp))
                    .setColor(ColorBuilders.argb(color))
                    .setWeight(
                        if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD
                        else LayoutElementBuilders.FONT_WEIGHT_NORMAL
                    )
                    .build()
            )
            .setMaxLines(1)
            .build()
    }

    private fun spacerV(dpVal: Float): LayoutElementBuilders.Spacer {
        return LayoutElementBuilders.Spacer.Builder()
            .setHeight(DimensionBuilders.dp(dpVal))
            .build()
    }

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val CLICK_REFRESH = "refresh_gps"
        private const val CLICK_SETTINGS = "open_settings"
        private const val LOCATION_STALE_MS = 60 * 60 * 1000L // 1 hour

        private const val COLOR_BLACK = 0xFF000000.toInt()
        private const val COLOR_WHITE = 0xFFFFFFFF.toInt()
        private const val COLOR_DIM = 0xFF7D858D.toInt()
        private const val COLOR_LIGHT_GRAY = 0xFFCFD8DC.toInt()
        private const val COLOR_GOLD = 0xFFF5C542.toInt()
        private const val COLOR_MINT = 0xFF69F0AE.toInt()
        private const val COLOR_HIGHLIGHT_BG = 0xFF12352C.toInt()
        private const val COLOR_CHIP_BG = 0xFF1E2226.toInt()
    }
}
