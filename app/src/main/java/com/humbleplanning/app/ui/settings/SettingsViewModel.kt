package com.humbleplanning.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humbleplanning.app.AppGraph
import com.humbleplanning.app.data.ApiException
import com.humbleplanning.app.data.DeviceDto
import com.humbleplanning.app.data.ThemeMode
import com.humbleplanning.app.data.UpdateProfileRequest
import com.humbleplanning.app.data.forScreen
import com.humbleplanning.app.reminders.ReminderOffset
import com.humbleplanning.app.reminders.ReminderSync
import com.humbleplanning.app.ui.signOut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ReminderState(
    val offset: ReminderOffset,
    val targetAll: Boolean,
    val lastSyncFailed: Boolean,
    val scheduledCount: Int,
    val syncing: Boolean = false,
)

data class ProfileState(
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: ApiException? = null,
)

data class SecurityState(
    val saving: Boolean = false,
    val passwordChanged: Boolean = false,
    val passwordMismatch: Boolean = false,
    val error: ApiException? = null,
    val twoFactorDisabled: Boolean = false,
)

data class AlertsSettingsState(
    val testSending: Boolean = false,
    val testSent: Boolean = false,
    /** The server answered, but nothing could reach this phone (no push token yet, for instance). */
    val testNotDelivered: Boolean = false,
    val error: ApiException? = null,
)

data class DevicesState(
    val devices: List<DeviceDto> = emptyList(),
    val loading: Boolean = true,
    val error: ApiException? = null,
)

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    private val repository = graph.repository
    private val reminders = graph.reminderSettings

    val theme = repository.theme
    val dynamicColor = repository.dynamicColor
    val cardTitle = repository.cardTitle
    val language = repository.language
    val references = repository.references
    val serverUrl: String get() = repository.serverUrl

    private val _reminder = MutableStateFlow(ReminderState(reminders.offset, reminders.targetAll, reminders.lastSyncFailed, scheduledCount = 0))
    val reminder = _reminder.asStateFlow()

    private val _profile = MutableStateFlow(ProfileState())
    val profile = _profile.asStateFlow()

    private val _security = MutableStateFlow(SecurityState())
    val security = _security.asStateFlow()

    private val _alerts = MutableStateFlow(AlertsSettingsState())
    val alerts = _alerts.asStateFlow()

    private val _devices = MutableStateFlow(DevicesState())
    val devices = _devices.asStateFlow()

    init {
        viewModelScope.launch { _reminder.value = reminderState() }
        loadDevices()
        // Back from a blocking screen, which replaced the answer
        viewModelScope.launch { repository.blocking.drop(1).collect { if (it == null) loadDevices() } }
    }

    /** The scheduled reminders are kept in an encrypted file: not read on the main thread. */
    private suspend fun reminderState() = withContext(Dispatchers.Default) {
        ReminderState(reminders.offset, reminders.targetAll, reminders.lastSyncFailed, reminders.scheduled.size)
    }

    fun setTheme(mode: ThemeMode) = repository.setTheme(mode)
    fun setDynamicColor(value: Boolean) = repository.setDynamicColor(value)
    fun setCardTitle(value: String) = repository.setCardTitle(value)
    fun setLanguage(code: String) = repository.setLanguage(code)

    fun setReminderOffset(context: Context, offset: ReminderOffset) {
        reminders.offset = offset
        syncReminders(context)
    }

    fun setReminderTarget(context: Context, all: Boolean) {
        reminders.targetAll = all
        syncReminders(context)
    }

    fun syncReminders(context: Context) {
        _reminder.update { it.copy(offset = reminders.offset, targetAll = reminders.targetAll, syncing = true) }
        viewModelScope.launch {
            repository.syncReminders()
            if (reminders.offset == ReminderOffset.None) ReminderSync.cancel(context) else ReminderSync.schedulePeriodic(context)
            _reminder.value = reminderState()
        }
    }

    // ---- Alerts ----

    fun setMobilePush(enabled: Boolean) {
        _alerts.update { it.copy(error = null, testSent = false) }
        viewModelScope.launch {
            try {
                repository.setMobilePush(enabled)
            } catch (e: ApiException) {
                _alerts.update { it.copy(error = e.forScreen()) }
            }
        }
    }

    fun sendTest() {
        _alerts.update { AlertsSettingsState(testSending = true) }
        viewModelScope.launch {
            try {
                val answer = repository.call { pushTest() }
                val delivered = (answer as? JsonObject)?.get("delivered")?.jsonPrimitive?.booleanOrNull != false
                _alerts.update { it.copy(testSending = false, testSent = delivered, testNotDelivered = !delivered) }
            } catch (e: ApiException) {
                _alerts.update { it.copy(testSending = false, error = e.forScreen()) }
            }
        }
    }

    // ---- Profile ----

    fun saveProfile(name: String, firstName: String, phone: String, mobile: String, job: String, color: String?) {
        _profile.value = ProfileState(saving = true)
        viewModelScope.launch {
            try {
                repository.updateProfile(
                    UpdateProfileRequest(
                        name = name.trim(),
                        firstName = firstName.trim().ifEmpty { null },
                        phone = phone.trim().ifEmpty { null },
                        mobile = mobile.trim().ifEmpty { null },
                        job = job.trim().ifEmpty { null },
                        color = color,
                    )
                )
                // The answer only carries the profile: permissions and flags come back with the whole set
                runCatching { repository.loadReferences() }
                _profile.value = ProfileState(saved = true)
            } catch (e: ApiException) {
                _profile.value = ProfileState(error = e.forScreen())
            }
        }
    }

    fun consumeSaved() {
        _profile.value = ProfileState()
    }

    // ---- Security ----

    fun changePassword(current: String, new: String, confirmation: String) {
        if (new != confirmation) {
            _security.value = SecurityState(passwordMismatch = true)
            return
        }
        _security.value = SecurityState(saving = true)
        viewModelScope.launch {
            try {
                repository.changePassword(current, new)
                _security.value = SecurityState(passwordChanged = true)
            } catch (e: ApiException) {
                _security.value = SecurityState(error = e.forScreen())
            }
        }
    }

    fun disableTwoFactor(password: String) {
        _security.value = SecurityState(saving = true)
        viewModelScope.launch {
            try {
                repository.disableTwoFactor(password)
                runCatching { repository.loadReferences() }
                _security.value = SecurityState(twoFactorDisabled = true)
            } catch (e: ApiException) {
                _security.value = SecurityState(error = e.forScreen())
            }
        }
    }

    fun resetSecurity() {
        _security.value = SecurityState()
    }

    // ---- Devices ----

    fun loadDevices() {
        viewModelScope.launch {
            try {
                val devices = repository.devices()
                _devices.value = DevicesState(devices, loading = false)
            } catch (e: ApiException) {
                _devices.update { it.copy(loading = false, error = e.forScreen()) }
            }
        }
    }

    fun revokeDevice(id: String) {
        viewModelScope.launch {
            try {
                repository.revokeDevice(id)
                _devices.update { s -> s.copy(devices = s.devices.filterNot { it.id == id }, error = null) }
            } catch (e: ApiException) {
                _devices.update { it.copy(error = e.forScreen()) }
            }
        }
    }

    fun logout(context: Context) = signOut(context)
}
