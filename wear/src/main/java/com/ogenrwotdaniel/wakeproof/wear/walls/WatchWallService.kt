package com.ogenrwotdaniel.wakeproof.wear.walls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ogenrwotdaniel.wakeproof.wear.R
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Walls gated apps on the watch (2026-11-15). A `specialUse` foreground service that,
 * while the screen is on, reads which app is in front about once a second
 * ([WatchForegroundTracker]) and, when [WatchWallState.verdict] shuts a gated app,
 * opens [WatchBlockedActivity] over it. Nothing kills the app's process: from
 * Android 14 an app may kill only its own, so the notification listener is what keeps
 * a walled messenger quiet behind the wall.
 *
 * Every wall is reactive, as on the phone: nothing is suspended at the OS level, so
 * a walled app shows for up to a tick before the wall lands. Missing grants are
 * survived, never crashed on ([WatchWallGrants]): without usage access nothing can
 * be seen and the loop only waits for the grant.
 */
class WatchWallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val interactive = MutableStateFlow(true)
    private var tracker: WatchForegroundTracker? = null

    private var lastWallPackage: String? = null
    private var lastWallAt = 0L
    private var lastMediaCheckAt = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            interactive.value = intent.action != Intent.ACTION_SCREEN_OFF
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        // A refused foreground start (background start on Android 12+) must not crash the
        // app: the service then stops and is tried again at the next app start or boot.
        // Stopping here does not trip "did not call startForeground": the call was made,
        // and the system clears the pending-foreground deadline before it refuses.
        val started = runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type) }
        if (started.isFailure) {
            Log.w(TAG, "foreground start refused", started.exceptionOrNull())
            stopSelf()
            return
        }
        interactive.value = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
        )
        scope.launch { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    private suspend fun loop() {
        while (true) {
            if (!interactive.value) {
                // Screen off: nothing is in front, and polling would only drain the battery.
                tracker?.forgetResumed()
                interactive.first { it }
            }
            runCatching { tick() }.onFailure { Log.w(TAG, "tick failed", it) }
            delay(TICK_MILLIS)
        }
    }

    private suspend fun tick() {
        if (!WatchWallGrants.hasUsageAccess(this)) {
            // No usage access: no app can be seen. Waits for the grant without spinning.
            tracker = null
            delay(NO_GRANT_RECHECK_MILLIS)
            return
        }
        val now = System.currentTimeMillis()
        pauseWalledMedia(now)
        val usm = getSystemService(UsageStatsManager::class.java) ?: return
        val current = (tracker ?: WatchForegroundTracker(usm).also { tracker = it }).current(now)
        val front = current ?: return
        if (front == packageName || !WatchWallState.isGated(this, front, now)) return
        // The phone's state is read only with a gated app in front: most ticks show the
        // watch face, and two Data Layer reads every 30 s on those cost battery for nothing.
        WatchWallState.refreshPhone(this, now)
        val wall = WatchWallState.verdict(this, now) ?: return
        val elapsed = SystemClock.elapsedRealtime()
        // The wall's own resume lands in the event store a moment late; without this
        // gap one opening could stack several wall screens.
        if (front == lastWallPackage && elapsed - lastWallAt < RELAUNCH_GAP_MILLIS) return
        lastWallPackage = front
        lastWallAt = elapsed
        showWall(wall)
    }

    /**
     * Pauses a gated app's music or video while its wall is up, as the phone's
     * `pauseWalledMedia` does: covering the screen and sending the user home leaves a
     * MediaSession playing, and a shut app that goes on playing is not shut. Every
     * [MEDIA_CHECK_MILLIS]; needs notification access (the listener is the key to the
     * active sessions), and without it does nothing. Not for the summary hold, which
     * removes nothing on the phone either.
     */
    private suspend fun pauseWalledMedia(now: Long) {
        val elapsed = SystemClock.elapsedRealtime()
        if (elapsed - lastMediaCheckAt < MEDIA_CHECK_MILLIS) return
        lastMediaCheckAt = elapsed
        val sessions = runCatching {
            getSystemService(MediaSessionManager::class.java)
                ?.getActiveSessions(ComponentName(this, WatchWallNotificationListener::class.java))
        }.getOrNull() ?: return
        val playing = sessions.filter {
            it.playbackState?.state == PlaybackState.STATE_PLAYING &&
                it.packageName != packageName &&
                WatchWallState.isGated(this, it.packageName, now)
        }
        if (playing.isEmpty()) return
        WatchWallState.refreshPhone(this, now)
        if (!wallRemovesNotifications(WatchWallState.verdict(this, now))) return
        playing.forEach { controller ->
            Log.i(TAG, "pausing ${controller.packageName}'s media")
            runCatching { controller.transportControls.pause() }
        }
    }

    private fun showWall(wall: WatchWall) {
        val intent = Intent(this, WatchBlockedActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(WatchBlockedActivity.EXTRA_TITLE, wall.title)
            .putExtra(WatchBlockedActivity.EXTRA_DETAIL, wallDetail(wall, LocalDateTime.now(), DateFormat.is24HourFormat(this), WatchWallState.examEnd(), WatchWallState.window()))
        // Without the overlay grant Android refuses a start from the background; the
        // refusal is logged and the next tick tries again, never a crash.
        runCatching { startActivity(intent) }.onFailure { Log.w(TAG, "wall refused", it) }
    }


    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Walls", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps gated apps shut on the watch, as on the phone."
                setShowBadge(false)
            }
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Walls are up")
            .setContentText("Gated apps open ${openWindowText(DateFormat.is24HourFormat(this), WatchWallState.window())}.")
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val TAG = "WatchWallService"
        private const val CHANNEL_ID = "wakeproof_watch_walls"
        private const val NOTIFICATION_ID = 61
        private const val TICK_MILLIS = 1_000L
        private const val NO_GRANT_RECHECK_MILLIS = 30_000L
        private const val RELAUNCH_GAP_MILLIS = 2_000L
        private const val MEDIA_CHECK_MILLIS = 2_000L

        /**
         * Starts the service. Called from app start, boot and package replace; a start
         * Android refuses (a background start on Android 12+) is dropped, and the next
         * trigger tries again.
         */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, WatchWallService::class.java))
            }.onFailure { Log.w(TAG, "start refused", it) }
        }
    }
}
