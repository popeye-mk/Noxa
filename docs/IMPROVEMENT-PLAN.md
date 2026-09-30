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
| 2 | Weekly auto-rebuild (GitHub Action) | **DONE — v1.6** (`rebuild-filter.yml`, gated by `verify_build.py`) |
| 3 | Multi-hop CNAME uncloaking | **DONE — verified already covered** |
| 4 | Wildcard/pattern rule layer | LATER — hot-path change, do carefully |
| 5 | Resolver speed (workers, cache, ID check, DoH back-off) | **DONE — v1.4** |
| 6 | Filter download integrity (SHA-256 + size check) | **DONE — v1.4** |

---

## 1. Targeted new lists (biggest win, lowest risk)

Aimed at the measured gap (social SDK tracking), not speculative volume.

Add to `build-tools/build_blocklist.py` EXTRA_SOURCES:

- **Dandelion Sprout Anti-Malware** — malware/PUP domains the general ad
  lists don't prioritize.
  `https://raw.githubusercontent.com/DandelionSprout/adfilt/master/Alternate%20versions%20Anti-Malware%20List/AntiMalwareAdGuardHome.txt` (adblock format)
- **Meta/Facebook pixel & SDK** — the measured 88% category.
  ⚠️ NOT `jmdugan/blocklists/.../facebook/all`: that list blocks ALL of
  Facebook, Instagram, WhatsApp and Messenger (it was only harmless because
  it was parsed in the wrong format). Removed in v1.6; `verify_build.py`
  now refuses any filter that blocks Meta's core apps. Use tracker-only
  lists (e.g. HaGeZi `native.` lists) if the gap needs closing.
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

## 5. Resolver speed — DONE (v1.4)

- Allowed lookups are resolved by 4 worker threads (each with its own
  upstream socket); the tun loop only parses, filters and sinkholes. One
  slow upstream answer no longer blocks every app's DNS.
- `DnsCache`: LRU of raw upstream answers (1000 entries, min-TTL, capped
  5 min, negative answers 60 s, never SERVFAIL/truncated). Blocking
  decisions — filter, firewall, allowlist, CNAME-uncloaking — still run on
  every lookup, so the cache can't bypass a block.
- Plain-DNS fallback only accepts a reply whose transaction ID matches
  (a late reply to a timed-out query used to reach the next query).
- DoH back-off grows 5 s → 10 s → 20 s → 30 s instead of a flat 30 s.
- Cross-checked in `build-tools/test_packets.py` ("DNS cache" section).

## 6. Filter download integrity — DONE (v1.4)

The manifest now carries the `.gbf` SHA-256 (written by
`build_blocklist.py`); `FilterUpdater` refuses a download that doesn't
match, and `BloomFilter.load` rejects any file whose body isn't exactly
`ceil(m_bits/8)` bytes.

## v2.0 ambition: Noxa's own filter INSIDE the tunnel

Today, tunnel mode and blocker mode share Android's single VPN slot: while
the WireGuard tunnel is up, blocking happens via AdGuard's resolver inside
the tunnel — solid, but not our 900k list, and our counter/per-app stats
pause. The real solution is ONE VpnService that does both jobs: WireGuard
transport + our DNS filter in the same pipeline (the RethinkDNS-class
architecture). Weeks of careful work; the single most valuable feature on
the long-term roadmap. Until then the modes stay separate and honest about
it (UI says counter pauses in tunnel mode).

## Honest limits (unchanged by any of this)

Same-domain/cosmetic ads and in-service tracking while logged in are
structurally out of reach for DNS-level blocking — documented in the
README, stays documented. The goal is closing measured gaps, not
inflating a score.
