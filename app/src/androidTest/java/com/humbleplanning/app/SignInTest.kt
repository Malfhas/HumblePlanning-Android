package com.humbleplanning.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.humbleplanning.app.support.Rest
import com.humbleplanning.app.support.Seed
import com.humbleplanning.app.support.Totp
import com.humbleplanning.app.support.UiTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignInTest : UiTest() {
    private fun editableText(label: String): String =
        compose.onAllNodes(field(label)).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    @Test fun badAddresses_areRefused() {
        attemptSignIn("someone", "anything", "https://exa mple")
        waitText(s(R.string.err_url_invalid))
        attemptSignIn("someone", "anything", "http://example.com")
        waitText(s(R.string.err_https))
        attemptSignIn("someone", "anything", Seed.mockUrl)
        waitText(s(R.string.err_not_humble), timeout = LONG_TIMEOUT)
    }

    @Test fun refusals_showTheMessageOfTheServer() {
        val account = Rest.newUser("wrongpass", "own")
        val wrong = Rest.loginResponse("${account.login}-nobody", "wrong").error
        assertTrue(wrong.isNotBlank())
        attemptSignIn(account.login, "not the password")
        waitText(wrong, timeout = LONG_TIMEOUT)
        expectLoginScreen()

        val disabled = Rest.loginResponse(Seed.disabled.login, Seed.disabled.password)
        assertEquals(403, disabled.status)
        attemptSignIn(Seed.disabled.login, Seed.disabled.password)
        waitText(disabled.error, timeout = LONG_TIMEOUT)
    }

    @Test fun forcedPasswordChange_cancelClosesTheSession_thenMismatch_thenSuccess() {
        val account = Seed.forced
        val other = Rest.login(account, deviceName = "other")
        attemptSignIn(account.login, account.password)
        waitText(s(R.string.password_change_required), timeout = LONG_TIMEOUT)
        waitForServer("session of the app open") { Rest.devices(other).size == 2 }
        tap(clickable(s(R.string.cancel)))
        expectLoginScreen()
        waitForServer("session of the app closed") { Rest.devices(other).size == 1 }

        attemptSignIn(account.login, account.password)
        waitText(s(R.string.password_change_required), timeout = LONG_TIMEOUT)
        type(s(R.string.password_new), "NewPassword2026!x")
        type(s(R.string.password_confirm), "Different2026!x")
        tap(clickable(s(R.string.password_change)))
        waitText(s(R.string.password_mismatch))
        type(s(R.string.password_confirm), "NewPassword2026!x")
        tap(clickable(s(R.string.password_change)))
        expectMainScreen()
        val response = Rest.loginResponse(account.login, "NewPassword2026!x").json
        assertEquals("authenticated", response.getString("status"))
        assertFalse(response.getBoolean("forcePasswordChange"))
    }

    @Test fun twoFactor_wrongCodeIsRefused_thenTheRightOneSignsIn() {
        val account = Seed.twoFactor
        attemptSignIn(account.login, account.password)
        waitText(s(R.string.two_factor_text), timeout = LONG_TIMEOUT)
        val wrong = if (Totp.now(account.totpSecret!!) == "000000") "111111" else "000000"
        type(s(R.string.two_factor_code), wrong)
        tap(clickable(s(R.string.two_factor_verify)))
        waitFor("code emptied after the refusal", LONG_TIMEOUT) { exists(field(s(R.string.two_factor_code))) && editableText(s(R.string.two_factor_code)).isEmpty() }
        type(s(R.string.two_factor_code), Totp.now(account.totpSecret))
        tap(clickable(s(R.string.two_factor_verify)))
        expectMainScreen()
    }

    @Test fun twoFactor_aBackupCodeWorksOnce() {
        val account = Rest.withTwoFactor(Rest.newUser("tfbackup", "own"))
        val code = account.backupCodes.first()
        repeat(2) { attempt ->
            attemptSignIn(account.login, account.password)
            waitText(s(R.string.two_factor_text), timeout = LONG_TIMEOUT)
            tap(clickable(s(R.string.two_factor_use_backup)))
            waitText(s(R.string.two_factor_backup_text))
            type(s(R.string.two_factor_backup_code), code)
            tap(clickable(s(R.string.two_factor_verify)))
            if (attempt == 0) {
                expectMainScreen()
                signOutFromSettings()
            }
        }
        waitFor("refused the second time", LONG_TIMEOUT) { exists(field(s(R.string.two_factor_backup_code))) && editableText(s(R.string.two_factor_backup_code)).isEmpty() }
        assertFalse(exists(tab(R.string.tab_planning)))
    }

    @Test fun forcedTwoFactor_goesThroughSetupToBackupCodes() {
        val account = Seed.force2fa
        attemptSignIn(account.login, account.password)
        waitText(s(R.string.two_factor_setup_required), timeout = LONG_TIMEOUT)
        type(s(R.string.login_password), account.password)
        tap(clickable(s(R.string.continue_action)))
        var secret = ""
        waitFor("key shown for the authenticator app", LONG_TIMEOUT) {
            secret = texts().firstOrNull { it.matches(Regex("^[A-Z2-7]{16,}=*$")) }.orEmpty()
            secret.isNotEmpty()
        }
        type(s(R.string.two_factor_code), Totp.now(secret))
        tap(clickable(s(R.string.two_factor_verify)))
        waitText(s(R.string.two_factor_backup_keep), timeout = LONG_TIMEOUT)
        tap(clickable(s(R.string.two_factor_backup_done)))
        expectMainScreen()
        assertEquals("now on", "two_factor_required", Rest.loginResponse(account.login, account.password).json.getString("status"))
    }
}
