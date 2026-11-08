package com.humbleplanning.app

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.humbleplanning.app.support.Rest
import com.humbleplanning.app.support.Seed
import com.humbleplanning.app.support.UiTest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Files go through the real system document picker: a file is put in Downloads on the emulator,
 * the test opens the picker of the app and chooses it there with UIAutomator.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentsTest : UiTest() {
    private fun putInDownloads(name: String, mime: String, bytes: ByteArray) {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "no entry in Downloads" }
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        afterTest { resolver.delete(uri, null, null) }
    }

    /** In the picker: the file is in Recent or in Downloads, depending on what the picker opens first. */
    private fun chooseInPicker(name: String) {
        device.wait(Until.hasObject(By.pkg(PICKER_PACKAGE).depth(0)), PICKER_TIMEOUT)
        var file: UiObject2? = device.wait(Until.findObject(By.text(name)), 4_000)
        if (file == null) {
            device.wait(Until.findObject(By.desc("Show roots")), 4_000)?.click()
            device.wait(Until.findObject(By.text("Downloads")), PICKER_TIMEOUT)?.click()
            file = device.wait(Until.findObject(By.text(name)), PICKER_TIMEOUT)
        }
        checkNotNull(file) { "$name not found in the document picker" }.click()
    }

    private fun fileCount(taskId: String) = Rest.task(taskId)!!.optJSONArray("files")?.length() ?: 0

    private fun addFile(name: String) {
        waitRevealed(clickable(s(R.string.file_add)))
        tap(clickable(s(R.string.file_add)))
        chooseInPicker(name)
    }

    @Test fun upload_open_delete() {
        val name = "ui-test-${System.currentTimeMillis()}.pdf"
        // A real PDF: the viewer closes by itself on anything else, and the back below would then close the task
        val bytes = """
            %PDF-1.4
            1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
            2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
            3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj
            trailer<</Root 1 0 R>>
            %%EOF
        """.trimIndent().toByteArray()
        putInDownloads(name, "application/pdf", bytes)
        val task = Rest.createTask(Rest.unique("files"), listOf(Seed.own.id))
        val id = task.getString("id")
        signIn(Seed.own)
        openTask(task.getString("title"))

        addFile(name)
        waitForServer("file on the server", LONG_TIMEOUT) { fileCount(id) == 1 }
        waitRevealed(clickable(name, substring = true), LONG_TIMEOUT)

        // Another app takes it, or the app says that none can: both are fine, on a phone without a viewer
        var leftTheApp = false
        var lastStage: Stage? = null
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { _, stage ->
            lastStage = stage
            if (stage == Stage.PAUSED) leftTheApp = true
        }
        instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
        afterTest { instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) } }
        tap(clickable(name, substring = true))
        val copy = File(File(app.cacheDir, "attachments"), name)
        waitFor("file downloaded", LONG_TIMEOUT) { copy.exists() && copy.length() == bytes.size.toLong() }
        assertTrue(copy.readBytes().contentEquals(bytes))
        waitFor("a viewer or a message", LONG_TIMEOUT) { leftTheApp || exists(label(s(R.string.err_file_no_viewer))) }
        if (leftTheApp) {
            // Back reaches the other app only once it has the screen: pressed sooner, it closes the task here
            waitFor("the other app on screen, or back here", LONG_TIMEOUT) { lastStage == Stage.STOPPED || lastStage == Stage.RESUMED }
            if (lastStage == Stage.STOPPED) device.pressBack()
            waitExists(icon(s(R.string.file_delete_label, name)), LONG_TIMEOUT)
        }

        tap(icon(s(R.string.file_delete_label, name)))
        waitText(s(R.string.file_delete_title))
        tap(clickable(s(R.string.delete)) and isInDialog())
        waitForServer("file removed") { fileCount(id) == 0 }
        waitText(s(R.string.attachments_none))
    }

    @Test fun forbiddenAndOversized_areRefusedBeforeUpload() {
        afterTest { Rest.setConfig("MAX_UPLOAD_SIZE_MB", "10") }
        Rest.setConfig("MAX_UPLOAD_SIZE_MB", "1")
        val forbidden = "ui-test-${System.currentTimeMillis()}.txt"
        val oversized = "ui-test-${System.currentTimeMillis()}.pdf"
        putInDownloads(forbidden, "text/plain", "not allowed".toByteArray())
        putInDownloads(oversized, "application/pdf", ByteArray(2 * 1024 * 1024))
        val task = Rest.createTask(Rest.unique("files refused"), listOf(Seed.own.id))
        signIn(Seed.own)
        openTask(task.getString("title"))

        addFile(forbidden)
        waitFor("type refused", LONG_TIMEOUT) { texts().any { it.startsWith(s(R.string.err_file_type, "").take(12)) } }
        addFile(oversized)
        waitText(s(R.string.err_file_size, 1), timeout = LONG_TIMEOUT)
        assertEquals("nothing sent", 0, fileCount(task.getString("id")))
    }

    @Test fun someoneElsesTask_offersNoFileAction() {
        val task = Rest.createTask(Rest.unique("files other"), listOf(Seed.own2.id))
        val file = Rest.uploadFile(task.getString("id"), "other.pdf", "%PDF-1.4".toByteArray())
        val refused = Rest.call("DELETE", "/api/tasks/${task.getString("id")}/files?fileId=${file.getString("id")}", Rest.sessionOf(Seed.own))
        assertEquals("the server refuses it: $refused", 403, refused.status)
        signIn(Seed.own)
        showEveryone()
        openTask(task.getString("title"))
        waitRevealed(clickable("other.pdf", substring = true))
        assertFalse("delete", exists(icon(s(R.string.file_delete_label, "other.pdf"))))
        assertFalse("add", exists(clickable(s(R.string.file_add))))
    }

    private companion object {
        const val PICKER_PACKAGE = "com.google.android.documentsui"
        const val PICKER_TIMEOUT = 10_000L
    }
}
