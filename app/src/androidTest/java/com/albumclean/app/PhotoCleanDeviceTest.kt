package com.albumclean.app

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.albumclean.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoCleanDeviceTest {
    companion object { private val samplePrefix = "PhotoClean_Sample_${System.currentTimeMillis()}_" }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun prepare() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission")).use { it.readBytes() }
        runBlocking { PhotoRepository(context).resetKept() }
        createSamples()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("photo_card").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun choiceUndoPendingRestoreAndThemeSurviveRecreation() {
        val before = currentDescription()
        compose.onNodeWithTag("keep").performClick()
        compose.waitUntil(5_000) { currentDescription() != before }
        compose.onNodeWithTag("undo").performClick()
        compose.waitUntil(5_000) { currentDescription() == before }
        compose.onNodeWithTag("delete").performClick()
        compose.waitUntil(5_000) { currentDescription() != before }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("pending_open").performClick()
        compose.onNodeWithText("尚未删除原图", substring = true).assertExists()
        compose.onAllNodesWithText("恢复").onFirst().performClick()
        compose.onNodeWithTag("pending_back").performClick()
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("theme_DARK").performClick()
        assertEquals(ThemeMode.DARK, PhotoRepository(context).theme)
        compose.onNodeWithTag("theme_LIGHT").performClick()
        assertEquals(ThemeMode.LIGHT, PhotoRepository(context).theme)
        compose.activityRule.scenario.recreate()
        assertEquals(ThemeMode.LIGHT, PhotoRepository(context).theme)
    }

    @Test fun swipingAndDetailViewerKeepTheSamePhoto() {
        val before = currentDescription()
        compose.onNodeWithTag("photo_card").performTouchInput { swipeRight(durationMillis = 400) }
        compose.waitUntil(5_000) { currentDescription() != before }
        compose.onNodeWithTag("undo").performClick()
        compose.waitUntil(5_000) { currentDescription() == before }
        compose.onNodeWithTag("photo_card").performClick()
        compose.onNodeWithTag("zoom_view").assertExists()
        compose.onNodeWithTag("zoom_view").performTouchInput { doubleClick() }
        compose.waitUntil(3_000) { compose.onAllNodesWithText("3.0×").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("detail_share").assertExists()
        compose.onNodeWithTag("detail_metadata").assertExists()
        compose.onNodeWithTag("detail_close").performClick()
        assertEquals(before, currentDescription())
    }

    @Test fun repositoryStoresMetadataAndUndoDurably() = runBlocking {
        val repository = PhotoRepository(context)
        val photo = repository.details(repository.photos().first { it.name.startsWith(samplePrefix) })
        repository.restore(photo.key)
        repository.decide(photo, Choice.DELETE)
        val reloaded = PhotoRepository(context).decisions().first { it.photo.key == photo.key }
        assertEquals(Choice.DELETE, reloaded.choice)
        assertEquals(1200, reloaded.photo.width)
        assertEquals(1600, reloaded.photo.height)
        assertTrue(reloaded.photo.bytes > 0)
        assertTrue(reloaded.photo.takenAt > 0)
        repository.restore(photo.key)
        assertFalse(PhotoRepository(context).decisions().any { it.photo.key == photo.key })
    }

    private fun currentDescription(): String = compose.onNodeWithTag("photo_card").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].first()

    private fun createSamples() {
        val resolver = context.contentResolver
        val existing = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf("_id"), "_display_name LIKE ?", arrayOf("$samplePrefix%"), null)?.use { it.count } ?: 0
        if (existing >= 6) return
        repeat(6) { index ->
            val bitmap = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawColor(intArrayOf(0xFFD9E1B9.toInt(), 0xFFE6C9AE.toInt(), 0xFFB2CCDB.toInt(), 0xFFB4C9AB.toInt(), 0xFFD8C1CF.toInt(), 0xFFE1D9AD.toInt())[index])
            paint.color = Color.rgb(75, 98, 64)
            canvas.drawCircle(850f, 450f, 270f, paint)
            paint.color = Color.rgb(48, 66, 48)
            canvas.drawRoundRect(150f, 850f, 1050f, 1700f, 250f, 250f, paint)
            paint.color = Color.WHITE; paint.textSize = 65f
            canvas.drawText("A LITTLE MORE SPACE", 130f, 260f, paint)
            paint.textSize = 160f; canvas.drawText("0${index + 1}", 150f, 1180f, paint)
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$samplePrefix$index.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.WIDTH, 1200)
                put(MediaStore.Images.Media.HEIGHT, 1600)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoCleanTest")
                put(MediaStore.Images.Media.DATE_TAKEN, 1_728_000_000_000L + index * 86_400_000L)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            })!!
            resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
            resolver.openFileDescriptor(uri, "rw")!!.use { descriptor ->
                android.media.ExifInterface(descriptor.fileDescriptor).apply {
                    setAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL, "2024:10:04 14:32:00")
                    saveAttributes()
                }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            bitmap.recycle()
        }
    }
}
