package com.humbleplanning.app.data

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The session cookie only goes to the server that issued it, even when that server redirects elsewhere. */
class SessionRedirectTest {
    private val own = MockWebServer()
    private val other = MockWebServer()

    @Before fun start() {
        own.start()
        other.start()
    }

    @After fun stop() {
        own.close()
        other.close()
    }

    /** Headers as the app adds them, then the guard of its client. */
    private fun client(session: SessionStore.Session) = OkHttpClient.Builder()
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Cookie", "HP_LOCALE=en; ${session.cookie}").build()) }
        .addNetworkInterceptor(RedirectGuard { session })
        .build()

    @Test fun redirectToAnotherServerDoesNotCarryTheSession() {
        val origin = own.url("/start").newBuilder().host("localhost").build()
        val target = other.url("/next").newBuilder().host("127.0.0.1").build()
        val session = SessionStore.Session("${origin.scheme}://${origin.host}:${origin.port}", "session", "secret")
        own.enqueue(MockResponse.Builder().code(302).addHeader("Location", target.toString()).build())
        other.enqueue(MockResponse.Builder().code(200).body("ok").build())

        client(session).newCall(Request.Builder().url(origin).build()).execute().use { assertEquals(200, it.code) }

        assertTrue(own.takeRequest().headers["Cookie"].orEmpty().contains("session=secret"))
        val next = other.takeRequest().headers["Cookie"].orEmpty()
        assertFalse(next, next.contains("session=secret"))
        assertEquals("what is not the session stays", "HP_LOCALE=en", next)
    }

    @Test fun redirectOnTheSameServerKeepsTheSession() {
        val origin = own.url("/start")
        val session = SessionStore.Session("${origin.scheme}://${origin.host}:${origin.port}", "session", "secret")
        own.enqueue(MockResponse.Builder().code(302).addHeader("Location", own.url("/next").toString()).build())
        own.enqueue(MockResponse.Builder().code(200).body("ok").build())

        client(session).newCall(Request.Builder().url(origin).build()).execute().use { assertEquals(200, it.code) }

        own.takeRequest()
        assertTrue(own.takeRequest().headers["Cookie"].orEmpty().contains("session=secret"))
    }
}