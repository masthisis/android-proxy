# AI Developer Guide

This repository is maintained by humans *and* AI agents, exactly like the
Windows sibling. This file is the handbook: ground rules, pitfall catalog,
and extension recipes. Start here before touching the engine.

## Ground rules

1. **Read [WORKFLOW.md](WORKFLOW.md) first** — issue → branch → PR → merge →
   release. Never push directly to `main`.
2. **Never invent facts.** Verify against real code and real upstream docs;
   mihomo's config surface is large and its wiki is the authority.
3. **One issue = one branch = one PR.** Conventional Commits, imperative,
   referencing the issue.
4. **Tests with behaviour** (see [TESTING.md](TESTING.md)); bug fixes land
   with a regression test that fails without the fix.
5. **Docs in the same PR**: user-visible changes update README/docs/*;
   service/event changes update CONTRACT.md.
6. **No secrets, ever**: no subscription URLs, tokens, server addresses,
   keystore material. Fixtures use RFC 5737 addresses and example.com.
7. **Match the file's existing style** before editing (Kotlin formatting,
   import order); do not reformat unrelated lines.
8. Verify locally with `./gradlew testDebugUnitTest assembleDebug` before
   pushing; let CI be the second gate, not the first.

## Pitfall catalog (P1–P15)

- **P1 — Streaming endpoints.** `/traffic`, `/logs`, `/memory` are infinite
  streams. Never read them into memory (OkHttp buffering defaults!), always
  consume chunked with read timeouts only. Coalesce before the UI: traffic
  1 Hz, logs 0.5 Hz, connections 1 Hz.
- **P2 — The fd is a one-shot resource.** The tun fd from `VpnService.Builder`
  is valid only for the lifetime of the service instance; never cache it
  across restarts, and close the tun listener before stopping the service.
- **P3 — AutoRoute must stay false.** On Android, routes/address/MTU are set
  by the VPN builder, not by mihomo (`AutoRoute: true` would try to configure
  the interface and fail); `AutoDetectInterface` must also be false —
  outbound sockets are protected via the socket hook instead.
- **P4 — protect() every outbound socket.** mihomo's `dialer.DefaultSocketHook`
  must call `VpnService.protect(fd)`; without it, outbound connections loop
  back into the tun device and hang forever (the classic Android VPN death
  spiral). Also install the fd-limit guard there (block new sockets at ~75%
  RLIMIT_NOFILE) as CMFA does.
- **P5 — One VPN at a time.** Another VPN (or Always-on for another app)
  makes `startEstablish` fail or immediately get torn down. Surface the
  system error, don't retry blindly.
- **P6 — Foreground service discipline.** The VPN service must be started as
  a foreground service with a persistent notification; dismissing that
  notification is a stop request. Type: `specialUse`/`vpn` per SDK level.
- **P7 — gomobile API surface is frozen at bind time.** Exported Go APIs can
  only use bindable types (int32/float64/string/bool/byte slices + exported
  interfaces/structs). Don't export maps; wrap them in JSON strings.
- **P8 — Kotlin 2.x + Compose compiler version lock.** The
  `org.jetbrains.kotlin.plugin.compose` version must equal the Kotlin version
  in the version catalog; with AGP 9 built-in Kotlin, `kotlin-android` must
  NOT be applied.
- **P9 — Per-app tunneling list changes require a restart** of the VPN
  builder; apply them at establish time, and restart the service on change.
- **P10 — store-selected.** Always generate `profile: store-selected: true`
  so node selections survive hot reloads; switching profiles uses
  `PUT /configs` first and a full core restart only as fallback.
- **P11 — Provider paths are confined.** mihomo restricts file providers to
  its home dir; generate config with `SAFE_PATHS` semantics and keep provider
  files under `<home>/providers/`.
- **P12 — Don't trust your YAML writer.** Generated configs are written with
  insertion-ordered output and asserted by tests; a map-iteration-order
  regression produces flaky diffs.

- **P13 — R8 full mode hates JDK-only libraries.** The v1.0.0-rc.1 release
  gate failed in `minifyReleaseWithR8`: snakeyaml introspects
  `java.beans.*` (absent on Android). The fix is a scoped
  `-dontwarn java.beans.**` in `app/proguard-rules.pro` — never a blanket
  `-dontwarn **`; the rc tag exists to catch exactly this class of failure.
- **P14 — DataStore delegates are process-wide.** `preferencesDataStore`
  delegates MUST be declared top-level (file-private), never inside a class
  instance — two instances of the same name throw on first access. Also:
  never block on the store on the main thread; read async (or `getBlocking`
  only from IO dispatchers inside the VPN service paths).
- **P15 — Branch pushes can drop their CI events.** Branch names contain
  `#` (e.g. `feat/#12-...`); a push occasionally creates no `workflow_run`.
  Don't retry blindly — check the runs list, then trigger CI explicitly via
  the `workflow_dispatch` of `ci.yml` on that ref. Merging is only allowed
  on a green, current-head run.

## Extension recipes

- **New service method**: implement in the domain service → expose to
  ViewModel → document in CONTRACT.md → unit test.
- **New event**: add the constant to `Events.kt` (one definition point) →
  emit from the manager → collect in the ViewModel → document in CONTRACT.md.
- **New setting**: add to the settings model with a default + validation
  clamp → persist via DataStore → surface in the Settings screen (EN + FA
  strings) → document the key in CONTRACT.md.
- **New parser**: pure function in `profile/parser` → fixtures as inline
  strings → table-driven test → wire into `DecodeContent` auto-detection.
