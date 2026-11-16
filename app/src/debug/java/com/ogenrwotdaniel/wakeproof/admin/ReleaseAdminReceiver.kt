package com.ogenrwotdaniel.wakeproof.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The adb way out of the uninstall lock:
 * `adb shell am broadcast -f 32 -n com.ogenrwotdaniel.wakeproof/.admin.ReleaseAdminReceiver`,
 * then `adb shell pm uninstall -k --user 0 com.ogenrwotdaniel.wakeproof` to keep the data.
 *
 * Declared in the debug manifest alone, behind `android.permission.DUMP`,
 * which the adb shell holds and no installed app can be granted: until
 * 2026-11-16 this was an extra on the exported MainActivity, which any app
 * on the phone could send to switch the lock off.
 */
class ReleaseAdminReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        UninstallLock.releaseAdmin(context.applicationContext)
    }
}
