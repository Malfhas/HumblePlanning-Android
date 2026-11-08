package com.humbleplanning.app.ui.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.humbleplanning.app.data.MobileAlert
import com.humbleplanning.app.data.Repository
import com.humbleplanning.app.data.forScreen
import com.humbleplanning.app.ui.AnimatedBanner
import com.humbleplanning.app.ui.Banner
import com.humbleplanning.app.ui.BannerTone
import com.humbleplanning.app.ui.CenteredLoader
import com.humbleplanning.app.ui.EmptyState
import com.humbleplanning.app.ui.apiErrorMessage
import com.humbleplanning.app.ui.appLocale
import com.humbleplanning.app.ui.humbleViewModel
import com.humbleplanning.app.ui.relativeDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AlertsState(
    val alerts: List<MobileAlert> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: ApiException? = null,
)

/** Alerts the server kept for this user, most recent first. */
class AlertsViewModel(private val repository: Repository) : ViewModel() {
    private val _state = MutableStateFlow(AlertsState())
    val state = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { repository.alertsChanged.drop(1).collect { load() } }
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load()
    }

    fun load() {
        viewModelScope.launch {
            try {
                val alerts = repository.alertHistory()
                _state.update { AlertsState(alerts, loading = false) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, refreshing = false, error = e.forScreen()) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(onOpenTask: (String) -> Unit, bottomPadding: Dp) {
    val vm = humbleViewModel { AlertsViewModel(it.repository) }
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.load()
        onPauseOrDispose { }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            stringResource(R.string.tab_alerts),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 4.dp).semantics { heading() },
        )
        Column(Modifier.padding(horizontal = 16.dp)) {
            AnimatedBanner(state.error != null) { Banner(apiErrorMessage(state.error), BannerTone.Error, Modifier.padding(top = 8.dp)) }
        }
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.weight(1f)) {
            when {
                state.loading && state.alerts.isEmpty() -> CenteredLoader()
                state.alerts.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                    item { EmptyState(Icons.Outlined.NotificationsNone, stringResource(R.string.alerts_empty), stringResource(R.string.alerts_empty_help)) }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.alerts, key = { it.id }) { alert -> AlertCard(alert, onOpenTask) }
                }
            }
        }
    }
}

@Composable
private fun AlertCard(alert: MobileAlert, onOpenTask: (String) -> Unit) {
    val locale = appLocale()
    val taskId = alert.taskId
    Surface(
        onClick = { taskId?.let(onOpenTask) },
        enabled = taskId != null,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(14.dp)) {
            Icon(Icons.Outlined.Notifications, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(alert.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(relativeDate(alert.createdAt, locale), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (alert.message.isNotBlank()) {
                    Text(alert.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
