package com.humbleplanning.app

import com.humbleplanning.app.data.TaskDto
import com.humbleplanning.app.reminders.ReminderOffset
import com.humbleplanning.app.reminders.ReminderPlanner
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPlannerTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val now: Instant = LocalDateTime.of(2026, 11, 5, 7, 0).atZone(zone).toInstant()

    private fun plan(tasks: List<TaskDto>, offset: ReminderOffset = ReminderOffset.Min15) =
        ReminderPlanner.plan(tasks, "08:00", offset, now, zone) { "title" to "detail" }

    @Test fun firesTheChosenDelayBeforeTheStartTime() {
        val reminder = plan(listOf(TaskDto(id = "a", startDate = "2026-11-05", startTime = "09:30:00"))).single()
        assertEquals(LocalDateTime.of(2026, 11, 5, 9, 15).atZone(zone).toInstant().toEpochMilli(), reminder.fireAtMillis)
        assertEquals("a", reminder.taskId)
        assertEquals("09:30 · detail", reminder.text)
    }

    @Test fun anAllDayTaskUsesTheMorningStart() {
        val reminder = plan(listOf(TaskDto(id = "a", startDate = "2026-11-05"))).single()
        assertEquals(LocalDateTime.of(2026, 11, 5, 7, 45).atZone(zone).toInstant().toEpochMilli(), reminder.fireAtMillis)
    }

    @Test fun leavesOutWhatIsAlreadyPast() {
        assertTrue(plan(listOf(TaskDto(id = "a", startDate = "2026-11-05", startTime = "07:10:00"))).isEmpty())
        assertTrue(plan(listOf(TaskDto(id = "b", startDate = "2026-11-04", startTime = "12:00:00"))).isEmpty())
    }

    @Test fun noneMeansNoReminder() {
        assertTrue(plan(listOf(TaskDto(id = "a", startDate = "2026-11-06")), ReminderOffset.None).isEmpty())
    }

    @Test fun anUnreadableDateIsIgnored() {
        assertTrue(plan(listOf(TaskDto(id = "a", startDate = "soon"))).isEmpty())
    }

    @Test fun sortedByFiringTime() {
        val reminders = plan(
            listOf(
                TaskDto(id = "late", startDate = "2026-11-07"),
                TaskDto(id = "soon", startDate = "2026-11-05", startTime = "10:00:00"),
            )
        )
        assertEquals(listOf("soon", "late"), reminders.map { it.taskId })
    }

    @Test fun startTimeReadsTheTaskThenTheDefault() {
        assertEquals(LocalTime.of(9, 30), ReminderPlanner.startTime(TaskDto(startTime = "09:30:00"), "08:00"))
        assertEquals(LocalTime.of(8, 0), ReminderPlanner.startTime(TaskDto(), "08:00"))
        assertEquals(LocalTime.of(8, 0), ReminderPlanner.startTime(TaskDto(startTime = "bad"), "also bad"))
    }
}
