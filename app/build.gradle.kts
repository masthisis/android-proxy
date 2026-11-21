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

// ---------------------------------------------------------------------------
// Go core AAR (issue #7): core/ (Go module `nrcore`, embedding mihomo) is
// compiled by gomobile into an AAR that this module consumes. One command
// builds the whole chain: ./gradlew assembleDebug  ==  Go -> AAR -> APK.
//
// -PskipCore=true skips the Go BUILD only: an existing AAR is still packaged
// (build the AAR once with `gomobile bind`, then iterate on UI fast).
// -PcoreTarget=android/arm64 limits the ABI set for constrained machines
// (CI keeps the default: all four ABIs).
//
// The Go build requires go, gomobile, gobind, javac on PATH and the NDK
// under ANDROID_HOME (see docs/BUILDING.md).
// ---------------------------------------------------------------------------
val skipCore = (project.findProperty("skipCore") as String?) == "true"
val coreAarFile = rootProject.layout.projectDirectory.file("core/build/nrcore.aar")
val coreTarget = (project.findProperty("coreTarget") as String?) ?: "android"

val buildCoreAar by tasks.registering(Exec::class) {
    workingDir = rootProject.file("core")
    commandLine(
        "gomobile", "bind",
        "-target=$coreTarget",      // android = arm64-v8a, armeabi-v7a, x86, x86_64
        "-androidapi=26",
        "-ldflags=-w -s",           // strip debug info: the AAR ships in every APK
        "-o", coreAarFile.asFile.absolutePath,
        ".",
    )
    // Source inputs only — core/build is the task output and must not be an
    // input too.
    inputs.files(rootProject.fileTree(rootProject.file("core")) { exclude("build") })
    outputs.file(coreAarFile)
    doFirst {
        coreAarFile.asFile.parentFile.mkdirs()
        val inPath = listOf("gomobile", "go").any { cmd ->
            runCatching {
                val which = ProcessBuilder("which", cmd).start()
                which.waitFor() == 0
            }.getOrDefault(false)
        }
        if (!inPath) {
            throw GradleException(
                "gomobile/go not found on PATH - install them (docs/BUILDING.md) " +
                    "or pass -PskipCore=true"
            )
        }
    }
}

if (!skipCore) {
    tasks.matching { it.name.startsWith("preBuild") }.configureEach {
        dependsOn(buildCoreAar)
    }
} else if (coreAarFile.asFile.exists()) {
    logger.lifecycle("NeuroRift: skipCore=true - packaging existing core AAR (${coreAarFile.asFile.name})")
}

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
    if (!skipCore || coreAarFile.asFile.exists()) {
        // Go core wrapper (mihomo embedded). The generated Kotlin/Java API
        // lives in package `nrcore` (Nrcore, ProtectHook, UidResolver).
        implementation(files(coreAarFile))
    }
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
