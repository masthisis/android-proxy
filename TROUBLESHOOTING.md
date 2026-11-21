# Troubleshooting

Worked answers to the problems that can actually block a first start. If your
problem is not here, open a [bug report](https://github.com/masthisis/android-proxy/issues/new?template=bug_report.yml)
with logs from **Settings → Logs → Share** (redact server addresses).

## 1. Android refuses to install the APK ("app not installed")

The release APKs are self-signed for GitHub distribution. Make sure you
downloaded the APK for your device (`arm64-v8a` on any phone from ~2014
onwards; `universal` works everywhere), that you are updating from a
**newer** version code, and that no Play-protected profile (work profile with
policy blocks) prevents sideloading. Uninstalling the old copy first always
works.

## 2. "NeuroRift keeps stopping" on first launch

Grab the log file (Settings → Logs → Share) — startup failures are logged
there and never silent. Most common causes: a corrupted download (verify the
SHA256 from `checksums-android.txt`) or an OEM "battery killer" (see §5)
force-stopping the app mid-start.

## 3. The VPN switch starts, then turns off by itself

Another VPN is active — Android allows exactly one VPN at a time. Turn the
other client off (or check **Settings → Network → VPN**) and start NeuroRift
again. If *Always-on VPN* is enabled for another app, NeuroRift cannot start
until it is disabled.

## 4. Connected but nothing loads

- Check the Dashboard: if traffic stays at 0, the subscription is likely dead
  — run **Test → latency test** on the profile.
- Re-add the subscription (airports rotate URLs frequently).
- Private DNS (Settings → Network → Private DNS) can clash with tun DNS
  hijacking — set it to *Off* or *Automatic*.
- Battery optimization force-stopping the core: allow **Unrestricted battery**
  for NeuroRift (see §5).

## 5. The core dies in the background (OEM battery killers)

Xiaomi (MIUI), Huawei, Oppo/Realme, Samsung and friends aggressively kill
long-running services. Fix once per device: app info → **Battery →
Unrestricted**, enable **Autostart**, and lock NeuroRift in the recents
overview where the OEM offers it. The notification is a foreground service —
do not dismiss it from the shade; that is treated as a stop request.

## 6. Per-app split tunneling is not applied

Split tunneling is enforced by Android's VPN builder; verify the app is in the
allow/deny list (Settings → Split tunneling), restart the VPN after changing
the list, and note that DNS of excluded apps still flows through the tunnel on
some OEM ROMs.

## 7. Speed tests show 0 Mbps

Speed tests run through the hidden `⚡ NR SpeedTest` listener on
`127.0.0.1:<speedTest port>`; if another app occupies that port, change the
port in Settings → Ports. Tests are serialized — a stuck test can be cancelled
from the Test Center button.

## 8. The app update check never finds anything

Updates are resolved from the GitHub `releases/latest` redirect; networks that
block GitHub need the mirror prefix setting (Settings → Updates). Pre-release
versions are only offered when **include prereleases** is on.

## 9. Farsi / RTL looks wrong

Language follows the system setting when it is en/fa, or is forced in
Settings → Language. If an RTL layout looks broken, report it with a
screenshot — RTL correctness is part of the definition of done for every UI
change.
