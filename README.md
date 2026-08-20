# Noxa

**Privacy is a right, not a privilege.**

Noxa is a free, open, dead-simple Android app that blocks ads, trackers, and malware across your **whole phone** — every app, not just the browser — and can hide your IP through an encrypted tunnel when you're on untrusted Wi-Fi. No servers of its own, no accounts, no ads, no data sale. Provably nothing leaves your device — you can read the code and check.

> Built for the people, and given back to them.

---

## What it does

- **System-wide blocking** — a local `VpnService` DNS sinkhole checks every app's lookups against a compiled **~860,000-domain** filter (UT1 + StevenBlack + EasyList/EasyPrivacy + OISD + HaGeZi + AdGuard). Blocked domains never connect.
- **Per-app control + plain-language stats** — see *which company* each app is tracking you for ("Google · Advertising", "Xiaomi · Analytics"), and block any app entirely with one switch.
- **CNAME uncloaking** — catches trackers disguised as first-party subdomains that plain domain-lists miss.
- **IPv6-proof** — DNS is captured over IPv4 *and* IPv6, so lookups can't slip around the filter on modern networks.
- **Works on Android TV** — appears in the TV launcher; block the trackers baked into every smart-TV app.
- **Encrypted DNS (DoH)** — hides your lookups from your ISP/Wi-Fi.
- **Hide my IP (WireGuard tunnel)** — optional; routes through *your* provider (Proton, Mullvad, IVPN, or your own server). Blocks trackers **while** tunnelling. Noxa runs no servers.
- **User allowlist** — un-block anything caught by mistake, yourself.
- **30-day rolling stats**, CSV export, first-run explainer.
- **Zero telemetry, verifiable.** No location permission, no accounts, nothing phones home.

Default experience is **one on/off switch**. Everything advanced lives one tap deeper.

---

## How it works

Noxa is a **local filter built on Android's `VpnService`** — not a real VPN by default. It routes DNS to itself, checks each looked-up domain against a compiled **Bloom filter** (built offline; the phone never parses raw lists), and drops trackers while forwarding everything else. The optional IP tunnel embeds the official **WireGuard** library and mode-switches with the blocker (Android allows one VPN at a time).

---

## Build it yourself

**Easiest — Android Studio:** Open the project, plug in a phone (USB debugging on), press **Run**.

**Command line (Linux):** `bash build-on-linux.sh` fetches a JDK 17 + the Android SDK + Gradle into a local folder in your home directory and produces `app/build/outputs/apk/debug/app-debug.apk`.

The compiled blocklist ships in `app/src/main/assets/guardian-default.gbf`, so the app builds without the raw lists. To **rebuild the filter**, download the sources (see `docs/`) and run `python3 build-tools/build_blocklist.py`. The build is cross-checked by `build-tools/test_filter.py`, `verify_kotlin_math.py`, and `test_packets.py`.

---

## Privacy & the "free forever" guarantee

Noxa runs **no servers**, so there's no bill forcing it to monetize you — "free forever" is structural, not a promise. And it's **open source under a copyleft license**, so nobody (not even the author) can close it, add tracking, and sell it: anyone can fork the last free version and keep it free.

The honest limits: DNS-level blocking can't hide same-domain/cosmetic ads (pair with a browser like Brave for that), and it can't stop a service you're logged into from tracking what you do *on it*. See `docs/` for the full, honest breakdown.

---

## Troubleshooting

A few apps refuse to start when their startup analytics beacon is blocked —
that's them, not you. Noxa ships with a tiny "app compatibility" set of
allowlist entries (visible and deletable in **Allowed sites**) covering the
known ones: Disney+ (error 142, `disneystreaming.com`) and Prime Video on
Android TV (`device-metrics-us.amazon.com`). If another app misbehaves with
Noxa on, add its domain to **Allowed sites** — and please open an issue so it
can help everyone.

## Not affiliated

Noxa is an independent project, not affiliated with any other privacy/security product.

## License

See [`LICENSE`](LICENSE). (Copyleft — free forever, for everyone.)
