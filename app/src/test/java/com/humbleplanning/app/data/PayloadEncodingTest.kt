package com.humbleplanning.app.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The server refuses a null for these fields (Zod `.optional()` without `.nullable()`): 422 on the whole request. */
class PayloadEncodingTest {
    private val payload = TaskPayload(
        projectId = "p", userIds = listOf("u"), statusId = "s", startDate = "2026-11-05", endDate = null, startTime = null, endTime = null,
        title = "", notes = "", placeId = null, placeText = null, resourceId = null, isMilestone = false,
    )

    @Test fun clearedFieldsGoOutAsNull() {
        val json = ApiClient.json.encodeToJsonElement(TaskPayload.serializer(), payload).jsonObject
        assertEquals(JsonNull, json["endDate"])
        assertEquals(JsonNull, json["startTime"])
        assertEquals(JsonNull, json["link"])
        assertEquals("false", json["isMilestone"]?.jsonPrimitive?.content)
    }

    @Test fun lockIsLeftOutWithoutTheRight() {
        assertFalse("isLocked" in ApiClient.json.encodeToJsonElement(TaskPayload.serializer(), payload).jsonObject)
    }

    @Test fun lockGoesOutWithTheRight() {
        val json = ApiClient.json.encodeToJsonElement(TaskPayload.serializer(), payload.copy(isLocked = false)).jsonObject
        assertEquals("false", json["isLocked"]?.jsonPrimitive?.content)
    }

    @Test fun coordinatesAreLeftOutWhenTheAddressDidNotChange() {
        val json = ApiClient.json.encodeToJsonElement(TaskPayload.serializer(), payload).jsonObject
        assertFalse("placeTextLat" in json)
        assertFalse("placeTextLng" in json)
    }

    @Test fun coordinatesGoOutAsNullWhenTheAddressChanged() {
        val cleared = payload.copy(placeText = "New address", placeTextLat = JsonNull, placeTextLng = JsonNull)
        val json = ApiClient.json.encodeToJsonElement(TaskPayload.serializer(), cleared).jsonObject
        assertEquals(JsonNull, json["placeTextLat"])
        assertEquals(JsonNull, json["placeTextLng"])
    }

    @Test fun deviceUpdateOnlySendsThePushToken() {
        val json = ApiClient.json.encodeToJsonElement(UpdateDeviceRequest.serializer(), UpdateDeviceRequest(pushToken = null)).jsonObject
        assertEquals(setOf("pushToken"), json.keys)
        assertEquals(JsonNull, json["pushToken"])
    }

    @Test fun repeatLeavesOutTheDayOfWeek() {
        val json = ApiClient.json.encodeToJsonElement(RepeatRequest.serializer(), RepeatRequest("weekly", 1, "2026-12-31", "skip")).jsonObject
        assertFalse("recurDayOfWeek" in json)
    }

    @Test fun profileLeavesOutAMissingColor() {
        val request = UpdateProfileRequest("Name", null, null, null, null)
        val json = ApiClient.json.encodeToJsonElement(UpdateProfileRequest.serializer(), request).jsonObject
        assertFalse("color" in json)
        assertTrue("firstName" in json)
    }
}
