package com.neurorift.android.profile

import android.content.Context

/**
 * Process-wide bridge from the VPN layer to the shared profile manager
 * (issue #14 wiring): TunService supplies the engine with the profile
 * metadata + active selection without holding a ViewModel.
 */
object ProfileRegistry {

    private fun manager(context: Context): ProfileManager = ProfileViewModel.manager(context)

    fun meta(context: Context): List<ProfileMeta> =
        runCatching { manager(context).meta() }.getOrElse { emptyList() }

    fun activeId(context: Context): String? =
        runCatching { manager(context).activeId() }.getOrElse { null }
}
