package com.humbleplanning.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.humbleplanning.app.support.Rest
import com.humbleplanning.app.support.Seed
import com.humbleplanning.app.support.UiTest
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionTest : UiTest() {
    private fun waitOfflineCopy() = waitFor("offline copy written", LONG_TIMEOUT) { File(File(app.filesDir, "offline"), "tasks.bin").exists() }

    private fun offlineBanner() = label(s(R.string.offline_since, "").substringBefore(":"), substring = true)

    @Test fun revokedDevice_goesToSignInWithTheNotice_whichALateRefusalKeeps() {
        val account = Rest.newUser("revoked", "own")
        val other = Rest.login(account, deviceName = "other")
        signIn(account)
        // Task card: real time connected and reading
        val probe = Rest.createTask(Rest.unique("before revoke"), listOf(account.id))
        waitForCard(probe.getString("title"))

        val mine = Rest.devices(other).first { !it.getBoolean("current") }.getString("id")
        assertTrue(Rest.call("DELETE", "/api/mobile/devices/$mine", other).ok)
        // No gesture: told by the server
        waitText(s(R.string.err_session), timeout = LONG_TIMEOUT)
        expectLoginScreen()
        assertFalse(app.graph.repository.isLoggedIn)

        // A request still on its way when that happened
        val repository = app.graph.repository
        runBlocking { runCatching { repository.api().profile() } }
        assertFalse("a late refusal does not sign out a second time", repository.sessionExpired.value)
        compose.waitForIdle()
        assertTrue("the notice stays", exists(label(s(R.string.err_session))))
    }

    @Test fun signOut_closesTheSessionAndWipes_andTheNextUserInheritsNothing() {
        val first = Rest.newUser("first", "own")
        val second = Rest.newUser("second", "own")
        val other = Rest.login(first, deviceName = "other")
        val firstTask = Rest.createTask(Rest.unique("first only"), listOf(first.id))
        val secondTask = Rest.createTask(Rest.unique("second task"), listOf(second.id))
        signIn(first)
        waitForCard(firstTask.getString("title"))
        tap(icon(s(R.string.filters)))
        tap(clickable(s(R.string.filter_all_projects)))
        waitExists(clickable(s(R.string.ok)))
        pick(Seed.project2.name)
        tap(clickable(s(R.string.ok)))
        tap(clickable(s(R.string.apply)))
        waitText("1")
        val offline = File(app.filesDir, "offline")
        waitOfflineCopy()

        goTo(R.string.tab_settings)
        waitRevealed(clickable(s(R.string.sign_out)))
        tap(clickable(s(R.string.sign_out)))
        waitText(s(R.string.sign_out_confirm))
        tap(clickable(s(R.string.cancel)) and isInDialog())
        waitGone(label(s(R.string.sign_out_confirm)))
        assertTrue("cancelled", app.graph.repository.isLoggedIn)

        signOutFromSettings()
        waitForServer("session closed on the server") { Rest.devices(other).size == 1 }
        waitFor("offline copy removed") { offline.listFiles().orEmpty().none { it.name == "tasks.bin" || it.name == "references.bin" } }

        signIn(second)
        waitForCard(secondTask.getString("title"))
        assertFalse("no filter left from the previous account", exists(label("1")))
        assertFalse(exists(card(firstTask.getString("title"))))
        goTo(R.string.tab_settings)
        waitText(second.name)
        assertFalse(exists(label(first.name)))
    }

    @Test fun offline_showsTheBannerAndMyCopy_andNetworkBackRefreshes() {
        val account = Rest.newUser("offline", "own")
        val mine = Rest.createTask(Rest.unique("offline mine"), listOf(account.id))
        val other = Rest.createTask(Rest.unique("offline other"), listOf(Seed.own2.id))
        signIn(account)
        waitForCard(mine.getString("title"))
        waitOfflineCopy()

        afterTest { setNetwork(true) }
        setNetwork(false)
        waitExists(offlineBanner(), LONG_TIMEOUT)
        waitForCard(mine.getString("title"))
        // Read again from the offline copy
        tap(clickable(s(R.string.view_month)))
        tap(clickable(s(R.string.view_day)))
        waitForCard(mine.getString("title"))
        assertFalse(exists(card(other.getString("title"))))

        setNetwork(true)
        waitGone(offlineBanner(), LONG_TIMEOUT)
        val added = Rest.unique("offline added")
        Rest.createTask(added, listOf(account.id))
        waitForCard(added, LONG_TIMEOUT)
        assertEquals(account.id, app.graph.repository.userId)
    }

    @Test fun offline_theFormDoesNotOpenFromTheCopy() {
        val account = Rest.newUser("offlineform", "own")
        val mine = Rest.createTask(Rest.unique("offline form"), listOf(account.id)) { put("link", "https://example.com/kept") }
        signIn(account)
        waitForCard(mine.getString("title"))
        waitOfflineCopy()
        afterTest { setNetwork(true) }
        setNetwork(false)
        // In the offline copy: the task still opens
        openTask(mine.getString("title"))
        waitExists(label(s(R.string.field_date)))
        tap(icon(s(R.string.edit)))
        // The copy holds what a card shows, not all the task: saving from it would erase the rest
        waitText(s(R.string.err_network), timeout = LONG_TIMEOUT)
        assertFalse(exists(field(s(R.string.field_title))))
    }
}
