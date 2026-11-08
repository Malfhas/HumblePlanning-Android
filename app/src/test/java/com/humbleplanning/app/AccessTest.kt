package com.humbleplanning.app

import com.humbleplanning.app.data.Access
import com.humbleplanning.app.data.ProjectDto
import com.humbleplanning.app.data.TaskDto
import com.humbleplanning.app.data.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessTest {
    private val me = "me"
    private fun access(vararg permissions: String, admin: Boolean = false, team: String? = "t1") = Access(me, team, permissions.toSet(), admin)

    private fun task(
        creator: String? = "other",
        users: List<UserDto> = listOf(UserDto(id = "other", teamId = "t2")),
        owner: String? = "other",
        locked: Boolean = false,
    ) = TaskDto(id = "x", creatorId = creator, users = users, project = ProjectDto(id = "p", ownerId = owner), isLocked = locked)

    @Test fun adminMayDoEverything() {
        val a = access(admin = true)
        assertTrue(a.canModify(task()))
        assertTrue(a.canCreateTask)
        assertTrue(a.canDelete(task()))
        assertTrue(a.canLock(task()))
    }

    @Test fun scopeAllModifiesAnyTask() {
        assertTrue(access("scope:modify_all").canModify(task()))
    }

    @Test fun scopeTeamNeedsAnAssigneeOfTheTeam() {
        val a = access("scope:modify_team")
        assertFalse(a.canModify(task()))
        assertTrue(a.canModify(task(users = listOf(UserDto(id = "colleague", teamId = "t1")))))
    }

    @Test fun scopeTeamWithoutATeamModifiesNothing() {
        val a = access("scope:modify_team", team = null)
        assertFalse(a.canModify(task(users = listOf(UserDto(id = "somebody", teamId = null)))))
    }

    @Test fun scopeOwnProjectNeedsToOwnTheProject() {
        val a = access("scope:modify_own_project")
        assertFalse(a.canModify(task()))
        assertTrue(a.canModify(task(owner = me)))
    }

    @Test fun scopeOwnNeedsToBeCreatorOrAssignee() {
        val a = access("scope:modify_own")
        assertFalse(a.canModify(task()))
        assertTrue(a.canModify(task(creator = me)))
        assertTrue(a.canModify(task(users = listOf(UserDto(id = me)))))
    }

    @Test fun noScopeIsReadOnly() {
        val a = access("tasks:create", "tasks:delete")
        assertFalse(a.canModify(task(creator = me, owner = me)))
        assertFalse(a.canCreateTask)
    }

    @Test fun widestScopeWins() {
        assertTrue(access("scope:modify_own", "scope:modify_all").canModify(task()))
    }

    @Test fun lockedTaskCannotBeDeletedAssignedOrCopied() {
        val a = access("scope:modify_all", "tasks:delete", "tasks:assign", "tasks:create")
        val locked = task(locked = true)
        assertFalse(a.canDelete(locked))
        assertFalse(a.canAssign(locked))
        assertFalse(a.canDuplicate(locked))
        assertTrue(a.canDelete(task()))
        assertTrue(a.canAssign(task()))
        assertTrue(a.canDuplicate(task()))
    }

    @Test fun lockedTaskCanOnlyBeEditedByWhoMayLock() {
        val locked = task(locked = true)
        assertFalse(access("scope:modify_all").canEdit(locked))
        assertTrue(access("scope:modify_all", "tasks:lock").canEdit(locked))
        assertTrue(access("scope:modify_all").canEdit(task()))
    }

    @Test fun filesFollowTheirPermissions() {
        assertTrue(access("files:view").canViewFiles)
        assertTrue(access("pages:files").canViewFiles)
        assertFalse(access().canViewFiles)
        assertTrue(access("files:upload").canUploadFiles)
        assertFalse(access("files:view").canUploadFiles)
        assertTrue(access("files:delete").canDeleteFiles)
    }

    @Test fun assignableUsersFollowTheScope() {
        val all = listOf(UserDto(id = me, teamId = "t1"), UserDto(id = "colleague", teamId = "t1"), UserDto(id = "other", teamId = "t2"))
        assertEquals(3, access("scope:modify_all").assignableUsers(all).size)
        assertEquals(3, access("scope:modify_own_project").assignableUsers(all).size)
        assertEquals(listOf(me, "colleague"), access("scope:modify_team").assignableUsers(all).map { it.id })
        assertEquals(listOf(me), access("scope:modify_own").assignableUsers(all).map { it.id })
        assertTrue(access().assignableUsers(all).isEmpty())
    }
}
