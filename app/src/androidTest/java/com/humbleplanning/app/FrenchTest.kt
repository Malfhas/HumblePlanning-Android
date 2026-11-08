package com.humbleplanning.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.humbleplanning.app.support.Rest
import com.humbleplanning.app.support.Seed
import com.humbleplanning.app.support.UiTest
import com.humbleplanning.app.ui.longLabel
import java.time.LocalDate
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The app in French: its own strings, dates, and the messages of the server. */
@RunWith(AndroidJUnit4::class)
class FrenchTest : UiTest(language = "fr") {
    @Test fun stringsDatesAndServerMessagesAreInFrench() {
        assertNotEquals("the strings of the test are French", sIn("en", R.string.sign_in), s(R.string.sign_in))
        val french = Rest.loginResponse("${Seed.prefix}-personne", "faux", language = "fr").error
        val english = Rest.loginResponse("${Seed.prefix}-personne", "faux", language = "en").error
        assertNotEquals(english, french)
        attemptSignIn(Rest.newUser("francais", "own").login, "pas le mot de passe")
        waitText(french, timeout = LONG_TIMEOUT)

        attemptSignIn(Seed.own.login, Seed.own.password)
        expectMainScreen()
        waitExists(tab(R.string.tab_upcoming))
        waitDisplayed(label(LocalDate.now().longLabel(appLocale)))
        tap(icon(s(R.string.new_task)))
        waitExists(clickable(s(R.string.save)))
        waitText(mediumDate(LocalDate.now()))
    }
}
