# Security Policy

## Supported versions

| Version | Supported |
| --- | --- |
| latest tagged release | yes |
| older releases | no — please update |

Releases are cut from `main`; tags are `vX.Y.Z`.

## Reporting a vulnerability

Use GitHub's **private vulnerability reporting**: open the repository →
**Security** tab → *Report a vulnerability*. This reaches the maintainers
directly without a public paper trail.

Please include:

- affected version / commit hash,
- the steps or inputs that trigger the issue,
- what an attacker could gain from it.

You will get an initial response within a few days. Once a fix is ready we
publish a GitHub Security Advisory with CVE credit to the reporter.

## Scope

**In scope**

- The NeuroRift for Android application itself: the VPN service (fd handling,
  socket protection, split tunneling), the local mihomo REST surface it
  exposes, settings/profile file handling, the subscription import pipeline
  (parsers run on untrusted input), and anything that executes the embedded
  mihomo core.
- The release pipeline in `.github/workflows/` (artifact integrity).

**Out of scope**

- The mihomo core itself — report engine issues upstream at
  [MetaCubeX/mihomo](https://github.com/MetaCubeX/mihomo/security/advisories).
  We pin the core version in the release workflow and follow upstream fixes
  closely.
- "A proxy client can be used to reach the internet" — that is the product.
- Missing Play-Store code signing on public builds (known limitation, tracked
  in the issue tracker; treat downloads from GitHub Releases + the published
  SHA256 sums as the integrity chain of trust).

## Supply-chain notes

- The embedded core is compiled from the pinned mihomo tag inside CI; the pin
  lives in `core/go.mod` and the Release workflow prints `mihomo -v` for the
  built artifact.
- Dependency updates (Gradle, Kotlin/AndroidX, Go modules, Actions) arrive via
  Dependabot and land only after CI passes.
- No telemetry, no remote configuration: the app makes outbound requests only
  to the subscription URLs the user configures, the update check endpoint
  (`github.com/masthisis/android-proxy/releases`), and the geo databases the
  generated config references.
