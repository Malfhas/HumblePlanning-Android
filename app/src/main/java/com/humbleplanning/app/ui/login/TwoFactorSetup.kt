package com.humbleplanning.app.ui.login

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.humbleplanning.app.R
import com.humbleplanning.app.data.ApiException
import com.humbleplanning.app.data.Repository
import com.humbleplanning.app.data.TwoFactorSetup
import com.humbleplanning.app.data.forScreen
import com.humbleplanning.app.graph
import com.humbleplanning.app.ui.Banner
import com.humbleplanning.app.ui.BannerTone
import com.humbleplanning.app.ui.ProgressButton
import com.humbleplanning.app.ui.apiErrorMessage
import com.humbleplanning.app.ui.humbleViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SetupStage { Password, Code, BackupCodes }

data class TwoFactorSetupState(
    val stage: SetupStage = SetupStage.Password,
    val password: String = "",
    val setup: TwoFactorSetup? = null,
    val code: String = "",
    val backupCodes: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: ApiException? = null,
)

/** Password, then QR code and secret, then a code from the authenticator app, then the backup codes. */
class TwoFactorSetupViewModel(private val repository: Repository) : ViewModel() {
    private val _state = MutableStateFlow(TwoFactorSetupState())
    val state = _state.asStateFlow()

    fun update(transform: (TwoFactorSetupState) -> TwoFactorSetupState) = _state.update { transform(it).copy(error = null) }

    fun begin() {
        val password = _state.value.password
        if (password.isEmpty() || _state.value.loading) return
        launchStep {
            val setup = repository.beginTwoFactorSetup(password)
            _state.update { it.copy(stage = SetupStage.Code, setup = setup, password = "", loading = false) }
        }
    }

    fun verify() {
        val code = _state.value.code
        if (code.length != 6 || _state.value.loading) return
        launchStep {
            val backupCodes = repository.confirmTwoFactorSetup(code)
            _state.update { it.copy(stage = SetupStage.BackupCodes, backupCodes = backupCodes, code = "", loading = false) }
        }
    }

    private fun launchStep(action: suspend () -> Unit) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                action()
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.forScreen()) }
            }
        }
    }
}

/** The steps of the setup; [onFinished] once the backup codes were seen. Shared by sign-in and settings. */
@Composable
fun TwoFactorSetupFlow(finishing: Boolean, attempt: Int = 0, onFinished: () -> Unit) {
    val vm = humbleViewModel(key = "two-factor-setup-$attempt") { TwoFactorSetupViewModel(it.repository) }
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        state.error?.let { Banner(apiErrorMessage(it), BannerTone.Error) }
        when (state.stage) {
            SetupStage.Password -> {
                Text(stringResource(R.string.two_factor_setup_password), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    state.password,
                    { v -> vm.update { it.copy(password = v) } },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.login_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.begin() }),
                    shape = MaterialTheme.shapes.medium,
                )
                ProgressButton(stringResource(R.string.continue_action), state.loading, vm::begin, Modifier.fillMaxWidth(), enabled = state.password.isNotEmpty())
            }
            SetupStage.Code -> {
                val setup = state.setup ?: TwoFactorSetup()
                Text(stringResource(R.string.two_factor_setup_scan), style = MaterialTheme.typography.bodyMedium)
                QrImage(setup.totpURI, Modifier.align(Alignment.CenterHorizontally))
                Text(stringResource(R.string.two_factor_setup_secret), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer {
                    Text(setup.secret, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                }
                OutlinedTextField(
                    state.code,
                    { v -> if (v.length <= 6 && v.all(Char::isDigit)) vm.update { it.copy(code = v) } },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.two_factor_code)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.verify() }),
                    shape = MaterialTheme.shapes.medium,
                )
                ProgressButton(stringResource(R.string.two_factor_verify), state.loading, vm::verify, Modifier.fillMaxWidth(), enabled = state.code.length == 6)
            }
            SetupStage.BackupCodes -> {
                Banner(stringResource(R.string.two_factor_backup_keep), BannerTone.Warning)
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    SelectionContainer {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.backupCodes.forEach {
                                Text(it, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
                            }
                        }
                    }
                }
                ProgressButton(stringResource(R.string.two_factor_backup_done), finishing, onFinished, Modifier.fillMaxWidth())
            }
        }
    }
}

/** QR code of the authenticator link, always dark on white: scanners expect it whatever the theme. */
@Composable
private fun QrImage(content: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) { runCatching { encodeQr(content) }.getOrNull() } ?: return
    val description = stringResource(R.string.two_factor_setup_qr)
    Canvas(modifier.size(220.dp).semantics { contentDescription = description }) {
        drawRect(Color.White)
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    // A hair larger than the cell: no seam between neighbours when the size is not a whole number
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

private fun encodeQr(content: String): BitMatrix =
    QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))

/** Setup started from the settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwoFactorSetupScreen(onClose: () -> Unit) {
    val repository = LocalContext.current.graph.repository
    val scope = rememberCoroutineScope()
    var finishing by remember { mutableStateOf(false) }
    // Leaves from the composition, on the main thread, whatever the thread the reload ended on
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(loaded) { if (loaded) onClose() }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.two_factor_enable)) },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } },
        )
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp)) {
            TwoFactorSetupFlow(finishing, onFinished = {
                finishing = true
                scope.launch {
                    runCatching { repository.loadReferences() }
                    loaded = true
                }
            })
        }
    }
}
