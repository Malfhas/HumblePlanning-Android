package com.humbleplanning.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humbleplanning.app.R
import com.humbleplanning.app.data.ApiException

enum class BannerTone { Info, Warning, Error }

/** Quiet information strip (offline, error, warning). */
@Composable
fun Banner(text: String, tone: BannerTone, modifier: Modifier = Modifier, icon: ImageVector? = null, action: (@Composable () -> Unit)? = null) {
    val colors = LocalHumbleColors.current
    val (accent, glyph) = when (tone) {
        BannerTone.Info -> MaterialTheme.colorScheme.onSurfaceVariant to (icon ?: Icons.Outlined.Info)
        BannerTone.Warning -> colors.warning to (icon ?: Icons.Outlined.WarningAmber)
        BannerTone.Error -> MaterialTheme.colorScheme.error to (icon ?: Icons.Outlined.ErrorOutline)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = accent.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.25f)),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(glyph, null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (tone == BannerTone.Info) MaterialTheme.colorScheme.onSurfaceVariant else accent, modifier = Modifier.weight(1f))
            action?.invoke()
        }
    }
}

@Composable
fun OfflineBanner(sinceMillis: Long?, modifier: Modifier = Modifier) {
    val locale = appLocale()
    val text = if (sinceMillis != null && sinceMillis > 0) {
        val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, locale).format(java.util.Date(sinceMillis))
        stringResource(R.string.offline_since, date)
    } else stringResource(R.string.offline)
    Banner(text, BannerTone.Warning, modifier, Icons.Outlined.CloudOff)
}

@Composable
fun AnimatedBanner(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { content() }
}

/** Text of a refusal: the server message is already translated, only the local failures have strings here. */
@Composable
fun apiErrorMessage(error: ApiException?): String = when (error) {
    null -> ""
    is ApiException.Network -> stringResource(R.string.err_network)
    is ApiException.SessionExpired -> stringResource(R.string.err_session)
    is ApiException.NotHumblePlanning -> stringResource(R.string.err_not_humble)
    is ApiException.Unexpected -> stringResource(R.string.err_unexpected)
    is ApiException.UpgradeRequired -> stringResource(R.string.update_title)
    is ApiException.Maintenance -> error.message?.ifBlank { null } ?: stringResource(R.string.maintenance_text)
    is ApiException.AppNotAllowed -> error.message?.ifBlank { null } ?: stringResource(R.string.not_allowed_text)
    is ApiException.Server -> error.message?.ifBlank { null } ?: stringResource(R.string.err_unexpected)
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.size(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.size(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.size(20.dp))
            action()
        }
    }
}

@Composable
fun ColorDot(color: Color?, size: Int = 10, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size.dp).clip(CircleShape)
            .background(color ?: MaterialTheme.colorScheme.outlineVariant)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), CircleShape)
    )
}

/** Section card (settings, details). */
@Composable
fun SectionCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title.uppercase(appLocale()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) { Column(Modifier.fillMaxWidth(), content = content) }
    }
}

@Composable
fun ProgressButton(text: String, loading: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    Button(onClick = onClick, enabled = enabled && !loading, modifier = modifier.heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        } else {
            if (icon != null) {
                Icon(icon, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text)
        }
    }
}

/**
 * Material's date picker without its animation between the calendar and the typed date: going back to the calendar
 * built every month while the dialog grew, and stuttered. Each mode now shows at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDatePicker(state: DatePickerState) {
    key(state.displayMode) { DatePicker(state) }
}

@Composable
fun CenteredLoader(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(strokeWidth = 3.dp) }
}

/** Segmented choice; a long label or a large font wraps inside its segment instead of being cut. */
@Composable
fun <T> AppSegmented(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    // Same height for all, the one of the longest label
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                modifier = Modifier.fillMaxHeight(),
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                icon = {},
                label = {
                    val style = MaterialTheme.typography.labelMedium
                    // A single word never breaks in two: with a large font it gets smaller instead
                    if (' ' in label) {
                        Text(label, style = style, textAlign = TextAlign.Center)
                    } else {
                        Text(label, style = style, textAlign = TextAlign.Center, maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp))
                    }
                },
            )
        }
    }
}

/** Item of a choice list (project, person, status, place...). */
data class PickerItem(val id: String, val label: String, val color: Color? = null, val group: String = "")

/** A choice list offers a search from this many items. */
private const val SEARCH_FROM = 8

/**
 * Share of the height a choice list with a search keeps, whatever the number of results. All of it: the sheet
 * then does not move either when the keyboard opens.
 */
private const val SEARCHABLE_HEIGHT = 1f

/** Choice in a bottom sheet, with a search above 8 items; single or multiple. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerSheet(
    title: String,
    items: List<PickerItem>,
    selected: Set<String>,
    multiple: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
    allowNone: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    var choice by remember { mutableStateOf(selected) }
    val filtered = remember(query, items) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) items else items.filter { it.label.lowercase().contains(q) || it.group.lowercase().contains(q) }
    }
    val searchable = items.size > SEARCH_FROM
    val listState = rememberLazyListState()
    // Each new search shows its first results
    LaunchedEffect(query) { listState.scrollToItem(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        // With a search, the sheet keeps its height: the field stays where it is while the results shrink
        Column(Modifier.fillMaxWidth().then(if (searchable) Modifier.fillMaxHeight(SEARCHABLE_HEIGHT) else Modifier).navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (multiple) {
                    TextButton(onClick = { choice = if (choice.size == items.size) emptySet() else items.map { it.id }.toSet() }) {
                        Text(stringResource(if (choice.size == items.size) R.string.none else R.string.all))
                    }
                }
            }
            if (searchable) {
                val searchLabel = stringResource(R.string.search)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    // A placeholder alone is not read by screen readers
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).semantics { contentDescription = searchLabel },
                    placeholder = { Text(searchLabel) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
            }
            LazyColumn(
                Modifier.fillMaxWidth().then(if (searchable) Modifier.weight(1f) else Modifier.heightIn(max = 520.dp)),
                state = listState,
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                if (allowNone && !multiple) {
                    item {
                        PickerRow(PickerItem("", stringResource(R.string.none)), choice.isEmpty() || "" in choice) {
                            onConfirm(emptySet())
                        }
                    }
                }
                items(filtered, key = { it.id }) { item ->
                    PickerRow(item, item.id in choice) {
                        if (multiple) {
                            choice = if (item.id in choice) choice - item.id else choice + item.id
                        } else {
                            onConfirm(setOf(item.id))
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    item { Text(stringResource(R.string.no_results), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (multiple) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onConfirm(choice) }, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.ok)) }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(item: PickerItem, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = checked, onClick = onClick, role = Role.Checkbox).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.color != null) {
            ColorDot(item.color, 12)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(item.label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.group.isNotEmpty()) {
                Text(item.group, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (checked) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** One line of a choice inside a dialog (series deletion, person to remove...). */
@Composable
fun DialogOption(text: String, color: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
    )
}
