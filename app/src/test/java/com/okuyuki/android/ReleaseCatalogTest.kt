package com.okuyuki.android

import org.junit.Assert.*
import org.junit.Test

class ReleaseCatalogTest {
    private fun release(tag: String, url: String = "https://github.com/lingmulongtai/Okuyuki-android/releases/download/$tag/Okuyuki-android-$tag.apk", draft: Boolean = false) = """
        {"tag_name":"$tag","draft":$draft,"assets":[{"name":"Okuyuki-android-$tag.apk","state":"uploaded","size":200,"digest":"sha256:${"a".repeat(64)}","browser_download_url":"$url"}]}
    """.trimIndent()

    @Test fun picksNewestPrereleaseNumerically() {
        val json = "[${release("v0.1.0-alpha.10-preview")},${release("v0.1.0-alpha.9-preview")},${release("v0.1.0-alpha.11-preview", draft = true)}]"
        assertEquals("0.1.0-alpha.10-preview", ReleaseCatalog.find(json, "0.1.0-alpha.9-preview", "android")?.version)
        assertNull(ReleaseCatalog.find(json, "0.1.0-alpha.10-preview", "android"))
    }
    @Test fun rejectsUntrustedAssetAndTreatsStableAsNewer() {
        val bad = release("v0.1.0", "https://evil.example/app.apk")
        assertNull(ReleaseCatalog.find("[$bad]", "0.1.0-alpha.9-preview", "android"))
        assertEquals("0.1.0", ReleaseCatalog.find("[${release("v0.1.0")}]", "0.1.0-alpha.9-preview", "android")?.version)
    }
    @Test fun findsPublishedPreviewInPublicFeedAndChecksItsExactAsset() {
        val feed = """<feed xmlns="http://www.w3.org/2005/Atom"><id>tag:github.com,2008:https://github.com/lingmulongtai/Okuyuki-android/releases</id><entry><link rel="alternate" href="https://github.com/lingmulongtai/Okuyuki-android/releases/tag/v0.1.0-alpha.12-preview"/></entry>
            <entry><link href="https://evil.example/releases/tag/v9.0.0"/></entry></feed>"""
        val tag = ReleaseCatalog.latestFeedTag(feed, "0.1.0-alpha.11-preview", "android")
        assertEquals("v0.1.0-alpha.12-preview", tag)
        val digest = "b".repeat(64)
        val release = ReleaseCatalog.fromChecksums(tag!!, "$digest  Okuyuki-android-$tag.apk\n", "android")
        assertEquals(digest, release?.digest)
        assertEquals(0L, release?.size)
        assertNull(ReleaseCatalog.fromChecksums(tag, "$digest  other.apk\n", "android"))
        assertNull(ReleaseCatalog.fromChecksums(tag, "$digest  Okuyuki-android-$tag.apk\n$digest  Okuyuki-android-$tag.apk\n", "android"))
        assertNull(ReleaseCatalog.latestFeedTag(feed, "0.1.0-alpha.12-preview", "android"))
        assertThrows(IllegalArgumentException::class.java) { ReleaseCatalog.latestFeedTag("not a feed", "0.1.0-alpha.11-preview", "android") }
        assertThrows(IllegalArgumentException::class.java) { ReleaseCatalog.latestFeedTag(feed.substringBefore("</feed>"), "0.1.0-alpha.11-preview", "android") }
        assertThrows(IllegalArgumentException::class.java) { ReleaseCatalog.latestFeedTag(feed.replace("Okuyuki-android/releases/tag/", "other/releases/tag/"), "0.1.0-alpha.11-preview", "android") }
    }
    @Test fun ordersAlphaBetaReleaseCandidateAndStable() {
        val json = "[${release("v0.2.0-beta.1")},${release("v0.2.0-rc.1")},${release("v0.2.0-alpha.20-preview")}]"
        assertEquals("0.2.0-rc.1", ReleaseCatalog.find(json, "0.2.0-alpha.1-preview", "android")?.version)
        assertEquals("0.2.0", ReleaseCatalog.find("[${release("v0.2.0")}]", "0.2.0-rc.1", "android")?.version)
    }
}
