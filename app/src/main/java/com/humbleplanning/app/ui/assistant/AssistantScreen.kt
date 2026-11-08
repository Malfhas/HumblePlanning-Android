package com.humbleplanning.app.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.humbleplanning.app.R
import com.humbleplanning.app.data.ApiException
import com.humbleplanning.app.data.ChatQuota
import com.humbleplanning.app.data.ConversationDto
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

data class ConversationsState(
    val conversations: List<ConversationDto> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: ApiException? = null,
    /** Monthly quota of the user, shown as a gauge; null until the server said. */
    val quota: ChatQuota? = null,
)

class ConversationsViewModel(private val repository: Repository) : ViewModel() {
    private val _state = MutableStateFlow(ConversationsState())
    val state = _state.asStateFlow()

    init {
        // Back from a blocking screen, which replaced an answer
        viewModelScope.launch { repository.blocking.drop(1).collect { if (it == null) load() } }
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load()
    }

    fun load() {
        viewModelScope.launch {
            try {
                val list = repository.call { conversations() }
                _state.update { ConversationsState(list, loading = false, quota = it.quota) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, refreshing = false, error = e.forScreen()) }
            }
        }
        viewModelScope.launch { repository.assistantQuota()?.let { quota -> _state.update { it.copy(quota = quota) } } }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                repository.call { deleteConversation(id) }
                _state.update { s -> s.copy(conversations = s.conversations.filterNot { it.id == id }, error = null) }
            } catch (e: ApiException) {
                _state.update { it.copy(error = e.forScreen()) }
            }
        }
    }
}

/** List of the conversations with the assistant. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(onOpenConversation: (String?) -> Unit, bottomPadding: Dp) {
    val vm = humbleViewModel { ConversationsViewModel(it.repository) }
    val state by vm.state.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<ConversationDto?>(null) }
    LifecycleResumeEffect(Unit) {
        vm.load()
        onPauseOrDispose { }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.tab_assistant),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).padding(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 4.dp).semantics { heading() },
                )
                QuotaGauge(state.quota, Modifier.padding(top = 10.dp, end = 10.dp))
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                AnimatedBanner(state.error != null) { Banner(apiErrorMessage(state.error), BannerTone.Error, Modifier.padding(top = 8.dp)) }
            }
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.weight(1f)) {
                when {
                    state.loading && state.conversations.isEmpty() -> CenteredLoader()
                    state.conversations.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                        item { EmptyState(Icons.Outlined.AutoAwesome, stringResource(R.string.assistant_empty), stringResource(R.string.assistant_empty_help)) }
                    }
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 88.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.conversations, key = { it.id }) { conversation ->
                            ConversationRow(conversation, onOpen = { onOpenConversation(conversation.id) }, onDelete = { toDelete = conversation })
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { onOpenConversation(null) },
            // The button keeps its text away from screen readers: the icon gives its name
            icon = { Icon(Icons.Outlined.Add, stringResource(R.string.assistant_new)) },
            text = { Text(stringResource(R.string.assistant_new)) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = bottomPadding + 16.dp),
        )
    }
    toDelete?.let { conversation ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            icon = { Icon(Icons.Outlined.Delete, null) },
            title = { Text(stringResource(R.string.assistant_delete_title)) },
            text = { Text(conversation.title.ifBlank { stringResource(R.string.assistant_untitled) }) },
            confirmButton = {
                Button(
                    onClick = {
                        vm.delete(conversation.id)
                        toDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = MaterialTheme.shapes.medium,
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ConversationRow(conversation: ConversationDto, onOpen: () -> Unit, onDelete: () -> Unit) {
    val locale = appLocale()
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text(
                    conversation.title.ifBlank { stringResource(R.string.assistant_untitled) },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                conversation.updatedAt?.let {
                    Text(relativeDate(it, locale), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
