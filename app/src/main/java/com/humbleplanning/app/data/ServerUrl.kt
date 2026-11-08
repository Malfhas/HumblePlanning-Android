package com.humbleplanning.app.data

import com.humbleplanning.app.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerUrl {
    sealed interface Result {
        data class Ok(val url: String, val host: String) : Result
        data object Empty : Result
        data object HttpsRequired : Result
        data object Invalid : Result
    }

    /** Debug builds only reach the local test server in clear text (see network_security_config). */
    private val CLEARTEXT_HOSTS = setOf("10.0.2.2", "localhost", "127.0.0.1")

    /** https://host[:port]/path, no trailing "/" or parameters: a password never leaves in clear text. */
    fun normalize(input: String, allowCleartextTestHosts: Boolean = BuildConfig.DEBUG): Result {
        val raw = input.trim()
        if (raw.isEmpty() || raw.equals("https://", ignoreCase = true)) return Result.Empty
        if (raw.any { it.isISOControl() } || raw.contains('\\')) return Result.Invalid
        val withScheme = if ("://" in raw) raw else "https://$raw"
        val url = withScheme.toHttpUrlOrNull()
        val cleartextAllowed = allowCleartextTestHosts && url?.scheme == "http" && url.host in CLEARTEXT_HOSTS
        if (!withScheme.startsWith("https://", ignoreCase = true) && !cleartextAllowed) return Result.HttpsRequired
        if (url == null || url.host.isBlank() || url.username.isNotEmpty()) return Result.Invalid
        val host = url.host.lowercase()
        val port = if (url.port != (if (url.isHttps) 443 else 80)) ":${url.port}" else ""
        val path = url.encodedPath.trimEnd('/')
        return Result.Ok("${url.scheme}://$host$port$path", host)
    }
}
