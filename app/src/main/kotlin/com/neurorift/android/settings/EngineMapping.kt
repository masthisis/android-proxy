package com.neurorift.android.settings

import com.neurorift.android.engine.EngineSettings

/**
 * Maps the persisted settings document onto the engine-facing subset
 * consumed by [com.neurorift.android.engine.ConfigGen] (issue #14: settings
 * become the single source of truth for the generated config).
 */
fun Settings.toEngineSettings(): EngineSettings = EngineSettings(
    mixedPort = ports.mixed,
    controllerPort = ports.controller,
    speedTestPort = ports.speedTest,
    controllerSecret = controllerSecret,
    allowLan = allowLan,
    mode = mode,
    logLevel = log.coreLevel,
    appLogLevel = log.appLevel,
    tunStack = tun.stack,
    tunMtu = tun.mtu,
    testUrl = test.url,
    speedTestUrl = test.speedUrl,
    speedUploadUrl = test.speedUploadUrl,
    dnsOverride = dns.override,
    dnsNameservers = dns.nameservers,
)
