package com.neurorift.android

import android.app.Application
import com.neurorift.android.profile.SubscriptionScheduler

/**
 * Application entry point. Wiring (Hilt, core init) lands with the engine
 * issues; this stays minimal so the bootstrap remains a plain Compose shell.
 *
 * Issue #18: the subscription auto-update scheduler enqueues its unique
 * periodic work once per start (KEEP policy — the cadence survives restarts).
 */
class NeuroRiftApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppInfo.init(this)
        SubscriptionScheduler(this).ensurePeriodicWork()
    }
}
