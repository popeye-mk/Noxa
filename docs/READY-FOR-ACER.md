# Ready for the Acer — Phase 1 handoff

Everything that can be built and checked without a phone is **done and verified on the Dell**. The only thing left for Phase 1 is building the APK and testing on a real device — that's the Acer's job. This note is the turnkey checklist. (Supersedes the "download the lists" steps in `TRANSFER-AND-SETUP.md` — those are already done.)

---

## What's already done — do NOT redo

- **Blocklists downloaded and folded in:** UT1 protection set + StevenBlack + EasyPrivacy + EasyList + OISD Big.
- **Filter compiled & copied into the app:** `695,997` domains → `2.39 MB` at `app/src/main/assets/guardian-default.gbf` (+ `blocklist-manifest.json`).
- **Two build-tool bugs fixed:** path-specific ad-rules no longer sinkhole whole sites (Google/PayPal etc.); a hard `bank`/`financial`/`liste_blanche` allowlist now protects real banks from every source.
- **Packet-layer hardening added:** IPv6 DNS, qtype-aware sinkhole (A→0.0.0.0, AAAA→::, else NODATA), DoH canary → NXDOMAIN, TCP-DNS dropped on purpose.
- **All offline checks pass:** `test_filter.py` (0 FP / 200k), `verify_kotlin_math.py` (40,004 / 0 mismatch), `test_packets.py` (checksums + layout).

## Before you copy the folder to the Acer

- **Delete the extracted UT1 tree** to keep the copy small: `blocklists/ut1/blacklists/` (~141 MB, auto-recreated). **Keep** `blocklists/ut1/blacklists.tar.gz`.
- Use an **exFAT** USB (no 4 GB / file-count limits).

## On the Acer — build & run

1. Install **Android Studio** (bundles the JDK). Python 3 is only needed if you later rebuild the filter — you don't have to; it's already built.
2. **Open** the `privacy` folder in Android Studio; let the Gradle sync finish (first time downloads build tools).
3. Connect an Android phone with **USB debugging** on, or start an emulator.
4. Press **Run ▶**. Guardian installs and opens.
5. Tap the switch → allow the VPN prompt → the "blocked" counter should start climbing.

## The two checks that actually close Phase 1

- **Step 6 (works correctly):** with protection on, a normal site (e.g. wikipedia.org) loads fine, and the blocked counter climbs during browsing. Ideally confirm on both IPv4 and IPv6 Wi-Fi.
- **Step 8 (as good as DDG):** run it through real daily use for several days, compare the blocked total against your **DuckDuckGo baseline of 22,616 tracking attempts in 5 days (~4,523/day)**, and confirm **no noticeable battery/perf hit**. Phase 1 is done only when it matches or beats that with no battery cost.

## If you ever rebuild the filter (added/updated a list)

```
cd build-tools
python3 build_blocklist.py
python3 test_filter.py          # 0 false positives, banks clean, trackers blocked
python3 verify_kotlin_math.py   # Kotlin lookup parity
python3 test_packets.py         # packet byte-math
cp out/guardian-default.gbf ../app/src/main/assets/guardian-default.gbf
cp out/manifest.json         ../app/src/main/assets/blocklist-manifest.json
```

## Small known nice-to-have (not blocking)

`POST_NOTIFICATIONS` isn't requested at runtime, so on Android 13+ the ongoing notification may be hidden. The filter still runs; add a runtime request later if you want the notification always visible.
