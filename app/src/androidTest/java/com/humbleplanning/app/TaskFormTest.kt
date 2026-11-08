package com.humbleplanning.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.humbleplanning.app.support.Rest
import com.humbleplanning.app.support.Seed
import com.humbleplanning.app.support.UiTest
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskFormTest : UiTest() {
    private val today: LocalDate get() = LocalDate.now()

    private fun newForm() {
        tap(icon(s(R.string.new_task)))
        waitExists(clickable(s(R.string.save)))
    }

    private fun chooseProject() {
        tap(clickable(s(R.string.field_project) + " *", substring = true))
        waitExists(label(s(R.string.field_project)))
        pick(Seed.project.name)
        waitExists(clickable(s(R.string.save)))
    }

    private fun save() = tap(clickable(s(R.string.save)))

    private fun waitCreated(title: String): JSONObject {
        var task: JSONObject? = null
        waitForServer("task $title on the server") {
            task = Rest.taskByTitle(title, today)
            task != null
        }
        // The sync route only has the fields of a card
        return Rest.task(task!!.getString("id"))!!
    }

    private fun edit(title: String) {
        openTask(title)
        tap(icon(s(R.string.edit)))
        waitExists(field(s(R.string.field_title)))
    }

    @Test fun validation_thenCorrected_createsAnAllDayTask() {
        val title = Rest.unique("form all day")
        signIn(Seed.own)
        newForm()
        type(s(R.string.field_title), title)
        save()
        waitText(s(R.string.val_project))
        chooseProject()
        waitGone(label(s(R.string.val_project)))

        tap(clickable(s(R.string.field_end), substring = true))
        chooseInDatePicker(today.minusDays(1))
        save()
        waitText(s(R.string.val_end_before_start))
        assertNull("nothing sent", Rest.taskByTitle(title, today))

        tap(icon(s(R.string.form_clear_end)))
        save()
        val task = waitCreated(title)
        assertTrue(task.isNull("startTime"))
        assertEquals(Seed.project.id, task.getString("projectId"))
        assertEquals(Seed.own.id, task.getJSONArray("users").getJSONObject(0).getString("id"))
        waitForCard(title)
    }

    @Test fun create_multiDayWithTimes() {
        val title = Rest.unique("form times")
        signIn(Seed.own)
        newForm()
        chooseProject()
        type(s(R.string.field_title), title)
        tap(clickable(s(R.string.field_end), substring = true))
        chooseInDatePicker(today.plusDays(2))
        tap(clickable(s(R.string.form_times)))
        tap(clickable(s(R.string.field_start_time), substring = true))
        tap(clickable(s(R.string.ok)) and isInDialog())
        tap(clickable(s(R.string.field_end_time), substring = true))
        tap(clickable(s(R.string.ok)) and isInDialog())
        save()
        val task = waitCreated(title)
        assertEquals(today.plusDays(2).toString(), task.getString("endDate"))
        assertTrue(task.getString("startTime") < task.getString("endTime"))
    }

    @Test fun edit_clearingTimesGoesBackToAllDay_andKeepsTheRest() {
        val task = Rest.createTask(Rest.unique("edit times"), listOf(Seed.own.id)) {
            put("startTime", "09:30")
            put("endTime", "10:45")
            put("notes", "Kept notes")
            put("link", "https://example.com/kept")
            put("isMilestone", true)
        }
        signIn(Seed.own)
        edit(task.getString("title"))
        waitText("09:30")
        tap(clickable(s(R.string.all_day)))
        save()
        waitForServer("times removed") { Rest.task(task.getString("id"))!!.isNull("startTime") }
        val after = Rest.task(task.getString("id"))!!
        assertTrue(after.isNull("endTime"))
        assertEquals("Kept notes", after.getString("notes"))
        assertEquals("https://example.com/kept", after.getString("link"))
        assertTrue(after.getBoolean("isMilestone"))
    }

    @Test fun edit_changingTheAddressClearsItsCoordinates() {
        val task = Rest.createTask(Rest.unique("edit place"), listOf(Seed.own.id)) {
            put("placeText", "Old address")
            put("placeTextLat", "48.8566")
            put("placeTextLng", "2.3522")
        }
        signIn(Seed.own)
        edit(task.getString("title"))
        waitRevealed(field(s(R.string.field_place_text)))
        type(s(R.string.field_place_text), "New address")
        save()
        waitForServer("address saved") { Rest.task(task.getString("id"))!!.getString("placeText") == "New address" }
        val after = Rest.task(task.getString("id"))!!
        assertTrue("old latitude gone", after.isNull("placeTextLat"))
        assertTrue("old longitude gone", after.isNull("placeTextLng"))
    }

    @Test fun recurring_creationMakesTheSeries_thenApplyToAllChangesIt() {
        val title = Rest.unique("form repeat")
        signIn(Seed.own)
        newForm()
        chooseProject()
        type(s(R.string.field_title), title)
        waitRevealed(clickable(s(R.string.form_repeat)))
        tap(clickable(s(R.string.form_repeat)))
        tap(clickable(s(R.string.recur_daily)))
        tap(clickable(s(R.string.form_exception_include)))
        tap(clickable(s(R.string.form_repeat_until), substring = true))
        chooseInDatePicker(today.plusDays(3))
        save()
        fun series() = Rest.tasksBetween(today, today.plusDays(3)).filter { it.optString("title") == title }
        waitForServer("series on the server", LONG_TIMEOUT) { series().size == 4 }
        val rootId = series().first { it.getString("startDate") == today.toString() }.getString("id")
        assertEquals("daily", Rest.task(rootId)!!.getString("recurType"))

        val newTitle = Rest.unique("form repeat edited")
        edit(title)
        type(s(R.string.field_title), newTitle)
        waitRevealed(label(s(R.string.form_apply_all)))
        tap(clickable(s(R.string.form_apply_all), substring = true))
        save()
        waitForServer("all changed", LONG_TIMEOUT) {
            Rest.tasksBetween(today, today.plusDays(3)).count { it.optString("parentId") == rootId && it.getString("title") == newTitle } == 4
        }
    }

    @Test fun duplicate_ofSomeoneElsesTask_isForMyselfWithinMyScope() {
        val copyTitle = Rest.unique("own copy")
        signIn(Seed.own)
        showEveryone()
        openTask(Seed.task("someoneElse").title)
        tap(icon(s(R.string.more_actions)))
        tap(clickable(s(R.string.duplicate)))
        waitText(s(R.string.form_title_copy))
        waitExists(field(s(R.string.field_title)))
        type(s(R.string.field_title), copyTitle)
        save()
        val copy = waitCreated(copyTitle)
        val users = copy.getJSONArray("users")
        assertEquals(1, users.length())
        assertEquals(Seed.own.id, users.getJSONObject(0).getString("id"))
        assertNotNull("original kept", Rest.task(Seed.task("someoneElse").id))
    }

    @Test fun back_withChangesAsksFirst() {
        val task = Rest.createTask(Rest.unique("dirty back"), listOf(Seed.own.id))
        val newTitle = Rest.unique("never saved")
        signIn(Seed.own)
        edit(task.getString("title"))
        pressBack()
        waitExists(icon(s(R.string.edit)))
        assertFalse("untouched: leaves at once", exists(label(s(R.string.form_discard_title))))

        tap(icon(s(R.string.edit)))
        waitExists(field(s(R.string.field_title)))
        type(s(R.string.field_title), newTitle)
        pressBackUntil(label(s(R.string.form_discard_title)))
        tap(clickable(s(R.string.form_keep_editing)))
        waitGone(label(s(R.string.form_discard_title)))
        waitText(newTitle)

        pressBackUntil(label(s(R.string.form_discard_title)))
        tap(clickable(s(R.string.form_discard)))
        waitExists(icon(s(R.string.edit)))
        assertEquals(task.getString("title"), Rest.task(task.getString("id"))!!.getString("title"))
    }
}
