package com.neurorift.android.update

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [UpdateAssets.apkName] tests (issue #20): the release-workflow asset
 * naming — first preferred ABI wins, unknown devices fall back to the
 * universal APK. The ABI list is passed in, keeping the test JVM-pure.
 */
class UpdateNamesTest {

    @Test
    fun `arm64 device picks the arm64 asset`() {
        assertEquals(
            "NeuroRift-1.2.3-android-arm64-v8a.apk",
            UpdateAssets.apkName("1.2.3", listOf("arm64-v8a", "armeabi-v7a", "x86_64")),
        )
    }

    @Test
    fun `first supported abi in preference order wins`() {
        assertEquals(
            "NeuroRift-1.2.3-android-armeabi-v7a.apk",
            UpdateAssets.apkName("1.2.3", listOf("armeabi-v7a", "x86_64")),
        )
        assertEquals(
            "NeuroRift-2.0.0-android-x86_64.apk",
            UpdateAssets.apkName("2.0.0", listOf("x86_64", "arm64-v8a")),
        )
    }

    @Test
    fun `unknown abis fall back to universal`() {
        assertEquals(
            "NeuroRift-1.2.3-android-universal.apk",
            UpdateAssets.apkName("1.2.3", listOf("riscv64", "mips")),
        )
        assertEquals(
            "NeuroRift-1.2.3-android-universal.apk",
            UpdateAssets.apkName("1.2.3", emptyList()),
        )
    }

    @Test
    fun `name embeds the release version string`() {
        // Version comes from the (v-stripped) release tag, not the running app.
        assertEquals(
            "NeuroRift-0.9.9-rc.1-android-arm64-v8a.apk",
            UpdateAssets.apkName("0.9.9-rc.1", listOf("arm64-v8a")),
        )
    }

    @Test
    fun `asset urls derive from the release html link`() {
        val link = "https://github.com/masthisis/android-proxy/releases/tag/v1.2.3"
        assertEquals(
            "https://github.com/masthisis/android-proxy/releases/download/v1.2.3/NeuroRift-1.2.3-android-arm64-v8a.apk",
            UpdateAssets.assetUrl(link, "v1.2.3", "NeuroRift-1.2.3-android-arm64-v8a.apk"),
        )
        assertEquals(
            "https://github.com/masthisis/android-proxy/releases/download/v1.2.3/checksums-android.txt",
            UpdateAssets.assetUrl(link, "v1.2.3", UpdateAssets.CHECKSUMS_ASSET),
        )
    }
}
