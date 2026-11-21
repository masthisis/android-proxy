package com.neurorift.android.settings

import com.neurorift.android.engine.ConfigGen
import com.neurorift.android.profile.ProfileKind
import com.neurorift.android.profile.ProfileMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings -> engine mapping (issue #14): port changes must propagate into
 * the generated config (acceptance criterion).
 */
class EngineMappingTest {

    @Test
    fun `ports propagate into the generated config`() {
        val settings = Settings(
            ports = Settings.Ports(mixed = 18097, controller = 29097, speedTest = 39097),
            allowLan = true,
        )
        val yaml = ConfigGen.generate(
            settings = settings.toEngineSettings(),
            profiles = listOf(ProfileMeta(id = "p1", name = "P", kind = ProfileKind.NODES)),
            activeId = "p1",
            contentOf = { "" },
        )
        assertTrue(yaml.contains("mixed-port: 18097"))
        assertTrue(yaml.contains("external-controller: 127.0.0.1:29097"))
        assertTrue("allow-lan: true" in yaml)
        assertTrue(yaml.contains("19097")) // speed-test listener port
    }

    @Test
    fun `core log level follows the settings document`() {
        val settings = Settings(log = Settings.LogLevels(coreLevel = "warning"))
        val yaml = ConfigGen.generate(
            settings = settings.toEngineSettings(),
            profiles = emptyList(),
            activeId = null,
            contentOf = { "" },
        )
        assertEquals(true, yaml.contains("log-level: warning"))
    }
}
