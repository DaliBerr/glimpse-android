package com.albumclean.app.updates

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

const val PROJECT_URL = "https://github.com/DaliBerr/glimpse-android"
const val RELEASE_PACKAGE = "com.albumclean.app"
const val RELEASE_API = "https://api.github.com/repos/DaliBerr/glimpse-android/releases/latest"
const val APK_LIMIT = 64L * 1024 * 1024

data class Release(val version: String, val build: Long, val bytes: Long, val sha256: String, val commit: String) {
    val tag get() = "v$version"
    val apkName get() = "Glimpse-$version.apk"
    val url get() = "$PROJECT_URL/releases/download/$tag/$apkName"
    fun validate(): Release {
        require(Regex("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}").matches(version))
        require(build in 1..Int.MAX_VALUE.toLong() && bytes in 1..APK_LIMIT)
        require(Regex("[a-f0-9]{64}").matches(sha256) && Regex("[a-f0-9]{40}").matches(commit))
        return this
    }
    fun newerThan(app: InstalledApp): Boolean {
        validate()
        if (app.id != RELEASE_PACKAGE || build <= app.build) return false
        val current = app.version.split('.').map { it.toIntOrNull() ?: return false }
        if (current.size != 3) return false
        val offered = version.split('.').map(String::toInt)
        return offered.zip(current).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: true
    }
}

data class InstalledApp(val id: String, val version: String, val build: Long, val certificates: Set<String>)

fun acceptsArchive(release: Release, installed: InstalledApp, archive: InstalledApp, minimumSdk: Int, deviceSdk: Int): Boolean =
    release.newerThan(installed) && archive.id == installed.id && archive.version == release.version && archive.build == release.build &&
        minimumSdk in 1..deviceSdk && installed.certificates.isNotEmpty() && installed.certificates == archive.certificates

enum class UpdateIssue { NETWORK, RATE_LIMIT, METADATA, PACKAGE, INSTALLER }
class UpdateFailure(val issue: UpdateIssue) : java.io.IOException(issue.name)
class NoPublishedRelease : java.io.IOException()

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
fun readRelease(json: JSONObject): Release {
    fun number(key: String): Long {
        val value = json.get(key)
        require(value is Long || value is Int)
        return (value as Number).toLong()
    }
    require(number("schema") == 1L && json.getString("packageName") == RELEASE_PACKAGE)
    val release = Release(json.getString("versionName"), number("versionCode"), number("size"), json.getString("sha256"), json.getString("sourceCommit")).validate()
    require(json.getString("apkName") == release.apkName)
    return release
}
fun releaseJson(release: Release): JSONObject = JSONObject().put("schema", 1).put("packageName", RELEASE_PACKAGE)
    .put("versionName", release.version).put("versionCode", release.build).put("size", release.bytes)
    .put("sha256", release.sha256).put("sourceCommit", release.commit).put("apkName", release.apkName)

interface UpdateTransport {
    fun read(url: String, limit: Int): ByteArray
    fun download(release: Release, file: File, progress: (Int) -> Unit)
    fun cancel()
}

/** Updates are public, unauthenticated requests; no gallery data enters this transport. */
class GitHubTransport : UpdateTransport {
    private val active = ConcurrentHashMap.newKeySet<HttpsURLConnection>()
    override fun cancel() { active.forEach { it.disconnect() } }

    override fun read(url: String, limit: Int): ByteArray {
        val connection = connect(url)
        try {
            require(connection.contentLengthLong <= limit)
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= limit)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally { active.remove(connection); connection.disconnect() }
    }

    override fun download(release: Release, file: File, progress: (Int) -> Unit) {
        val connection = connect(release.url)
        val deadline = System.nanoTime() + 180_000_000_000L
        try {
            require(connection.contentLengthLong == -1L || connection.contentLengthLong == release.bytes)
            val hash = MessageDigest.getInstance("SHA-256")
            var received = 0L
            connection.inputStream.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    if (System.nanoTime() > deadline) throw UpdateFailure(UpdateIssue.NETWORK)
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                    require(received <= release.bytes && received <= APK_LIMIT)
                    hash.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    progress((received * 100 / release.bytes).toInt())
                }
            } }
            require(received == release.bytes && hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == release.sha256)
        } catch (_: IllegalArgumentException) { throw UpdateFailure(UpdateIssue.PACKAGE) }
        finally { active.remove(connection); connection.disconnect() }
    }

    private fun connect(initial: String): HttpsURLConnection {
        var target = initial
        repeat(6) {
            require(trustedUpdateUrl(target))
            val connection = URL(target).openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000; connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "Glimpse-Android-Updater")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            active.add(connection)
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw UpdateFailure(UpdateIssue.NETWORK)
                    target = URL(URL(target), location).toString()
                    active.remove(connection); connection.disconnect()
                } else {
                    if (status == 404 && initial == RELEASE_API) throw NoPublishedRelease()
                    if (status == 403 || status == 429) throw UpdateFailure(UpdateIssue.RATE_LIMIT)
                    if (status !in 200..299) throw UpdateFailure(UpdateIssue.NETWORK)
                    return connection
                }
            } catch (error: Exception) { active.remove(connection); connection.disconnect(); throw error }
        }
        throw UpdateFailure(UpdateIssue.NETWORK)
    }
}

fun trustedUpdateUrl(value: String): Boolean = runCatching {
    val url = URL(value)
    url.protocol == "https" && (url.port == -1 || url.port == 443) && url.userInfo == null && url.ref == null && when (url.host) {
        "api.github.com" -> url.path == "/repos/DaliBerr/glimpse-android/releases/latest" && url.query == null
        "github.com" -> Regex("/DaliBerr/glimpse-android/releases/download/v[0-9.]+/(android-update\\.json|Glimpse-[0-9.]+\\.apk)").matches(url.path) && url.query == null
        "release-assets.githubusercontent.com", "objects.githubusercontent.com" -> true
        else -> false
    }
}.getOrDefault(false)

fun discoverRelease(transport: UpdateTransport): Release? {
    val raw = try { transport.read(RELEASE_API, 256 * 1024) } catch (_: NoPublishedRelease) { return null }
    val published = JSONObject(String(raw, Charsets.UTF_8))
    require(published.opt("draft") == false && published.opt("prerelease") == false)
    val tag = published.getString("tag_name")
    require(Regex("v[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}").matches(tag))
    val assets = published.getJSONArray("assets")
    require(assets.length() <= 100)
    fun asset(name: String) = (0 until assets.length()).map { assets.getJSONObject(it) }.single { it.getString("name") == name }
    val manifest = asset("android-update.json")
    val url = "$PROJECT_URL/releases/download/$tag/android-update.json"
    require(manifest.getString("browser_download_url") == url)
    val bytes = transport.read(url, 16 * 1024)
    require(manifest.getString("digest") == "sha256:${sha256(bytes)}")
    val release = readRelease(JSONObject(String(bytes, Charsets.UTF_8)))
    require(release.tag == tag)
    val apk = asset(release.apkName)
    require(apk.getString("browser_download_url") == release.url && apk.getString("digest") == "sha256:${release.sha256}")
    require(apk.get("size") is Number && apk.getLong("size") == release.bytes)
    return release
}
