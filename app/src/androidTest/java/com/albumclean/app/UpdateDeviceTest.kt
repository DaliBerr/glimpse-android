package com.albumclean.app

import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.albumclean.app.updates.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdateDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun metadataDigestAndStrictFieldsAreEnforced() {
        val fixture = Fixture("test APK".toByteArray())
        assertEquals(fixture.release, discoverRelease(fixture))
        fixture.badManifest = true
        assertTrue(runCatching { discoverRelease(fixture) }.isFailure)
        for ((key, value) in listOf("schema" to 2, "versionCode" to "2", "size" to 2.5, "packageName" to "other.app", "apkName" to "../unsafe.apk")) {
            assertTrue(key, runCatching { readRelease(releaseJson(fixture.release).put(key, value)) }.isFailure)
        }
        val unpublished = object : UpdateTransport {
            override fun read(url: String, limit: Int): ByteArray = throw NoPublishedRelease()
            override fun download(release: Release, file: File, progress: (Int) -> Unit) = Unit
            override fun cancel() = Unit
        }
        assertNull(discoverRelease(unpublished))
    }

    @Test fun verifiedOriginalSignerUpdateRestoresAndUsesReadOnlyFileProvider() = runBlocking {
        val fixture = realFixture()
        val installed = installedFor(fixture)
        val directory = File(context.cacheDir, "updates")
        directory.deleteRecursively()
        var updater = AppUpdater(context, fixture, installed)
        try {
            wait(updater, UpdateStage.IDLE)
            updater.check(); wait(updater, UpdateStage.AVAILABLE)
            updater.download(); wait(updater, UpdateStage.READY)
            updater.close()
            updater = AppUpdater(context, fixture, installed)
            wait(updater, UpdateStage.READY)
            assertTrue("Run with the device test helper to grant the fixture installation permission", updater.mayInstall())
            val intent = updater.installIntent()!!
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("content", intent.data!!.scheme)
            assertEquals("${context.packageName}.updates", intent.data!!.authority)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            assertArrayEquals(fixture.bytes, context.contentResolver.openInputStream(intent.data!!)!!.use { it.readBytes() })
            assertTrue(runCatching { FileProvider.getUriForFile(context, "${context.packageName}.updates", File(context.filesDir, "private")) }.isFailure)
            File(directory, "packages/update.apk").appendText("corrupted")
            assertNull(updater.installIntent())
            assertEquals(UpdateIssue.PACKAGE, updater.state.value.issue)
        } finally {
            updater.close()
            directory.deleteRecursively()
        }
    }

    @Test fun wrongSignerAndCancelledDownloadsNeverBecomeReady() = runBlocking {
        val fixture = realFixture()
        val directory = File(context.cacheDir, "updates")
        directory.deleteRecursively()
        var updater = AppUpdater(context, fixture, installedFor(fixture).copy(certificates = setOf("wrong")))
        try {
            wait(updater, UpdateStage.IDLE); updater.check(); wait(updater, UpdateStage.AVAILABLE)
            updater.download(); wait(updater, UpdateStage.ERROR)
            assertEquals(UpdateIssue.PACKAGE, updater.state.value.issue)
            assertFalse(File(directory, "ready.json").exists())
            updater.close()
            fixture.delay = true
            fixture.started = false
            updater = AppUpdater(context, fixture, installedFor(fixture))
            wait(updater, UpdateStage.IDLE); updater.check(); wait(updater, UpdateStage.AVAILABLE)
            updater.download()
            withTimeout(5000) { while (!fixture.started) delay(10) }
            updater.cancel()
            assertEquals(UpdateStage.AVAILABLE, updater.state.value.stage)
            withTimeout(5000) { while (File(directory, "packages").listFiles().orEmpty().any { it.extension == "part" }) delay(10) }
            assertFalse(File(directory, "ready.json").exists())
        } finally { updater.close(); directory.deleteRecursively() }
    }

    private fun realFixture(): Fixture {
        val path = InstrumentationRegistry.getArguments().getString("releaseApk") ?: error("Supply -e releaseApk with the signed release fixture")
        return Fixture(File(path).readBytes())
    }
    @Suppress("DEPRECATION")
    private fun installedFor(fixture: Fixture): InstalledApp {
        val path = File(context.cacheDir, "update-identity.apk").apply { writeBytes(fixture.bytes) }
        val archive = context.packageManager.getPackageArchiveInfo(path.path, PackageManager.GET_SIGNING_CERTIFICATES)!!
        path.delete()
        return InstalledApp(RELEASE_PACKAGE, "1.0.0", 1, archive.signingInfo!!.apkContentsSigners.map { sha256(it.toByteArray()) }.toSet())
    }
    private suspend fun wait(updater: AppUpdater, stage: UpdateStage) { withTimeout(20_000) { updater.state.first { it.stage == stage } } }

    private class Fixture(val bytes: ByteArray) : UpdateTransport {
        val release = Release("1.0.1", 2, bytes.size.toLong(), sha256(bytes), "a".repeat(40))
        var badManifest = false
        @Volatile var delay = false
        @Volatile var started = false
        @Volatile var stopped = false
        override fun read(url: String, limit: Int): ByteArray {
            stopped = false
            val manifest = releaseJson(release).toString().toByteArray()
            if (url != RELEASE_API) return manifest
            val assets = JSONArray().put(JSONObject().put("name", "android-update.json")
                .put("browser_download_url", "$PROJECT_URL/releases/download/${release.tag}/android-update.json")
                .put("digest", "sha256:" + if (badManifest) "0".repeat(64) else sha256(manifest)))
                .put(JSONObject().put("name", release.apkName).put("browser_download_url", release.url).put("digest", "sha256:${release.sha256}").put("size", bytes.size))
            return JSONObject().put("draft", false).put("prerelease", false).put("tag_name", release.tag).put("assets", assets).toString().toByteArray()
        }
        override fun download(release: Release, file: File, progress: (Int) -> Unit) {
            started = true
            if (delay) { Thread.sleep(400); if (stopped) throw java.io.IOException("Cancelled fixture") }
            file.writeBytes(bytes); progress(100)
        }
        override fun cancel() { stopped = true }
    }
}
