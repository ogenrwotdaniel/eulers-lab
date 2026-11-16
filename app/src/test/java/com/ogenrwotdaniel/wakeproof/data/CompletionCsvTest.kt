package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.data.db.CompletionLog
import com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus
import com.ogenrwotdaniel.wakeproof.data.db.GoalKind
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class CompletionCsvTest {
    private val day = LocalDate.of(2026, 11, 15).toEpochDay()

    @Test
    fun `the event column sits after the subject`() {
        assertEquals("date,kind,status,amount,subject,event,alarmId", CompletionCsv.HEADER)
    }

    @Test
    fun `a focus row carries course and event, quoted`() {
        val log = CompletionLog(
            epochDay = day, kind = GoalKind.FOCUS, status = CompletionStatus.VERIFIED, amount = 25,
            subject = "Materials", eventTitle = "Graphene \"2D\" seminar, room 3"
        )
        assertEquals(
            "2026-11-15,FOCUS,VERIFIED,25,\"Materials\",\"Graphene \"\"2D\"\" seminar, room 3\",",
            CompletionCsv.row(log)
        )
    }

    @Test
    fun `a row without an event has an empty cell and keeps its alarm id`() {
        val log = CompletionLog(
            epochDay = day, kind = GoalKind.PUSHUP, status = CompletionStatus.VERIFIED, amount = 10, alarmId = 3
        )
        assertEquals("2026-11-15,PUSHUP,VERIFIED,10,\"\",\"\",3", CompletionCsv.row(log))
    }

    @Test
    fun `text that Excel would run as a formula is kept as text`() {
        assertEquals("\"'=HYPERLINK(1)\"", CompletionCsv.text("=HYPERLINK(1)"))
        assertEquals("\"'-1\"", CompletionCsv.text("-1"))
    }
}
