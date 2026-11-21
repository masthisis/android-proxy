# Roadmap

The live backlog lives in [GitHub issues](https://github.com/masthisis/android-proxy/issues)
(scope labels `v1.0.0`, `v1.1.0`, `v1.2.0`, `v2`). This file summarizes it —
when an issue lands, remove the bullet here in the same docs sweep.

## v1.0.0 — MVP: the Windows cockpit on Android

- [x] #1 chore: bootstrap repository — Gradle skeleton, CI, docs foundation, issue templates
- [ ] #2 feat(core): Go core wrapper — mihomo v1.19.34 embedded via gomobile, VpnService fd tun
- [ ] #3 feat(vpn): VpnService layer — fd handoff, notification, split tunneling, always-on
- [ ] #4 feat(app): Compose app shell — Material 3, navigation, theming, EN/FA RTL
- [ ] #5 feat(engine): control plane — config generator, REST client, streams
- [ ] #6 feat(profiles): subscription import — URL/clipboard/file, all link formats, Clash YAML, sing-box
- [ ] #7 feat(proxies): groups dashboard — select, batch latency, sorting/search
- [ ] #8 feat(ui): live cockpit — traffic graph, connections, logs, rules, mode switching
- [ ] #9 feat(settings): settings document + surface
- [ ] #16 ci(release): tag-triggered release workflow — signed APKs + checksums

## v1.1.0 — test ergonomics + automation

- [ ] #10 feat(test): Test Center — multi-stream speed tests, cancel, byte-cap presets, all profiles
- [ ] #11 feat(failover): node-health watchdog — strikes/cooldown auto-switch
- [ ] #12 feat(rules): per-domain routing overrides + raw YAML editor with live validation
- [ ] #13 feat(platform): subscription auto-update scheduler (WorkManager) + Update All
- [ ] #14 feat(ui): quick settings tile + boot autostart + notification controls
- [ ] #15 feat(app): in-app self-update from GitHub releases
- [ ] #17 docs: full documentation suite (CONTRACT, FEATURES, AI guide, changelog)

## v1.2.0 — subscription power features

- [ ] #18 feat(profiles): subscription union — activate multiple subscriptions as one routing set
- [ ] #19 feat(profiles): configurable subscription User-Agent / custom headers

## v2 — beyond parity with the Windows client

- [ ] #20 feat(reporting): automatic problem reports as GitHub issues (crash bundles, redacted)
- [ ] #21 feat(accessibility): full TalkBack audit + dynamic type sweep
- [ ] #22 feat(tun): DNS tunnel transports tracked at core level (sing-box parity watch)
- [ ] #23 perf(ui): pause traffic sampling while the app is backgrounded
- [ ] #24 feat(widgets): home-screen traffic widget
