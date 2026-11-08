package com.humbleplanning.app.ui.planning

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humbleplanning.app.R
import com.humbleplanning.app.data.References
import com.humbleplanning.app.data.UserDto
import com.humbleplanning.app.ui.EmptyState
import com.humbleplanning.app.ui.LocalHumbleColors
import com.humbleplanning.app.ui.appLocale
import com.humbleplanning.app.ui.contentColor
import com.humbleplanning.app.ui.longLabel
import com.humbleplanning.app.ui.mediumLabel
import com.humbleplanning.app.ui.narrowLabel
import com.humbleplanning.app.ui.parseColor
import com.humbleplanning.app.ui.timeRange
import com.humbleplanning.app.ui.toDateOrNull
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val BAR_HEIGHT = 22.dp
private val BAR_GAP = 3.dp
private val ROW_PADDING = 5.dp
private val ROW_MIN_HEIGHT = 44.dp
private val HEADER_HEIGHT = 48.dp

/** Holidays as on the web planning: its default colour, 19 % behind the label, 8 % over the column. */
val HOLIDAY_DEFAULT = Color(0xFFF97316)
const val HOLIDAY_LABEL_ALPHA = 0.19f
const val HOLIDAY_COLUMN_ALPHA = 0.08f

private fun rowHeight(lanes: Int): Dp = maxOf(ROW_MIN_HEIGHT, ROW_PADDING * 2 + (BAR_HEIGHT + BAR_GAP) * lanes - BAR_GAP)

/** What a day column looks like, worked out once for the whole grid. */
private class DayColumn(val date: LocalDate, val weekend: Boolean, val today: Boolean, val holiday: Color?, val holidayLabel: String?)

/**
 * Team grid: one row per person, grouped by team, one column per day, the tasks as bars, like the planning of
 * the web client. The names and the days stay in place while the grid scrolls; pinching changes the width of the days.
 * A tap on a bar opens the task, a tap on an empty day of a row creates a task there when the role allows it.
 */
@Composable
fun GridMode(
    state: PlanningState,
    refs: References,
    titleMode: String,
    onOpenTask: (String) -> Unit,
    onNewTask: (LocalDate, String) -> Unit,
    onCellWidth: (Float, Boolean) -> Unit,
    onShowEveryone: () -> Unit,
    bottomPadding: Dp,
) {
    val start = state.gridStart
    val days = state.gridDays
    val people = remember(refs.users, state.filter, state.me, refs.teams) { GridLayout.people(refs.users, state.filter, state.me, refs.teams) }
    val groups = remember(people, refs.teams, state.tasks, state.filter, start, days) { GridLayout.groups(people, refs.teams, state.tasks, state.filter, start, days) }
    val columns = remember(start, days, refs.holidays) {
        val today = LocalDate.now()
        List(days) { i ->
            val date = start.plusDays(i.toLong())
            val holiday = refs.holiday(date.toString())
            DayColumn(
                date,
                date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY,
                date == today,
                holiday?.let { parseColor(it.color) ?: HOLIDAY_DEFAULT },
                holiday?.let { it.label?.ifBlank { null } },
            )
        }
    }
    val access = refs.access
    val creatable = remember(refs.users, access) {
        if (access.canCreateTask) access.assignableUsers(refs.users).mapTo(HashSet()) { it.id } else emptySet()
    }
    var collapsed by rememberSaveable { mutableStateOf(listOf<String>()) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val cellWidth = state.cellWidth.dp
    val cellPx = with(density) { cellWidth.toPx() }

    // On a new month or a date chosen elsewhere: the chosen day comes first, after one day of context
    LaunchedEffect(start, state.selectedDate, state.focusRequest) {
        withTimeoutOrNull(1_000) { snapshotFlow { scroll.maxValue }.first { it > 0 } }
        val index = ChronoUnit.DAYS.between(start, state.selectedDate).toInt().coerceIn(0, days - 1)
        scroll.scrollTo(((index - 1).coerceAtLeast(0) * cellPx).roundToInt())
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val nameWidth = if (maxWidth >= 600.dp) 168.dp else 108.dp
        val namePx = with(density) { nameWidth.toPx() }
        val currentWidth by rememberUpdatedState(state.cellWidth)
        val currentOnWidth by rememberUpdatedState(onCellWidth)
        Column(
            Modifier.fillMaxSize().pointerInput(Unit) {
                // Two fingers: the days get wider or narrower around the fingers; one finger scrolls as usual
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var zoomed = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                val before = currentWidth
                                val after = (before * zoom).coerceIn(GRID_MIN_WIDTH, GRID_MAX_WIDTH)
                                val focus = event.calculateCentroid(useCurrent = true).x - namePx
                                val target = (scroll.value + focus) * (after / before) - focus
                                currentOnWidth(after, false)
                                scope.launch {
                                    withFrameNanos { }
                                    scroll.scrollTo(target.roundToInt())
                                }
                                zoomed = true
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (zoomed) currentOnWidth(currentWidth, true)
                }
            },
        ) {
            if (state.filter.people == PeopleFilter.Mine) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.grid_only_you),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onShowEveryone) { Text(stringResource(R.string.grid_show_everyone)) }
                }
            }
            DaysHeader(columns, people.size, nameWidth, cellWidth, scroll)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (groups.isEmpty()) {
                if (state.covers(start) && !state.loading) {
                    EmptyState(Icons.Outlined.Groups, stringResource(R.string.grid_nobody), stringResource(R.string.grid_nobody_help))
                }
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding + 88.dp)) {
                for (group in groups) {
                    val key = group.team?.id ?: NO_TEAM
                    val closed = key in collapsed
                    if (groups.size > 1 || group.team != null) {
                        stickyHeader(key = "team-$key") {
                            TeamHeader(group, closed) { collapsed = if (closed) collapsed - key else collapsed + key }
                        }
                    }
                    if (!closed) {
                        items(group.rows, key = { "$key-${it.user.id}" }) { row ->
                            PersonRow(
                                row, columns, refs, titleMode, nameWidth, cellWidth, cellPx, scroll,
                                isMe = row.user.id == state.me,
                                canCreate = row.user.id in creatable,
                                onOpenTask = onOpenTask,
                                onNewTask = { day -> onNewTask(start.plusDays(day.toLong()), row.user.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val NO_TEAM = "none"

/** Week numbers and days; scrolls with the rows, the corner above the names stays. */
@Composable
private fun DaysHeader(columns: List<DayColumn>, people: Int, nameWidth: Dp, cellWidth: Dp, scroll: ScrollState) {
    val locale = appLocale()
    val scheme = MaterialTheme.colorScheme
    val colors = LocalHumbleColors.current
    Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT).background(scheme.surface)) {
        Box(Modifier.width(nameWidth).fillMaxHeight().padding(start = 12.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                pluralStringResource(R.plurals.n_people, people, people),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Row(Modifier.weight(1f).horizontalScroll(scroll)) {
            columns.forEachIndexed { index, column ->
                val date = column.date
                val description = buildList {
                    add(date.longLabel(locale))
                    if (column.today) add(stringResource(R.string.today))
                    if (column.holiday != null) add(column.holidayLabel ?: stringResource(R.string.holiday))
                }.joinToString(", ")
                Column(
                    Modifier.width(cellWidth).fillMaxHeight()
                        .background(
                            when {
                                column.today -> scheme.primaryContainer
                                column.holiday != null -> column.holiday.copy(alpha = HOLIDAY_LABEL_ALPHA)
                                column.weekend -> scheme.surfaceContainerHigh
                                else -> Color.Transparent
                            }
                        )
                        .clearAndSetSemantics { contentDescription = description },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // The week number on Mondays and on the first column
                    val week = if (date.dayOfWeek == DayOfWeek.MONDAY || index == 0) stringResource(R.string.grid_week, date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)) else ""
                    Text(week, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                    val label = if (cellWidth >= 40.dp) "${date.dayOfWeek.narrowLabel(locale)} ${date.dayOfMonth}" else date.dayOfMonth.toString()
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (column.today) FontWeight.Bold else FontWeight.Medium),
                        color = if (column.today) colors.today else scheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun TeamHeader(group: GridGroup, closed: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val color = parseColor(group.team?.color) ?: scheme.outline
    val name = group.team?.name ?: stringResource(R.string.grid_no_team)
    val count = pluralStringResource(R.plurals.n_people, group.rows.size, group.rows.size)
    val state = stringResource(if (closed) R.string.grid_collapsed else R.string.grid_expanded)
    Row(
        Modifier.fillMaxWidth().height(36.dp).background(scheme.surfaceContainer)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { heading(); stateDescription = state }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 4.dp, height = 18.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(8.dp))
        Text(name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Text(count, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
        Icon(if (closed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, null, tint = scheme.onSurfaceVariant)
    }
}

@Composable
private fun PersonRow(
    row: GridRow,
    columns: List<DayColumn>,
    refs: References,
    titleMode: String,
    nameWidth: Dp,
    cellWidth: Dp,
    cellPx: Float,
    scroll: ScrollState,
    isMe: Boolean,
    canCreate: Boolean,
    onOpenTask: (String) -> Unit,
    onNewTask: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val height = rowHeight(row.lanes)
    val lines = scheme.outlineVariant
    val weekend = scheme.surfaceContainerHigh.copy(alpha = 0.6f)
    val today = scheme.primaryContainer.copy(alpha = 0.45f)
    Row(Modifier.fillMaxWidth().height(height)) {
        PersonCell(row.user, isMe, nameWidth)
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
            Box(
                Modifier.width(cellWidth * columns.size).fillMaxHeight()
                    .drawBehind {
                        columns.forEachIndexed { i, column ->
                            val fill = when {
                                column.today -> today
                                column.holiday != null -> column.holiday.copy(alpha = HOLIDAY_COLUMN_ALPHA)
                                column.weekend -> weekend
                                else -> null
                            }
                            if (fill != null) drawRect(fill, Offset(i * cellPx, 0f), Size(cellPx, size.height))
                            val strong = column.date.dayOfWeek == DayOfWeek.MONDAY
                            drawLine(lines.copy(alpha = if (strong) 1f else 0.5f), Offset(i * cellPx, 0f), Offset(i * cellPx, size.height), strokeWidth = if (strong) 1.5f else 1f)
                        }
                        drawLine(lines, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f))
                    }
                    .then(
                        if (canCreate) {
                            Modifier.pointerInput(cellPx) {
                                detectTapGestures { offset -> onNewTask((offset.x / cellPx).toInt().coerceIn(0, columns.size - 1)) }
                            }
                        } else Modifier
                    ),
            ) {
                row.bars.forEach { bar -> Bar(bar, refs, titleMode, cellWidth, scroll, row.user, onOpenTask) }
            }
        }
    }
}

@Composable
private fun PersonCell(user: UserDto, isMe: Boolean, width: Dp) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.width(width).fillMaxHeight().background(scheme.surface)
            .drawBehind {
                drawLine(scheme.outlineVariant, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f))
                drawLine(scheme.outlineVariant, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height))
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(user, 24)
        Spacer(Modifier.width(6.dp))
        Text(
            user.displayName,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium, lineHeight = 14.sp),
            color = if (isMe) scheme.primary else scheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Bar(bar: GridBar, refs: References, titleMode: String, cellWidth: Dp, scroll: ScrollState, user: UserDto, onOpenTask: (String) -> Unit) {
    val task = bar.task
    val scheme = MaterialTheme.colorScheme
    val locale = appLocale()
    val color = parseColor(task.projectOf(refs)?.color) ?: parseColor(task.statusOf(refs)?.color) ?: scheme.primary
    val half = cellWidth / 2
    val x = cellWidth * bar.first + if (bar.part == DayPart.Afternoon) half else 0.dp
    val width = cellWidth * (bar.last - bar.first + 1) - if (bar.part == DayPart.Whole) 0.dp else half
    val y = ROW_PADDING + (BAR_HEIGHT + BAR_GAP) * bar.lane
    val title = task.cardTitle(refs, titleMode)
    val description = buildList {
        add(title)
        task.title?.takeIf { it.isNotBlank() && it != title }?.let(::add)
        add(user.displayName)
        val first = task.startDate.toDateOrNull()
        val last = task.lastDate.toDateOrNull()
        if (first != null) add(if (last != null && last != first) "${first.mediumLabel(locale)} – ${last.mediumLabel(locale)}" else first.longLabel(locale))
        task.timeRange()?.let(::add)
        task.statusOf(refs)?.name?.let(::add)
        if (task.isLocked) add(stringResource(R.string.a11y_locked))
    }.joinToString(", ")
    val text = color.contentColor()
    val density = LocalDensity.current
    // Where the content of the bar starts, in the scrolled grid
    val contentStart = with(density) { (x + 1.dp + 4.dp).toPx() }
    val keep = with(density) { MIN_TITLE_WIDTH.roundToPx() }
    val startHidden by remember(contentStart) { derivedStateOf { scroll.value > contentStart } }
    Row(
        Modifier.offset { IntOffset(x.roundToPx() + 1.dp.roundToPx(), y.roundToPx()) }
            .width(width - 2.dp).height(BAR_HEIGHT)
            .clip(RoundedCornerShape(5.dp))
            .background(color)
            .clickable(role = Role.Button) { onOpenTask(task.id) }
            .clearAndSetSemantics { contentDescription = description }
            .padding(horizontal = 4.dp)
            .followVisibleStart(scroll, contentStart, keep),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bar.continuesBefore || startHidden) Text("‹", color = text, style = MaterialTheme.typography.labelMedium)
        if (task.isLocked && width >= 40.dp) Icon(Icons.Outlined.Lock, null, Modifier.size(11.dp), tint = text)
        // Too narrow for a readable text: the colour alone says the day is taken
        if (width >= MIN_TITLE_WIDTH) {
            Text(
                title,
                Modifier.weight(1f).padding(start = 2.dp),
                style = MaterialTheme.typography.labelSmall.withStatus(task.statusOf(refs)),
                color = text,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (bar.continuesAfter) Text("›", color = text, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
    }
}

private val MIN_TITLE_WIDTH = 30.dp

/**
 * When the start of a bar is scrolled out of sight, its content follows the left edge of the grid, so the title of
 * a task over several days stays readable; it stops [keepPx] before the end of the bar. Read at layout: scrolling
 * moves the content without recomposing.
 */
private fun Modifier.followVisibleStart(scroll: ScrollState, startPx: Float, keepPx: Int) = layout { measurable, constraints ->
    val shift = (scroll.value - startPx).roundToInt().coerceIn(0, (constraints.maxWidth - keepPx).coerceAtLeast(0))
    val width = constraints.maxWidth - shift
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(shift, 0) }
}
