package com.humbleplanning.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.humbleplanning.app.support.Rest
import com.humbleplanning.app.support.Seed
import com.humbleplanning.app.support.UiTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** humbleplanning://login?url=...&code=...: nothing goes to the server named by a link until the user says so. */
@RunWith(AndroidJUnit4::class)
class SignInLinkTest : UiTest() {
    private fun linkTo(server: String) = "humbleplanning://login?url=${Uri.encode(server)}&code=${"a".repeat(64)}"
    private fun codeOf(link: String) = Uri.parse(link).getQueryParameter("code")!!

    private fun expectConfirmation(host: String) {
        waitText(s(R.string.qr_confirm_title))
        assertTrue("the server is shown in clear", exists(label(host)))
    }

    private fun confirm() = tap(clickable(s(R.string.sign_in)) and isInDialog())
    private fun refuse() = tap(clickable(s(R.string.cancel)) and isInDialog())

    @Test fun linkAsksFirst_aRefusalSendsNothing_andBadLinksAreNotAccepted() {
        val account = Rest.newUser("linkrefused", "own")
        val session = Rest.login(account)
        val link = Rest.pairingLink(session)
        val before = Rest.devices(session).size

        openLink(link)
        expectConfirmation("10.0.2.2:3100")
        assertEquals("nothing on the server while the question is on screen", before, Rest.devices(session).size)
        refuse()
        waitGone(label(s(R.string.qr_confirm_title)))
        assertEquals("no session for a refused link", before, Rest.devices(session).size)
        assertEquals("the app did not use the code", "authenticated", Rest.redeemPairing(codeOf(link)).json.getString("status"))

        // Not a sign-in link: ignored
        openLink("humbleplanning://login?url=${Uri.encode(Seed.serverUrl)}&code=short")
        staysAbsent(label(s(R.string.qr_confirm_title)), 1_000)
        // An address the app does not connect to, whatever hides in it: said so, no way forward
        for (server in listOf("http://example.com", "https://planning.example.com@evil.example")) {
            openLink(linkTo(server))
            waitText(s(R.string.qr_invalid_title))
            assertFalse(exists(label(s(R.string.qr_confirm_title))))
            tap(clickable(s(R.string.close)))
            waitGone(label(s(R.string.qr_invalid_title)))
        }
        expectLoginScreen()
    }

    @Test fun validCodeSignsIn_andAReplayIsRefused() {
        val account = Rest.newUser("linkvalid", "own")
        val session = Rest.login(account)
        val before = Rest.devices(session).size
        val link = Rest.pairingLink(session)
        openLink(link)
        expectConfirmation("10.0.2.2:3100")
        confirm()
        expectMainScreen()
        assertEquals(account.id, app.graph.repository.userId)
        assertEquals(before + 1, Rest.devices(session).size)

        signOutFromSettings()
        val expected = Rest.redeemPairing(codeOf(link)).error
        assertTrue(expected.isNotBlank())
        openLink(link)
        expectConfirmation("10.0.2.2:3100")
        confirm()
        waitText(expected, timeout = LONG_TIMEOUT)
        assertFalse(app.graph.repository.isLoggedIn)
    }

    @Test fun whileSignedIn_refusingKeepsTheSession_confirmingSignsOutFirst() {
        val account = Rest.newUser("linkleave", "own")
        val other = Rest.login(account, deviceName = "other")
        signIn(account)
        waitForServer("session open") { Rest.devices(other).size == 2 }

        openLink(linkTo(Seed.mockUrl))
        // The port is part of what the user decides on
        expectConfirmation("10.0.2.2:3102")
        assertTrue("warned about the sign-out", exists(label(s(R.string.qr_confirm_signed_in))))
        refuse()
        waitGone(label(s(R.string.qr_confirm_title)))
        assertTrue(app.graph.repository.isLoggedIn)
        assertEquals(2, Rest.devices(other).size)

        openLink(linkTo(Seed.mockUrl))
        expectConfirmation("10.0.2.2:3102")
        confirm()
        // The mock answers no sign-in: the app says so, signed out of its server
        waitText(s(R.string.err_not_humble), timeout = LONG_TIMEOUT)
        expectLoginScreen()
        assertFalse(app.graph.repository.isLoggedIn)
        waitForServer("the old session is closed on its server") { Rest.devices(other).size == 1 }
    }
}
