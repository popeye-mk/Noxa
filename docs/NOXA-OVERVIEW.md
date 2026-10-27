# Noxa: everything it is today

*Version 1.14.6 (versionCode 30) · blocklist built 27 October 2026*

Noxa is a free, open-source (AGPL-3.0) privacy and security blocker for
Android phones, tablets and Android TV (Android 7.0 and newer). It runs
entirely on the device: no account, no servers of its own, no analytics.
One switch turns it on; almost everything else happens by itself.

---

## 1. How it works

Noxa uses Android's VPN slot to see every app's **DNS lookups**, which
happen when an app asks "what is the address of `ads.example.com`?" It is
not a real VPN: nothing is sent to anyone, it is a local filter.

- Each lookup is checked against Noxa's lists on the phone.
- A blocked lookup gets "nothing there" (`0.0.0.0`), so the ad, tracker or
  scam site never loads.
- An allowed lookup is answered by the DNS provider you choose, encrypted
  (DNS-over-HTTPS) when possible.
- Noxa also notes **which app** made the lookup, so it can tell you who
  tracks you through which app.

Answers are cached, and the work runs on background threads that sleep until
there is something to do.

---

## 2. The blocklists

| List | Size | Used |
|---|---|---|
| **Main blocklist** (ads, trackers, malware, phishing, scams) | **867,189 domains** | always |
| Dangerous-site list (to explain *why* something was blocked) | 434,350 | for warnings |
| Spyware (stalkerware) servers | 525 | always, with an alert |
| Risky web endings (`.top`, `.xyz`, …) | 127 endings | only with *Strict scam protection* |
| **Family filter** (adult, gambling, dating) | **4,672,103** | only when switched on |

The main list merges 12 public lists, 1,789,388 entries in total before
removing duplicates:

| Source | Domains |
|---|---|
| OISD | 234,252 |
| HaGeZi Threat Intelligence (mini) | 212,777 |
| HaGeZi Pro | 209,844 |
| AdGuard DNS | 183,756 |
| Phishing Army | 153,199 |
| StevenBlack | 74,481 |
| EasyList | 45,963 |
| EasyPrivacy | 47,289 |
| Dandelion Sprout Anti-Malware | 11,598 |
| HaGeZi DNS-over-HTTPS bypass | 3,277 |
| NoCoin (crypto mining) | 312 |
| URLhaus (malware being spread right now) | 118 |

On top of these sits the UT1 base list. A blocked domain also blocks all of
its subdomains.

**Updated every Monday by itself.** A GitHub workflow rebuilds the lists. It
refuses to ship if:

- an everyday site would be blocked,
- the list shrank suspiciously, or
- the ad-block test score went down.

Before using a new list, the phone checks it again itself and keeps the old
one if anything looks wrong.

---

## 3. Protection

- **Ads, trackers, analytics**: blocked in every app and browser.
- **CNAME uncloaking**: catches trackers hiding behind a site's own address.
- **Scam, phishing and malware sites**: blocked, and you're told why.
- **Fake-site warning**: catches look-alikes of banks, PayPal, Netflix,
  Disney+, couriers and more (e.g. `paypa1.com`, `netflix-billing.xyz`).
  It was checked against the top 100,000 websites with no false alarms.
- **Stalkerware alert**: an app contacting a known spyware server raises a
  strong warning.
- **Strict scam protection** (optional): blocks whole risky web endings.
- **Router attack shield**: websites can't point ordinary-looking names at
  your router, camera or printer (DNS rebinding). Home names (`.local`,
  `fritz.box`, Plex) keep working. The shield stands aside on hotel Wi-Fi
  until you've logged in.
- **Browser DNS-bypass blocked**: browsers can't quietly switch to their own
  DNS and skip Noxa.

---

## 4. Things Noxa does by itself

- **Automatic check-up**: notifies you only when something breaks, such as
  Always-on VPN switched off, the phone keeps stopping Noxa, or the list is
  too old.
- **Self-healing**: if protection dies, it restarts within seconds. A
  watchdog checks every ~15 minutes and after reboot.
- **Battery judged by facts**: after 3 days without an unexpected stop, the
  battery setting counts as fine. This works on Xiaomi and other phones that
  report it wrongly.
- **DNS fallback**: if your DNS provider stops answering, Noxa uses the next
  one for 10 minutes, then goes back.
- **Stuck-app offer**: if Noxa blocks an app's own server and nothing gets
  through, it offers a fix. At most once a week, never for system apps.
- **Daily pop-up ad app check**: you only hear about it when one is found.
- **Daily spy-app watch**: looks for apps that can read and control your
  screen *and* hide from uninstalling, or that weren't installed from a store.
  This is how banking trojans and stalkerware work.
- **New-app report**: a day after you install an app, one quiet note says how
  many trackers it contacted and from which companies.
- **Weekly summary** every Sunday evening.
- **Public Wi-Fi guard**: on Wi-Fi with no password or with a login page,
  your "Hide my IP" tunnel switches on after login and off when you leave.
  It needs a saved tunnel config.
- **Update notice**: once a day it checks for a newer Noxa. Updating takes one
  tap, and the download must be Noxa, newer than your copy, and signed with
  the Noxa release key.

---

## 5. Tools you can open

- **Who's tracking you**: per-app stats in plain language (which company,
  why, how often).
- **Live**: watch lookups happen and fix a wrong block with one tap. This
  feed is kept in memory only.
- **Fix an app that won't work**: likely apps first, a gentle fix first,
  applies instantly.
- **Allowed and blocked sites**: your own "never block" and "always block"
  lists.
- **Per app**:
  - **Block the whole app** (firewall).
  - **Wi-Fi only**: no mobile data for that app.
  - **Don't filter this app**: for apps that refuse to run with a VPN.
- **DNS provider**: Cloudflare (default), Quad9, Mullvad, AdGuard, Google or
  your own. **Test speed** marks the fastest on your network.
- **Check a link**: share or select a link → "Check link with Noxa", or copy
  it and tap the **Check link** Quick Settings tile (or long-press Noxa's
  icon). It answers offline: fake brand site, scam/spyware site, disguised
  link (`paypal.com@evil.xyz`), shortened link, bare IP, risky ending,
  tracker.
- **Find the app behind pop-up ads**.
- **Protection check-up** with one-tap fixes.
- **Back up and restore** your choices.
- **Home-screen widget**, **Quick Settings tiles** (on/off and Check link),
  **Pause 5 minutes** from the notification.
- **Hide my IP**: Tor through Orbot, or your own WireGuard provider
  (Proton, Mullvad, IVPN…), with ad/tracker blocking inside the tunnel.

---

## 6. For families (all optional, off by default)

| Feature | What it does |
|---|---|
| **Parent lock** | A PIN before protection can be switched off, paused or weakened. |
| **Bedtime** | Quiet hours: every app, or chosen apps, go offline. Calls and texts keep working. |
| **Safe search** | Google, YouTube, Bing, DuckDuckGo and Yandex are forced into safe mode. |
| **Family filter** | Adult, gambling and dating sites blocked (about 4.7 million domains). |
| **Report to my home server** | One screen at home for every device: protection on/off, counts, warnings. **Never the sites visited.** |

The home server only works on your own Wi-Fi with a private address. Since
1.14.6 the key never travels after joining: every report is signed, and
house rules are only accepted from answers signed with the same key. See
`docs/FAMILY.md` and `docs/HOME-SERVER.md`.

---

## 7. Privacy and permissions

Noxa sends nothing about you anywhere. Its only own network calls are:

- the daily blocklist and update check (anonymous downloads from GitHub),
- your DNS lookups to the provider you chose,
- your own tunnel, if you use one,
- your own home server, if you set one up.

| Permission | Why |
|---|---|
| Internet, network state | DNS filtering; seeing Wi-Fi vs mobile data and open Wi-Fi (no location needed) |
| Foreground service, notifications | Keeps protection running; alerts and the daily count |
| Ignore battery optimisation, start at boot | So the phone doesn't kill protection |
| See all apps | Naming apps in stats, pop-up ad and spy-app checks (the list never leaves the phone) |
| Install packages | The one-tap update (signature-checked first) |

**Not asked for:** location, contacts, storage, camera, microphone.

---

## 8. Honest limits

- It filters **DNS**. An app with its own built-in encrypted DNS can slip
  past (the common ways are blocked).
- Only one VPN can run at a time. With "Hide my IP" on, blocking happens
  through the tunnel's DNS instead.
- Lists are lists: rarely a site is wrongly blocked (fix it with one tap) or
  missed.
- Android lets anyone uninstall an app; the parent lock can't stop that. A
  home server notices when a device goes silent.

---

## 9. Behind the scenes

- **Code:** Kotlin, about 8,500 lines; 121 automated tests run on every
  change.
- **Build:** Gradle 9.8, Android Gradle Plugin 9.3, Kotlin 2.3, compileSdk
  36, targetSdk 34, minSdk 24.
- **Only dependency:** the official WireGuard library (Apache-2.0).
- **Releases:** you publish a release on GitHub, and a workflow builds and
  signs the APK. It **refuses to publish** if the APK isn't signed with the
  real Noxa key, so a mistyped secret can't break anyone's updates.
- **Test builds:** every change makes a "Noxa TEST" APK that installs next
  to the real app:
  https://github.com/popeye-mk/Noxa/raw/test-apk/noxa-TEST.apk
- **F-Droid ready:** no proprietary code, no Google dependency block in the
  APK, changelogs within 500 characters, update notice off in F-Droid builds.
- **Repository safety:**
  - Main can't be deleted or force-pushed.
  - No personal email in the history.
  - The signing key lives only in GitHub secrets.
  - AI help (Claude) must report and get the owner's "yes" before any push,
    PR, merge or workflow run (`.claude/CLAUDE.md`, enforced by
    `.claude/settings.json`).

---

## 10. Where to find things

| What | Where |
|---|---|
| Latest Noxa | https://github.com/popeye-mk/Noxa/releases/latest |
| User manual | `docs/USER-MANUAL.md` |
| Family features | `docs/FAMILY.md` |
| Home server | `docs/HOME-SERVER.md` |
| Releases, F-Droid, URLhaus key | `docs/FDROID-AND-RELEASE.md` |
| What changed in each version | `fastlane/metadata/android/en-US/changelogs/` |
