package com.humbleplanning.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.humbleplanning.app.support.Rest
import com.humbleplanning.app.support.Seed
import com.humbleplanning.app.support.UiTest
import com.humbleplanning.app.ui.longLabel
import com.humbleplanning.app.ui.monday
import java.time.LocalDate
import java.time.temporal.IsoFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanningTest : UiTest() {
    private val today: LocalDate get() = LocalDate.now()

    private fun heading(date: LocalDate) = label(date.longLabel(appLocale))

    private fun openFilters() {
        tap(icon(s(R.string.filters)))
        waitExists(clickable(s(R.string.apply)))
    }

    private fun apply() {
        tap(clickable(s(R.string.apply)))
        waitGone(clickable(s(R.string.apply)))
    }

    private fun resetFilters() {
        openFilters()
        tap(clickable(s(R.string.reset)))
        apply()
    }

    private fun pickInFilter(section: Int, name: String) {
        openFilters()
        tap(clickable(s(section)))
        waitExists(clickable(s(R.string.ok)))
        pick(name)
        tap(clickable(s(R.string.ok)))
        apply()
    }

    @Test fun views_dayWeekMonth() {
        val day = if (today.dayOfMonth == 20) today.withDayOfMonth(21) else today.withDayOfMonth(20)
        val task = Rest.createTask(Rest.unique("month day"), listOf(Seed.own.id), day)
        signIn(Seed.own)
        waitDisplayed(heading(today))
        waitForCard(Seed.task("mine").title)

        tap(clickable(s(R.string.view_week)))
        val monday = today.monday()
        waitText(s(R.string.week_of, monday.longLabel(appLocale, false), monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)))
        waitForCard(Seed.task("mine").title)

        tap(clickable(s(R.string.view_month)))
        tap(desc(day.longLabel(appLocale), substring = true))
        waitDisplayed(heading(day))
        waitForCard(task.getString("title"))
    }

    @Test fun moving_swipeTodayAndDatePicker() {
        signIn(Seed.reader)
        waitDisplayed(heading(today))
        reveal(heading(today)).performTouchInput { swipeLeft() }
        waitDisplayed(heading(today.plusDays(1)))
        tap(clickable(s(R.string.today)))
        waitDisplayed(heading(today))
        // Far enough to hold nothing
        val empty = today.plusDays(150)
        pickDate(empty)
        waitDisplayed(heading(empty))
        waitDisplayed(label(s(R.string.nothing_planned)))
        tap(clickable(s(R.string.today)))
        waitDisplayed(heading(today))
    }

    @Test fun filters_meEveryoneAndPick() {
        signIn(Seed.own)
        waitForCard(Seed.task("mine").title)
        assertFalse(exists(card(Seed.task("someoneElse").title)))

        showEveryone()
        waitForCard(Seed.task("someoneElse").title)
        waitForCard(Seed.task("mine").title)

        openFilters()
        tap(clickable(s(R.string.filter_pick)))
        // The list opens with me ticked: swap for someone else
        waitExists(clickable(s(R.string.ok)))
        pick(Seed.own2.name)
        pick(Seed.own.name)
        tap(clickable(s(R.string.ok)))
        waitText(plural(R.plurals.n_people, 1))
        apply()
        waitForCard(Seed.task("someoneElse").title)
        assertFalse(exists(card(Seed.task("mine").title)))
    }

    @Test fun filters_teamProjectAndStatus() {
        signIn(Seed.own)
        showEveryone()
        pickInFilter(R.string.filter_all_teams, Seed.teamA.name)
        waitForCard(Seed.task("teamTask").title)
        assertFalse(exists(card(Seed.task("outsideTeam").title)))
        resetFilters()

        pickInFilter(R.string.filter_all_projects, Seed.project2.name)
        waitForCard(Seed.task("otherProject").title)
        assertFalse(exists(card(Seed.task("mine").title)))
        waitText("1")
        resetFilters()

        pickInFilter(R.string.filter_all_statuses, Seed.styledStatus.name)
        waitForCard(Seed.task("styled").title)
        assertFalse(exists(card(Seed.task("mine").title)))
    }

    private fun textStyleOf(text: String): TextStyle {
        val results = mutableListOf<TextLayoutResult>()
        val node = compose.onAllNodes(label(text), useUnmergedTree = true).fetchSemanticsNodes().first { it.config.contains(SemanticsActions.GetTextLayoutResult) }
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first().layoutInput.style
    }

    @Test fun content_statusStyleMultiDayAndHoliday() {
        signIn(Seed.own)
        waitForCard(Seed.task("styled").title)
        val style = textStyleOf(Seed.task("styled").title)
        assertEquals(FontWeight.Bold, style.fontWeight)
        assertTrue("struck through", style.textDecoration?.contains(TextDecoration.LineThrough) == true)

        waitForCard(Seed.task("multiDay").title)
        assertTrue(exists(label(s(R.string.all_day) + " · " + s(R.string.day_x_of_y, 1, 3))))
        waitRevealed(heading(today))
        reveal(heading(today)).performTouchInput { swipeLeft() }
        waitDisplayed(heading(today.plusDays(1)))
        waitDisplayed(label(s(R.string.all_day) + " · " + s(R.string.day_x_of_y, 2, 3)))

        val holiday = LocalDate.parse(Seed.holidayDate)
        pickDate(holiday)
        waitDisplayed(heading(holiday))
        waitDisplayed(label(Seed.holidayLabel))
    }

    @Test fun pullToRefresh_readsTheServer() {
        // Few tasks: the gesture must start from the top of the list
        val account = Rest.newUser("pull", "own")
        signIn(account)
        // Real time up and its first reload done, then cut: only the gesture can bring the next one
        val probe = Rest.createTask(Rest.unique("pull probe"), listOf(account.id))
        waitForCard(probe.getString("title"))
        app.graph.repository.realtime.disconnect()
        val task = Rest.createTask(Rest.unique("pulled"), listOf(account.id))
        staysAbsent(card(task.getString("title")), 1_500)
        pullToRefresh()
        waitForCard(task.getString("title"))
    }

    private fun dayHeader(date: LocalDate) = desc(date.longLabel(appLocale), substring = true) and !hasClickAction()

    private fun bar(title: String) = hasClickAction() and desc(title, substring = true)

    /** Taps the grid where the row of [person] meets the column of [date]. */
    private fun tapGrid(person: String, date: LocalDate) {
        val column = compose.onNode(dayHeader(date), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val row = compose.onNode(label(person), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { click(Offset(column.center.x, row.center.y)) }
    }

    @Test fun grid_openCreateFromARowAndRowsOfOthersStayClosed() {
        val title = Rest.unique("grid cell")
        signIn(Seed.own)
        tap(clickable(s(R.string.view_grid)))
        // The person filter starts on "Me": only my row
        waitExists(clickable(s(R.string.grid_show_everyone)))
        waitExists(bar(Seed.task("mine").title))
        assertFalse(exists(bar(Seed.task("someoneElse").title)))
        tap(bar(Seed.task("mine").title))
        waitExists(icon(s(R.string.back)))
        pressBack()

        // An empty day of my row: the form opens on that day, for me
        val day = listOf(3L, -1L, -2L).map { today.plusDays(it) }.first { exists(dayHeader(it)) && displayed(dayHeader(it)) }
        tapGrid(Seed.own.name, day)
        waitExists(clickable(s(R.string.save)))
        type(s(R.string.field_title), title)
        openChoice(R.string.field_project)
        pick(Seed.project.name)
        tap(clickable(s(R.string.save)))
        waitExists(bar(title))
        val created = Rest.taskByTitle(title, day)!!
        assertEquals(day.toString(), created.getString("startDate"))
        assertEquals(Seed.own.id, created.getJSONArray("users").getJSONObject(0).getString("id"))

        // Own scope: the row of someone else opens nothing
        openFilters()
        tap(clickable(s(R.string.filter_pick)))
        waitExists(clickable(s(R.string.ok)))
        pick(Seed.own2.name)
        pick(Seed.own.name)
        tap(clickable(s(R.string.ok)))
        apply()
        waitExists(bar(Seed.task("someoneElse").title))
        tapGrid(Seed.own2.name, day)
        staysAbsent(clickable(s(R.string.save)), 1_500)
    }

    @Test fun upcoming_listsMyNextTasks() {
        signIn(Seed.own)
        goTo(R.string.tab_upcoming)
        waitText(s(R.string.upcoming_title))
        waitForCard(Seed.task("mine").title)
        assertFalse(exists(card(Seed.task("someoneElse").title)))
        waitForCard(Seed.task("recurring").title)
    }
}
