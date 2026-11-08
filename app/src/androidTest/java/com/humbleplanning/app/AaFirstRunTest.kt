package com.humbleplanning.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import com.humbleplanning.app.support.Seed
import com.humbleplanning.app.support.UiTest
import java.util.regex.Pattern
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The system question of a new installation. Android kills an app whose permission is taken back, so a test cannot
 * bring this state back: it has to run before anything grants the permission, hence the name of this class,
 * the first of the campaign. run.ps1 uninstalls the app beforehand.
 */
@RunWith(AndroidJUnit4::class)
class AaFirstRunTest : UiTest(coldStart = true) {
    @Test fun firstSignIn_asksForTheNotificationPermissionOnce() {
        assumeFalse(
            "notifications already allowed: not a new installation",
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
        app.graph.prefs.putBoolean("notifications_asked", false)
        openApp()
        attemptSignIn(Seed.reader.login, Seed.reader.password)
        var allow: UiObject2? = null
        waitFor("the system asks", LONG_TIMEOUT) {
            allow = device.findObject(By.text(Pattern.compile("Allow")))
            allow != null
        }
        allow!!.click()
        expectMainScreen()
        waitFor("granted") { app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }
        assertTrue(app.graph.prefs.boolean("notifications_asked", false))
    }
}