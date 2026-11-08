package com.humbleplanning.app

import androidx.compose.ui.test.isEnabled
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.humbleplanning.app.alerts.AlertSync
import com.humbleplanning.app.support.Account
import com.humbleplanning.app.support.Rest
import com.humbleplanning.app.support.UiTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlertsTest : UiTest() {
    private val account: Account by lazy { Rest.newUser("alerts", "own") }

    private fun alertsSwitch() = clickable(s(R.string.alerts_on_phone), substring = true)

    /** Reads the server's alert about this task: its text is what the app must show. */
    private fun waitServerAlert(taskId: String): String {
        var title = ""
        waitForServer("alert on the server") {
            val alert = Rest.alerts(Rest.sessionOf(account)).firstOrNull { it.optString("actionUrl").contains(taskId) }
            title = alert?.optString("title").orEmpty()
            alert != null
        }
        return title
    }

    @Test fun alertFromSomeoneElse_appearsLive_andOpensTheTask() {
        signIn(account)
        goTo(R.string.tab_alerts)
        waitText(s(R.string.alerts_empty))
        val task = Rest.createTask(Rest.unique("alert live"), listOf(account.id))
        val title = waitServerAlert(task.getString("id"))
        tap(clickable(title, substring = true), REALTIME)
        waitText(task.getString("title"))
        waitExists(icon(s(R.string.back)))
    }

    @Test fun turningAlertsOff_isSavedAndStopsThem() {
        signIn(account)
        goTo(R.string.tab_settings)
        waitRevealed(alertsSwitch())
        tap(alertsSwitch())
        val session = Rest.sessionOf(account)
        waitForServer("preference saved") { !Rest.profile(session).getBoolean("mobilePushNotificationsUser") }

        val silent = Rest.createTask(Rest.unique("alert silent"), listOf(account.id))
        goTo(R.string.tab_alerts)
        waitText(s(R.string.alerts_empty))
        staysAbsent(label(silent.getString("title"), substring = true), 2_000)
        assertEquals(0, Rest.alerts(session).count { it.optString("actionUrl").contains(silent.getString("id")) })
    }

    @Test fun adminAndInstanceSwitches_showInSettings() {
        afterTest { Rest.setConfig("MOBILE_PUSH_ENABLED", "true") }
        Rest.updateUser(account.id) { put("mobilePushNotifications", false) }
        signIn(account)
        goTo(R.string.tab_settings)
        waitRevealed(label(s(R.string.alerts_off_by_admin)))
        assertFalse("the switch cannot be used", exists(alertsSwitch() and isEnabled()))
        signOutFromSettings()

        Rest.setConfig("MOBILE_PUSH_ENABLED", "false")
        waitForServer("setting applied") { !Rest.mobileInfo().getBoolean("alertsEnabled") }
        signIn(account)
        goTo(R.string.tab_settings)
        waitRevealed(clickable(s(R.string.sign_out)))
        assertFalse(exists(label(s(R.string.alerts_on_phone))))
    }

    @Test fun inTheBackground_theSyncWorkerPostsANotificationThatOpensTheTask() {
        grantNotifications()
        signIn(account)
        device.pressHome()
        // Stopped: the socket is closed and only the worker can read the alert
        waitFor("app in the background") { !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        val task = Rest.createTask(Rest.unique("alert background"), listOf(account.id))
        val title = waitServerAlert(task.getString("id"))

        AlertSync.runNow(app)
        waitNotification()

        device.openNotification()
        checkNotNull(device.wait(Until.findObject(By.text(title)), 15_000)) { "notification \"$title\" not in the shade" }.click()
        waitText(task.getString("title"), timeout = LONG_TIMEOUT)
        waitExists(icon(s(R.string.back)))
    }

    private companion object {
        const val REALTIME = 10_000L
    }
}
