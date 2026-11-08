package com.humbleplanning.app.ui.planning

import com.humbleplanning.app.data.TaskDto
import com.humbleplanning.app.data.TeamDto
import com.humbleplanning.app.data.UserDto
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GridLayoutTest {
    private val monday = LocalDate.parse("2026-11-02")

    private fun task(id: String, start: String, end: String? = null, from: String? = null, to: String? = null, users: List<String> = listOf("u1"), project: String = "p1") =
        TaskDto(id = id, startDate = start, endDate = end, startTime = from, endTime = to, projectId = project, users = users.map { UserDto(id = it) })

    @Test fun halfDaysFollowTheWebRule() {
        assertEquals(DayPart.Whole, GridLayout.partOf(task("a", "2026-11-02")))
        assertEquals(DayPart.Morning, GridLayout.partOf(task("b", "2026-11-02", from = "08:00:00", to = "12:00:00")))
        assertEquals(DayPart.Afternoon, GridLayout.partOf(task("c", "2026-11-02", from = "14:00:00", to = "17:00:00")))
        assertEquals(DayPart.Whole, GridLayout.partOf(task("d", "2026-11-02", from = "10:00:00", to = "15:00:00")))
        // Several days: always whole days
        assertEquals(DayPart.Whole, GridLayout.partOf(task("e", "2026-11-02", "2026-11-03", from = "14:00:00")))
    }

    @Test fun aMorningAndAnAfternoonShareALineAndOverlapsStack() {
        val bars = GridLayout.bars(
            listOf(
                task("am", "2026-11-03", from = "08:00:00", to = "12:00:00"),
                task("pm", "2026-11-03", from = "14:00:00", to = "17:00:00"),
                task("week", "2026-11-02", "2026-11-06"),
            ),
            monday, 7,
        ).associateBy { it.task.id }
        // Several days first, then by time
        assertEquals(0, bars.getValue("week").lane)
        assertEquals(1, bars.getValue("am").lane)
        assertEquals(1, bars.getValue("pm").lane)
        assertEquals(0 to 4, bars.getValue("week").first to bars.getValue("week").last)
    }

    @Test fun barsAreCutAtTheEdgesOfThePeriod() {
        val bars = GridLayout.bars(
            listOf(task("long", "2026-10-28", "2026-11-12"), task("before", "2026-10-01"), task("after", "2026-12-01")),
            monday, 7,
        )
        val bar = bars.single()
        assertEquals(0 to 6, bar.first to bar.last)
        assertTrue(bar.continuesBefore)
        assertTrue(bar.continuesAfter)
    }

    @Test fun rowsGroupedByTeamInOrderWithTheTasksOfEachPerson() {
        val teams = listOf(TeamDto(id = "t2", name = "B", sortOrder = 2), TeamDto(id = "t1", name = "A", sortOrder = 1))
        val users = listOf(
            UserDto(id = "u3", name = "Zed", teamId = "t1", sortOrder = 2),
            UserDto(id = "u1", name = "Ann", teamId = "t1", sortOrder = 1),
            UserDto(id = "u2", name = "Bob", teamId = "t2"),
            UserDto(id = "u4", name = "Solo"),
        )
        val tasks = listOf(task("shared", "2026-11-04", users = listOf("u1", "u2")), task("other", "2026-11-04", users = listOf("u1"), project = "p2"))
        val groups = GridLayout.groups(users, teams, tasks, PlanningFilter(people = PeopleFilter.All), monday, 7)
        assertEquals(listOf("t1", "t2", null), groups.map { it.team?.id })
        assertEquals(listOf("u1", "u3"), groups[0].rows.map { it.user.id })
        // One bar per person on a shared task; the project filter picks the bars
        assertEquals(2, groups[0].rows[0].lanes)
        assertEquals(listOf("shared"), groups[1].rows[0].bars.map { it.task.id })
        val filtered = GridLayout.groups(users, teams, tasks, PlanningFilter(people = PeopleFilter.All, specificProjects = true, projectIds = setOf("p2")), monday, 7)
        assertEquals(listOf("other"), filtered[0].rows[0].bars.map { it.task.id })
    }

    @Test fun personAndTeamFiltersPickTheRows() {
        val teams = listOf(TeamDto(id = "t1", userIds = listOf("u1")))
        val users = listOf(UserDto(id = "u1", teamId = "t1"), UserDto(id = "u2"))
        assertEquals(listOf("u2"), GridLayout.people(users, PlanningFilter(people = PeopleFilter.Mine), "u2", teams).map { it.id })
        assertEquals(listOf("u1"), GridLayout.people(users, PlanningFilter(people = PeopleFilter.All, specificTeams = true, teamIds = setOf("t1")), "u2", teams).map { it.id })
        assertFalse(GridLayout.people(users, PlanningFilter(people = PeopleFilter.Specific, userIds = setOf("u9")), "u2", teams).isNotEmpty())
    }
}
