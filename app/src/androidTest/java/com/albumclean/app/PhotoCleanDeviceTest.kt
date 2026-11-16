package com.albumclean.app

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.albumclean.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoCleanDeviceTest {
    // Each test owns its samples; terminal DELETED decisions must not leak into another case.
    private val samplePrefix = "PhotoClean_Sample_${java.util.UUID.randomUUID()}_"
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

    @Test fun externalDeletionIsRemovedFromPendingAndStaysRemovedAfterReload() = runBlocking {
        val repository = PhotoRepository(context)
        val (gone, kept, pending) = repository.photos().filter { it.name.startsWith(samplePrefix) }.take(3)
        listOf(gone, kept, pending).forEach { repository.restore(it.key) }
        repository.decide(gone, Choice.DELETE)
        repository.decide(kept, Choice.KEEP)
        repository.decide(pending, Choice.DELETE)
        assertEquals(1, context.contentResolver.delete(Uri.parse(gone.uri), null, null))

        val decisions = repository.reconcilePendingDeletes(repository.photos(), hasFullAccess = true).associateBy { it.photo.key }
        assertEquals(Choice.DELETED, decisions.getValue(gone.key).choice)
        assertEquals(Choice.KEEP, decisions.getValue(kept.key).choice)
        assertEquals(Choice.DELETE, decisions.getValue(pending.key).choice)
        assertEquals(Choice.DELETED, PhotoRepository(context).decisions().first { it.photo.key == gone.key }.choice)
        repository.restore(kept.key)
        repository.restore(pending.key)
    }

    @Test fun systemTrashIsReconciledWithoutPermanentlyDeletingTheOriginal() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        val repository = PhotoRepository(context)
        val photo = repository.photos().first { it.name.startsWith(samplePrefix) }
        repository.restore(photo.key)
        repository.decide(photo, Choice.DELETE)
        val uri = Uri.parse(photo.uri)
        try {
            assertEquals(1, context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_TRASHED, 1)
            }, null, null))
            assertEquals(PhotoAvailability.REMOVED, repository.availability(photo, hasFullAccess = true))
            val decisions = repository.reconcilePendingDeletes(repository.photos(), hasFullAccess = true)
            assertEquals(Choice.DELETED, decisions.first { it.photo.key == photo.key }.choice)
            // A refresh only reconciles our SQLite record; the system trash still owns the file.
            val args = android.os.Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
            context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.IS_TRASHED), args, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
        } finally {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_TRASHED, 0) }, null, null)
        }
    }

    @Test fun partialAccessAndDisconnectedStorageDoNotDiscardPendingRecords() = runBlocking {
        val repository = PhotoRepository(context)
        val photo = repository.photos().first { it.name.startsWith(samplePrefix) }
        repository.restore(photo.key)
        repository.decide(photo, Choice.DELETE)
        context.contentResolver.delete(Uri.parse(photo.uri), null, null)
        val plan = repository.planDeletion(listOf(photo), hasFullAccess = false)
        assertTrue(plan.ready.isEmpty())
        assertTrue(plan.removed.isEmpty())
        assertEquals(listOf(photo.key), plan.unavailable)
        val decisions = repository.reconcilePendingDeletes(emptyList(), hasFullAccess = false)
        assertEquals(Choice.DELETE, decisions.first { it.photo.key == photo.key }.choice)

        val disconnected = photo.copy(uri = "content://media/absent_test_volume/images/media/1")
        assertEquals(PhotoAvailability.UNAVAILABLE, repository.availability(disconnected, hasFullAccess = true))
        repository.deleted(listOf(photo.key))
    }

    @Test fun deletionPreparationRechecksPhotosRemovedAfterForegroundRefresh() = runBlocking {
        val repository = PhotoRepository(context)
        val (gone, present) = repository.photos().filter { it.name.startsWith(samplePrefix) }.take(2)
        listOf(gone, present).forEach { repository.restore(it.key); repository.decide(it, Choice.DELETE) }
        lateinit var model: PhotoViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[PhotoViewModel::class.java] }
        model.refresh().join()
        assertTrue(model.state.value.pending.any { it.key == gone.key })
        context.contentResolver.delete(Uri.parse(gone.uri), null, null)

        val plan = model.prepareDeletion(listOf(gone.key, present.key))
        assertEquals(listOf(gone.key), plan.removed)
        assertEquals(listOf(present.key), plan.ready.map { it.key })
        assertFalse(model.state.value.pending.any { it.key == gone.key })
        assertTrue(model.state.value.pending.any { it.key == present.key })
        repository.restore(present.key)
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
