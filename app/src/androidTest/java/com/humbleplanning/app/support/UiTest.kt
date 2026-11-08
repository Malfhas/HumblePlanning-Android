package com.humbleplanning.app.support

import android.Manifest
import android.app.LocaleManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.util.Log
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import androidx.work.WorkManager
import com.humbleplanning.app.AppGraph
import com.humbleplanning.app.HumbleApplication
import com.humbleplanning.app.MainActivity
import com.humbleplanning.app.PendingActions
import com.humbleplanning.app.R
import com.humbleplanning.app.ui.mediumLabel
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.rules.Timeout
import org.junit.runner.Description

/**
 * Base of the UI tests: each one starts from a signed-out app with nothing on the phone, in a known language,
 * then signs in through the real sign-in screen against the local test server.
 *
 * The app stays in the process between tests: its data is wiped and its object graph rebuilt, which is what a new
 * installation would start from, without the cost of a new process per test.
 *
 * With `coldStart`, no activity is opened by the rule: the test opens and closes the app itself ([openApp], [closeApp]).
 */
abstract class UiTest(private val language: String = "en", private val coldStart: Boolean = false) {
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val app: HumbleApplication get() = instrumentation.targetContext.applicationContext as HumbleApplication
    protected val device: UiDevice = UiDevice.getInstance(instrumentation)
    protected val compose: ComposeTestRule = if (coldStart) createEmptyComposeRule() else createAndroidComposeRule<MainActivity>()

    private val cleanups = ArrayDeque<() -> Unit>()

    /** Runs after the test, passed or failed, most recent first: settings of the instance or of the phone put back. */
    protected fun afterTest(block: () -> Unit) = cleanups.addFirst(block)

    private val reset = object : ExternalResource() {
        override fun before() = resetApp()
        override fun after() {
            cleanups.forEach { runCatching(it).onFailure { e -> Log.e(TAG, "cleanup", e) } }
            cleanups.clear()
            openedApp?.close()
            openedApp = null
            if (coldStart) finishActivities()
        }
    }

    private val capture = object : TestWatcher() {
        override fun failed(e: Throwable, description: Description) {
            val name = "${description.testClass.simpleName}.${description.methodName}"
            runCatching {
                shell("mkdir -p /data/local/tmp/hp-ui")
                shell("screencap -p /data/local/tmp/hp-ui/$name.png")
            }
            Log.e(TAG, "FAILED $name: ${e.message?.take(400)}")
            runCatching {
                val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes().size
                repeat(roots) { i ->
                    compose.onAllNodes(isRoot())[i].printToString().chunked(3500).forEach { Log.e(TAG, it) }
                }
            }
        }
    }

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(Timeout.seconds(MAX_SECONDS)).around(reset).around(compose).around(capture)

    // ---- Clean state ----

    private fun resetApp() {
        val app = app
        PendingActions.qrLogin.value = null
        PendingActions.openTaskId.value = null
        // What the previous test left running
        val old = app.graph
        old.repository.realtime.disconnect()
        runCatching {
            val field = old.repository.javaClass.getDeclaredField("logoutJob").apply { isAccessible = true }
            (field.get(old.repository) as? Job)?.let { job -> runBlocking { withTimeoutOrNull(5_000) { job.join() } } }
        }
        WorkManager.getInstance(app).cancelAllWork()
        app.getSystemService(NotificationManager::class.java).cancelAll()
        old.prefs.raw.edit().clear().commit()
        File(app.filesDir, "offline").deleteRecursively()
        File(app.cacheDir, "attachments").deleteRecursively()
        HumbleApplication::class.java.getDeclaredField("graph").apply { isAccessible = true }.set(app, AppGraph(app))
        val graph = app.graph
        graph.repository.pushTokenProvider = null
        graph.repository.setLanguage(language)
        // The tests look for task titles: cards show them instead of the project
        graph.repository.setCardTitle("title")
        // Without this the first sign-in opens the system dialog of the notification permission
        graph.prefs.putBoolean(KEY_NOTIFICATIONS_ASKED, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.getSystemService(LocaleManager::class.java).applicationLocales =
                if (language.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language)
        }
    }

    // ---- App opened by the test ----

    private var openedApp: ActivityScenario<MainActivity>? = null

    /** Cold start only: a new activity, new view models, on the data on the phone. */
    protected fun openApp() {
        check(coldStart) { "coldStart tests only" }
        openedApp = ActivityScenario.launch(MainActivity::class.java)
    }

    protected fun closeApp() {
        check(coldStart) { "coldStart tests only" }
        openedApp?.close()
        openedApp = null
        finishActivities()
    }

    /** What the system opened by itself (a link): a scenario does not follow it, the app changes the intent it gets. */
    private fun finishActivities() {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        instrumentation.runOnMainSync {
            listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED).flatMap { monitor.getActivitiesInStage(it) }.forEach { it.finish() }
        }
        val end = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < end) {
            var open = 0
            instrumentation.runOnMainSync { open = listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED).sumOf { monitor.getActivitiesInStage(it).size } }
            if (open == 0) break
            SystemClock.sleep(50)
        }
    }

    // ---- Strings, in the language of the test ----

    private val localized: Context by lazy {
        val config = Configuration(instrumentation.targetContext.resources.configuration)
        config.setLocale(if (language.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(language))
        instrumentation.targetContext.createConfigurationContext(config)
    }
    protected val appLocale: Locale get() = localized.resources.configuration.locales[0]

    protected fun s(@StringRes id: Int, vararg args: Any): String = localized.getString(id, *args)
    protected fun plural(@PluralsRes id: Int, count: Int): String = localized.resources.getQuantityString(id, count, count)

    /** A string in another language than the one of the test. */
    protected fun sIn(tag: String, @StringRes id: Int): String {
        val config = Configuration(instrumentation.targetContext.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return instrumentation.targetContext.createConfigurationContext(config).getString(id)
    }

    // ---- Finding things ----

    protected fun label(text: String, substring: Boolean = false): SemanticsMatcher = hasText(text, substring = substring)
    protected fun field(label: String): SemanticsMatcher = hasSetTextAction() and hasText(label, substring = true)
    protected fun clickable(text: String, substring: Boolean = false): SemanticsMatcher = hasClickAction() and hasText(text, substring = substring)
    protected fun icon(description: String): SemanticsMatcher = hasClickAction() and hasContentDescription(description)
    protected fun desc(description: String, substring: Boolean = false): SemanticsMatcher = hasContentDescription(description, substring = substring)

    protected fun count(matcher: SemanticsMatcher): Int = runCatching { compose.onAllNodes(matcher).fetchSemanticsNodes().size }.getOrDefault(0)

    protected fun exists(matcher: SemanticsMatcher): Boolean = count(matcher) > 0

    protected fun displayed(matcher: SemanticsMatcher): Boolean = runCatching {
        val nodes = compose.onAllNodes(matcher)
        nodes.fetchSemanticsNodes().indices.any { nodes[it].isDisplayed() }
    }.getOrDefault(false)

    /** First matching node on screen, or else the first one there is. */
    protected fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val nodes = compose.onAllNodes(matcher)
        val size = nodes.fetchSemanticsNodes(atLeastOneRootRequired = false, errorMessageOnFail = "looking for $matcher").size
        check(size > 0) { "No node for: ${matcher.description}" }
        return (0 until size).firstOrNull { runCatching { nodes[it].isDisplayed() }.getOrDefault(false) }?.let(nodes::get) ?: nodes[0]
    }

    protected fun texts(matcher: SemanticsMatcher = SemanticsMatcher("has text") { it.config.contains(SemanticsProperties.Text) }): List<String> =
        runCatching {
            compose.onAllNodes(matcher).fetchSemanticsNodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
        }.getOrDefault(emptyList())

    // ---- Waiting: on the state of the screen or of the server, never on a fixed time ----

    protected fun waitFor(what: String, timeout: Long = TIMEOUT, condition: () -> Boolean) {
        compose.waitUntil(what, timeout) { runCatching(condition).getOrDefault(false) }
    }

    /** Same, for a condition that calls the server: not on every frame. */
    protected fun waitForServer(what: String, timeout: Long = TIMEOUT, condition: () -> Boolean) {
        var last = 0L
        waitFor(what, timeout) {
            val now = SystemClock.uptimeMillis()
            if (now - last < 300) {
                false
            } else {
                last = now
                condition()
            }
        }
    }

    protected fun waitExists(matcher: SemanticsMatcher, timeout: Long = TIMEOUT) = waitFor("exists: ${matcher.description}", timeout) { exists(matcher) }
    protected fun waitDisplayed(matcher: SemanticsMatcher, timeout: Long = TIMEOUT) = waitFor("displayed: ${matcher.description}", timeout) { displayed(matcher) }
    protected fun waitGone(matcher: SemanticsMatcher, timeout: Long = TIMEOUT) = waitFor("gone: ${matcher.description}", timeout) { !exists(matcher) }
    protected fun waitText(text: String, substring: Boolean = false, timeout: Long = TIMEOUT) = waitExists(label(text, substring), timeout)

    /** Nothing changes on screen for this long: the only time-based check, for what must not happen. */
    protected fun staysAbsent(matcher: SemanticsMatcher, millis: Long = 2_500) {
        val end = SystemClock.uptimeMillis() + millis
        compose.waitUntil("absent for $millis ms: ${matcher.description}", millis + 5_000) {
            check(!exists(matcher)) { "Should not appear: ${matcher.description}" }
            SystemClock.uptimeMillis() >= end
        }
    }

    // ---- Acting ----

    /**
     * Brings the node on screen through the vertical list on screen. Never through a pager: it would go through all its
     * pages looking for it, and a card of the day next to this one is not the one wanted.
     */
    protected fun reveal(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        if (!displayed(matcher)) {
            val verticalList = hasScrollToNodeAction() and SemanticsMatcher("scrolls vertically") { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
            val lists = compose.onAllNodes(verticalList)
            for (i in 0 until lists.fetchSemanticsNodes().size) {
                if (displayed(matcher)) break
                runCatching { if (lists[i].isDisplayed()) lists[i].performScrollToNode(matcher) }
            }
        }
        if (!displayed(matcher) && exists(matcher)) {
            val inPager = hasAnyAncestor(SemanticsMatcher("scrolls horizontally") { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) })
            val inScrollingColumn = matcher and !inPager
            if (exists(inScrollingColumn)) runCatching { node(inScrollingColumn).performScrollTo() }
        }
        return node(matcher)
    }

    protected fun tap(matcher: SemanticsMatcher, timeout: Long = TIMEOUT) {
        var last = 0L
        waitFor("reachable: ${matcher.description}", timeout) {
            val now = SystemClock.uptimeMillis()
            exists(matcher) || (now - last >= 500 && run {
                last = now
                runCatching { reveal(matcher) }.isSuccess
            })
        }
        reveal(matcher).performClick()
        compose.waitForIdle()
    }

    /** A line of a choice list, not what shows the same name behind it. */
    protected fun pickerItem(name: String): SemanticsMatcher = clickable(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)

    /** The search of a choice list: long lists only have it, and may not show the line otherwise. */
    protected fun searchField(): SemanticsMatcher = hasSetTextAction() and hasContentDescription(s(R.string.search))

    /**
     * Opens the choice list of a required field of the task form (its sheet is titled like the field). A tap made
     * while the keyboard of the previous field closes can be lost, the form moving under the finger: the field is
     * tapped once more when no list shows up.
     */
    protected fun openChoice(@StringRes field: Int) {
        val title = s(field)
        val opener = clickable("$title *", substring = true)
        tap(opener)
        val opened = runCatching { waitFor("choice list $title", OPEN_RETRY) { exists(label(title)) } }.isSuccess
        if (!opened) tap(opener)
        waitExists(label(title))
    }

    /** Chooses a line in the choice list on screen, through its search when it has one. */
    protected fun pick(name: String) {
        waitFor("choice list with $name", LONG_TIMEOUT) { exists(searchField()) || exists(pickerItem(name)) }
        if (exists(searchField())) node(searchField()).performTextReplacement(name)
        tap(pickerItem(name))
    }

    protected fun type(label: String, value: String) {
        waitExists(field(label))
        node(field(label)).performTextReplacement(value)
    }

    /** The gesture of the whole list, wherever the finger is. */
    protected fun pullToRefresh() {
        compose.onRoot().performTouchInput { swipeDown(startY = height * 0.45f, endY = height * 0.85f, durationMillis = 400) }
        compose.waitForIdle()
    }

    protected fun pressBack() {
        device.pressBack()
        compose.waitForIdle()
    }

    /** The first back may only close the keyboard. */
    protected fun pressBackUntilGone(matcher: SemanticsMatcher) {
        repeat(3) {
            if (!exists(matcher)) return
            device.pressBack()
            compose.waitForIdle()
            val end = SystemClock.uptimeMillis() + 1_500
            compose.waitUntil(5_000) { !exists(matcher) || SystemClock.uptimeMillis() >= end }
        }
        waitGone(matcher)
    }

    /** The first back may only close the keyboard. */
    protected fun pressBackUntil(matcher: SemanticsMatcher) {
        repeat(3) {
            device.pressBack()
            compose.waitForIdle()
            val end = SystemClock.uptimeMillis() + 1_500
            compose.waitUntil(5_000) { exists(matcher) || SystemClock.uptimeMillis() >= end }
            if (exists(matcher)) return
        }
        waitExists(matcher)
    }

    protected fun shell(command: String): String = device.executeShellCommand(command)

    // ---- Sign-in ----

    protected fun fillLogin(login: String, password: String, server: String = Seed.serverUrl) {
        type(s(R.string.login_server), server)
        type(s(R.string.login_identifier), login)
        type(s(R.string.login_password), password)
    }

    protected fun submitLogin() = tap(clickable(s(R.string.sign_in)))

    protected fun attemptSignIn(login: String, password: String, server: String = Seed.serverUrl) {
        fillLogin(login, password, server)
        submitLogin()
    }

    /** Through the sign-in screen, up to the main screen. */
    protected fun signIn(account: Account, password: String = account.password) {
        attemptSignIn(account.login, password)
        expectMainScreen()
    }

    protected fun expectMainScreen() {
        waitExists(tab(R.string.tab_planning), LONG_TIMEOUT)
        waitFor("main screen loaded") { !exists(label(s(R.string.loading))) }
    }

    protected fun expectLoginScreen() {
        waitExists(field(s(R.string.login_server)), LONG_TIMEOUT)
    }

    protected fun tab(@StringRes name: Int): SemanticsMatcher = clickable(s(name))

    protected fun goTo(@StringRes name: Int) {
        tap(tab(name))
    }

    // ---- Notifications ----

    protected fun grantNotifications() {
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        waitFor("permission granted") { app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }
    }

    protected fun waitNotification() {
        val manager = app.getSystemService(NotificationManager::class.java)
        waitFor("a notification from the app", LONG_TIMEOUT) { manager.activeNotifications.isNotEmpty() }
    }

    // ---- Planning and tasks ----

    protected fun card(title: String): SemanticsMatcher = clickable(title)

    /** Waits for it wherever it is in the list on screen, and brings it on screen. */
    protected fun waitRevealed(matcher: SemanticsMatcher, timeout: Long = TIMEOUT) {
        var last = 0L
        waitFor("on screen: ${matcher.description}", timeout) {
            val now = SystemClock.uptimeMillis()
            when {
                displayed(matcher) -> true
                now - last < 500 -> false
                else -> {
                    last = now
                    runCatching { reveal(matcher) }
                    displayed(matcher)
                }
            }
        }
    }

    protected fun waitForCard(title: String, timeout: Long = TIMEOUT) = waitRevealed(card(title), timeout)

    protected fun openTask(title: String) {
        waitForCard(title)
        tap(card(title))
        waitExists(icon(s(R.string.back)))
    }

    /** Planning filter: the tasks of everyone the account can see. */
    protected fun showEveryone() {
        tap(icon(s(R.string.filters)))
        tap(clickable(s(R.string.filter_everyone)))
        tap(clickable(s(R.string.apply)))
    }

    protected fun signOutFromSettings() {
        goTo(R.string.tab_settings)
        reveal(clickable(s(R.string.sign_out))).performClick()
        waitExists(label(s(R.string.sign_out_confirm)))
        tap(clickable(s(R.string.sign_out)) and isInDialog())
        expectLoginScreen()
    }

    /** A button of the dialog on top, not the one behind it with the same text. */
    protected fun isInDialog(): SemanticsMatcher = hasAnyAncestor(isDialog())

    /** Delivers a link the way another app or the camera would. */
    protected fun openLink(link: String) {
        instrumentation.targetContext.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(link))
                .setPackage(instrumentation.targetContext.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    // ---- Dates ----

    /** Date picker of the header. */
    protected fun pickDate(date: LocalDate) {
        tap(icon(s(R.string.choose_date)))
        chooseInDatePicker(date)
    }

    /** In its text mode: any date in one step, whatever the month shown. English format: the tests run in English. */
    protected fun chooseInDatePicker(date: LocalDate) {
        val toInput = hasClickAction() and hasContentDescription("input", substring = true, ignoreCase = true)
        waitExists(toInput)
        tap(toInput)
        val input = hasSetTextAction() and isInDialog()
        waitExists(input)
        node(input).performTextReplacement(DateTimeFormatter.ofPattern("MMddyyyy").format(date))
        compose.waitForIdle()
        tap(clickable(s(R.string.ok)) and isInDialog())
    }

    protected fun mediumDate(date: LocalDate): String = date.mediumLabel(appLocale)

    // ---- Network of the phone ----

    protected fun setNetwork(enabled: Boolean) {
        val word = if (enabled) "enable" else "disable"
        shell("svc wifi $word")
        shell("svc data $word")
        // Connections of the old network hang instead of failing
        Rest.resetConnections()
        val manager = instrumentation.targetContext.getSystemService(ConnectivityManager::class.java)
        val end = SystemClock.uptimeMillis() + LONG_TIMEOUT
        while ((manager.activeNetwork != null) != enabled && SystemClock.uptimeMillis() < end) SystemClock.sleep(200)
        check((manager.activeNetwork != null) == enabled) { "network still ${if (enabled) "off" else "on"}" }
        if (enabled) {
            while (runCatching { Rest.mobileInfo() }.isFailure && SystemClock.uptimeMillis() < end) SystemClock.sleep(300)
        }
    }

    companion object {
        const val TAG = "HPUI"
        const val TIMEOUT = 15_000L
        const val LONG_TIMEOUT = 30_000L
        /** A choice list that did not open by then is tapped again (see [openChoice]). */
        const val OPEN_RETRY = 5_000L
        /** A test stuck for this long fails instead of holding up the campaign. */
        private const val MAX_SECONDS = 300L
        private const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"
    }
}
