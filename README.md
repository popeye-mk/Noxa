# Noxa

**Privacy is a right, not a privilege.**

Noxa is a free, open, dead-simple Android app that blocks ads, trackers, and malware across your **whole phone** — every app, not just the browser — and can hide your IP through an encrypted tunnel when you're on untrusted Wi-Fi. No telemetry, no accounts, no ads, no Noxa servers. The only connections Noxa makes are to GitHub (list and update downloads) and to the DNS resolver you choose (or HaGeZi DNS while the tunnel is on) — plus, only if you set them up yourself, the WireGuard provider you chose and a home server you run in your own house (Noxa provides none). You can read the code and check.

> Built for the people, and given back to them.

**Current version: 1.15.1** · Android 7.0 – 17 (phones, tablets, Android TV) · blocklist rebuilt every week · see [everything Noxa does](docs/NOXA-OVERVIEW.md)

---

## Install (2 minutes)

1. On your phone, open **[the latest release](https://github.com/popeye-mk/Noxa/releases/latest)** and download the `noxa-v….apk` file
2. Tap the downloaded file and allow the install when Android asks
3. Open Noxa, flip the switch, allow the VPN prompt — done

**Then do one more thing:** tell your phone not to kill Noxa in the
background — takes a minute, steps for every phone brand are in the
**[User Manual](docs/USER-MANUAL.md)** (also as [PDF](docs/Noxa-User-Manual.pdf)).
Skipping this is the #1 reason protection "randomly" stops.

Something not working? See [Troubleshooting](#troubleshooting) or the
[User Manual](docs/USER-MANUAL.md) — and please [open an issue](https://github.com/popeye-mk/Noxa/issues).

---

## What it does

- **System-wide blocking** — a local `VpnService` DNS sinkhole checks every app's lookups against a compiled **~990,000-domain** filter merged from **13 public lists** plus the UT1 base ([full list below](#the-full-blocklist)). Blocked domains never connect. Ad-block test: **97%**.
- **Per-app control + plain-language stats** — see *which company* each app is tracking you for ("Google · Advertising", "Xiaomi · Analytics"), and block any app entirely with one switch.
- **CNAME uncloaking** — catches trackers disguised as first-party subdomains that plain domain-lists miss.
- **IPv6-proof** — DNS is captured over IPv4 *and* IPv6, so lookups can't slip around the filter on modern networks.
- **Works on Android TV** — appears in the TV launcher; block the trackers baked into every smart-TV app.
- **Stays on** — battery-exemption ask, a self-restarting watchdog, and automatic start after reboot: protection that doesn't silently die.
- **Encrypted DNS (DoH)** — hides your lookups from your ISP/Wi-Fi. **You choose the resolver**: Cloudflare, Quad9, Mullvad, AdGuard, Google or your own.
- **Hide my IP (WireGuard tunnel)** — optional; routes through *your* provider (Proton, Mullvad, IVPN, or your own server). Blocks ads and trackers **while** tunnelling through [HaGeZi DNS](https://github.com/hagezi/dns-servers) (free, EU, no per-query logs): **95%** in the ad-block test. Noxa runs no servers.
- **Public Wi-Fi guard** — on a Wi-Fi with no password or a login page (hotel, airport, café) the tunnel switches on by itself after you log in, and off when you leave. Needs a saved tunnel config; no location permission.
- **Watch it live** — see every app's lookups as they happen; tap one to allow or block that site on the spot. Kept in memory only, never saved.
- **Your own lists** — *Allowed sites* (never block) and *My blocked sites* (always block), editable any time.
- **Dangerous-site alert** — ~520,000 known scam, phishing and malware domains are flagged separately: when a browser hits one, Noxa blocks it *and* says why ("don't enter passwords there"); a non-browser app doing it gets a stronger warning.
- **Fake-site warning** — catches look-alikes of banks, PayPal, Netflix, Disney+, couriers and crypto wallets (`paypa1.com`, `netflix-billing.xyz`), checked against the top 100,000 websites for false alarms.
- **Crypto-scam protection** — MetaMask's own phishing list (~118,000 fake wallets, fake NFT/DeFi sites and "connect your wallet" traps) is part of the weekly list.
- **Check a link** — share or select any link → "Check link with Noxa", or copy it and tap the **Check link** tile: fake brand site, scam or spyware site, disguised link (`paypal.com@evil.xyz`), shortened link, risky ending. Offline; the link never leaves the phone.
- **Router attack shield** — websites can't point ordinary-looking names at your router, camera or printer (DNS rebinding). Home names (`.local`, `fritz.box`, Plex) keep working.
- **Spy-app watch** — once a day, offline: an app that can read and control your screen *and* hides from uninstalling (or didn't come from a store) is how banking trojans and stalkerware work. You only hear about it if one is found.
- **Wi-Fi only, per app** — keep chosen apps offline on mobile data; on Wi-Fi they work normally.
- **New-app report** — a day after you install an app, one quiet note says how many trackers it contacted and from which companies.
- **Protection check-up** — one screen listing what keeps you protected (battery exemption, Always-on VPN, alerts, encryption, list age) with a one-tap fix for each.
- **Spyware alert** — if any app tries to contact a known stalkerware server (~525 domains, refreshed weekly), Noxa blocks it *and* warns you, naming the app.
- **Update notice, one tap** — a once-a-day anonymous check of this repo's Releases page. Tap the notification and Noxa downloads the new version, checks it against the SHA-256 published with the release, and opens Android's installer: you tap Install. Noxa never installs anything by itself.
- **Home-screen widget + 7-day chart** — status, blocked-today and an on/off button on your home screen; a week of blocked trackers at a glance.
- **Fresh lists every week** — a GitHub Action rebuilds the filter from every source each Monday and ships it only if strict safety checks pass (no false negatives, no blocked banks/connectivity/messaging apps, no suspicious size swings, the ad-block score never drops). Must-work sites (Google, WhatsApp, GitHub, cloud storage…) are kept off even when a list names them; a source that breaks or shrinks opens a GitHub issue by itself. Each phone checks the new list again before using it.
- **Backup & restore** — save your allowed/blocked sites and app settings to a file, restore them on a new phone.
- **Bedtime** (optional) — quiet hours per device ("21:00 to 07:00"): every app, or the ones a parent chooses, get no internet in that window. Calls and texts keep working. Shown as "Bedtime" in the live feed; changing it asks the parent PIN.
- **Safe search** (optional) — Google, YouTube, Bing, DuckDuckGo and Yandex forced into safe / restricted mode on the device, the way family DNS services do it (a CNAME to each site's own safe-mode address). Every browser and app that uses the device's DNS; nothing is blocked outright.
- **Family filter** (optional) — adult, gambling and dating sites blocked on the device: a second list of several million domains, rebuilt weekly, downloaded only when a parent switches it on (about 10 MB), verified by SHA-256. Education sites are left alone; your Allowed sites win.
- **Parent lock** (optional) — a PIN on a child's phone, tablet or the TV: turning protection off, pausing it, letting an app bypass Noxa, allowing a blocked site or changing the home-server settings asks for it; the tile, widget and notification can't switch it off. Blocking more never needs the PIN. Honest limit: Android lets anyone uninstall an app — a home server notices the silence.
- **A Linux PC in the same picture** — `pc/noxa-pc.sh` makes a Linux PC report to the same home server (updates, firewall, DNS) and switches Quad9, the firewall and automatic security updates on. See [docs/HOME-SERVER.md](docs/HOME-SERVER.md).
- **Report to my home server** (optional, off by default) — a family with several phones, tablets and a TV gets one place in their own house that shows every device: protection on or off, blocked counts by category, and whether an app reached a scam or spyware server. Once an hour, only on your home Wi-Fi, only to a private address you typed (or found on the Wi-Fi with one tap while the home server's door is open); never the sites you visit (Noxa doesn't keep them). Format and a 30-line example server: [docs/HOME-SERVER.md](docs/HOME-SERVER.md). The report is plain JSON, so Home Assistant or any dashboard can receive it, and a server can send rules back that Noxa applies on the device. Noxa still runs no servers of its own.
- **Quick Settings tile + "Pause 5 min"** — on/off from the pull-down shade; a one-tap timed pause in the notification that turns itself back on.
- **30-day rolling stats**, CSV export, first-run explainer.
- **Zero telemetry, verifiable.** No location permission, no accounts, nothing phones home.

Default experience is **one on/off switch**. Everything advanced lives one tap deeper.

---

## The full blocklist

Built **9 November 2026** (rebuilt every Monday; numbers change a little each week). Each entry also blocks all of its subdomains.

| Source | What it adds | Domains |
|---|---|---|
| [UT1](https://dsi.ut-capitole.fr/blacklists/) (Université Toulouse) | the base: ads, malware, phishing, crypto-mining, stalkerware, DoH bypass… | base list |
| [OISD Big](https://oisd.nl) | ads, trackers, malware | 231,817 |
| [HaGeZi Pro](https://github.com/hagezi/dns-blocklists) | ads, tracking, analytics, telemetry | 225,344 |
| [HaGeZi Threat Intelligence (mini)](https://github.com/hagezi/dns-blocklists) | malware, phishing, scams, command-and-control | 188,782 |
| [AdGuard DNS filter](https://github.com/AdguardTeam/HostlistsRegistry) | ads, trackers | 187,211 |
| [Phishing Army (extended)](https://phishing.army) | phishing | 144,114 |
| [MetaMask eth-phishing-detect](https://github.com/MetaMask/eth-phishing-detect) | crypto scams, fake wallets, wallet drainers | 118,183 |
| [StevenBlack hosts](https://github.com/StevenBlack/hosts) | ads, malware | 78,216 |
| [EasyPrivacy](https://easylist.to) | trackers | 47,381 |
| [EasyList](https://easylist.to) | ads | 46,840 |
| [Dandelion Sprout Anti-Malware](https://github.com/DandelionSprout/adfilt) | malware | 11,509 |
| [HaGeZi DoH/VPN bypass](https://github.com/hagezi/dns-blocklists) | browsers' own DNS that would skip the filter | 3,571 |
| [NoCoin](https://github.com/hoshsadiq/adblock-nocoin-list) | crypto mining in the browser | 312 |
| [abuse.ch URLhaus](https://urlhaus.abuse.ch) | sites spreading malware *right now* (free key) | 111 |

**Total: 989,597 unique domains** (1,895,256 entries before removing duplicates).

Shipped next to it:

| List | Size | Used |
|---|---|---|
| Dangerous-site list (scam / phishing / malware, for the warning) | 521,329 | always |
| Spyware (stalkerware) servers | 525 | always, with an alert |
| Risky web endings (`.top`, `.xyz`, …) | 127 endings | only with *Strict scam protection* |
| Family filter (adult, gambling, dating) | 4,697,689 | only when a parent switches it on |

---

## How it works

Noxa is a **local filter built on Android's `VpnService`** — not a real VPN by default. It routes DNS to itself, checks each looked-up domain against a compiled **Bloom filter** (built offline; the phone never parses raw lists), and drops trackers while forwarding everything else. The optional IP tunnel embeds the official **WireGuard** library (ready for 16 KB-page phones) and mode-switches with the blocker (Android allows one VPN at a time); while it's on, blocking happens at HaGeZi DNS inside the tunnel.

Built for the newest Android rules (targets Android 16): screens fit between the status bar, navigation bar and keyboard, and the predictive back gesture works everywhere. Releases are signed in GitHub Actions, and the workflow refuses to publish an APK signed with any other key.

---

## Build it yourself

**Easiest — Android Studio:** Open the project, plug in a phone (USB debugging on), press **Run**.

**Command line (Linux):** `bash build-on-linux.sh` fetches a JDK 17 + the Android SDK + Gradle into a local folder in your home directory and produces `app/build/outputs/apk/debug/app-debug.apk`.

The compiled blocklist ships in `app/src/main/assets/guardian-default.gbf`, so the app builds without the raw lists. To **rebuild the filter**, download the sources (see `docs/`) and run `python3 build-tools/build_blocklist.py`. The build is cross-checked by `build-tools/test_filter.py`, `verify_kotlin_math.py`, `test_packets.py` and `test_site_coverage.py` (ad-block test score); the app's own unit tests (`app/src/test`) run with `./gradlew testDebugUnitTest` and on every push in GitHub Actions.

---

## Privacy & the "free forever" guarantee

Noxa runs **no servers**, so there's no bill forcing it to monetize you — "free forever" is structural, not a promise. And it's **open source under a copyleft license**, so nobody (not even the author) can close it, add tracking, and sell it: anyone can fork the last free version and keep it free.

Setting up a child's device — parent lock, bedtime, safe search, family filter: [docs/FAMILY.md](docs/FAMILY.md).

The honest limits: DNS-level blocking can't hide same-domain/cosmetic ads (pair with a browser like Brave for that), and it can't stop a service you're logged into from tracking what you do *on it*. See `docs/` for the full, honest breakdown.

---

## Troubleshooting

**An app shows "no internet" or won't connect while Noxa is on** (Android
Auto, some streaming and banking apps — they refuse to run when they detect
a VPN): **Per-app details → "App won't work with Noxa on? Exclude it"** →
pick the app → toggle protection off and on. The excluded app bypasses Noxa
entirely — it works, the rest of the phone stays protected.

**A site or service is wrongly blocked:** add its address in **Per-app
details → Allowed sites** — it will never be blocked again. Noxa ships with
a small compatibility set already (visible and deletable there) for apps
that hang when their startup beacon is blocked: Disney+ (error 142) and
Prime Video on Android TV.

**Protection silently stops after a while:** your phone's battery manager is
killing it. Noxa asks for a battery exemption, restarts itself every ~15
minutes and after reboots — but aggressive brands need one or two extra
taps (Xiaomi: enable Autostart). Per-brand steps: [User Manual](docs/USER-MANUAL.md).

Hit something new? Please [open an issue](https://github.com/popeye-mk/Noxa/issues)
— your fix helps everyone.

## Not affiliated

Noxa is an independent project, not affiliated with any other privacy/security product.

## License

See [`LICENSE`](LICENSE). (Copyleft — free forever, for everyone.)
