package com.neurorift.android.core

import android.content.Context
import com.neurorift.android.AppInfo
import java.io.File
import nrcore.Nrcore
import nrcore.ProtectHook
import nrcore.UidResolver

/**
 * Kotlin-side bridge to the embedded Go core (mihomo via the `nrcore` AAR).
 *
 * Lifecycle: [init] once per app process (installs the platform hooks and
 * the home dir), then [applyConfig] + [startTun] when the VPN service
 * establishes the tun interface. All calls throw on failure — the caller
 * translates that into the engine state machine.
 *
 * gomobile maps Go `error` returns to Java exceptions, so every call is
 * wrapped here into a Kotlin-friendly API.
 */
object CoreBridge {

    /** Home dir inside app storage — mirrors the Windows `<data>/core` layout. */
    fun coreHome(context: Context): File =
        File(context.filesDir, "neurorift/core").apply { mkdirs() }

    /**
     * Installs the platform hooks. [protect] must call
     * `VpnService.protect(fd)` (pitfall P4 — without it outbound sockets loop
     * back into the tun device and hang forever).
     */
    fun init(context: Context, protect: ProtectHook, uid: UidResolver) {
        Nrcore.init(coreHome(context).absolutePath, "mihomo-" + AppInfo.versionName, protect, uid)
    }

    /** Parses + applies the generated runtime YAML (hot apply). */
    fun applyConfig(yaml: String) {
        Nrcore.applyConfig(yaml)
    }

    /** Binds mihomo's tun listener to the VpnService fd. One-shot — pitfall P2. */
    fun startTun(fd: Int, stack: String, gateway: String, dns: String) {
        // gomobile maps Go int32 -> Java int.
        Nrcore.startTun(fd, stack, gateway, "", dns)
    }

    fun stopTun() {
        Nrcore.stopTun()
    }

    fun stop() {
        Nrcore.stop()
    }

    fun version(): String = Nrcore.version()
}
