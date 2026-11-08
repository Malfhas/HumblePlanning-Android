package com.humbleplanning.app.ui.task

import com.humbleplanning.app.data.References
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskFormTest {
    private val valid = TaskForm(projectId = "p", userIds = listOf("u"), statusId = "s", startDate = "2026-11-05")
    private val refs = References(config = mapOf("AM_START" to "07:30", "AM_END" to "11:30", "PM_START" to "13:00", "PM_END" to "17:00"))

    @Test fun aCompleteFormIsValid() {
        assertNull(validateTaskForm(valid))
    }

    @Test fun requiredFields() {
        assertEquals(FormError.ProjectRequired, validateTaskForm(valid.copy(projectId = "")))
        assertEquals(FormError.UsersRequired, validateTaskForm(valid.copy(userIds = emptyList())))
        assertEquals(FormError.StatusRequired, validateTaskForm(valid.copy(statusId = "")))
        assertEquals(FormError.StartDateRequired, validateTaskForm(valid.copy(startDate = "")))
    }

    @Test fun endDateNotBeforeStart() {
        assertEquals(FormError.EndDateBeforeStart, validateTaskForm(valid.copy(endDate = "2026-11-04")))
        assertNull(validateTaskForm(valid.copy(endDate = "2026-11-06")))
    }

    @Test fun timesEndAfterStartOnASingleDay() {
        assertEquals(FormError.TimesRequired, validateTaskForm(valid.copy(mode = DayMode.Times)))
        assertEquals(FormError.EndTimeBeforeStart, validateTaskForm(valid.copy(mode = DayMode.Times, startTime = "12:00", endTime = "12:00")))
        assertNull(validateTaskForm(valid.copy(mode = DayMode.Times, startTime = "9:00", endTime = "10:00")))
        assertNull(validateTaskForm(valid.copy(mode = DayMode.Times, startTime = "16:00", endTime = "08:00", endDate = "2026-11-06")))
    }

    @Test fun linkIsAWebAddressOrEmpty() {
        assertEquals(FormError.LinkInvalid, validateTaskForm(valid.copy(link = "not a link")))
        assertEquals(FormError.LinkInvalid, validateTaskForm(valid.copy(link = "javascript:alert(1)")))
        assertNull(validateTaskForm(valid.copy(link = "https://example.com/a")))
        assertNull(validateTaskForm(valid.copy(link = "   ")))
    }

    @Test fun recurrenceNeedsAnEndAfterTheStartAndAValidFrequency() {
        val repeat = valid.copy(repeat = true, recurEndDate = "2026-12-31")
        assertNull(validateTaskForm(repeat))
        assertEquals(FormError.RecurrenceEndRequired, validateTaskForm(repeat.copy(recurEndDate = "")))
        assertEquals(FormError.RecurrenceEndBeforeStart, validateTaskForm(repeat.copy(recurEndDate = "2026-11-05")))
        assertEquals(FormError.RecurrenceFrequency, validateTaskForm(repeat.copy(recurFrequency = 0)))
        assertEquals(FormError.RecurrenceFrequency, validateTaskForm(repeat.copy(recurFrequency = 31)))
    }

    @Test fun allDayClearsTheTimes() {
        val payload = valid.toPayload(refs, canLock = false)
        assertNull(payload.startTime)
        assertNull(payload.endTime)
    }

    @Test fun halfDaysUseTheHoursOfTheServer() {
        assertEquals("07:30", valid.copy(mode = DayMode.Morning).toPayload(refs, false).startTime)
        assertEquals("11:30", valid.copy(mode = DayMode.Morning).toPayload(refs, false).endTime)
        assertEquals("13:00", valid.copy(mode = DayMode.Afternoon).toPayload(refs, false).startTime)
        assertEquals("17:00", valid.copy(mode = DayMode.Afternoon).toPayload(refs, false).endTime)
    }

    @Test fun aSingleDayHasNoEndDate() {
        assertNull(valid.copy(endDate = "2026-11-05").toPayload(refs, false).endDate)
        assertEquals("2026-11-06", valid.copy(endDate = "2026-11-06").toPayload(refs, false).endDate)
    }

    @Test fun titleAndNotesGoOutEmptyAndTheOtherTextAsNull() {
        val payload = valid.toPayload(refs, false)
        assertEquals("", payload.title)
        assertEquals("", payload.notes)
        assertNull(payload.link)
        assertNull(payload.placeId)
        assertNull(payload.placeText)
        assertNull(payload.resourceId)
    }

    @Test fun lockOnlyGoesOutWithTheRight() {
        assertNull(valid.copy(locked = true).toPayload(refs, canLock = false).isLocked)
        assertEquals(true, valid.copy(locked = true).toPayload(refs, canLock = true).isLocked)
        assertEquals(false, valid.toPayload(refs, canLock = true).isLocked)
    }

    @Test fun aFileNameIsNeverAPath() {
        assertEquals("report.pdf", safeFileName("../../report.pdf", "x"))
        assertEquals("report.pdf", safeFileName("..\\..\\report.pdf", "x"))
        assertEquals("hidden", safeFileName(".hidden", "x"))
        assertEquals("x", safeFileName("..", "x"))
        assertEquals("x", safeFileName("", "x"))
    }
}
