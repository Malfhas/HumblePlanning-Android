package com.humbleplanning.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.humbleplanning.app.AppGraph
import com.humbleplanning.app.alerts.AlertSync
import com.humbleplanning.app.graph
import com.humbleplanning.app.reminders.ReminderSync
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** ViewModel built with the shared objects of the app (manual injection). */
@Composable
inline fun <reified VM : ViewModel> humbleViewModel(key: String? = null, crossinline create: (AppGraph) -> VM): VM {
    val graph = LocalContext.current.graph
    return viewModel(key = key, factory = viewModelFactory { initializer { create(graph) } })
}

/** Same, for a screen whose input must survive the death of the process. */
@Composable
inline fun <reified VM : ViewModel> humbleSavedViewModel(key: String? = null, crossinline create: (AppGraph, SavedStateHandle) -> VM): VM {
    val graph = LocalContext.current.graph
    return viewModel(key = key, factory = viewModelFactory { initializer { create(graph, createSavedStateHandle()) } })
}

/** Attachments already opened on this phone: folder shared with other apps through the FileProvider. */
fun attachmentsDir(context: Context): File = File(context.cacheDir, "attachments")

/** Everything a sign-out stops on this phone: background work, opened attachments, then the session. */
fun signOut(context: Context) {
    val app = context.applicationContext
    ReminderSync.cancel(app)
    AlertSync.cancel(app)
    CoroutineScope(Dispatchers.IO).launch { attachmentsDir(app).deleteRecursively() }
    app.graph.repository.logout()
}
