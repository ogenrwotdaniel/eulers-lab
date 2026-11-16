package com.ogenrwotdaniel.wakeproof.wear.walls

import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Removes notifications posted on the watch by gated apps while they are shut
 * (2026-11-15): watch-native apps such as WhatsApp for Wear OS post their own, and
 * the phone's guard never sees those. Bridged copies of phone notifications are
 * posted by the companion, not the app, and go when the phone removes the original.
 *
 * Also starts [WatchWallService] when bound: the system binds a granted listener
 * at boot, which is one more way back for a wall service that was stopped.
 */
class WatchWallNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onListenerConnected() {
        super.onListenerConnected()
        WatchWallService.start(this)
        scope.launch {
            val active = runCatching { activeNotifications?.toList() }.getOrNull().orEmpty()
            active.forEach { remove(it) }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        scope.launch { remove(posted) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun remove(sbn: StatusBarNotification) {
        val context: Context = this
        if (sbn.packageName == packageName) return
        runCatching {
            if (!WatchWallState.isGated(context, sbn.packageName)) return
            WatchWallState.refreshPhone(context)
            if (wallRemovesNotifications(WatchWallState.verdict(context))) cancelNotification(sbn.key)
        }.onFailure { Log.w(TAG, "could not judge a notification", it) }
    }

    private companion object {
        const val TAG = "WatchWallListener"
    }
}

/**
 * Starts [WatchWallService] at boot and after an update. Boot and package-replaced
 * broadcasts are among the few moments Android 12+ still allows a foreground
 * service to start from the background.
 */
class WatchWallBootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> WatchWallService.start(context)
        }
    }
}
