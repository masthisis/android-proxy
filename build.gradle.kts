// Root build file: plugin versions come from the version catalog; modules
// apply them themselves. AGP 9 ships built-in Kotlin — the org.jetbrains.kotlin
// .android plugin is intentionally NOT applied anywhere.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
