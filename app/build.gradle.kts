import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Version resolution: CI (release workflow) passes -PappVersion=X.Y.Z from the
// tag; local builds default to a dev version.
val appVersion: String = (project.findProperty("appVersion") as String?) ?: "1.0.0-dev"
val appVersionCode: Int =
    (project.findProperty("appVersionCode") as String?)?.toInt()
        ?: 1

android {
    namespace = "com.neurorift.android"
    // AGP 9 minor-SDK support: Compose 1.13 (BOM 2026.11) requires 37.1.
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.neurorift.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersion
    }

    base {
        archivesName = "NeuroRift"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Self-signed GitHub distribution: fall back to the debug key when
            // no release keystore is configured (see SECURITY.md + CI).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Per-ABI APKs are produced only for release distributions; CI debug
    // builds stay single-APK to keep artifact paths stable.
    splits {
        abi {
            isEnable = (project.findProperty("splitApk") as String?) == "true"
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
