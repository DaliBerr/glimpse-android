package com.albumclean.app

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.provider.MediaStore
import com.albumclean.app.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowMediaStore
import org.robolectric.shadows.StorageVolumeBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], shadows = [PendingDeletionTest.LegacyVolumeShadow::class])
class PendingDeletionTest {
    private lateinit var context: Application
    private lateinit var provider: TestMediaProvider
    private lateinit var repository: PhotoRepository
    private val fullPermission get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("photo-clean.db")
        shadowOf(context).grantPermissions(fullPermission)
        val storage = shadowOf(context.getSystemService(StorageManager::class.java))
        storage.resetStorageVolumeList()
        storage.addStorageVolume(StorageVolumeBuilder("primary", context.cacheDir, "Test storage",
            Process.myUserHandle(), Environment.MEDIA_MOUNTED).setIsPrimary(true).setIsEmulated(true).build())
        assertTrue(MediaStore.VOLUME_EXTERNAL_PRIMARY in MediaStore.getExternalVolumeNames(context))
        provider = TestMediaProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        repository = PhotoRepository(context)
    }

    @Test fun externalDeletionReconcilesPersistedPendingWithoutChangingOtherChoices() = runBlocking {
        val gone = photo(1)
        val live = photo(2)
        val kept = photo(3)
        provider.add(live)
        provider.add(kept)
        repository.decide(gone, Choice.DELETE)
        repository.decide(live, Choice.DELETE)
        repository.decide(kept, Choice.KEEP)

        val decisions = repository.reconcilePendingDeletes(listOf(live, kept), true).associateBy { it.photo.key }
        assertEquals(Choice.DELETED, decisions.getValue(gone.key).choice)
        assertEquals(Choice.DELETE, decisions.getValue(live.key).choice)
        assertEquals(Choice.KEEP, decisions.getValue(kept.key).choice)
        assertEquals(Choice.DELETED, PhotoRepository(context).decisions().first { it.photo.key == gone.key }.choice)
        assertEquals(0, provider.deleteCalls)
    }

    @Test fun trashIsRemovedFromOurQueueWithoutDeletingTheSystemTrash() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        val trashed = photo(1)
        provider.add(trashed, trashed = true)
        repository.decide(trashed, Choice.DELETE)

        val decisions = repository.reconcilePendingDeletes(emptyList(), true)
        assertEquals(Choice.DELETED, decisions.single().choice)
        assertEquals(MediaStore.MATCH_INCLUDE, provider.lastArgs!!.getInt(MediaStore.QUERY_ARG_MATCH_TRASHED))
        assertTrue(provider.rows.containsKey(trashed.uri))
        assertEquals(0, provider.deleteCalls)
    }

    @Test fun missingPhotoWithPartialAccessKeepsItsPendingRecord() = runBlocking {
        val hidden = photo(1)
        repository.decide(hidden, Choice.DELETE)
        shadowOf(context).denyPermissions(fullPermission)

        val plan = repository.planDeletion(listOf(hidden), false)
        assertTrue(plan.ready.isEmpty())
        assertTrue(plan.removed.isEmpty())
        assertEquals(listOf(hidden.key), plan.unavailable)
        assertEquals(Choice.DELETE, repository.reconcilePendingDeletes(emptyList(), false).single().choice)
        // Even a stale FULL snapshot cannot override a runtime permission revocation.
        assertEquals(PhotoAvailability.UNAVAILABLE, repository.availability(hidden, true))
    }

    @Test fun unmountedStorageDoesNotProveDeletion() = runBlocking {
        val photo = photo(1)
        repository.decide(photo, Choice.DELETE)
        shadowOf(context.getSystemService(StorageManager::class.java)).resetStorageVolumeList()
        assertEquals(PhotoAvailability.UNAVAILABLE, repository.availability(photo, true))
        assertEquals(Choice.DELETE, repository.reconcilePendingDeletes(emptyList(), true).single().choice)
    }

    @Test fun nullCursorAndProviderFailuresKeepPendingRecords() = runBlocking {
        val photo = photo(1)
        repository.decide(photo, Choice.DELETE)
        for (failure in listOf("null", "security", "provider")) {
            provider.failure = failure
            assertEquals(PhotoAvailability.UNAVAILABLE, repository.availability(photo, true))
            assertEquals(Choice.DELETE, repository.reconcilePendingDeletes(emptyList(), true).single().choice)
        }
    }

    @Test fun pendingWritesAndChangedPhotoIdentityAreNotSentForDeletion() = runBlocking {
        val writing = photo(1)
        val edited = photo(2)
        provider.add(writing, pending = true)
        provider.add(edited.copy(bytes = edited.bytes + 1))
        val plan = repository.planDeletion(listOf(writing, edited), true)
        assertTrue(plan.ready.isEmpty())
        assertTrue(plan.removed.isEmpty())
        assertEquals(listOf(writing.key, edited.key), plan.unavailable)
    }

    @Test fun mixedDeletionPlanKeepsValidPhotosAndSeparatesMissingFromInaccessible() = runBlocking {
        val gone = photo(1)
        val live = photo(2)
        val hidden = photo(3).copy(uri = "content://media/disconnected/images/media/3")
        provider.add(live)
        val plan = repository.planDeletion(listOf(gone, live, hidden), true)
        assertEquals(listOf(live), plan.ready)
        assertEquals(listOf(gone.key), plan.removed)
        assertEquals(listOf(hidden.key), plan.unavailable)
    }

    @Test fun permissionOrVolumeLostDuringQueryDoesNotDiscardPendingRecord() = runBlocking {
        val photo = photo(1)
        repository.decide(photo, Choice.DELETE)
        provider.onQuery = { shadowOf(context).denyPermissions(fullPermission) }
        assertEquals(PhotoAvailability.UNAVAILABLE, repository.availability(photo, true))
        shadowOf(context).grantPermissions(fullPermission)
        provider.onQuery = { shadowOf(context.getSystemService(StorageManager::class.java)).resetStorageVolumeList() }
        assertEquals(PhotoAvailability.UNAVAILABLE, repository.availability(photo, true))
        assertEquals(Choice.DELETE, repository.decisions().single().choice)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun refreshAndPreDeleteCheckRemoveAStaleItemFromTheViewModel() = runBlocking {
        val gone = photo(1)
        val live = photo(2)
        provider.add(gone)
        provider.add(live)
        repository.decide(gone, Choice.DELETE)
        repository.decide(live, Choice.DELETE)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val model = PhotoViewModel(context)
        try {
            model.refresh().join()
            assertEquals(2, model.state.value.pending.size)
            provider.rows.remove(gone.uri)

            val plan = model.prepareDeletion(listOf(gone.key, live.key))
            assertEquals(listOf(gone.key), plan.removed)
            assertEquals(listOf(live), plan.ready)
            assertEquals(listOf(live), model.state.value.pending)

            provider.rows.remove(live.uri)
            model.refresh().join()
            assertTrue(model.state.value.pending.isEmpty())
            assertTrue(model.state.value.photos.isEmpty())
        } finally {
            model.updater.close()
            Dispatchers.resetMain()
        }
    }

    private fun photo(id: Long): Photo {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id).toString()
        return Photo("$uri:100:1000", uri, "sample$id.jpg", 1000, 200, 100, 1, 1, "Test", "image/jpeg")
    }

    // Android 10 asks the storage binder for hidden VolumeInfo objects, which Robolectric
    // does not populate. Use its configured mounted volumes for this platform lookup only.
    @Implements(value = MediaStore::class, minSdk = 29, maxSdk = 29)
    class LegacyVolumeShadow : ShadowMediaStore() {
        companion object {
            @JvmStatic @Implementation
            fun getExternalVolumeNames(context: Context): Set<String> =
                context.getSystemService(StorageManager::class.java).storageVolumes
                    .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
                    .mapNotNull { if (it.isPrimary) MediaStore.VOLUME_EXTERNAL_PRIMARY else it.uuid?.lowercase(java.util.Locale.ROOT) }
                    .toSet()
        }
    }

    class TestMediaProvider : ContentProvider() {
        val rows = linkedMapOf<String, Map<String, Any>>()
        var failure: String? = null
        var lastArgs: Bundle? = null
        var onQuery: (() -> Unit)? = null
        var deleteCalls = 0

        fun add(photo: Photo, trashed: Boolean = false, pending: Boolean = false) {
            rows[photo.uri] = mapOf("_id" to ContentUris.parseId(Uri.parse(photo.uri)), "_display_name" to photo.name,
                "_size" to photo.bytes, "width" to photo.width, "height" to photo.height, "datetaken" to photo.takenAt,
                "date_added" to photo.addedAt / 1000, "date_modified" to 100L, "bucket_display_name" to photo.album,
                "mime_type" to photo.mimeType, "volume_name" to MediaStore.VOLUME_EXTERNAL_PRIMARY, "orientation" to 0,
                "is_trashed" to if (trashed) 1 else 0, "is_pending" to if (pending) 1 else 0)
        }

        override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor? {
            lastArgs = queryArgs
            return query(uri, projection, null, null, null)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            onQuery?.invoke()
            when (failure) {
                "null" -> return null
                "security" -> throw SecurityException("Test permission loss")
                "provider" -> throw IllegalStateException("Test provider failure")
            }
            val columns = requireNotNull(projection)
            val cursor = MatrixCursor(columns)
            val matching = if (uri.lastPathSegment?.toLongOrNull() != null) listOfNotNull(rows[uri.toString()])
                else rows.values.filter { it["is_trashed"] == 0 && it["is_pending"] == 0 }
            matching.forEach { row -> cursor.addRow(columns.map { row[it] }) }
            return cursor
        }
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/jpeg"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Not used")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Not used")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleteCalls++
            return if (rows.remove(uri.toString()) != null) 1 else 0
        }
    }
}
