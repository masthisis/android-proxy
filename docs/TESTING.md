# Testing

## Philosophy

The same as the Windows sibling: **every domain behaviour ships with a test**,
platform plumbing gets Robolectric coverage where it is cheap, and the real
core is exercised manually against a live subscription before a release. The
data plane is mihomo's — the app tests its own logic (parsing, config
generation, state machines, watchdog policy) and the correctness of what it
*asks* the core to do, not mihomo itself.

## Layers

| Layer | Runner | Scope |
| --- | --- | --- |
| Pure JVM unit tests | `./gradlew testDebugUnitTest` | Parsers (share links, base64 lists, Clash YAML, sing-box JSON), config generator output, settings validation/sanitization, watchdog policy, speed-test byte accounting, updater resolution |
| Robolectric | `./gradlew testDebugUnitTest` (same source set) | REST client request shapes against MockWebServer, stream decoding/coalescing, Room DAOs, DataStore settings |
| Manual real-core | on device/emulator | Import your own subscription, connect, verify traffic, latency + speed tests, failover switch, hot reload |

## Conventions

- Tests live next to the code: `app/src/test/kotlin/…`.
- Parsers must not hit the network; fixtures are inline strings. Subscription
  URLs, tokens, or personal server addresses are **never** committed — use
  `example.com` / `198.51.100.x` (RFC 5737) / obviously-fake UUIDs as the
  fixtures do.
- Config-generator tests assert on the generated YAML (stable, insertion-
  ordered) — snapshot-style, one assertion per concern.
- Regression rule (inherited from win-proxy): **a bug fix lands with a test
  that fails without the fix.**
- Speed/latency numbers are never asserted in CI; only ordering, phasing,
  cancellation and accounting are.

## Real-core integration checks (manual, pre-release)

1. Import a Clash YAML profile with groups + rules → switch to it → connect →
   traffic flows, connections table fills.
2. Import a base64 link list → generated config contains the provider with
   `include-all` groups → update the subscription → node list swaps without
   dropping connections.
3. Test Center: latency batch per group, speed test down+up on one node,
   cancel mid-flight, byte-cap preset honoured.
4. Failover: block the active node → watchdog switches after `strikes` →
   unblock → event log shows the switch.
5. Kill the core (force stop from the app's own button) → supervisor restarts
   it with backoff → crash-loop guard engages after 5 restarts / 2 min.

## Running

```bash
./gradlew testDebugUnitTest            # full JVM suite (includes Robolectric)
./gradlew :app:testDebugUnitTest --tests "com.neurorift.android.profile.UriParseTest"
```

CI runs the full suite plus lint plus the debug APK build on every push/PR.
