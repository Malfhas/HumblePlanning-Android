package com.humbleplanning.app.data

/**
 * What the signed-in user may do, same rules as the web client (hooks/use-permissions.ts).
 * Controls are hidden from these checks; the server still enforces everything.
 */
data class Access(
    val userId: String = "",
    val teamId: String? = null,
    val permissions: Set<String> = emptySet(),
    val isAdmin: Boolean = false,
) {
    fun has(permission: String): Boolean = isAdmin || permission in permissions

    /** First match of the scope group wins; none = read-only. */
    private val modifyScope: String
        get() = when {
            isAdmin -> "all"
            "scope:modify_all" in permissions -> "all"
            "scope:modify_team" in permissions -> "team"
            "scope:modify_own_project" in permissions -> "own_project"
            "scope:modify_own" in permissions -> "own"
            else -> "readonly"
        }

    val canCreateTask: Boolean get() = has("tasks:create") && modifyScope != "readonly"

    fun canModify(task: TaskDto): Boolean = when (modifyScope) {
        "all" -> true
        "team" -> teamId != null && task.users.any { it.teamId == teamId }
        "own_project" -> task.project?.ownerId == userId
        "own" -> task.creatorId == userId || task.users.any { it.id == userId }
        else -> false
    }

    fun canDelete(task: TaskDto): Boolean = has("tasks:delete") && canModify(task) && !task.isLocked
    fun canAssign(task: TaskDto): Boolean = has("tasks:assign") && canModify(task) && !task.isLocked
    fun canLock(task: TaskDto): Boolean = has("tasks:lock") && canModify(task)
    fun canEdit(task: TaskDto): Boolean = canModify(task) && (!task.isLocked || canLock(task))
    fun canDuplicate(task: TaskDto): Boolean = canCreateTask && !task.isLocked

    val canViewFiles: Boolean get() = has("files:view") || has("pages:files")
    val canUploadFiles: Boolean get() = has("files:upload")
    val canDeleteFiles: Boolean get() = has("files:delete")

    /** People a copy of a task keeps: those this user may assign, or else the user. */
    fun assignableAmong(ids: List<String>, all: List<UserDto>): List<String> {
        if (modifyScope == "all") return ids
        val allowed = assignableUsers(all).map { it.id }
        return ids.filter { it in allowed }.ifEmpty { listOf(userId).filter { it in allowed } }
    }

    /** Who a new task can be assigned to: own scope = only oneself, team scope = only the team. */
    fun assignableUsers(all: List<UserDto>): List<UserDto> = when (modifyScope) {
        "team" -> all.filter { it.teamId != null && it.teamId == teamId }
        "own" -> all.filter { it.id == userId }
        "readonly" -> emptyList()
        else -> all
    }
}

/** Reference data loaded after sign-in, kept encrypted for offline use. */
@kotlinx.serialization.Serializable
data class References(
    val user: UserDto = UserDto(),
    val permissions: List<String> = emptyList(),
    val isAdmin: Boolean = false,
    val config: Map<String, String?> = emptyMap(),
    val assistant: AssistantAccess = AssistantAccess(),
    val projects: List<ProjectDto> = emptyList(),
    val statuses: List<StatusDto> = emptyList(),
    val users: List<UserDto> = emptyList(),
    val teams: List<TeamDto> = emptyList(),
    val places: List<PlaceDto> = emptyList(),
    val resources: List<ResourceDto> = emptyList(),
    val holidays: List<HolidayDto> = emptyList(),
) {
    val access: Access get() = Access(user.id, user.teamId, permissions.toSet(), isAdmin)

    fun project(id: String?) = projects.firstOrNull { it.id == id }
    fun status(id: String?) = statuses.firstOrNull { it.id == id }
    fun user(id: String?) = users.firstOrNull { it.id == id }
    fun place(id: String?) = places.firstOrNull { it.id == id }
    fun resource(id: String?) = resources.firstOrNull { it.id == id }
    fun holiday(date: String) = holidays.firstOrNull { it.date == date }

    private fun flag(key: String, default: Boolean): Boolean = config[key]?.let { it == "true" || it == "1" } ?: default
    val placesEnabled: Boolean get() = flag("MODULE_PLACES", true)
    val resourcesEnabled: Boolean get() = flag("MODULE_RESOURCES", true)
    val filesEnabled: Boolean get() = flag("ENABLE_FILE_UPLOAD", true)
    val morningStart: String get() = config["AM_START"].takeUnless { it.isNullOrBlank() } ?: "08:00"
    val morningEnd: String get() = config["AM_END"].takeUnless { it.isNullOrBlank() } ?: "12:00"
    val afternoonStart: String get() = config["PM_START"].takeUnless { it.isNullOrBlank() } ?: "14:00"
    val afternoonEnd: String get() = config["PM_END"].takeUnless { it.isNullOrBlank() } ?: "17:00"
    val maxUploadBytes: Long get() = (config["MAX_UPLOAD_SIZE_MB"]?.toLongOrNull() ?: 10L) * 1024 * 1024
    val uploadExtensions: List<String>
        get() = config["WHITELIST_UPLOAD"]?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }.orEmpty()
}
