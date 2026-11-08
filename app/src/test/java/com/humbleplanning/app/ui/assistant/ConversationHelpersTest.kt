package com.humbleplanning.app.ui.assistant

import androidx.compose.ui.graphics.Color
import com.humbleplanning.app.data.ChatLine
import com.humbleplanning.app.data.PendingApproval
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHelpersTest {
    private val toolPart = buildJsonObject { put("type", "tool-x"); put("state", "approval-responded") }

    @Test fun onlyTheResumedLineKeepsItsToolCalls() {
        val history = historyForTurn(
            listOf(
                ChatLine("u", true, "Hi"),
                ChatLine("a1", false, "Done", parts = listOf(toolPart)),
                ChatLine("a2", false, "", parts = listOf(toolPart)),
            ),
            resumedLineId = "a2",
        )
        assertEquals(listOf("u", "a1", "a2"), history.map { it.id })
        assertTrue(history[1].parts.isEmpty())
        assertEquals(1, history[2].parts.size)
    }

    @Test fun answersWithoutTextAreLeftOutOfANewTurn() {
        val history = historyForTurn(
            listOf(ChatLine("a", false, "", parts = listOf(toolPart)), ChatLine("u", true, "Next")),
            resumedLineId = null,
        )
        assertEquals(listOf("u"), history.map { it.id })
    }

    @Test fun approvalsDoNotGoOut() {
        val approval = PendingApproval("a", "c", "t", null)
        val history = historyForTurn(listOf(ChatLine("a", false, "x", approvals = listOf(approval))), null)
        assertTrue(history.single().approvals.isEmpty())
    }

    @Test fun toolNamesForPeople() {
        assertEquals("Create task", readableToolName("create_task"))
        assertEquals("Create task", readableToolName("createTask"))
    }

    @Test fun jsonForPeople() {
        val input = buildJsonObject {
            put("title", "Visit")
            put("count", 2)
            put("nothing", JsonNull)
            put("users", buildJsonArray { add("Ann"); add("Bob") })
            put("place", buildJsonObject { put("city", "Lyon") })
        }
        assertEquals(
            """
            title: Visit
            count: 2
            users:
              - Ann
              - Bob
            place:
              city: Lyon
            """.trimIndent(),
            readableJson(input),
        )
        assertEquals("", readableJson(null))
    }

    @Test fun paragraphsBulletsAndHeadings() {
        val blocks = markdownBlocks("# Title\n\nOne\nstill one\n\n- a\n* b\n\nTwo")
        assertEquals(
            listOf(
                MarkdownBlock("Title", heading = true),
                MarkdownBlock("One\nstill one"),
                MarkdownBlock("a", bullet = true),
                MarkdownBlock("b", bullet = true),
                MarkdownBlock("Two"),
            ),
            blocks,
        )
    }

    @Test fun boldAndCodeLoseTheirMarkers() {
        val text = inlineMarkdown("a **b** `c` d", Color.Black)
        assertEquals("a b c d", text.text)
        assertEquals(2, text.spanStyles.size)
    }

    @Test fun anOpenMarkerStaysAsText() {
        assertEquals("a **b", inlineMarkdown("a **b", Color.Black).text)
        assertEquals("a `b", inlineMarkdown("a `b", Color.Black).text)
    }
}
