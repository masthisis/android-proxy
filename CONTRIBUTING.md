# Contributing to NeuroRift for Android

First off — thank you. Whether you are fixing a typo or building a feature,
this document tells you what you need and how we work. This repository follows
the **same operating model** as the sibling
[win-proxy](https://github.com/masthisis/win-proxy) repository; the flows are
kept identical on purpose.

## Development environment

| Tool | Version | Install |
| --- | --- | --- |
| JDK | 21 (17+ works) | [adoptium.net](https://adoptium.net/) |
| Android Studio | latest stable | [developer.android.com/studio](https://developer.android.com/studio) |
| Android SDK | Platform 37, Build-Tools 36.0.0 | via Android Studio SDK Manager |
| NDK | r28 (for the Go core AAR) | via Android Studio SDK Manager |
| Go | 1.27+ (for the core wrapper) | [go.dev/dl](https://go.dev/dl/) |
| gomobile | latest | `go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init` |
| Gradle | 9.6 (wrapper handles it) | `./gradlew` bootstraps itself |

The Go core (`core/`) builds through gomobile into an AAR that the `app`
module consumes; `./gradlew assembleDebug` runs the whole chain (Go → AAR →
APK) in one command.

## Getting started

```bash
git clone https://github.com/masthisis/android-proxy.git
cd android-proxy

# one-time: local.properties with sdk.dir, or ANDROID_HOME set
./gradlew assembleDebug          # builds core AAR + debug APK
./gradlew testDebugUnitTest      # JVM unit tests
adb install app/build/outputs/apk/debug/app-debug.apk
```

For deeper build/packaging detail see [docs/BUILDING.md](docs/BUILDING.md).

## Where things live

```
core/                                 Go core wrapper (mihomo embedded, gomobile AAR)
app/src/main/kotlin/com/neurorift/android/
    vpn/                              TunService (VpnService), tile, boot receiver
    engine/                           config generator, REST client, streams, lifecycle
    profile/                          subscription import, link parsers, Room registry
    nettest/                          latency + throughput measurement
    failover/                         node watchdog / auto-switching
    settings/                         DataStore-backed settings
    ui/                               Compose screens, theme, navigation, i18n
docs/                                 architecture, contract, testing, roadmap
.github/                              workflows, issue templates, dependabot
```

Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) before your first change,
and keep [docs/CONTRACT.md](docs/CONTRACT.md) in sync whenever a service
method or event changes.

## Ground rules

1. **Conventional Commits** for PR titles and commits
   (`feat(engine): …`, `fix(vpn): …`, `docs: …`, `chore: …`, `ci: …`).
2. **Tests**: behavioural changes to domain packages come with a JVM unit
   test (`./gradlew testDebugUnitTest`); platform paths are covered by
   Robolectric where sensible (see docs/TESTING.md).
3. **No secrets**: never commit subscription URLs, tokens, keystore
   passwords, or personal server addresses. Use the fixture links in the
   parser tests as templates.
4. **Formatting**: `./gradlew lint` clean (Android Lint) and Kotlin coding
   conventions; no wildcard imports.
5. **Docs**: user-visible behaviour changes update README or `docs/*` in the
   same PR.
6. **Core changes**: mihomo itself is upstream — the wrapper in `core/` stays
   thin. If you need a mihomo change, file it upstream and track it here.

## Pull request process

- Follow the operating contract in
  [docs/WORKFLOW.md](docs/WORKFLOW.md): every change starts as an issue,
  lives on a `<type>/#<issue>-<slug>` branch, and lands as a PR containing
  `Closes #N` (squash merge, CI green, branch deleted).
- Branch from `main`, keep the diff focused — one logical change per PR.
- Fill in the PR template (it includes the pre-merge checklist).
- CI must be green: unit tests, lint, and the full debug APK build.
- Small, well-described PRs get reviewed and merged fastest.

## For AI-assisted development

This repository is deliberately maintained by humans *and* AI agents, exactly
like its Windows sibling. [docs/AI_DEVELOPER_GUIDE.md](docs/AI_DEVELOPER_GUIDE.md)
contains ready-to-use task prompts, the pitfall catalogue and extension
recipes — start there before touching the engine.

## Reporting bugs

Open a [bug report](https://github.com/masthisis/android-proxy/issues/new?template=bug_report.yml)
with logs (in-app: Settings → Logs → share, redact server addresses).
Security issues: see [SECURITY.md](SECURITY.md) — do **not** open a public
issue for those.
