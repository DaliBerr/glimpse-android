package com.albumclean.app

import com.albumclean.app.updates.*
import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val app = InstalledApp(RELEASE_PACKAGE, "1.0.0", 1, setOf("trusted"))
    private val release = Release("1.0.1", 2, 123, "a".repeat(64), "b".repeat(40))

    @Test fun versionsNeverOfferDowngradesAndDebugBuildsStaySeparate() {
        assertTrue(release.newerThan(app))
        assertFalse(release.newerThan(app.copy(build = 2)))
        assertFalse(release.newerThan(app.copy(id = "$RELEASE_PACKAGE.debug")))
        assertFalse(release.newerThan(app.copy(version = "2.0.0")))
        assertFalse(release.newerThan(app.copy(version = "1.0.0-debug")))
        assertTrue(release.copy(version = "1.0.0").newerThan(app))
    }
    @Test fun anArchiveMustMatchEveryIdentityAndPlatformField() {
        val good = app.copy(version = release.version, build = release.build)
        assertTrue(acceptsArchive(release, app, good, 29, 34))
        for (bad in listOf(good.copy(id = "other.app"), good.copy(version = "2.0.0"), good.copy(build = 3),
            good.copy(certificates = setOf("wrong")), good.copy(certificates = setOf("trusted", "extra")))) {
            assertFalse(acceptsArchive(release, app, bad, 29, 34))
        }
        assertFalse(acceptsArchive(release, app.copy(certificates = emptySet()), good, 29, 34))
        assertFalse(acceptsArchive(release, app, good, 35, 34))
    }
    @Test fun oversizedOrMalformedMetadataIsRejected() {
        for (invalid in listOf(release.copy(version = "../1"), release.copy(build = 0), release.copy(build = Int.MAX_VALUE.toLong() + 1),
            release.copy(bytes = 0), release.copy(bytes = APK_LIMIT + 1), release.copy(sha256 = "x".repeat(64)), release.copy(commit = "main"))) {
            assertTrue(runCatching { invalid.validate() }.isFailure)
        }
    }
    @Test fun updateUrlsCannotEscapeTheOfficialHttpsRepository() {
        assertTrue(trustedUpdateUrl(RELEASE_API))
        assertTrue(trustedUpdateUrl(release.url))
        assertTrue(trustedUpdateUrl("https://release-assets.githubusercontent.com/path?token=synthetic"))
        for (bad in listOf("http://github.com/file", "https://github.com.evil.invalid/path", "https://user@github.com/path",
            "$RELEASE_API?redirect=evil", "https://api.github.com/user", "https://github.com/other/repo/releases/download/v1.0.1/Glimpse-1.0.1.apk",
            "https://objects.githubusercontent.com:444/file", "https://github.com/DaliBerr/glimpse-android/releases/download/v1.0.1/../secret")) {
            assertFalse(bad, trustedUpdateUrl(bad))
        }
    }
    @Test fun filenamesAndDigestsAreDeterministic() {
        assertEquals("Glimpse-1.0.1.apk", release.apkName)
        assertEquals("$PROJECT_URL/releases/download/v1.0.1/Glimpse-1.0.1.apk", release.url)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256(byteArrayOf()))
    }
}
