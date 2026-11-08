package com.humbleplanning.app.support

import java.nio.ByteBuffer
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class RestSession(val cookie: String, val userId: String)

class RestResponse(val status: Int, val text: String) {
    val ok: Boolean get() = status in 200..299
    val json: JSONObject get() = JSONObject(text)
    val array: JSONArray get() = JSONArray(text)
    /** Translated message of a refusal. */
    val error: String get() = json.optString("error")
    override fun toString() = "HTTP $status ${text.take(300)}"
}

/** The server as another user or an administrator sees it: what the tests do "from elsewhere" and read back. */
object Rest {
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val sessions = HashMap<String, RestSession>()
    private val counter = AtomicInteger()

    /** The "web" client carries no app version: the version gate and maintenance do not concern it. */
    fun call(method: String, path: String, session: RestSession? = null, body: Any? = null, web: Boolean = false, language: String = "en"): RestResponse {
        val request = Request.Builder().url(Seed.serverUrl + path).apply {
            if (session != null) header("Cookie", session.cookie)
            if (!web) header("X-App-Version", Seed.appVersion)
            header("Accept-Language", language)
            header("Cookie", listOfNotNull(session?.cookie, "HP_LOCALE=$language").joinToString("; "))
            val payload = body?.toString()?.toRequestBody(JSON_TYPE)
            method(method, payload ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody(null) else null)
        }.build()
        client.newCall(request).execute().use { return RestResponse(it.code, it.body.string()) }
    }

    fun resetConnections() = client.connectionPool.evictAll()

    private fun must(what: String, response: RestResponse): RestResponse {
        check(response.ok) { "$what: $response" }
        return response
    }

    private fun device(name: String) = JSONObject().put("name", name).put("appVersion", Seed.appVersion).put("locale", "en")

    fun loginResponse(identifier: String, password: String, language: String = "en"): RestResponse =
        call("POST", "/api/mobile/auth/login", body = JSONObject().put("identifier", identifier).put("password", password).put("device", device("rest")), language = language)

    /** A new session, seen as another device of this account. */
    fun login(account: Account, password: String = account.password, deviceName: String = "rest"): RestSession {
        val body = must(
            "login ${account.login}",
            call("POST", "/api/mobile/auth/login", body = JSONObject().put("identifier", account.login).put("password", password).put("device", device(deviceName))),
        ).json
        check(body.getString("status") == "authenticated") { "login ${account.login}: $body" }
        val session = body.getJSONObject("session")
        return RestSession("${session.getString("cookieName")}=${session.getString("cookieValue")}", session.getString("userId"))
    }

    val admin: RestSession get() = sessions.getOrPut("admin") { login(Seed.admin, deviceName = "admin") }

    /** One session per account for reading back; the app's own session stays another device. */
    fun sessionOf(account: Account): RestSession = sessions.getOrPut(account.id) { login(account) }

    // ---- Tasks ----

    fun unique(name: String) = "${Seed.prefix} $name ${counter.incrementAndGet()}-${System.currentTimeMillis() % 100000}"

    fun createTask(
        title: String,
        userIds: List<String>,
        date: LocalDate = LocalDate.now(),
        web: Boolean = false,
        over: JSONObject.() -> Unit = {},
    ): JSONObject {
        val body = JSONObject()
            .put("projectId", Seed.project.id)
            .put("userIds", JSONArray(userIds))
            .put("statusId", Seed.defaultStatus.id)
            .put("startDate", date.toString())
            .put("title", title)
            .put("isMilestone", false)
            .apply(over)
        return must("create task", call("POST", "/api/tasks", admin, body, web = web)).json
    }

    fun updateTask(id: String, over: JSONObject.() -> Unit) =
        must("update task", call("PUT", "/api/tasks/$id", admin, JSONObject().apply(over))).json

    fun deleteTask(id: String) = must("delete task", call("DELETE", "/api/tasks/$id?scope=single", admin))

    /** Null when the task no longer exists. */
    fun task(id: String): JSONObject? {
        val response = call("GET", "/api/tasks/$id", admin)
        if (response.status == 404) return null
        return must("read task", response).json
    }

    fun tasksBetween(start: LocalDate, end: LocalDate): List<JSONObject> {
        val changed = must("sync", call("GET", "/api/mobile/tasks/sync?startDate=$start&endDate=$end", admin)).json.getJSONArray("changed")
        return List(changed.length()) { changed.getJSONObject(it) }
    }

    fun taskByTitle(title: String, date: LocalDate = LocalDate.now()): JSONObject? =
        tasksBetween(date, date).firstOrNull { it.optString("title") == title }

    /** A daily series from `start` to `end`, returned as root and occurrences, oldest first. */
    fun createSeries(title: String, userIds: List<String>, start: LocalDate, end: LocalDate): List<JSONObject> {
        val root = createTask(title, userIds, start)
        must(
            "repeat",
            call("POST", "/api/tasks/${root.getString("id")}/repeat", admin, JSONObject().put("recurType", "daily").put("recurFrequency", 1).put("recurEndDate", end.toString()).put("recurException", "include")),
        )
        return tasksBetween(start, end).filter { it.optString("parentId") == root.getString("id") }.sortedBy { it.getString("startDate") }
    }

    fun uploadFile(taskId: String, name: String, bytes: ByteArray): JSONObject {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", name, bytes.toRequestBody("application/pdf".toMediaType())).build()
        val request = Request.Builder().url("${Seed.serverUrl}/api/tasks/$taskId/files").header("Cookie", admin.cookie).header("X-App-Version", Seed.appVersion).post(form).build()
        client.newCall(request).execute().use {
            val text = it.body.string()
            check(it.isSuccessful) { "upload: HTTP ${it.code} $text" }
            return JSONObject(text)
        }
    }

    // ---- Accounts ----

    /** A new account for a test that uses it up (lockout, one-shot code, password change...). */
    fun newUser(name: String, roleKey: String, extra: JSONObject.() -> Unit = {}): Account {
        val suffix = "${counter.incrementAndGet()}-${System.currentTimeMillis() % 100000}"
        val login = "${Seed.prefix.lowercase()}-${name.lowercase()}-$suffix"
        val fullName = "${Seed.prefix} $name $suffix"
        val body = JSONObject()
            .put("name", fullName).put("username", login).put("password", Seed.password).put("roleId", Seed.roleId(roleKey))
            .put("color", "#3366ff").put("visibleOnPlanning", true).put("notifications", false).put("pushNotifications", false)
            .put("smsNotifications", false).put("active", true)
            .apply(extra)
        val user = must("create user", call("POST", "/api/users", admin, body)).json
        return Account(user.getString("id"), login, Seed.password, fullName)
    }

    fun updateUser(id: String, over: JSONObject.() -> Unit) = must("update user", call("PUT", "/api/users/$id", admin, JSONObject().apply(over)))

    fun profile(session: RestSession): JSONObject = must("profile", call("GET", "/api/profile", session)).json

    /** Turns two-step verification on for an account: secret and backup codes, as its phone would hold them. */
    fun withTwoFactor(account: Account): Account {
        val session = login(account)
        val enabled = must("2FA enable", call("POST", "/api/profile/2fa/enable", session, JSONObject().put("password", account.password))).json
        val secret = enabled.getString("secret")
        val verified = must("2FA verify", call("POST", "/api/profile/2fa/verify", session, JSONObject().put("code", Totp.now(secret)))).json
        val codes = verified.getJSONArray("backupCodes")
        return account.copy(totpSecret = secret, backupCodes = List(codes.length()) { codes.getString(it) })
    }

    fun devices(session: RestSession): List<JSONObject> {
        val list = must("devices", call("GET", "/api/mobile/devices", session)).array
        return List(list.length()) { list.getJSONObject(it) }
    }

    /** Whether this session is still accepted by the server. */
    fun sessionAlive(session: RestSession): Boolean = call("GET", "/api/profile", session).status != 401

    /** New sign-in link for the account (the QR code of the web profile). */
    fun pairingLink(session: RestSession): String = must("pairing", call("POST", "/api/mobile/pairing", session)).json.getString("link")

    fun redeemPairing(code: String): RestResponse = call("POST", "/api/mobile/auth/pairing", body = JSONObject().put("code", code).put("device", device("rest")))

    fun alerts(session: RestSession): List<JSONObject> {
        val list = must("alerts", call("GET", "/api/mobile/alerts", session)).json.getJSONArray("alerts")
        return List(list.length()) { list.getJSONObject(it) }
    }

    // ---- Settings of the instance ----

    fun setConfig(key: String, value: String) = must("config $key", call("PUT", "/api/config", admin, JSONObject().put(key, value), web = true))

    fun mobileInfo(): JSONObject = must("info", call("GET", "/api/mobile/info")).json

    /** Monthly quota of the assistant: `{enabled, amountUsd, resetDay, resetAt}`. */
    fun assistantQuotaSettings(): JSONObject =
        must("quota settings", call("GET", "/api/ai-chat/admin/quotas", admin, web = true)).json.getJSONObject("settings")

    fun setAssistantQuota(enabled: Boolean, amountUsd: Double, resetDay: Int) = must(
        "quota settings",
        call("PUT", "/api/ai-chat/admin/quotas/settings", admin, JSONObject().put("enabled", enabled).put("amountUsd", amountUsd).put("resetDay", resetDay), web = true),
    )

    fun conversations(session: RestSession): List<JSONObject> {
        val list = must("conversations", call("GET", "/api/ai-chat/conversations", session)).array
        return List(list.length()) { list.getJSONObject(it) }
    }
}

/** RFC 6238 TOTP (SHA-1, 6 digits, 30 s), what an authenticator app would show. */
object Totp {
    /**
     * A code that still has time to reach the server: it refuses one of the period that just ended. This is the one
     * wait on a clock in these tests, because the code itself depends on it.
     */
    fun now(secret: String): String {
        val left = 30_000 - System.currentTimeMillis() % 30_000
        if (left < 6_000) Thread.sleep(left + 200)
        return at(secret, System.currentTimeMillis())
    }

    private fun at(secret: String, at: Long): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val bits = secret.trimEnd('=').uppercase().map { alphabet.indexOf(it).toString(2).padStart(5, '0') }.joinToString("")
        val key = bits.chunked(8).filter { it.length == 8 }.map { it.toInt(2).toByte() }.toByteArray()
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(at / 30_000).array())
        val offset = hash.last().toInt() and 15
        val value = ByteBuffer.wrap(hash, offset, 4).int and 0x7fffffff
        return (value % 1_000_000).toString().padStart(6, '0')
    }
}
