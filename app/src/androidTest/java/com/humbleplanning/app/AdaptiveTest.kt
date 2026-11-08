package com.humbleplanning.app

import android.content.res.Resources
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.humbleplanning.app.support.Rest
import com.humbleplanning.app.support.Seed
import com.humbleplanning.app.support.UiTest
import com.humbleplanning.app.ui.longLabel
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Tablet size, large font, and what screen readers get. The phone is put back after each test. */
@RunWith(AndroidJUnit4::class)
class AdaptiveTest : UiTest() {
    /** After the test, without a composition to wait on. */
    private fun waitPlainly(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + LONG_TIMEOUT
        while (!condition() && SystemClock.uptimeMillis() < end) SystemClock.sleep(100)
    }

    private fun tabBounds(name: Int) = node(tab(name)).fetchSemanticsNode().boundsInRoot

    @Test fun tablet_hasTheRail_phoneTheBottomBar() {
        signIn(Seed.full)
        assertEquals("phone: tabs on one line", tabBounds(R.string.tab_planning).top, tabBounds(R.string.tab_settings).top, 1f)

        val width = Resources.getSystem().displayMetrics.widthPixels
        afterTest {
            shell("wm size reset")
            waitPlainly { Resources.getSystem().displayMetrics.widthPixels == width }
        }
        shell("wm size 1600x2560")
        waitFor("rail", LONG_TIMEOUT) {
            val planning = tabBounds(R.string.tab_planning)
            val settings = tabBounds(R.string.tab_settings)
            kotlin.math.abs(planning.left - settings.left) < 2f && settings.top > planning.top
        }
        openTask(Seed.task("fullMine").title)
        tap(icon(s(R.string.edit)))
        waitExists(clickable(s(R.string.save)))
        assertTrue(displayed(clickable(s(R.string.save))))
    }

    /** Lines of a text as laid out: a word cut in two takes two. */
    private fun linesOf(text: String): Int {
        val results = mutableListOf<TextLayoutResult>()
        val node = compose.onAllNodes(label(text), useUnmergedTree = true).fetchSemanticsNodes().first { it.config.contains(SemanticsActions.GetTextLayoutResult) }
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first().lineCount
    }

    @Test fun largeFont_keepsTheActionsAndWholeWords() {
        afterTest {
            shell("settings put system font_scale 1.0")
            waitPlainly { Resources.getSystem().configuration.fontScale == 1f }
        }
        shell("settings put system font_scale 1.5")
        waitFor("font scale applied", LONG_TIMEOUT) {
            Resources.getSystem().configuration.fontScale == 1.5f && exists(field(s(R.string.login_server)))
        }
        compose.waitForIdle()
        waitRevealed(clickable(s(R.string.sign_in)))
        signIn(Seed.own)
        assertTrue(displayed(icon(s(R.string.new_task))))
        assertTrue(displayed(icon(s(R.string.filters))))
        for (name in listOf(R.string.tab_planning, R.string.tab_upcoming, R.string.tab_alerts, R.string.tab_settings)) {
            assertTrue(displayed(tab(name)))
        }
        openTask(Seed.task("mine").title)
        assertTrue(displayed(icon(s(R.string.edit))))
        assertTrue(displayed(icon(s(R.string.more_actions))))
        tap(icon(s(R.string.edit)))
        waitExists(clickable(s(R.string.save)))
        assertTrue(displayed(clickable(s(R.string.save))))
        waitExists(clickable(s(R.string.afternoon)))
        for (word in listOf(R.string.morning, R.string.afternoon, R.string.form_times)) {
            assertEquals(s(word), 1, linesOf(s(word)))
        }
    }

    private fun SemanticsNode.spoken(): String = listOfNotNull(
        config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
        config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
        config.getOrNull(SemanticsProperties.EditableText)?.text,
        config.getOrNull(SemanticsProperties.StateDescription),
    ).joinToString(" ").trim()

    private fun checkScreen(name: String, problems: MutableList<String>) {
        compose.waitForIdle()
        val density = compose.density.density
        compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
            if (node.spoken().isBlank()) problems += "$name: action without a name: ${node.boundsInRoot} role=${node.config.getOrNull(SemanticsProperties.Role)}"
            val smallest = minOf(node.touchBoundsInRoot.width, node.touchBoundsInRoot.height) / density
            if (smallest < 40f && node.boundsInRoot.width > 0f) problems += "$name: target too small: ${node.spoken()} $smallest dp"
        }
    }

    @Test fun everyActionHasANameAndAUsableSize() {
        val problems = mutableListOf<String>()
        checkScreen("sign-in", problems)

        val task = Rest.createTask(Rest.unique("a11y task"), listOf(Seed.full.id)) { put("notes", "Notes") }
        signIn(Seed.full)
        waitForCard(task.getString("title"))
        checkScreen("planning day", problems)
        tap(clickable(s(R.string.view_week)))
        checkScreen("planning week", problems)
        tap(clickable(s(R.string.view_month)))
        waitExists(desc(LocalDate.now().longLabel(appLocale), substring = true))
        checkScreen("planning month", problems)
        tap(clickable(s(R.string.view_day)))

        tap(icon(s(R.string.filters)))
        waitExists(clickable(s(R.string.apply)))
        checkScreen("filters", problems)
        tap(clickable(s(R.string.apply)))

        goTo(R.string.tab_upcoming)
        waitText(s(R.string.upcoming_title))
        checkScreen("upcoming", problems)
        goTo(R.string.tab_alerts)
        waitExists(label(s(R.string.tab_alerts)))
        checkScreen("alerts", problems)
        goTo(R.string.tab_assistant)
        waitExists(icon(s(R.string.assistant_new)))
        checkScreen("assistant", problems)
        tap(icon(s(R.string.assistant_new)))
        waitExists(field(s(R.string.assistant_placeholder)))
        checkScreen("conversation", problems)
        tap(icon(s(R.string.back)))
        goTo(R.string.tab_settings)
        waitText(Seed.full.name)
        checkScreen("settings", problems)
        tap(icon(s(R.string.profile_edit)))
        waitText(s(R.string.profile_title))
        checkScreen("profile", problems)
        pressBack()

        goTo(R.string.tab_planning)
        openTask(task.getString("title"))
        checkScreen("task detail", problems)
        tap(icon(s(R.string.edit)))
        waitExists(clickable(s(R.string.save)))
        checkScreen("task form", problems)
        tap(clickable(s(R.string.field_project) + " *", substring = true))
        waitFor("choice list") { exists(searchField()) || exists(hasClickAction() and label(Seed.project.name)) }
        checkScreen("choice list", problems)

        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
