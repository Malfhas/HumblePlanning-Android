package com.humbleplanning.app.ui.upcoming

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.humbleplanning.app.R
import com.humbleplanning.app.data.ApiException
import com.humbleplanning.app.data.Repository
import com.humbleplanning.app.data.TaskDto
import com.humbleplanning.app.data.forScreen
import com.humbleplanning.app.ui.AnimatedBanner
import com.humbleplanning.app.ui.Banner
import com.humbleplanning.app.ui.BannerTone
import com.humbleplanning.app.ui.CenteredLoader
import com.humbleplanning.app.ui.EmptyState
import com.humbleplanning.app.ui.LocalHumbleColors
import com.humbleplanning.app.ui.OfflineBanner
import com.humbleplanning.app.ui.apiErrorMessage
import com.humbleplanning.app.ui.appLocale
import com.humbleplanning.app.ui.humbleViewModel
import com.humbleplanning.app.ui.longLabel
import com.humbleplanning.app.ui.occursOn
import com.humbleplanning.app.ui.planning.TaskCard
import com.humbleplanning.app.ui.sortedForDay
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DAYS = 30

data class UpcomingState(
    val days: List<Pair<LocalDate, List<TaskDto>>> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: ApiException? = null,
    val offlineSinceMillis: Long? = null,
)

/** My tasks of the next 30 days, day by day (days without a task left out). */
class UpcomingViewModel(private val repository: Repository) : ViewModel() {
    private val _state = MutableStateFlow(UpcomingState())
    val state = _state.asStateFlow()
    val references = repository.references
    val cardTitle = repository.cardTitle

    init {
        load()
        viewModelScope.launch { repository.tasksChanged.drop(1).collect { load() } }
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load()
    }

    fun load() {
        viewModelScope.launch {
            val today = LocalDate.now()
            val me = repository.userId
            try {
                val result = repository.tasks(today.toString(), today.plusDays(DAYS.toLong()).toString())
                val mine = result.tasks.filter { me in it.userIds }
                val days = (0..DAYS).map { today.plusDays(it.toLong()) }
                    .map { day -> day to mine.filter { it.occursOn(day) }.sortedForDay() }
                    .filter { it.second.isNotEmpty() }
                _state.update { UpcomingState(days, loading = false, offlineSinceMillis = result.offlineSinceMillis) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, refreshing = false, error = e.forScreen()) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpcomingScreen(onOpenTask: (String) -> Unit, bottomPadding: Dp) {
    val vm = humbleViewModel { UpcomingViewModel(it.repository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refs by vm.references.collectAsStateWithLifecycle()
    val titleMode by vm.cardTitle.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.load()
        onPauseOrDispose { }
    }
    val locale = appLocale()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            stringResource(R.string.upcoming_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 20.dp, top = 14.dp, end = 20.dp).semantics { heading() },
        )
        Text(
            pluralStringResource(R.plurals.upcoming_subtitle, DAYS, DAYS),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )
        Column(Modifier.padding(horizontal = 16.dp)) {
            AnimatedBanner(state.offlineSinceMillis != null) { OfflineBanner(state.offlineSinceMillis, Modifier.padding(top = 8.dp)) }
            AnimatedBanner(state.error != null) { Banner(apiErrorMessage(state.error), BannerTone.Error, Modifier.padding(top = 8.dp)) }
        }
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.weight(1f)) {
            when {
                state.loading && state.days.isEmpty() -> CenteredLoader()
                state.days.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                    item { EmptyState(Icons.Outlined.EventAvailable, stringResource(R.string.upcoming_empty), pluralStringResource(R.plurals.upcoming_empty_help, DAYS, DAYS)) }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    state.days.forEach { (day, tasks) ->
                        item(key = "d$day") {
                            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                val today = day == LocalDate.now()
                                Text(
                                    if (today) stringResource(R.string.today) else if (day == LocalDate.now().plusDays(1)) stringResource(R.string.tomorrow) else day.longLabel(locale),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (today) LocalHumbleColors.current.today else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f).semantics { heading() },
                                )
                                if (today) {
                                    Text(day.longLabel(locale), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(pluralStringResource(R.plurals.n_tasks, tasks.size, tasks.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(tasks, key = { "$day-${it.id}" }) { task ->
                            TaskCard(task, refs, titleMode, showPerson = false, day = day, onClick = { onOpenTask(task.id) }, modifier = Modifier.animateItem())
                        }
                    }
                }
            }
        }
    }
}
