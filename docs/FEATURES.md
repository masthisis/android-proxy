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
| Update All + union routing | ✅ | ❌ | 🟡 | ✅ | ✅ |
| Multi-stream speed test (down **and** up) | ✅ | ❌ | ✅ | ✅ | ✅ |
| Latency sparklines per node | ✅ | ❌ | ✅ | ❌ | ✅ |
| Failover watchdog (strikes/cooldown) | ✅ | ❌ | ❌ | 🟡 | ✅ |
| Per-domain routing overrides | ✅ | ❌ | ✅ | ✅ | ✅ |
| Raw YAML editor with live validation | ✅ | ❌ | ✅ | ✅ | ✅ |
| Live connections table + per-conn close | ✅ | ✅ | ✅ | ✅ | ✅ |
| Core log stream + app log | ✅ | ✅ | ✅ | ✅ | ✅ |
| Mode switch without reload | ✅ | ✅ | ✅ | ✅ | ✅ |
| Per-app split tunneling | ✅ | ✅ | ✅ | ✅ | n/a (Windows uses TUN rules) |
| Always-on VPN + kill switch | ✅ | ✅ | ✅ | ✅ | n/a |
| Quick settings tile + boot autostart | ✅ | ✅ | ✅ | ✅ | n/a (tray instead) |
| In-app self-update | ✅ | ❌ | ✅ | ✅ | ✅ |
| Bilingual EN + Farsi (RTL) | ✅ | ✅ | ✅ | ✅ | ✅ |
| Fully open, issue-driven, AI-documented | ✅ | ✅ | ✅ | ✅ | ✅ |

Notes:

- "Embedded" means the core runs inside the app process and receives the
  VpnService tun fd directly (no tun2socks hop, no child process supervision
  of a separate binary).
- The planned column keeps the roadmap honest: features the Windows client
  already has are explicitly scheduled, not aspirational.
