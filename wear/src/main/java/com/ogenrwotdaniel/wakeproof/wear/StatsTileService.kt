package com.ogenrwotdaniel.wakeproof.wear

import android.text.format.DateFormat
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.VERTICAL_ALIGN_CENTER
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.TypeBuilders
import androidx.wear.protolayout.expression.DynamicBuilders
import androidx.wear.protolayout.material.CircularProgressIndicator
import androidx.wear.protolayout.material.ProgressIndicatorColors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import java.time.Instant
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

/**
 * A Wear OS Tile showing today's WakeProof progress — streak and focus vs goal,
 * read from the snapshot the phone mirrors onto the Data Layer,
 * with focus blocks finished on the watch but not yet synced added on top.
 *
 * While the watch's own timer runs, the tile shows the phase and the wall-clock
 * time it ends ("Break at 14:32") rather than a countdown: a fixed time stays
 * true without the tile re-rendering every second, which tiles are not for.
 *
 * Tapping the tile opens the watch app. Refreshed on demand by
 * [StatsDataListenerService] whenever the phone pushes new stats, and by
 * [WatchTimerEngine] on every timer change.
 */
class StatsTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> = scope.future {
        val context = this@StatsTileService
        val stats = WearStatsReader.read(context)
        val zone = ZoneId.systemDefault()
        val today = focusToday(
            stats, FocusSessionOutbox.pending(context), LocalDate.now(zone).toEpochDay(), zone
        )
        val timer = WatchTimerEngine.state(context)
        // The phone's session, when the watch runs none of its own.
        val phone = if (timer.active) null
        else TimerSnapshot.read(context)?.takeIf { it.liveAt(System.currentTimeMillis()) }
        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(REFRESH_MS)
            .setTileTimeline(
                TimelineBuilders.Timeline.fromLayoutElement(layout(stats, today, timer, phone))
            )
            .build()
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> = scope.future {
        ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun layout(
        stats: WearStats,
        today: WatchFocusToday,
        timer: WatchTimerState,
        phone: TimerSnapshot?
    ): LayoutElementBuilders.LayoutElement {
        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
        // Named on every state (2026-11-20): during a session the tile showed only figures.
        column.addContent(text("Euler's Lab", Typography.TYPOGRAPHY_CAPTION2, COLOR_MUTED))
        column.addContent(spacer())

        if (phone != null) {
            column.addContent(text(phone.phase.ifBlank { "Focus" }, Typography.TYPOGRAPHY_TITLE2, COLOR_STREAK))
            val detail = if (phone.running && phone.finishAtMillis > 0L) {
                val next = if (phone.phase.equals("Focus", ignoreCase = true)) "Break" else "Next"
                "$next at ${DateFormat.getTimeFormat(this).format(Date(phone.finishAtMillis))}"
            } else {
                "On your phone"
            }
            column.addContent(text(detail, Typography.TYPOGRAPHY_BODY1, COLOR_TEXT))
            column.addContent(spacer())
            column.addContent(
                text("Focus today ${today.minutes}m", Typography.TYPOGRAPHY_CAPTION1, COLOR_MUTED)
            )
        } else if (timer.active) {
            column.addContent(text(timer.phase.label, Typography.TYPOGRAPHY_TITLE2, COLOR_STREAK))
            val next = if (timer.phase == WatchPhase.FOCUS) "Break" else "Focus"
            val detail = if (timer.running) {
                "$next at ${DateFormat.getTimeFormat(this).format(Date(timer.phaseEndsAt))}"
            } else {
                "Paused"
            }
            column.addContent(text(detail, Typography.TYPOGRAPHY_BODY1, COLOR_TEXT))
            column.addContent(spacer())
            column.addContent(
                text("Focus today ${today.minutes}m", Typography.TYPOGRAPHY_CAPTION1, COLOR_MUTED)
            )
        } else if (!stats.present) {
            // Never met the phone. The watch can still run a pomodoro, so the
            // tile offers that rather than sending the user off to the phone.
            column.addContent(
                text("Focus today ${today.minutes}m", Typography.TYPOGRAPHY_TITLE3, COLOR_TEXT)
            )
            column.addContent(spacer())
            column.addContent(
                text("Tap to start\na focus block", Typography.TYPOGRAPHY_BODY2, COLOR_MUTED)
            )
        } else {
            val star = if (stats.asterisk) "*" else ""
            column.addContent(
                text("🔥 ${stats.streak}$star", Typography.TYPOGRAPHY_DISPLAY3, COLOR_STREAK)
            )
            column.addContent(text("day streak", Typography.TYPOGRAPHY_CAPTION1, COLOR_MUTED))
            column.addContent(spacer())
            val focusDone = stats.focusGoal > 0 && today.minutes >= stats.focusGoal
            column.addContent(
                text(
                    "Focus ${today.minutes}/${stats.focusGoal}m" + if (focusDone) " ✓" else "",
                    Typography.TYPOGRAPHY_TITLE3,
                    if (focusDone) COLOR_GOOD else COLOR_TEXT
                )
            )
        }
        if (today.unsyncedBlocks > 0) {
            column.addContent(spacer())
            column.addContent(
                text(
                    "${today.unsyncedBlocks} to sync",
                    Typography.TYPOGRAPHY_CAPTION2,
                    COLOR_MUTED
                )
            )
        }

        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("open")
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
                    .build()
            )
            .addContent(ring(FocusFaces.tileRing(stats.focusGoal, today.minutes, timer, System.currentTimeMillis())))
            .addContent(column.build())
            .build()
    }

    /**
     * The arc round the tile's edge (2026-11-15): the phase while the timer runs,
     * else today's focus against the goal. While a phase runs the arc is bound
     * to the platform clock, so a renderer with dynamic data moves it on by
     * itself between the tile's rare refreshes; an older one shows it as of the
     * render, which every phase change refreshes.
     */
    private fun ring(ring: TileRing): LayoutElementBuilders.LayoutElement {
        val progress = TypeBuilders.FloatProp.Builder(ring.progress)
        val endsAt = ring.phaseEndsAt
        if (endsAt != null && ring.phaseSeconds > 0) {
            val secondsLeft = DynamicBuilders.DynamicInstant.platformTimeWithSecondsPrecision()
                .durationUntil(DynamicBuilders.DynamicInstant.withSecondsPrecision(Instant.ofEpochMilli(endsAt)))
                .toIntSeconds()
                .asFloat()
            progress.setDynamicValue(
                DynamicBuilders.DynamicFloat.constant(1f).minus(secondsLeft.div(ring.phaseSeconds.toFloat()))
            )
        }
        return CircularProgressIndicator.Builder()
            .setProgress(progress.build())
            .setStrokeWidth(6f)
            .setCircularProgressIndicatorColors(
                ProgressIndicatorColors(if (ring.accent) COLOR_STREAK else COLOR_GOOD, COLOR_TRACK)
            )
            .setContentDescription("Progress")
            .build()
    }

    private fun text(value: String, typography: Int, color: Int): LayoutElementBuilders.LayoutElement =
        Text.Builder(this, value)
            .setTypography(typography)
            .setColor(argb(color))
            .setMaxLines(2)
            .build()

    private fun spacer(): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build()

    private companion object {
        const val RESOURCES_VERSION = "1"

        const val REFRESH_MS = 30 * 60 * 1000L

        const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        const val COLOR_MUTED = 0xFFB0BEC5.toInt()
        const val COLOR_STREAK = 0xFFFF7043.toInt()
        const val COLOR_GOOD = 0xFF81C784.toInt()
        const val COLOR_TRACK = 0xFF263238.toInt()
    }
}
