package com.humbleplanning.app.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyBuilderTest {
    private fun event(type: String, vararg fields: Pair<String, String>) = buildJsonObject {
        put("type", type)
        fields.forEach { (k, v) -> put(k, v) }
    }

    @Test fun textDeltasAreStreamedThenGathered() {
        val builder = ReplyBuilder()
        assertEquals(ChatEvent.Text("Hello "), builder.accept(event("text-delta", "delta" to "Hello ")))
        assertEquals(ChatEvent.Text("world"), builder.accept(event("text-delta", "delta" to "world")))
        val part = builder.parts().single()
        assertEquals("text", part["type"]?.jsonPrimitive?.content)
        assertEquals("Hello world", part["text"]?.jsonPrimitive?.content)
    }

    @Test fun unknownEventsAreIgnored() {
        assertNull(ReplyBuilder().accept(event("start-step")))
    }

    @Test fun nothingReceivedMeansNoParts() {
        assertTrue(ReplyBuilder().parts().isEmpty())
    }

    @Test fun aToolIsAnnouncedThenCompleted() {
        val builder = ReplyBuilder()
        assertEquals(ChatEvent.Tool("list_tasks"), builder.accept(event("tool-input-available", "toolCallId" to "c1", "toolName" to "list_tasks")))
        assertNull(builder.accept(buildJsonObject {
            put("type", "tool-output-available")
            put("toolCallId", "c1")
            put("output", buildJsonObject { put("count", 3) })
        }))
        val part = builder.parts().single()
        assertEquals("tool-list_tasks", part["type"]?.jsonPrimitive?.content)
        assertEquals("output-available", part["state"]?.jsonPrimitive?.content)
        assertEquals("3", part["output"]?.jsonObject?.get("count")?.jsonPrimitive?.content)
    }

    @Test fun anApprovalCarriesTheToolAndItsInput() {
        val builder = ReplyBuilder()
        builder.accept(buildJsonObject {
            put("type", "tool-input-available")
            put("toolCallId", "c1")
            put("toolName", "create_task")
            put("input", buildJsonObject { put("title", "Visit") })
        })
        val approval = (builder.accept(event("tool-approval-request", "toolCallId" to "c1", "approvalId" to "a1")) as ChatEvent.Approval).approval
        assertEquals("a1", approval.approvalId)
        assertEquals("c1", approval.toolCallId)
        assertEquals("create_task", approval.toolName)
        assertEquals("Visit", approval.input?.jsonObject?.get("title")?.jsonPrimitive?.content)
        assertEquals("approval-requested", builder.parts().single()["state"]?.jsonPrimitive?.content)
    }

    @Test fun anApprovalOfAnUnknownToolIsIgnored() {
        assertNull(ReplyBuilder().accept(event("tool-approval-request", "toolCallId" to "nope", "approvalId" to "a1")))
    }

    @Test fun anErrorEventFails() {
        assertEquals(ChatEvent.Failed("quota reached"), ReplyBuilder().accept(event("error", "errorText" to "quota reached")))
    }

    @Test fun aDecisionAnswersOnlyItsToolCall() {
        val approval = PendingApproval("a1", "c1", "create_task", null)
        val other = PendingApproval("a2", "c2", "create_task", null)
        val line = ChatLine(
            "l", false, "",
            approvals = listOf(approval, other),
            parts = listOf(
                buildJsonObject { put("toolCallId", "c1"); put("state", "approval-requested") },
                buildJsonObject { put("toolCallId", "c2"); put("state", "approval-requested") },
            ),
        )
        val answered = AssistantStream.withDecision(line, approval, approved = true)
        assertEquals(listOf(other), answered.approvals)
        assertEquals("approval-responded", answered.parts[0]["state"]?.jsonPrimitive?.content)
        assertEquals("a1", answered.parts[0]["approval"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
        assertEquals("true", answered.parts[0]["approval"]?.jsonObject?.get("approved")?.jsonPrimitive?.content)
        assertEquals("approval-requested", answered.parts[1]["state"]?.jsonPrimitive?.content)
    }

    @Test fun historyFallsBackOnTheTextOfALine() {
        val history = AssistantStream.historyJson(listOf(ChatLine("u", true, "Hi")))
        val message = history.single().jsonObject
        assertEquals("user", message["role"]?.jsonPrimitive?.content)
        assertEquals("Hi", message["parts"].toString().let { ApiClient.json.parseToJsonElement(it) }.let { (it as kotlinx.serialization.json.JsonArray)[0].jsonObject["text"]?.jsonPrimitive?.content })
    }
}
