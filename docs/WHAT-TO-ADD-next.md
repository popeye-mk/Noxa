# Guardian — what to add next (logical build order)

Ordered by what the product actually **needs**: make blocking better and lasting first, then trustworthy/installable, then simpler to use, and **cosmetic last** (an app icon must never block real work). Big new frontiers are kept separate at the end.

Where we are today: Phases 1–3 done on device, encrypted DNS live, and the WireGuard **tunnel + blocking-while-tunnelling combo** working (IP hidden **and** trackers blocked together). Already a strong, distinctive, free app.

---

## Build order (do roughly in this sequence)

### 1. Stronger blocklists — ✅ DONE
Added **HaGeZi Multi PRO** (217,939) and **AdGuard DNS filter** (154,730) to `build-tools/build_blocklist.py`. Filter rebuilt: **695,997 → 859,248 unique domains (2.95 MB)**, and re-verified — 5,000/5,000 known-bad, **0 false positives**, all banks + legit sites clean, Kotlin parity intact. New `.gbf` copied into the app. (Peter Lowe's skipped — heavy overlap, marginal gain.)

### 2. Blocklist auto-updates — ✅ MECHANISM BUILT (needs a host URL to go live)
`FilterUpdater` downloads a newer prebuilt `.gbf` from a public URL, verifies the `GBF1` magic, and atomically swaps it into `filesDir`; `BloomFilter.loadCurrent()` prefers it over the bundled asset. Two triggers: a **quiet once-a-day check** on service start, and a **"Check for blocklist update"** button (+ a "Blocklist: <date>" line) in the dashboard. One-way download — nothing about the user is uploaded, so zero-telemetry holds.
**To activate:** set `FilterUpdater.BASE` to the project's public raw path and host `blocklist-manifest.json` + `guardian-default.gbf` there — i.e. it goes live with **#4 (open-source on GitHub)**. Until then the button just says "couldn't reach the update source" (harmless). Publishing a fresh `.gbf` there then updates every install automatically.

### 3. CNAME uncloaking — ✅ DONE
`DnsPacket.cnameTargets()` parses the DNS *response* CNAME chain (handles name-compression); `forward()` checks each CNAME target against the filter and **sinkholes the query if any hop is a tracker** — catching trackers disguised as first-party subdomains (e.g. `metrics.site.com` CNAME→ a tracking company) that plain domain-lists miss. Blocked cloaked trackers show up in the per-app screen tagged `· CNAME-cloaked`. Parser cross-checked in `test_packets.py` (compression + chain extraction).

### 4. Open-source it — makes the whole pitch TRUE
Push the code to a public repo (GitHub/GitLab) → eventually **F-Droid**. Turns *"provable, auditable, zero telemetry, free"* from a claim into a fact anyone can verify. It's the core of the free+open+auditable identity and the main thing separating Guardian from the paid/company apps. Low effort, can be done anytime — best once the core (1–3) is strong.

### 5. Release signing + notification permission
Sign a **release build** so people can actually install it (sideload / F-Droid), not just debug builds. And request `POST_NOTIFICATIONS` on Android 13+ so the ongoing status notification always shows.

### 6. First-run explainer — ✅ DONE
One-time, plain-language "what is this" dialog on first launch (`MainActivity.maybeShowIntro`). No permissions, no dependency.

### 7. Easy config import — ✅ DONE (file import); QR-camera optional later
Built a one-tap **"Import config from file (.conf)"** via the system file picker (`ACTION_OPEN_DOCUMENT`) — **no new dependency, no camera permission** — which reads the file, validates it, and saves. This is the better fit for the providers actually in use (Proton/Mullvad hand you a `.conf` file, not a QR). **True camera-QR is deferred:** it would add AndroidX + a QR library + the camera permission, and wouldn't help file-based providers anyway. Add it only if a QR-only provider comes up.

### 8. Auto-tunnel-on for public Wi-Fi — ❌ DECIDED: SKIP (won't ask for location)
Reading Wi-Fi security/SSID to auto-detect "untrusted Wi-Fi" **requires the LOCATION permission** on modern Android — which is self-defeating for a privacy app and erodes trust for a one-tap convenience. **Decision: don't do it.** The manual **"Hide my IP"** switch stays; the user flips it on for public Wi-Fi. Holding the "we ask for nothing" line beats the convenience. (A permission-free "auto-on for *any* Wi-Fi" toggle is possible later if wanted, but not worth it now.)

### 8b. Drop the tunnel's "experimental" label — ✅ DONE
Validated on a **second provider**: Mullvad config imported (file-import), tunnel connected, IP changed (ipleak showed the Mullvad IP), combo still blocked. With Proton + Mullvad both proven, the "experimental" tag was removed from the button and the screen. Also fixed a false-positive found during this test: the filter was blocking `mullvad.net` / `protonvpn.com` (official VPN sites) — now allowlisted, while scam lookalikes (`mullvad-download.*`) stay blocked.
Still-nice-to-have for even more confidence (not blockers): a self-hosted config, IPv6-only network, and reconnect-after-network-change behaviour.

### 9. App icon + name — LAST (cosmetic)
Replace the generic Android lock icon with a real icon and polish the name. **Deliberately last** — polish never blocks function.

---

## Bigger future frontiers (after the above are solid)

- **SNI / IP-level filtering** — block trackers that skip DNS entirely (apps using hardcoded IPs or their own encrypted DNS). Deeper packet inspection; bigger effort, but closes the "apps that cheat DNS" gap.
- **Phase 4 — inference-risk warning** (the honest heuristic version; the full statistical one needs population data we won't collect).
- **Phase 5 — IPS / DDoS inbound protection** (hardest, saved for last).
- **Deep all-in-one** — Guardian's own 700k filter running *while* tunnelling (vs. today's resolver-based combo). Big, uncertain, likely overkill; a distant maybe.

---

## Honest ceiling note

DNS-level blocking caps around ~68 on ad tests — it can't hide an ad box served from the site's **own** domain; only an in-browser tool (uBlock / Brave) does that. Guardian is the **system-wide** layer and does that job well; pair it with **Brave** to max out web blocking. Items 1–3 push Guardian as high as DNS blocking can realistically go.

**Measured on device (Mi 11T Pro):** Guardian alone = **68** on adblock-tester with the 696k filter → **78** after Step 1 added HaGeZi + AdGuard (859k filter) — a real +10 from stronger lists, DNS-only. **Guardian + Brave = 96** (now higher with the bigger filter). Confirms both the layered strategy (Guardian = whole-device + IP, Brave = in-page) and that stronger lists measurably raise Guardian's own score. (Web ad-tests are noisy ±20 between runs — trust the trend, not one run.)
