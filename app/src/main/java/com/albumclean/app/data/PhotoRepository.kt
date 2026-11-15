package com.albumclean.app.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.net.Uri
import android.media.ExifInterface
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotoRepository(private val context: Context) {
    private val store = DecisionStore(context)
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var theme: ThemeMode
        get() = runCatching { ThemeMode.valueOf(preferences.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)
        set(value) { preferences.edit().putString("theme", value.name).apply() }

    suspend fun photos(): List<Photo> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.VOLUME_NAME,
            MediaStore.Images.Media.ORIENTATION,
        )
        val selection = if (Build.VERSION.SDK_INT >= 30) "is_pending = 0 AND is_trashed = 0" else "is_pending = 0"
        context.contentResolver.query(collection, projection, selection, null, "date_added DESC")?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.long("_id")
                    val volume = cursor.string("volume_name").ifBlank { MediaStore.VOLUME_EXTERNAL_PRIMARY }
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volume), id).toString()
                    val size = cursor.long("_size")
                    val rotation = cursor.long("orientation").toInt()
                    val w = cursor.long("width").toInt()
                    val h = cursor.long("height").toInt()
                    add(Photo(
                        key = "$uri:${cursor.long("date_modified")}:$size",
                        uri = uri, name = cursor.string("_display_name"), bytes = size,
                        width = if (rotation == 90 || rotation == 270) h else w,
                        height = if (rotation == 90 || rotation == 270) w else h,
                        takenAt = cursor.long("datetaken"), addedAt = cursor.long("date_added") * 1000,
                        album = cursor.string("bucket_display_name").ifBlank { "未命名相册" },
                        mimeType = cursor.string("mime_type").ifBlank { "image/*" },
                    ))
                }
            }
        } ?: emptyList()
    }

    suspend fun decisions(): List<Decision> = withContext(Dispatchers.IO) { store.all() }
    /** Read EXIF only for the visible photo when the platform index lacks metadata. */
    suspend fun details(photo: Photo): Photo = withContext(Dispatchers.IO) {
        if (photo.takenAt > 0 && photo.width > 0 && photo.height > 0) return@withContext photo
        runCatching {
            context.contentResolver.openFileDescriptor(Uri.parse(photo.uri), "r")?.use { descriptor ->
                val exif = ExifInterface(descriptor.fileDescriptor)
                val taken = parseExifTime(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), exif.getAttribute("OffsetTimeOriginal"))
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                val swap = orientation in listOf(5, 6, 7, 8)
                val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                photo.copy(takenAt = photo.takenAt.takeIf { it > 0 } ?: taken,
                    width = photo.width.takeIf { it > 0 } ?: if (swap) h else w,
                    height = photo.height.takeIf { it > 0 } ?: if (swap) w else h)
            } ?: photo
        }.getOrDefault(photo)
    }
    suspend fun decide(photo: Photo, choice: Choice) = withContext(Dispatchers.IO) { store.put(photo, choice) }
    suspend fun restore(key: String) = withContext(Dispatchers.IO) { store.writableDatabase.delete("decisions", "photo_key = ? AND choice != ?", arrayOf(key, Choice.DELETED.name)); Unit }
    suspend fun deleted(keys: List<String>) = withContext(Dispatchers.IO) {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            keys.forEach { db.update("decisions", ContentValues().apply { put("choice", Choice.DELETED.name) }, "photo_key = ?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    suspend fun resetKept() = withContext(Dispatchers.IO) { store.writableDatabase.delete("decisions", "choice = ?", arrayOf(Choice.KEEP.name)); Unit }

    private fun Cursor.string(column: String) = getString(getColumnIndexOrThrow(column)).orEmpty()
    private fun Cursor.long(column: String) = getLong(getColumnIndexOrThrow(column))

    private class DecisionStore(context: Context) : SQLiteOpenHelper(context, "photo-clean.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE decisions (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                photo_key TEXT NOT NULL UNIQUE, uri TEXT NOT NULL, name TEXT NOT NULL,
                bytes INTEGER NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL,
                taken_at INTEGER NOT NULL, added_at INTEGER NOT NULL, album TEXT NOT NULL,
                mime_type TEXT NOT NULL, choice TEXT NOT NULL)""")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        fun put(photo: Photo, choice: Choice) {
            writableDatabase.insertOrThrow("decisions", null, ContentValues().apply {
                put("photo_key", photo.key); put("uri", photo.uri); put("name", photo.name)
                put("bytes", photo.bytes); put("width", photo.width); put("height", photo.height)
                put("taken_at", photo.takenAt); put("added_at", photo.addedAt); put("album", photo.album)
                put("mime_type", photo.mimeType); put("choice", choice.name)
            })
        }
        fun all(): List<Decision> = readableDatabase.query("decisions", null, null, null, null, null, "sequence ASC").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Decision(Photo(c.string("photo_key"), c.string("uri"), c.string("name"), c.long("bytes"),
                        c.long("width").toInt(), c.long("height").toInt(), c.long("taken_at"), c.long("added_at"),
                        c.string("album"), c.string("mime_type")), Choice.valueOf(c.string("choice")), c.long("sequence")))
                }
            }
        }
        private fun Cursor.string(column: String) = getString(getColumnIndexOrThrow(column)).orEmpty()
        private fun Cursor.long(column: String) = getLong(getColumnIndexOrThrow(column))
    }
}
