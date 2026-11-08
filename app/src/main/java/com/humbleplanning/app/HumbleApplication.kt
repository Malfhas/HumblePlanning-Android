package com.humbleplanning.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.humbleplanning.app.alerts.AlertNotifier
import com.humbleplanning.app.alerts.PushTokens
import com.humbleplanning.app.data.Crypto
import com.humbleplanning.app.data.EncryptedFiles
import com.humbleplanning.app.data.Prefs
import com.humbleplanning.app.data.Repository
import com.humbleplanning.app.data.SecureStore
import com.humbleplanning.app.data.SessionStore
import com.humbleplanning.app.data.buildHttpClient
import com.humbleplanning.app.reminders.ReminderScheduler
import com.humbleplanning.app.reminders.ReminderSettings

/** Shared objects of the app (manual injection, no library). */
class AppGraph(context: Context) {
    val prefs = Prefs(context)
    private val crypto = Crypto()
    private val secure = SecureStore(prefs, crypto)
    private val files = EncryptedFiles(context, crypto)
    val sessions = SessionStore(secure)
    val alertNotifier = AlertNotifier(context)
    val reminderSettings = ReminderSettings(prefs, files)
    val reminderScheduler = ReminderScheduler(context, reminderSettings)
    val http = buildHttpClient(sessions) { repository.effectiveLanguage() }
    val repository: Repository = Repository(prefs, sessions, files, http, reminderSettings, reminderScheduler, alertNotifier).also { repository ->
        repository.pushTokenProvider = { PushTokens.current(context) }
    }
}

class HumbleApplication : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        watchNetwork()
        // Real time while the app is visible; push and background sync otherwise
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = graph.repository.onForeground()
            override fun onStop(owner: LifecycleOwner) = graph.repository.onBackground()
        })
    }

    /** Images (avatars, logo) with the client of the API: the session only goes to its own server. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.http })) }
        .build()

    private fun watchNetwork() {
        val manager = getSystemService(ConnectivityManager::class.java)
        val hasInternet = manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        graph.repository.onNetworkChanged(hasInternet)
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = graph.repository.onNetworkChanged(true)
            override fun onLost(network: Network) = graph.repository.onNetworkChanged(false)
        })
    }
}

val Context.graph: AppGraph get() = (applicationContext as HumbleApplication).graph
