# Backend ↔ UI Contract

The authoritative API surface between the Kotlin service layer and the
Compose UI (and between the app and the embedded core). This document mirrors
its Windows sibling and grows with every feature issue; it must be updated in
the same PR whenever a service method or event changes.

## Core integration (app ↔ nrcore ↔ mihomo)

| Function | Notes |
| --- | --- |
| `Nrcore.init(homeDir, version)` | sets mihomo home dir + version string; installs the socket-protection and uid-resolver hooks (wired to `VpnService.protect()` / `LookupUid`) |
| `Nrcore.applyConfig(yaml)` | parses + applies the generated runtime config (inbound mixed port, external controller, providers, groups, rules) |
| `Nrcore.startTun(fd, stack, gateway, portal, dns)` | creates the tun listener from the VpnService fd (`LC.Tun.FileDescriptor`), `AutoRoute=false` (routes are set by the VPN builder), DNS hijack to the fake-ip DNS address |
| `Nrcore.stopTun()` / `Nrcore.stop()` | closes the tun listener / shuts the core down |
| socket hook (Go) | `dialer.DefaultSocketHook` → fd-limit guard + `protect(fd)` callback into Kotlin |
| uid resolver (Go) | `process.DefaultPackageNameResolver` → per-app rule support |

## Control plane (Kotlin ↔ mihomo REST)

Identical to the Windows client: the app generates the runtime YAML with
`external-controller: 127.0.0.1:<controller port>` + random secret and speaks
the mihomo REST API:

| Endpoint | Use |
| --- | --- |
| `GET /version` | core version for About + status |
| `GET/PATCH /configs` | mode switching (rule/global/direct), live patches |
| `PUT /configs?force=true` | hot config reload (profile switch) |
| `GET /proxies` | groups + nodes + delays |
| `PUT /proxies/<group>` | select a node |
| `GET /proxies/<node>/delay` | latency probes |
| `PUT /providers/proxies/<name>` | hot provider swap (subscription update without restart) |
| `GET /connections` (poll 1 Hz) | connections snapshot |
| `DELETE /connections/<id>` / `DELETE /connections` | close one / all connections |
| `GET /rules` | active routing rules |
| `GET /traffic` (stream) | live down/up — chunked, coalesced to 1 Hz |
| `GET /logs` (stream) | core log — chunked, batched 0.5 Hz, ring ≤ 500 |
| `GET /memory` (stream) | core memory |

**Streaming rule (pitfall P4):** `/traffic`, `/logs`, `/memory` are infinite
streams — never buffer them; consume chunked with per-read timeouts only, and
coalesce before the UI.

## Service layer (UI-facing)

Documented per feature issue as it lands; the table below tracks the final
surface. Payload shapes follow the Windows CONTRACT.md naming so both
codebases stay conceptually identical.

| Service | Methods |
| --- | --- |
| `EngineService` | `status`, `start`, `stop`, `restart` — state machine `stopped → starting → running ⇄ updating`, `failed` terminal |
| `ProfileService` | `list`, `addFromUrl`, `addFromText`, `addFromFile`, `update`, `updateAll` (issue #18: parallel refresh, bounded concurrency 4, returns `UpdateSummary{updated, failed, skipped, errors}`), `remove`, `setActive`, `setUnionActive`/`unionIds` (issue #23: subscription union — union-active NODES providers join the active profile's Node Select `use` list, primary unchanged), `rename`, `setInterval`, `nodes`, `getRaw`, `updateRaw` (raw YAML editor, issue #17: validate-before-write, atomic, hot re-apply for the active profile) |
| `ProxyService` | `groups`, `selectProxy`, `testGroup`, `testAllNodes`, `testNode`, `mode`/`setMode`, `rules`, `closeConnection`, `closeAllConnections` |
| `TestService` | `speedTest`, `speedTestQueue`, `cancelSpeedTest`, `speedHistory`, `latencyHistory`, `testProfileLatency` |
| `SettingsService` | `get`, `save` (sanitized) |
| `UpdateService` | `check`, `download`, `install` (v1.1.0) |
| `FailoverService` | `status`, `testNow` (v1.1.0) |

## UI surfaces (Android)

Compose screens consume the service/event layer; the mapping landed with the
issues in parentheses:

| Screen | Consumes |
| --- | --- |
| Home / cockpit (#13, #34) | `netTraffic` (60-sample window), engine status, profile registry; quick routes to Connections/Logs/Rules; lifecycle-aware sampling (pauses when hidden) |
| Proxies (#12) | `ProxyService.groups/selectProxy/testGroup/testNode/setMode`, `groupsRefresh` |
| Connections (#13) | `netConnections` poll + `closeConnection`/`closeAllConnections` |
| Logs (#13) | `coreLogs` batches + ring tail, level filter, copy/clear |
| Rules (#13) | `GET /rules` grouped + search, live mode patch |
| Profiles (#11) | `ProfileService` CRUD + `subProgress` |

## Events (service → UI flows)

Names mirror the Windows event catalog 1:1:

| Flow | Payload |
| --- | --- |
| `coreStatus` | engine status |
| `coreDownload` | core download progress (percent, detail) |
| `profilesChanged` | null — re-fetch profile list |
| `subProgress` | `{profileId, phase, detail}` — fetching/parsing/writing/done/error |
| `latencyResults` | `{group, results[], at}` |
| `speedProgress` / `speedDone` | live speed-test progress / final result |
| `failoverEvent` | probe-failed / switched / probe-ok |
| `netTraffic` | `{down, up, memory}` 1 Hz |
| `netConnections` | connections snapshot 1 Hz |
| `coreLogs` | `string[]` batched |
| `groupsRefresh` | reason — config hot-reloaded |
| `appUpdate` | updater status (v1.1.0) |

## Settings document

Mirrors the Windows settings shape, minus Windows-only keys (window geometry,
system-proxy guard, portable mode) and plus Android-only keys:

```
language: "system"|"en"|"fa"        startOnBoot: bool
allowLan: bool                      mode: "rule"|"global"|"direct"
ports: {mixed: 7897, controller: 9097, speedTest: 19097}
test: {url, timeoutMs, speedUrl, speedUploadUrl, speedStreams(1..16),
       speedMaxSeconds, speedMaxMb, uploadTest}
failover: {enable, intervalSec(≥15), timeoutMs, strikes, cooldownSec}
dns: {override, nameservers: string[]}
tun: {stack: "system"|"gvisor"|"mixed", mtu}
subscription: {defaultIntervalH, updateViaProxy}
log: {appLevel, coreLevel}
routingOverrides: [{pattern, type, target}]   (v1.1.0)
updates: {autoCheck (default true), includePrerelease (default false)}
splitTunneling: {mode: "allowlist"|"denylist"|"off", apps: string[]}
bypassDomains: string[]
controllerSecret: string (auto-generated; read-only in practice)
```
