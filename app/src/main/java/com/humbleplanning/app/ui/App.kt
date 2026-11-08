package com.humbleplanning.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.humbleplanning.app.PendingActions
import com.humbleplanning.app.QrLogin
import com.humbleplanning.app.R
import com.humbleplanning.app.graph
import com.humbleplanning.app.ui.assistant.ConversationScreen
import com.humbleplanning.app.ui.blocking.BlockingScreen
import com.humbleplanning.app.ui.login.LoginScreen
import com.humbleplanning.app.ui.login.TwoFactorSetupScreen
import com.humbleplanning.app.ui.main.MainScreen
import com.humbleplanning.app.ui.scanner.ScannerScreen
import com.humbleplanning.app.ui.task.TaskDetailScreen
import com.humbleplanning.app.ui.task.TaskFormScreen
import kotlinx.serialization.Serializable

@Serializable object LoginRoute
@Serializable object MainRoute
@Serializable object ScannerRoute
@Serializable object TwoFactorSetupRoute
@Serializable data class TaskRoute(val id: String)
@Serializable data class TaskFormRoute(val id: String? = null, val date: String? = null, val copyOf: String? = null, val userId: String? = null)
@Serializable data class ConversationRoute(val id: String? = null)

@Composable
fun HumbleApp() {
    val context = LocalContext.current
    val repository = context.graph.repository
    val theme by repository.theme.collectAsStateWithLifecycle()
    val dynamic by repository.dynamicColor.collectAsStateWithLifecycle()
    HumbleTheme(theme, dynamic) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val nav = rememberNavController()
            val blocking by repository.blocking.collectAsStateWithLifecycle()
            val sessionExpired by repository.sessionExpired.collectAsStateWithLifecycle()
            val qrLogin by PendingActions.qrLogin.collectAsStateWithLifecycle()
            val openTask by PendingActions.openTaskId.collectAsStateWithLifecycle()
            var expiredNotice by remember { mutableStateOf(false) }
            // Sign-in link the user confirmed: only then does the sign-in screen use it
            var confirmedQr by remember { mutableStateOf<QrLogin?>(null) }

            // Session refused by the server: back to the sign-in screen, with a notice
            LaunchedEffect(sessionExpired) {
                if (sessionExpired) {
                    signOut(context)
                    expiredNotice = true
                    nav.navigate(LoginRoute) { popUpTo(nav.graph.id) { inclusive = true } }
                }
            }
            // Reminder or alert tapped: open the task
            LaunchedEffect(openTask) {
                val id = openTask ?: return@LaunchedEffect
                if (repository.isLoggedIn) {
                    PendingActions.openTaskId.value = null
                    nav.navigate(TaskRoute(id))
                }
            }

            Box(Modifier.fillMaxSize()) {
                // What the blocking screen covers is not read by screen readers either
                Box(if (blocking != null) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize()) {
                    AppNavHost(
                        nav,
                        expiredNotice,
                        onNoticeShown = { expiredNotice = false },
                        confirmedQr = confirmedQr,
                        onQrUsed = { confirmedQr = null },
                    )
                }
                // Maintenance (423), app too old (426) or app not allowed (403): blocking screen above the app
                var lastBlocking by remember { mutableStateOf(blocking) }
                if (blocking != null) lastBlocking = blocking
                AnimatedVisibility(blocking != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
                    lastBlocking?.let {
                        BlockingScreen(it, onRetry = { repository.retryBlocked() }, onSignOut = {
                            signOut(context)
                            nav.navigate(LoginRoute) { popUpTo(nav.graph.id) { inclusive = true } }
                        })
                    }
                }
            }

            // A link or a QR code never signs in by itself: the user sees the server and decides
            qrLogin?.let { qr ->
                QrLoginDialog(
                    host = repository.hostOf(qr.serverUrl),
                    signedIn = repository.isLoggedIn,
                    onConfirm = {
                        PendingActions.qrLogin.value = null
                        if (repository.isLoggedIn) signOut(context)
                        confirmedQr = qr
                        if (nav.currentDestination?.route?.contains("LoginRoute") != true) {
                            nav.navigate(LoginRoute) { popUpTo(nav.graph.id) { inclusive = true } }
                        }
                    },
                    onRefuse = { PendingActions.qrLogin.value = null },
                )
            }
        }
    }
}

/** Shows the server in clear before anything is sent to it. `host` is null when its address is not acceptable. */
@Composable
private fun QrLoginDialog(host: String?, signedIn: Boolean, onConfirm: () -> Unit, onRefuse: () -> Unit) {
    if (host == null) {
        AlertDialog(
            onDismissRequest = onRefuse,
            icon = { Icon(Icons.Outlined.WarningAmber, null) },
            title = { Text(stringResource(R.string.qr_invalid_title)) },
            text = { Text(stringResource(R.string.qr_invalid_text)) },
            confirmButton = { TextButton(onClick = onRefuse) { Text(stringResource(R.string.close)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onRefuse,
        icon = { Icon(Icons.Outlined.QrCodeScanner, null) },
        title = { Text(stringResource(R.string.qr_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.qr_confirm_text))
                Text(host, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                if (signedIn) Text(stringResource(R.string.qr_confirm_signed_in))
                Text(stringResource(R.string.qr_confirm_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = onConfirm, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.sign_in)) } },
        dismissButton = { TextButton(onClick = onRefuse) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AppNavHost(
    nav: NavHostController,
    expiredNotice: Boolean,
    onNoticeShown: () -> Unit,
    confirmedQr: QrLogin?,
    onQrUsed: () -> Unit,
) {
    val repository = LocalContext.current.graph.repository
    val start: Any = remember { if (repository.isLoggedIn) MainRoute else LoginRoute }
    NavHost(
        navController = nav,
        startDestination = start,
        enterTransition = { slideInHorizontally(tween(260)) { it / 6 } + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { slideOutHorizontally(tween(220)) { it / 6 } + fadeOut(tween(220)) },
    ) {
        composable<LoginRoute> {
            LoginScreen(
                sessionExpired = expiredNotice,
                onNoticeShown = onNoticeShown,
                confirmedQr = confirmedQr,
                onQrUsed = onQrUsed,
                onScan = { nav.navigate(ScannerRoute) },
                onLoggedIn = { nav.navigate(MainRoute) { popUpTo(nav.graph.id) { inclusive = true } } },
            )
        }
        composable<ScannerRoute> {
            ScannerScreen(
                onResult = { link ->
                    nav.popBackStack()
                    PendingActions.handleLink(link)
                },
                onClose = { nav.popBackStack() },
            )
        }
        composable<MainRoute> {
            // A notification tapped before signing in: its task opens once in the app
            LaunchedEffect(Unit) {
                PendingActions.openTaskId.value?.let { id ->
                    PendingActions.openTaskId.value = null
                    nav.navigate(TaskRoute(id))
                }
            }
            MainScreen(
                onOpenTask = { nav.navigate(TaskRoute(it)) },
                onNewTask = { date, userId -> nav.navigate(TaskFormRoute(date = date, userId = userId)) },
                onOpenConversation = { nav.navigate(ConversationRoute(it)) },
                onSetupTwoFactor = { nav.navigate(TwoFactorSetupRoute) },
                onLoggedOut = { nav.navigate(LoginRoute) { popUpTo(nav.graph.id) { inclusive = true } } },
            )
        }
        composable<TaskRoute> { entry ->
            val route = entry.toRoute<TaskRoute>()
            TaskDetailScreen(
                taskId = route.id,
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate(TaskFormRoute(id = route.id)) },
                onCopy = { nav.navigate(TaskFormRoute(copyOf = route.id)) },
            )
        }
        composable<TaskFormRoute> { entry ->
            val route = entry.toRoute<TaskFormRoute>()
            TaskFormScreen(
                taskId = route.id,
                defaultDate = route.date,
                defaultUserId = route.userId,
                copyOf = route.copyOf,
                onClose = { nav.popBackStack() },
            )
        }
        composable<ConversationRoute> { entry ->
            ConversationScreen(
                conversationId = entry.toRoute<ConversationRoute>().id,
                onBack = { nav.popBackStack() },
            )
        }
        composable<TwoFactorSetupRoute> {
            TwoFactorSetupScreen(onClose = { nav.popBackStack() })
        }
    }
}
