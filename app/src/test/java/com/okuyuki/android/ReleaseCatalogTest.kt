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
}
