package com.humbleplanning.app

import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Sign-in link read from a QR code: humbleplanning://login?url=<server address>&code=<single-use code>. */
data class QrLogin(val serverUrl: String, val code: String)

/** Actions coming from outside (link, notification, scanner), handled by the interface. */
object PendingActions {
    val qrLogin = MutableStateFlow<QrLogin?>(null)
    val openTaskId = MutableStateFlow<String?>(null)

    /** Null when this is not a valid Humble Planning sign-in link. */
    fun parseQrLogin(link: String): QrLogin? {
        val trimmed = link.trim()
        if (!trimmed.startsWith("${AppInfo.QR_SCHEME}://login", ignoreCase = true)) return null
        // HttpUrl only reads http(s): same link, scheme replaced, to read its parameters
        val url = ("https://" + trimmed.substringAfter("://")).toHttpUrlOrNull() ?: return null
        val server = url.queryParameter("url")?.trim().orEmpty()
        val code = url.queryParameter("code")?.trim().orEmpty()
        if (server.isEmpty() || !code.matches(Regex("^[0-9a-fA-F]{64}$"))) return null
        return QrLogin(server, code)
    }

    fun openTask(taskId: String) {
        openTaskId.value = taskId
    }

    /** The link only opens the confirmation of the sign-in screen: nothing is sent to its server before that. */
    fun handleLink(link: String): Boolean {
        val login = parseQrLogin(link) ?: return false
        qrLogin.value = login
        return true
    }
}
