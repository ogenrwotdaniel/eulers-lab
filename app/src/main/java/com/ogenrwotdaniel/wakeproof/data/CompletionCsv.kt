package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.data.db.CompletionLog
import java.time.LocalDate

/**
 * The CSV export of completion logs (Settings), pure so its columns are pinned
 * by a test. `event` sits beside `subject` (2026-11-15): the user opens the file
 * in Excel at the end of a week to see where the time went, by course and by
 * calendar event.
 */
object CompletionCsv {
    const val HEADER = "date,kind,status,amount,subject,event,alarmId"

    fun row(log: CompletionLog): String = listOf(
        LocalDate.ofEpochDay(log.epochDay).toString(),
        log.kind.name,
        log.status.name,
        log.amount.toString(),
        text(log.subject),
        text(log.eventTitle),
        log.alarmId?.toString() ?: ""
    ).joinToString(",")

    /**
     * A free-text cell, always quoted with quotes doubled. Text starting like a
     * formula gets a leading apostrophe: an event title comes from whoever sent
     * the calendar invitation, and Excel would otherwise run "=…" as a formula.
     */
    fun text(value: String?): String {
        val raw = value.orEmpty()
        val safe = if (raw.firstOrNull() in FORMULA_STARTS) "'$raw" else raw
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }

    private val FORMULA_STARTS = setOf('=', '+', '-', '@', '\t', '\r')
}
