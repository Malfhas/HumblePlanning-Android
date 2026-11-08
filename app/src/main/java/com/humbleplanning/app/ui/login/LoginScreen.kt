package com.humbleplanning.app.ui.login

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.humbleplanning.app.AppInfo
import com.humbleplanning.app.QrLogin
import com.humbleplanning.app.R
import com.humbleplanning.app.data.LoginError
import com.humbleplanning.app.ui.Banner
import com.humbleplanning.app.ui.BannerTone
import com.humbleplanning.app.ui.ProgressButton
import com.humbleplanning.app.ui.humbleViewModel

@Composable
fun LoginScreen(
    sessionExpired: Boolean,
    onNoticeShown: () -> Unit,
    confirmedQr: QrLogin?,
    onQrUsed: () -> Unit,
    onScan: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    val vm = humbleViewModel { LoginViewModel(it.repository) }
    val state by vm.state.collectAsStateWithLifecycle()
    var showExpired by rememberSaveable { mutableStateOf(sessionExpired) }
    LaunchedEffect(sessionExpired) {
        if (sessionExpired) {
            showExpired = true
            onNoticeShown()
        }
    }
    LaunchedEffect(state.done) { if (state.done) onLoggedIn() }
    val blocking by vm.blocking.collectAsStateWithLifecycle()
    LaunchedEffect(blocking) { if (blocking == null) vm.resumeSignIn() }
    LaunchedEffect(confirmedQr) {
        if (confirmedQr != null) {
            showExpired = false
            vm.loginWithPairing(confirmedQr)
            onQrUsed()
        }
    }
    BackHandler(enabled = state.step != LoginStep.Credentials, onBack = vm::back)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            Image(painterResource(R.drawable.ic_logo_mark), null, Modifier.size(84.dp))
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    when (state.step) {
                        LoginStep.Credentials -> R.string.login_subtitle
                        LoginStep.TwoFactor -> R.string.two_factor_subtitle
                        LoginStep.PasswordChange -> R.string.password_change_required
                        LoginStep.TwoFactorSetup -> R.string.two_factor_setup_required
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Column(Modifier.widthIn(max = 460.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (showExpired) Banner(stringResource(R.string.err_session), BannerTone.Warning)
                state.error?.let { error -> loginErrorMessage(error).takeIf { it.isNotBlank() }?.let { Banner(it, BannerTone.Error) } }
                AnimatedContent(
                    state.step,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        (slideInHorizontally(tween(260)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(260))) togetherWith
                            (slideOutHorizontally(tween(200)) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(200)))
                    },
                    label = "step",
                ) { step ->
                    when (step) {
                        LoginStep.Credentials -> CredentialsForm(state, vm, onScan) { showExpired = false }
                        LoginStep.TwoFactor -> TwoFactorForm(state, vm)
                        LoginStep.PasswordChange -> PasswordChangeForm(state, vm)
                        LoginStep.TwoFactorSetup -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            TwoFactorSetupFlow(finishing = state.loading, attempt = state.setupAttempt, onFinished = vm::completeTwoFactorSetup)
                            TextButton(onClick = vm::back, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.cancel)) }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.version, AppInfo.VERSION),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CredentialsForm(state: LoginState, vm: LoginViewModel, onScan: () -> Unit, onEdit: () -> Unit) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Field(state.serverUrl, { v -> onEdit(); vm.update { it.copy(serverUrl = v) } }, stringResource(R.string.login_server), Icons.Outlined.Dns,
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false))
        Field(state.login, { v -> onEdit(); vm.update { it.copy(login = v) } }, stringResource(R.string.login_identifier), Icons.Outlined.Person,
            keyboard = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false))
        Field(
            state.password, { v -> onEdit(); vm.update { it.copy(password = v) } }, stringResource(R.string.login_password), Icons.Outlined.Lock,
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            actions = KeyboardActions(onDone = { vm.submit() }),
            visual = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, stringResource(R.string.login_show_password))
                }
            },
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(state.remember, role = Role.Checkbox) { v -> vm.update { it.copy(remember = v) } },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(state.remember, null, Modifier.padding(horizontal = 12.dp))
            Text(stringResource(R.string.login_remember), style = MaterialTheme.typography.bodyMedium)
        }
        ProgressButton(stringResource(R.string.sign_in), state.loading, vm::submit, Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f))
            Text(stringResource(R.string.or), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.weight(1f))
        }
        OutlinedButton(onClick = onScan, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) {
            Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.login_qr))
        }
        Text(
            stringResource(R.string.login_qr_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TwoFactorForm(state: LoginState, vm: LoginViewModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.backupCode) { runCatching { focus.requestFocus() } }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Text(
                stringResource(if (state.backupCode) R.string.two_factor_backup_text else R.string.two_factor_text),
                Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.backupCode) {
            OutlinedTextField(
                value = state.code,
                onValueChange = { v -> if (v.length <= 20) vm.update { it.copy(code = v) } },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                label = { Text(stringResource(R.string.two_factor_backup_code)) },
                placeholder = { Text("ABCD-1234") },
                leadingIcon = { Icon(Icons.Outlined.Key, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.submit() }),
                shape = MaterialTheme.shapes.medium,
            )
        } else {
            OutlinedTextField(
                value = state.code,
                onValueChange = { v -> if (v.length <= 6 && v.all(Char::isDigit)) vm.update { it.copy(code = v) } },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                label = { Text(stringResource(R.string.two_factor_code)) },
                leadingIcon = { Icon(Icons.Outlined.Key, null) },
                textStyle = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 8.sp, fontWeight = FontWeight.SemiBold),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.submit() }),
                shape = MaterialTheme.shapes.medium,
            )
        }
        ProgressButton(
            stringResource(R.string.two_factor_verify),
            state.loading,
            vm::submit,
            Modifier.fillMaxWidth(),
            enabled = if (state.backupCode) state.code.isNotBlank() else state.code.length == 6,
        )
        TextButton(onClick = { vm.update { it.copy(backupCode = !it.backupCode, code = "") } }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(if (state.backupCode) R.string.two_factor_use_app else R.string.two_factor_use_backup), textAlign = TextAlign.Center)
        }
        TextButton(onClick = vm::back, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.back))
        }
    }
}

@Composable
private fun PasswordChangeForm(state: LoginState, vm: LoginViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Field(state.newPassword, { v -> vm.update { it.copy(newPassword = v) } }, stringResource(R.string.password_new), Icons.Outlined.Lock,
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next), visual = PasswordVisualTransformation())
        Field(state.confirmation, { v -> vm.update { it.copy(confirmation = v) } }, stringResource(R.string.password_confirm), Icons.Outlined.Lock,
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            actions = KeyboardActions(onDone = { vm.submit() }), visual = PasswordVisualTransformation())
        ProgressButton(stringResource(R.string.password_change), state.loading, vm::submit, Modifier.fillMaxWidth())
        TextButton(onClick = vm::back, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    actions: KeyboardActions = KeyboardActions.Default,
    visual: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        leadingIcon = { Icon(icon, null) },
        trailingIcon = trailing,
        singleLine = true,
        keyboardOptions = keyboard,
        keyboardActions = actions,
        visualTransformation = visual,
        shape = MaterialTheme.shapes.medium,
    )
}

@Composable
private fun loginErrorMessage(error: LoginError): String = when (error) {
    LoginError.UrlRequired -> stringResource(R.string.err_url_required)
    LoginError.HttpsRequired -> stringResource(R.string.err_https)
    LoginError.InvalidUrl -> stringResource(R.string.err_url_invalid)
    LoginError.NotHumblePlanning -> stringResource(R.string.err_not_humble)
    LoginError.FieldsRequired -> stringResource(R.string.err_fields)
    LoginError.PasswordLoginDisabled -> stringResource(R.string.err_password_login_disabled)
    LoginError.PasswordMismatch -> stringResource(R.string.password_mismatch)
    LoginError.Network -> stringResource(R.string.err_network)
    is LoginError.Server -> error.message
}
