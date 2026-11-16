package com.ogenrwotdaniel.wakeproof.wear.walls

import android.Manifest
import android.app.AppOpsManager
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ogenrwotdaniel.wakeproof.wear.TimerSnapshot
import com.ogenrwotdaniel.wakeproof.wear.WatchTimerEngine
import com.ogenrwotdaniel.wakeproof.wear.WearStatsReader
import java.time.LocalDateTime

/**
 * Which grants the watch's walls are missing, for any screen that wants to say so
 * (the watch has no settings page for any of them; they are given over adb).
 */
object WatchWallGrants {
    fun missing(context: Context): List<WatchWallGrant> = missingWallGrants(
        usageAccess = hasUsageAccess(context),
        overlay = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false),
        notificationListener = runCatching {
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
        }.getOrDefault(false),
        postNotifications = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    )

    /** Usage access is an app-op: the manifest permission alone grants nothing. */
    fun hasUsageAccess(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }.getOrDefault(false)
}

/**
 * Process-wide wall state shared by [WatchWallService] and [WatchWallNotificationListener]:
 * the phone's last word (read from the Data Layer at most every [PHONE_REFRESH_MILLIS],
 * sooner after a push marks it [markPhoneStale]) and which packages are gated.
 */
object WatchWallState {
    /** The phone's word changes rarely and each read is a binder round trip to Play services. */
    private const val PHONE_REFRESH_MILLIS = 30_000L

    /** Installs and default apps change rarely; a package's class is read once per refresh. */
    private const val PACKAGES_REFRESH_MILLIS = 5 * 60_000L

    @Volatile private var phoneReadAt = 0L
    @Volatile private var summaryUntil = 0L
    @Volatile private var examUntil = 0L
    @Volatile private var phoneTimer: TimerSnapshot? = null

    private val gatedCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    @Volatile private var packagesReadAt = 0L
    @Volatile private var browsers: Set<String> = emptySet()
    @Volatile private var essentials: Set<String> = WatchGate.NEVER_WALLED

    /** A phone push landed ([com.ogenrwotdaniel.wakeproof.wear.StatsDataListenerService]): read again next tick. */
    fun markPhoneStale() {
        phoneReadAt = 0L
    }

    /** Re-reads the phone's stats and timer when stale. Any thread but main. */
    suspend fun refreshPhone(context: Context, now: Long = System.currentTimeMillis()) {
        if (now - phoneReadAt in 0 until PHONE_REFRESH_MILLIS) return
        // A failed read keeps the last word: an exam week must not lift on a flaky link.
        runCatching { WearStatsReader.read(context) }.getOrNull()?.takeIf { it.present }?.let {
            summaryUntil = it.summaryUntilMillis
            examUntil = it.examShutUntilMillis
        }
        runCatching { TimerSnapshot.read(context) }.onSuccess { phoneTimer = it }
        phoneReadAt = now
    }

    /** The wall up now, or null when gated apps are open. Reads only cached phone state. */
    /** When the phone's held exam weeks end, in local time; null for none. */
    fun examEnd(): LocalDateTime? = examUntil.takeIf { it > 0L }?.let {
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), java.time.ZoneId.systemDefault())
    }

    fun verdict(context: Context, now: Long = System.currentTimeMillis()): WatchWall? = watchWallVerdict(
        WallInputs(
            nowMillis = now,
            localNow = LocalDateTime.now(),
            autoTimeOn = autoTimeOn(context),
            watchSessionActive = runCatching { WatchTimerEngine.state(context).active }.getOrDefault(false),
            phoneSessionLive = phoneTimer?.liveAt(now) == true,
            summaryUntilMillis = summaryUntil,
            examShutUntilMillis = examUntil
        )
    )

    /**
     * A clock that cannot be read as automatic is believed (the phone's rule: a
     * check that throws is no doubt), so a missing setting cannot lock the watch.
     */
    private fun autoTimeOn(context: Context): Boolean = runCatching {
        val resolver = context.contentResolver
        clockIsAutomatic(
            Settings.Global.getInt(resolver, Settings.Global.AUTO_TIME, 1),
            Settings.Global.getInt(resolver, CLOCKWORK_AUTO_TIME, CLOCKWORK_TIME_UNKNOWN)
        )
    }.getOrDefault(true)

    /** Whether [packageName] is gated on this watch. Any thread but main (PackageManager). */
    fun isGated(context: Context, packageName: String, now: Long = System.currentTimeMillis()): Boolean {
        refreshPackages(context, now)
        return gatedCache.getOrPut(packageName) {
            val category = runCatching {
                context.packageManager.getApplicationInfo(packageName, 0).category
            }.getOrDefault(-1)
            WatchGate.isGated(packageName, category, packageName in browsers, essentials)
        }
    }

    private fun refreshPackages(context: Context, now: Long) {
        if (now - packagesReadAt in 0 until PACKAGES_REFRESH_MILLIS) return
        val pm = context.packageManager
        browsers = runCatching {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE),
                PackageManager.MATCH_ALL
            ).mapTo(mutableSetOf()) { it.activityInfo.packageName }
        }.getOrDefault(browsers)
        essentials = WatchGate.essentialsWith(dynamicEssentials(context))
        gatedCache.clear()
        packagesReadAt = now
    }

    /**
     * The watch's own home, dialer, keyboard and watch face, whatever their package:
     * Samsung and Google name them differently, and a vendor update can rename one.
     */
    private fun dynamicEssentials(context: Context): Set<String> {
        val pm = context.packageManager
        val found = mutableSetOf(context.packageName)
        runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL)
                .forEach { found += it.activityInfo.packageName }
        }
        runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_DIAL), PackageManager.MATCH_ALL)
                .forEach { found += it.activityInfo.packageName }
        }
        runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.let { ComponentName.unflattenFromString(it)?.packageName }
                ?.let { found += it }
        }
        runCatching {
            WallpaperManager.getInstance(context).wallpaperInfo?.packageName?.let { found += it }
        }
        return found
    }
}
