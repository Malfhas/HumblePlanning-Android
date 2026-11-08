package com.humbleplanning.app

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.humbleplanning.app.support.Account
import com.humbleplanning.app.support.Rest
import com.humbleplanning.app.support.UiTest
import org.junit.Test
import org.junit.runner.RunWith

/** Changes made elsewhere reach the screen by themselves: no gesture from here on in these tests. */
@RunWith(AndroidJUnit4::class)
class RealtimeTest : UiTest() {
    private lateinit var account: Account

    /** Real time connected: an event it already received cannot be mistaken for the one under test. */
    private fun signInAndWaitForSocket() {
        account = Rest.newUser("realtime", "own")
        signIn(account)
        val probe = Rest.createTask(Rest.unique("socket probe"), listOf(account.id))
        waitForCard(probe.getString("title"))
    }

    @Test fun planningFollowsCreateEditDelete() {
        signInAndWaitForSocket()
        val task = Rest.createTask(Rest.unique("live row"), listOf(account.id))
        waitForCard(task.getString("title"), REALTIME)
        val edited = Rest.unique("live row edited")
        Rest.updateTask(task.getString("id")) { put("title", edited); put("isMilestone", false) }
        waitForCard(edited, REALTIME)
        waitGone(card(task.getString("title")), REALTIME)
        Rest.deleteTask(task.getString("id"))
        waitGone(card(edited), REALTIME)
    }

    @Test fun openTaskFollowsTheChanges_andSaysWhenDeleted() {
        signInAndWaitForSocket()
        val task = Rest.createTask(Rest.unique("live detail"), listOf(account.id))
        openTask(task.getString("title"))
        val title = Rest.unique("live detail edited")
        Rest.updateTask(task.getString("id")) { put("title", title); put("isMilestone", false) }
        waitText(title, timeout = REALTIME)
        Rest.deleteTask(task.getString("id"))
        waitText(s(R.string.err_task_not_found), timeout = REALTIME)
    }

    @Test fun changesMadeWhileInTheBackgroundAreThereOnReturn() {
        signInAndWaitForSocket()
        device.pressHome()
        waitFor("app in the background") { !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        val title = Rest.unique("live away")
        Rest.createTask(title, listOf(account.id))
        instrumentation.targetContext.startActivity(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        waitForCard(title, REALTIME)
    }

    private companion object {
        const val REALTIME = 10_000L
    }
}
