package com.humbleplanning.app.ui.assistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.humbleplanning.app.R
import com.humbleplanning.app.data.ChatQuota
import com.humbleplanning.app.data.QuotaLevel
import com.humbleplanning.app.ui.appLocale
import com.humbleplanning.app.ui.mediumLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch

/** The reset day of the quota, in the phone's time zone; empty when the server gave none. */
@Composable
internal fun quotaResetLabel(quota: ChatQuota): String {
    val day: LocalDate = runCatching { Instant.parse(quota.resetsAt).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull() ?: return ""
    return day.mediumLabel(appLocale())
}

/** What the gauge says, on two lines; "Unlimited quota" for an exempt account. */
@Composable
internal fun quotaText(quota: ChatQuota): String =
    if (quota.unlimited) stringResource(R.string.assistant_quota_unlimited)
    else listOf(stringResource(R.string.assistant_quota_used, quota.percent), stringResource(R.string.assistant_quota_reset, quotaResetLabel(quota))).joinToString("\n")

/**
 * Circular gauge of the monthly quota, as on the web: a thin ring whose coloured arc shows the share used,
 * green, then orange from 70%, red from 90%, a filled disc at 100%. Tapping it tells the detail. An account
 * exempt from the quota sees a neutral ring; quota off, nothing at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotaGauge(quota: ChatQuota?, modifier: Modifier = Modifier) {
    if (quota == null || !quota.enabled) return
    val text = quotaText(quota)
    val tooltip = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val track = scheme.outlineVariant
    val arcColor = when (quota.level) {
        QuotaLevel.Success -> Color(0xFF16A34A)
        QuotaLevel.Warning -> Color(0xFFF59E0B)
        QuotaLevel.Danger -> scheme.error
    }
    val full = !quota.unlimited && quota.percent >= 100
    val fill = scheme.error.copy(alpha = 0.22f)
    val share = quota.percent.coerceIn(0, 100) / 100f
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(text) } },
        state = tooltip,
        modifier = modifier,
    ) {
        Box(
            Modifier
                .clickable(role = Role.Button) { scope.launch { tooltip.show() } }
                .padding(10.dp)
                .semantics { contentDescription = text.replace("\n", " · ") },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(22.dp)) {
                val stroke = 2.5.dp.toPx()
                val inset = stroke / 2
                val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                if (full) drawCircle(fill, radius = size.minDimension / 2 - inset)
                drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
                if (!quota.unlimited && share > 0f) {
                    // The arc starts at noon, like a clock
                    drawArc(
                        arcColor, -90f, 360f * share, useCenter = false, topLeft = topLeft, size = arcSize,
                        style = Stroke(stroke, cap = if (full) StrokeCap.Butt else StrokeCap.Round),
                    )
                }
            }
        }
    }
}
