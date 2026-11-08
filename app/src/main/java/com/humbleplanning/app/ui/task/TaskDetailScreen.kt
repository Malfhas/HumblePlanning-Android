package com.humbleplanning.app.ui.task

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.humbleplanning.app.BuildConfig
import com.humbleplanning.app.R
import com.humbleplanning.app.data.Access
import com.humbleplanning.app.data.References
import com.humbleplanning.app.data.TaskDto
import com.humbleplanning.app.data.TaskFile
import com.humbleplanning.app.data.UserDto
import com.humbleplanning.app.data.isNotFound
import com.humbleplanning.app.ui.Banner
import com.humbleplanning.app.ui.BannerTone
import com.humbleplanning.app.ui.CenteredLoader
import com.humbleplanning.app.ui.DialogOption
import com.humbleplanning.app.ui.EmptyState
import com.humbleplanning.app.ui.apiErrorMessage
import com.humbleplanning.app.ui.appLocale
import com.humbleplanning.app.ui.fileSizeLabel
import com.humbleplanning.app.ui.humbleViewModel
import com.humbleplanning.app.ui.longLabel
import com.humbleplanning.app.ui.mediumLabel
import com.humbleplanning.app.ui.parseColor
import com.humbleplanning.app.ui.planning.StatusChip
import com.humbleplanning.app.ui.planning.UserAvatar
import com.humbleplanning.app.ui.planning.projectOf
import com.humbleplanning.app.ui.planning.statusOf
import com.humbleplanning.app.ui.planning.timeLabel
import com.humbleplanning.app.ui.planning.withStatus
import com.humbleplanning.app.ui.toDateOrNull

private enum class DeleteStep { Confirm, Series, Shared, RemoveUser }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(taskId: String, onBack: () -> Unit, onEdit: () -> Unit, onCopy: () -> Unit) {
    val vm = humbleViewModel(key = "task-$taskId") { TaskDetailViewModel(it.repository, taskId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refs by vm.references.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menu by rememberSaveable { mutableStateOf(false) }
    var deleteStep by rememberSaveable { mutableStateOf<DeleteStep?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.upload(context, uri, refs.uploadExtensions, refs.maxUploadBytes)
    }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    LaunchedEffect(state.opened) {
        val opened = state.opened ?: return@LaunchedEffect
        vm.consumeOpened(openInOtherApp(context, opened))
    }

    val task = state.task
    val access = refs.access
    val projectColor = parseColor(task?.projectOf(refs)?.color) ?: MaterialTheme.colorScheme.outline

    Box(Modifier.fillMaxSize()) {
        when {
            task == null && state.loading -> CenteredLoader()
            task == null -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                val notFound = state.error?.isNotFound == true
                EmptyState(
                    Icons.Outlined.EventBusy,
                    if (notFound) stringResource(R.string.err_task_not_found) else apiErrorMessage(state.error),
                    action = { OutlinedButton(onClick = if (notFound) onBack else ({ vm.load() })) { Text(stringResource(if (notFound) R.string.back else R.string.retry)) } },
                )
            }
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Header(task, refs, projectColor)
                Column(Modifier.padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.actionError?.let { Banner(apiErrorMessage(it), BannerTone.Error) }
                    state.fileProblem?.let { Banner(fileProblemMessage(it, refs), BannerTone.Error) }
                    Details(task, refs)
                    if (refs.filesEnabled && access.canViewFiles) {
                        Attachments(
                            task,
                            access,
                            uploading = state.uploading,
                            openingFileId = state.openingFileId,
                            onAdd = { picker.launch(arrayOf("*/*")) },
                            onOpen = { vm.open(context, it) },
                            onDelete = vm::deleteFile,
                            hint = uploadHint(refs),
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        TopAppBar(
            title = {},
            navigationIcon = { RoundIcon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), onBack) },
            actions = {
                if (task != null) {
                    val canEdit = access.canEdit(task)
                    val canCopy = access.canDuplicate(task)
                    val canDelete = access.canDelete(task)
                    if (canEdit) {
                        RoundIcon(Icons.Outlined.Edit, stringResource(R.string.edit), onEdit)
                        Spacer(Modifier.width(6.dp))
                    }
                    if (canCopy || canDelete) {
                        Box {
                            RoundIcon(Icons.Outlined.MoreVert, stringResource(R.string.more_actions)) { menu = true }
                            DropdownMenu(menu, { menu = false }) {
                                if (canCopy) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.duplicate)) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = { menu = false; onCopy() })
                                }
                                if (canDelete) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            menu = false
                                            deleteStep = when {
                                                task.isRecurring -> DeleteStep.Series
                                                task.users.size > 1 -> DeleteStep.Shared
                                                else -> DeleteStep.Confirm
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
    }

    if (task != null) {
        DeleteDialogs(deleteStep, task, access, onStep = { deleteStep = it }, onDelete = vm::delete, onRemoveUser = vm::removeUser)
    }
}

/** Hands the file over to the app that opens its type; the other app only reads this one file. */
private fun openInOtherApp(context: Context, opened: OpenedFile): FileProblem? {
    val uri: Uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", opened.file)
    val type = opened.mimeType.ifBlank { context.contentResolver.getType(uri) ?: "*/*" }
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(intent)
        null
    } catch (e: ActivityNotFoundException) {
        FileProblem.NoViewer
    }
}

@Composable
private fun fileProblemMessage(problem: FileProblem, refs: References): String = when (problem) {
    FileProblem.Type -> stringResource(R.string.err_file_type, refs.uploadExtensions.joinToString(", "))
    FileProblem.Size -> stringResource(R.string.err_file_size, refs.maxUploadBytes / (1024 * 1024))
    FileProblem.Read -> stringResource(R.string.err_file_read)
    FileProblem.NoViewer -> stringResource(R.string.err_file_no_viewer)
}

@Composable
private fun uploadHint(refs: References): String {
    val size = stringResource(R.string.files_max_size, refs.maxUploadBytes / (1024 * 1024))
    return if (refs.uploadExtensions.isEmpty()) size else "${refs.uploadExtensions.joinToString(", ")} · $size"
}

@Composable
private fun DeleteDialogs(
    step: DeleteStep?,
    task: TaskDto,
    access: Access,
    onStep: (DeleteStep?) -> Unit,
    onDelete: (String) -> Unit,
    onRemoveUser: (String) -> Unit,
) {
    val error = MaterialTheme.colorScheme.error
    val close = { onStep(null) }
    val cancel: @Composable () -> Unit = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } }
    when (step) {
        DeleteStep.Confirm -> AlertDialog(
            onDismissRequest = close,
            icon = { Icon(Icons.Outlined.Delete, null) },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_text)) },
            confirmButton = {
                Button(
                    onClick = { onStep(null); onDelete("single") },
                    colors = ButtonDefaults.buttonColors(containerColor = error),
                    shape = MaterialTheme.shapes.medium,
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = cancel,
        )
        DeleteStep.Series -> AlertDialog(
            onDismissRequest = close,
            icon = { Icon(Icons.Outlined.Repeat, null) },
            title = { Text(stringResource(R.string.delete_series_title)) },
            text = {
                Column {
                    DialogOption(stringResource(R.string.delete_this_occurrence), error) { onStep(null); onDelete("single") }
                    DialogOption(stringResource(R.string.delete_this_and_following), error) { onStep(null); onDelete("after") }
                    DialogOption(stringResource(R.string.delete_whole_series), error) { onStep(null); onDelete("all") }
                }
            },
            confirmButton = cancel,
        )
        DeleteStep.Shared -> AlertDialog(
            onDismissRequest = close,
            icon = { Icon(Icons.Outlined.Delete, null) },
            title = { Text(stringResource(R.string.delete_shared_title)) },
            text = {
                Column {
                    DialogOption(stringResource(R.string.delete_for_everyone), error) { onStep(null); onDelete("single") }
                    if (access.canAssign(task)) {
                        DialogOption(stringResource(R.string.delete_remove_user)) { onStep(DeleteStep.RemoveUser) }
                    }
                }
            },
            confirmButton = cancel,
        )
        DeleteStep.RemoveUser -> AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.delete_remove_user)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    task.users.forEach { user ->
                        DialogOption(user.displayName) { onStep(null); onRemoveUser(user.id) }
                    }
                }
            },
            confirmButton = cancel,
        )
        null -> Unit
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.92f), shadowElevation = 2.dp) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(icon, description, Modifier.size(20.dp)) }
    }
}

/** Header in the colours of the project: project, title, status. */
@Composable
private fun Header(task: TaskDto, refs: References, color: Color) {
    val project = task.projectOf(refs)
    val status = task.statusOf(refs)
    // Below the buttons of the bar, whatever the height of the status bar of the phone
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopAppBarDefaults.TopAppBarExpandedHeight
    Box(
        Modifier.fillMaxWidth().background(
            Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0.10f), MaterialTheme.colorScheme.background))
        )
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = top, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Without a task title the project takes its place: not shown twice
            val ownTitle = task.title?.ifBlank { null }
            val context = listOfNotNull(project?.group?.name?.ifBlank { null }, project?.label?.takeIf { ownTitle != null }).joinToString(" · ")
            if (context.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(color, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        context,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                ownTitle ?: project?.label.orEmpty(),
                style = MaterialTheme.typography.headlineSmall.withStatus(status),
                modifier = Modifier.semantics { heading() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (status != null) StatusChip(status.name, parseColor(status.color))
                if (task.isMilestone) HeaderFlag(Icons.Outlined.Flag, stringResource(R.string.milestone), MaterialTheme.colorScheme.tertiary)
                if (task.isLocked) HeaderFlag(Icons.Outlined.Lock, stringResource(R.string.a11y_locked), MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HeaderFlag(icon: ImageVector, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = color)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** Map app at the coordinates of the place, or else at the address of the project. */
private fun mapUri(task: TaskDto, project: com.humbleplanning.app.data.ProjectDto?): Uri? {
    val label = Uri.encode(task.placeLabel.orEmpty())
    val lat = task.placeTextLat?.takeIf { it.isNotBlank() }
    val lng = task.placeTextLng?.takeIf { it.isNotBlank() }
    val projectLat = project?.addressLat?.takeIf { it.isNotBlank() }
    val projectLng = project?.addressLng?.takeIf { it.isNotBlank() }
    val address = project?.address?.takeIf { it.isNotBlank() }
    return when {
        lat != null && lng != null -> "geo:$lat,$lng?q=$lat,$lng($label)".toUri()
        projectLat != null && projectLng != null -> "geo:$projectLat,$projectLng?q=$projectLat,$projectLng($label)".toUri()
        address != null -> "geo:0,0?q=${Uri.encode(address)}".toUri()
        else -> null
    }
}

@Composable
private fun recurrenceLabel(task: TaskDto): String? {
    if (!task.isRecurring) return null
    val locale = appLocale()
    val frequency = task.recurFrequency ?: 1
    val rule = when (task.recurType) {
        "daily" -> if (frequency == 1) stringResource(R.string.recur_every_day) else pluralStringResource(R.plurals.recur_every_days, frequency, frequency)
        "weekly" -> if (frequency == 1) stringResource(R.string.recur_every_week) else pluralStringResource(R.plurals.recur_every_weeks, frequency, frequency)
        "monthly" -> if (frequency == 1) stringResource(R.string.recur_every_month) else pluralStringResource(R.plurals.recur_every_months, frequency, frequency)
        else -> null
    }
    if (!task.isSeriesRoot || rule == null) return stringResource(R.string.recur_part_of_series)
    val end = task.recurEndDate?.toDateOrNull()?.mediumLabel(locale) ?: return rule
    return stringResource(R.string.recur_until, rule, end)
}

@Composable
private fun Details(task: TaskDto, refs: References) {
    val locale = appLocale()
    val context = LocalContext.current
    val start = task.startDate.toDateOrNull()
    val end = task.lastDate.toDateOrNull()
    val project = task.projectOf(refs)
    Card {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            val dates = when {
                start == null -> task.startDate
                task.isMultiDay && end != null -> stringResource(R.string.from_to, start.longLabel(locale, false), end.longLabel(locale, false))
                else -> start.longLabel(locale)
            }
            DetailRow(Icons.Outlined.CalendarToday, stringResource(R.string.field_date), dates)
            DetailRow(Icons.Outlined.Schedule, stringResource(R.string.field_time), task.timeLabel())
            recurrenceLabel(task)?.let { DetailRow(Icons.Outlined.Repeat, stringResource(R.string.field_recurrence), it) }
            task.users.forEach { user -> UserRow(user) }
            task.placeLabel?.let { place ->
                val map = mapUri(task, project)
                DetailRow(Icons.Outlined.Place, stringResource(R.string.field_place), place, link = map != null, onClick = map?.let { uri ->
                    { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) } }
                })
            }
            (task.resource?.name ?: refs.resource(task.resourceId)?.name)?.let {
                DetailRow(Icons.Outlined.Inventory2, stringResource(R.string.field_resource), it)
            }
            task.link?.takeIf { it.isNotBlank() }?.let { link ->
                // A link is only followed to a web page
                val web = link.startsWith("http://", ignoreCase = true) || link.startsWith("https://", ignoreCase = true)
                DetailRow(Icons.Outlined.Link, stringResource(R.string.field_link), link, link = web, onClick = if (web) {
                    { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri())) } }
                } else null)
            }
        }
    }
    task.notes?.takeIf { it.isNotBlank() }?.let { notes ->
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(Icons.AutoMirrored.Outlined.Notes, stringResource(R.string.field_notes))
                SelectionContainer { Text(notes, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), content = content)
}

@Composable
private fun SectionTitle(icon: ImageVector, title: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UserRow(user: UserDto) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        UserAvatar(user, 24)
        Spacer(Modifier.width(14.dp))
        Text(user.displayName, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DetailRow(
    icon: ImageVector?,
    label: String,
    value: String,
    link: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (leading != null) leading() else if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = if (link) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (link) TextDecoration.Underline else null,
            )
        }
    }
}

@Composable
private fun Attachments(
    task: TaskDto,
    access: Access,
    uploading: Boolean,
    openingFileId: String?,
    onAdd: () -> Unit,
    onOpen: (TaskFile) -> Unit,
    onDelete: (String) -> Unit,
    hint: String,
) {
    val locale = appLocale()
    var toDelete by rememberSaveable { mutableStateOf<String?>(null) }
    val canAdd = access.canUploadFiles && access.canModify(task)
    if (task.files.isEmpty() && !canAdd) return
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(Icons.Outlined.AttachFile, stringResource(R.string.attachments), Modifier.weight(1f))
                if (uploading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (task.files.isEmpty()) {
                Text(stringResource(R.string.attachments_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            task.files.forEach { file ->
                Surface(
                    onClick = { onOpen(file) },
                    enabled = openingFileId == null,
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (openingFileId == file.id) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.Description, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(file.originalName.ifBlank { file.name }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (file.size > 0) Text(file.size.fileSizeLabel(locale), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (access.canDeleteFiles && access.canModify(task)) {
                            IconButton(onClick = { toDelete = file.id }) { Icon(Icons.Outlined.Close, stringResource(R.string.file_delete_label, file.originalName.ifBlank { file.name }), Modifier.size(18.dp)) }
                        }
                    }
                }
            }
            if (canAdd) {
                OutlinedButton(onClick = onAdd, enabled = !uploading, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.UploadFile, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.file_add))
                }
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    val pending = task.files.firstOrNull { it.id == toDelete }
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.file_delete_title)) },
            text = { Text(pending.originalName.ifBlank { pending.name }) },
            confirmButton = {
                Button(onClick = { onDelete(pending.id); toDelete = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
