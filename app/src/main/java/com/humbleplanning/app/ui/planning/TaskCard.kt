package com.humbleplanning.app.ui.planning

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.humbleplanning.app.R
import com.humbleplanning.app.data.ProjectDto
import com.humbleplanning.app.data.References
import com.humbleplanning.app.data.StatusDto
import com.humbleplanning.app.data.TaskDto
import com.humbleplanning.app.data.UserDto
import com.humbleplanning.app.graph
import com.humbleplanning.app.ui.ColorDot
import com.humbleplanning.app.ui.contentColor
import com.humbleplanning.app.ui.parseColor
import com.humbleplanning.app.ui.timeRange
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Time of a task: "08:00 – 12:00", or "All day". */
@Composable
fun TaskDto.timeLabel(): String = timeRange() ?: stringResource(R.string.all_day)

fun TaskDto.projectOf(refs: References): ProjectDto? = project ?: refs.project(projectId)

fun TaskDto.statusOf(refs: References): StatusDto? = status ?: refs.status(statusId)

/** Main text of the card, as chosen in the settings (project or task title). */
fun TaskDto.cardTitle(refs: References, mode: String): String {
    val project = projectOf(refs)?.label.orEmpty()
    return if (mode == "title") title?.takeIf { it.isNotBlank() } ?: project else project.ifEmpty { title.orEmpty() }
}

fun TaskDto.cardSubtitle(refs: References, mode: String): String {
    val project = projectOf(refs)?.label.orEmpty()
    val own = title?.takeIf { it.isNotBlank() }
    return when {
        mode == "title" -> if (own == null) "" else project
        project.isEmpty() -> ""
        else -> own.orEmpty()
    }
}

/** Text style a status gives to the tasks it marks. */
fun TextStyle.withStatus(status: StatusDto?): TextStyle {
    if (status == null) return this
    val decorations = listOfNotNull(
        TextDecoration.LineThrough.takeIf { status.strikethrough },
        TextDecoration.Underline.takeIf { status.underline },
    )
    return copy(
        fontWeight = if (status.bold) FontWeight.Bold else fontWeight,
        fontStyle = if (status.italic) FontStyle.Italic else fontStyle,
        textDecoration = if (decorations.isEmpty()) textDecoration else TextDecoration.combine(decorations),
    )
}

/** Card of a task: stripe in the colour of the project, time, people, status, place. */
@Composable
fun TaskCard(
    task: TaskDto,
    refs: References,
    titleMode: String,
    showPerson: Boolean,
    day: LocalDate?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val projectColor = parseColor(task.projectOf(refs)?.color) ?: MaterialTheme.colorScheme.outline
    val status = task.statusOf(refs)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(projectColor))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (task.isMilestone) {
                        Icon(Icons.Outlined.Flag, stringResource(R.string.milestone), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        task.cardTitle(refs, titleMode),
                        style = MaterialTheme.typography.titleSmall.withStatus(status),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                    if (task.isLocked) Icon(Icons.Outlined.Lock, stringResource(R.string.a11y_locked), Modifier.padding(start = 4.dp).size(16.dp), tint = iconTint)
                    if (task.isRecurring) Icon(Icons.Outlined.Repeat, stringResource(R.string.a11y_recurring), Modifier.padding(start = 4.dp).size(16.dp), tint = iconTint)
                    if (task.hasAttachments) Icon(Icons.Outlined.AttachFile, stringResource(R.string.a11y_attachments), Modifier.padding(start = 4.dp).size(16.dp), tint = iconTint)
                }
                val subtitle = task.cardSubtitle(refs, titleMode)
                if (subtitle.isNotBlank() && !compact) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Meta(Icons.Outlined.Schedule, task.timeLabel() + multiDaySuffix(task, day))
                    task.placeLabel?.let { Meta(Icons.Outlined.Place, it, Modifier.weight(1f, fill = false)) }
                }
                val people = showPerson && task.users.isNotEmpty()
                if (people || status != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (people) PeopleRow(task.users, Modifier.weight(1f, fill = false))
                        if (status != null) StatusChip(status.name, parseColor(status.color))
                    }
                }
            }
        }
    }
}

@Composable
private fun multiDaySuffix(task: TaskDto, day: LocalDate?): String {
    if (!task.isMultiDay || day == null) return ""
    val start = runCatching { LocalDate.parse(task.startDate) }.getOrNull() ?: return ""
    val end = runCatching { LocalDate.parse(task.lastDate) }.getOrNull() ?: return ""
    val index = ChronoUnit.DAYS.between(start, day) + 1
    val total = ChronoUnit.DAYS.between(start, end) + 1
    return " · " + stringResource(R.string.day_x_of_y, index, total)
}

@Composable
private fun Meta(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun StatusChip(name: String, color: Color?) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorDot(color, 8)
        Spacer(Modifier.width(6.dp))
        Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One person with their name; several as overlapping avatars (3 at most, then +N). Screen readers get all the names. */
@Composable
private fun PeopleRow(users: List<UserDto>, modifier: Modifier = Modifier) {
    val single = users.singleOrNull()
    if (single != null) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            UserAvatar(single, 20)
            Spacer(Modifier.width(6.dp))
            Text(single.displayName, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    val names = users.joinToString(", ") { it.displayName }
    Row(modifier.clearAndSetSemantics { contentDescription = names }, verticalAlignment = Alignment.CenterVertically) {
        val shown = users.take(3)
        shown.forEachIndexed { index, user ->
            Box(Modifier.offset(x = (-6 * index).dp)) { UserAvatar(user, 22) }
        }
        if (users.size > shown.size) {
            Text(
                "+${users.size - shown.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.offset(x = (-6 * (shown.size - 1) + 4).dp),
            )
        }
    }
}

/** Picture of the person when there is one, initials otherwise. Only a path on the user's own server is loaded. */
@Composable
fun UserAvatar(user: UserDto, size: Int) {
    val image = avatarUrl(LocalContext.current.graph.repository.serverUrl, user.image)
    // The initials until the picture is there: they would show through a transparent one
    var loaded by remember(image) { mutableStateOf(false) }
    Box {
        if (!loaded) Avatar(user.displayName, parseColor(user.color), size)
        if (image != null) {
            AsyncImage(image, null, Modifier.size(size.dp).clip(CircleShape), contentScale = ContentScale.Crop, onSuccess = { loaded = true })
        }
    }
}

/**
 * Address of the photo of a person: the server keeps `avatars/<file>` and serves it at `/api/avatars/<file>`.
 * The server writes each photo in AVIF and in WebP; Android does not read its AVIF, so the WebP copy is asked.
 * A file name only, never an address elsewhere nor a way out of the folder.
 */
internal fun avatarUrl(server: String, image: String?): String? {
    val file = image?.trim()?.removePrefix("/api/avatars/")?.removePrefix("avatars/") ?: return null
    if (!file.matches(AVATAR_FILE)) return null
    val name = if (file.endsWith(".avif", ignoreCase = true)) file.dropLast(".avif".length) + ".webp" else file
    return "$server/api/avatars/$name"
}

private val AVATAR_FILE = Regex("""[A-Za-z0-9_-][A-Za-z0-9._-]*""")

/** Initials on the colour of the person. */
@Composable
fun Avatar(name: String, color: Color?, size: Int) {
    val background = color ?: MaterialTheme.colorScheme.secondaryContainer
    val initials = name.split(' ', '-').mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }.take(2).joinToString("").uppercase()
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(background)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            color = if (color != null) background.contentColor() else MaterialTheme.colorScheme.onSecondaryContainer,
            style = if (size < 28) MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.titleSmall,
            maxLines = 1,
        )
    }
}
