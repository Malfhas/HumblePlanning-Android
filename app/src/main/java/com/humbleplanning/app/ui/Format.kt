package com.humbleplanning.app.ui

import android.icu.text.RelativeDateTimeFormatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.humbleplanning.app.data.TaskDto
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Composable
fun appLocale(): Locale {
    val configuration = LocalConfiguration.current
    return remember(configuration) { configuration.locales[0] ?: Locale.getDefault() }
}

fun LocalDate.monday(): LocalDate = with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

fun String.toDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()

fun String.capitalized(locale: Locale): String = replaceFirstChar { it.titlecase(locale) }

/** "Monday 3 November" ("monday 3 november" inside a sentence) */
fun LocalDate.longLabel(locale: Locale, capitalize: Boolean = true): String =
    DateTimeFormatter.ofPattern("EEEE d MMMM", locale).format(this).let { if (capitalize) it.capitalized(locale) else it }

/** "3 Nov 2026" */
fun LocalDate.mediumLabel(locale: Locale): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(this)

/** "November 2026" */
fun LocalDate.monthLabel(locale: Locale): String = DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(this).capitalized(locale)

fun DayOfWeek.shortLabel(locale: Locale): String = getDisplayName(TextStyle.SHORT, locale).trimEnd('.').capitalized(locale)

fun DayOfWeek.narrowLabel(locale: Locale): String = getDisplayName(TextStyle.NARROW, locale).capitalized(locale)

/** "08:35:00" -> "08:35" */
fun String.hhmm(): String = take(5)

/** "8:05" -> "08:05", comparable as text. */
fun String.paddedTime(): String = hhmm().padStart(5, '0')

fun TaskDto.occursOn(date: LocalDate): Boolean {
    val d = date.toString()
    return startDate <= d && d <= lastDate
}

/** Like the web planning: a task without a start time lasts the whole day. */
val TaskDto.isAllDay: Boolean get() = startTime.isNullOrBlank()

/** "08:00 – 12:00", "08:00" with no end, null for a whole day. */
fun TaskDto.timeRange(): String? {
    val start = startTime?.takeIf { it.isNotBlank() }?.hhmm() ?: return null
    val end = endTime?.takeIf { it.isNotBlank() }?.hhmm() ?: return start
    return "$start – $end"
}

/** Order inside a day: multi-day tasks, then all-day ones, then by start time. */
fun List<TaskDto>.sortedForDay(): List<TaskDto> = sortedWith(
    compareBy<TaskDto>(
        {
            when {
                it.isMultiDay -> 0
                it.isAllDay -> 1
                else -> 2
            }
        },
        { it.startTime.orEmpty().paddedTime() },
        { it.title.orEmpty().lowercase() },
        { it.id },
    )
)

/** "5 minutes ago", in the language of the app; the ISO date itself when it cannot be read. */
fun relativeDate(iso: String?, locale: Locale, now: Instant = Instant.now()): String {
    val instant = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return iso.orEmpty()
    val formatter = RelativeDateTimeFormatter.getInstance(locale)
    val elapsed = Duration.between(instant, now)
    val minutes = elapsed.toMinutes()
    return when {
        minutes < 1 -> formatter.format(RelativeDateTimeFormatter.Direction.PLAIN, RelativeDateTimeFormatter.AbsoluteUnit.NOW)
        minutes < 60 -> formatter.format(minutes.toDouble(), RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.RelativeUnit.MINUTES)
        elapsed.toHours() < 24 -> formatter.format(elapsed.toHours().toDouble(), RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.RelativeUnit.HOURS)
        else -> formatter.format(elapsed.toDays().toDouble(), RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.RelativeUnit.DAYS)
    }
}

/** "1.2 MB" */
fun Long.fileSizeLabel(locale: Locale): String = when {
    this >= 1024 * 1024 -> String.format(locale, "%.1f MB", this / (1024.0 * 1024.0))
    this >= 1024 -> String.format(locale, "%d KB", this / 1024)
    else -> String.format(locale, "%d B", this)
}
