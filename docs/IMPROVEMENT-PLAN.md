# Noxa — Blocking Improvement Plan (working doc)

Current state: 92–100% on independent test sites (superadblocktest.com,
getblockify.com, adblock.turtlecute.org). ~860k-domain compiled filter,
CNAME uncloaking, DoH-bypass blocking, WireGuard tunnel option.
Weakest measured spot: social/Facebook SDK tracking (88% vs 100% elsewhere).

**Principle: keep the architecture exactly as it is.** Local `VpnService` +
offline-compiled Bloom filter. No new services, no server dependency, no
change to the one-switch experience or the zero-telemetry guarantee.

---

## Status at a glance

| # | Item | Status |
|---|------|--------|
| 1 | Targeted new lists | TODO — next up |
| 2 | Weekly auto-rebuild (GitHub Action) | TODO — next up |
| 3 | Multi-hop CNAME uncloaking | **DONE — verified already covered** |
| 4 | Wildcard/pattern rule layer | LATER — hot-path change, do carefully |

---

## 1. Targeted new lists (biggest win, lowest risk)

Aimed at the measured gap (social SDK tracking), not speculative volume.

Add to `build-tools/build_blocklist.py` EXTRA_SOURCES:

- **Dandelion Sprout Anti-Malware** — malware/PUP domains the general ad
  lists don't prioritize.
  `https://raw.githubusercontent.com/DandelionSprout/adfilt/master/Alternate%20versions%20Anti-Malware%20List/AntiMalwareAdGuardHome.txt` (adblock format)
- **Meta/Facebook pixel & SDK** — the measured 88% category. Use the
  actively maintained standalone list:
  `https://raw.githubusercontent.com/jmdugan/blocklists/master/corporations/facebook/all` (hosts-like, bare domains)
  plus HaGeZi's `native.` tracker lists if needed.
- **NoCoin** (cryptomining):
  `https://raw.githubusercontent.com/hoshsadiq/adblock-nocoin-list/master/hosts.txt` (hosts format)
- **Phishing Army** (extended):
  `https://phishing.army/download/phishing_army_blocklist_extended.txt` (bare domains)

**Deliberately excluded:**

- ~~Energized Blu/Ultimate~~ — project abandoned (~2022). A stale list is
  the exact failure mode item 2 exists to prevent.
- ~~1Hosts Pro~~ — false-positive-prone; conflicts with the
  "never breaks a non-technical user's phone" rule. Revisit only if a
  measured gap remains after the additions above.

After adding: rebuild, spot-check `merged-domains.txt` for obvious legit
domains, run `test_filter.py`, re-test on device.

## 2. Weekly auto-rebuild pipeline (GitHub Action)

The biggest silent killer is a stale list, not a missing one. The phone-side
updater (`FilterUpdater`) already pulls `guardian-default.gbf` from this
repo once a day — automating the rebuild completes the loop: users get
fresh lists forever with zero manual work.

- `.github/workflows/rebuild-filter.yml`, `schedule: cron "0 4 * * 1"`
  (Mondays 04:00 UTC) + `workflow_dispatch` for manual runs.
- Steps: checkout → download all list sources → `python3
  build-tools/build_blocklist.py` → run `test_filter.py` (abort on failure —
  never ship an unverified filter) → commit `app/src/main/assets/
  guardian-default.gbf` + `blocklist-manifest.json` only if changed.
- Note: ~8 MB binary committed weekly grows repo history. Acceptable for
  now; if it becomes a problem, publish the .gbf as a Release asset instead
  and point `FilterUpdater.BASE` there.

## 3. Multi-hop CNAME uncloaking — VERIFIED DONE

`DnsPacket.cnameTargets()` walks **every** answer record and collects
**every** CNAME in the response. Resolvers return the full chain
(a.site.com → b.tracker.net → c.evil.com) in a single answer, so 2–3-hop
evasion chains are already inspected end-to-end. No work needed; covered
by existing `forward()` logic in `GuardianVpnService`.

## 4. Wildcard / pattern rule layer (do last, carefully)

A domain Bloom filter can't catch randomized rotating subdomains
(`x7f3a9.adnet.example` changing daily). Real gap, but:

- It's the **hot path** — runs on every DNS lookup. We already fought one
  battery fire; keep this to a handful of compiled patterns, checked only
  AFTER the Bloom filter misses (so the common case pays ~zero cost).
- Format: a tiny static pattern table shipped in the app (not
  user-configurable, not a general regex engine).
- Only add patterns backed by an observed miss on a real device or test.

## Honest limits (unchanged by any of this)

Same-domain/cosmetic ads and in-service tracking while logged in are
structurally out of reach for DNS-level blocking — documented in the
README, stays documented. The goal is closing measured gaps, not
inflating a score.
