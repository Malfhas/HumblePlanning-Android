package com.humbleplanning.app.ui.planning

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.humbleplanning.app.R
import com.humbleplanning.app.data.References
import com.humbleplanning.app.data.TaskDto
import com.humbleplanning.app.ui.AnimatedBanner
import com.humbleplanning.app.ui.AppSegmented
import com.humbleplanning.app.ui.Banner
import com.humbleplanning.app.ui.BannerTone
import com.humbleplanning.app.ui.EmptyState
import com.humbleplanning.app.ui.LocalHumbleColors
import com.humbleplanning.app.ui.OfflineBanner
import com.humbleplanning.app.ui.apiErrorMessage
import com.humbleplanning.app.ui.appLocale
import com.humbleplanning.app.ui.longLabel
import com.humbleplanning.app.ui.monday
import com.humbleplanning.app.ui.monthLabel
import com.humbleplanning.app.ui.narrowLabel
import com.humbleplanning.app.ui.parseColor
import com.humbleplanning.app.ui.shortLabel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs

private const val PAGES = 20_000
private const val CENTER = PAGES / 2

/** What a screen reader says for a day: the dots and colours of the cell are not read. */
@Composable
private fun dayDescription(day: LocalDate, taskCount: Int, refs: References): String {
    val locale = appLocale()
    val parts = buildList {
        add(day.longLabel(locale))
        if (day == LocalDate.now()) add(stringResource(R.string.today))
        refs.holiday(day.toString())?.let { add(it.label?.ifBlank { null } ?: stringResource(R.string.holiday)) }
        add(if (taskCount > 0) pluralStringResource(R.plurals.n_tasks, taskCount, taskCount) else stringResource(R.string.nothing_planned))
    }
    return parts.joinToString(", ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanningScreen(vm: PlanningViewModel, onOpenTask: (String) -> Unit, onNewTask: (date: String, userId: String) -> Unit, bottomPadding: Dp) {
    val state by vm.state.collectAsStateWithLifecycle()
    val refs by vm.references.collectAsStateWithLifecycle()
    val titleMode by vm.cardTitle.collectAsStateWithLifecycle()
    val offline by vm.offline.collectAsStateWithLifecycle()
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    val locale = appLocale()
    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose { }
    }
    val showPerson = state.filter.people != PeopleFilter.Mine

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // Header: month, today, filters, view
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier.heightIn(min = 48.dp).clip(MaterialTheme.shapes.small).clickable(role = Role.Button) { showDatePicker = true }.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedContent(state.selectedDate.monthLabel(locale), Modifier.weight(1f, fill = false), transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "month") {
                        Text(it, style = MaterialTheme.typography.headlineSmall)
                    }
                    Icon(Icons.Outlined.ExpandMore, stringResource(R.string.choose_date), Modifier.padding(start = 2.dp))
                }
            }
            TextButton(onClick = vm::goToday) {
                Icon(Icons.Outlined.Today, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.today))
            }
            IconButton(onClick = { showFilters = true }) {
                BadgedBox(badge = { if (state.filter.activeCount > 0) Badge { Text(state.filter.activeCount.toString()) } }) {
                    Icon(Icons.Outlined.FilterList, stringResource(R.string.filters))
                }
            }
        }
        AppSegmented(
            listOf(
                PlanningMode.Day to stringResource(R.string.view_day),
                PlanningMode.Week to stringResource(R.string.view_week),
                PlanningMode.Month to stringResource(R.string.view_month),
                PlanningMode.Grid to stringResource(R.string.view_grid),
            ),
            state.mode,
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            vm::setMode,
        )
        if (state.mode == PlanningMode.Grid) GridToolbar(state.cellWidth, onMonth = vm::moveMonth, onWidth = { vm.setCellWidth(it) })
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            // No connection: offline copy loaded, or phone network down (data already on screen)
            AnimatedBanner(state.offlineSinceMillis != null || offline) {
                OfflineBanner(state.offlineSinceMillis, Modifier.padding(top = 8.dp))
            }
            AnimatedBanner(state.error != null && state.offlineSinceMillis == null && !offline) {
                Banner(apiErrorMessage(state.error), BannerTone.Error, Modifier.padding(top = 8.dp))
            }
        }

        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.weight(1f)) {
            when (state.mode) {
                PlanningMode.Day -> DayMode(state, refs, titleMode, showPerson, vm::select, onOpenTask, bottomPadding)
                PlanningMode.Week -> WeekMode(state, refs, titleMode, showPerson, vm::select, onOpenTask, bottomPadding)
                PlanningMode.Month -> MonthMode(state, refs, titleMode, showPerson, vm::select, onOpenTask, bottomPadding)
                PlanningMode.Grid -> GridMode(
                    state, refs, titleMode, onOpenTask,
                    onNewTask = { date, userId -> onNewTask(date.toString(), userId) },
                    onCellWidth = vm::setCellWidth,
                    onShowEveryone = { vm.setFilter(state.filter.copy(people = PeopleFilter.All)) },
                    bottomPadding = bottomPadding,
                )
            }
        }
    }

    if (showFilters) {
        FilterSheet(state.filter, refs, vm.currentUserId, onDismiss = { showFilters = false }, onApply = {
            showFilters = false
            vm.setFilter(it)
        })
    }
    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = state.selectedDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { vm.select(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    showDatePicker = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(pickerState) }
    }
}

/** Grid only: previous and next month, width of the days (also changed by pinching the grid). */
@Composable
private fun GridToolbar(cellWidth: Float, onMonth: (Long) -> Unit, onWidth: (Float) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onMonth(-1) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.grid_previous_month)) }
        IconButton(onClick = { onMonth(1) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.grid_next_month)) }
        Text(
            stringResource(R.string.grid_pinch_hint),
            Modifier.weight(1f).padding(horizontal = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.SwapHoriz, stringResource(R.string.grid_width)) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                val current = GRID_WIDTHS.minBy { abs(it.first - cellWidth) }.first
                GRID_WIDTHS.forEach { (width, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        onClick = {
                            menu = false
                            onWidth(width)
                        },
                        leadingIcon = { if (width == current) Icon(Icons.Outlined.Check, null) else Spacer(Modifier.size(24.dp)) },
                    )
                }
            }
        }
    }
}

private val GRID_WIDTHS = listOf(
    32f to R.string.grid_width_compact,
    GRID_DEFAULT_WIDTH to R.string.grid_width_normal,
    96f to R.string.grid_width_wide,
)

/** "Endless" pager tied to the chosen date: swiping changes the date, a date chosen elsewhere scrolls. */
@Composable
private fun rememberLinkedPager(
    selected: LocalDate,
    pageOf: (LocalDate) -> Int,
    dateOf: (Int) -> LocalDate,
    onSelect: (LocalDate) -> Unit,
): PagerState {
    val pager = rememberPagerState(initialPage = pageOf(selected)) { PAGES }
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    LaunchedEffect(selected) {
        val target = pageOf(selected)
        if (pager.currentPage != target) {
            if (abs(pager.currentPage - target) > 2) pager.scrollToPage(target) else pager.animateScrollToPage(target)
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            if (pageOf(currentSelected) != page) currentOnSelect(dateOf(page))
        }
    }
    return pager
}

/** Name of the holiday next to the title of a day. */
@Composable
private fun HolidayLabel(day: LocalDate, refs: References, modifier: Modifier = Modifier) {
    val holiday = refs.holiday(day.toString()) ?: return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Celebration, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(
            holiday.label?.ifBlank { null } ?: stringResource(R.string.holiday),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- Day

@Composable
private fun DayMode(
    state: PlanningState,
    refs: References,
    titleMode: String,
    showPerson: Boolean,
    onSelect: (LocalDate) -> Unit,
    onOpenTask: (String) -> Unit,
    bottomPadding: Dp,
) {
    val anchor = remember { LocalDate.now() }
    Column(Modifier.fillMaxSize()) {
        WeekStrip(state, refs, anchor, onSelect)
        val pager = rememberLinkedPager(
            state.selectedDate,
            pageOf = { CENTER + ChronoUnit.DAYS.between(anchor, it).toInt() },
            dateOf = { anchor.plusDays((it - CENTER).toLong()) },
            onSelect = onSelect,
        )
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { it }) { page ->
            val day = anchor.plusDays((page - CENTER).toLong())
            DayList(day, state, refs, titleMode, showPerson, onOpenTask, bottomPadding)
        }
    }
}

@Composable
private fun DayList(
    day: LocalDate,
    state: PlanningState,
    refs: References,
    titleMode: String,
    showPerson: Boolean,
    onOpenTask: (String) -> Unit,
    bottomPadding: Dp,
) {
    val locale = appLocale()
    val tasks = state.tasksOn(day)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 88.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "title") {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(day.longLabel(locale), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                    if (tasks.isNotEmpty()) {
                        Text(
                            pluralStringResource(R.plurals.n_tasks, tasks.size, tasks.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HolidayLabel(day, refs)
            }
        }
        if (tasks.isEmpty() && state.covers(day) && !state.loading) {
            item(key = "empty") { EmptyState(Icons.Outlined.EventAvailable, stringResource(R.string.nothing_planned), stringResource(R.string.nothing_planned_help)) }
        }
        items(tasks, key = { it.id }) { task ->
            TaskCard(task, refs, titleMode, showPerson, day, onClick = { onOpenTask(task.id) }, modifier = Modifier.animateItem())
        }
    }
}

/** Week strip: days, chosen day, today, dots for the days with tasks. */
@Composable
private fun WeekStrip(state: PlanningState, refs: References, anchor: LocalDate, onSelect: (LocalDate) -> Unit) {
    val locale = appLocale()
    val anchorMonday = anchor.monday()
    val pager = rememberLinkedPager(
        state.selectedDate.monday(),
        pageOf = { CENTER + ChronoUnit.WEEKS.between(anchorMonday, it.monday()).toInt() },
        dateOf = { anchorMonday.plusWeeks((it - CENTER).toLong()) },
        onSelect = { monday -> onSelect(monday.plusDays((state.selectedDate.dayOfWeek.value - 1).toLong())) },
    )
    HorizontalPager(pager, Modifier.fillMaxWidth().padding(vertical = 4.dp), key = { it }) { page ->
        val monday = anchorMonday.plusWeeks((page - CENTER).toLong())
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            for (i in 0..6) {
                val day = monday.plusDays(i.toLong())
                DayChip(day, day == state.selectedDate, state.tasksOn(day), refs, locale, Modifier.weight(1f)) { onSelect(day) }
            }
        }
    }
}

@Composable
private fun DayChip(day: LocalDate, selected: Boolean, tasks: List<TaskDto>, refs: References, locale: java.util.Locale, modifier: Modifier, onClick: () -> Unit) {
    val today = day == LocalDate.now()
    val colors = LocalHumbleColors.current
    val scheme = MaterialTheme.colorScheme
    val description = dayDescription(day, tasks.size, refs)
    Column(
        modifier.padding(horizontal = 2.dp).heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium)
            .background(if (selected) scheme.primary else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            day.dayOfWeek.shortLabel(locale),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            day.dayOfMonth.toString(),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (today || selected) FontWeight.Bold else FontWeight.Medium),
            color = when {
                selected -> scheme.onPrimary
                today -> colors.today
                else -> scheme.onSurface
            },
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.height(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            tasks.map { it.projectId }.distinct().take(3).forEach {
                Box(Modifier.size(5.dp).clip(CircleShape).background(if (selected) scheme.onPrimary else scheme.onSurfaceVariant))
            }
        }
    }
}

private fun isWeekend(day: DayOfWeek) = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY

// ---------------------------------------------------------------- Week

@Composable
private fun WeekMode(
    state: PlanningState,
    refs: References,
    titleMode: String,
    showPerson: Boolean,
    onSelect: (LocalDate) -> Unit,
    onOpenTask: (String) -> Unit,
    bottomPadding: Dp,
) {
    val locale = appLocale()
    val anchorMonday = remember { LocalDate.now().monday() }
    val pager = rememberLinkedPager(
        state.selectedDate.monday(),
        pageOf = { CENTER + ChronoUnit.WEEKS.between(anchorMonday, it.monday()).toInt() },
        dateOf = { anchorMonday.plusWeeks((it - CENTER).toLong()) },
        onSelect = { monday -> onSelect(monday.plusDays((state.selectedDate.dayOfWeek.value - 1).toLong())) },
    )
    HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { it }) { page ->
        val monday = anchorMonday.plusWeeks((page - CENTER).toLong())
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding + 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "week") {
                Text(
                    stringResource(R.string.week_of, monday.longLabel(locale, false), monday.weekOfYear()),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            for (i in 0..6) {
                val day = monday.plusDays(i.toLong())
                val tasks = state.tasksOn(day)
                if (tasks.isEmpty() && isWeekend(day.dayOfWeek) && refs.holiday(day.toString()) == null) continue
                item(key = "d$i") { WeekDayHeader(day, day == state.selectedDate, tasks.size, refs) { onSelect(day) } }
                if (tasks.isEmpty()) {
                    item(key = "e$i") {
                        Text(
                            stringResource(if (state.covers(day)) R.string.nothing_planned else R.string.loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 52.dp, bottom = 8.dp),
                        )
                    }
                }
                items(tasks, key = { "$i-${it.id}" }) { task ->
                    TaskCard(task, refs, titleMode, showPerson, day, compact = true, onClick = { onOpenTask(task.id) }, modifier = Modifier.padding(start = 52.dp).animateItem())
                }
            }
        }
    }
}

@Composable
private fun WeekDayHeader(day: LocalDate, selected: Boolean, count: Int, refs: References, onClick: () -> Unit) {
    val locale = appLocale()
    val today = day == LocalDate.now()
    val colors = LocalHumbleColors.current
    val scheme = MaterialTheme.colorScheme
    val description = dayDescription(day, count, refs)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(if (today) colors.today else if (selected) scheme.surfaceContainerHigh else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, color = if (today) scheme.onPrimary else scheme.onSurface)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) }, style = MaterialTheme.typography.titleSmall)
            HolidayLabel(day, refs)
        }
        if (count > 0) Text(pluralStringResource(R.plurals.n_tasks, count, count), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
    }
}

private fun LocalDate.weekOfYear(): Int = get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)

// ---------------------------------------------------------------- Month

@Composable
private fun MonthMode(
    state: PlanningState,
    refs: References,
    titleMode: String,
    showPerson: Boolean,
    onSelect: (LocalDate) -> Unit,
    onOpenTask: (String) -> Unit,
    bottomPadding: Dp,
) {
    val locale = appLocale()
    val anchor = remember { LocalDate.now().withDayOfMonth(1) }
    val pager = rememberLinkedPager(
        state.selectedDate.withDayOfMonth(1),
        pageOf = { CENTER + ChronoUnit.MONTHS.between(anchor, it.withDayOfMonth(1)).toInt() },
        dateOf = { anchor.plusMonths((it - CENTER).toLong()) },
        onSelect = { first ->
            val day = state.selectedDate.dayOfMonth.coerceAtMost(first.lengthOfMonth())
            onSelect(first.withDayOfMonth(day))
        },
    )
    val tasks = state.tasksOn(state.selectedDate)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding + 88.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "grid") {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Cells close to square on a phone, capped on a tablet or in landscape; they grow with a large font
                val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                val cellHeight = (maxWidth / 7 / 0.92f).coerceIn(48.dp * fontScale, 68.dp * fontScale)
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clearAndSetSemantics { }) {
                        DayOfWeek.entries.forEach {
                            Text(
                                it.narrowLabel(locale),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    HorizontalPager(pager, Modifier.fillMaxWidth(), key = { it }) { page ->
                        val first = anchor.plusMonths((page - CENTER).toLong())
                        MonthGrid(first, state, refs, cellHeight, onSelect)
                    }
                }
            }
        }
        item(key = "title") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(state.selectedDate.longLabel(locale), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                HolidayLabel(state.selectedDate, refs)
            }
        }
        if (tasks.isEmpty() && state.covers(state.selectedDate) && !state.loading) {
            item(key = "empty") {
                Text(stringResource(R.string.nothing_planned), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(tasks, key = { it.id }) { task ->
            TaskCard(task, refs, titleMode, showPerson, state.selectedDate, onClick = { onOpenTask(task.id) }, modifier = Modifier.padding(horizontal = 16.dp).animateItem())
        }
    }
}

@Composable
private fun MonthGrid(first: LocalDate, state: PlanningState, refs: References, cellHeight: Dp, onSelect: (LocalDate) -> Unit) {
    val start = first.monday()
    val colors = LocalHumbleColors.current
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        for (week in 0 until 6) {
            Row(Modifier.fillMaxWidth()) {
                for (d in 0..6) {
                    val day = start.plusDays((week * 7 + d).toLong())
                    val inMonth = day.month == first.month
                    val selected = day == state.selectedDate
                    val today = day == LocalDate.now()
                    val tasks = state.tasksOn(day)
                    val dots = tasks.mapNotNull { it.project?.color ?: refs.project(it.projectId)?.color }.distinct()
                    val holiday = refs.holiday(day.toString())
                    val tint = holiday?.let { (parseColor(it.color) ?: HOLIDAY_DEFAULT).copy(alpha = HOLIDAY_LABEL_ALPHA) } ?: Color.Transparent
                    val description = dayDescription(day, tasks.size, refs)
                    Column(
                        Modifier.weight(1f).height(cellHeight).padding(2.dp).clip(MaterialTheme.shapes.small)
                            .background(if (selected) scheme.primary else tint)
                            .then(if (today && !selected) Modifier.border(1.5.dp, colors.today, MaterialTheme.shapes.small) else Modifier)
                            .selectable(selected = selected, role = Role.Tab) { onSelect(day) }
                            .clearAndSetSemantics { contentDescription = description }
                            .padding(top = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            day.dayOfMonth.toString(),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (today || selected) FontWeight.Bold else FontWeight.Normal),
                            color = when {
                                selected -> scheme.onPrimary
                                today -> colors.today
                                !inMonth -> scheme.onSurfaceVariant
                                else -> scheme.onSurface
                            },
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            dots.take(3).forEach { c ->
                                Box(Modifier.size(6.dp).clip(CircleShape).background(parseColor(c) ?: scheme.outline))
                            }
                        }
                        if (dots.size > 3) {
                            Text("+${dots.size - 3}", style = MaterialTheme.typography.labelSmall, color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
