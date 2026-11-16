package com.albumclean.app

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.albumclean.app.data.Photo
import com.albumclean.app.ui.CleanApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val model: PhotoViewModel by viewModels()
    private var remainingDeletes = arrayListOf<String>()
    private var activeBatch = arrayListOf<String>()
    private var requestInFlight = false
    private var skippedDeletes = false
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.refresh()
        if (model.access() == PhotoAccess.NONE) model.notify("需要照片权限才能开始整理，可在设置中开启。")
    }
    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        requestInFlight = false
        if (result.resultCode != Activity.RESULT_OK) {
            finishDeletion("已取消，未删除的照片仍在清单中。")
        } else {
            lifecycleScope.launch {
                try {
                    if (Build.VERSION.SDK_INT >= 30) {
                        val batch = activeBatch.toList()
                        model.markDeleted(batch)
                        remainingDeletes.removeAll(batch.toSet())
                        activeBatch.clear()
                        deleteNext()
                    } else {
                        // Android 10 grants URI-specific consent; the actual delete must be retried.
                        deleteNext(approvedLegacyKey = activeBatch.firstOrNull())
                    }
                } catch (error: Exception) { finishDeletion("删除进度保存失败，请重新打开清单检查。") }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        remainingDeletes = savedInstanceState?.getStringArrayList("deleteQueue") ?: arrayListOf()
        activeBatch = savedInstanceState?.getStringArrayList("deleteBatch") ?: arrayListOf()
        requestInFlight = savedInstanceState?.getBoolean("deleteInFlight") ?: false
        skippedDeletes = savedInstanceState?.getBoolean("deleteSkipped") ?: false
        model.setDeleting(requestInFlight)
        enableEdgeToEdge()
        setContent {
            CleanApp(model, onPermission = ::requestPhotos, onSettings = ::openSettings,
                onShare = ::share, onDelete = ::startDeletion)
        }
        if (!requestInFlight) { remainingDeletes.clear(); activeBatch.clear() }
    }

    override fun onResume() { super.onResume(); model.refresh() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("deleteQueue", remainingDeletes)
        outState.putStringArrayList("deleteBatch", activeBatch)
        outState.putBoolean("deleteInFlight", requestInFlight)
        outState.putBoolean("deleteSkipped", skippedDeletes)
        super.onSaveInstanceState(outState)
    }

    private fun requestPhotos() {
        permissionLauncher.launch(when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        })
    }
    private fun openSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
    private fun share(photo: Photo) {
        try {
            val uri = Uri.parse(photo.uri)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = photo.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, photo.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "分享照片"))
        } catch (error: Exception) { model.notify("暂时无法分享，请检查照片是否仍可读取。") }
    }

    private fun startDeletion(photos: List<Photo>) {
        if (model.state.value.busy || remainingDeletes.isNotEmpty()) return
        remainingDeletes = ArrayList(photos.map { it.key }.distinct())
        if (remainingDeletes.isEmpty()) { model.notify("这些照片暂时无法访问，请先重新授权或移出清单。"); return }
        skippedDeletes = false
        model.setDeleting(true)
        lifecycleScope.launch { deleteNext() }
    }

    private suspend fun deleteNext(approvedLegacyKey: String? = null) {
        while (remainingDeletes.isNotEmpty()) {
            try {
                // A gallery can remove a photo after our foreground refresh or between batches.
                val plan = model.prepareDeletion(remainingDeletes.take(200))
                val ready = plan.ready.mapTo(HashSet()) { it.key }
                val checked = remainingDeletes.take(200).toSet()
                remainingDeletes.removeAll { it in checked && it !in ready }
                skippedDeletes = skippedDeletes || plan.unavailable.isNotEmpty()
                val photos = plan.ready
                if (photos.isEmpty()) continue
                if (Build.VERSION.SDK_INT >= 30) {
                    // A conservative chunk size keeps requests small across Android releases.
                    val batch = photos.take(200)
                    activeBatch = ArrayList(batch.map { it.key })
                    val request = MediaStore.createDeleteRequest(contentResolver, batch.map { Uri.parse(it.uri) })
                    requestInFlight = true
                    deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                    return
                } else {
                    val photo = photos.first()
                    activeBatch = arrayListOf(photo.key)
                    try {
                        withContext(Dispatchers.IO) { contentResolver.delete(Uri.parse(photo.uri), null, null) }
                        model.markDeleted(listOf(photo.key))
                        remainingDeletes.remove(photo.key)
                    } catch (consent: RecoverableSecurityException) {
                        if (approvedLegacyKey == photo.key) throw consent
                        requestInFlight = true
                        deleteLauncher.launch(IntentSenderRequest.Builder(consent.userAction.actionIntent.intentSender).build())
                        return
                    }
                }
            } catch (error: Exception) {
                finishDeletion("部分照片未能删除，已保留在清单中。请检查照片权限后重试。")
                return
            }
        }
        finishDeletion(if (skippedDeletes) "已处理可访问的照片，其余照片暂时无法访问，仍保留在清单中。" else "删除完成，给相册腾出了一点空间。")
    }

    private fun finishDeletion(message: String) {
        remainingDeletes.clear(); activeBatch.clear(); requestInFlight = false; skippedDeletes = false
        model.setDeleting(false)
        model.notify(message)
        model.refresh()
    }
}
