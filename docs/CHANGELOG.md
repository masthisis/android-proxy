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

- **Profiles pipeline** (issue #11): full subscription import (URL,
  clipboard, local files) with content auto-detection (base64 link lists,
  plain link lists, Clash YAML with/without groups, sing-box JSON raw or
  base64-wrapped), every share-link format parsed (vmess/vless+t REALITY/
  trojan/ss SIP002+legacy+plugins/ssr/hysteria2 port-hopping/tuic v4+v5/
  anytls/socks/http), sanitize + name dedupe, provider materialization
  (providers/sub-*.yaml), atomic JSON registry, per-profile sync status +
  error badge, sub:progress events with never-silent warnings, Profiles
  screen (EN + FA).
- **Engine control plane** (issue #10): runtime-config generator (overlay:
  external controller + random secret, mixed port, hidden speed-test group +
  dedicated  listener, store-selected, unified-delay,
  tcp-concurrent, jsdelivr geo mirrors; nodes-kind providers with active-only
  health checks; clash/local splicing that preserves user content verbatim),
  ordered-YAML emitter, mihomo REST client (MockWebServer-tested, auth
  header, no closed-body bugs), dedicated no-timeout stream client with
  Windows-parity coalescing (traffic 1 Hz, logs 0.5 Hz batches, ring ≤500,
  lock-guarded), engine supervisor (hot reload + restart fallback, provider
  hot swap, mode patch, crash-loop guard 5 fails / 2 min), process-wide
  event bus mirroring the Windows event catalog.
- **App shell** (issue #9): bottom navigation with the five Windows-parity
  destinations (Dashboard/Proxies/Profiles/Test/Settings), stable secondary
  routes (Connections/Logs/Rules), edge-to-edge Material 3 shell, honest
  "lands with issue #N" placeholders, Dashboard base with the live VPN
  connect switch. Every user-facing string exists in EN + FA (RTL).
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
