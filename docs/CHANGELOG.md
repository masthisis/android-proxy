# Changelog

All notable changes to NeuroRift for Android are documented here. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning
is [SemVer](https://semver.org/). Releases are cut from `main` tags.

## [Unreleased]

### Added

- Repository bootstrap: Gradle 9.6 + AGP 9.5 + Kotlin 2.4 project skeleton,
  Jetpack Compose (Material 3) app shell placeholder, CI, issue templates,
  dependabot, and the documentation foundation.
- **Go core wrapper** (`core/`, module `nrcore`): mihomo v1.19.34 embedded
  in-process via gomobile AAR — `Init`/`ApplyConfig`/`StartTun`/`StopTun`/
  `Stop`/`Version` plus the `ProtectHook` (VpnService socket protection) and
  `UidResolver` (per-app rules) platform callbacks, and the fd-limit
  connection guard. Tun options enforce Android semantics (`AutoRoute=false`,
  `AutoDetectInterface=false`, fd hand-off). Unit tests for the tun option
  mapping, guard, and uid resolver; `go vet` + `go test` join CI.
- Gradle wiring: `:app:buildCoreAar` Exec task runs `gomobile bind` (all four
  ABIs) as part of every build; `-PskipCore=true` skips it. CI (test + build)
  and the release workflow install Go + gomobile and build the AAR.

### Added

- **VPN layer** (`vpn/TunService.kt`, issue #8): VpnService-based tunnel —
  one-time prepare flow, tun builder (172.19.0.x/30 + IPv6, MTU 9000,
  blocking fd), fd hand-off to the core, `protect()` socket hook, uid
  resolver (API 31+ connection-owner lookup), Always-on VPN support,
  foreground notification with live status + disconnect action. Engine state
  machine (`stopped → starting → running ⇄ stopping`, `failed`) with tests;
  temporary connect switch in the app shell until #9.

### Fixed

- Gradle `buildCoreAar` inputs no longer include the task's own output
  directory (`core/build`).
