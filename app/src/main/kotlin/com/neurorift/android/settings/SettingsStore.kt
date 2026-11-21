package com.neurorift.android.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * DataStore-backed settings persistence (issue #14) — the atomic JSON
 * document equivalent of the Windows xjson store. Migration-safe: unknown /
 * missing keys fall back to the data-class defaults; a corrupt document
 * resets to defaults instead of crashing the tunnel.
 *
 * [save] runs the document through [SettingsValidator.sanitize] and returns
 * the sanitized result (Windows service_settings parity); callers then
 * trigger the engine re-apply.
 */

/** Top-level DataStore delegate — one store per process, by contract. */
private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsStore private constructor(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settings: Flow<Settings> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY]?.let { decode(it) } ?: Settings()
    }

    suspend fun get(): Settings = settings.first()

    /** Blocking read for early service paths (VPN start before any UI). */
    fun getBlocking(): Settings = kotlinx.coroutines.runBlocking { get() }

    suspend fun save(input: Settings): SettingsStore.Saved {
        val sanitized = SettingsValidator.sanitize(input)
        context.settingsDataStore.edit { prefs ->
            prefs[KEY] = json.encodeToString(Settings.serializer(), sanitized.settings)
        }
        return SettingsStore.Saved(sanitized.settings, sanitized.fixes)
    }

    private fun decode(raw: String): Settings = runCatching {
        json.decodeFromString(Settings.serializer(), raw)
    }.getOrDefault(Settings())

    data class Saved(val settings: Settings, val fixes: List<String>)

    companion object {
        private val KEY = stringPreferencesKey("settings_doc")

        @Volatile
        private var shared: SettingsStore? = null

        fun shared(context: Context): SettingsStore =
            shared ?: synchronized(this) {
                shared ?: SettingsStore(context.applicationContext).also { shared = it }
            }
    }
}
