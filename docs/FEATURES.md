# Feature matrix

NeuroRift for Android vs the reference Android proxy clients and the Windows
sibling. ✅ = full support, 🟡 = partial/planned, ❌ = not applicable.

| Feature | NeuroRift Android | ClashMetaForAndroid | FlClash | Hiddify | NeuroRift Windows |
| --- | --- | --- | --- | --- | --- |
| Engine | mihomo (embedded) | mihomo (embedded) | mihomo (embedded) | sing-box | mihomo (child process) |
| VLESS + REALITY | ✅ | ✅ | ✅ | ✅ | ✅ |
| Hysteria2 (port hopping) | ✅ | ✅ | ✅ | ✅ | ✅ |
| TUIC v4/v5 | ✅ | ✅ | ✅ | ✅ | ✅ |
| AnyTLS / WireGuard outbounds | ✅ | ✅ | ✅ | ✅ | ✅ |
| SS plugins (obfs, v2ray-plugin) | ✅ | ✅ | ✅ | ✅ | ✅ |
| WS/gRPC/H2/httpupgrade/xhttp transports | ✅ | ✅ | ✅ | ✅ | ✅ |
| Base64 + plain link-list subscriptions | ✅ | ✅ | ✅ | ✅ | ✅ |
| Full Clash YAML passthrough | ✅ | ✅ | ✅ | ✅ | ✅ |
| sing-box JSON import | ✅ | ❌ | ✅ | ✅ | ✅ (v1.8.0) |
| Subscription auto-update + hot swap | ✅ | ✅ | ✅ | ✅ | ✅ |
| Update All + union routing | ✅ (planned v1.1/1.2) | ❌ | 🟡 | ✅ | ✅ |
| Multi-stream speed test (down **and** up) | ✅ (planned v1.1) | ❌ | ✅ | ✅ | ✅ |
| Latency sparklines per node | ✅ (planned v1.1) | ❌ | ✅ | ❌ | ✅ |
| Failover watchdog (strikes/cooldown) | ✅ (planned v1.1) | ❌ | ❌ | 🟡 | ✅ |
| Per-domain routing overrides | ✅ (planned v1.1) | ❌ | ✅ | ✅ | ✅ |
| Raw YAML editor with live validation | ✅ (planned v1.1) | ❌ | ✅ | ✅ | ✅ |
| Live connections table + per-conn close | ✅ | ✅ | ✅ | ✅ | ✅ |
| Core log stream + app log | ✅ | ✅ | ✅ | ✅ | ✅ |
| Mode switch without reload | ✅ | ✅ | ✅ | ✅ | ✅ |
| Per-app split tunneling | ✅ | ✅ | ✅ | ✅ | n/a (Windows uses TUN rules) |
| Always-on VPN + kill switch | ✅ | ✅ | ✅ | ✅ | n/a |
| Quick settings tile + boot autostart | ✅ (planned v1.1) | ✅ | ✅ | ✅ | n/a (tray instead) |
| In-app self-update | ✅ (planned v1.1) | ❌ | ✅ | ✅ | ✅ |
| Bilingual EN + Farsi (RTL) | ✅ | ✅ | ✅ | ✅ | ✅ |
| Fully open, issue-driven, AI-documented | ✅ | ✅ | ✅ | ✅ | ✅ |

Notes:

- "Embedded" means the core runs inside the app process and receives the
  VpnService tun fd directly (no tun2socks hop, no child process supervision
  of a separate binary).
- The planned column keeps the roadmap honest: features the Windows client
  already has are explicitly scheduled, not aspirational.
