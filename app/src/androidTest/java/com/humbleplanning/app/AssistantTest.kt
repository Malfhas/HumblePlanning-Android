package com.humbleplanning.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.humbleplanning.app.support.Account
import com.humbleplanning.app.support.Rest
import com.humbleplanning.app.support.Seed
import com.humbleplanning.app.support.UiTest
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Against the scripted model of the test server: "crée une tâche :: {json}" asks for a write, anything else is echoed. */
@RunWith(AndroidJUnit4::class)
class AssistantTest : UiTest() {
    private val account: Account by lazy { Rest.newUser("assistant", "full") }

    private fun openAssistant() {
        signIn(account)
        goTo(R.string.tab_assistant)
        waitExists(icon(s(R.string.assistant_new)))
    }

    private fun newConversation() {
        tap(icon(s(R.string.assistant_new)))
        waitExists(field(s(R.string.assistant_placeholder)))
    }

    private fun send(text: String) {
        type(s(R.string.assistant_placeholder), text)
        tap(icon(s(R.string.assistant_send)))
    }

    /** The mock answers with this, then the end of the message it received. */
    private val answerStart = "Réponse de test"

    private fun waitAnswer() = waitExists(label(answerStart, substring = true), LONG_TIMEOUT)

    /** Message that makes the mock ask for a write, valid for this instance. */
    private fun writeRequest(title: String, date: LocalDate): String {
        val input = JSONObject()
            .put("projectId", Seed.project.id)
            .put("userIds", JSONArray(listOf(account.id)))
            .put("statusId", Seed.defaultStatus.id)
            .put("startDate", date.toString())
            .put("title", title)
        return "crée une tâche :: $input"
    }

    private fun waitApproval() {
        waitExists(clickable(s(R.string.assistant_approve)), LONG_TIMEOUT)
        waitText(s(R.string.assistant_confirm_title))
    }

    @Test fun conversation_answerHistoryAndDelete() {
        openAssistant()
        waitText(s(R.string.assistant_empty))
        newConversation()
        send("message to remember")
        waitAnswer()
        waitExists(icon(s(R.string.assistant_send)))
        tap(icon(s(R.string.back)))
        waitExists(icon(s(R.string.assistant_new)))

        // The list shows it, and it opens with what was said
        val session = Rest.sessionOf(account)
        val title = Rest.conversations(session).first().getString("title")
        tap(clickable(title, substring = true))
        waitText("message to remember", timeout = LONG_TIMEOUT)
        waitAnswer()
        tap(icon(s(R.string.back)))
        waitExists(icon(s(R.string.assistant_new)))

        tap(icon(s(R.string.delete)))
        waitText(s(R.string.assistant_delete_title))
        tap(clickable(s(R.string.delete)) and isInDialog())
        waitForServer("conversation removed") { Rest.conversations(session).isEmpty() }
        waitText(s(R.string.assistant_empty))
    }

    @Test fun approval_refuseLeavesNothing_approveCreatesTheTask() {
        val date = LocalDate.now().plusDays(70)
        val refused = Rest.unique("ai refused")
        val approved = Rest.unique("ai approved")
        openAssistant()
        newConversation()
        send(writeRequest(refused, date))
        waitApproval()
        assertTrue("the write is described", exists(label(refused, substring = true)))
        assertNull("nothing before the decision", Rest.taskByTitle(refused, date))
        tap(clickable(s(R.string.assistant_refuse)))
        waitGone(clickable(s(R.string.assistant_approve)), LONG_TIMEOUT)
        waitExists(icon(s(R.string.assistant_send)), LONG_TIMEOUT)
        assertNull("refused: nothing created", Rest.taskByTitle(refused, date))

        tap(icon(s(R.string.back)))
        newConversation()
        send(writeRequest(approved, date))
        waitApproval()
        tap(clickable(s(R.string.assistant_approve)))
        waitForServer("task created by the assistant", LONG_TIMEOUT) { Rest.taskByTitle(approved, date) != null }
        assertEquals(Seed.project.id, Rest.taskByTitle(approved, date)!!.getString("projectId"))
    }

    @Test fun quota_gaugeThenReachedClosesTheInput() {
        val before = Rest.assistantQuotaSettings()
        // The scripted model charges 1 $ for a "costly" question: one reply uses up a quota of one cent
        Rest.setAssistantQuota(enabled = true, amountUsd = 0.01, resetDay = 1)
        try {
            openAssistant()
            waitExists(desc(s(R.string.assistant_quota_used, 0), substring = true), LONG_TIMEOUT)
            newConversation()
            send("one costly question")
            waitAnswer()
            // The gauge follows the reply; the input closes and says until when, the conversation stays readable
            waitExists(desc(s(R.string.assistant_quota_used, 100), substring = true), LONG_TIMEOUT)
            waitText(s(R.string.assistant_quota_reached, "").substringBefore('.'), substring = true)
            waitGone(field(s(R.string.assistant_placeholder)))
            assertTrue("the answer stays", exists(label(answerStart, substring = true)))
        } finally {
            Rest.setAssistantQuota(before.getBoolean("enabled"), before.getDouble("amountUsd"), before.getInt("resetDay"))
        }
    }

    @Test fun permissionLost_hidesTheEntryInRealTime() {
        openAssistant()
        Rest.updateUser(account.id) { put("roleId", Seed.roleId("noAssistant")) }
        waitGone(tab(R.string.tab_assistant), LONG_TIMEOUT)
        // Back on a tab that exists
        waitExists(icon(s(R.string.new_task)))
    }
}
