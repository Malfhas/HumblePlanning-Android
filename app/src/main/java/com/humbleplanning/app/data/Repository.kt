package com.humbleplanning.app.data

import android.os.Build
import com.humbleplanning.app.AppInfo
import com.humbleplanning.app.alerts.AlertNotifier
import com.humbleplanning.app.reminders.ReminderOffset
import com.humbleplanning.app.reminders.ReminderPlanner
import com.humbleplanning.app.reminders.ReminderScheduler
import com.humbleplanning.app.reminders.ReminderSettings
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient

/** Errors of the sign-in screen. */
sealed interface LoginError {
    data object UrlRequired : LoginError
    data object HttpsRequired : LoginError
    data object InvalidUrl : LoginError
    data object NotHumblePlanning : LoginError
    data object FieldsRequired : LoginError
    data object PasswordLoginDisabled : LoginError
    data object PasswordMismatch : LoginError
    /** Refused by the server: the message is already translated. */
    data class Server(val message: String) : LoginError
    data object Network : LoginError
}

/** What sign-in still needs before the app opens. */
sealed interface LoginProgress {
    data object Done : LoginProgress
    data class TwoFactor(val pendingToken: String) : LoginProgress
    data object PasswordChange : LoginProgress
    data object TwoFactorSetup : LoginProgress
    data class Failed(val error: LoginError) : LoginProgress
}

/** Screen shown above the whole app. */
sealed interface BlockingState {
    data class UpgradeRequired(val minimumVersion: String, val storeUrl: String) : BlockingState
    data class Maintenance(val message: String) : BlockingState
    /** The role lost the mobile app permission while signed in. */
    data class AppNotAllowed(val message: String) : BlockingState
}

/** Loaded tasks: online, or from the offline copy (date of its last refresh). */
data class TasksResult(val tasks: List<TaskDto>, val offlineSinceMillis: Long? = null)

enum class ThemeMode(val key: String) {
    System("system"), Light("light"), Dark("dark");

    companion object {
        fun from(key: String) = entries.firstOrNull { it.key == key } ?: System
    }
}

@OptIn(FlowPreview::class)
class Repository(
    private val prefs: Prefs,
    private val sessions: SessionStore,
    private val files: EncryptedFiles,
    private val http: OkHttpClient,
    val reminderSettings: ReminderSettings,
    private val reminderScheduler: ReminderScheduler,
    private val alertNotifier: AlertNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val realtime = RealtimeClient(http, scope)
    val assistantStream = AssistantStream(http)

    var serverUrl: String
        get() = prefs.string(KEY_SERVER_URL)
        private set(value) = prefs.putString(KEY_SERVER_URL, value)
    val lastLogin: String get() = prefs.string(KEY_LAST_LOGIN)
    val rememberMe: Boolean get() = prefs.boolean(KEY_REMEMBER_ME, true)
    val userId: String get() = _references.value.user.id.ifEmpty { prefs.string(KEY_USER_ID) }
    val userName: String get() = prefs.string(KEY_USER_NAME)
    val isLoggedIn: Boolean get() = sessions.current != null

    // ---- Display preferences ----

    /** "" follows the phone; otherwise fr, en, es or de. */
    private val _language = MutableStateFlow(prefs.string(KEY_LANGUAGE))
    val language: StateFlow<String> = _language.asStateFlow()
    fun setLanguage(code: String) {
        prefs.putString(KEY_LANGUAGE, code)
        _language.value = code
        if (isLoggedIn) scope.launch { runCatching { api().setLocale(code.ifEmpty { "auto" }) } }
    }

    /** Language sent to the server: its messages and notifications follow the app. */
    fun effectiveLanguage(): String = language.value.ifEmpty {
        Locale.getDefault().language.takeIf { it in SUPPORTED_LANGUAGES } ?: "en"
    }

    private val _theme = MutableStateFlow(ThemeMode.from(prefs.string(KEY_THEME, "system")))
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()
    fun setTheme(mode: ThemeMode) {
        prefs.putString(KEY_THEME, mode.key)
        _theme.value = mode
    }

    private val _dynamicColor = MutableStateFlow(prefs.boolean(KEY_DYNAMIC, false))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()
    fun setDynamicColor(value: Boolean) {
        prefs.putBoolean(KEY_DYNAMIC, value)
        _dynamicColor.value = value
    }

    /** Main text of task cards: "project" or "title". */
    private val _cardTitle = MutableStateFlow(prefs.string(KEY_CARD_TITLE, "project"))
    val cardTitle: StateFlow<String> = _cardTitle.asStateFlow()
    fun setCardTitle(value: String) {
        prefs.putString(KEY_CARD_TITLE, value)
        _cardTitle.value = value
    }

    // ---- Global state ----

    private val _sessionExpired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    private val _blocking = MutableStateFlow<BlockingState?>(null)
    val blocking: StateFlow<BlockingState?> = _blocking.asStateFlow()

    /** Raised when a task may have changed, here or for anyone else (real time): screens reload. */
    private val _tasksChanged = MutableStateFlow(0)
    val tasksChanged: StateFlow<Int> = _tasksChanged.asStateFlow()
    private val remoteChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    /** Raised when new alerts arrived: the Alerts screen reloads. */
    private val _alertsChanged = MutableStateFlow(0)
    val alertsChanged: StateFlow<Int> = _alertsChanged.asStateFlow()

    fun onNetworkChanged(available: Boolean) {
        // The connections of a network that went away hang until their timeout instead of failing:
        // without this, the first requests of the new one fail too and the app stays offline
        http.connectionPool.evictAll()
        if (!available) {
            _offline.value = true
        } else {
            sessions.current?.let(realtime::reconnect)
            if (_offline.value) {
                _offline.value = false
                if (isLoggedIn) _tasksChanged.value++
            }
        }
    }

    private val referencesSerializer = References.serializer()
    private val _references = MutableStateFlow(References())
    val references: StateFlow<References> = _references.asStateFlow()

    private var api: ApiClient? = null
    private var apiUrl = ""

    private fun apiFor(url: String): ApiClient {
        api?.takeIf { apiUrl == url }?.let { return it }
        apiUrl = url
        // Several requests can be refused together, the last ones once signed out: only the first one counts, or its notice is lost
        return ApiClient(url, http, onSessionExpired = { if (isLoggedIn) _sessionExpired.value = true }).also { api = it }
    }

    fun api(): ApiClient = apiFor(serverUrl)

    init {
        scope.launch {
            files.read(FILE_REFS)?.let { saved ->
                runCatching { ApiClient.json.decodeFromString(referencesSerializer, saved) }.getOrNull()?.let { _references.value = it }
            }
        }
        // A batch of changes (series, bulk) arrives as a burst of events: one reload for all
        scope.launch { remoteChanges.debounce(400).collect { _tasksChanged.value++ } }
        scope.launch { realtime.events.collect(::onRealtimeEvent) }
    }

    /** Foreground: connected and up to date. Background: push takes over. */
    fun onForeground() {
        sessions.current?.let(realtime::connect)
        if (isLoggedIn) scope.launch { runCatching { syncAlerts(notify = false) } }
    }

    fun onBackground() = realtime.disconnect()

    private suspend fun onRealtimeEvent(event: RealtimeEvent) {
        when (event) {
            RealtimeEvent.Connected, RealtimeEvent.TasksChanged -> remoteChanges.tryEmit(Unit)
            RealtimeEvent.PermissionsChanged, RealtimeEvent.AssistantChanged -> runCatching { loadReferences() }
            RealtimeEvent.Alert -> runCatching { syncAlerts(notify = false) }
            // Sent for all the user's sessions: only a refused call means this one ended
            RealtimeEvent.SessionRevoked, RealtimeEvent.InvalidSession -> runCatching { api().profile() }
        }
    }

    /** Call with the blocking and offline states. */
    suspend fun <T> call(block: suspend ApiClient.() -> T): T {
        try {
            val result = api().block()
            _blocking.value = null
            _offline.value = false
            return result
        } catch (e: ApiException.Network) {
            _offline.value = true
            throw e
        } catch (e: ApiException.UpgradeRequired) {
            _blocking.value = BlockingState.UpgradeRequired(e.minimumVersion, e.storeUrl)
            throw e
        } catch (e: ApiException.Maintenance) {
            _blocking.value = BlockingState.Maintenance(e.message.orEmpty())
            throw e
        } catch (e: ApiException.AppNotAllowed) {
            // At sign-in the form says it; signed in, nothing else can be done but sign out
            if (isLoggedIn) _blocking.value = BlockingState.AppNotAllowed(e.message.orEmpty())
            throw e
        }
    }

    /** Blocking screen: new attempt. True when the server answers normally again; the screens then read it again. */
    suspend fun retryBlocked(): Boolean {
        val cleared = runCatching {
            if (isLoggedIn) {
                call { profile() }
            } else {
                // Blocked at the first sign-in: nothing saved yet, only the server being tried. Its public route is not blocked: it says.
                val info = ApiClient(pendingServer?.url ?: serverUrl, http, onSessionExpired = {}).info()
                if (info.maintenance || isOlderVersion(AppInfo.VERSION, info.minimumAppVersion)) return@runCatching
                _blocking.value = null
            }
        }.isSuccess && _blocking.value == null
        if (cleared) {
            _tasksChanged.value++
            _alertsChanged.value++
        }
        return cleared
    }

    // ---- Sign-in ----

    private fun device() = DeviceInfo(
        name = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(100),
        appVersion = AppInfo.VERSION,
        locale = effectiveLanguage(),
    )

    private fun normalize(input: String): Pair<ServerUrl.Result.Ok?, LoginError?> = when (val result = ServerUrl.normalize(input)) {
        ServerUrl.Result.Empty -> null to LoginError.UrlRequired
        ServerUrl.Result.HttpsRequired -> null to LoginError.HttpsRequired
        ServerUrl.Result.Invalid -> null to LoginError.InvalidUrl
        is ServerUrl.Result.Ok -> result to null
    }

    private var pendingServer: ServerUrl.Result.Ok? = null
    private var pendingRemember = true
    private var pendingLogin = ""

    /** Server address, for the confirmation of a QR code or link: shown before anything is sent to it, port included. */
    fun hostOf(urlInput: String): String? = (ServerUrl.normalize(urlInput) as? ServerUrl.Result.Ok)?.url?.substringAfter("://")?.substringBefore('/')

    private suspend fun attempt(block: suspend () -> LoginProgress): LoginProgress = try {
        // Signing in right after a sign-out: the end of the old session must not touch the new one
        logoutJob?.join()
        block()
    } catch (e: ApiException.NotHumblePlanning) {
        LoginProgress.Failed(LoginError.NotHumblePlanning)
    } catch (e: ApiException.Network) {
        LoginProgress.Failed(LoginError.Network)
    } catch (e: ApiException.UpgradeRequired) {
        _blocking.value = BlockingState.UpgradeRequired(e.minimumVersion, e.storeUrl)
        LoginProgress.Failed(LoginError.Server(""))
    } catch (e: ApiException.Maintenance) {
        // Its message is on the blocking screen: nothing left on the form once that screen goes away
        _blocking.value = BlockingState.Maintenance(e.message.orEmpty())
        LoginProgress.Failed(LoginError.Server(""))
    } catch (e: ApiException) {
        LoginProgress.Failed(LoginError.Server(e.message.orEmpty()))
    }

    suspend fun login(urlInput: String, identifier: String, password: String, remember: Boolean): LoginProgress {
        val (server, invalid) = normalize(urlInput)
        if (server == null) return LoginProgress.Failed(invalid!!)
        if (identifier.isBlank() || password.isEmpty()) return LoginProgress.Failed(LoginError.FieldsRequired)
        return attempt {
            val client = ApiClient(server.url, http, onSessionExpired = {})
            if (!client.info().loginEnabled) return@attempt LoginProgress.Failed(LoginError.PasswordLoginDisabled)
            pendingServer = server
            pendingRemember = remember
            pendingLogin = identifier.trim()
            afterSignIn(client.login(MobileLoginRequest(identifier.trim(), password, device())))
        }
    }

    suspend fun loginWithPairing(urlInput: String, code: String): LoginProgress {
        val (server, invalid) = normalize(urlInput)
        if (server == null) return LoginProgress.Failed(invalid!!)
        return attempt {
            val client = ApiClient(server.url, http, onSessionExpired = {})
            client.info()
            pendingServer = server
            pendingRemember = rememberMe
            pendingLogin = lastLogin
            afterSignIn(client.loginPairing(MobilePairingRequest(code, device())))
        }
    }

    suspend fun submitTwoFactor(pendingToken: String, code: String, isBackupCode: Boolean): LoginProgress {
        val server = pendingServer ?: return LoginProgress.Failed(LoginError.UrlRequired)
        return attempt {
            afterSignIn(ApiClient(server.url, http, onSessionExpired = {}).loginTwoFactor(MobileTwoFactorRequest(pendingToken, code.trim(), isBackupCode, device())))
        }
    }

    private var pendingTwoFactorSetup = false

    private suspend fun afterSignIn(response: MobileLoginResponse): LoginProgress {
        if (response.status == "two_factor_required") return LoginProgress.TwoFactor(response.pendingToken.orEmpty())
        val session = response.session ?: throw ApiException.Unexpected("no session")
        val server = pendingServer ?: throw ApiException.Unexpected("no server")
        // Offline data of another server or account is not kept
        if (serverUrl != server.url || prefs.string(KEY_USER_ID) != session.userId) clearLocalData()
        serverUrl = server.url
        sessions.save(SessionStore.Session(server.url, session.cookieName, session.cookieValue))
        prefs.putString(KEY_USER_ID, session.userId)
        prefs.putBoolean(KEY_REMEMBER_ME, pendingRemember)
        prefs.putString(KEY_LAST_LOGIN, if (pendingRemember) pendingLogin else "")
        _sessionExpired.value = false
        pendingTwoFactorSetup = response.setupTwoFactorRequired
        return when {
            response.forcePasswordChange -> LoginProgress.PasswordChange
            response.setupTwoFactorRequired -> LoginProgress.TwoFactorSetup
            else -> finishSignIn()
        }
    }

    /** The session is open but its first reading was stopped by a blocking screen: sign-in goes on once it clears. */
    var signInPending = false
        private set

    private suspend fun finishSignIn(): LoginProgress {
        signInPending = true
        loadReferences()
        signInPending = false
        runCatching { api().setLocale(_language.value.ifEmpty { "auto" }) }
        pushTokenProvider?.invoke()?.let { token -> runCatching { api().updateDevice(UpdateDeviceRequest(pushToken = token)) } }
        // Alerts from before this sign-in are history, not news
        runCatching { prefs.putString(KEY_ALERTS_AFTER, api().alerts(Instant.now().toString()).serverTime) }
        onForeground()
        return LoginProgress.Done
    }

    suspend fun submitPasswordChange(newPassword: String, confirmation: String): LoginProgress {
        if (newPassword.isBlank() || newPassword != confirmation) return LoginProgress.Failed(LoginError.PasswordMismatch)
        return attempt {
            api().changeRequiredPassword(newPassword)
            if (pendingTwoFactorSetup) LoginProgress.TwoFactorSetup else finishSignIn()
        }
    }

    suspend fun beginTwoFactorSetup(password: String): TwoFactorSetup = call { enableTwoFactor(password) }
    suspend fun confirmTwoFactorSetup(code: String): List<String> = call { verifyTwoFactor(code.trim()) }.backupCodes
    suspend fun disableTwoFactor(password: String) { call { disableTwoFactor(password) } }
    suspend fun completeTwoFactorSetup(): LoginProgress = attempt { finishSignIn() }

    private var logoutJob: Job? = null

    /** Ends the session here and, when reachable, on the server. */
    fun logout() {
        // Not `api`: it is still empty when sign-in stops at a required step, whose session must close too
        if (isLoggedIn && !_sessionExpired.value) {
            val client = api()
            logoutJob = scope.launch {
                runCatching { client.updateDevice(UpdateDeviceRequest(pushToken = null)) }
                runCatching { client.logout() }
                clearSession()
            }
        } else {
            clearSession()
        }
        signInPending = false
        realtime.disconnect()
        reminderScheduler.cancelAll()
        clearLocalData()
        if (!rememberMe) prefs.remove(KEY_SERVER_URL, KEY_LAST_LOGIN)
        _references.value = References()
        _blocking.value = null
    }

    private fun clearSession() {
        sessions.save(null)
        _sessionExpired.value = false
    }

    private fun clearLocalData() {
        files.clear()
        taskRange = null
        // Filters name people, projects and teams of this account: not for the next one
        prefs.remove(KEY_USER_ID, KEY_USER_NAME, KEY_OFFLINE_TS, KEY_ALERTS_AFTER, KEY_ALERTS_SHOWN, KEY_PLANNING_FILTER)
    }

    // ---- Reference data ----

    suspend fun loadReferences() {
        val boot = call { bootstrap() }
        val me = boot.user
        val refs = References(
            user = me,
            permissions = boot.permissions,
            isAdmin = boot.isAdmin,
            config = boot.config,
            assistant = boot.assistant,
            projects = boot.projects.orEmpty().filter { it.status != "archived" }.sortedWith(compareBy({ it.group?.sortOrder ?: Int.MAX_VALUE }, { it.name.lowercase() })),
            statuses = boot.statuses.orEmpty().sortedBy { it.sortOrder },
            // A user hidden from the planning still sees their own tasks: added to the list
            users = boot.users.orEmpty().let { list -> if (list.any { it.id == me.id }) list else list + me }.sortedBy { it.displayName.lowercase() },
            teams = boot.teams.orEmpty().sortedBy { it.sortOrder },
            places = boot.places.orEmpty().sortedBy { it.name.lowercase() },
            resources = boot.resources.orEmpty().sortedBy { it.name.lowercase() },
            holidays = boot.holidays.orEmpty(),
        )
        prefs.putString(KEY_USER_NAME, me.displayName)
        _references.value = refs
        withContext(Dispatchers.Default) { files.write(FILE_REFS, ApiClient.json.encodeToString(referencesSerializer, refs)) }
    }

    // ---- Tasks ----

    private val tasksSerializer = ListSerializer(TaskDto.serializer())

    private fun offlineTasks(): List<TaskDto>? =
        files.read(FILE_TASKS)?.let { runCatching { ApiClient.json.decodeFromString(tasksSerializer, it) }.getOrNull() }

    /** Tasks of the last range read: the next read of the same range only fetches what changed. */
    private class TaskRange(val start: String, val end: String, val serverTime: String, val tasks: Map<String, TaskDto>)

    @Volatile
    private var taskRange: TaskRange? = null

    private suspend fun syncRange(start: String, end: String): List<TaskDto> {
        val known = taskRange?.takeIf { it.start == start && it.end == end }
        val sync = call { tasksSync(start, end, known?.serverTime) }
        return withContext(Dispatchers.Default) {
            val merged = HashMap(known?.tasks.orEmpty())
            sync.changed.forEach { merged[it.id] = it }
            // An id no longer listed was deleted, or left this range or the user's scope
            merged.keys.retainAll(sync.ids.toSet())
            taskRange = TaskRange(start, end, sync.serverTime, merged)
            merged.values.toList()
        }
    }

    /** Offline copy: my tasks from D-30 to D+90, refreshed when the planning opens or is pulled. */
    suspend fun refreshOfflineData() {
        val today = LocalDate.now()
        val me = userId
        val tasks = call { tasksSync(today.minusDays(30).toString(), today.plusDays(90).toString(), null) }.changed.filter { me in it.userIds }
        withContext(Dispatchers.Default) { files.write(FILE_TASKS, ApiClient.json.encodeToString(tasksSerializer, tasks)) }
        prefs.putLong(KEY_OFFLINE_TS, System.currentTimeMillis())
    }

    /** Tasks of a period, in the user's read scope; filters apply on the screen. Without network: the offline copy. */
    suspend fun tasks(startDate: String, endDate: String): TasksResult = try {
        TasksResult(syncRange(startDate, endDate))
    } catch (e: ApiException.Network) {
        val cached = withContext(Dispatchers.Default) { offlineTasks() } ?: throw e
        TasksResult(
            cached.filter { it.startDate <= endDate && it.lastDate >= startDate },
            offlineSinceMillis = prefs.long(KEY_OFFLINE_TS) ?: 0L,
        )
    }

    suspend fun task(id: String): TaskDto = try {
        call { task(id) }
    } catch (e: ApiException.Network) {
        withContext(Dispatchers.Default) { offlineTasks() }?.firstOrNull { it.id == id } ?: throw e
    }

    /** For a form: never from the offline copy, which only holds what a card shows. Saving from it would erase the rest. */
    suspend fun taskToEdit(id: String): TaskDto = call { task(id) }

    private fun changed() { _tasksChanged.value++ }

    suspend fun createTask(payload: TaskPayload): TaskDto = call { createTask(payload) }.also { changed() }

    suspend fun updateTask(id: String, payload: TaskPayload, applyToAll: Boolean = false): TaskDto =
        call { updateTask(id, payload, applyToAll) }.also { changed() }

    /** `scope`: single, after or all (series). */
    suspend fun deleteTask(id: String, scope: String = "single") {
        call { deleteTask(id, scope) }
        changed()
    }

    suspend fun removeUserFromTask(id: String, userId: String, scope: String = "single") {
        call { removeUserFromTask(id, userId, scope) }
        changed()
    }

    suspend fun repeatTask(id: String, request: RepeatRequest) {
        call { repeatTask(id, request) }
        changed()
    }

    suspend fun uploadFile(taskId: String, name: String, mimeType: String, size: Long, open: () -> InputStream?) {
        call { uploadFile(taskId, name, mimeType, size, open) }
        changed()
    }

    suspend fun deleteFile(taskId: String, fileId: String) {
        call { deleteFile(taskId, fileId) }
        changed()
    }

    suspend fun downloadFile(taskId: String, file: TaskFile, target: File) = call { downloadFile(taskId, file.name, target) }

    // ---- Profile ----

    suspend fun profile(): UserDto = call { profile() }

    suspend fun updateProfile(request: UpdateProfileRequest): UserDto =
        call { updateProfile(request) }.also { user ->
            prefs.putString(KEY_USER_NAME, user.displayName)
            _references.value = _references.value.copy(user = user)
        }

    suspend fun changePassword(current: String, new: String) { call { changePassword(current, new) } }

    suspend fun setMobilePush(enabled: Boolean) {
        call { setMobilePush(enabled) }
        _references.value = _references.value.copy(user = _references.value.user.copy(mobilePushNotificationsUser = enabled))
    }

    suspend fun devices(): List<DeviceDto> = call { devices() }
    suspend fun revokeDevice(id: String) { call { revokeDevice(id) } }
    suspend fun pushTest() { call { pushTest() } }

    // ---- Alerts ----

    /**
     * Set by the push layer when Firebase is available in this build. Gives the Firebase Installation ID the
     * phone is registered under for FCM, sent to the server as `pushToken`.
     */
    var pushTokenProvider: (suspend () -> String?)? = null

    /** New FID from Firebase (`onRegistered`): first start, reinstall, cleared data, restore on another phone. */
    fun onPushToken(token: String) {
        if (isLoggedIn) scope.launch { runCatching { api().updateDevice(UpdateDeviceRequest(pushToken = token)) } }
    }

    /** History of the Alerts screen. */
    suspend fun alertHistory(): List<MobileAlert> = call { alerts(null) }.alerts.asReversed()

    /** Reads what arrived since last time; `notify` shows it on the phone (app in the background). */
    suspend fun syncAlerts(notify: Boolean) {
        if (!isLoggedIn) return
        var after = prefs.string(KEY_ALERTS_AFTER).ifEmpty { null } ?: Instant.now().toString()
        val received = mutableListOf<MobileAlert>()
        // A full page gives the date to read on from; the rest waits for the next sync
        for (i in 1..MAX_ALERT_PAGES) {
            val page = call { alerts(after) }
            received += page.alerts
            after = page.serverTime
            if (!page.more) break
        }
        prefs.putString(KEY_ALERTS_AFTER, after)
        // `after` is inclusive: an alert can come back, an id is only shown once
        val shown = prefs.string(KEY_ALERTS_SHOWN).split(',').filter { it.isNotEmpty() }.toSet()
        val fresh = received.distinctBy { it.id }.filter { it.id !in shown }
        if (fresh.isEmpty()) return
        prefs.putString(KEY_ALERTS_SHOWN, (shown + fresh.map { it.id }).toList().takeLast(MAX_SHOWN_ALERTS).joinToString(","))
        _alertsChanged.value++
        if (notify) fresh.forEach(alertNotifier::show)
    }

    // ---- Reminders ----

    /** Reminders: tasks of the next 14 days (mine or all), a chosen delay before they start. A failure keeps those scheduled. */
    suspend fun syncReminders(): Boolean {
        val offset = reminderSettings.offset
        if (!isLoggedIn || offset == ReminderOffset.None) {
            reminderScheduler.cancelAll()
            return true
        }
        return try {
            val today = LocalDate.now()
            val me = userId
            val all = call { tasksSync(today.toString(), today.plusDays(14).toString(), null) }.changed
            val tasks = if (reminderSettings.targetAll) all else all.filter { me in it.userIds }
            if (_references.value.projects.isEmpty()) runCatching { loadReferences() }
            val refs = _references.value
            val reminders = ReminderPlanner.plan(tasks, refs.morningStart, offset, Instant.now(), ZoneId.systemDefault()) { task ->
                val project = task.project?.label ?: refs.project(task.projectId)?.label ?: task.title.orEmpty()
                val title = task.title?.takeIf { it.isNotBlank() } ?: project
                project to if (title == project) task.users.joinToString(", ") { it.displayName } else title
            }
            reminderScheduler.schedule(reminders)
            reminderSettings.lastSyncFailed = false
            true
        } catch (e: ApiException) {
            reminderSettings.lastSyncFailed = true
            false
        }
    }

    // ---- Assistant ----

    /** The user's monthly quota; null when the server could not say (the gauge then stays hidden). */
    suspend fun assistantQuota(): ChatQuota? = runCatching { call { assistantState() }.quota }.getOrNull()

    fun assistantTurn(conversationId: String, history: List<ChatLine>): Flow<ChatEvent> = assistantStream.turn(api(), conversationId, history)

    companion object {
        /** "1.2.0" against a minimum of "1.10": numbers compared one by one; no minimum, nothing is older. */
        internal fun isOlderVersion(version: String, minimum: String?): Boolean {
            if (minimum.isNullOrBlank()) return false
            val current = version.split('.').map { it.toIntOrNull() ?: 0 }
            val required = minimum.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(current.size, required.size)) {
                val difference = current.getOrElse(i) { 0 } - required.getOrElse(i) { 0 }
                if (difference != 0) return difference < 0
            }
            return false
        }

        private val SUPPORTED_LANGUAGES = setOf("fr", "en", "es", "de")
        private const val MAX_SHOWN_ALERTS = 200
        private const val MAX_ALERT_PAGES = 2
        const val KEY_PLANNING_FILTER = "planning_filter"
        private const val KEY_SERVER_URL = "server"
        private const val KEY_LAST_LOGIN = "last_login"
        private const val KEY_REMEMBER_ME = "remember_me"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_THEME = "theme"
        private const val KEY_DYNAMIC = "dynamic_colors"
        private const val KEY_CARD_TITLE = "card_title"
        private const val KEY_OFFLINE_TS = "offline_date"
        private const val KEY_ALERTS_AFTER = "alerts_after"
        private const val KEY_ALERTS_SHOWN = "alerts_shown"
        private const val FILE_TASKS = "tasks.bin"
        private const val FILE_REFS = "references.bin"
    }
}
