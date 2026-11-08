package com.humbleplanning.app

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.humbleplanning.app.reminders.ReminderReceiver
import com.humbleplanning.app.ui.HumbleApp
import com.humbleplanning.app.ui.localizedContext
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localizedContext(newBase, newBase.graph.repository.language.value))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        adoptSystemLanguage()
        // Language changed in the app: Android 13+ recreates the activity itself once told, older versions need it here
        lifecycleScope.launch {
            graph.repository.language.drop(1).collect { language ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    getSystemService(LocaleManager::class.java).applicationLocales =
                        if (language.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language)
                } else {
                    recreate()
                }
            }
        }
        setContent { HumbleApp() }
    }

    /** Android 13+ also offers the app language in the phone settings: that choice replaces the one saved here. */
    private fun adoptSystemLanguage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val repository = graph.repository
        val system = getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags().substringBefore(',').substringBefore('-')
        if (system != repository.language.value && (system.isEmpty() || system in APP_LANGUAGES)) {
            repository.setLanguage(system)
            recreate()
        }
    }

    private companion object {
        val APP_LANGUAGES = setOf("fr", "en", "es", "de")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(ReminderReceiver.EXTRA_TASK_ID)?.let {
            PendingActions.openTask(it)
            intent.removeExtra(ReminderReceiver.EXTRA_TASK_ID)
        }
        intent.dataString?.let { link ->
            if (PendingActions.handleLink(link)) intent.data = null
        }
    }
}
