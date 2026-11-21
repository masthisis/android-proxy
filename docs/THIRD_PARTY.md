# Third-party components & licenses

NeuroRift for Android depends on the following open-source components. Their
licenses are reproduced in the repository vendored copies or linked; this
file is updated in the same PR that introduces a new dependency.

| Component | License | Use | Source |
| --- | --- | --- | --- |
| [mihomo](https://github.com/MetaCubeX/mihomo) | MIT | proxy core (embedded, pinned via `core/go.mod`) | MetaCubeX |
| [sing-tun](https://github.com/metacubex/sing-tun) (via mihomo) | MIT | tun listener from the VpnService fd | MetaCubeX |
| Kotlin & Kotlinx (stdlib, coroutines, serialization) | Apache-2.0 | language + concurrency | JetBrains |
| Jetpack Compose / Material 3 / AndroidX (core, lifecycle, activity, navigation, room, datastore, work) | Apache-2.0 | UI + platform integration | Google |
| OkHttp | Apache-2.0 | REST client + stream consumption | Square |
| Hilt | Apache-2.0 | dependency injection | Google |
| gomobile (x/mobile) | BSD-3-Clause | Go → AAR binding | Go contributors |
| Robolectric | MIT | JVM Android tests | Robolectric |
| JUnit | EPL-1.0 | unit testing | JUnit |
| MockK / Turbine | Apache-2.0 | test utilities | — |

The mihomo license and notice are included here because the core is compiled
into the distributed APK:

> Copyright (c) mihomo contributors — MIT License. mihomo is a Clash.Meta
> fork; the original Clash is Copyright (c) Dreamacro — MIT License.
