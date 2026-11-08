package com.humbleplanning.app.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class ApiException(message: String?, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable) : ApiException(cause.message, cause)
    class SessionExpired : ApiException("session expired")
    /** 426: the server requires a newer app. */
    class UpgradeRequired(val minimumVersion: String, val storeUrl: String) : ApiException("upgrade required")
    /** 423: maintenance, the server message is translated. */
    class Maintenance(message: String) : ApiException(message)
    /** 403 `mobile.errors.appNotAllowed`: the role of the account does not allow the app; translated message. */
    class AppNotAllowed(message: String) : ApiException(message)
    /** The address does not answer like a Humble Planning server. */
    class NotHumblePlanning : ApiException("not a Humble Planning server")
    class Unexpected(body: String) : ApiException(body)
    /** Refusal with a stable `code`; `message` is already translated by the server. */
    class Server(val status: Int, val code: String, message: String, val body: ApiErrorBody = ApiErrorBody()) : ApiException(message)
}

val ApiException.isNotFound: Boolean get() = this is ApiException.Server && status == 404
val ApiException.isForbidden: Boolean get() = this is ApiException.Server && status == 403

/** The blocking screen says it (maintenance, app too old, app not allowed): no error of its own on the screen below, which would outlast it. */
val ApiException.isBlocking: Boolean get() = this is ApiException.UpgradeRequired || this is ApiException.Maintenance || this is ApiException.AppNotAllowed

/** What a screen shows of a failure. */
fun ApiException.forScreen(): ApiException? = takeUnless { it.isBlocking }

/** Task routes of the REST API and of `/api/mobile`, for one server. */
class ApiClient(
    private val baseUrl: String,
    private val http: OkHttpClient,
    private val onSessionExpired: () -> Unit,
) {
    private fun url(path: String, query: Map<String, String?> = emptyMap()): HttpUrl =
        "$baseUrl$path".toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> if (value != null) addQueryParameter(key, value) }
        }.build()

    private suspend fun execute(request: Request): Pair<Response, String> = withContext(Dispatchers.IO) {
        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            throw ApiException.Network(e)
        }
        val body = try {
            response.use { it.body.string() }
        } catch (e: IOException) {
            throw ApiException.Network(e)
        }
        response to body
    }

    private fun failure(status: Int, path: String, body: String): ApiException {
        val error = decodeOrNull<ApiErrorBody>(body)
        return when {
            status == 426 -> ApiException.UpgradeRequired(error?.param("minimumVersion").orEmpty(), error?.param("storeUrl").orEmpty())
            status == 423 -> ApiException.Maintenance(error?.error.orEmpty())
            status == 403 && error?.code == APP_NOT_ALLOWED -> ApiException.AppNotAllowed(error.error)
            // A proxy answers a bare 502/503 during an update: as good as no network
            error == null && status >= 500 -> ApiException.Network(IOException("HTTP $status"))
            error == null -> ApiException.Unexpected(body.take(300))
            status == 401 && error.code in SESSION_CODES && !path.startsWith("/api/mobile/auth/") -> {
                onSessionExpired()
                ApiException.SessionExpired()
            }
            else -> ApiException.Server(status, error.code, error.error, error)
        }
    }

    private suspend inline fun <reified T> send(request: Request, path: String): T = withContext(Dispatchers.Default) {
        val (response, body) = execute(request)
        if (!response.isSuccessful) throw failure(response.code, path, body)
        try {
            json.decodeFromString<T>(body)
        } catch (e: SerializationException) {
            throw ApiException.Unexpected(body.take(300))
        } catch (e: IllegalArgumentException) {
            throw ApiException.Unexpected(body.take(300))
        }
    }

    private suspend inline fun <reified T> get(path: String, query: Map<String, String?> = emptyMap()): T =
        send(Request.Builder().url(url(path, query)).get().build(), path)

    private suspend inline fun <reified T, reified B> sendJson(method: String, path: String, body: B, session: Boolean = true, query: Map<String, String?> = emptyMap()): T =
        send(
            Request.Builder().url(url(path, query))
                .method(method, json.encodeToString(body).toRequestBody(JSON))
                .apply { if (!session) header(HEADER_NO_SESSION, "1") }
                .build(),
            path,
        )

    private suspend inline fun <reified T> sendEmpty(method: String, path: String, query: Map<String, String?> = emptyMap()): T =
        send(Request.Builder().url(url(path, query)).method(method, if (method == "DELETE") null else EMPTY_BODY).build(), path)

    // ---- Sign-in: never with the session of another server ----

    suspend fun info(): MobileInfo {
        val info = try {
            send<MobileInfo>(Request.Builder().url(url("/api/mobile/info")).header(HEADER_NO_SESSION, "1").get().build(), "/api/mobile/info")
        } catch (e: ApiException.Unexpected) {
            throw ApiException.NotHumblePlanning()
        } catch (e: ApiException.Server) {
            throw if (e.status == 404) ApiException.NotHumblePlanning() else e
        }
        if (info.service != "humble-planning") throw ApiException.NotHumblePlanning()
        return info
    }

    suspend fun login(body: MobileLoginRequest): MobileLoginResponse = sendJson("POST", "/api/mobile/auth/login", body, session = false)
    suspend fun loginTwoFactor(body: MobileTwoFactorRequest): MobileLoginResponse = sendJson("POST", "/api/mobile/auth/2fa", body, session = false)
    suspend fun loginPairing(body: MobilePairingRequest): MobileLoginResponse = sendJson("POST", "/api/mobile/auth/pairing", body, session = false)
    suspend fun logout(): SuccessResponse = sendEmpty("POST", "/api/mobile/auth/logout")
    suspend fun changeRequiredPassword(password: String): SuccessResponse = sendJson("POST", "/api/auth/change-password", ChangeRequiredPasswordRequest(password))
    suspend fun enableTwoFactor(password: String): TwoFactorSetup = sendJson("POST", "/api/profile/2fa/enable", PasswordOnlyRequest(password))
    suspend fun verifyTwoFactor(code: String): BackupCodes = sendJson("POST", "/api/profile/2fa/verify", CodeOnlyRequest(code))
    suspend fun disableTwoFactor(password: String): SuccessResponse = sendJson("POST", "/api/profile/2fa/disable", PasswordOnlyRequest(password))

    // ---- Data ----

    suspend fun bootstrap(): Bootstrap = get("/api/mobile/bootstrap")

    suspend fun tasksSync(start: String, end: String, since: String?): TaskSync =
        get("/api/mobile/tasks/sync", mapOf("startDate" to start, "endDate" to end, "since" to since))

    suspend fun task(id: String): TaskDto = get("/api/tasks/$id")
    suspend fun createTask(payload: TaskPayload): TaskDto = sendJson("POST", "/api/tasks", payload)
    suspend fun updateTask(id: String, payload: TaskPayload, applyToAll: Boolean): TaskDto =
        sendJson("PUT", "/api/tasks/$id", payload, query = mapOf("applyToAll" to if (applyToAll) "true" else null))
    suspend fun deleteTask(id: String, scope: String): Unit = sendEmpty<kotlinx.serialization.json.JsonElement>("DELETE", "/api/tasks/$id", mapOf("scope" to scope)).let { }
    suspend fun removeUserFromTask(id: String, userId: String, scope: String): Unit =
        sendEmpty<kotlinx.serialization.json.JsonElement>("DELETE", "/api/tasks/$id/users/$userId", mapOf("scope" to scope)).let { }
    suspend fun repeatTask(id: String, body: RepeatRequest): Unit = sendJson<kotlinx.serialization.json.JsonElement, RepeatRequest>("POST", "/api/tasks/$id/repeat", body).let { }

    suspend fun uploadFile(taskId: String, fileName: String, mimeType: String, size: Long, open: () -> InputStream?): TaskFile {
        val type = mimeType.toMediaTypeOrNull() ?: "application/octet-stream".toMediaType()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, StreamBody(type, size, open))
            .build()
        return send(Request.Builder().url(url("/api/tasks/$taskId/files")).post(body).build(), "/api/tasks/$taskId/files")
    }

    suspend fun deleteFile(taskId: String, fileId: String): SuccessResponse =
        sendEmpty("DELETE", "/api/tasks/$taskId/files", mapOf("fileId" to fileId))

    /** Downloads a task attachment into [target]. */
    suspend fun downloadFile(taskId: String, name: String, target: File): Unit = withContext(Dispatchers.IO) {
        val path = "/api/files/tasks/$taskId/$name"
        val response = try {
            http.newCall(Request.Builder().url("$baseUrl/api/files/tasks/$taskId/".toHttpUrl().newBuilder().addPathSegment(name).build()).build()).await()
        } catch (e: IOException) {
            throw ApiException.Network(e)
        }
        response.use {
            if (!it.isSuccessful) throw failure(it.code, path, it.body.string())
            try {
                target.outputStream().use { out -> it.body.byteStream().copyTo(out) }
            } catch (e: IOException) {
                throw ApiException.Network(e)
            }
        }
    }

    // ---- Alerts, devices, profile ----

    suspend fun alerts(after: String?): AlertsPage = get("/api/mobile/alerts", mapOf("after" to after))
    suspend fun updateDevice(body: UpdateDeviceRequest): kotlinx.serialization.json.JsonElement = sendJson("PUT", "/api/mobile/devices/current", body)
    suspend fun devices(): List<DeviceDto> = get("/api/mobile/devices")
    suspend fun revokeDevice(id: String): SuccessResponse = sendEmpty("DELETE", "/api/mobile/devices/$id")
    suspend fun pushTest(): kotlinx.serialization.json.JsonElement = sendEmpty("POST", "/api/mobile/devices/current/push-test")

    suspend fun profile(): UserDto = get("/api/profile")
    suspend fun updateProfile(body: UpdateProfileRequest): UserDto = sendJson("PUT", "/api/profile", body)
    suspend fun changePassword(current: String, new: String): kotlinx.serialization.json.JsonElement = sendJson("PUT", "/api/profile/password", ChangePasswordRequest(current, new))
    suspend fun setMobilePush(enabled: Boolean): kotlinx.serialization.json.JsonElement = sendJson("PUT", "/api/profile/preferences", MobilePushPreference(enabled))
    suspend fun setLocale(preference: String): kotlinx.serialization.json.JsonElement = sendJson("POST", "/api/locale", LocaleRequest(preference))

    // ---- Assistant ----

    suspend fun assistantState(): AssistantState = get("/api/ai-chat/access")
    suspend fun conversations(): List<ConversationDto> = get("/api/ai-chat/conversations")
    suspend fun conversation(id: String): ConversationDetail = get("/api/ai-chat/conversations/$id")
    suspend fun createConversation(title: String?): ConversationDto = sendJson("POST", "/api/ai-chat/conversations", NewConversationRequest(title))
    suspend fun renameConversation(id: String, title: String): ConversationDto = sendJson("PATCH", "/api/ai-chat/conversations/$id", RenameConversationRequest(title))
    suspend fun deleteConversation(id: String): kotlinx.serialization.json.JsonElement = sendEmpty("DELETE", "/api/ai-chat/conversations/$id")
    suspend fun stopReply(id: String) {
        val (response, body) = execute(Request.Builder().url(url("/api/ai-chat/conversations/$id/stop")).post(EMPTY_BODY).build())
        if (!response.isSuccessful) throw failure(response.code, "/api/ai-chat/conversations/$id/stop", body)
    }

    /** Raw request of a chat turn, streamed by [AssistantStream]. */
    fun chatTurnRequest(id: String, body: String): Request =
        Request.Builder().url(url("/api/ai-chat/conversations/$id/messages")).post(body.toRequestBody(JSON)).build()

    fun failureOf(status: Int, path: String, body: String): ApiException = failure(status, path, body)

    @OptIn(ExperimentalSerializationApi::class)
    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = true
            encodeDefaults = true
        }

        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        private val SESSION_CODES = setOf("errors.unauthenticated", "errors.sessionExpired")
        private const val APP_NOT_ALLOWED = "mobile.errors.appNotAllowed"

        inline fun <reified T> decodeOrNull(body: String): T? = try {
            json.decodeFromString<T>(body)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

private inline fun <reified T> decodeOrNull(body: String): T? = ApiClient.decodeOrNull(body)

/** Multipart file read as it is sent: a large attachment is never loaded in memory. */
private class StreamBody(private val type: MediaType, private val size: Long, private val open: () -> InputStream?) : RequestBody() {
    override fun contentType() = type
    override fun contentLength() = size
    override fun writeTo(sink: BufferedSink) {
        val input = open() ?: throw IOException("file not readable")
        input.source().use { sink.writeAll(it) }
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = continuation.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { runCatching { cancel() } }
}
