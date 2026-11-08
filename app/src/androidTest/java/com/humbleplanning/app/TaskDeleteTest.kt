package com.humbleplanning.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.humbleplanning.app.support.Rest
import com.humbleplanning.app.support.Seed
import com.humbleplanning.app.support.UiTest
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskDeleteTest : UiTest() {
    private val today: LocalDate get() = LocalDate.now()

    private fun openDelete(title: String) {
        openTask(title)
        tap(icon(s(R.string.more_actions)))
        tap(clickable(s(R.string.delete)))
    }

    private fun backOnThePlanning() = waitExists(clickable(s(R.string.today)), LONG_TIMEOUT)

    @Test fun plainTask_cancelKeepsIt_deleteRemovesIt() {
        val task = Rest.createTask(Rest.unique("del plain"), listOf(Seed.own.id))
        signIn(Seed.own)
        openDelete(task.getString("title"))
        waitText(s(R.string.delete_text))
        tap(clickable(s(R.string.cancel)))
        waitGone(label(s(R.string.delete_text)))
        assertNotNull(Rest.task(task.getString("id")))

        tap(icon(s(R.string.more_actions)))
        tap(clickable(s(R.string.delete)))
        tap(clickable(s(R.string.delete)) and isInDialog())
        backOnThePlanning()
        waitForServer("gone from the server") { Rest.task(task.getString("id")) == null }
        waitGone(card(task.getString("title")))
    }

    @Test fun series_occurrenceFollowingAndWhole() {
        val yesterday = today.minusDays(1).toString()
        // Yesterday to D+2: today is an occurrence with one before and two after
        val choices = listOf(
            R.string.delete_this_occurrence to listOf(yesterday, today.plusDays(1).toString(), today.plusDays(2).toString()),
            R.string.delete_this_and_following to listOf(yesterday),
            R.string.delete_whole_series to emptyList(),
        )
        signIn(Seed.own)
        for ((choice, left) in choices) {
            val title = Rest.unique("del series")
            val root = Rest.createSeries(title, listOf(Seed.own.id), today.minusDays(1), today.plusDays(2)).first().getString("id")
            openDelete(title)
            waitText(s(R.string.delete_series_title))
            tap(clickable(s(choice)))
            backOnThePlanning()
            waitForServer("left: $left") {
                Rest.tasksBetween(today.minusDays(1), today.plusDays(2)).filter { it.optString("parentId") == root }.map { it.getString("startDate") }.sorted() == left
            }
        }
    }

    @Test fun shared_removeOnePerson_orDeleteForEveryone() {
        val keep = Rest.createTask(Rest.unique("del shared one"), listOf(Seed.full.id, Seed.own2.id))
        val gone = Rest.createTask(Rest.unique("del shared all"), listOf(Seed.full.id, Seed.own2.id))
        signIn(Seed.full)
        openDelete(keep.getString("title"))
        waitText(s(R.string.delete_shared_title))
        tap(clickable(s(R.string.delete_remove_user)))
        tap(clickable(Seed.own2.name) and isInDialog())
        waitForServer("removed") { Rest.task(keep.getString("id"))!!.getJSONArray("users").length() == 1 }
        val users = Rest.task(keep.getString("id"))!!.getJSONArray("users")
        assertEquals(Seed.full.id, users.getJSONObject(0).getString("id"))
        waitGone(label(Seed.own2.name))
        assertTrue("the task stays open", exists(icon(s(R.string.edit))))
        tap(icon(s(R.string.back)))
        backOnThePlanning()

        openDelete(gone.getString("title"))
        waitText(s(R.string.delete_shared_title))
        tap(clickable(s(R.string.delete_for_everyone)))
        backOnThePlanning()
        waitForServer("gone for everyone") { Rest.task(gone.getString("id")) == null }
    }
}
