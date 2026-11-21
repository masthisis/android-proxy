package com.neurorift.android.update

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.neurorift.android.AppInfo
import com.neurorift.android.engine.EventBus
import com.neurorift.android.engine.Events
import com.neurorift.android.settings.SettingsStore
import java.io.File
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Updater state machine (issue #20) — mirrors the Windows updater's states.
 * Published on the `app:update` event (CONTRACT.md) and collected by the
 * Settings screen + Dashboard banner.
 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val version: String, val downloadUrl: String, val notesUrl: String) : UpdateState
    data object None : UpdateState
    data class Downloading(val bytesRead: Long, val totalBytes: Long) : UpdateState
    data class Ready(val file: File, val version: String) : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * In-app self-update from GitHub releases (issue #20).
 *
 * - [check] fetches the project's `releases.atom` feed (Windows parity —
 *   never api.github.com), filters prereleases per the `updates.includePrerelease`
 *   setting, and picks the newest release whose semver beats the running
 *   version. The last seen version persists in `updatestate.json` so the
 *   notify/emit only happens once per new release (Windows parity).
 * - [download] streams the device-ABI APK asset (universal fallback) plus
 *   `checksums-android.txt`, verifies SHA256, and lands in [UpdateState.Ready].
 * - [install] hands the verified APK to the system installer through a
 *   FileProvider content URI.
 *
 * Pure helpers ([Semver], [parseAtom], [UpdateAssets.apkName], [verifySha256])
 * are JVM-tested separately.
 */
class UpdateService(private val context: Context) {

    val state = MutableStateFlow<UpdateState>(UpdateState.Idle)

    private val json = Json { ignoreUnknownKeys = true }
    private val stateFile = File(File(context.filesDir, "neurorift"), "updatestate.json")
    private val cacheDir = File(context.cacheDir, "apk").apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .readTimeout(Duration.ofSeconds(60))
        .build()

    @Volatile
    private var lastParsed: List<AtomRelease> = emptyList()

    // ---------------------------------------------------------------- check --

    /**
     * Manual "Check now" path: always hits the feed (bypassing the 6 h
     * auto-check guard) and updates [state] with the outcome.
     */
    suspend fun check() {
        state.value = UpdateState.Checking
        runCatching {
            val settings = SettingsStore.shared(context).get()
            val xml = fetchText(RELEASES_ATOM_URL)
            val releases = parseAtom(xml).also { lastParsed = it }
            val current = AppInfo.versionName.ifEmpty { "0.0.0" }
            releases
                .filter { settings.updates.includePrerelease || !it.tag.contains('-') }
                .sortedWith { a, b -> Semver.compare(Semver.stripV(b.tag), Semver.stripV(a.tag)) }
                .firstOrNull { Semver.isNewer(Semver.stripV(it.tag), current) }
        }.onSuccess { release ->
            if (release == null) {
                // No (eligible) release newer than the running version.
                publish(UpdateState.None)
                stampCheck()
            } else {
                val version = Semver.stripV(release.tag)
                val apkUrl = UpdateAssets.assetUrl(
                    release.link,
                    release.tag,
                    UpdateAssets.apkName(version, Build.SUPPORTED_ABIS.toList()),
                )
                // Notify-once parity (Windows updatestate): the availability
                // state is always reflected, but the app:update event + the
                // lastSeenVersion write only fire for a NEW release.
                val doc = loadDoc()
                val seen = doc.lastSeenVersion != version
                state.value = UpdateState.Available(version, apkUrl, release.link)
                if (seen) {
                    EventBus.emit(Events.APP_UPDATE, version)
                }
                saveDoc(doc.copy(lastSeenVersion = version, lastCheckAt = System.currentTimeMillis()))
            }
        }.onFailure { e ->
            state.value = UpdateState.Error(e.message ?: "update check failed")
        }
    }

    /**
     * App-start path ([com.neurorift.android.NeuroRiftApplication]): honours
     * `updates.autoCheck` and checks at most once per [AUTO_CHECK_INTERVAL].
     */
    suspend fun autoCheck() {
        val settings = SettingsStore.shared(context).get()
        if (!settings.updates.autoCheck) return
        if (System.currentTimeMillis() - loadDoc().lastCheckAt < AUTO_CHECK_INTERVAL.toMillis()) return
        check()
    }

    // -------------------------------------------------------------- download --

    /**
     * Downloads + verifies the given release's APK. Resolves the release by
     * tag from the last check when the UI only carries the version string.
     */
    suspend fun download(version: String) {
        val release = lastParsed.firstOrNull { Semver.stripV(it.tag) == version }
            ?: run {
                check()
                lastParsed.firstOrNull { Semver.stripV(it.tag) == version }
            }
        if (release != null) download(release)
    }

    /** Streams the APK + checksums into the cache dir, then verifies SHA256. */
    suspend fun download(release: AtomRelease) {
        withContext(Dispatchers.IO) {
            val version = Semver.stripV(release.tag)
            val name = UpdateAssets.apkName(version, Build.SUPPORTED_ABIS.toList())
            val apkUrl = UpdateAssets.assetUrl(release.link, release.tag, name)
            val checksumsUrl = UpdateAssets.assetUrl(release.link, release.tag, UpdateAssets.CHECKSUMS_ASSET)
            val target = File(cacheDir, name)
            runCatching {
                publish(UpdateState.Downloading(0, 0))
                fetchToFile(apkUrl, target) { read, totalBytes ->
                    // StateFlow conflates — publishing per read is safe.
                    state.value = UpdateState.Downloading(read, totalBytes)
                }
                val checksums = fetchText(checksumsUrl)
                if (!verifySha256(target, checksums)) {
                    target.delete()
                    throw IllegalStateException("checksum mismatch for $name")
                }
                state.value = UpdateState.Ready(target, version)
                EventBus.emit(Events.APP_UPDATE, "ready:$version")
            }.onFailure { e ->
                if (target.exists()) target.delete()
                state.value = UpdateState.Error(e.message ?: "download failed")
                EventBus.emit(Events.APP_UPDATE, "error:${e.message}")
            }
        }
    }

    // --------------------------------------------------------------- install --

    /**
     * Hands the verified APK to the system installer: FileProvider content
     * URI + ACTION_VIEW (the package-archive view starts the installer
     * activity; NEW_TASK because the service context is not an activity).
     */
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(
            context.applicationContext,
            context.packageName + ".fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.applicationContext.startActivity(intent)
    }

    // --------------------------------------------------------------- helpers --

    private fun publish(next: UpdateState) {
        state.value = next
        EventBus.emit(Events.APP_UPDATE, next)
    }

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalArgumentException("update fetch failed: HTTP ${resp.code}")
            resp.body.string()
        }
    }

    /** Streams the asset to [target]; returns the total bytes (-1 when unknown). */
    private suspend fun fetchToFile(url: String, target: File, onProgress: (Long, Long) -> Unit): Long =
        withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalArgumentException("download failed: HTTP ${resp.code}")
                val total = resp.body.contentLength()
                var read = 0L
                resp.body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                            read += n
                            onProgress(read, total)
                        }
                    }
                }
                total
            }
        }

    // --------------------------------------------------------- updatestate --

    /** Windows `updatestate` parity: last seen version + last check time. */
    @Serializable
    private data class UpdateDoc(val lastSeenVersion: String = "", val lastCheckAt: Long = 0L)

    private fun loadDoc(): UpdateDoc =
        runCatching { json.decodeFromString(UpdateDoc.serializer(), stateFile.readText()) }
            .getOrDefault(UpdateDoc())

    private fun saveDoc(doc: UpdateDoc) {
        runCatching {
            stateFile.parentFile?.mkdirs()
            stateFile.writeText(json.encodeToString(UpdateDoc.serializer(), doc))
        }
    }

    private suspend fun stampCheck() {
        val doc = loadDoc()
        saveDoc(doc.copy(lastCheckAt = System.currentTimeMillis()))
    }

    companion object {
        /** Atom feed only — api.github.com is a Windows-parity forbidden surface. */
        const val RELEASES_ATOM_URL = "https://github.com/masthisis/android-proxy/releases.atom"

        /** autoCheck cadence guard. */
        val AUTO_CHECK_INTERVAL: Duration = Duration.ofHours(6)

        @Volatile
        private var shared: UpdateService? = null

        fun shared(context: Context): UpdateService =
            shared ?: synchronized(this) {
                shared ?: UpdateService(context.applicationContext).also { shared = it }
            }
    }
}
