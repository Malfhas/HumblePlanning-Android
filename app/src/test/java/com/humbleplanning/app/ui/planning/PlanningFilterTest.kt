package com.humbleplanning.app.ui.planning

import com.humbleplanning.app.data.TaskDto
import com.humbleplanning.app.data.TeamDto
import com.humbleplanning.app.data.UserDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanningFilterTest {
    private val teams = listOf(TeamDto(id = "t1", userIds = listOf("u1", "u2")), TeamDto(id = "t2", userIds = listOf("u3")))
    private val task = TaskDto(projectId = "p1", statusId = "s1", users = listOf(UserDto(id = "u1")))

    private fun matches(filter: PlanningFilter) = filter.matches(task, "me", teams)

    @Test fun mineOnlyKeepsMyTasks() {
        assertFalse(matches(PlanningFilter(people = PeopleFilter.Mine)))
        assertTrue(PlanningFilter(people = PeopleFilter.Mine).matches(task, "u1", teams))
    }

    @Test fun everyoneKeepsAll() {
        assertTrue(matches(PlanningFilter(people = PeopleFilter.All)))
    }

    @Test fun pickedPeople() {
        assertTrue(matches(PlanningFilter(people = PeopleFilter.Specific, userIds = setOf("u1", "u9"))))
        assertFalse(matches(PlanningFilter(people = PeopleFilter.Specific, userIds = setOf("u9"))))
        assertFalse(matches(PlanningFilter(people = PeopleFilter.Specific)))
    }

    @Test fun teamsMatchThroughTheirMembers() {
        assertTrue(matches(PlanningFilter(people = PeopleFilter.All, specificTeams = true, teamIds = setOf("t1"))))
        assertFalse(matches(PlanningFilter(people = PeopleFilter.All, specificTeams = true, teamIds = setOf("t2"))))
    }

    @Test fun projectsAndStatuses() {
        assertTrue(matches(PlanningFilter(people = PeopleFilter.All, specificProjects = true, projectIds = setOf("p1"))))
        assertFalse(matches(PlanningFilter(people = PeopleFilter.All, specificProjects = true, projectIds = setOf("p2"))))
        assertTrue(matches(PlanningFilter(people = PeopleFilter.All, specificStatuses = true, statusIds = setOf("s1"))))
        assertFalse(matches(PlanningFilter(people = PeopleFilter.All, specificStatuses = true)))
    }
}
