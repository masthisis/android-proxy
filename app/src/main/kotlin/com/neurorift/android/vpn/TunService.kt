package com.neurorift.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.neurorift.android.MainActivity
import com.neurorift.android.R
import com.neurorift.android.core.CoreBridge
import com.neurorift.android.engine.EngineHolder
import com.neurorift.android.engine.EngineManager
import com.neurorift.android.engine.EngineSettings
import com.neurorift.android.engine.EngineState
import com.neurorift.android.profile.ProfileRegistry
import com.neurorift.android.settings.AppFilter
import com.neurorift.android.settings.SettingsStore
import com.neurorift.android.settings.toEngineSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nrcore.ProtectHook
import nrcore.UidResolver

/**
 * The Android VPN layer (issue #8): owns the VpnService lifecycle, creates
 * the tun interface and hands its fd to the embedded mihomo core (issue #7).
 *
 * Extends VpnService directly (Builder is an inner class of VpnService on
 * current SDKs, so the service must be the outer receiver); the coroutine
 * scope is cancelled in onDestroy.
 *
 * Pitfalls honored (docs/AI_DEVELOPER_GUIDE.md):
 * - P2: the tun fd is a one-shot resource — never cached across restarts.
 * - P3: routes/address/MTU are set here by the VPN builder; mihomo's tun
 *       listener runs with AutoRoute=false / AutoDetectInterface=false.
 * - P4: every outbound socket is protected via [VpnService.protect].
 * - P5: only one VPN can run — system failures are surfaced, not retried.
 * - P6: foreground service with a persistent notification; dismiss = stop.
 */
class TunService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tun: ParcelFileDescriptor? = null

    /**
     * Settings live in the persisted store (issue #14): the secret is
     * generated once and stays stable across restarts; every (re)start and
     * config regeneration reads the current document.
     */
    private val settingsStore by lazy { SettingsStore.shared(applicationContext) }

    private fun engineSettings(): EngineSettings = settingsStore.getBlocking().toEngineSettings()

    @Volatile
    private var engine: EngineManager? = null

    /** Lazily built supervisor — one per service instance (P2: fd lifetime). */
    private fun engine(): EngineManager =
        engine ?: EngineManager(
            coreHome = CoreBridge.coreHome(this),
            settingsProvider = { engineSettings() },
            profilesProvider = { ProfileRegistry.meta(this@TunService) },
            activeIdProvider = { ProfileRegistry.activeId(this@TunService) },
        ).also { engine = it }

    // ---------------------------------------------------------------------
    // Platform hooks handed to the Go core
    // ---------------------------------------------------------------------

    /** Unqualified delegate so the hook object does not recurse into itself. */
    private fun protectSocket(fd: Int): Boolean = protect(fd)

    /** Protects outbound sockets so they bypass the VPN tunnel (P4). */
    private val protectHook = object : ProtectHook {
        override fun protect(fd: Int): Boolean = protectSocket(fd)
    }

    /**
     * Resolves the owning uid for per-app rules. API 31+ uses the platform
     * connection-owner lookup; older releases return -1 (split tunneling at
     * the VPN layer still covers per-app routing there).
     */
    private val uidResolver = object : UidResolver {
        override fun resolveUid(protocol: Int, source: String, target: String): Int {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return -1
            val cm = getSystemService(ConnectivityManager::class.java) ?: return -1
            val src = parseSocketAddress(source) ?: return -1
            val dst = parseSocketAddress(target) ?: return -1
            return runCatching { cm.getConnectionOwnerUid(protocol, src, dst) }.getOrDefault(-1)
        }
    }

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_MODE_CYCLE -> serviceScope.launch(Dispatchers.IO) { cycleMode() }

            else -> serviceScope.launch { startVpn() }
        }
        return START_STICKY
    }

    /**
     * Notification quick control (issue #19, Windows tray:mode parity):
     * cycles rule -> global -> direct, persists the new mode and patches the
     * live core without a reload.
     */
    private suspend fun cycleMode() {
        val order = listOf("rule", "global", "direct")
        val current = runCatching { settingsStore.get().mode }.getOrDefault("rule")
        val idx = order.indexOf(current).takeIf { it >= 0 } ?: 0
        val next = order[(idx + 1) % order.size]
        runCatching { settingsStore.save(settingsStore.get().copy(mode = next)) }
        runCatching { engine().setMode(next) }
        notifyStatus(getString(R.string.vpn_state_running))
    }

    override fun onDestroy() {
        serviceScope.cancel()
        shutdownQuietly()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // VPN establishment
    // ---------------------------------------------------------------------

    private suspend fun startVpn() {
        if (EngineHolder.status.value.isRunning) return
        EngineHolder.transitionTo(EngineState.STARTING)
        notifyStatus(getString(R.string.vpn_state_starting))
        startForegroundSafely()

        try {
            withContext(Dispatchers.IO) {
                val settings = engineSettings()
                val pfd = establish(settings)
                if (pfd == null) {
                    throw VpnException(getString(R.string.vpn_error_prepare))
                }
                CoreBridge.init(this@TunService, protectHook, uidResolver)
                val mgr = engine()
                mgr.applyFromProfiles()
                CoreBridge.startTun(
                    fd = pfd.fd,
                    stack = settings.tunStack,
                    gateway = EngineSettings.TUN_GATEWAY,
                    dns = EngineSettings.TUN_DNS,
                )
                tun = pfd
            }
            EngineHolder.transitionTo(EngineState.RUNNING)
            notifyStatus(getString(R.string.vpn_state_running))
        } catch (e: Exception) {
            shutdownQuietly()
            EngineHolder.transitionTo(EngineState.FAILED, e.message)
            notifyStatus(getString(R.string.vpn_state_failed))
        }
    }

    /**
     * Builds the tun interface and returns its descriptor. Routes/address
     * are owned by the builder (P3); MTU + split tunneling follow the
     * persisted settings (issue #14).
     */
    private fun establish(settings: EngineSettings): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(SESSION_NAME)
            .setMtu(settings.tunMtu)
            .addAddress(VPN_ADDR4, VPN_PREFIX4)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(EngineSettings.TUN_DNS)
        // IPv6 is safe on every supported API level.
        runCatching {
            builder.addAddress(VPN_ADDR6, VPN_PREFIX6)
            builder.addRoute("::", 0)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setBlocking(true)
        }
        // Split tunneling (issue #14): allowlist = only these apps; denylist
        // = these apps bypass; off = everything routes.
        val stored = settingsStore.getBlocking()
        val decision = AppFilter.decide(stored.splitTunneling.mode, stored.splitTunneling.apps)
        decision.allowedApps.forEach { runCatching { builder.addAllowedApplication(it) } }
        decision.disallowedApps.forEach { runCatching { builder.addDisallowedApplication(it) } }
        return builder.establish()
    }

    // ---------------------------------------------------------------------
    // Shutdown
    // ---------------------------------------------------------------------

    private fun shutdown() {
        // Runs on the service's main thread — the work is light (fd close +
        // state publish + notification). onDestroy also calls shutdownQuietly,
        // so a killed scope cannot leak the tunnel.
        shutdownQuietly()
        EngineHolder.transitionTo(EngineState.STOPPED)
        notifyStatus(getString(R.string.vpn_state_stopped))
        stopSelf()
    }

    private fun shutdownQuietly() {
        runCatching { CoreBridge.stopTun() }
        runCatching { tun?.close() }
        tun = null
    }

    // ---------------------------------------------------------------------
    // Notification (P6: foreground service; dismiss = stop)
    // ---------------------------------------------------------------------

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun baseNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TunService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val modeCycle = PendingIntent.getService(
            this,
            2,
            Intent(this, TunService::class.java).setAction(ACTION_MODE_CYCLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.vpn_action_mode_cycle), modeCycle)
            .addAction(0, getString(R.string.vpn_action_disconnect), stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun notifyStatus(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, baseNotification(text))
    }

    private fun startForegroundSafely() {
        val notification = baseNotification(getString(R.string.vpn_state_starting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun parseSocketAddress(socket: String): java.net.InetSocketAddress? {
        val idx = socket.lastIndexOf(':')
        if (idx <= 0) return null
        val host = socket.substring(0, idx).trim('[', ']')
        val port = socket.substring(idx + 1).toIntOrNull() ?: return null
        return java.net.InetSocketAddress(host, port)
    }

    private class VpnException(message: String) : Exception(message)

    companion object {
        // VPN interface addressing — mirrors EngineSettings.TUN_GATEWAY.
        const val VPN_ADDR4 = "172.19.0.2"
        const val VPN_PREFIX4 = 30
        const val VPN_ADDR6 = "fdfe:dcba:9876::2"
        const val VPN_PREFIX6 = 126

        const val CHANNEL_ID = "vpn_status"
        const val NOTIFICATION_ID = 1
        const val SESSION_NAME = "NeuroRift"
        const val MTU = 9000
        const val ACTION_START = "com.neurorift.android.action.START"
        const val ACTION_STOP = "com.neurorift.android.action.STOP"
        const val ACTION_MODE_CYCLE = "com.neurorift.android.action.MODE_CYCLE"

        fun start(context: Context) {
            val intent = Intent(context, TunService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TunService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
