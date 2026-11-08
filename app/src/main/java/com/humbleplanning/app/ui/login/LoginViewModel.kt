package com.humbleplanning.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humbleplanning.app.QrLogin
import com.humbleplanning.app.data.LoginError
import com.humbleplanning.app.data.LoginProgress
import com.humbleplanning.app.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LoginStep { Credentials, TwoFactor, PasswordChange, TwoFactorSetup }

data class LoginState(
    val serverUrl: String = "",
    val login: String = "",
    val password: String = "",
    val remember: Boolean = true,
    val code: String = "",
    val backupCode: Boolean = false,
    val pendingToken: String = "",
    val newPassword: String = "",
    val confirmation: String = "",
    val step: LoginStep = LoginStep.Credentials,
    /** Each forced two-factor setup starts over, even after leaving the previous one half way. */
    val setupAttempt: Int = 0,
    val loading: Boolean = false,
    val error: LoginError? = null,
    val done: Boolean = false,
)

class LoginViewModel(private val repository: Repository) : ViewModel() {
    private val _state = MutableStateFlow(
        LoginState(
            serverUrl = repository.serverUrl.ifEmpty { "https://" },
            login = repository.lastLogin,
            remember = repository.rememberMe,
        )
    )
    val state: StateFlow<LoginState> = _state.asStateFlow()
    val blocking = repository.blocking

    fun update(transform: (LoginState) -> LoginState) = _state.update { transform(it).copy(error = null) }

    /** The screen follows what the repository says sign-in still needs. */
    private fun attempt(action: suspend () -> LoginProgress) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val progress = action()
            _state.update { s ->
                when (progress) {
                    LoginProgress.Done -> s.copy(loading = false, done = true, password = "", newPassword = "", confirmation = "", code = "")
                    is LoginProgress.TwoFactor -> s.copy(loading = false, step = LoginStep.TwoFactor, pendingToken = progress.pendingToken, code = "", backupCode = false, password = "")
                    LoginProgress.PasswordChange -> s.copy(loading = false, step = LoginStep.PasswordChange, code = "", password = "")
                    LoginProgress.TwoFactorSetup -> s.copy(loading = false, step = LoginStep.TwoFactorSetup, setupAttempt = s.setupAttempt + 1, code = "", password = "", newPassword = "", confirmation = "")
                    is LoginProgress.Failed -> s.copy(loading = false, error = progress.error, code = if (s.step == LoginStep.TwoFactor) "" else s.code)
                }
            }
        }
    }

    fun submit() {
        val s = _state.value
        if (s.loading) return
        when (s.step) {
            LoginStep.Credentials -> attempt { repository.login(s.serverUrl, s.login, s.password, s.remember) }
            LoginStep.TwoFactor -> attempt { repository.submitTwoFactor(s.pendingToken, s.code, s.backupCode) }
            LoginStep.PasswordChange -> attempt { repository.submitPasswordChange(s.newPassword, s.confirmation) }
            LoginStep.TwoFactorSetup -> Unit
        }
    }

    /** Only after the user confirmed the server shown by the app (see App.kt). */
    fun loginWithPairing(qr: QrLogin) {
        _state.update { it.copy(serverUrl = qr.serverUrl, step = LoginStep.Credentials) }
        attempt { repository.loginWithPairing(qr.serverUrl, qr.code) }
    }

    /** The two-factor setup imposed at sign-in is finished: backup codes seen. */
    fun completeTwoFactorSetup() = attempt { repository.completeTwoFactorSetup() }

    /** The server lets the app through again: the sign-in it stopped half way goes on. */
    fun resumeSignIn() {
        if (repository.signInPending && !_state.value.loading) attempt { repository.completeTwoFactorSetup() }
    }

    /** Back to the first step; a required step left behind closes the session it had opened. */
    fun back() {
        val s = _state.value
        if (s.step == LoginStep.PasswordChange || s.step == LoginStep.TwoFactorSetup) repository.logout()
        _state.update { it.copy(step = LoginStep.Credentials, password = "", code = "", backupCode = false, newPassword = "", confirmation = "", error = null) }
    }
}
