# Architecture

This document is the authoritative map of NeuroRift for Android's design. It
is written for both human maintainers and AI agents. The design deliberately
mirrors the Windows sibling ([win-proxy/ARCHITECTURE.md](https://github.com/masthisis/win-proxy/blob/main/docs/ARCHITECTURE.md))
so that both codebases feel identical to work on.

## 1. Bird's-eye view

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  NeuroRift.apk (Kotlin, Jetpack Compose Material 3)                          │
│                                                                              │
│  ┌──────────────┐  StateFlow  ┌──────────────────────────────────────────┐  │
│  │  Compose UI  │◄────────────│  ViewModels + service layer (Kotlin)     │  │
│  │  (Material3) │   events    │  Engine/Profiles/Proxy/Test/Settings/    │  │
│  └──────────────┘◄────────────│  Update services — thin facades          │  │
│         │          Room/      └──────────────┬───────────────────────────┘  │
│         │          DataStore                 │ DI (Hilt)                    │
│  ┌──────▼───────────────────────────────────▼───────────────────────────┐  │
│  │  Domain/* — all business logic, zero Android-UI imports               │  │
│  │                                                                       │  │
│  │  settings ─ profile ─ engine ─ nettest ─ failover                     │  │
│  │      └──────┬──────────┤                                               │  │
│  │             │          events (SharedFlow bus) + atomic persistence    │  │
│  └─────────────┼──────────┼───────────────────────────────────────────────┘  │
│                │          │                                                 │
│  ┌─────────────▼───┐   ┌──▼───────────────────────────────────────────┐   │
│  │ TunService      │   │ Control plane (Kotlin): REST client against  │   │
│  │ (VpnService)    │   │ mihomo external controller, config generator │   │
│  │ fd ─────────────┼──►│ provider materializer, stream consumers      │   │
│  │ protect()/uids  │   │ 127.0.0.1:<controller>, random secret        │   │
│  └─────────────────┘   └──────────────┬───────────────────────────────┘   │
│                                       │ JNI (gomobile-bind AAR)           │
│                        ┌──────────────▼───────────────────────────────┐   │
│                        │  nrcore (Go library, in-process)             │   │
│                        │  mihomo v1.19.34 — 100% of the data plane    │   │
│                        │  tun listener bound to the VpnService fd     │   │
│                        │  outbound sockets via VpnService.protect()   │   │
│                        │  external controller enabled in config       │   │
│                        └──────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────────┘
```

**The single most important design decision** (inherited from the Windows
client): the app is a *supervisor and orchestrator*, never a proxy itself.
mihomo is embedded in-process (via a small Go wrapper compiled with gomobile
into an AAR) and carries 100% of the traffic; the app communicates with it
exclusively through its external-controller REST API and by regenerating its
YAML config. The only platform-specific goop is the Android VPN layer: the
`VpnService` fd is handed to mihomo's tun listener (the proven
ClashMetaForAndroid mechanism — `LC.Tun.FileDescriptor`), and every outbound
socket mihomo opens is routed through `VpnService.protect()` via mihomo's
`dialer.DefaultSocketHook`.

This gives native-core performance, the full protocol breadth of mihomo
(VLESS/REALITY, Hysteria2, TUIC, AnyTLS, WireGuard…), clean state management,
and a control plane that is **line-for-line the same contract as the Windows
app**.

## 2. Module & package responsibilities

| Component | Responsibility | Key patterns |
| --- | --- | --- |
| `core/` (Go, module `nrcore`) | Thin wrapper around mihomo: init, apply config, start/stop tun from fd, socket-protection hook, uid resolver, fd-limit guard | Facade over mihomo hub/listener |
| `app/…/core` (Kotlin) | JNI bridge calls into the AAR; wraps start/stop lifecycle | Adapter |
| `app/…/vpn` | `TunService` (VpnService): fd creation, split tunneling, notification, always-on | Android service |
| `app/…/engine` | Config generator, REST client, stream consumers, engine state machine | Supervisor, Factory |
| `app/…/profile` | Subscription fetching, format detection, share-link parsing, provider materialization, Room registry | Strategy, Repository |
| `app/…/nettest` | Latency probes + multi-stream speed tests | — |
| `app/…/failover` | Node-health watchdog with strike/cooldown policy | — |
| `app/…/settings` | DataStore-backed settings model, validation | Facade |
| `app/…/ui` | Compose screens, theme, navigation, i18n (EN/FA) | MVVM |

**Dependency rule**: domain packages never import Compose or Android view
classes. The `ui` package is the only Compose-aware layer; `vpn` is the only
VpnService-aware layer. Everything else stays unit-testable on the JVM.

## 3. The two profile kinds (core UX architecture — same as Windows)

Subscriptions come in two shapes, handled with different mechanisms:

### Kind "nodes" — URI lists & proxies-only YAML
- Materialized as a mihomo **file proxy-provider** (`providers/sub-<id>.yaml`).
- Generated groups reference providers with `include-all: true`, so the group
  membership follows the file automatically.
- **Subscription updates never restart the core**: the app rewrites the
  provider file and pokes `PUT /providers/proxies/<name>`. Existing
  connections survive; node lists swap instantly.

### Kind "clash" / "local" — full Clash YAML
- The user document is parsed and **spliced**: the app overlay (controller
  address, secret, ports, speed-test listener + hidden group, `store-selected`)
  is injected while everything the user wrote (groups, rules, dns, sniffer,
  hosts…) is preserved verbatim.
- Switching/updating reloads the config via `PUT /configs` (hot, keeps
  selections via mihomo's `store-selected`), falling back to a core restart if
  the reload is rejected.

## 4. Runtime config overlay (always injected)

```
external-controller: 127.0.0.1:<ports.controller>   # random secret
mixed-port: <ports.mixed>                            # main inbound
listeners:
  - name: nr-speedtest          # dedicated inbound used by nettest
    type: mixed, port: <ports.speedTest>
    proxy: "⚡ NR SpeedTest"     # routes straight into the hidden group
proxy-groups: + "⚡ NR SpeedTest" (select, include-all)  # + Node Select / NR Auto for nodes-kind
profile: { store-selected: true, store-fake-ip: true }
unified-delay, tcp-concurrent: true
geox-url: jsdelivr mirrors   geo-auto-update: weekly
```

The tun listener itself is **not** part of the YAML — it is created directly
in Go code (`sing_tun.New(LC.Tun{FileDescriptor: fd, AutoRoute: false,
AutoDetectInterface: false, …}, tunnel.Tunnel)`) after the config is applied,
because the fd can only come from the running `VpnService`. DNS hijack points
at the app's fake DNS address; routes are set by `VpnService.Builder`, not by
mihomo.

The speed-test listener is the key to non-disruptive testing: selecting a node
in the hidden group and driving traffic through this listener measures exactly
that node, without touching the main selector, without a config reload, and
without any rules involvement. The same path is reused by the failover
watchdog for single-node probes.

## 5. Data flow examples

**Import subscription** — `ProfileService.addFromUrl` → fetch (direct or via
mixed-port proxy) → `DecodeContent` (detect format → parse links / parse Clash
YAML / parse sing-box JSON → sanitize → dedupe) → materialize provider file →
Room registry persist → `Engine.applyFromProfiles` (generate config → write
file → hot reload or restart) → flows `profilesChanged`, `subProgress`.

**Start VPN** — `TunService` prepares `VpnService` → establishes the tun
interface (`addAddress`, `addRoute`, `setMtu`, `setBlocking`) → passes the fd
to `nrcore.start(fd, configPath)` → mihomo applies the generated config and
binds the tun listener → REST streams (traffic/logs/connections) start →
notification shows live status.

**Speed test** — `TestService.speedTest(node)` → select node in the hidden
group via API → N parallel HTTP transfers through `127.0.0.1:<speedTest>` with
progress every 500 ms → download phase, then upload phase → result persisted
to Room + `speedDone` flow.

**Failover** — `FailoverWatchdog.run` ticks every `failover.intervalSec` →
resolves the primary group → probes the current node through the speed-test
path → on `strikes` consecutive failures (outside cooldown) switches to the
candidate with the best known latency → `failoverEvent` flow.

## 6. Persistence layout

```
<filesDir>/neurorift/
  settings.json (DataStore)    app settings
  profiles.json (DataStore)    profile registry metadata
  profiles.db   (Room)         speed/latency history
  logs/                         app log (also mirrored in-memory for the UI)
  providers/sub-*.yaml          materialized node providers (hot-swappable)
  clash/<id>.yaml               raw Clash YAML profiles
  core/                         config.yaml, cache.db, geo files
```

The core is initialized with `constant.SetHomeDir(<filesDir>/neurorift/core)`
and `SAFE_PATHS` confined to that dir, mirroring the Windows layout so the
same config-generation logic maps 1:1.

## 7. Event catalog

All service → UI events flow: domain layer → `SharedFlow` bus → ViewModels →
Compose. Event names mirror the Windows `internal/nr` constants and are
defined **once** in `Events.kt`, documented in [CONTRACT.md](CONTRACT.md).

## 8. Concurrency model

- Every manager is a mutex/actor-guarded facade; no shared mutable state leaks.
- The core is supervised: backoff restarts, crash-loop guard after 5 restarts
  / 2 min.
- Background loops (subscription scheduler, failover watchdog, stream
  consumers) share one `CoroutineScope` cancelled at VPN shutdown; all of them
  are nil-safe and idle-cheap.
- Speed tests are serialized by design (one at a time; cancelable) so
  measurements don't pollute each other.
- UI streams are coalesced exactly like the Windows client: traffic 1 Hz,
  logs 0.5 Hz batches, connections 1 Hz — keeping recomposition cheap.

## 9. Performance notes

- The data plane is mihomo's — native Go compiled for all four ABIs, zero
  interpretation layer, no tun2socks hop.
- The control plane talks to a local REST API; streaming endpoints are
  consumed with chunked JSON decoding and coalesced before they hit Compose.
- The Go AAR is stripped (`-ldflags "-w -s"`); R8 full-mode + resource
  shrinking is always on for release builds.
- The UI is a plain Compose app: no heavyweight component library, no
  webview, no reflection-based serialization in hot paths.

## 10. Security

- The external controller binds `127.0.0.1` only, with a random 32-char
  secret generated on first run.
- Provider paths are confined to the app data dir; the core runs with
  `SAFE_PATHS` limited to that dir.
- No telemetry, no remote config, everything stays on the device.
- Subscription URLs/tokens are stored only in private app storage.
- The VPN socket hook never logs addresses; reports (if ever enabled) are
  redacted like the Windows client's.
