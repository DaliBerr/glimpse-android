package com.albumclean.app.updates

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

enum class UpdateStage { IDLE, CHECKING, CURRENT, UNPUBLISHED, AVAILABLE, DOWNLOADING, VERIFYING, READY, ERROR }
data class UpdateState(val stage: UpdateStage = UpdateStage.IDLE, val release: Release? = null, val percent: Int = 0, val issue: UpdateIssue? = null) {
    val busy get() = stage in listOf(UpdateStage.CHECKING, UpdateStage.DOWNLOADING, UpdateStage.VERIFYING)
}

class UpdateFileProvider : FileProvider()

class AppUpdater(
    private val context: Context,
    private val transport: UpdateTransport = GitHubTransport(),
    private val installed: InstalledApp = inspectInstalled(context),
    private val directory: File = File(context.cacheDir, "updates"),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()
    val supported get() = installed.id == RELEASE_PACKAGE
    private var job: Job? = null
    @Volatile private var generation = 0
    private val packages get() = File(directory, "packages")
    private val downloaded get() = File(packages, "update.apk")
    private val saved get() = File(directory, "ready.json")

    init {
        perform(UpdateStage.VERIFYING) {
            val cached = withContext(Dispatchers.IO) {
                packages.listFiles()?.filter { it.name.startsWith("download-") && it.extension == "part" }?.forEach { it.delete() }
                val candidate = runCatching {
                    require(saved.isFile && saved.length() in 1..16_384)
                    readRelease(JSONObject(saved.readText())).also { verify(downloaded, it) }
                }.getOrNull()
                if (candidate == null) { downloaded.delete(); saved.delete() }
                candidate
            }
            mutableState.value = if (cached == null) UpdateState() else UpdateState(UpdateStage.READY, cached, 100)
        }
    }

    fun check() {
        if (!supported) return
        perform(UpdateStage.CHECKING) {
            val latest = withContext(Dispatchers.IO) { discoverRelease(transport) }
            mutableState.value = when {
                latest == null -> UpdateState(UpdateStage.UNPUBLISHED)
                latest.newerThan(installed) -> UpdateState(UpdateStage.AVAILABLE, latest)
                else -> UpdateState(UpdateStage.CURRENT)
            }
        }
    }

    fun download() {
        val release = state.value.release ?: return
        if (state.value.stage !in listOf(UpdateStage.AVAILABLE, UpdateStage.ERROR)) return
        perform(UpdateStage.DOWNLOADING, release) {
            val token = generation
            val partial = File(packages, "download-$token.part")
            try {
                withContext(Dispatchers.IO) {
                    check(packages.mkdirs() || packages.isDirectory)
                    transport.download(release, partial) { percent ->
                        if (generation == token) mutableState.value = UpdateState(UpdateStage.DOWNLOADING, release, percent.coerceIn(0, 100))
                    }
                    ensureActive()
                }
                mutableState.value = UpdateState(UpdateStage.VERIFYING, release)
                withContext(Dispatchers.IO) {
                    verify(partial, release)
                    ensureActive()
                    check(partial.renameTo(downloaded))
                    val temporary = File(directory, "ready.part")
                    temporary.writeText(releaseJson(release).toString())
                    check(temporary.renameTo(saved))
                }
                mutableState.value = UpdateState(UpdateStage.READY, release, 100)
            } finally { withContext(NonCancellable + Dispatchers.IO) { partial.delete() } }
        }
    }

    fun cancel() {
        generation++
        job?.cancel(); transport.cancel(); job = null
        mutableState.value = state.value.release?.let { UpdateState(UpdateStage.AVAILABLE, it) } ?: UpdateState()
    }
    fun close() { cancel(); scope.cancel() }
    fun mayInstall() = context.packageManager.canRequestPackageInstalls()
    fun permissionIntent() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
    fun installerFailed() { mutableState.value = state.value.copy(stage = UpdateStage.ERROR, issue = UpdateIssue.INSTALLER) }

    suspend fun installIntent(): Intent? {
        val snapshot = state.value
        val release = snapshot.release ?: return null
        if (snapshot.stage != UpdateStage.READY || !mayInstall()) return null
        mutableState.value = snapshot.copy(stage = UpdateStage.VERIFYING)
        return try {
            withContext(Dispatchers.IO) { verify(downloaded, release) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", downloaded)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Glimpse update", uri) }
            mutableState.value = snapshot
            intent
        } catch (cancel: CancellationException) { mutableState.value = snapshot; throw cancel }
        catch (_: Exception) { mutableState.value = UpdateState(UpdateStage.ERROR, release, issue = UpdateIssue.PACKAGE); null }
    }

    private fun perform(stage: UpdateStage, release: Release? = null, action: suspend () -> Unit) {
        if (state.value.busy) return
        val token = ++generation
        mutableState.value = UpdateState(stage, release)
        job = scope.launch {
            try { action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (generation == token) mutableState.value = UpdateState(UpdateStage.ERROR, release, issue = when (error) {
                    is UpdateFailure -> error.issue
                    is IllegalArgumentException, is org.json.JSONException -> UpdateIssue.METADATA
                    else -> UpdateIssue.NETWORK
                })
            }
        }
    }

    private fun verify(file: File, release: Release) {
        try {
            require(file.isFile && file.length() == release.bytes)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == release.sha256)
            val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Missing APK")
            require(acceptsArchive(release, installed, identity(archive), archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE, Build.VERSION.SDK_INT))
        } catch (_: Exception) { throw UpdateFailure(UpdateIssue.PACKAGE) }
    }

    companion object {
        @Suppress("DEPRECATION")
        fun inspectInstalled(context: Context): InstalledApp = identity(context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES))
        private fun identity(info: PackageInfo) = InstalledApp(info.packageName, info.versionName.orEmpty(), info.longVersionCode,
            info.signingInfo?.apkContentsSigners.orEmpty().map { sha256(it.toByteArray()) }.toSet())
    }
}
