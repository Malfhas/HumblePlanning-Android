package com.humbleplanning.app.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.humbleplanning.app.R
import com.humbleplanning.app.data.ChatLine
import com.humbleplanning.app.data.PendingApproval
import com.humbleplanning.app.ui.Banner
import com.humbleplanning.app.ui.BannerTone
import com.humbleplanning.app.ui.CenteredLoader
import com.humbleplanning.app.ui.EmptyState
import com.humbleplanning.app.ui.apiErrorMessage
import com.humbleplanning.app.ui.humbleViewModel

/** A conversation: chat bubbles, answers written as they arrive, confirmation of every write. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(conversationId: String?, onBack: () -> Unit) {
    val vm = humbleViewModel(key = "conversation-$conversationId") { ConversationViewModel(it.repository, conversationId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    val networkError = stringResource(R.string.err_network)
    val listState = rememberLazyListState()
    val last = state.lines.lastOrNull()
    // Follows the answer as it is written, and the keyboard when it opens above the list
    LaunchedEffect(state.lines.size, last?.text?.length, last?.approvals?.size, state.working) {
        if (state.lines.isNotEmpty()) listState.scrollToItem(state.lines.lastIndex, Int.MAX_VALUE)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    state.title.ifBlank { stringResource(R.string.assistant_new) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } },
            actions = { QuotaGauge(state.quota, Modifier.padding(end = 4.dp)) },
        )
        Column(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading -> CenteredLoader()
                state.loadError != null -> EmptyState(
                    Icons.Outlined.WarningAmber,
                    apiErrorMessage(state.loadError),
                    action = { OutlinedButton(onClick = vm::retryLoad) { Text(stringResource(R.string.retry)) } },
                )
                state.lines.isEmpty() -> EmptyState(Icons.Outlined.AutoAwesome, stringResource(R.string.assistant_hello), stringResource(R.string.assistant_hello_help))
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.lines, key = { it.id }) { line ->
                        Bubble(
                            line,
                            writing = state.streaming && line === last,
                            enabled = !state.streaming,
                            onDecide = { approval, approved -> vm.decide(line, approval, approved, networkError) },
                        )
                    }
                    if (state.working) {
                        item(key = "working") {
                            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Build, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.assistant_working), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.imePadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.error?.let { Banner(it, BannerTone.Error, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (state.waitingForDecision) {
                Text(stringResource(R.string.assistant_decide_first), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val quota = state.quota
            // Quota reached: the input closes and says until when, as on the web; reading stays possible
            if (quota != null && state.quotaReached && !state.streaming) {
                Banner(
                    stringResource(R.string.assistant_quota_reached, quotaResetLabel(quota)),
                    BannerTone.Warning,
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    draft,
                    { draft = it },
                    Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.assistant_placeholder)) },
                    enabled = !state.waitingForDecision && state.loadError == null,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = MaterialTheme.shapes.large,
                )
                Spacer(Modifier.width(8.dp))
                if (state.streaming) {
                    FilledIconButton(onClick = vm::stop, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Outlined.Stop, stringResource(R.string.assistant_stop))
                    }
                } else {
                    FilledIconButton(
                        onClick = { vm.send(draft, networkError) { draft = "" } },
                        enabled = draft.isNotBlank() && !state.waitingForDecision && !state.loading,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.assistant_send))
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(line: ChatLine, writing: Boolean, enabled: Boolean, onDecide: (PendingApproval, Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (line.fromUser) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (line.text.isNotBlank() || writing) {
            val background = if (line.fromUser) scheme.primaryContainer else scheme.surfaceContainerLow
            val content = if (line.fromUser) scheme.onPrimaryContainer else scheme.onSurface
            Surface(shape = MaterialTheme.shapes.large, color = background, modifier = Modifier.widthIn(max = 560.dp)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (line.text.isBlank()) {
                        val writingLabel = stringResource(R.string.assistant_writing)
                        CircularProgressIndicator(Modifier.size(18.dp).semantics { contentDescription = writingLabel }, strokeWidth = 2.dp)
                    } else if (line.fromUser) {
                        SelectionContainer { Text(line.text, style = MaterialTheme.typography.bodyMedium, color = content) }
                    } else {
                        SelectionContainer { MarkdownText(line.text, content) }
                    }
                }
            }
        }
        line.approvals.forEach { approval -> ApprovalCard(approval, enabled, onDecide) }
    }
}

/** A write the assistant wants to run: nothing happens until the user approves it. */
@Composable
private fun ApprovalCard(approval: PendingApproval, enabled: Boolean, onDecide: (PendingApproval, Boolean) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.assistant_confirm_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(readableToolName(approval.toolName), style = MaterialTheme.typography.titleSmall)
            val input = readableJson(approval.input)
            if (input.isNotBlank()) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Text(
                        input,
                        Modifier.fillMaxWidth().padding(10.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onDecide(approval, false) }, enabled = enabled, shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.assistant_refuse))
                }
                Button(onClick = { onDecide(approval, true) }, enabled = enabled, shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.assistant_approve))
                }
            }
        }
    }
}
