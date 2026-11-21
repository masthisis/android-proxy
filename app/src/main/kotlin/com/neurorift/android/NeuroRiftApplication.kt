package com.neurorift.android

import android.app.Application

/**
 * Application entry point. Wiring (Hilt, core init) lands with the engine
 * issues; this stays minimal so the bootstrap remains a plain Compose shell.
 */
class NeuroRiftApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppInfo.init(this)
    }
}
