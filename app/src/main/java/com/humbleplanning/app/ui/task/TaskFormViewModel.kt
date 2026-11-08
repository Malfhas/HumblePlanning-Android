package com.humbleplanning.app.ui.task

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humbleplanning.app.data.ApiClient
import com.humbleplanning.app.data.ApiException
import com.humbleplanning.app.data.References
import com.humbleplanning.app.data.RepeatRequest
import com.humbleplanning.app.data.Repository
import com.humbleplanning.app.data.TaskDto
import com.humbleplanning.app.data.TaskPayload
import com.humbleplanning.app.data.forScreen
import com.humbleplanning.app.ui.hhmm
import com.humbleplanning.app.ui.paddedTime
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class DayMode { AllDay, Morning, Afternoon, Times }

/** Input errors checked before sending. */
enum class FormError {
    ProjectRequired, UsersRequired, StatusRequired, StartDateRequired, EndDateBeforeStart, TimesRequired, EndTimeBeforeStart,
    TextTooLong, LinkInvalid, RecurrenceEndRequired, RecurrenceEndBeforeStart, RecurrenceFrequency,
}

@Serializable
data class TaskForm(
    val projectId: String = "",
    val userIds: List<String> = emptyList(),
    val statusId: String = "",
    val startDate: String = "",
    /** Empty for a single day. */
    val endDate: String = "",
    val mode: DayMode = DayMode.AllDay,
    val startTime: String = "",
    val endTime: String = "",
    val title: String = "",
    val placeId: String = "",
    val placeText: String = "",
    val resourceId: String = "",
    val link: String = "",
    val milestone: Boolean = false,
    val locked: Boolean = false,
    val notes: String = "",
    val repeat: Boolean = false,
    val recurType: String = "weekly",
    val recurFrequency: Int = 1,
    val recurEndDate: String = "",
    val recurException: String = "skip",
    val applyToAll: Boolean = false,
)

data class TaskFormState(
    val isNew: Boolean,
    val isCopy: Boolean = false,
    val form: TaskForm = TaskForm(),
    val initial: TaskForm = TaskForm(),
    /** The task being edited; null for a new task or a copy. */
    val existing: TaskDto? = null,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val saving: Boolean = false,
    val validationError: FormError? = null,
    val apiError: ApiException? = null,
    val saved: Boolean = false,
) {
    val dirty: Boolean get() = form != initial
}

private val TIME = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$")

/** Checks done here so that a mistake does not need a round trip; the server checks again. */
internal fun validateTaskForm(f: TaskForm): FormError? = when {
    f.projectId.isEmpty() -> FormError.ProjectRequired
    f.userIds.isEmpty() -> FormError.UsersRequired
    f.statusId.isEmpty() -> FormError.StatusRequired
    f.startDate.isEmpty() -> FormError.StartDateRequired
    f.endDate.isNotEmpty() && f.endDate < f.startDate -> FormError.EndDateBeforeStart
    f.mode == DayMode.Times && (!TIME.matches(f.startTime) || !TIME.matches(f.endTime)) -> FormError.TimesRequired
    f.mode == DayMode.Times && (f.endDate.isEmpty() || f.endDate == f.startDate) && f.endTime.paddedTime() <= f.startTime.paddedTime() -> FormError.EndTimeBeforeStart
    f.title.length > 255 || f.placeText.length > 255 -> FormError.TextTooLong
    f.link.isNotBlank() && f.link.trim().toHttpUrlOrNull() == null -> FormError.LinkInvalid
    f.repeat && f.recurEndDate.isEmpty() -> FormError.RecurrenceEndRequired
    f.repeat && f.recurEndDate <= f.startDate -> FormError.RecurrenceEndBeforeStart
    f.repeat && f.recurFrequency !in 1..30 -> FormError.RecurrenceFrequency
    else -> null
}

/**
 * Body of the request. Optional text goes out as null, except title and notes, which the server accepts
 * as missing or text but not as null: they go out empty. `isLocked` only with the right to lock.
 */
internal fun TaskForm.toPayload(refs: References, canLock: Boolean, placeTextChanged: Boolean = false): TaskPayload {
    val (start, end) = when (mode) {
        DayMode.AllDay -> null to null
        DayMode.Morning -> refs.morningStart to refs.morningEnd
        DayMode.Afternoon -> refs.afternoonStart to refs.afternoonEnd
        DayMode.Times -> startTime.paddedTime() to endTime.paddedTime()
    }
    return TaskPayload(
        projectId = projectId,
        userIds = userIds,
        statusId = statusId,
        startDate = startDate,
        endDate = endDate.takeIf { it.isNotEmpty() && it != startDate },
        startTime = start,
        endTime = end,
        title = title.trim(),
        notes = notes,
        link = link.trim().ifEmpty { null },
        placeId = placeId.ifEmpty { null },
        placeText = placeText.trim().ifEmpty { null },
        placeTextLat = JsonNull.takeIf { placeTextChanged },
        placeTextLng = JsonNull.takeIf { placeTextChanged },
        resourceId = resourceId.ifEmpty { null },
        isMilestone = milestone,
        isLocked = if (canLock) locked else null,
    )
}

class TaskFormViewModel(
    private val repository: Repository,
    private val handle: SavedStateHandle,
    private val taskId: String?,
    private val defaultDate: String?,
    copyOf: String?,
    /** Row of the planning grid the task was created from. */
    private val defaultUserId: String? = null,
) : ViewModel() {
    private val restoredForm: TaskForm? = handle.get<String>(KEY_FORM)?.let(::decode)
    private val restoredInitial: TaskForm? = handle.get<String>(KEY_INITIAL)?.let(::decode)

    /** A new task already sent, if its recurrence then failed: saving again must not create a second one. */
    private var createdId: String? = handle[KEY_CREATED_ID]

    private val _state = MutableStateFlow(
        TaskFormState(
            isNew = taskId == null,
            isCopy = copyOf != null,
            form = restoredForm ?: TaskForm(),
            initial = restoredInitial ?: TaskForm(),
        )
    )
    val state = _state.asStateFlow()
    val references = repository.references
    val currentUserId: String get() = repository.userId

    init {
        val source = taskId ?: copyOf
        if (source != null) {
            _state.update { it.copy(loading = true) }
            viewModelScope.launch {
                try {
                    val task = repository.taskToEdit(source)
                    val refs = repository.references.value
                    val form = restoredForm ?: task.toForm(refs).let { form ->
                        if (copyOf == null) form
                        else form.copy(locked = false, applyToAll = false, repeat = false, userIds = refs.access.assignableAmong(form.userIds, refs.users))
                    }
                    _state.update {
                        it.copy(
                            form = form,
                            // A copy counts as changed from the start: saving it creates a new task
                            initial = restoredInitial ?: if (copyOf != null) TaskForm() else form,
                            existing = task.takeIf { taskId != null },
                            loading = false,
                        )
                    }
                    persist()
                } catch (e: ApiException) {
                    _state.update { it.copy(loading = false, loadFailed = true, apiError = e) }
                }
            }
        } else if (restoredForm == null) {
            // Reference data can arrive after the form opens: the defaults are filled in as soon as they are known
            viewModelScope.launch {
                repository.references.collect { refs ->
                    _state.update { s ->
                        val form = s.form.withDefaults(refs, repository.userId, defaultDate)
                        val untouched = !s.dirty
                        s.copy(form = form, initial = if (untouched) form else s.initial)
                    }
                    persist()
                }
            }
        }
    }

    private fun TaskForm.withDefaults(refs: References, me: String, date: String?): TaskForm {
        val status = statusId.ifEmpty { (refs.statuses.firstOrNull { it.isDefault } ?: refs.statuses.firstOrNull())?.id.orEmpty() }
        val assignable = refs.access.assignableUsers(refs.users)
        val users = userIds.ifEmpty { listOfNotNull(defaultUserId ?: me).filter { id -> assignable.any { it.id == id } } }
        return copy(statusId = status, userIds = users, startDate = startDate.ifEmpty { date ?: LocalDate.now().toString() })
    }

    private fun TaskDto.toForm(refs: References): TaskForm {
        val start = startTime?.takeIf { it.isNotBlank() }?.hhmm().orEmpty()
        val end = endTime?.takeIf { it.isNotBlank() }?.hhmm().orEmpty()
        val mode = when {
            start.isEmpty() -> DayMode.AllDay
            start.paddedTime() == refs.morningStart.paddedTime() && end.paddedTime() == refs.morningEnd.paddedTime() -> DayMode.Morning
            start.paddedTime() == refs.afternoonStart.paddedTime() && end.paddedTime() == refs.afternoonEnd.paddedTime() -> DayMode.Afternoon
            else -> DayMode.Times
        }
        return TaskForm(
            projectId = projectId,
            userIds = userIds,
            statusId = statusId,
            startDate = startDate,
            endDate = endDate?.takeIf { it != startDate }.orEmpty(),
            mode = mode,
            startTime = if (mode == DayMode.Times) start else "",
            endTime = if (mode == DayMode.Times) end else "",
            title = title.orEmpty(),
            placeId = placeId.orEmpty(),
            placeText = placeText.orEmpty(),
            resourceId = resourceId.orEmpty(),
            link = link.orEmpty(),
            milestone = isMilestone,
            locked = isLocked,
            notes = notes.orEmpty(),
            repeat = isSeriesRoot,
            recurType = recurType ?: "weekly",
            recurFrequency = recurFrequency ?: 1,
            recurEndDate = recurEndDate.orEmpty(),
            recurException = recurException ?: "skip",
        )
    }

    private fun decode(json: String): TaskForm? = runCatching { ApiClient.json.decodeFromString(TaskForm.serializer(), json) }.getOrNull()

    /** Saved outside the process: Android can end it while the user is away from the form. */
    private fun persist() {
        val s = _state.value
        handle[KEY_FORM] = ApiClient.json.encodeToString(TaskForm.serializer(), s.form)
        handle[KEY_INITIAL] = ApiClient.json.encodeToString(TaskForm.serializer(), s.initial)
    }

    fun update(transform: (TaskForm) -> TaskForm) {
        _state.update { it.copy(form = transform(it.form), validationError = null, apiError = null) }
        persist()
    }

    fun save() {
        val s = _state.value
        val f = s.form
        validateTaskForm(f)?.let { error -> _state.update { it.copy(validationError = error) }; return }
        val refs = repository.references.value
        val existing = s.existing
        val payload = f.toPayload(
            refs,
            canLock = existing != null && refs.access.canLock(existing),
            placeTextChanged = existing != null && f.placeText.trim() != existing.placeText.orEmpty().trim(),
        )
        // The series is only rebuilt when its rule changed, or when there is none yet
        val ruleChanged = existing == null || f.recurType != s.initial.recurType || f.recurFrequency != s.initial.recurFrequency ||
            f.recurEndDate != s.initial.recurEndDate || f.recurException != s.initial.recurException || !existing.isSeriesRoot

        _state.update { it.copy(saving = true, apiError = null) }
        viewModelScope.launch {
            try {
                val id = taskId ?: createdId
                val saved = if (id == null) repository.createTask(payload) else repository.updateTask(id, payload, f.applyToAll)
                if (taskId == null && createdId == null) {
                    createdId = saved.id
                    handle[KEY_CREATED_ID] = saved.id
                }
                if (f.repeat && ruleChanged) {
                    repository.repeatTask(saved.id, RepeatRequest(f.recurType, f.recurFrequency, f.recurEndDate, f.recurException))
                }
                _state.update { it.copy(saving = false, saved = true) }
            } catch (e: ApiException) {
                _state.update { it.copy(saving = false, apiError = e.forScreen()) }
            }
        }
    }

    private companion object {
        const val KEY_FORM = "form"
        const val KEY_INITIAL = "initial"
        const val KEY_CREATED_ID = "created_id"
    }
}
