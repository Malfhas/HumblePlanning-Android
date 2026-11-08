package com.humbleplanning.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.humbleplanning.app.data.ThemeMode
import com.humbleplanning.app.support.Account
import com.humbleplanning.app.support.Rest
import com.humbleplanning.app.support.Totp
import com.humbleplanning.app.support.UiTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsTest : UiTest() {
    private val account: Account by lazy { Rest.newUser("settings", "own") }

    private fun openSettings() {
        signIn(account)
        goTo(R.string.tab_settings)
        waitText(account.name)
    }

    @Test fun themeAndTheFiveLanguages() {
        openSettings()
        tap(clickable(s(R.string.theme_dark)))
        waitFor("dark") { app.graph.repository.theme.value == ThemeMode.Dark }
        tap(clickable(s(R.string.theme_light)))
        waitFor("light") { app.graph.repository.theme.value == ThemeMode.Light }

        var languageRow = s(R.string.settings_language)
        for ((tag, name) in listOf("fr" to "Français", "es" to "Español", "de" to "Deutsch", "en" to "English")) {
            tap(clickable(languageRow, substring = true))
            tap(pickerItem(name))
            // The whole app follows, without a restart
            waitExists(clickable(sIn(tag, R.string.tab_settings)), LONG_TIMEOUT)
            waitFor("language $tag") { app.graph.repository.language.value == tag && app.graph.repository.effectiveLanguage() == tag }
            languageRow = sIn(tag, R.string.settings_language)
        }
        // Fifth option: back to the language of the phone
        tap(clickable(languageRow, substring = true))
        tap(pickerItem(s(R.string.language_system)))
        waitFor("follows the phone again", LONG_TIMEOUT) { app.graph.repository.language.value == "" }
        assertTrue(app.graph.repository.isLoggedIn)
    }

    @Test fun profile_isSaved_andPassword_wrongThenCorrect() {
        openSettings()
        tap(icon(s(R.string.profile_edit)))
        waitText(s(R.string.profile_title))
        type(s(R.string.profile_first_name), "Uitest")
        type(s(R.string.profile_job), "Tester")
        waitRevealed(clickable(s(R.string.save)))
        tap(clickable(s(R.string.save)))
        waitText(s(R.string.profile_saved), timeout = LONG_TIMEOUT)
        val profile = Rest.profile(Rest.sessionOf(account))
        assertEquals("Uitest", profile.getString("firstName"))
        assertEquals("Tester", profile.getString("job"))
        pressBackUntilGone(label(s(R.string.profile_title)))
        waitRevealed(label("Uitest ${account.name}"))

        val expected = Rest.call(
            "PUT", "/api/profile/password", Rest.sessionOf(account),
            JSONObject().put("currentPassword", "wrong wrong").put("newPassword", "Another2026!x"),
        ).error
        assertTrue(expected.isNotBlank())
        waitRevealed(clickable(s(R.string.password_change)))
        tap(clickable(s(R.string.password_change)))
        type(s(R.string.password_current), "wrong wrong")
        type(s(R.string.password_new), "Another2026!x")
        type(s(R.string.password_confirm), "Another2026!x")
        tap(clickable(s(R.string.save)) and isInDialog())
        waitText(expected, timeout = LONG_TIMEOUT)
        type(s(R.string.password_current), account.password)
        tap(clickable(s(R.string.save)) and isInDialog())
        waitText(s(R.string.security_password_changed), timeout = LONG_TIMEOUT)
        assertTrue("this session stays", app.graph.repository.isLoggedIn)
        assertEquals("authenticated", Rest.loginResponse(account.login, "Another2026!x").json.getString("status"))
    }

    @Test fun twoFactor_enableThenDisable() {
        openSettings()
        waitRevealed(clickable(s(R.string.two_factor_enable)))
        tap(clickable(s(R.string.two_factor_enable)))
        waitText(s(R.string.two_factor_setup_password))
        type(s(R.string.login_password), account.password)
        tap(clickable(s(R.string.continue_action)))
        var secret = ""
        waitFor("key on screen", LONG_TIMEOUT) {
            secret = texts().firstOrNull { it.matches(Regex("^[A-Z2-7]{16,}=*$")) }.orEmpty()
            secret.isNotEmpty()
        }
        type(s(R.string.two_factor_code), Totp.now(secret))
        tap(clickable(s(R.string.two_factor_verify)))
        waitText(s(R.string.two_factor_backup_keep), timeout = LONG_TIMEOUT)
        tap(clickable(s(R.string.two_factor_backup_done)))
        waitExists(tab(R.string.tab_settings), LONG_TIMEOUT)
        assertEquals("two_factor_required", Rest.loginResponse(account.login, account.password).json.getString("status"))

        waitRevealed(clickable(s(R.string.two_factor_disable), substring = true))
        tap(clickable(s(R.string.two_factor_disable), substring = true))
        waitText(s(R.string.two_factor_disable_text))
        type(s(R.string.login_password), account.password)
        tap(clickable(s(R.string.two_factor_disable)) and isInDialog())
        waitText(s(R.string.two_factor_disabled), timeout = LONG_TIMEOUT)
        assertEquals("authenticated", Rest.loginResponse(account.login, account.password).json.getString("status"))
    }

    @Test fun devices_revokeAnotherOne_andPushTestSaysWhatHappened() {
        grantNotifications()
        // Push through the relay of the mock server; no device of the account has a push token
        Rest.setConfig("MOBILE_PUSH_RELAY_URL", "http://mock:4000")
        afterTest { Rest.setConfig("MOBILE_PUSH_RELAY_URL", "") }
        val other = Rest.login(account, deviceName = "Second phone")
        openSettings()

        waitRevealed(clickable(s(R.string.alerts_send_test)))
        tap(clickable(s(R.string.alerts_send_test)))
        waitText(s(R.string.alerts_test_not_delivered), timeout = LONG_TIMEOUT)
        // Asked from the open app, the test still shows as a notification: it is there to check them
        waitNotification()

        waitRevealed(label("Second phone"))
        tap(icon(s(R.string.devices_revoke_label, "Second phone")))
        waitText(s(R.string.devices_revoke_title))
        tap(clickable(s(R.string.devices_revoke)) and isInDialog())
        waitForServer("other session gone") { !Rest.sessionAlive(other) }
        waitGone(label("Second phone"))
        assertTrue("this one stays", Rest.sessionAlive(Rest.sessionOf(account)) && app.graph.repository.isLoggedIn)
    }
}
