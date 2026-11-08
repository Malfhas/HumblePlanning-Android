package com.humbleplanning.app.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.humbleplanning.app.AppInfo
import com.humbleplanning.app.R
import com.humbleplanning.app.data.DeviceDto
import com.humbleplanning.app.data.ThemeMode
import com.humbleplanning.app.graph
import com.humbleplanning.app.reminders.ReminderOffset
import com.humbleplanning.app.ui.AppSegmented
import com.humbleplanning.app.ui.Banner
import com.humbleplanning.app.ui.BannerTone
import com.humbleplanning.app.ui.LocalHumbleColors
import com.humbleplanning.app.ui.PickerItem
import com.humbleplanning.app.ui.PickerSheet
import com.humbleplanning.app.ui.ProgressButton
import com.humbleplanning.app.ui.SectionCard
import com.humbleplanning.app.ui.apiErrorMessage
import com.humbleplanning.app.ui.appLocale
import com.humbleplanning.app.ui.humbleViewModel
import com.humbleplanning.app.ui.planning.UserAvatar
import com.humbleplanning.app.ui.relativeDate

private const val LICENSE_NAME = "AGPL-3.0-or-later"
private const val DEVICES_SHOWN = 3

@Composable
fun SettingsScreen(onSetupTwoFactor: () -> Unit, onLoggedOut: () -> Unit, bottomPadding: Dp) {
    val vm = humbleViewModel { SettingsViewModel(it) }
    val context = LocalContext.current
    val theme by vm.theme.collectAsStateWithLifecycle()
    val dynamic by vm.dynamicColor.collectAsStateWithLifecycle()
    val cardTitle by vm.cardTitle.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()
    val reminder by vm.reminder.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val security by vm.security.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val refs by vm.references.collectAsStateWithLifecycle()
    val user = refs.user
    var showProfile by rememberSaveable { mutableStateOf(false) }
    var showOffsets by rememberSaveable { mutableStateOf(false) }
    var showLanguages by rememberSaveable { mutableStateOf(false) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var showDisableTwoFactor by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var deviceToRevoke by remember { mutableStateOf<DeviceDto?>(null) }

    // State of the permissions, read again when back from the phone settings
    var resumeTick by rememberSaveable { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumeTick++
        onPauseOrDispose { }
    }
    val notificationsOn = resumeTick >= 0 && NotificationManagerCompat.from(context).areNotificationsEnabled()
    val exactOn = resumeTick >= 0 && context.graph.reminderScheduler.exactAllowed()
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        resumeTick++
        if (it) vm.syncReminders(context)
    }
    val fixNotifications = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsOn) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
        }
        Unit
    }
    val openWeb = { url: String -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }; Unit }
    val locale = appLocale()

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = bottomPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item { Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() }) }

        // Profile
        item {
            SectionCard {
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { showProfile = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    UserAvatar(user, 52)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(user.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        user.email?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(vm.serverUrl.removePrefix("https://"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Outlined.Edit, stringResource(R.string.profile_edit), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Display
        item {
            SectionCard(title = stringResource(R.string.settings_display)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Labeled(Icons.Outlined.Palette, stringResource(R.string.settings_theme)) {
                        AppSegmented(
                            listOf(
                                ThemeMode.System to stringResource(R.string.theme_system),
                                ThemeMode.Light to stringResource(R.string.theme_light),
                                ThemeMode.Dark to stringResource(R.string.theme_dark),
                            ),
                            theme,
                            onSelect = vm::setTheme,
                        )
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SwitchRow(stringResource(R.string.settings_phone_colors), stringResource(R.string.settings_phone_colors_help), dynamic, onChange = vm::setDynamicColor)
                    }
                    Labeled(null, stringResource(R.string.settings_card_title)) {
                        AppSegmented(
                            listOf("project" to stringResource(R.string.card_title_project), "title" to stringResource(R.string.card_title_task)),
                            cardTitle,
                            onSelect = vm::setCardTitle,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavRow(Icons.Outlined.Language, stringResource(R.string.settings_language), languageLabel(language)) { showLanguages = true }
            }
        }

        // Alerts
        if (refs.config["MOBILE_PUSH_ENABLED"] != "false") {
            item {
                SectionCard(title = stringResource(R.string.settings_alerts)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        alerts.error?.let { Banner(apiErrorMessage(it), BannerTone.Error) }
                        if (alerts.testSent) Banner(stringResource(R.string.alerts_test_sent), BannerTone.Info, icon = Icons.Outlined.CheckCircle)
                        if (alerts.testNotDelivered) Banner(stringResource(R.string.alerts_test_not_delivered), BannerTone.Warning)
                        val allowed = user.mobilePushNotifications
                        SwitchRow(
                            stringResource(R.string.alerts_on_phone),
                            stringResource(if (allowed) R.string.alerts_on_phone_help else R.string.alerts_off_by_admin),
                            allowed && user.mobilePushNotificationsUser,
                            enabled = allowed,
                            onChange = vm::setMobilePush,
                        )
                        PermissionRow(Icons.Outlined.NotificationsActive, stringResource(R.string.perm_notifications), notificationsOn, fixNotifications)
                        OutlinedButton(onClick = vm::sendTest, enabled = !alerts.testSending, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                            if (alerts.testSending) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.alerts_send_test))
                            }
                        }
                    }
                }
            }
        }

        // Reminders
        item {
            SectionCard(title = stringResource(R.string.settings_reminders)) {
                NavRow(Icons.Outlined.Timer, stringResource(R.string.reminder_delay), offsetLabel(reminder.offset)) { showOffsets = true }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Labeled(null, stringResource(R.string.reminder_for)) {
                        AppSegmented(
                            listOf(false to stringResource(R.string.reminder_my_tasks), true to stringResource(R.string.reminder_all_tasks)),
                            reminder.targetAll,
                        ) { vm.setReminderTarget(context, it) }
                    }
                    if (reminder.offset != ReminderOffset.None) {
                        PermissionRow(Icons.Outlined.NotificationsActive, stringResource(R.string.perm_notifications), notificationsOn, fixNotifications)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            PermissionRow(Icons.Outlined.Alarm, stringResource(R.string.perm_alarms), exactOn) {
                                runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())) }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (reminder.lastSyncFailed) stringResource(R.string.reminder_failed) else pluralStringResource(R.plurals.reminders_scheduled, reminder.scheduledCount, reminder.scheduledCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (reminder.lastSyncFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (reminder.syncing) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                TextButton(onClick = { vm.syncReminders(context) }) {
                                    Icon(Icons.Outlined.Sync, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.reminder_sync))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Security
        item {
            SectionCard(title = stringResource(R.string.settings_security)) {
                if (security.passwordChanged) Banner(stringResource(R.string.security_password_changed), BannerTone.Info, Modifier.padding(16.dp), icon = Icons.Outlined.CheckCircle)
                if (security.twoFactorDisabled) Banner(stringResource(R.string.two_factor_disabled), BannerTone.Info, Modifier.padding(16.dp), icon = Icons.Outlined.CheckCircle)
                NavRow(Icons.Outlined.Key, stringResource(R.string.password_change), null) {
                    vm.resetSecurity()
                    showPassword = true
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                when {
                    !user.twoFactorEnabled -> NavRow(Icons.Outlined.Security, stringResource(R.string.two_factor_enable), null, onClick = onSetupTwoFactor)
                    user.forceTwoFactor -> InfoRow(Icons.Outlined.Security, stringResource(R.string.two_factor_title), stringResource(R.string.two_factor_on))
                    else -> NavRow(Icons.Outlined.Security, stringResource(R.string.two_factor_disable), stringResource(R.string.two_factor_on)) {
                        vm.resetSecurity()
                        showDisableTwoFactor = true
                    }
                }
            }
        }

        // Devices
        item {
            SectionCard(title = stringResource(R.string.settings_devices)) {
                devices.error?.let { Banner(apiErrorMessage(it), BannerTone.Error, Modifier.padding(16.dp)) }
                if (devices.loading) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                } else if (devices.devices.isEmpty() && devices.error == null) {
                    Text(stringResource(R.string.devices_none), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // This phone first, then the most recent ones: a long list would push the rest of the page down
                var allDevices by rememberSaveable { mutableStateOf(false) }
                val sorted = devices.devices.sortedWith(compareByDescending<DeviceDto> { it.current }.thenByDescending { it.lastSeenAt.orEmpty() })
                val shown = if (allDevices) sorted else sorted.take(DEVICES_SHOWN)
                shown.forEachIndexed { index, device ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DeviceRow(device, locale) { deviceToRevoke = device }
                }
                if (sorted.size > DEVICES_SHOWN) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextButton(onClick = { allDevices = !allDevices }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        Text(if (allDevices) stringResource(R.string.devices_show_less) else stringResource(R.string.devices_show_all, sorted.size))
                    }
                }
            }
        }

        // About
        item {
            SectionCard(title = stringResource(R.string.settings_about)) {
                InfoRow(Icons.Outlined.Info, stringResource(R.string.about_app_version), AppInfo.VERSION)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                InfoRow(Icons.Outlined.Gavel, stringResource(R.string.about_license), LICENSE_NAME)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavRow(Icons.Outlined.Code, stringResource(R.string.about_source), null, external = true) { openWeb(AppInfo.SOURCE_URL) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavRow(Icons.Outlined.Public, stringResource(R.string.about_website), null, external = true) { openWeb(AppInfo.WEBSITE_URL) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavRow(Icons.Outlined.Policy, stringResource(R.string.about_privacy), null, external = true) { openWeb("${vm.serverUrl}/privacy") }
            }
        }

        item {
            OutlinedButton(
                onClick = { confirmLogout = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Logout, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sign_out))
            }
        }
    }

    if (showOffsets) {
        PickerSheet(
            stringResource(R.string.reminder_delay),
            ReminderOffset.entries.map { PickerItem(it.key, offsetLabel(it)) },
            setOf(reminder.offset.key),
            multiple = false,
            onDismiss = { showOffsets = false },
            onConfirm = { keys ->
                showOffsets = false
                keys.firstOrNull()?.let { vm.setReminderOffset(context, ReminderOffset.from(it)) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsOn) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
        )
    }
    if (showLanguages) {
        PickerSheet(
            stringResource(R.string.settings_language),
            listOf("", "fr", "en", "es", "de").map { PickerItem(it.ifEmpty { "system" }, languageLabel(it)) },
            setOf(language.ifEmpty { "system" }),
            multiple = false,
            onDismiss = { showLanguages = false },
            onConfirm = { keys ->
                showLanguages = false
                vm.setLanguage(keys.firstOrNull().orEmpty().takeUnless { it == "system" }.orEmpty())
            },
        )
    }
    if (showProfile) {
        ProfileSheet(vm, user, profile, onDismiss = { showProfile = false; vm.consumeSaved() })
    }
    if (showPassword) {
        PasswordDialog(security, vm, onClose = { showPassword = false })
    }
    if (showDisableTwoFactor) {
        DisableTwoFactorDialog(security, vm, onClose = { showDisableTwoFactor = false })
    }
    deviceToRevoke?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToRevoke = null },
            icon = { Icon(Icons.Outlined.Smartphone, null) },
            title = { Text(stringResource(R.string.devices_revoke_title)) },
            text = { Text(stringResource(R.string.devices_revoke_text, device.deviceName ?: device.platform)) },
            confirmButton = {
                Button(
                    onClick = {
                        vm.revokeDevice(device.id)
                        deviceToRevoke = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = MaterialTheme.shapes.medium,
                ) { Text(stringResource(R.string.devices_revoke)) }
            },
            dismissButton = { TextButton(onClick = { deviceToRevoke = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            icon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) },
            title = { Text(stringResource(R.string.sign_out)) },
            text = { Text(stringResource(R.string.sign_out_confirm)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmLogout = false
                        vm.logout(context)
                        onLoggedOut()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = MaterialTheme.shapes.medium,
                ) { Text(stringResource(R.string.sign_out)) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Each language by its own name; the empty choice follows the phone. */
@Composable
private fun languageLabel(code: String): String = when (code) {
    "fr" -> "Français"
    "en" -> "English"
    "es" -> "Español"
    "de" -> "Deutsch"
    else -> stringResource(R.string.language_system)
}

@Composable
private fun PasswordDialog(security: SecurityState, vm: SettingsViewModel, onClose: () -> Unit) {
    var current by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(security.passwordChanged) { if (security.passwordChanged) onClose() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.password_change)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                security.error?.let { Banner(apiErrorMessage(it), BannerTone.Error) }
                PasswordField(current, { current = it }, stringResource(R.string.password_current))
                PasswordField(new, { new = it }, stringResource(R.string.password_new))
                PasswordField(confirmation, { confirmation = it }, stringResource(R.string.password_confirm), isError = security.passwordMismatch)
                if (security.passwordMismatch) Text(stringResource(R.string.password_mismatch), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            ProgressButton(stringResource(R.string.save), security.saving, { vm.changePassword(current, new, confirmation) }, enabled = current.isNotEmpty() && new.isNotEmpty())
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DisableTwoFactorDialog(security: SecurityState, vm: SettingsViewModel, onClose: () -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(security.twoFactorDisabled) { if (security.twoFactorDisabled) onClose() }
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.Outlined.WarningAmber, null) },
        title = { Text(stringResource(R.string.two_factor_disable)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                security.error?.let { Banner(apiErrorMessage(it), BannerTone.Error) }
                Text(stringResource(R.string.two_factor_disable_text))
                PasswordField(password, { password = it }, stringResource(R.string.login_password))
            }
        },
        confirmButton = {
            ProgressButton(stringResource(R.string.two_factor_disable), security.saving, { vm.disableTwoFactor(password) }, enabled = password.isNotEmpty())
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, isError: Boolean = false) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(),
        label = { Text(label) }, singleLine = true, isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = MaterialTheme.shapes.medium,
    )
}

@Composable
private fun DeviceRow(device: DeviceDto, locale: java.util.Locale, onRevoke: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Smartphone, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(device.deviceName?.ifBlank { null } ?: device.platform, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(
                stringResource(R.string.devices_current).takeIf { device.current },
                device.appVersion?.takeIf { it.isNotBlank() },
                device.lastSeenAt?.let { relativeDate(it, locale) },
            ).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Leaving this phone is the sign-out button
        if (!device.current) {
            IconButton(onClick = onRevoke) {
                Icon(Icons.Outlined.Delete, stringResource(R.string.devices_revoke_label, device.deviceName ?: device.platform), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun offsetLabel(offset: ReminderOffset): String = when (offset) {
    ReminderOffset.None -> stringResource(R.string.reminder_never)
    ReminderOffset.Min5 -> stringResource(R.string.reminder_5min)
    ReminderOffset.Min15 -> stringResource(R.string.reminder_15min)
    ReminderOffset.Min30 -> stringResource(R.string.reminder_30min)
    ReminderOffset.H1 -> stringResource(R.string.reminder_1h)
    ReminderOffset.H4 -> stringResource(R.string.reminder_4h)
    ReminderOffset.Day1 -> stringResource(R.string.reminder_1d)
}

@Composable
private fun Labeled(icon: ImageVector?, label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
            }
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
        content()
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, null, enabled = enabled)
    }
}

@Composable
private fun NavRow(icon: ImageVector?, title: String, value: String?, external: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
        }
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Icon(
            if (external) Icons.AutoMirrored.Outlined.OpenInNew else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            null,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoRow(icon: ImageVector?, title: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
        } else Spacer(Modifier.width(34.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, title: String, granted: Boolean, onFix: () -> Unit) {
    val colors = LocalHumbleColors.current
    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (granted) {
            Icon(Icons.Outlined.CheckCircle, stringResource(R.string.perm_granted), Modifier.size(20.dp), tint = colors.success)
        } else {
            TextButton(onClick = onFix) {
                Icon(Icons.Outlined.WarningAmber, null, Modifier.size(16.dp), tint = colors.warning)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.perm_allow))
            }
        }
    }
}
