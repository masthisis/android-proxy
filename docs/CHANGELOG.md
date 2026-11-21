# Changelog

All notable changes to NeuroRift for Android are documented here. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning
is [SemVer](https://semver.org/). Releases are cut from `main` tags.

## [Unreleased]

### Changed

- **Proton VPN visual identity** (issue #34): the app adopts the Proton VPN
  Android design language — fixed brand palette (accent violet `#6D4AFF`,
  connected green `#27DDB1`/`#1C9C7C`, alert red `#DC3251`, disconnected
  pink `#F08FA4`, dark layering `#0C0C14 → #1C1B24 → #292733`; token values
  referenced from the official ProtonVPN/android-app colors.xml), dynamic
  Material You color removed, Proton-style Home (status headline, 220dp
  circular connect button with pulse while connecting, active-profile card),
  restyled top bar, bottom navigation (globe icon for Proxies) and Profiles
  cards (20dp rounded, violet active border, violet FABs). All new strings
  land in EN + FA.

### Added

- **Live cockpit** (issue #13): the Proton-style home now shows the live
  60-sample traffic graph (Canvas area chart, 1 Hz coalesced ticks, peak
  normalized, core memory readout), quick access to the secondary cockpit
  views, and lifecycle-aware sampling (traffic/connections/log collectors
  pause when the screen is hidden — Windows #76 groundwork). New screens:
  Connections (1 Hz snapshot poll, per-connection close, confirmed
  close-all, session totals), Logs (batched stream + ≤500 tail, level
  filter, copy, clear) and Rules (`GET /rules` grouped by type, search,
  live mode patch without reload). JVM tests pin the windowing and byte
  formatting; CONTRACT.md gains the Android UI-surface mapping.
- **Settings document + surface** (issue #14): the full CONTRACT settings
  document as one serializable data class with Windows-mirroring validation
  clamps (ports, timeouts, speed-test bounds, failover interval/strikes,
  TUN MTU/stack, enum fallbacks, port-conflict resolution, secret preserved),
  DataStore persistence (migration-safe defaults, corrupt-doc reset), engine
  mapping (`Settings.toEngineSettings`) and automatic re-apply on save.
  Settings screen with grouped Proton-styled sections (General/Mode/Ports/
  Test/DNS/TUN/Subscription/Log/Updates/Split tunneling/About, EN + FA),
  per-app language override (API 33+ locale manager), and the split-tunneling
  app picker (v2rayNG per-app proxy parity) wired into the VPN builder via
  the pure `AppFilter` decision table. The VPN layer now reads the persisted
  document (secret stable across restarts) and hands the profile registry to
  the engine (`ProfileRegistry`).
- **Groups dashboard** (issue #12): Proxies screen — mode chips
  (rule/global/direct live patch), search + sort (delay/name) controls,
  group cards (Selector/URLTest/Fallback/LoadBalance) with per-node delay
  chips colored by the Windows NodeChip palette (<200 green, <500 orange,
  else red), hidden internal groups collapsed by default, single-node probe
  through the hidden speed-test selector, one-shot batch URL tests via
  `/group/<name>/delay`, "test all nodes", selection persisted across hot
  reloads (store-selected), and `groups:refresh` refetch after profile
  switches (Windows bug #40 regression covered by JVM tests). Engine
  additions: `ProxyService`, `EngineRuntime` process registry,
  `/group/<g>/delay` + `/configs` mode endpoints on the REST client.
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
