package com.humbleplanning.app.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.humbleplanning.app.HumbleApplication
import com.humbleplanning.app.MainActivity
import com.humbleplanning.app.R
import com.humbleplanning.app.data.ApiClient
import com.humbleplanning.app.data.EncryptedFiles
import com.humbleplanning.app.data.Prefs
import com.humbleplanning.app.data.TaskDto
import com.humbleplanning.app.ui.localizedContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** Délai du rappel avant le début d'une tâche. */
enum class ReminderOffset(val key: String, val minutes: Int) {
    None("none", 0), Min5("5min", 5), Min15("15min", 15), Min30("30min", 30), H1("1h", 60), H4("4h", 240), Day1("1j", 1440);

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: Min15
    }
}

@Serializable
data class Reminder(val taskId: String, val fireAtMillis: Long, val title: String, val text: String) {
    /** Identifiant stable de l'alarme et de la notification. */
    val requestCode: Int get() = taskId.hashCode()
}

/** Réglages et état des rappels. */
class ReminderSettings(private val prefs: Prefs, private val files: EncryptedFiles) {
    private val serializer = ListSerializer(Reminder.serializer())
    private companion object { const val FILE = "reminders.bin" }

    var offset: ReminderOffset
        get() = ReminderOffset.from(prefs.string("reminder_offset", ReminderOffset.Min15.key))
        set(value) = prefs.putString("reminder_offset", value.key)

    /** Rappels pour toutes les tâches visibles, sinon seulement les miennes (défaut). */
    var targetAll: Boolean
        get() = prefs.boolean("reminder_all", false)
        set(value) = prefs.putBoolean("reminder_all", value)

    var lastSyncFailed: Boolean
        get() = prefs.boolean("reminder_failed", false)
        set(value) = prefs.putBoolean("reminder_failed", value)

    /** Last scheduled reminders, rescheduled when the phone restarts, without network. Task titles: kept encrypted. */
    var scheduled: List<Reminder>
        get() = runCatching { ApiClient.json.decodeFromString(serializer, files.read(FILE).orEmpty().ifEmpty { "[]" }) }.getOrDefault(emptyList())
        set(value) = files.write(FILE, ApiClient.json.encodeToString(serializer, value))
}

object ReminderPlanner {
    private const val MAX_REMINDERS = 400

    /** Start of a task: its start time, otherwise the instance's morning start. Wall-clock, like on the server. */
    fun startTime(task: TaskDto, defaultStart: String): LocalTime =
        parseTime(task.startTime) ?: parseTime(defaultStart) ?: LocalTime.of(8, 0)

    private fun parseTime(value: String?): LocalTime? {
        val text = value?.takeIf { it.length >= 5 } ?: return null
        val hour = text.take(2).toIntOrNull() ?: return null
        val minute = text.substring(3, 5).toIntOrNull() ?: return null
        return if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
    }

    fun plan(
        tasks: List<TaskDto>,
        defaultStart: String,
        offset: ReminderOffset,
        now: Instant,
        zone: ZoneId,
        label: (TaskDto) -> Pair<String, String>,
    ): List<Reminder> {
        if (offset == ReminderOffset.None) return emptyList()
        val format = DateTimeFormatter.ofPattern("HH:mm")
        return tasks
            .mapNotNull { task ->
                val date = runCatching { LocalDate.parse(task.startDate) }.getOrNull() ?: return@mapNotNull null
                val time = startTime(task, defaultStart)
                val start = LocalDateTime.of(date, time).atZone(zone).toInstant()
                val fireAt = start.minusSeconds(offset.minutes * 60L)
                if (!fireAt.isAfter(now)) return@mapNotNull null
                val (title, detail) = label(task)
                Reminder(task.id, fireAt.toEpochMilli(), title, "${time.format(format)} · $detail")
            }
            .sortedBy { it.fireAtMillis }
            .take(MAX_REMINDERS)
    }
}

/** Alarmes exactes (compatibles avec la mise en veille) qui déclenchent ReminderReceiver. */
class ReminderScheduler(private val context: Context, private val settings: ReminderSettings) {
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)

    fun exactAllowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun pendingIntent(reminder: Reminder?, requestCode: Int, create: Boolean): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java)
        if (reminder != null) {
            intent.putExtra(ReminderReceiver.EXTRA_TASK_ID, reminder.taskId)
            intent.putExtra(ReminderReceiver.EXTRA_TITLE, reminder.title)
            intent.putExtra(ReminderReceiver.EXTRA_TEXT, reminder.text)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    /** Remplace tous les rappels programmés par ceux-ci. */
    fun schedule(reminders: List<Reminder>) {
        cancelAll()
        val exact = exactAllowed()
        reminders.forEach { reminder ->
            val operation = pendingIntent(reminder, reminder.requestCode, create = true) ?: return@forEach
            if (exact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.fireAtMillis, operation)
            } else {
                // Sans l'autorisation « Alarmes et rappels » : alarme approximative
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.fireAtMillis, operation)
            }
        }
        settings.scheduled = reminders
    }

    fun cancelAll() {
        settings.scheduled.forEach { reminder ->
            pendingIntent(null, reminder.requestCode, create = false)?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
        }
        settings.scheduled = emptyList()
    }
}

/** Affiche le rappel d'une tâche ; le toucher ouvre la tâche dans l'app. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(systemContext: Context, intent: Intent) {
        val app = systemContext.applicationContext as HumbleApplication
        val context = localizedContext(systemContext, app.graph.repository.effectiveLanguage())
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        // Android 13+: the user can refuse or withdraw the permission at any time
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(context)
        val open = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_TASK_ID, taskId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(context, taskId.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { context.getString(R.string.notif_titre) })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(taskId.hashCode(), notification)
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TITLE = "task_title"
        const val EXTRA_TEXT = "task_text"
        private const val CHANNEL_ID = "reminders"

        fun ensureChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_canal), NotificationManager.IMPORTANCE_HIGH)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

/** Redémarrage du téléphone, mise à jour de l'app, autorisation d'alarmes : reprogrammer, puis resynchroniser. */
class BootReceiver : BroadcastReceiver() {
    private companion object {
        val HANDLED_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        val graph = (context.applicationContext as HumbleApplication).graph
        val now = System.currentTimeMillis()
        graph.reminderScheduler.schedule(graph.reminderSettings.scheduled.filter { it.fireAtMillis > now })
        ReminderSync.enqueueOnce(context)
        ReminderSync.schedulePeriodic(context)
    }
}

/** Synchronisation des rappels en arrière-plan (toutes les 6 h avec réseau, ou une fois). */
class ReminderSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as HumbleApplication).graph
        return if (graph.repository.syncReminders()) Result.success() else Result.retry()
    }
}

object ReminderSync {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "reminders_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderSyncWorker>(6, TimeUnit.HOURS).setConstraints(network).build(),
        )
    }

    fun enqueueOnce(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "reminders_sync_once",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReminderSyncWorker>().setConstraints(network).build(),
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("reminders_sync")
        WorkManager.getInstance(context).cancelUniqueWork("reminders_sync_once")
    }
}
