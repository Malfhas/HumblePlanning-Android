package com.humbleplanning.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The quota of `/api/ai-chat/access`, read like the web gauge (`quota-gauge.tsx`). */
class ChatQuotaTest {
    private fun state(json: String) = ApiClient.json.decodeFromString(AssistantState.serializer(), json)

    @Test fun readsTheQuotaOfTheServer() {
        val quota = state("""{"enabled":true,"level":"WRITE","quota":{"enabled":true,"unlimited":false,"percent":42,"reached":false,"resetsAt":"2026-12-01T00:00:00.000Z"}}""").quota!!
        assertEquals(42, quota.percent)
        assertEquals("2026-12-01T00:00:00.000Z", quota.resetsAt)
        assertFalse(quota.blocksQuestions)
    }

    @Test fun noQuotaFromAnOlderServer() {
        assertNull(state("""{"enabled":true}""").quota)
    }

    @Test fun coloursLikeTheWeb() {
        assertEquals(QuotaLevel.Success, ChatQuota(enabled = true, percent = 69).level)
        assertEquals(QuotaLevel.Warning, ChatQuota(enabled = true, percent = 70).level)
        assertEquals(QuotaLevel.Warning, ChatQuota(enabled = true, percent = 89).level)
        assertEquals(QuotaLevel.Danger, ChatQuota(enabled = true, percent = 90).level)
    }

    @Test fun onlyAReachedQuotaOfALimitedAccountBlocksQuestions() {
        assertTrue(ChatQuota(enabled = true, percent = 100, reached = true).blocksQuestions)
        assertFalse(ChatQuota(enabled = true, unlimited = true, percent = 100, reached = true).blocksQuestions)
        assertFalse(ChatQuota(enabled = false, percent = 100, reached = true).blocksQuestions)
    }
}
