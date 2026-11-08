package com.humbleplanning.app.ui.planning

import com.humbleplanning.app.data.TaskDto
import com.humbleplanning.app.data.TeamDto
import com.humbleplanning.app.data.UserDto
import com.humbleplanning.app.ui.hhmm
import com.humbleplanning.app.ui.paddedTime
import com.humbleplanning.app.ui.toDateOrNull
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Part of a day a bar covers: the morning and afternoon halves of the web planning. */
enum class DayPart { Whole, Morning, Afternoon }

/**
 * A task on one person's row: columns [first]..[last] of the period (inclusive), its line in the row,
 * and whether it goes on before or after the period.
 */
data class GridBar(
    val task: TaskDto,
    val first: Int,
    val last: Int,
    val lane: Int,
    val part: DayPart,
    val continuesBefore: Boolean,
    val continuesAfter: Boolean,
)

data class GridRow(val user: UserDto, val bars: List<GridBar>, val lanes: Int)

/** People of one team, in the order of the web planning; [team] is null for those without a team. */
data class GridGroup(val team: TeamDto?, val rows: List<GridRow>)

object GridLayout {
    /**
     * Same rule as the web planning (time-slots.ts): no start time = whole day, ends by noon = morning,
     * starts at noon or later = afternoon. Only a one-day task takes half a day.
     */
    fun partOf(task: TaskDto): DayPart {
        if (task.startDate != task.lastDate) return DayPart.Whole
        val start = task.startTime?.takeIf { it.isNotBlank() }?.hhmm()?.paddedTime() ?: return DayPart.Whole
        val end = task.endTime?.takeIf { it.isNotBlank() }?.hhmm()?.paddedTime()
        return when {
            start >= NOON -> DayPart.Afternoon
            end != null && end <= NOON -> DayPart.Morning
            else -> DayPart.Whole
        }
    }

    /**
     * Bars of one row over [days] days from [start]. Each bar takes the first free line, counted in half days:
     * a morning and an afternoon task of the same day share a line, which keeps the rows low.
     */
    fun bars(tasks: List<TaskDto>, start: LocalDate, days: Int): List<GridBar> {
        val lastDay = start.plusDays(days - 1L)
        val placed = mutableListOf<GridBar>()
        val lines = mutableListOf<BooleanArray>()
        val sorted = tasks.mapNotNull { task ->
            val from = task.startDate.toDateOrNull() ?: return@mapNotNull null
            val to = task.lastDate.toDateOrNull()?.takeIf { it >= from } ?: from
            if (to < start || from > lastDay) null else Triple(task, from, to)
        }.sortedWith(
            compareBy<Triple<TaskDto, LocalDate, LocalDate>>(
                { it.second },
                { if (it.first.startTime.isNullOrBlank() || it.second != it.third) 0 else 1 },
                { it.first.startTime?.paddedTime().orEmpty() },
                { it.first.updatedAt.orEmpty() },
                { it.first.id },
            )
        )
        for ((task, from, to) in sorted) {
            val first = ChronoUnit.DAYS.between(start, from).toInt().coerceAtLeast(0)
            val last = ChronoUnit.DAYS.between(start, to).toInt().coerceAtMost(days - 1)
            val part = partOf(task)
            val halfFrom = first * 2 + if (part == DayPart.Afternoon) 1 else 0
            val halfTo = last * 2 + if (part == DayPart.Morning) 0 else 1
            var lane = lines.indexOfFirst { line -> (halfFrom..halfTo).none { line[it] } }
            if (lane < 0) {
                lines += BooleanArray(days * 2)
                lane = lines.lastIndex
            }
            for (h in halfFrom..halfTo) lines[lane][h] = true
            placed += GridBar(task, first, last, lane, part, continuesBefore = (from < start), continuesAfter = (to > lastDay))
        }
        return placed
    }

    /** People shown by the filter: the person filter and the team filter pick the rows. */
    fun people(users: List<UserDto>, filter: PlanningFilter, me: String, teams: List<TeamDto>): List<UserDto> {
        val members = if (filter.specificTeams) teams.filter { it.id in filter.teamIds }.flatMapTo(HashSet()) { it.userIds } else null
        return users.filter { user ->
            val person = when (filter.people) {
                PeopleFilter.All -> true
                PeopleFilter.Mine -> user.id == me
                PeopleFilter.Specific -> user.id in filter.userIds
            }
            person && (members == null || user.id in members || (user.teamId != null && user.teamId in filter.teamIds))
        }
    }

    /**
     * Rows grouped by team, in the default order of the web planning: teams by their order then name,
     * people by their order then name, people without a team last. The project and status filters pick the bars.
     */
    fun groups(
        users: List<UserDto>,
        teams: List<TeamDto>,
        tasks: List<TaskDto>,
        filter: PlanningFilter,
        start: LocalDate,
        days: Int,
    ): List<GridGroup> {
        val byUser = HashMap<String, MutableList<TaskDto>>()
        for (task in tasks) {
            if (filter.specificProjects && task.projectId !in filter.projectIds) continue
            if (filter.specificStatuses && task.statusId !in filter.statusIds) continue
            task.userIds.forEach { byUser.getOrPut(it) { mutableListOf() } += task }
        }
        val teamById = teams.associateBy { it.id }
        val personOrder = compareBy<UserDto>({ it.sortOrder }, { it.displayName.lowercase() })
        return users.groupBy { user -> user.teamId?.let(teamById::get) }
            .entries
            .sortedWith(compareBy({ it.key == null }, { it.key?.sortOrder ?: 0 }, { it.key?.name?.lowercase().orEmpty() }))
            .map { (team, members) ->
                GridGroup(team, members.sortedWith(personOrder).map { user ->
                    val bars = bars(byUser[user.id].orEmpty(), start, days)
                    GridRow(user, bars, (bars.maxOfOrNull { it.lane } ?: -1) + 1)
                })
            }
    }

    private const val NOON = "12:00"
}
