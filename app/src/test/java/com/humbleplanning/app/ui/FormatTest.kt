package com.humbleplanning.app.ui

import com.humbleplanning.app.data.TaskDto
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {
    private val day = "2026-11-05"

    @Test fun multiDayFirstThenAllDayThenByStartTime() {
        val sorted = listOf(
            TaskDto(id = "late", startDate = day, startTime = "14:00:00", endTime = "16:00:00"),
            TaskDto(id = "allday", startDate = day),
            TaskDto(id = "early", startDate = day, startTime = "08:00:00", endTime = "12:00:00"),
            TaskDto(id = "multi", startDate = "2026-11-04", endDate = "2026-11-07", startTime = "16:00:00"),
        ).sortedForDay()
        assertEquals(listOf("multi", "allday", "early", "late"), sorted.map { it.id })
    }

    @Test fun anUnpaddedTimeSortsLikeThePaddedOne() {
        val sorted = listOf(
            TaskDto(id = "ten", startDate = day, startTime = "10:00"),
            TaskDto(id = "nine", startDate = day, startTime = "9:00"),
        ).sortedForDay()
        assertEquals(listOf("nine", "ten"), sorted.map { it.id })
    }

    @Test fun sameTimeSortsByTitle() {
        val sorted = listOf(
            TaskDto(id = "b", startDate = day, title = "beta", startTime = "08:00:00"),
            TaskDto(id = "a", startDate = day, title = "Alpha", startTime = "08:00:00"),
        ).sortedForDay()
        assertEquals(listOf("a", "b"), sorted.map { it.id })
    }

    @Test fun timeRangeKeepsTheTimesAndHasNoneForAWholeDay() {
        assertEquals("08:00 – 12:00", TaskDto(startTime = "08:00:00", endTime = "12:00:00").timeRange())
        assertEquals("08:00", TaskDto(startTime = "08:00:00").timeRange())
        assertNull(TaskDto().timeRange())
        assertNull(TaskDto(startTime = "").timeRange())
    }

    @Test fun occursOnEveryDayOfItsSpan() {
        val task = TaskDto(startDate = "2026-11-04", endDate = "2026-11-06")
        assertFalse(task.occursOn(LocalDate.parse("2026-11-03")))
        assertTrue(task.occursOn(LocalDate.parse("2026-11-04")))
        assertTrue(task.occursOn(LocalDate.parse("2026-11-06")))
        assertFalse(task.occursOn(LocalDate.parse("2026-11-07")))
        assertTrue(TaskDto(startDate = day).occursOn(LocalDate.parse(day)))
    }

    @Test fun mondayOfAWeek() {
        assertEquals(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-05").monday())
        assertEquals(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-02").monday())
        assertEquals(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-08").monday())
    }
}
