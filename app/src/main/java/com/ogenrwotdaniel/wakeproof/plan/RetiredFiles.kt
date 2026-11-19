package com.ogenrwotdaniel.wakeproof.plan

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the removed day plan and evening review left in the app's private storage.
 * Not `top_three.json` any more: tomorrow's top three came back on 2026-11-20 and
 * uses that file again.
 */
internal fun retiredFiles(filesDir: File): List<File> = listOf(
    File(filesDir, "day_plans"),
    File(filesDir, "day_reviews.json"),
    File(filesDir, "day_reviews.json.part")
)

/**
 * Deletes the day-plan photos, evening reviews and top-three lists (2026-11-13
 * and 2026-11-19, the user's request when those features were removed: nothing
 * shows them any more, and files nobody can open or delete would only take up
 * storage). Only those names, never the session summaries beside them. Run with
 * the app on screen, when the storage is unlocked; a failure is tried again next time.
 */
suspend fun deleteRetiredFiles(context: Context) = withContext(Dispatchers.IO) {
    // Once: a later feature that reused one of these names must not be wiped at every start.
    val prefs = context.getSharedPreferences("retired_files", Context.MODE_PRIVATE)
    if (prefs.getBoolean(KEY_DONE, false)) return@withContext
    val gone = retiredFiles(context.filesDir).map { runCatching { it.deleteRecursively() }.getOrDefault(false) }
    if (gone.all { it }) prefs.edit().putBoolean(KEY_DONE, true).apply()
}

private const val KEY_DONE = "day_plans_reviews_and_top_three_deleted"
