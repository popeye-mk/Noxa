# Noxa: cyber-defence review

*Reviewed: 19 November 2026 · code on `main` at v1.15.1 (versionCode 32) ·
blocklist of 16 November (1,003,540 domains)*

This review comes from reading the code. Nothing here was tested on a device,
so items marked **verify on device** need a quick check on a phone before
anyone acts on them.

## Status after v1.16

| Item | Status |
|---|---|
| H1 Private DNS detection | **Done in 1.16** (check-up row, alert, home report) |
| H2 Hard-coded DNS | **Done in 1.16 as an optional switch**, off by default until tested on more devices |
| H3 Firmware-malware watch | **Done in 1.16** (alert, one-tap block, check-up row, home report) |
| M1 Lockdown switch | Open: needs a check on a device |
| M2 Botnet IP shield | Open |
| M3 `onRevoke` | **Done in 1.16** (alert at once, immediate home report) |
| M4 Tunnel key storage | **Done in 1.16** (Android Keystore, `FLAG_SECURE`) |
| M5 Parent PIN | **Done in 1.16** (PBKDF2, growing lockout, clock-proof) |
| M6 Signed blocklist manifest | Open |
| L1 Watchdog receiver | **Done in 1.16** (split; only the boot receiver is exported) |
| L2 Plain-DNS replies | **Partly done in 1.16** (the reply must match the question; the socket is still reused) |
| L3 Tap-jacking | Open |
| L4 Dependency checksums | Open |
| L5 `SECURITY.md` | **Done in 1.16** |
| L6 Parser fuzz test | **Done in 1.16** |
| Device hygiene rows | **Done in 1.16** |
| Data-smuggling detector, evidence export, larger threat feed | Open |

---

## 1. Summary

Noxa's design is sound. The parts that usually go wrong in apps like this
(updates, list downloads, the home-server channel, the release pipeline) have
already been hardened. The gaps that remain are mostly **ways around a DNS
filter**, plus a few missing checks that would catch more of what happened on
the tablet (firmware that talks to scam servers).

The most valuable additions, in order:

1. Detect Android's **Private DNS**, which silently switches Noxa's blocking off.
2. Catch apps that use a **hard-coded DNS server** (8.8.8.8 and similar).
3. A **firmware-malware watch** for system apps that keep reaching scam servers.
4. **Device hygiene rows** in the check-up (old security patch, user-installed
   certificates, USB debugging).
5. A **botnet IP shield** for malware that connects by IP address with no DNS lookup.

---

## 2. What is already strong

| Area | Status |
|---|---|
| App updates | Download only from the Noxa releases URL, SHA-256 checked, signature must be the Noxa release key, version must be newer. |
| Release pipeline | Actions pinned to commits, least-privilege tokens, the build refuses an APK signed with the wrong key. |
| Blocklist updates | HTTPS, SHA-256 from the manifest, "newer only", and the phone's own sanity check (`FilterSanity`) before swapping. |
| Weekly rebuild | Third-party downloads handled with a read-only token; must-work sites protected; source-health alerts. |
| Home server | Requests and answers signed (HMAC-SHA256), replay window, private addresses only, key never sent after joining. |
| Backups | Cloud backup and device transfer excluded, so keys and the PIN hash stay on the device. |
| App surface | Only the launcher, link check, widget, tiles and boot receiver are exported; PendingIntents are immutable. |
| Privacy | No location permission, no analytics; the live feed is memory-only; domains are not written to logcat. |
| DNS engine | CNAME uncloaking, rebinding shield, DoH canary and DoH-hostname blocking, IPv4 and IPv6 capture. |

---

## 3. Findings

Priority reflects how much real protection the fix adds for ordinary users.

### High

**H1. Private DNS turns Noxa off without any sign.**
If Android's *Settings → Network → Private DNS* is set to a provider name
(strict mode), the system sends lookups straight to that provider over DNS
over TLS. They never reach Noxa, so nothing is blocked and nothing is counted,
while the app still says "Protected". The code has no check for this.
*Fix:* add a check-up row and a `HealthMonitor` alert. `LinkProperties.isPrivateDnsActive`
and `privateDnsServerName` (Android 9+) report it without any permission.
Offer a one-tap jump to the setting. *Effort: small.*

**H2. Apps with a hard-coded DNS server bypass the filter.**
Noxa routes only its own DNS address (`10.111.0.2`) into the tunnel. An app or
firmware component that sends lookups directly to `8.8.8.8`, `1.1.1.1` or
`9.9.9.9` is never seen. Smart-TV apps and cheap-tablet firmware do this often.
*Fix:* also route the well-known public resolver addresses (IPv4 and IPv6) into
the tunnel. Answer UDP port 53 to those addresses through the normal filter,
and drop TCP 853 (DNS over TLS) so the app falls back. Noxa's own traffic is
already excluded from the tunnel, so its upstream lookups are unaffected.
Make it a switch ("Catch apps that use their own DNS") that is on by default.
*Effort: medium; needs testing on a phone and a TV.*

**H3. Firmware malware: system apps get the same gentle treatment as normal apps.**
This is the tablet case. Today a system app that reaches a scam server gets
one "reached a dangerous server" alert, rate-limited, and nothing more. The
pop-up-ad scan, the spy-app watch and the stuck-app detector all skip system
apps on purpose.
*Fix:* a "repeat offender" rule. When a **system app, or the unattributed
system bucket,** hits the dangerous-site list on several separate days:
- raise one strong alert that says plainly this is the device's own software;
- offer one tap to **block that app completely** (the per-app firewall already
  works for system apps);
- explain how to disable the app in Android settings;
- include it in the home-server report, which already has `apps_30d`.

*Effort: small to medium. This is the feature that matches your own experience best.*

### Medium

**M1. "Block connections without VPN" may cut all internet (verify on device).**
The check-up and `docs/FAMILY.md` recommend Always-on VPN **with** "Block
connections without VPN". Noxa routes only DNS through its tunnel. On most
Android versions that lockdown switch blocks everything that does not go
through the VPN, which for a DNS-only tunnel is nearly all traffic. Other
DNS-only blockers tell users to leave it off.
*Check:* turn both switches on and open a website. If it fails, change the
check-up text and the family guide to recommend Always-on **without** the
block switch. If it works on your devices, note which Android versions were tested.

**M2. Malware that connects by IP address is invisible.**
Some botnets skip DNS and connect to a fixed IP. A DNS filter cannot see that.
*Fix:* a small "botnet IP shield". Add routes for a short, high-confidence list
of command-and-control addresses (for example abuse.ch Feodo Tracker, a few
hundred entries) so those packets arrive in the tunnel. Drop them, name the
app, and alert. Keep the list small: every route costs memory, and a wrong
entry blocks a real server. *Effort: medium.*

**M3. No immediate reaction when the VPN is taken away.**
`onRevoke()` is not overridden. If another VPN app takes the slot, or someone
disconnects Noxa in Android's VPN settings, the service just stops. The
watchdog notices up to 15 minutes later.
*Fix:* override `onRevoke()` to notify at once. With the parent lock on, also
send the home server a "protection was switched off outside Noxa" report.
*Effort: small.*

**M4. The WireGuard private key is stored as plain text.**
The tunnel config, including the private key, sits in `SharedPreferences`.
App-private storage and the backup exclusion protect it in normal use, but not
on a rooted or forensically imaged device.
*Fix:* encrypt it with an AES-GCM key held in the Android Keystore (no new
library needed). Also set `FLAG_SECURE` on the tunnel screen so the key does
not appear in screenshots or the recent-apps preview. *Effort: small.*

**M5. Parent PIN: weak hash and a clock-based lockout.**
The PIN is stored as one round of salted SHA-256, and the lockout uses the
wall clock. A child can skip the one-minute lockout by changing the device
time. Five tries a minute lets a 4-digit PIN fall in about a day and a half.
*Fix:* use PBKDF2 (built into Android) with a high iteration count, make the
lockout grow (1 min, 5 min, 30 min, …), and base it on a counter that a clock
change cannot reset. *Effort: small.*

**M6. Blocklist updates are checked for integrity, not for origin.**
The list's SHA-256 comes from the same place as the list. Anyone who could
change files in the repository could ship a different list. `FilterSanity` and
TLS limit the damage, but a signature would close it.
*Fix:* sign `blocklist-manifest.json` in the workflow with an ECDSA P-256 key
(an Actions secret) and verify it in the app with the public key compiled in.
*Effort: medium. Lower priority than H1–H3.*

### Low

**L1. The watchdog receiver accepts broadcasts from any app.**
`WatchdogReceiver` is exported so it can receive the boot broadcast, but any
app can send it an explicit intent with its own extras. The effect today is
minor (an extra health check, a kill not being recorded).
*Fix:* split it. A small exported receiver handles only `BOOT_COMPLETED`,
which other apps cannot fake; the alarm receiver becomes non-exported.

**L2. The plain-DNS fallback is easier to spoof than it needs to be.**
Each worker keeps one UDP socket, so its source port never changes, and a
reply is accepted on transaction ID and source address alone. This path is
used only when encrypted DNS fails.
*Fix:* open a fresh socket per fallback query and check that the reply's
question matches the query.

**L3. No tap-jacking protection on sensitive buttons.**
The PIN dialog, "Don't filter this app" and the allow buttons do not set
`filterTouchesWhenObscured`. Recent Android versions block most overlay
attacks, but older ones (Noxa supports Android 7) do not.

**L4. Build dependencies are not pinned by checksum.**
The Gradle wrapper is checksum-pinned, but there is no
`gradle/verification-metadata.xml`. Adding one makes a tampered library fail
the build.

**L5. No security policy file.**
There is no `SECURITY.md`. Add a short one saying how to report a
vulnerability privately (GitHub's private vulnerability reporting can be
switched on in the repository settings).

**L6. The packet parser has no fuzz test.**
`DnsPacket` reads bytes from every app on the device and from the network.
The unit tests cover known cases. A simple test that feeds it random and
mutated packets and asserts it never throws would be cheap insurance.

---

## 4. New defences worth adding

**Device hygiene rows in the check-up.** These need no permissions and run offline:

| Check | Why it matters | Source |
|---|---|---|
| Security patch older than 12 months | Cheap tablets and TVs often never get patches | `Build.VERSION.SECURITY_PATCH` |
| User-installed CA certificates | Lets someone read encrypted traffic; a classic stalkerware and "free VPN" trick | `AndroidCAStore` aliases starting `user:` |
| USB debugging on | Anyone with a cable can control the device | `Settings.Global.ADB_ENABLED` |
| Private DNS active | See H1 | `LinkProperties` |
| A global proxy is set | Traffic may be going through someone else | `ConnectivityManager.getDefaultProxy()` |

**Data-smuggling detector.** Malware can leak data inside DNS lookups: many
long, random-looking subdomains of one domain in a short time. A small counter
per app and domain can flag that pattern and name the app. Start as an alert
only, with no blocking, to see how often it fires on normal apps.

**Evidence export.** After an alert like the tablet's, let the user save a
short report: app name, package, the dangerous domains, counts and dates. That
is what a shop, a manufacturer or a consumer authority asks for. "Save report"
exists already; this is a focused version for one app.

**Stronger threat list as an option.** Noxa ships HaGeZi's "mini" threat feed
to stay small. An optional larger feed (like the family filter: downloaded
only when switched on) would catch more for people who want it.

---

## 5. F-Droid notes

- **Self-update permission.** `REQUEST_INSTALL_PACKAGES` is in the manifest of
  every build. The updater is already switched off in builds not signed with
  the Noxa key, but a reviewer may still ask why the permission is there. A
  build flavour without the permission and the update screen would answer
  that cleanly. Otherwise, be ready to point to `AppUpdater.signedWithReleaseKey`.
- **`QUERY_ALL_PACKAGES`.** Already explained in `docs/FDROID-AND-RELEASE.md`.
- **Reproducible builds.** If F-Droid can reproduce the release APK
  byte for byte, it can publish the copy signed with the Noxa key. Users could
  then move between GitHub and F-Droid without reinstalling. Minification is
  already off, which helps. Optional, and best asked about in the F-Droid request.
- **Network use.** The README now states exactly what Noxa connects to, which
  is what reviewers look for.

---

## 6. Suggested order of work

| Step | Items | Why first |
|---|---|---|
| 1 | H1, M1, M3 | Small changes that stop silent loss of protection |
| 2 | H3, device hygiene rows | Directly addresses firmware malware; small and offline |
| 3 | H2 | The biggest bypass; needs careful device testing |
| 4 | M4, M5, L1, L2, L5 | Hardening with low risk |
| 5 | M2, M6, the rest | Larger pieces, lower urgency |

Steps 1 and 2 fit in one release. Step 3 deserves its own release and a test
build on both a phone and a TV before publishing.

---

## 7. Limits that stay

- Noxa filters **DNS**. It cannot see what an app sends to a server it is
  allowed to reach.
- Anyone holding the device can uninstall Noxa, clear its data or switch the
  VPN off in Android settings. The parent lock cannot prevent that; the home
  server can notice it.
- Malware inside the firmware can be **contained** by blocking its lookups, as
  it was on the tablet, but not removed. Only a clean system image or a
  different device removes it.
