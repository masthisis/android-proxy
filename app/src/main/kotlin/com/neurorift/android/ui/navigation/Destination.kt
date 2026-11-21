package com.neurorift.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.ui.graphics.vector.ImageVector
import com.neurorift.android.R

/**
 * Bottom navigation destinations — mirrors the Windows view inventory.
 * Connections / Logs / Rules are secondary routes reachable from the
 * Dashboard (see [SecondaryRoute]); their screens land with the cockpit
 * issue (#13) and keep these routes stable.
 */
enum class Destination(
    val route: String,
    val icon: ImageVector,
) {
    Dashboard("dashboard", Icons.Filled.Home),
    Proxies("proxies", Icons.Filled.Public),
    Profiles("profiles", Icons.Filled.ViewList),
    Test("test", Icons.Filled.Speed),
    Settings("settings", Icons.Filled.Settings),
    ;

    /** Localized label string resource id — kept next to the route. */
    val labelRes: Int
        get() = when (this) {
            Dashboard -> R.string.nav_dashboard
            Proxies -> R.string.nav_proxies
            Profiles -> R.string.nav_profiles
            Test -> R.string.nav_test
            Settings -> R.string.nav_settings
        }
}

/** Secondary routes beyond the bottom bar. */
object SecondaryRoute {
    const val CONNECTIONS = "connections"
    const val LOGS = "logs"
    const val RULES = "rules"
    const val SPLIT_TUNNELING = "split-tunneling"

    /** Raw profile YAML editor route pattern (#17); see [yamlEditor]. */
    const val YAML_EDITOR = "yaml/{profileId}"

    /** Builds the editor route for a concrete profile id. */
    fun yamlEditor(profileId: String): String = "yaml/$profileId"
}
