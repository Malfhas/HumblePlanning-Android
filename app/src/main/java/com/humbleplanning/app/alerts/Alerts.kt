package com.humbleplanning.app.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.humbleplanning.app.HumbleApplication
import com.humbleplanning.app.MainActivity
import com.humbleplanning.app.R
import com.humbleplanning.app.data.MobileAlert
import com.humbleplanning.app.data.ApiException
import com.humbleplanning.app.reminders.ReminderReceiver
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Shows a server alert on the phone; tapping it opens the task. */
class AlertNotifier(private val context: Context) {
    fun show(alert: MobileAlert) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        // Android 13+: the user can refuse or withdraw the permission at any time
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel()
        val open = Intent(context, MainActivity::class.java).apply {
            alert.taskId?.let { putExtra(ReminderReceiver.EXTRA_TASK_ID, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(context, alert.id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(alert.title)
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(alert.id.hashCode(), notification)
    }

    fun buildSyncNotification() = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.alerts_syncing))
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.alerts_channel), NotificationManager.IMPORTANCE_HIGH)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "alerts"
    }
}

/** Reads the new alerts and shows them: woken up by a push, or periodically without Firebase. */
class AlertSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as HumbleApplication).graph.repository
        return try {
            repository.syncAlerts(notify = true)
            Result.success()
        } catch (e: ApiException.Network) {
            Result.retry()
        } catch (e: ApiException) {
            Result.success()
        }
    }

    // Only used when an expedited run has to fall back on a foreground service (before Android 12)
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val graph = (applicationContext as HumbleApplication).graph
        graph.alertNotifier.ensureChannel()
        return ForegroundInfo(SYNC_NOTIFICATION_ID, graph.alertNotifier.buildSyncNotification())
    }

    private companion object {
        const val SYNC_NOTIFICATION_ID = 4101
    }
}

object AlertSync {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "alerts_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AlertSyncWorker>(15, TimeUnit.MINUTES).setConstraints(network).build(),
        )
    }

    /** A push arrived: read now, the phone just left Doze for this. */
    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "alerts_sync_now",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<AlertSyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(network)
                .build(),
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("alerts_sync")
        WorkManager.getInstance(context).cancelUniqueWork("alerts_sync_now")
    }
}

/** Firebase is only a wake-up here: a message holds an alert id, the content comes from the user's server. */
class HumbleMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        (applicationContext as HumbleApplication).graph.repository.onPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        AlertSync.runNow(applicationContext)
    }
}

/** Firebase is optional: a build without google-services.json has no app and simply gets no token. */
object PushTokens {
    suspend fun current(context: Context): String? {
        if (FirebaseApp.getApps(context).isEmpty()) return null
        return suspendCancellableCoroutine { continuation ->
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resume(null) }
        }
    }
}
