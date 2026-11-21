# NeuroRift for Android

[![CI](https://github.com/masthisis/android-proxy/actions/workflows/ci.yml/badge.svg)](https://github.com/masthisis/android-proxy/actions/workflows/ci.yml)
[![Release](https://github.com/masthisis/android-proxy/actions/workflows/release.yml/badge.svg)](https://github.com/masthisis/android-proxy/actions/workflows/release.yml)
[![Latest release](https://img.shields.io/github/v/release/masthisis/android-proxy)](https://github.com/masthisis/android-proxy/releases/latest)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=android&logoColor=white)](https://developer.android.com/compose)
[![mihomo](https://img.shields.io/badge/mihomo-1.19-00ADD8)](https://github.com/MetaCubeX/mihomo)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Code of Conduct](https://img.shields.io/badge/Code_of_Conduct-Contributor_Covenant-4051b5)](CODE_OF_CONDUCT.md)

**The Android sibling of [NeuroRift](https://github.com/masthisis/win-proxy) — a fast, modern proxy client powered by [mihomo](https://github.com/MetaCubeX/mihomo) (Clash.Meta).**

NeuroRift for Android brings the complete NeuroRift cockpit to Android with the same philosophy: the
app is a *supervisor and orchestrator* — mihomo (embedded in-process, the same proven core as the
Windows client) carries 100% of the traffic — while the app generates configs, drives mihomo's local
REST control plane, and renders a clean, modern Jetpack Compose UI.

```
Kotlin + Compose Material 3  •  mihomo core (in-process, via gomobile)  •  VpnService tun
```

## Why

| Problem with typical Android Clash clients | NeuroRift's answer |
| --- | --- |
| WebView / hybrid shells, heavyweight UI | Fully native **Kotlin + Jetpack Compose (Material 3)** — no webview, no cross-platform overhead |
| Stale or forked cores | The **same mihomo engine as NeuroRift for Windows**, embedded in-process (CMFA-style), not a black box |
| Subscription import limited to one format | Every share-link format (`vmess:// vless:// trojan:// ss:// ssr:// hysteria2:// tuic:// anytls:// socks5:// http(s)://`, base64 lists, Clash YAML, sing-box JSON) |
| No real speed testing | Multi-stream download **and** upload throughput tests per node through a dedicated side-channel that never disturbs your connection |
| No automatic failover | Watchdog: probes the active node, counts strikes, switches to the best candidate with cooldown-based flap protection |
| Per-app tunneling missing or awkward | First-class split tunneling (allowlist/denylist) on top of the standard Android VPN dialogs |
| No desktop sibling | **Feature parity tracking with NeuroRift for Windows** — same operating model, same release discipline, same docs structure |

## Feature highlights

- **VPN layer**: Android `VpnService` with direct fd hand-off to mihomo's tun listener (the
  ClashMetaForAndroid approach — no tun2socks hop), socket protection via `VpnService.protect`,
  per-app split tunneling, Always-on VPN + kill switch support, foreground notification with live
  status and quick actions.
- **Live cockpit**: traffic graph, active connections table (with per-connection close), core log
  stream, rules view, mode switching (rule/global/direct) without a reload.
- **Profiles**: subscription URLs, clipboard content, local files — auto-detected; per-profile
  auto-update schedule with hot provider swap **without dropping connections**.
- **Latency + speed testing**: batch URL-tests via the core API; per-node throughput (Mbps down/up)
  through the hidden `⚡ NR SpeedTest` group + dedicated listener — the exact mechanism the Windows
  client uses.
- **Failover watchdog**: configurable interval / strikes / cooldown; full event stream to the UI.
- **Full Clash passthrough**: profiles with `proxy-groups`/`rules`/`dns`/`sniffer` are spliced
  verbatim into the runtime config — your existing configs work unchanged.
- **Bilingual UI**: English + Farsi (RTL-aware).
- **Material You**: dynamic color, dark theme, edge-to-edge.

## Download

Ready-to-install APK builds are published automatically by CI — grab the latest release from
[Releases](https://github.com/masthisis/android-proxy/releases/latest). Every release ships:

- `NeuroRift-<version>-android-arm64-v8a.apk` — **recommended** for modern devices (2014+)
- `NeuroRift-<version>-android-universal.apk` — every architecture in one file
- `checksums-android.txt` — SHA256 sums to verify the downloads

Build it yourself instead? See [docs/BUILDING.md](docs/BUILDING.md).

## Install (Android 8.0+)

1. Download the arm64-v8a APK from [Releases](https://github.com/masthisis/android-proxy/releases/latest)
   and open it (allow *Install unknown apps* for your browser/file manager — the builds are unsigned
   for Play, self-signed for GitHub).
2. Launch **NeuroRift**, add your subscription (**Profiles → Add from URL**), pick a node, tap the
   **connect** switch. Android will ask for the VPN permission dialog once — accept it.
3. Done. Everything stays on the device: no telemetry, no remote config.

Uninstalling removes the app and its data. Updating: the app checks GitHub for new releases and can
download and hand the new APK to the system installer.

## Documentation

| Doc | What's inside |
| --- | --- |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | System design: layers, data flow, config generation strategy, dependency rules |
| [docs/CONTRACT.md](docs/CONTRACT.md) | The exact UI ↔ service ↔ core API: services, flows, events, payload schemas |
| [docs/FEATURES.md](docs/FEATURES.md) | Feature matrix vs ClashMetaForAndroid / FlClash / Hiddify / the Windows client |
| [docs/BUILDING.md](docs/BUILDING.md) | Build instructions (Android Studio, CLI, core AAR, cross-compile) |
| [docs/TESTING.md](docs/TESTING.md) | Test philosophy; unit + Robolectric + real-core integration; testing with your own subscription |
| [docs/ROADMAP.md](docs/ROADMAP.md) | What's next (tracked as issues labelled `v1.1.0` / `v1.2.0` / `v2`) |
| [docs/WORKFLOW.md](docs/WORKFLOW.md) | The operating contract: issue → branch → PR → merge → release |
| [docs/AI_DEVELOPER_GUIDE.md](docs/AI_DEVELOPER_GUIDE.md) | Conventions, ready-to-use task prompts, pitfall catalog |
| [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md) | Open-source components & licenses |

## Sponsors of speed

The heavy lifting (protocol negotiation, routing, TLS) is done by **mihomo** — the same MIT-licensed,
actively maintained Clash.Meta core used by NeuroRift for Windows, here compiled as an Android
library and embedded in-process. The app talks to it over its local REST API and never proxies
traffic itself, which is why connection performance is identical to running mihomo directly.

## License

MIT — see [LICENSE](LICENSE). mihomo and every bundled open-source component keep their own
licenses, listed in [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md).
