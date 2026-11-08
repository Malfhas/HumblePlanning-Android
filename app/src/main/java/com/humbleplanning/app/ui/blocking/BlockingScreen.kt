package com.humbleplanning.app.ui.blocking

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.humbleplanning.app.AppInfo
import com.humbleplanning.app.R
import com.humbleplanning.app.data.BlockingState
import com.humbleplanning.app.ui.ProgressButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val RETRY_SECONDS = 30
private const val STORE_ID = "com.humbleplanning.app"

/** Update through the store only, never by the app itself. A web page or the store is all a server can point at. */
private fun openStore(context: Context, storeUrl: String) {
    val url = storeUrl.trim().takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("market://", ignoreCase = true) }
        ?: "market://details?id=$STORE_ID"
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }.onFailure {
        // No store app on this phone
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$STORE_ID".toUri())) }
    }
}

/**
 * Blocking screen: server maintenance (423), with a new attempt every 30 s by itself;
 * app too old (426), with the way to the new version; role without the app permission (403),
 * with a new attempt once the administrator gave it back, or signing out.
 */
@Composable
fun BlockingScreen(state: BlockingState, onRetry: suspend () -> Boolean, onSignOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    var retrying by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(RETRY_SECONDS) }
    val retry: () -> Unit = {
        if (!retrying) scope.launch {
            retrying = true
            onRetry()
            retrying = false
            countdown = RETRY_SECONDS
        }
    }
    if (state is BlockingState.Maintenance) {
        LaunchedEffect(state) {
            while (true) {
                delay(1000)
                if (!retrying) countdown--
                if (countdown <= 0) retry()
            }
        }
    }
    // Touches stop here: without this they reach the app below, wherever this screen has nothing to tap
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(Unit) {}.safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(Modifier.size(88.dp).clip(CircleShape), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        when (state) {
                            is BlockingState.UpgradeRequired -> Icons.Outlined.SystemUpdate
                            is BlockingState.Maintenance -> Icons.Outlined.Construction
                            is BlockingState.AppNotAllowed -> Icons.Outlined.Block
                        },
                        null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(
                    when (state) {
                        is BlockingState.UpgradeRequired -> R.string.update_title
                        is BlockingState.Maintenance -> R.string.maintenance_title
                        is BlockingState.AppNotAllowed -> R.string.not_allowed_title
                    }
                ),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            when (state) {
                is BlockingState.Maintenance -> {
                    Text(
                        state.message.ifBlank { stringResource(R.string.maintenance_text) },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(32.dp))
                    ProgressButton(stringResource(R.string.retry), retrying, retry, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { countdown.coerceAtLeast(0) / RETRY_SECONDS.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                        drawStopIndicator = {},
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.maintenance_auto, countdown.coerceAtLeast(0)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is BlockingState.UpgradeRequired -> {
                    Text(
                        stringResource(R.string.update_text, AppInfo.VERSION, state.minimumVersion.ifBlank { "?" }),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(32.dp))
                    val context = LocalContext.current
                    ProgressButton(stringResource(R.string.update_open_store), false, { openStore(context, state.storeUrl) }, Modifier.fillMaxWidth(), icon = Icons.Outlined.SystemUpdate)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = retry, enabled = !retrying, modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium) {
                        Text(stringResource(R.string.retry))
                    }
                }
                is BlockingState.AppNotAllowed -> {
                    Text(
                        state.message.ifBlank { stringResource(R.string.not_allowed_text) },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(32.dp))
                    ProgressButton(stringResource(R.string.retry), retrying, retry, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium) {
                        Text(stringResource(R.string.sign_out))
                    }
                }
            }
        }
    }
}
