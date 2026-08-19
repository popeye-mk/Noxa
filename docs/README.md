# Guardian

A free, open, easy-to-use Android app that protects people from tracking, ads, and malware — all in one app, on one shared traffic pipeline. Built to be right, not fast. No premium tier, no ads, no data sale, provable zero telemetry.

> Built for the people, and for us. No rush — each phase has to actually work before the next one starts.

**Status: ✅ Phase 1 DONE — core blocker proven on a real device. Next: Phase 2.**

---

## All the text docs live here (one folder)

Everything written lives in `docs/`. Nothing scattered.

- **[TRANSFER-AND-SETUP.md](TRANSFER-AND-SETUP.md)** — moving to the dev PC + which blocklists to download. Start here on the other PC.
- **[WHAT-MAKES-GUARDIAN-DIFFERENT.md](WHAT-MAKES-GUARDIAN-DIFFERENT.md)** — the mission and the "why." Read this first.
- **[GUARDIAN-BUILD-PLAN.md](GUARDIAN-BUILD-PLAN.md)** — the full plan, all 5 phases.
- **[PHASE-1-core-blocker.md](PHASE-1-core-blocker.md)** — Phase 1 broken into every step, with checkpoints. ✅ Done.
- **[PHASE-2-per-app-firewall.md](PHASE-2-per-app-firewall.md)** — Phase 2 per-app firewall + stats. ✅ Done (on device).
- **[PHASE-3-plain-language.md](PHASE-3-plain-language.md)** — Phase 3 plain-language who/why layer. ✅ Built (on device).
- **[PHASE-TUNNEL-ip-hiding.md](PHASE-TUNNEL-ip-hiding.md)** — optional IP-hiding tunnel (WireGuard/Tor). 🟡 Tunnel + blocking-while-tunnelling combo built; testing on device.
- **[WHAT-TO-ADD-next.md](WHAT-TO-ADD-next.md)** — prioritized list of what to add to make it a real, shippable, lasting free app (open-source it + blocklist updates first).
- **README.md** — this file, the map.

The two PDFs stay in the folder above as untouched originals.

---

## How we work (read this first)

Five simple rules so the work stays clean:

1. **Clean steps, in order.** Do the steps in the order written. Each step has a *checkpoint* at the end — that checkpoint must pass before moving to the next step.
2. **No double work.** Each thing gets built once. A blocklist is downloaded once, merged once, compiled once. If it's already done, we don't redo it — we reuse it.
3. **No shortcuts.** We don't skip a step to "get ahead." A skipped step becomes a bug every later phase inherits. If a step is hard, we slow down, we don't jump over it.
4. **Mark it done.** When a step is finished and its checkpoint passes, tick its box `[x]` and update the status line. When a whole file's work is complete, change its top status to ✅ **DONE**.
5. **One shared pipeline.** Everything is a layer on the same `VpnService` point — never a separate engine, never a parallel copy of something we already have.

### How to mark things done

- **A step:** change `- [ ]` to `- [x]` in the phase file.
- **A phase:** change the status line at the top of that phase file to `✅ DONE`, and update the table below.
- **The project:** the status line at the top of this README always shows the current phase.

Status labels we use everywhere: ⚪ **Not started** · 🟡 **In progress** · ✅ **Done**

---

## The five phases at a glance

| Phase | What it adds | Status |
|-------|--------------|--------|
| **1 — Core blocker** | System-wide ad/tracker/malware blocking (the foundation) | ✅ Done — runs on device, ~4,900/day blocked (≥ DDG), no battery hit |
| **2 — Per-app firewall + stats** | Control and visibility per app | ✅ Done — per-app stats + firewall verified on device |
| **3 — Plain-language layer** | Every block explained in plain words | 🟡 Code complete — needs device test |
| **4 — Inference-risk warning** | Warns when your setup itself is a fingerprint | ⚪ Not started |
| **5 — IPS / DDoS protection** | Inbound attack protection | ⚪ Not started |

---

## The rules that never change

- **Easy by default** — one on/off switch, no jargon, no setup wizard.
- **One shared pipeline** — everything is a layer on the same `VpnService` point.
- **Free, always** — no paywall, no ads, no data sale. Distributed via sideload / F-Droid.
- **Provably zero telemetry** — the app's own traffic is open and auditable.
- **No rush** — a phase is "done" only when its exit criteria are really met and tested.

---

## Next step

Start **Phase 1**. Open [PHASE-1-core-blocker.md](PHASE-1-core-blocker.md) and begin at Step 1 — setting up the `VpnService` local sinkhole. Follow the steps in order, tick each box when its checkpoint passes.

## Planned project layout (grows as we build)

```
privacy/
├── guardian-build-plan.pdf          ← original (untouched)
├── privacy-the-inference-problem.pdf ← original (untouched)
├── docs/                            ← all text lives here
│   ├── README.md                    ← the map (this file)
│   ├── GUARDIAN-BUILD-PLAN.md       ← full plan, 5 phases
│   └── PHASE-1-core-blocker.md      ← Phase 1 steps
├── blocklists/ut1/                  ← ✅ UT1 archive, extracted (all categories)
├── build-tools/                     ← ✅ Bloom filter compiler + verified output
│   ├── build_blocklist.py
│   └── out/guardian-default.gbf     ← the compiled 0.92 MB filter
└── app/                             ← 🟡 the Android app (scaffolded)
    ├── HOW-TO-BUILD.md              ← plain-language build guide
    └── src/main/...                 ← Kotlin code + the filter as an asset
```

Built so far: the blocklist pipeline (verified) and the app skeleton. Next: add the 3 open lists, then build/test on a real phone. Slow and steady.
