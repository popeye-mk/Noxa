# Guardian — Phase 2: Per-app firewall + stats dashboard

**Status: ✅ DONE — verified on device (Mi 11T Pro).** Per-app attribution, stats, dashboard, and the firewall all confirmed on the phone; blocking a specific app (Google Play Store) cut its internet completely, and turning it back off restored it.

**Goal:** give users control per app, and visibility into what's happening, without adding any complexity to the default experience. Everything rides the **same `VpnService` interception point** from Phase 1 — no separate engine.

---

## Done when (exit criteria)

- A non-technical user **never needs this screen** to be protected — the default is still one on/off switch.
- A curious user can drill in **one tap** and get real per-app answers: what's blocked, per app, and block any app entirely.
- No regression to Phase 1: still cool, still blocking, counter still persists.

---

## The steps

### Step 1 — Per-app attribution + firewall on the same pipeline
- [x] Map each DNS query to the **app that made it** — `ConnectivityManager.getConnectionOwnerUid()` (API 29+) → UID → package name, cached (`GuardianVpnService.ownerOf`).
- [x] Add a **per-app firewall**: if an app is on the block list, every one of its lookups is sinkholed — implemented as a branch in the *same* `when` block as the tracker filter, not a new service (`AppStats.isFirewalled`).
- [x] Firewall set persists to disk (`AppStats`, JSON in SharedPreferences).

**Checkpoint (device):** flip an app's switch → that app loses internet; flip it back → it works again.

### Step 2 — Per-app stats, persisted
- [x] Count **blocked** and **allowed** lookups per app (`AppStats.recordBlocked/recordAllowed`).
- [x] Persist to disk periodically (same 50-event flush as the global counter) and on stop, so a MIUI kill can't lose them.

**Checkpoint (device):** use a few apps, reopen the dashboard, counts are there and survive a restart.

### Step 3 — Per-app dashboard (one tap deeper)
- [x] `AppsActivity`: apps listed **most-blocked first**, each row shows `label · N blocked · M allowed` and a **block-this-app** switch.
- [x] Main screen unchanged except **one** new button — "Per-app details ›". The default experience is still just the switch.

**Checkpoint (device):** the list matches real usage; the main screen still feels like one switch.

### Step 4 — Export the block log
- [x] **Export stats (CSV)** button → shares `app_package,blocked,allowed,firewalled` via the Android share sheet (save to Drive, email, etc.) — no manual weekly tallying.

**Checkpoint (device):** export produces a CSV you can open with the real numbers.

### Step 5 — Keep the default simple
- [x] All per-app depth is behind the one "Per-app details ›" tap; no configuration on the front screen, no wizard.

---

## Quick progress tracker

| # | Step | Status |
|---|------|--------|
| 1 | Per-app attribution + firewall (same pipeline) | ✅ Done — apps attributed on device; Play Store blocked completely when switched on, restored when off |
| 2 | Per-app stats, persisted | ✅ Done — per-app blocked/allowed counts shown live on device |
| 3 | Per-app dashboard (one tap deeper) | ✅ Done — list renders, most-blocked first, with per-app switches |
| 4 | CSV export of block log | ✅ Done — Export button present and wired to the share sheet |
| 5 | Default stays a single switch | ✅ Done (button only) |

**Phase 2 verified on device (Mi 11T Pro) — 2026-08-16.** Only lingering watch: battery over a longer run, since attribution adds a per-lookup system call (Phase 1's cool-running win must hold). Not a blocker; monitor like Phase 1's Step 8.

---

## Notes / honest limits (to validate on device)

- **Firewall = DNS-level.** Blocking an app sinkholes its DNS, which stops virtually all apps. An app using **hard-coded IPs** could still connect — a full traffic-level block would need routing all traffic (not just DNS) through the tun, deferred to keep the pipeline light.
- **Attribution cost.** `getConnectionOwnerUid` is a binder call per DNS query. DNS is low-volume so it should be fine, but **re-check battery/heat on device** (Phase 1's hard-won win must not regress).
- **API 29+ for attribution.** Below Android 10, lookups group under `(system / unknown)`; the firewall/stats still work, just less granular. `minSdk` is 24.
- **IPv4 only**, matching the Phase-1 pipeline (IPv6 DNS capture is still deferred).

## New/changed files

- `AppStats.kt` — per-app stats + firewall set + persistence + CSV export.
- `GuardianVpnService.kt` — `ownerOf()` attribution; firewall + per-app counting in the loop; loads/saves `AppStats`.
- `AppsActivity.kt` — the dashboard (built programmatically, no new layout files).
- `MainActivity.kt` / `activity_main.xml` / `strings.xml` — the one "Per-app details ›" button.
- `AndroidManifest.xml` — registers `AppsActivity`.
