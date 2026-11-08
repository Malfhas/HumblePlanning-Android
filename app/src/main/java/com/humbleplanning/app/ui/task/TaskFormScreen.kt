package com.humbleplanning.app.ui.task

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.humbleplanning.app.R
import com.humbleplanning.app.data.References
import com.humbleplanning.app.data.TaskDto
import com.humbleplanning.app.ui.AppDatePicker
import com.humbleplanning.app.ui.AppSegmented
import com.humbleplanning.app.ui.Banner
import com.humbleplanning.app.ui.BannerTone
import com.humbleplanning.app.ui.CenteredLoader
import com.humbleplanning.app.ui.ColorDot
import com.humbleplanning.app.ui.EmptyState
import com.humbleplanning.app.ui.PickerItem
import com.humbleplanning.app.ui.PickerSheet
import com.humbleplanning.app.ui.apiErrorMessage
import com.humbleplanning.app.ui.appLocale
import com.humbleplanning.app.ui.humbleSavedViewModel
import com.humbleplanning.app.ui.mediumLabel
import com.humbleplanning.app.ui.parseColor
import com.humbleplanning.app.ui.toDateOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private enum class Pick { Project, Users, Status, Place, Resource }
private enum class DateField { Start, End, RecurEnd }
private enum class TimeField { Start, End }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskFormScreen(taskId: String?, defaultDate: String?, defaultUserId: String?, copyOf: String?, onClose: () -> Unit) {
    val vm = humbleSavedViewModel(key = "form-$taskId-$copyOf-$defaultDate-$defaultUserId") { graph, handle ->
        TaskFormViewModel(graph.repository, handle, taskId, defaultDate, copyOf, defaultUserId)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val refs by vm.references.collectAsStateWithLifecycle()
    val f = state.form
    var picking by rememberSaveable { mutableStateOf<Pick?>(null) }
    var pickingDate by rememberSaveable { mutableStateOf<DateField?>(null) }
    var pickingTime by rememberSaveable { mutableStateOf<TimeField?>(null) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val locale = appLocale()
    LaunchedEffect(state.saved) { if (state.saved) onClose() }
    val close = { if (state.dirty && !state.saved) confirmDiscard = true else onClose() }
    // Only caught when there is something to lose: otherwise the system back gesture keeps its preview
    BackHandler(enabled = state.dirty && !state.saved) { confirmDiscard = true }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    stringResource(when {
                        state.isCopy -> R.string.form_title_copy
                        state.isNew -> R.string.new_task
                        else -> R.string.form_title_edit
                    }),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = { IconButton(onClick = close) { Icon(Icons.Outlined.Close, stringResource(R.string.cancel)) } },
            actions = {
                Button(onClick = vm::save, enabled = !state.saving && !state.loading && !state.loadFailed, shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(end = 12.dp)) {
                    if (state.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text(stringResource(R.string.save))
                }
            },
        )
        if (state.loading) {
            CenteredLoader()
            return@Column
        }
        if (state.loadFailed) {
            EmptyState(
                Icons.Outlined.EventBusy,
                apiErrorMessage(state.apiError),
                action = { OutlinedButton(onClick = onClose) { Text(stringResource(R.string.back)) } },
            )
            return@Column
        }
        val scroll = rememberScrollState()
        // A refusal is shown at the top of the form: the user may be far below
        LaunchedEffect(state.validationError, state.apiError) {
            if (state.validationError != null || state.apiError != null) scroll.animateScrollTo(0)
        }
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(scroll).navigationBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.validationError?.let { Banner(formErrorMessage(it), BannerTone.Error) }
            state.apiError?.let { Banner(apiErrorMessage(it), BannerTone.Error) }

            val project = refs.project(f.projectId) ?: state.existing?.project?.takeIf { it.id == f.projectId }
            PickRow(Icons.Outlined.Folder, stringResource(R.string.field_project), project?.label, parseColor(project?.color), required = true,
                error = state.validationError == FormError.ProjectRequired) { picking = Pick.Project }

            val existing = state.existing
            val canAssign = existing == null || refs.access.canAssign(existing)
            PickRow(
                Icons.Outlined.People,
                stringResource(R.string.field_users),
                f.userIds.takeIf { it.isNotEmpty() }?.let { usersLabel(it, refs, existing) },
                null,
                required = true,
                error = state.validationError == FormError.UsersRequired,
                enabled = canAssign,
            ) { picking = Pick.Users }

            val status = refs.status(f.statusId) ?: state.existing?.status?.takeIf { it.id == f.statusId }
            PickRow(Icons.Outlined.Tune, stringResource(R.string.field_status), status?.name, parseColor(status?.color), required = true,
                error = state.validationError == FormError.StatusRequired) { picking = Pick.Status }

            // When
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.form_when), style = MaterialTheme.typography.titleSmall)
                    ValueButton(Icons.Outlined.CalendarToday, stringResource(R.string.field_start) + " *", f.startDate.toDateOrNull()?.mediumLabel(locale) ?: f.startDate) { pickingDate = DateField.Start }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ValueButton(
                            Icons.Outlined.CalendarToday,
                            stringResource(R.string.field_end),
                            f.endDate.toDateOrNull()?.mediumLabel(locale) ?: stringResource(R.string.form_same_day),
                            Modifier.weight(1f),
                        ) { pickingDate = DateField.End }
                        if (f.endDate.isNotEmpty()) {
                            IconButton(onClick = { vm.update { it.copy(endDate = "") } }) { Icon(Icons.Outlined.Close, stringResource(R.string.form_clear_end)) }
                        }
                    }
                    AppSegmented(
                        listOf(
                            DayMode.AllDay to stringResource(R.string.all_day),
                            DayMode.Morning to stringResource(R.string.morning),
                            DayMode.Afternoon to stringResource(R.string.afternoon),
                            DayMode.Times to stringResource(R.string.form_times),
                        ),
                        f.mode,
                    ) { mode -> vm.update { it.copy(mode = mode) } }
                    when (f.mode) {
                        DayMode.AllDay -> Unit
                        DayMode.Morning -> Text("${refs.morningStart} – ${refs.morningEnd}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        DayMode.Afternoon -> Text("${refs.afternoonStart} – ${refs.afternoonEnd}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        DayMode.Times -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ValueButton(Icons.Outlined.Schedule, stringResource(R.string.field_start_time), f.startTime.ifBlank { "--:--" }, Modifier.weight(1f)) { pickingTime = TimeField.Start }
                            ValueButton(Icons.Outlined.Schedule, stringResource(R.string.field_end_time), f.endTime.ifBlank { "--:--" }, Modifier.weight(1f)) { pickingTime = TimeField.End }
                        }
                    }
                }
            }

            OutlinedTextField(
                f.title, { v -> vm.update { it.copy(title = v.take(255)) } }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.field_title)) }, singleLine = true, shape = MaterialTheme.shapes.medium,
            )

            if (refs.placesEnabled) {
                val place = refs.place(f.placeId)
                PickRow(Icons.Outlined.Place, stringResource(R.string.field_place), place?.name, parseColor(place?.color)) { picking = Pick.Place }
                OutlinedTextField(
                    f.placeText,
                    { v -> vm.update { it.copy(placeText = v.take(255), placeId = if (v.isNotBlank()) "" else it.placeId) } },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.field_place_text)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
            }
            if (refs.resourcesEnabled) {
                val resource = refs.resource(f.resourceId)
                PickRow(Icons.Outlined.Inventory2, stringResource(R.string.field_resource), resource?.name ?: state.existing?.resource?.takeIf { it.id == f.resourceId }?.name, parseColor(resource?.color)) { picking = Pick.Resource }
            }
            OutlinedTextField(
                f.link, { v -> vm.update { it.copy(link = v) } }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_link)) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), shape = MaterialTheme.shapes.medium,
                isError = state.validationError == FormError.LinkInvalid,
            )
            SwitchRow(stringResource(R.string.milestone), stringResource(R.string.milestone_help), f.milestone) { v -> vm.update { it.copy(milestone = v) } }
            if (existing != null && refs.access.canLock(existing)) {
                SwitchRow(stringResource(R.string.form_lock), stringResource(R.string.form_lock_help), f.locked) { v -> vm.update { it.copy(locked = v) } }
            }
            OutlinedTextField(
                f.notes, { v -> vm.update { it.copy(notes = v) } }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_notes)) },
                minLines = 3, shape = MaterialTheme.shapes.medium,
            )

            RecurrenceSection(state, onChange = vm::update, onPickEnd = { pickingDate = DateField.RecurEnd })

            if (state.isNew) {
                Text(stringResource(R.string.form_files_after), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(24.dp))
        }
    }

    // Choice lists
    when (picking) {
        Pick.Project -> PickerSheet(
            stringResource(R.string.field_project),
            refs.projects.map { PickerItem(it.id, it.label, parseColor(it.color), it.group?.name.orEmpty()) },
            setOf(f.projectId), multiple = false,
            onDismiss = { picking = null },
            onConfirm = { ids -> vm.update { it.copy(projectId = ids.firstOrNull().orEmpty()) }; picking = null },
        )
        Pick.Users -> {
            // Who already is on the task stays listed, even outside what this user could assign
            val assignable = refs.access.assignableUsers(refs.users)
            val others = state.existing?.users.orEmpty().filter { user -> assignable.none { it.id == user.id } }
            PickerSheet(
                stringResource(R.string.field_users),
                (assignable + others).map { user -> PickerItem(user.id, user.displayName, parseColor(user.color), refs.teams.firstOrNull { it.id == user.teamId }?.name.orEmpty()) },
                f.userIds.toSet(), multiple = true,
                onDismiss = { picking = null },
                onConfirm = { ids -> vm.update { it.copy(userIds = ids.toList()) }; picking = null },
            )
        }
        Pick.Status -> PickerSheet(
            stringResource(R.string.field_status),
            refs.statuses.map { PickerItem(it.id, it.name, parseColor(it.color)) },
            setOf(f.statusId), multiple = false,
            onDismiss = { picking = null },
            onConfirm = { ids -> vm.update { it.copy(statusId = ids.firstOrNull().orEmpty()) }; picking = null },
        )
        Pick.Place -> PickerSheet(
            stringResource(R.string.field_place),
            refs.places.map { PickerItem(it.id, it.name, parseColor(it.color)) },
            setOf(f.placeId), multiple = false, allowNone = true,
            onDismiss = { picking = null },
            onConfirm = { ids ->
                val id = ids.firstOrNull().orEmpty()
                vm.update { it.copy(placeId = id, placeText = if (id.isNotEmpty()) "" else it.placeText) }
                picking = null
            },
        )
        Pick.Resource -> PickerSheet(
            stringResource(R.string.field_resource),
            refs.resources.map { PickerItem(it.id, it.name, parseColor(it.color)) },
            setOf(f.resourceId), multiple = false, allowNone = true,
            onDismiss = { picking = null },
            onConfirm = { ids -> vm.update { it.copy(resourceId = ids.firstOrNull().orEmpty()) }; picking = null },
        )
        null -> Unit
    }

    pickingDate?.let { field ->
        val current = when (field) {
            DateField.Start -> f.startDate
            DateField.End -> f.endDate.ifEmpty { f.startDate }
            DateField.RecurEnd -> f.recurEndDate.ifEmpty { f.startDate }
        }.toDateOrNull() ?: LocalDate.now()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = current.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = null },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        vm.update {
                            when (field) {
                                DateField.Start -> it.copy(startDate = date, endDate = if (it.endDate.isNotEmpty() && it.endDate < date) "" else it.endDate)
                                DateField.End -> it.copy(endDate = if (date == it.startDate) "" else date)
                                DateField.RecurEnd -> it.copy(recurEndDate = date)
                            }
                        }
                    }
                    pickingDate = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = null }) { Text(stringResource(R.string.cancel)) } },
        ) { AppDatePicker(pickerState) }
    }

    pickingTime?.let { field ->
        val current = (if (field == TimeField.Start) f.startTime else f.endTime).ifBlank { if (field == TimeField.Start) refs.morningStart else refs.morningEnd }
        val timeState = rememberTimePickerState(current.substringBefore(':').toIntOrNull() ?: 8, current.substringAfter(':').take(2).toIntOrNull() ?: 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingTime = null },
            title = { Text(stringResource(if (field == TimeField.Start) R.string.field_start_time else R.string.field_end_time)) },
            text = { TimePicker(timeState) },
            confirmButton = {
                TextButton(onClick = {
                    val value = "%02d:%02d".format(java.util.Locale.ROOT, timeState.hour, timeState.minute)
                    vm.update { if (field == TimeField.Start) it.copy(startTime = value) else it.copy(endTime = value) }
                    pickingTime = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingTime = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.form_discard_title)) },
            text = { Text(stringResource(R.string.form_discard_text)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text(stringResource(R.string.form_discard)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.form_keep_editing)) } },
        )
    }
}

@Composable
private fun usersLabel(ids: List<String>, refs: References, existing: TaskDto?): String {
    val first = ids.first().let { id -> refs.user(id)?.displayName ?: existing?.users?.firstOrNull { it.id == id }?.displayName }
    return if (ids.size == 1 && first != null) first else pluralStringResource(R.plurals.n_people, ids.size, ids.size)
}

/** Repeat on a new task or a series root; choice to change the whole series on an occurrence. */
@Composable
private fun RecurrenceSection(state: TaskFormState, onChange: ((TaskForm) -> TaskForm) -> Unit, onPickEnd: () -> Unit) {
    val f = state.form
    val locale = appLocale()
    val existing = state.existing
    if (existing?.isRecurring == true) {
        SwitchRow(stringResource(R.string.form_apply_all), stringResource(R.string.form_apply_all_help), f.applyToAll) { v -> onChange { it.copy(applyToAll = v) } }
    }
    if (existing != null && !existing.isSeriesRoot) return
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // A series exists from its root on: it cannot be turned back into a single task here
            SwitchRow(stringResource(R.string.form_repeat), null, f.repeat, enabled = existing == null) { v -> onChange { it.copy(repeat = v) } }
            if (f.repeat) {
                AppSegmented(
                    listOf(
                        "daily" to stringResource(R.string.recur_daily),
                        "weekly" to stringResource(R.string.recur_weekly),
                        "monthly" to stringResource(R.string.recur_monthly),
                    ),
                    f.recurType,
                ) { type -> onChange { it.copy(recurType = type) } }
                OutlinedTextField(
                    if (f.recurFrequency == 0) "" else f.recurFrequency.toString(),
                    { v -> onChange { it.copy(recurFrequency = v.filter(Char::isDigit).take(2).toIntOrNull() ?: 0) } },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.form_repeat_every)) },
                    supportingText = { Text(stringResource(R.string.form_repeat_every_help)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = state.validationError == FormError.RecurrenceFrequency,
                    shape = MaterialTheme.shapes.medium,
                )
                ValueButton(
                    Icons.Outlined.EventRepeat,
                    stringResource(R.string.form_repeat_until) + " *",
                    f.recurEndDate.toDateOrNull()?.mediumLabel(locale) ?: stringResource(R.string.choose),
                    onClick = onPickEnd,
                )
                Text(stringResource(R.string.form_repeat_exception), style = MaterialTheme.typography.titleSmall)
                AppSegmented(
                    listOf(
                        "skip" to stringResource(R.string.form_exception_skip),
                        "shift" to stringResource(R.string.form_exception_shift),
                        "include" to stringResource(R.string.form_exception_include),
                    ),
                    f.recurException,
                ) { exception -> onChange { it.copy(recurException = exception) } }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, null, enabled = enabled)
    }
}

@Composable
private fun PickRow(icon: ImageVector, label: String, value: String?, color: Color?, required: Boolean = false, error: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(label + if (required) " *" else "", style = MaterialTheme.typography.labelMedium, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (value != null && color != null) {
                        ColorDot(color, 10)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        value ?: stringResource(R.string.choose),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (enabled) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ValueButton(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest, contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun formErrorMessage(error: FormError): String = stringResource(
    when (error) {
        FormError.ProjectRequired -> R.string.val_project
        FormError.UsersRequired -> R.string.val_users
        FormError.StatusRequired -> R.string.val_status
        FormError.StartDateRequired -> R.string.val_start
        FormError.EndDateBeforeStart -> R.string.val_end_before_start
        FormError.TimesRequired -> R.string.val_times
        FormError.EndTimeBeforeStart -> R.string.val_end_time
        FormError.TextTooLong -> R.string.val_text_long
        FormError.LinkInvalid -> R.string.val_link
        FormError.RecurrenceEndRequired -> R.string.val_recur_end
        FormError.RecurrenceEndBeforeStart -> R.string.val_recur_end_before
        FormError.RecurrenceFrequency -> R.string.val_recur_frequency
    }
)
