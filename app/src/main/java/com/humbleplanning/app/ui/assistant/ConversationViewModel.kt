package com.humbleplanning.app.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humbleplanning.app.data.ApiException
import com.humbleplanning.app.data.AssistantStream
import com.humbleplanning.app.data.ChatEvent
import com.humbleplanning.app.data.ChatLine
import com.humbleplanning.app.data.ChatQuota
import com.humbleplanning.app.data.PendingApproval
import com.humbleplanning.app.data.Repository
import com.humbleplanning.app.data.isBlocking
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class ConversationState(
    val title: String = "",
    val lines: List<ChatLine> = emptyList(),
    val loading: Boolean = false,
    val streaming: Boolean = false,
    /** A tool is running on the server. */
    val working: Boolean = false,
    val error: String? = null,
    val loadError: ApiException? = null,
    /** Monthly quota of the user; null until the server said. */
    val quota: ChatQuota? = null,
) {
    /** The turn waits for the user: nothing else can be sent until they answer. */
    val waitingForDecision: Boolean get() = lines.any { it.approvals.isNotEmpty() }

    /** Quota reached: the conversation stays readable, no new question until the reset. */
    val quotaReached: Boolean get() = quota?.blocksQuestions == true
}

/** History sent with a turn: answers as text, only the line being resumed keeps its tool calls and decisions. */
internal fun historyForTurn(lines: List<ChatLine>, resumedLineId: String?): List<ChatLine> =
    lines.filter { it.fromUser || it.text.isNotBlank() || it.id == resumedLineId }
        .map { if (it.id == resumedLineId) it else it.copy(parts = emptyList(), approvals = emptyList()) }

/** "create_task" or "createTask" -> "Create task". */
internal fun readableToolName(name: String): String =
    name.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace('_', ' ').replace('-', ' ').trim().lowercase().replaceFirstChar { it.uppercase() }

/** Input of a tool as indented `key: value` lines, for a person rather than a parser. */
internal fun readableJson(element: JsonElement?, indent: String = ""): String = when (element) {
    null, JsonNull -> ""
    is JsonPrimitive -> element.contentOrNull.orEmpty()
    is JsonArray -> element.joinToString("\n") { item ->
        if (item is JsonPrimitive) "$indent- ${item.contentOrNull.orEmpty()}" else "$indent-\n${readableJson(item, "$indent  ")}"
    }
    is JsonObject -> element.entries.filter { it.value != JsonNull }.joinToString("\n") { (key, value) ->
        if (value is JsonPrimitive) "$indent$key: ${value.contentOrNull.orEmpty()}" else "$indent$key:\n${readableJson(value, "$indent  ")}"
    }
}

class ConversationViewModel(private val repository: Repository, initialId: String?) : ViewModel() {
    private var conversationId: String? = initialId
    private val _state = MutableStateFlow(ConversationState(loading = initialId != null))
    val state = _state.asStateFlow()
    private var turn: Job? = null

    init {
        if (initialId != null) load(initialId)
        refreshQuota()
    }

    /** The gauge follows each answer, like on the web; a refusal for quota reached closes the input. */
    private fun refreshQuota() {
        viewModelScope.launch {
            val quota = repository.assistantQuota() ?: return@launch
            // Once reached, the closed input says it with the reset date: the refusal is not repeated above it
            _state.update { it.copy(quota = quota, error = if (quota.blocksQuestions) null else it.error) }
        }
    }

    private fun load(id: String) {
        viewModelScope.launch {
            try {
                val detail = repository.call { conversation(id) }
                val lines = detail.messages
                    .filter { it.content.isNotBlank() && (it.role == "USER" || it.role == "ASSISTANT") }
                    .map { ChatLine(it.id.ifEmpty { UUID.randomUUID().toString() }, fromUser = it.role == "USER", text = it.content) }
                _state.update { it.copy(title = detail.title, lines = lines, loading = false) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, loadError = e) }
            }
        }
    }

    /** The blocking screen speaks for itself; a failure without a message from the server is the network. */
    private fun errorText(e: ApiException, networkError: String): String? = when {
        e.isBlocking -> null
        e is ApiException.Network -> networkError
        else -> e.message ?: networkError
    }

    fun retryLoad() {
        val id = conversationId ?: return
        _state.update { it.copy(loading = true, loadError = null) }
        load(id)
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** [onAccepted] empties the input once the message is really on its way. */
    fun send(text: String, networkError: String, onAccepted: () -> Unit) {
        val message = text.trim()
        val s = _state.value
        if (message.isEmpty() || s.streaming || s.waitingForDecision || s.quotaReached) return
        _state.update { it.copy(streaming = true, error = null) }
        turn = viewModelScope.launch {
            val id = conversationId ?: try {
                repository.call { createConversation(message.take(60)) }.id.also {
                    conversationId = it
                    _state.update { state -> state.copy(title = message.take(60)) }
                }
            } catch (e: ApiException) {
                _state.update { it.copy(streaming = false, error = errorText(e, networkError)) }
                refreshQuota()
                return@launch
            }
            onAccepted()
            _state.update { it.copy(lines = it.lines + AssistantStream.userLine(message)) }
            runTurn(id, resumedLineId = null, networkError)
        }
    }

    fun decide(line: ChatLine, approval: PendingApproval, approved: Boolean, networkError: String) {
        val id = conversationId ?: return
        if (_state.value.streaming) return
        val answered = AssistantStream.withDecision(line, approval, approved)
        _state.update { s -> s.copy(lines = s.lines.map { if (it.id == line.id) answered else it }, error = null) }
        // Several writes in one answer: the turn resumes once they are all decided
        if (answered.approvals.isNotEmpty()) return
        _state.update { it.copy(streaming = true) }
        turn = viewModelScope.launch { runTurn(id, resumedLineId = line.id, networkError) }
    }

    private suspend fun runTurn(id: String, resumedLineId: String?, networkError: String) {
        val history = historyForTurn(_state.value.lines, resumedLineId)
        val replyId = UUID.randomUUID().toString()
        fun reply(transform: (ChatLine) -> ChatLine) = _state.update { s -> s.copy(lines = s.lines.map { if (it.id == replyId) transform(it) else it }) }
        _state.update { it.copy(lines = it.lines + ChatLine(replyId, fromUser = false, text = ""), working = false) }
        try {
            repository.assistantTurn(id, history).collect { event ->
                when (event) {
                    is ChatEvent.Text -> {
                        reply { it.copy(text = it.text + event.text) }
                        _state.update { it.copy(working = false) }
                    }
                    is ChatEvent.Tool -> _state.update { it.copy(working = true) }
                    is ChatEvent.Approval -> {
                        reply { it.copy(approvals = it.approvals + event.approval) }
                        _state.update { it.copy(working = false) }
                    }
                    is ChatEvent.Finished -> reply { it.copy(parts = event.parts) }
                    is ChatEvent.Failed -> _state.update { it.copy(error = event.message.ifBlank { networkError }) }
                }
            }
        } catch (e: ApiException) {
            _state.update { it.copy(error = errorText(e, networkError)) }
        } catch (e: CancellationException) {
            // Stopped by the user, or the screen left: what was received stays
            throw e
        } finally {
            _state.update { s ->
                s.copy(
                    streaming = false,
                    working = false,
                    // A turn that said nothing leaves no empty bubble
                    lines = s.lines.filterNot { it.id == replyId && it.text.isBlank() && it.approvals.isEmpty() },
                )
            }
            refreshQuota()
        }
    }

    /** Asks the server to stop; the stream then ends by itself, or is dropped when the server cannot be reached. */
    fun stop() {
        val id = conversationId ?: run {
            turn?.cancel()
            return
        }
        val running = turn ?: return
        viewModelScope.launch {
            runCatching { repository.call { stopReply(id) } }
            delay(STOP_GRACE_MILLIS)
            running.cancel()
        }
    }

    private companion object {
        const val STOP_GRACE_MILLIS = 3_000L
    }
}
