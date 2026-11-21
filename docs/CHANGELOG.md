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

- **Subscription union** (issue #23): Profiles gain a union toggle per card
  (CallMerge action + a `union` chip) that joins a subscription into the
  union routing set — the `🚀 Node Select` group's `use` list extends from
  the active provider to the active provider + every union-active NODES
  provider in registry order (primary first), so multiple subscriptions are
  active as ONE routing set (Windows parity). Union providers stay
  materialized file providers without a health-check (only the primary keeps
  one); `activeId` semantics are unchanged (primary + additional provider
  sources). The flag persists migration-safely as `unionActive` in
  `profiles.json` and hot-reloads a running tunnel through the engine's
  `unionIdsProvider` plumbing (EngineManager/ConfigGen/TunService).
- **Node-health failover watchdog** (issue #16): while the tunnel runs, a
  watchdog probes the primary group's current node through the hidden
  speed-test selector once per `failover.intervalSec`; consecutive failures
  accumulate strikes and, after `failover.strikes` AND the
  `failover.cooldownSec` cooldown since the last switch have both elapsed,
  the group is switched to the member with the best known delay (catalog
  history, current node and DIRECT excluded) via `PUT /proxies/<group>`.
  Inside the cooldown failures are only counted — never switched — so a
  flapping node cannot thrash the selection. Every cycle emits a
  `failover:event` (`probe-ok` / `probe-failed` / `switched`) on the event
  bus, and the Test Center gains a Proton-style Failover status card
  (Watching/Off state, current node, strike counter, last switch, last-5
  event log, "Test now" immediate probe). JVM tests pin strike
  accumulation, success resets, the strikes+cooldown switch gate and the
  best-delay candidate pick with a virtual clock; all strings land in
  EN + FA.
- **Subscription auto-update + Update All** (issue #18): a WorkManager-backed
  scheduler (`SubscriptionScheduler`) enqueues the unique 30-minute periodic
  `subscription-sync` scan (KEEP policy, network-connected constraint) plus an
  expedited one-shot for immediate kicks; the worker refreshes every profile
  whose `intervalH` interval is due through the pure, JVM-tested
  `ScheduleMath` due check (deterministic ±10%-band jitter derived from the
  last sync — never later than the plain interval, never-synced profiles are
  immediately due). URL imports now inherit `subscription.defaultIntervalH`
  and fetches honour `subscription.updateViaProxy` through the app's own
  mixed inbound (injectable `proxyProvider` on the manager). Profiles gains
  an Update All action — parallel refresh of every URL-sourced profile with
  bounded concurrency 4, an `updated X · failed Y · skipped Z` summary row
  and a per-card "next" sync time; all strings land in EN + FA.
- **In-app self-update** (issue #20): the app updates itself from its own
  GitHub releases — `releases.atom` feed only (Windows parity, never
  api.github.com) parsed by a pure JVM-tested atom parser, prereleases gated
  behind `updates.includePrerelease`, newest release picked through a pure
  `Semver` comparison (v-strip, numeric triple, prerelease below its plain
  version). The last-seen version + check timestamp persist in
  `updatestate.json` (Windows parity: notify once per release), the
  device-ABI APK asset (`NeuroRift-<version>-android-<abi>.apk`, universal
  fallback) streams into the cache dir with live progress and is verified
  against the release `checksums-android.txt` (SHA256, mismatch → delete +
  error state), and a verified build hands off to the system installer via
  FileProvider (`REQUEST_INSTALL_PACKAGES`). Settings → Updates gains Check
  now + status + Install; the Dashboard shows an update banner chip while
  available/downloading/ready; `updates.autoCheck` checks at most every 6 h
  on app start. JVM tests pin Semver ordering, the asset naming/URL
  derivation and the atom parsing; strings land in EN + FA.
- **Automatic problem reports** (issue #25): the app installs a redacting
  uncaught-exception handler (`report/CrashReporter`) that writes a
  `crash-latest.txt` bundle (stack trace + app version + API level + model
  + thread name) with the controller secret, every `Bearer <token>`
  authorization and every subscription URL stripped before anything
  touches disk. On the next launch MainActivity offers a pre-filled
  GitHub issue (`issues/new`, title "Crash report (auto)", body capped to
  a browser-safe length) or dismiss. Redaction + truncation are pure JVM
  (device info composed at the install call site); tests pin secret /
  bearer / URL redaction, the ~4000-char report cap and the
  write → pending → clear lifecycle; strings land in EN + FA.
- **Background sampling pause** (issue #26): a process-lifecycle
  `SamplingGate` on the stream loops — traffic/log UI emissions pause while
  the app is backgrounded (5 s idle cadence instead of 1 Hz) and resume
  instantly on foreground; the cheap bookkeeping (traffic atomics, log
  ring) keeps running so reopening the app shows live data immediately.
- **Test Center** (issue #15): the Test tab grows into a real throughput
  lab — multi-stream download + upload speed tests routed through the
  hidden `⚡ NR SpeedTest` selector and its dedicated listener (node select
  → parallel GETs/POSTs via the local `127.0.0.1` proxy port), byte-cap
  presets (100/250/500 MB + settings max), live progress ticks every 500 ms
  with a cancel button (volatile flag + scope + socket-level cancel), speed
  results on the `speed:progress`/`speed:done` events, single-node +
  test-all latency probes, and a file-backed history store
  (`nettest-history.json`, atomic tmp+rename writes, latest speed per node,
  ≤50 latency samples per node with per-node Canvas sparklines). The screen
  degrades to the "connect first" state while the engine is down; JVM tests
  pin the Mbps math and the store; all strings land in EN + FA.
- **Per-domain routing overrides + raw YAML editor** (issue #17): the
  settings document gains a Routing section (pattern → target rows with
  type chips, add/remove dialog, ≤200 sanitized entries persisted through
  the validator) whose overrides are injected into every generated config
  between the LAN direct block and the MATCH catch-all — in both the
  app-owned and the spliced-clash shapes, mapped through a neutral
  engine-side `ConfigGen.RoutingRule`. The Connections screen quick-adds a
  DOMAIN-SUFFIX → DIRECT override for a live host from its row (settings
  save + tunnel hot reload without dropping connections), and Profiles gain
  an edit action opening a raw YAML editor (monospace surface, local
  Validate through the pure `YamlLint` snakeyaml linter, atomic save with
  pre-write validation, read-only view for nodes-kind provider files, and
  automatic engine hot re-apply for the active profile on a running
  tunnel). All new strings land in EN + FA.
- **Quick settings tile + boot autostart + notification controls** (issue
  #19): `VpnTileService` mirrors the engine state (active/inactive/
  unavailable) and deep-links into the app when the one-time VPN permission
  is still missing; `BootReceiver` honours `startOnBoot` on
  BOOT_COMPLETED / MY_PACKAGE_REPLACED (system Always-on VPN precedence
  respected); the foreground notification gains a mode-cycle action
  (rule → global → direct, persisted + live-patched) next to Disconnect —
  the Windows tray quick controls on Android.
- **Release hardening** (issue #21): tag-triggered release workflow gains
  an optional CI keystore (KEYSTORE_BASE64 secret, decoded without printing
  secrets; debug-key fallback stays the local default), plus a release
  smoke job that downloads every published asset, verifies all SHA256 sums,
  checks the universal APK for arm64-v8a/armeabi-v7a/x86_64 libraries and
  asserts the prerelease flag matches the tag suffix. v1.0.0 roadmap items
  checked off.
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
