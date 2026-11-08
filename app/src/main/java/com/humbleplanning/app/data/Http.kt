package com.humbleplanning.app.data

import com.humbleplanning.app.AppInfo
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/** Server session: the Better Auth cookie, bound to the server that issued it. */
class SessionStore(private val secure: SecureStore) {
    data class Session(val serverUrl: String, val cookieName: String, val cookieValue: String) {
        val origin: HttpUrl? = serverUrl.toHttpUrlOrNull()
        val cookie: String get() = "$cookieName=$cookieValue"
    }

    @Volatile
    private var cached: Session? = read()

    val current: Session? get() = cached

    private fun read(): Session? {
        val raw = secure.get(KEY) ?: return null
        val parts = raw.split('\n')
        return if (parts.size == 3) Session(parts[0], parts[1], parts[2]) else null
    }

    fun save(session: Session?) {
        cached = session
        secure.set(KEY, session?.let { "${it.serverUrl}\n${it.cookieName}\n${it.cookieValue}" })
    }

    private companion object {
        const val KEY = "session"
    }
}

/** Marks a request that must not carry the session (sign-in, or a server that is not the session's). */
const val HEADER_NO_SESSION = "X-No-Session"

/**
 * Adds what every request carries: app version, language and, only towards the server that
 * issued it, the session cookie. A server named by a link can therefore never receive it.
 */
class SessionInterceptor(private val sessions: SessionStore, private val language: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val session = sessions.current
        val sendSession = request.header(HEADER_NO_SESSION) == null && session != null && isServerOf(request.url, session)
        val language = language()
        val cookies = buildList {
            add("HP_LOCALE=$language")
            if (sendSession) add(session.cookie)
        }
        val response = chain.proceed(
            request.newBuilder()
                .removeHeader(HEADER_NO_SESSION)
                .header("X-App-Version", AppInfo.VERSION)
                .header("User-Agent", "HumblePlanning-Android/${AppInfo.VERSION}")
                .header("Accept-Language", language)
                .header("Cookie", cookies.joinToString("; "))
                .build()
        )
        // Better Auth can re-issue the cookie when it extends the session
        if (sendSession) {
            response.headers("Set-Cookie").firstNotNullOfOrNull { parseSessionCookie(it, session.cookieName) }?.let { value ->
                if (value != session.cookieValue) sessions.save(session.copy(cookieValue = value))
            }
        }
        return response
    }


    private fun parseSessionCookie(header: String, name: String): String? {
        if (!header.startsWith("$name=")) return null
        return header.substringAfter('=').substringBefore(';').ifEmpty { null }
    }
}

private fun isServerOf(url: HttpUrl, session: SessionStore.Session): Boolean {
    val origin = session.origin ?: return false
    return url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port
}

/**
 * A redirect keeps the headers of the request it answers, the cookie among them: at each step, what actually
 * goes out loses the session when it is headed for another server.
 */
internal class RedirectGuard(private val session: () -> SessionStore.Session?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val current = session()
        val cookies = request.header("Cookie")
        if (current == null || cookies == null || isServerOf(request.url, current)) return chain.proceed(request)
        val kept = cookies.split("; ").filterNot { it.startsWith("${current.cookieName}=") }.joinToString("; ")
        return chain.proceed(request.newBuilder().apply { if (kept.isEmpty()) removeHeader("Cookie") else header("Cookie", kept) }.build())
    }
}

/** Client shared by the API, images and real time. HTTPS is never downgraded by a redirect. */
fun buildHttpClient(sessions: SessionStore, language: () -> String): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(SessionInterceptor(sessions, language))
    .addNetworkInterceptor(RedirectGuard { sessions.current })
    .followSslRedirects(false)
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .callTimeout(90, TimeUnit.SECONDS)
    .build()
