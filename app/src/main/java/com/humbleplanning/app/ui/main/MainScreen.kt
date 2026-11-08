package com.humbleplanning.app.ui.main

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.humbleplanning.app.R
import com.humbleplanning.app.alerts.AlertSync
import com.humbleplanning.app.graph
import com.humbleplanning.app.reminders.ReminderSync
import com.humbleplanning.app.ui.alerts.AlertsScreen
import com.humbleplanning.app.ui.assistant.AssistantScreen
import com.humbleplanning.app.ui.humbleViewModel
import com.humbleplanning.app.ui.planning.PlanningScreen
import com.humbleplanning.app.ui.planning.PlanningViewModel
import com.humbleplanning.app.ui.settings.SettingsScreen
import com.humbleplanning.app.ui.upcoming.UpcomingScreen

private enum class Tab(val label: Int, val selectedIcon: ImageVector, val icon: ImageVector) {
    Planning(R.string.tab_planning, Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
    Upcoming(R.string.tab_upcoming, Icons.Filled.ViewAgenda, Icons.Outlined.ViewAgenda),
    Alerts(R.string.tab_alerts, Icons.Filled.Notifications, Icons.Outlined.Notifications),
    Assistant(R.string.tab_assistant, Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
    Settings(R.string.tab_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
}

private const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"

/** Main screen: bottom bar on a phone, rail on a tablet. */
@Composable
fun MainScreen(
    onOpenTask: (String) -> Unit,
    onNewTask: (date: String?, userId: String?) -> Unit,
    onOpenConversation: (String?) -> Unit,
    onSetupTwoFactor: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(Tab.Planning) }
    val planning = humbleViewModel { PlanningViewModel(it.repository, it.prefs) }
    val planningState by planning.state.collectAsStateWithLifecycle()
    val refs by planning.references.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Reminders and alerts: in sync on entry; the notification permission is only asked once
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ReminderSync.enqueueOnce(context) }
    LaunchedEffect(Unit) {
        ReminderSync.enqueueOnce(context)
        ReminderSync.schedulePeriodic(context)
        AlertSync.schedulePeriodic(context)
        val prefs = context.graph.prefs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !prefs.boolean(KEY_NOTIFICATIONS_ASKED, false)) {
            prefs.putBoolean(KEY_NOTIFICATIONS_ASKED, true)
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val assistantAvailable = refs.assistant.available
    // The assistant can be withdrawn while its tab is open
    val tab = if (selected == Tab.Assistant && !assistantAvailable) Tab.Planning else selected

    val layout = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfoV2())
    // The bottom bar takes the system navigation area; with a rail, the lists keep clear of it themselves
    val bottomPadding = if (layout == NavigationSuiteType.NavigationBar) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val scheme = MaterialTheme.colorScheme
    val itemColors = NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            indicatorColor = scheme.primaryContainer,
            selectedIconColor = scheme.onPrimaryContainer,
            selectedTextColor = scheme.onSurface,
        ),
        navigationRailItemColors = NavigationRailItemDefaults.colors(
            indicatorColor = scheme.primaryContainer,
            selectedIconColor = scheme.onPrimaryContainer,
            selectedTextColor = scheme.onSurface,
        ),
    )
    NavigationSuiteScaffold(
        layoutType = layout,
        containerColor = MaterialTheme.colorScheme.background,
        navigationSuiteItems = {
            Tab.entries.filter { it != Tab.Assistant || assistantAvailable }.forEach { item ->
                val isSelected = item == tab
                item(
                    selected = isSelected,
                    onClick = { selected = item },
                    icon = { Icon(if (isSelected) item.selectedIcon else item.icon, null) },
                    label = { Text(stringResource(item.label), maxLines = 1) },
                    colors = itemColors,
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End))) {
            Crossfade(tab, label = "tab") { current ->
                when (current) {
                    Tab.Planning -> PlanningScreen(planning, onOpenTask, onNewTask, bottomPadding)
                    Tab.Upcoming -> UpcomingScreen(onOpenTask, bottomPadding)
                    Tab.Alerts -> AlertsScreen(onOpenTask, bottomPadding)
                    Tab.Assistant -> AssistantScreen(onOpenConversation, bottomPadding)
                    Tab.Settings -> SettingsScreen(onSetupTwoFactor, onLoggedOut, bottomPadding)
                }
            }
            AnimatedVisibility(
                (tab == Tab.Planning || tab == Tab.Upcoming) && refs.access.canCreateTask,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = bottomPadding + 16.dp),
                enter = scaleIn(),
                exit = scaleOut(),
            ) {
                ExtendedFloatingActionButton(
                    onClick = { onNewTask(if (tab == Tab.Planning) planningState.selectedDate.toString() else null, null) },
                    // The button keeps its text away from screen readers: the icon gives its name
                    icon = { Icon(Icons.Outlined.Add, stringResource(R.string.new_task)) },
                    text = { Text(stringResource(R.string.new_task)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}
