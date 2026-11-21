package com.neurorift.android.update

import java.io.File
import java.security.MessageDigest

/**
 * Release-asset naming + integrity math for the self-updater (issue #20).
 * Pure and JVM-testable: the device ABI list is passed in (never read from
 * `Build` here), so [apkName] has no Android dependencies.
 */
object UpdateAssets {

    /** ABIs the release workflow ships per-ABI APKs for (plus universal). */
    val APK_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

    const val CHECKSUMS_ASSET = "checksums-android.txt"

    /**
     * Distribution APK name for the given release [version] — matches the
     * release workflow's rename step (`NeuroRift-<version>-android-<abi>.apk`).
     * The first of [supportedAbis] present in [APK_ABIS] wins (the platform
     * orders SUPPORTED_ABIS by preference); unknown devices fall back to the
     * universal APK.
     */
    fun apkName(version: String, supportedAbis: List<String>): String {
        val abi = supportedAbis.firstOrNull { it in APK_ABIS } ?: "universal"
        return "NeuroRift-$version-android-$abi.apk"
    }

    /**
     * Release-hosted asset URL. GitHub's Atom `link` points at the HTML page
     * (`…/releases/tag/v1.2.3`); the asset lives at the parallel
     * `…/releases/download/<tag>/<asset>` path (Windows-parity derivation,
     * never the api.github.com JSON surface).
     */
    fun assetUrl(releaseLink: String, tag: String, asset: String): String {
        val base = releaseLink.removeSuffix("/tag/$tag")
        return "$base/download/$tag/$asset"
    }
}

/**
 * Verifies the downloaded file against the release's `checksums-android.txt`
 * (`sha256sum` format: `<hex digest>␣␣<filename>`). Pure apart from reading
 * [file] — streamed so a large APK never sits in memory.
 */
fun verifySha256(file: File, checksums: String): Boolean {
    val expected = checksums.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 2) parts[1] to parts[0] else null
        }
        .toMap()[file.name] ?: return false
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    return actual.equals(expected, ignoreCase = true)
}
