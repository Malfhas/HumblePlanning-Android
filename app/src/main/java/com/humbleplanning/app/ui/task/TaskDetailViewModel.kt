package com.humbleplanning.app.ui.task

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humbleplanning.app.data.ApiException
import com.humbleplanning.app.data.Repository
import com.humbleplanning.app.data.TaskDto
import com.humbleplanning.app.data.TaskFile
import com.humbleplanning.app.data.forScreen
import com.humbleplanning.app.data.isNotFound
import com.humbleplanning.app.ui.attachmentsDir
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FileProblem { Type, Size, Read, NoViewer }

data class TaskDetailState(
    val task: TaskDto? = null,
    val loading: Boolean = true,
    val error: ApiException? = null,
    val deleting: Boolean = false,
    val deleted: Boolean = false,
    val uploading: Boolean = false,
    /** Attachment being downloaded before it opens. */
    val openingFileId: String? = null,
    /** Downloaded attachment, for the screen to hand over to another app. */
    val opened: OpenedFile? = null,
    val fileProblem: FileProblem? = null,
    val actionError: ApiException? = null,
)

data class OpenedFile(val file: File, val mimeType: String)

/** A file name of the server, as a name inside the cache folder: never a path. */
internal fun safeFileName(name: String, fallback: String): String =
    name.substringAfterLast('/').substringAfterLast('\\').filterNot { it.isISOControl() }.trim().trimStart('.').ifEmpty { fallback }

class TaskDetailViewModel(private val repository: Repository, private val taskId: String) : ViewModel() {
    private val _state = MutableStateFlow(TaskDetailState())
    val state = _state.asStateFlow()
    val references = repository.references

    init {
        load()
        viewModelScope.launch { repository.tasksChanged.drop(1).collect { load(silent = true) } }
    }

    fun load(silent: Boolean = false) {
        if (!silent) _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val task = repository.task(taskId)
                _state.update { it.copy(task = task, loading = false, error = null) }
            } catch (e: ApiException) {
                // Deleted or out of reach while on screen: the task is really gone from here
                _state.update { it.copy(task = if (e.isNotFound) null else it.task, loading = false, error = e) }
            }
        }
    }

    private fun action(block: suspend () -> Unit) {
        _state.update { it.copy(deleting = true, actionError = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: ApiException) {
                _state.update { it.copy(deleting = false, actionError = e.forScreen()) }
            }
        }
    }

    /** `scope`: single, after or all (series). */
    fun delete(scope: String = "single") = action {
        repository.deleteTask(taskId, scope)
        _state.update { it.copy(deleting = false, deleted = true) }
    }

    fun removeUser(userId: String) = action {
        repository.removeUserFromTask(taskId, userId)
        _state.update { it.copy(deleting = false) }
    }

    /** Checks extension and size before anything is sent; the file is read as it goes out, never kept in memory. */
    fun upload(context: Context, uri: Uri, extensions: List<String>, maxBytes: Long) {
        _state.update { it.copy(uploading = true, fileProblem = null, actionError = null) }
        val resolver = context.applicationContext.contentResolver
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { describe(context, uri) }
            when {
                picked == null -> _state.update { it.copy(uploading = false, fileProblem = FileProblem.Read) }
                extensions.isNotEmpty() && picked.name.substringAfterLast('.', "").lowercase() !in extensions ->
                    _state.update { it.copy(uploading = false, fileProblem = FileProblem.Type) }
                picked.size > maxBytes -> _state.update { it.copy(uploading = false, fileProblem = FileProblem.Size) }
                else -> try {
                    repository.uploadFile(taskId, picked.name, picked.mimeType, picked.size) { resolver.openInputStream(uri) }
                    _state.update { it.copy(uploading = false) }
                } catch (e: ApiException) {
                    _state.update { it.copy(uploading = false, actionError = e.forScreen()) }
                }
            }
        }
    }

    private data class PickedFile(val name: String, val mimeType: String, val size: Long)

    /** The size must be known beforehand: it is checked here and announced in the request. */
    private fun describe(context: Context, uri: Uri): PickedFile? = runCatching {
        val resolver = context.applicationContext.contentResolver
        var name: String? = null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                if (!c.isNull(0)) name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size < 0) size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (size < 0) return null
        PickedFile(
            safeFileName(name ?: uri.lastPathSegment.orEmpty(), "file"),
            resolver.getType(uri) ?: "application/octet-stream",
            size,
        )
    }.getOrNull()

    fun deleteFile(fileId: String) {
        viewModelScope.launch {
            try {
                repository.deleteFile(taskId, fileId)
            } catch (e: ApiException) {
                _state.update { it.copy(actionError = e.forScreen()) }
            }
        }
    }

    /** Downloads into the shared cache folder; the screen then opens it in another app. */
    fun open(context: Context, file: TaskFile) {
        if (_state.value.openingFileId != null) return
        _state.update { it.copy(openingFileId = file.id, fileProblem = null, actionError = null) }
        val folder = attachmentsDir(context)
        viewModelScope.launch {
            try {
                val target = withContext(Dispatchers.IO) {
                    folder.mkdirs()
                    File(folder, safeFileName(file.originalName.ifBlank { file.name }, file.id))
                }
                repository.downloadFile(taskId, file, target)
                _state.update { it.copy(openingFileId = null, opened = OpenedFile(target, file.mimeType)) }
            } catch (e: ApiException) {
                _state.update { it.copy(openingFileId = null, actionError = e.forScreen()) }
            }
        }
    }

    fun consumeOpened(problem: FileProblem? = null) = _state.update { it.copy(opened = null, fileProblem = problem) }
}
