package com.humbleplanning.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingActionsTest {
    private val code = "a".repeat(64)

    @After fun clear() {
        PendingActions.qrLogin.value = null
    }

    @Test fun acceptsAValidLink() {
        val login = PendingActions.parseQrLogin("humbleplanning://login?url=https%3A%2F%2Fplanning.example.com&code=$code")
        assertNotNull(login)
        assertEquals("https://planning.example.com", login?.serverUrl)
        assertEquals(code, login?.code)
    }

    @Test fun acceptsUpperCaseHexAndSurroundingSpaces() {
        val upper = "ABCDEF0123456789".repeat(4)
        assertEquals(upper, PendingActions.parseQrLogin("  humbleplanning://login?url=x.example&code=$upper\n")?.code)
    }

    @Test fun refusesOtherSchemes() {
        assertNull(PendingActions.parseQrLogin("https://login?url=https://planning.example.com&code=$code"))
        assertNull(PendingActions.parseQrLogin("otherapp://login?url=https://planning.example.com&code=$code"))
        assertNull(PendingActions.parseQrLogin("javascript:alert(1)"))
    }

    @Test fun refusesAnotherPathOfTheScheme() {
        assertNull(PendingActions.parseQrLogin("humbleplanning://logout?url=https://planning.example.com&code=$code"))
    }

    @Test fun refusesABadCode() {
        assertNull(PendingActions.parseQrLogin("humbleplanning://login?url=https://planning.example.com&code=${"a".repeat(63)}"))
        assertNull(PendingActions.parseQrLogin("humbleplanning://login?url=https://planning.example.com&code=${"a".repeat(65)}"))
        assertNull(PendingActions.parseQrLogin("humbleplanning://login?url=https://planning.example.com&code=${"g".repeat(64)}"))
        assertNull(PendingActions.parseQrLogin("humbleplanning://login?url=https://planning.example.com"))
    }

    @Test fun refusesAMissingServer() {
        assertNull(PendingActions.parseQrLogin("humbleplanning://login?code=$code"))
    }

    @Test fun aLinkOnlyAsksForConfirmation() {
        assertTrue(PendingActions.handleLink("humbleplanning://login?url=https%3A%2F%2Fplanning.example.com&code=$code"))
        assertEquals("https://planning.example.com", PendingActions.qrLogin.value?.serverUrl)
    }

    @Test fun anInvalidLinkIsIgnored() {
        assertFalse(PendingActions.handleLink("https://example.com"))
        assertNull(PendingActions.qrLogin.value)
    }
}
