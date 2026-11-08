package com.humbleplanning.app.support

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject

/** An account of the data set: `totpSecret` and `backupCodes` only when two-step verification is on. */
data class Account(
    val id: String,
    val login: String,
    val password: String,
    val name: String,
    val totpSecret: String? = null,
    val backupCodes: List<String> = emptyList(),
)

data class SeedTask(val id: String, val title: String)

data class Ref(val id: String, val name: String)

/**
 * Data set written by tools/ui-tests/seed.mjs and pushed to the emulator by run.ps1.
 * Its path can be given to the instrumentation: `-e seedFile /data/local/tmp/other.json`.
 */
object Seed {
    private const val DEFAULT_FILE = "/data/local/tmp/hp-ui-seed.json"

    private val json: JSONObject by lazy {
        val path = InstrumentationRegistry.getArguments().getString("seedFile") ?: DEFAULT_FILE
        // Read by the shell: the app itself cannot open /data/local/tmp
        val pipe = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")
        val text = ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes().decodeToString() }
        check(text.startsWith("{")) { "No seed in $path: run tools/ui-tests/run.ps1, which writes and pushes it" }
        JSONObject(text)
    }

    val prefix: String get() = json.getString("prefix")
    val serverUrl: String get() = json.getString("serverUrl")
    val mockUrl: String get() = json.getString("mockUrl")
    val password: String get() = json.getString("password")
    val appVersion: String get() = json.getString("appVersion")

    fun roleId(key: String): String = json.getJSONObject("roles").getString(key)

    fun account(key: String): Account {
        val a = json.getJSONObject("accounts").getJSONObject(key)
        val codes = a.optJSONArray("backupCodes")
        return Account(
            a.getString("id"), a.getString("login"), a.getString("password"), a.getString("name"),
            a.optString("totpSecret").ifEmpty { null },
            if (codes == null) emptyList() else List(codes.length()) { codes.getString(it) },
        )
    }

    fun task(key: String): SeedTask = json.getJSONObject("tasks").getJSONObject(key).let { SeedTask(it.getString("id"), it.getString("title")) }

    private fun ref(key: String) = json.getJSONObject(key).let { Ref(it.getString("id"), it.getString("name")) }

    val project: Ref get() = ref("project")
    val project2: Ref get() = ref("project2")
    val defaultStatus: Ref get() = ref("defaultStatus")
    val styledStatus: Ref get() = ref("styledStatus")
    val teamA: Ref get() = json.getJSONObject("teams").getJSONObject("a").let { Ref(it.getString("id"), it.getString("name")) }
    val holidayDate: String get() = json.getJSONObject("holiday").getString("date")
    val holidayLabel: String get() = json.getJSONObject("holiday").getString("label")

    val admin get() = account("admin")
    val reader get() = account("reader")
    val own get() = account("own")
    val own2 get() = account("own2")
    val team get() = account("team")
    val teammate get() = account("teammate")
    val outsider get() = account("outsider")
    val full get() = account("full")
    val noAssistant get() = account("noAssistant")
    val forced get() = account("forced")
    val force2fa get() = account("force2fa")
    val disabled get() = account("disabled")
    val twoFactor get() = account("twoFactor")
}
