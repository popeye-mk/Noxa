# Guardian — Phase 3: Plain-language explanation layer (rules-based)

**Status: 🟡 Code complete — needs on-device testing.** Rules/template-based (Phase 3a); no AI, no training data, no network. Phase 3b (a small purpose-built model) stays deferred until the rules engine hits real limits.

**Goal:** turn raw block logs into insight — every blocked event a user sees has a plain-language *who and why*, with no DNS/company-domain jargon in the UI.

---

## Done when (exit criteria)

- Every blocked thing shows a human reason: **who** the tracker was (company) and **why** (category), not a domain string.
- The dashboard reads like sentences a non-technical person understands at a glance.
- No new battery cost (it's just table lookups on the block we already do).

---

## The steps

### Step 1 — Tracker → company/category lookup
- [x] `Trackers.kt`: a compact **embedded** map of the trackers behind the large majority of real blocks → `"Company · Category"` (e.g. `doubleclick.net → Google · Advertising`, `scorecardresearch.com → Comscore · Analytics`, `appsflyer.com → AppsFlyer · Attribution`). Includes Xiaomi/MIUI trackers (relevant on the test device).
- [x] Lookup checks the domain **and each parent suffix**; unknown domains fall back to their root domain + "Tracking" so the user always sees something honest.

### Step 2 — Record the per-app company breakdown
- [x] On every block, identify the company and aggregate it per app (`AppStats.companiesByApp`, `app → ("Company · Category" → count)`).
- [x] Persisted to disk with the rest of the stats (survives kills).
- [x] Firewall blocks read "Blocked by you · Firewall"; DoH-canary blocks read "DNS-over-HTTPS · Filter bypass" — honest reasons, not fake companies.

### Step 3 — Template explanation engine
- [x] `Explanations.kt` — pure template rules over the aggregates:
  - **header:** "Guardian blocked N tracking attempts from M companies. Most persistent: A, B, C."
  - **per-app one-liner:** "Reached N tracking companies — mostly [Company]."
  - **per-app detail:** the full "who it was trying to reach" list.
- [x] No jargon leaks: the UI shows company + category, never raw domains/DNS terms.

### Step 4 — Surface it in the UI
- [x] Dashboard **summary header** (the header sentence).
- [x] Each app row gains a green **who/why** line under the counts.
- [x] **Tap an app** → a dialog with the full plain-language breakdown of every company it tried to reach and how many times.
- [x] CSV export gained a `top_company` column.

---

## Quick progress tracker

| # | Step | Status |
|---|------|--------|
| 1 | Tracker → company/category lookup | 🟡 Coded, needs device test |
| 2 | Per-app company breakdown, persisted | 🟡 Coded, needs device test |
| 3 | Template explanation engine | 🟡 Coded, needs device test |
| 4 | Explanations surfaced in the UI | 🟡 Coded, needs device test |

**Phase 3 is done when the dashboard shows real company names + plain-language reasons on the phone, with no jargon and no battery regression.**

---

## Notes / honest limits

- **Coverage.** The tracker table covers the big, concentrated players (which are most real hits). Long-tail domains show their root domain + "Tracking" — honest, just less specific. The table is trivial to extend, and a future pass could fold in a fuller open dataset (DDG Tracker Radar / Disconnect) at build time, the same way the blocklist is compiled.
- **Cumulative, not time-windowed yet.** Explanations are over running totals ("reached N companies"), not "in the last hour." A lightweight hourly window is a later refinement — it needs per-event timestamps, which we deliberately avoided to keep storage/battery tiny.
- **Phase 3b (AI) still not started** — and correctly so; the rules engine has to prove insufficient first.

## New/changed files

- `Trackers.kt` — domain → "Company · Category" lookup.
- `Explanations.kt` — the template sentence engine.
- `AppStats.kt` — per-app company breakdown + persistence + CSV `top_company`.
- `GuardianVpnService.kt` — identifies the company on each block and records it.
- `AppsActivity.kt` — summary header, per-row who/why, tap-for-detail dialog.
