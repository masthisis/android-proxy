package com.neurorift.android

import android.app.Application
import com.neurorift.android.profile.SubscriptionScheduler
import com.neurorift.android.update.UpdateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Process-level wiring lives here.
 *
 * Issue #18: the subscription auto-update scheduler enqueues its unique
 * periodic work once per start (KEEP policy — the cadence survives restarts).
 *
 * Issue #20: when `updates.autoCheck` is on, a best-effort release check
 * runs at most every 6 h — the timestamp lives in the updatestate file, so
 * every app start does not hit the releases feed.
 */
class NeuroRiftApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AppInfo.init(this)
        SubscriptionScheduler(this).ensurePeriodicWork()
        appScope.launch {
            runCatching { UpdateService.shared(this@NeuroRiftApplication).autoCheck() }
        }
    }
}
