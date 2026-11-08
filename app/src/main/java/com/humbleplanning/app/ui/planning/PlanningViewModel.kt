package com.humbleplanning.app.ui.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humbleplanning.app.data.ApiClient
import com.humbleplanning.app.data.ApiException
import com.humbleplanning.app.data.Prefs
import com.humbleplanning.app.data.Repository
import com.humbleplanning.app.data.TaskDto
import com.humbleplanning.app.data.TeamDto
import com.humbleplanning.app.data.forScreen
import com.humbleplanning.app.ui.monday
import com.humbleplanning.app.ui.occursOn
import com.humbleplanning.app.ui.sortedForDay
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

enum class PlanningMode { Day, Week, Month, Grid }

enum class PeopleFilter { All, Mine, Specific }

/** Planning filters, all applied in the app on the tasks the server returned; kept from one opening to the next. */
@Serializable
data class PlanningFilter(
    val people: PeopleFilter = PeopleFilter.Mine,
    val userIds: Set<String> = emptySet(),
    val specificTeams: Boolean = false,
    val teamIds: Set<String> = emptySet(),
    val specificProjects: Boolean = false,
    val projectIds: Set<String> = emptySet(),
    val specificStatuses: Boolean = false,
    val statusIds: Set<String> = emptySet(),
) {
    val activeCount: Int get() = listOf(people == PeopleFilter.Specific, specificTeams, specificProjects, specificStatuses).count { it }

    fun matches(task: TaskDto, me: String, teams: List<TeamDto>): Boolean {
        val people = when (people) {
            PeopleFilter.All -> true
            PeopleFilter.Mine -> me in task.userIds
            PeopleFilter.Specific -> task.userIds.any { it in userIds }
        }
        if (!people) return false
        if (specificTeams) {
            val members = teams.filter { it.id in teamIds }.flatMapTo(HashSet()) { it.userIds }
            if (task.userIds.none { it in members }) return false
        }
        if (specificProjects && task.projectId !in projectIds) return false
        if (specificStatuses && task.statusId !in statusIds) return false
        return true
    }
}

data class PlanningState(
    val mode: PlanningMode = PlanningMode.Day,
    val selectedDate: LocalDate = LocalDate.now(),
    val rangeStart: LocalDate? = null,
    val rangeEnd: LocalDate? = null,
    val tasks: List<TaskDto> = emptyList(),
    val filter: PlanningFilter = PlanningFilter(),
    val me: String = "",
    val teams: List<TeamDto> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: ApiException? = null,
    val offlineSinceMillis: Long? = null,
    /** Width of a day on the grid, in dp. */
    val cellWidth: Float = GRID_DEFAULT_WIDTH,
    /** Changes on each "Today" or date choice: the grid scrolls back to the chosen day even if it did not change. */
    val focusRequest: Int = 0,
) {
    /** The grid shows the whole weeks of the chosen month. */
    val gridStart: LocalDate get() = selectedDate.withDayOfMonth(1).monday()
    val gridDays: Int get() = ChronoUnit.DAYS.between(gridStart, selectedDate.withDayOfMonth(selectedDate.lengthOfMonth()).monday().plusDays(7)).toInt()

    // Worked out once per state: the week strip and the month grid ask for many days on each composition
    private val visibleTasks: List<TaskDto> by lazy { tasks.filter { filter.matches(it, me, teams) } }
    private val tasksByDay = HashMap<LocalDate, List<TaskDto>>()

    fun tasksOn(date: LocalDate): List<TaskDto> = tasksByDay.getOrPut(date) { visibleTasks.filter { it.occursOn(date) }.sortedForDay() }

    /** The loaded period covers this date (otherwise its tasks are still to come). */
    fun covers(date: LocalDate): Boolean = rangeStart != null && rangeEnd != null && date >= rangeStart && date <= rangeEnd
}

class PlanningViewModel(private val repository: Repository, private val prefs: Prefs) : ViewModel() {
    private val _state = MutableStateFlow(
        PlanningState(
            mode = runCatching { PlanningMode.valueOf(prefs.string(KEY_MODE, PlanningMode.Day.name)) }.getOrDefault(PlanningMode.Day),
            filter = runCatching { ApiClient.json.decodeFromString(PlanningFilter.serializer(), prefs.string(Repository.KEY_PLANNING_FILTER)) }.getOrDefault(PlanningFilter()),
            me = repository.userId,
            cellWidth = prefs.string(KEY_CELL_WIDTH).toFloatOrNull()?.coerceIn(GRID_MIN_WIDTH, GRID_MAX_WIDTH) ?: GRID_DEFAULT_WIDTH,
        )
    )
    val state: StateFlow<PlanningState> = _state.asStateFlow()
    val references = repository.references
    val currentUserId: String get() = repository.userId
    val cardTitle = repository.cardTitle
    val offline = repository.offline

    private var loadJob: Job? = null

    init {
        refreshBackground()
        viewModelScope.launch { repository.tasksChanged.drop(1).collect { reload(silent = true) } }
        viewModelScope.launch {
            repository.references.collect { refs ->
                _state.update { it.copy(me = repository.userId, teams = refs.teams) }
            }
        }
        reload()
    }

    fun setMode(mode: PlanningMode) {
        prefs.putString(KEY_MODE, mode.name)
        _state.update { it.copy(mode = mode) }
        ensureLoaded()
    }

    fun select(date: LocalDate) {
        if (date == _state.value.selectedDate) return
        _state.update { it.copy(selectedDate = date) }
        ensureLoaded()
    }

    fun goToday() {
        _state.update { it.copy(focusRequest = it.focusRequest + 1) }
        select(LocalDate.now())
    }

    /** Previous or next month of the grid. */
    fun moveMonth(months: Long) = select(_state.value.selectedDate.withDayOfMonth(1).plusMonths(months))

    fun setFilter(filter: PlanningFilter) {
        prefs.putString(Repository.KEY_PLANNING_FILTER, ApiClient.json.encodeToString(PlanningFilter.serializer(), filter))
        _state.update { it.copy(filter = filter) }
    }

    /** Pinch or width menu of the grid; saved once the gesture is over. */
    fun setCellWidth(width: Float, save: Boolean = true) {
        val value = width.coerceIn(GRID_MIN_WIDTH, GRID_MAX_WIDTH)
        _state.update { it.copy(cellWidth = value) }
        if (save) prefs.putString(KEY_CELL_WIDTH, value.toString())
    }

    private var resumedOnce = false

    /** Back on the planning (app reopened, back from a task): quiet reload. */
    fun onResume() {
        if (resumedOnce) reload(silent = true) else resumedOnce = true
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        reload(silent = true)
        refreshBackground()
    }

    /** Reference data and offline copy, in the background. */
    private fun refreshBackground() {
        viewModelScope.launch {
            runCatching { repository.loadReferences() }
            runCatching { repository.refreshOfflineData() }
        }
    }

    /**
     * Loaded period: the displayed week and its neighbours (Day, Week: swiping to the next day or week is
     * immediate), or the 6 weeks of the month grid, or the weeks of the team grid.
     */
    private fun rangeFor(mode: PlanningMode, date: LocalDate): Pair<LocalDate, LocalDate> = when (mode) {
        PlanningMode.Day, PlanningMode.Week -> date.monday().let { it.minusDays(7) to it.plusDays(13) }
        PlanningMode.Month -> date.withDayOfMonth(1).monday().let { it to it.plusDays(41) }
        PlanningMode.Grid -> PlanningState(selectedDate = date).let { it.gridStart to it.gridStart.plusDays(it.gridDays - 1L) }
    }

    /** Other day or view: nothing to do when the period is already there. */
    private fun ensureLoaded() {
        val s = _state.value
        val (start, end) = rangeFor(s.mode, s.selectedDate)
        val needed = if (s.mode == PlanningMode.Month || s.mode == PlanningMode.Grid) start to end else s.selectedDate.monday() to s.selectedDate.monday().plusDays(6)
        if (s.covers(needed.first) && s.covers(needed.second)) return
        load(start, end, silent = false)
    }

    private fun reload(silent: Boolean = false) {
        val s = _state.value
        val (start, end) = rangeFor(s.mode, s.selectedDate)
        load(start, end, silent)
    }

    private fun load(start: LocalDate, end: LocalDate, silent: Boolean) {
        loadJob?.cancel()
        _state.update { it.copy(loading = !silent, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val result = repository.tasks(start.toString(), end.toString())
                _state.update {
                    it.copy(tasks = result.tasks, offlineSinceMillis = result.offlineSinceMillis, loading = false, refreshing = false, rangeStart = start, rangeEnd = end)
                }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, refreshing = false, error = e.forScreen()) }
            }
        }
    }

    private companion object {
        const val KEY_MODE = "planning_view"
        const val KEY_CELL_WIDTH = "grid_cell_width"
    }
}

const val GRID_MIN_WIDTH = 24f
const val GRID_DEFAULT_WIDTH = 56f
const val GRID_MAX_WIDTH = 140f
