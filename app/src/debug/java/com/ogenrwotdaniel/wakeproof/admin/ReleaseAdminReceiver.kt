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
 *
 * Refused until 2027-11-21 ([UninstallLock.HELD_UNTIL_MILLIS]); `am broadcast`
 * prints the answer (`result=1` and "released", or `result=0` and the refusal).
 */
class ReleaseAdminReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val released = UninstallLock.releaseAdmin(context.applicationContext)
        if (isOrderedBroadcast) {
            resultCode = if (released) 1 else 0
            resultData = if (released) "released" else
                "refused: the uninstall lock holds until 2027-11-21 (and needs network time)"
        }
    }
}
