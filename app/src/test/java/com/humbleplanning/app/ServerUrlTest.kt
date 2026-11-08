package com.humbleplanning.app

import com.humbleplanning.app.data.ServerUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {
    private fun release(input: String) = ServerUrl.normalize(input, allowCleartextTestHosts = false)
    private fun debug(input: String) = ServerUrl.normalize(input, allowCleartextTestHosts = true)

    @Test fun addsHttpsAndRemovesTheEnd() {
        assertEquals(ServerUrl.Result.Ok("https://planning.example.com", "planning.example.com"), release("planning.example.com"))
        assertEquals(ServerUrl.Result.Ok("https://planning.example.com", "planning.example.com"), release("  https://Planning.Example.com/  "))
        assertEquals(ServerUrl.Result.Ok("https://planning.example.com/app", "planning.example.com"), release("https://planning.example.com/app/"))
    }

    @Test fun keepsAPortThatIsNotTheDefault() {
        assertEquals("https://planning.example.com:8443", (release("planning.example.com:8443") as ServerUrl.Result.Ok).url)
        assertEquals("https://planning.example.com", (release("planning.example.com:443") as ServerUrl.Result.Ok).url)
    }

    @Test fun emptyInput() {
        assertEquals(ServerUrl.Result.Empty, release(""))
        assertEquals(ServerUrl.Result.Empty, release("   "))
        assertEquals(ServerUrl.Result.Empty, release("https://"))
    }

    @Test fun releaseRefusesHttp() {
        assertEquals(ServerUrl.Result.HttpsRequired, release("http://planning.example.com"))
        assertEquals(ServerUrl.Result.HttpsRequired, release("http://10.0.2.2:3000"))
        assertEquals(ServerUrl.Result.HttpsRequired, release("http://localhost:3000"))
        assertEquals(ServerUrl.Result.HttpsRequired, release("ftp://planning.example.com"))
    }

    @Test fun debugAcceptsTheEmulatorHostInClearText() {
        assertEquals(ServerUrl.Result.Ok("http://10.0.2.2:3000", "10.0.2.2"), debug("http://10.0.2.2:3000"))
    }

    @Test fun debugStillRefusesHttpForAnyOtherServer() {
        assertEquals(ServerUrl.Result.HttpsRequired, debug("http://planning.example.com"))
        assertEquals(ServerUrl.Result.HttpsRequired, debug("http://10.0.2.3:3000"))
        assertEquals(ServerUrl.Result.HttpsRequired, debug("http://10.0.2.2.evil.example"))
    }

    @Test fun refusesWhatCouldHideTheRealServer() {
        assertEquals(ServerUrl.Result.Invalid, release("https://user:pass@planning.example.com"))
        assertEquals(ServerUrl.Result.Invalid, release("https://planning.example.com\\@evil.example"))
        assertEquals(ServerUrl.Result.Invalid, release("https://planning.example.com\nhost"))
    }

    @Test fun theHostIsTheOneThatIsReallyContacted() {
        val result = release("https://planning.example.com.evil.example/planning.example.com")
        assertTrue(result is ServerUrl.Result.Ok)
        assertEquals("planning.example.com.evil.example", (result as ServerUrl.Result.Ok).host)
    }
}
