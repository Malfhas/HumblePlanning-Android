package com.humbleplanning.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.humbleplanning.app.support.Seed
import com.humbleplanning.app.support.UiTest
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** The app closed and opened again: new activity and view models, on what the phone kept. */
@RunWith(AndroidJUnit4::class)
class ColdStartTest : UiTest(coldStart = true) {
    @Test fun sessionFiltersAndView_persistAfterRestart() {
        openApp()
        signIn(Seed.own)
        tap(icon(s(R.string.filters)))
        tap(clickable(s(R.string.filter_all_projects)))
        waitExists(clickable(s(R.string.ok)))
        pick(Seed.project2.name)
        tap(clickable(s(R.string.ok)))
        tap(clickable(s(R.string.apply)))
        waitForCard(Seed.task("otherProject").title)
        tap(clickable(s(R.string.view_week)))
        closeApp()

        openApp()
        expectMainScreen()
        assertFalse("signed in: no sign-in screen", exists(field(s(R.string.login_server))))
        waitFor("week view again") { texts().any { it.startsWith(s(R.string.week_of, "", 0).substringBefore(" ")) } }
        waitForCard(Seed.task("otherProject").title)
        assertFalse(exists(card(Seed.task("mine").title)))
    }
}
