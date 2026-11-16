package com.ogenrwotdaniel.wakeproof.wear.walls

import android.app.Application

/**
 * Starts the watch's walls with the app's process (2026-11-15). Most starts are the
 * user opening the app, where a foreground service may start; a process started in
 * the background (a Data Layer push, a tile) is refused quietly by
 * [WatchWallService.start] and the boot or listener path brings the walls back.
 */
class WakeProofWearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WatchWallService.start(this)
    }
}
