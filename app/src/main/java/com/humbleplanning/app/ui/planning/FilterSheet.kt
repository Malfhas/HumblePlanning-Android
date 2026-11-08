package com.humbleplanning.app.ui.planning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.humbleplanning.app.R
import com.humbleplanning.app.data.References
import com.humbleplanning.app.ui.AppSegmented
import com.humbleplanning.app.ui.PickerItem
import com.humbleplanning.app.ui.PickerSheet
import com.humbleplanning.app.ui.parseColor

private enum class FilterList { People, Teams, Projects, Statuses }

/** Planning filters: people (me, everyone, picked), teams, projects, statuses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(initial: PlanningFilter, refs: References, currentUserId: String, onDismiss: () -> Unit, onApply: (PlanningFilter) -> Unit) {
    var filter by remember { mutableStateOf(initial) }
    var picking by remember { mutableStateOf<FilterList?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(stringResource(R.string.filters), style = MaterialTheme.typography.titleLarge)

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.filter_people), style = MaterialTheme.typography.titleSmall)
                AppSegmented(
                    listOf(
                        PeopleFilter.Mine to stringResource(R.string.filter_me),
                        PeopleFilter.All to stringResource(R.string.filter_everyone),
                        PeopleFilter.Specific to stringResource(R.string.filter_pick),
                    ),
                    filter.people,
                ) { value ->
                    filter = filter.copy(people = value, userIds = if (value == PeopleFilter.Specific && filter.userIds.isEmpty()) setOf(currentUserId) else filter.userIds)
                    if (value == PeopleFilter.Specific) picking = FilterList.People
                }
                if (filter.people == PeopleFilter.Specific) {
                    ChoiceRow(pluralStringResource(R.plurals.n_people, filter.userIds.size, filter.userIds.size)) { picking = FilterList.People }
                }
            }

            if (refs.teams.isNotEmpty()) {
                ToggleSection(stringResource(R.string.filter_teams), stringResource(R.string.filter_all_teams), !filter.specificTeams, {
                    filter = filter.copy(specificTeams = !it)
                    if (!it) picking = FilterList.Teams
                }) {
                    if (filter.specificTeams) ChoiceRow(pluralStringResource(R.plurals.n_teams, filter.teamIds.size, filter.teamIds.size)) { picking = FilterList.Teams }
                }
            }

            ToggleSection(stringResource(R.string.filter_projects), stringResource(R.string.filter_all_projects), !filter.specificProjects, {
                filter = filter.copy(specificProjects = !it)
                if (!it) picking = FilterList.Projects
            }) {
                if (filter.specificProjects) ChoiceRow(pluralStringResource(R.plurals.n_projects, filter.projectIds.size, filter.projectIds.size)) { picking = FilterList.Projects }
            }

            ToggleSection(stringResource(R.string.filter_statuses), stringResource(R.string.filter_all_statuses), !filter.specificStatuses, {
                filter = filter.copy(specificStatuses = !it)
                if (!it) picking = FilterList.Statuses
            }) {
                if (filter.specificStatuses) ChoiceRow(pluralStringResource(R.plurals.n_statuses, filter.statusIds.size, filter.statusIds.size)) { picking = FilterList.Statuses }
            }

            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { filter = PlanningFilter() }) { Text(stringResource(R.string.reset)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onApply(filter) }, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.apply)) }
            }
        }
    }

    when (picking) {
        FilterList.People -> PickerSheet(
            stringResource(R.string.filter_people),
            refs.users.map { user -> PickerItem(user.id, user.displayName, parseColor(user.color), refs.teams.firstOrNull { it.id == user.teamId }?.name.orEmpty()) },
            filter.userIds, multiple = true,
            onDismiss = { picking = null },
            onConfirm = { filter = filter.copy(userIds = it); picking = null },
        )
        FilterList.Teams -> PickerSheet(
            stringResource(R.string.filter_teams),
            refs.teams.map { PickerItem(it.id, it.name, parseColor(it.color)) },
            filter.teamIds, multiple = true,
            onDismiss = { picking = null },
            onConfirm = { filter = filter.copy(teamIds = it); picking = null },
        )
        FilterList.Projects -> PickerSheet(
            stringResource(R.string.filter_projects),
            refs.projects.map { PickerItem(it.id, it.label, parseColor(it.color), it.group?.name.orEmpty()) },
            filter.projectIds, multiple = true,
            onDismiss = { picking = null },
            onConfirm = { filter = filter.copy(projectIds = it); picking = null },
        )
        FilterList.Statuses -> PickerSheet(
            stringResource(R.string.filter_statuses),
            refs.statuses.map { PickerItem(it.id, it.name, parseColor(it.color)) },
            filter.statusIds, multiple = true,
            onDismiss = { picking = null },
            onConfirm = { filter = filter.copy(statusIds = it); picking = null },
        )
        null -> Unit
    }
}

@Composable
private fun ToggleSection(title: String, allLabel: String, all: Boolean, onToggle: (Boolean) -> Unit, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(all, role = Role.Switch, onValueChange = onToggle), verticalAlignment = Alignment.CenterVertically) {
            Text(allLabel, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(all, null)
        }
        content()
    }
}

@Composable
private fun ChoiceRow(label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
        }
    }
}
