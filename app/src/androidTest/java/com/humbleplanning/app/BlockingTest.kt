package com.humbleplanning.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.humbleplanning.app.support.Account
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

/**
 * Maintenance and the minimum app version, set through the REST API like the web settings do. A request of the app is
 * what reveals them. The other users of the instance would be blocked as well, so each test puts the setting back, even
 * when it fails.
 */
@RunWith(AndroidJUnit4::class)
class BlockingTest : UiTest() {
    /** A session that really is an app (version header): it says when the setting is in force. */
    private fun appStatus() = Rest.call("GET", "/api/profile", Rest.sessionOf(Seed.own2)).status

    private fun setMinimumVersion(value: String) {
        // Opened before: once blocked, this account could not sign in to tell
        Rest.sessionOf(Seed.own2)
        Rest.setConfig("MOBILE_MIN_APP_VERSION", value)
        waitForServer("minimum version applied") { (appStatus() == 426) == value.isNotEmpty() }
    }

    private fun setMaintenance(on: Boolean) {
        Rest.sessionOf(Seed.own2)
        Rest.setConfig("MAINTENANCE_MODE", on.toString())
        waitForServer("maintenance applied") { (appStatus() == 423) == on }
    }

    /** Signed in and silent: a request still on its way would answer between the block and what the test checks. */
    private fun signInQuietly(account: Account) {
        signIn(account)
        waitFor("offline copy written", LONG_TIMEOUT) { File(File(app.filesDir, "offline"), "tasks.bin").exists() }
        app.graph.repository.realtime.disconnect()
    }

    @Test fun tooOldApp_showsVersionsAndStore_cannotBeTappedThrough_retryComesBackUpToDate() {
        afterTest { Rest.setConfig("MOBILE_MIN_APP_VERSION", "") }
        val account = Rest.newUser("blockrefresh", "own")
        signInQuietly(account)
        // Where the tab is, before the screen covers it
        val settings = node(tab(R.string.tab_settings)).fetchSemanticsNode().boundsInRoot
        setMinimumVersion("9.0.0")
        // The planning itself gets the refusal
        pullToRefresh()
        waitText(s(R.string.update_text, AppInfo.VERSION, "9.0.0"), timeout = LONG_TIMEOUT)
        assertTrue(exists(clickable(s(R.string.update_open_store))))
        waitGone(tab(R.string.tab_planning))

        compose.onRoot().performTouchInput { click(Offset(settings.center.x, settings.center.y)) }
        compose.waitForIdle()

        // Still blocked while the server says so
        tap(clickable(s(R.string.retry)))
        waitExists(clickable(s(R.string.retry)))
        assertTrue(exists(label(s(R.string.update_title))))

        val during = Rest.createTask(Rest.unique("during block"), listOf(account.id), web = true)
        setMinimumVersion("")
        // Any answer of the server lifts it, even between these two lines; otherwise the button
        runCatching { node(clickable(s(R.string.retry))).performClick() }
        waitGone(label(s(R.string.update_text, AppInfo.VERSION, "9.0.0")), LONG_TIMEOUT)
        // Nothing left of the refusal, the news are there, and the tab under the screen was not used
        waitForCard(during.getString("title"), LONG_TIMEOUT)
        waitGone(label(s(R.string.update_title)))
        assertFalse(exists(label(s(R.string.settings_about).uppercase(appLocale))))
    }

    @Test fun blockedAtTheFirstSignIn_tooOldThenMaintenance_goesOnWithOneSession() {
        afterTest {
            Rest.setConfig("MOBILE_MIN_APP_VERSION", "")
            Rest.setConfig("MAINTENANCE_MODE", "false")
        }
        val account = Rest.newUser("blockedonce", "own")
        val other = Rest.login(account, deviceName = "other")
        setMinimumVersion("9.0.0")
        attemptSignIn(account.login, account.password)
        waitText(s(R.string.update_title), timeout = LONG_TIMEOUT)
        setMinimumVersion("")
        // No server saved yet on a new installation: the retry has to work anyway
        tap(clickable(s(R.string.retry)))
        waitGone(label(s(R.string.update_title)), LONG_TIMEOUT)
        expectLoginScreen()

        setMaintenance(true)
        submitLogin()
        waitText(s(R.string.maintenance_title), timeout = LONG_TIMEOUT)
        setMaintenance(false)
        tap(clickable(s(R.string.retry)))
        // The session opened before the block: sign-in goes on by itself, nothing left of the refusal
        expectMainScreen()
        assertEquals("this phone and the other device", 2, Rest.devices(other).size)
    }

    @Test fun maintenance_blocksThenComesBackByItself() {
        afterTest { Rest.setConfig("MAINTENANCE_MODE", "false") }
        signInQuietly(Seed.reader)
        setMaintenance(true)
        val message = Rest.call("GET", "/api/profile", Rest.sessionOf(Seed.own2)).error
        assertTrue(message.isNotBlank())
        runBlocking { runCatching { app.graph.repository.profile() } }
        waitText(s(R.string.maintenance_title), timeout = LONG_TIMEOUT)
        waitText(message)

        setMaintenance(false)
        // No gesture here: the automatic attempt, within its 30 s
        waitGone(label(s(R.string.maintenance_title)), 70_000)
        waitExists(tab(R.string.tab_planning))
    }

    @Test fun appWithdrawnFromTheRole_blocksAndOffersToSignOut() {
        val account = Rest.newUser("noapp", "own")
        signInQuietly(account)
        // The system role Membre does not have the mobile app permission
        Rest.updateUser(account.id) { put("roleId", "00000000-0000-0000-0000-000000000004") }
        pullToRefresh()
        waitText(s(R.string.not_allowed_title), timeout = LONG_TIMEOUT)
        waitGone(tab(R.string.tab_planning))
        tap(clickable(s(R.string.sign_out)))
        expectLoginScreen()
        // Signing in again is refused on the form, with the server's words
        attemptSignIn(account.login, account.password)
        waitText(s(R.string.not_allowed_text), timeout = LONG_TIMEOUT)
    }

    @Test fun afterTheBlock_settingsAreCleanToo() {
        afterTest { Rest.setConfig("MOBILE_MIN_APP_VERSION", "") }
        val account = Rest.newUser("blocksettings", "own")
        // Nothing else reads the server: settings are what gets refused
        signInQuietly(account)
        setMinimumVersion("9.0.0")
        goTo(R.string.tab_settings)
        waitText(s(R.string.update_title), timeout = LONG_TIMEOUT)
        setMinimumVersion("")
        tap(clickable(s(R.string.retry)))
        waitGone(label(s(R.string.update_text, AppInfo.VERSION, "9.0.0")), LONG_TIMEOUT)
        // The devices it could not read are there, without the refusal in their place
        waitRevealed(label(s(R.string.settings_devices).uppercase(appLocale)))
        waitFor("devices read", LONG_TIMEOUT) { texts().any { it.startsWith(s(R.string.devices_current)) } }
        assertFalse(exists(label(s(R.string.update_title))))
    }

    @Test fun afterTheBlock_aFormBeingFilledIsKept() {
        afterTest { Rest.setConfig("MOBILE_MIN_APP_VERSION", "") }
        val account = Rest.newUser("blockform", "own")
        val task = Rest.createTask(Rest.unique("block form"), listOf(account.id))
        val title = Rest.unique("block form renamed")
        signInQuietly(account)
        openTask(task.getString("title"))
        tap(icon(s(R.string.edit)))
        waitExists(field(s(R.string.field_title)))
        type(s(R.string.field_title), title)
        setMinimumVersion("9.0.0")
        tap(clickable(s(R.string.save)))
        waitText(s(R.string.update_text, AppInfo.VERSION, "9.0.0"), timeout = LONG_TIMEOUT)
        setMinimumVersion("")
        tap(clickable(s(R.string.retry)))
        waitGone(label(s(R.string.update_text, AppInfo.VERSION, "9.0.0")), LONG_TIMEOUT)
        waitText(title)
        assertFalse("nothing stale on the form", exists(label(s(R.string.update_title))))
        tap(clickable(s(R.string.save)))
        waitForServer("saved") { Rest.task(task.getString("id"))!!.getString("title") == title }
    }
}
