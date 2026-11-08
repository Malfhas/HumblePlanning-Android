package com.humbleplanning.app.data

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A write the assistant wants to run: the turn waits for the user's decision. */
data class PendingApproval(val approvalId: String, val toolCallId: String, val toolName: String, val input: JsonElement?)

data class ChatLine(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val approvals: List<PendingApproval> = emptyList(),
    /** Parts as the server sent them: sent back with the history, tool calls and approvals included. */
    val parts: List<JsonObject> = emptyList(),
)

sealed interface ChatEvent {
    data class Text(val text: String) : ChatEvent
    data class Approval(val approval: PendingApproval) : ChatEvent
    /** A tool is running: `name` is its technical name. */
    data class Tool(val name: String) : ChatEvent
    data class Finished(val parts: List<JsonObject>) : ChatEvent
    data class Failed(val message: String) : ChatEvent
}

/**
 * One conversation turn, over the stream protocol of the web chat (AI SDK UI messages, server-sent
 * events). The provider is the server's: the app holds no key. A write stops the turn at
 * `approval-requested`; [withDecision] then gives the history to send back for it to resume.
 */
class AssistantStream(private val http: OkHttpClient) {
    /** Long answers: no call timeout, only a long silence ends the read. */
    private val client: OkHttpClient by lazy {
        http.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    }

    fun turn(api: ApiClient, conversationId: String, history: List<ChatLine>): Flow<ChatEvent> = flow {
        val body = buildJsonObject { put("messages", historyJson(history)) }.toString()
        val request = api.chatTurnRequest(conversationId, body)
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            emit(ChatEvent.Failed(e.message.orEmpty()))
            return@flow
        }
        response.use {
            if (!it.isSuccessful) {
                val failure = api.failureOf(it.code, request.url.encodedPath, it.body.string())
                emit(ChatEvent.Failed(failure.message.orEmpty()))
                return@flow
            }
            val builder = ReplyBuilder()
            try {
                val source = it.body.source()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val event = runCatching { ApiClient.json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    builder.accept(event)?.let { emitted -> emit(emitted) }
                }
                emit(ChatEvent.Finished(builder.parts()))
            } catch (e: IOException) {
                emit(ChatEvent.Failed(e.message.orEmpty()))
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        fun userLine(text: String) = ChatLine(UUID.randomUUID().toString(), fromUser = true, text = text)

        fun historyJson(lines: List<ChatLine>): JsonArray = buildJsonArray {
            lines.forEach { line ->
                add(buildJsonObject {
                    put("id", line.id)
                    put("role", if (line.fromUser) "user" else "assistant")
                    put("parts", JsonArray(line.parts.ifEmpty { listOf(textPart(line.text)) }))
                })
            }
        }

        private fun textPart(text: String) = buildJsonObject {
            put("type", "text")
            put("text", text)
        }

        /** The same line with this tool call answered: `approval-responded`, as the web chat sends it. */
        fun withDecision(line: ChatLine, approval: PendingApproval, approved: Boolean): ChatLine = line.copy(
            approvals = line.approvals.filterNot { it.approvalId == approval.approvalId },
            parts = line.parts.map { part ->
                if (part["toolCallId"]?.jsonPrimitive?.contentOrNull != approval.toolCallId) return@map part
                JsonObject(part + mapOf(
                    "state" to JsonPrimitive("approval-responded"),
                    "approval" to buildJsonObject {
                        put("id", approval.approvalId)
                        put("approved", approved)
                    },
                ))
            },
        )
    }
}

/** Rebuilds the assistant message from the stream events. */
internal class ReplyBuilder {
    private val text = StringBuilder()
    private val tools = linkedMapOf<String, JsonObject>()

    fun accept(event: JsonObject): ChatEvent? {
        fun field(name: String) = event[name]?.jsonPrimitive?.contentOrNull
        return when (field("type")) {
            "text-delta" -> {
                val delta = field("delta").orEmpty()
                text.append(delta)
                ChatEvent.Text(delta)
            }
            "tool-input-available" -> {
                val id = field("toolCallId").orEmpty()
                val name = field("toolName").orEmpty()
                tools[id] = buildJsonObject {
                    put("type", "tool-$name")
                    put("toolCallId", id)
                    put("state", "input-available")
                    event["input"]?.let { put("input", it) }
                }
                ChatEvent.Tool(name)
            }
            "tool-approval-request" -> {
                val id = field("toolCallId").orEmpty()
                val approvalId = field("approvalId").orEmpty()
                val part = tools[id] ?: return null
                tools[id] = JsonObject(part + mapOf(
                    "state" to JsonPrimitive("approval-requested"),
                    "approval" to buildJsonObject { put("id", approvalId) },
                ))
                val name = part["type"]?.jsonPrimitive?.contentOrNull.orEmpty().removePrefix("tool-")
                ChatEvent.Approval(PendingApproval(approvalId, id, name, part["input"]))
            }
            "tool-output-available" -> {
                val id = field("toolCallId").orEmpty()
                val part = tools[id] ?: return null
                tools[id] = JsonObject(part + buildMap {
                    put("state", JsonPrimitive("output-available"))
                    event["output"]?.let { put("output", it) }
                })
                null
            }
            "error" -> ChatEvent.Failed(field("errorText").orEmpty())
            else -> null
        }
    }

    fun parts(): List<JsonObject> = buildList {
        addAll(tools.values)
        if (text.isNotEmpty()) add(buildJsonObject {
            put("type", "text")
            put("text", text.toString())
        })
    }
}
