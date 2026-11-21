package com.neurorift.android

import android.content.Context
import android.os.Build

/**
 * Static application metadata, mirrored after the Windows client's
 * AppService.Info(): what the About panel and diagnostics show. Pure and
 * unit-testable — no Android imports beyond the Context used at init.
 */
data class PlatformInfo(
    val androidRelease: String,
    val apiLevel: Int,
    val arch: String,
)

object AppInfo {
    const val APP_NAME: String = "NeuroRift"
    const val BRAND: String = "NeuroRift for Android"
    const val GITHUB_REPO: String = "masthisis/android-proxy"

    var versionName: String = ""
        private set

    fun init(context: Context) {
        versionName = try {
            val pm = context.packageManager.getPackageInfo(context.packageName, 0)
            pm.versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /** Full identity string used by the About panel, e.g. "NeuroRift for Android 1.0.0". */
    fun fullVersion(): String = "$BRAND ${versionName.ifEmpty { "unknown" }}"

    fun platformInfo(): PlatformInfo = PlatformInfo(
        androidRelease = Build.VERSION.RELEASE ?: "",
        apiLevel = Build.VERSION.SDK_INT,
        arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
    )
}
