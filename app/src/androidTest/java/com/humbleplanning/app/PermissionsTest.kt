package com.humbleplanning.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.humbleplanning.app.support.Rest
import com.humbleplanning.app.support.Seed
import com.humbleplanning.app.support.UiTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What each account is offered, on screen. The server enforces it too: tools/api-tests covers that side. */
@RunWith(AndroidJUnit4::class)
class PermissionsTest : UiTest() {
    private fun openUsersPicker() {
        tap(icon(s(R.string.new_task)))
        waitExists(clickable(s(R.string.save)))
        openChoice(R.string.field_users)
    }

    private fun backToPlanning() {
        tap(icon(s(R.string.back)))
        waitExists(clickable(s(R.string.today)))
    }

    @Test fun reader_isOfferedNothingThatWrites() {
        signIn(Seed.reader)
        waitForCard(Seed.task("readerMine").title)
        assertFalse("new task", exists(icon(s(R.string.new_task))))

        openTask(Seed.task("readerMine").title)
        waitExists(label(s(R.string.field_date)))
        assertFalse("edit", exists(icon(s(R.string.edit))))
        assertFalse("more actions", exists(icon(s(R.string.more_actions))))
        backToPlanning()

        showEveryone()
        openTask(Seed.task("attached").title)
        waitExists(label(s(R.string.field_date)))
        assertFalse("edit of someone else's", exists(icon(s(R.string.edit))))
        assertFalse("attachments", exists(label(s(R.string.attachments))))
    }

    @Test fun ownScope_editsTheirOwnOnly_andAssignsOnlyThemselves() {
        val locked = Rest.createTask(Rest.unique("locked own"), listOf(Seed.own.id))
        Rest.updateTask(locked.getString("id")) { put("isLocked", true); put("isMilestone", false) }
        signIn(Seed.own)

        openTask(Seed.task("mine").title)
        waitExists(icon(s(R.string.edit)))
        assertTrue(exists(icon(s(R.string.more_actions))))
        backToPlanning()

        openTask(locked.getString("title"))
        waitText(s(R.string.a11y_locked))
        assertFalse("locked: no edit", exists(icon(s(R.string.edit))))
        assertFalse("locked: no delete", exists(icon(s(R.string.more_actions))))
        backToPlanning()

        showEveryone()
        openTask(Seed.task("someoneElse").title)
        waitExists(label(s(R.string.field_date)))
        assertFalse("someone else's: no edit", exists(icon(s(R.string.edit))))
        backToPlanning()

        openUsersPicker()
        waitExists(pickerItem(Seed.own.name))
        assertFalse(exists(label(Seed.own2.name)))
        assertFalse(exists(label(Seed.full.name)))
    }

    @Test fun teamScope_isLimitedToTheTeam() {
        signIn(Seed.team)
        showEveryone()
        openTask(Seed.task("outsideTeam").title)
        waitExists(label(s(R.string.field_date)))
        assertFalse(exists(icon(s(R.string.edit))))
        backToPlanning()

        openTask(Seed.task("teamTask").title)
        waitExists(icon(s(R.string.edit)))
        backToPlanning()

        openUsersPicker()
        waitExists(pickerItem(Seed.team.name))
        waitExists(pickerItem(Seed.teammate.name))
        assertFalse(exists(label(Seed.outsider.name)))
    }

    @Test fun full_locksAndEditsTheTasksOfOthers() {
        val task = Rest.createTask(Rest.unique("full edit"), listOf(Seed.own2.id))
        val newTitle = Rest.unique("full edited")
        signIn(Seed.full)
        waitExists(tab(R.string.tab_assistant))
        showEveryone()
        openTask(task.getString("title"))
        tap(icon(s(R.string.edit)))
        waitExists(field(s(R.string.field_title)))
        type(s(R.string.field_title), newTitle)
        waitRevealed(label(s(R.string.form_lock)))
        tap(clickable(s(R.string.form_lock), substring = true))
        tap(clickable(s(R.string.save)))
        waitForServer("saved") { Rest.task(task.getString("id"))?.optString("title") == newTitle }
        val saved = Rest.task(task.getString("id"))!!
        assertTrue("locked", saved.getBoolean("isLocked"))
        assertEquals(Seed.own2.id, saved.getJSONArray("users").getJSONObject(0).getString("id"))
    }

    @Test fun noAssistant_hasNoAssistantEntry() {
        signIn(Seed.noAssistant)
        goTo(R.string.tab_settings)
        waitText(s(R.string.settings_display).uppercase(appLocale))
        assertFalse(exists(tab(R.string.tab_assistant)))
    }
}
