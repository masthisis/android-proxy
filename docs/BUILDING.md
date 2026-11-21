# Building NeuroRift for Android

Everything builds with one Gradle command — the Go core wrapper is compiled
by gomobile into an AAR as part of the normal build, then consumed by the app
module.

## Prerequisites

| Tool | Notes |
| --- | --- |
| JDK 21 | `java -version`; Gradle 9.6 requires 17+ |
| Android SDK | Platform 37, Build-Tools 36.0.0, Platform-Tools; set `ANDROID_HOME` or `sdk.dir` in `local.properties` |
| NDK r28 | needed for the Go core (gomobile) — SDK Manager → NDK (Side by side) |
| Go 1.27+ | `go version` |
| gomobile | `go install golang.org/x/mobile/cmd/gomobile@latest && go install golang.org/x/mobile/cmd/gobind@latest && gomobile init` |

## Full build (core AAR + APK)

```bash
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

What happens under the hood:

1. `:app:buildCoreAar` — runs `gomobile bind -target=android -androidapi=26
   -o core/build/nrcore.aar` inside `core/` (Go module `nrcore`, embedding
   mihomo, pinned via `core/go.mod`).
2. `:app` consumes the AAR (`implementation(files(...))`) and compiles the
   Kotlin app.
3. R8 full-mode + resource shrinking runs for release builds.

To skip the Go build when iterating on UI only (a pre-built AAR must exist):

```bash
./gradlew assembleDebug -PskipCore=true
```

## Release build

```bash
./gradlew assembleRelease            # debug-signed if no keystore is configured
```

Signing: provide `KEYSTORE_PATH` (or `NEURORIFT_KEYSTORE`), `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD` via `gradle.properties` or environment; otherwise
release APKs fall back to debug signing (fine for GitHub distribution, see
SECURITY.md).

Per-ABI splits: the release build produces `arm64-v8a`, `armeabi-v7a`,
`x86_64` and a `universal` APK. CI (`.github/workflows/release.yml`) builds
and publishes them with SHA256 checksums on every `vX.Y.Z` tag.

## Building the core AAR alone

```bash
./gradlew :app:buildCoreAar
# output: core/build/nrcore.aar
```

Directly with gomobile (what the Gradle task wraps):

```bash
cd core
gomobile bind -target=android -androidapi=26 -o build/nrcore.aar .
```

## Versioning

- The tag drives the release: `git tag vX.Y.Z && git push origin vX.Y.Z`.
- CI stamps `versionName`/`versionCode` from the tag.
- Locally the default is `1.0.0-dev`.

## Troubleshooting the build

| Symptom | Fix |
| --- | --- |
| `gomobile: command not found` | install gomobile + gobind, ensure `$(go env GOPATH)/bin` is on PATH |
| `no android NDK found` | install NDK via SDK Manager; gomobile reads `ANDROID_HOME/ndk-bundle` or the highest `ndk/<ver>` |
| `SDK location not found` | create `local.properties` with `sdk.dir=/path/to/sdk` or export `ANDROID_HOME` |
| gomobile bind OOM | `export GOFLAGS=-mod=mod; gomobile bind -work` and check disk; CI uses 7 GB runners |
| Kotlin/Compose version clash | the Compose compiler plugin version **must** equal the Kotlin version in `gradle/libs.versions.toml` |
