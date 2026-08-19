# What Makes Guardian Different (the mission)

**Status: 📌 Reference — read before building anything. This is the "why."**

Keep this open while building. Every feature decision checks back against it. If a change doesn't serve the mission below, it doesn't go in — no matter how clever it is.

---

## The one-sentence mission

**One free app that gives a non-technical person real, basic safety out of the box — without installing five blockers, two firewalls, and paying a subscription to unlock the parts that actually matter.**

---

## The problem nobody else is solving

Most privacy tools protect the wrong layer. Trackers, VPNs, ad-IDs, cookies — all about **collection**. But collection was never the real ceiling. **Inference is.**

The paradox: the moment you install a tracker blocker or run a VPN, you don't disappear — you become a *rarer* pattern. Fewer than 5% of users block trackers. A self-made blocklist can make you the **only person on earth** with that exact signature. This is **anonymity-set collapse**: privacy tools only work when enough other people use the *same* tool in the *same* config. Most "privacy" software optimizes for individual protection while accidentally destroying the crowd that protection depends on.

No consumer blocker warns you about this. They all optimize for "blocked more = better" and ignore that the config itself is a fingerprint. **Guardian is the one that tells you.**

---

## The design law (above every feature)

Every feature is worthless if it needs a technical person to configure it. So, in order:

1. **One tap, zero configuration.** Install → tap enable → done. That's the entire onboarding.
2. **Everything on by default.** Blocklists, per-app firewall, IPS/DDoS — all enabled the moment it's installed. No categories to pick, no ports, no rules.
3. **Plain language, never jargon.** No "SNI," "Bloom filter," "IPS," or "DNS" in the UI. The user reads *"Critical Strike is contacting 11 companies you don't know"* — not a log line.
4. **Advanced settings exist, but hidden.** Category toggles, per-app rules, IPS sensitivity live one tap deeper for people who want them — never required for full protection.
5. **Notifications explain impact, not mechanics.** "Blocked 40 tracking attempts today" beats "40 DNS queries dropped."

---

## What's table stakes vs what's one-of-a-kind

**Table stakes (necessary, free, but not unique):** ad/tracker blocking, per-app firewall, IPS/DDoS, malware/phishing blocking. AdGuard and others already do versions of these. We do them well because the app has to actually work — but they're not why Guardian matters.

**The five real differentiators — nobody in this space ships these:**

1. **Inference-risk warning** — calculates how unique your combination of blocked trackers, DNS setup, and device signals makes you, and warns when your "protection" has started making you *more* identifiable, not less.
2. **Local, plain-language explanations** — a small, purpose-built on-device reasoning layer (rules/templates first, tiny AI only if justified) that turns raw block logs into human sentences. Narrow scope, no cloud call, no dependency on any general assistant — small enough to ship inside the app and be fully auditable.
3. **Behavior-change / supply-chain detection** — flags when an already-installed app suddenly starts contacting new domains after an update (a common sign of a compromised SDK). Nothing consumer-facing watches for *change* over time.
4. **"Who and why" transparency** — instead of a blocked domain string, show which company owns it and what they typically collect (ad ID, location, fingerprint). Turns the tool into an education layer, not a silent filter.
5. **Provable zero telemetry** — many "free" tools phone home their own analytics. Guardian's own network traffic is open source, so "nothing leaves the device" is checkable, not just claimed.

Everything else is good and necessary. **These five are the reason it exists.**

---

## Why free is realistic (not charity, not a trick)

Every paid competitor — AdGuard, Total Adblock, Surfshark CleanWeb — is built on the **same free, community-maintained blocklists** anyone can download. The lists are the actual engine. The subscription pays for polish, infrastructure, and support — **not for better blocking**. Skipping the subscription costs you convenience, not effectiveness.

So a genuinely free, ~85–95% effective blocker is achievable by standing on the same open lists the paid tools already use. The gap between free and paid isn't blocking power — it's polish. Guardian gives away the actual protection instead of a watered-down free tier designed to nudge people toward paying. No ads to fund it, no data sale. **If it's not free and effective for everyone, it's not the point.**

---

## How it stays one app, not three stitched together

Blocklists, firewall, and IPS/DDoS all run on the **same `VpnService` interception point** — different rule layers checked against the same traffic stream, not three separate services to wire together and keep in sync. One traffic pipeline, one settings model. That's what keeps both the build and the experience simple instead of "a squid + IPS + DDoS setup wearing an app icon."

---

## Where Guardian sits (from the source doc)

| | **Guardian** | AdGuard Free | AdGuard Premium |
|---|---|---|---|
| Cost | **€0** | €0 | €2.49–5.49/mo or €79.99 lifetime |
| System-wide (non-browser apps) | ✅ | ✅ | ✅ |
| Cosmetic / first-party filtering | Partial (in-app browser) | Browser only | Full, HTTPS inspection |
| Stalkerware / phishing blocking | ✅ (via UT1) | ✅ | ✅ |
| **Anonymity-set awareness** | ✅ **(unique)** | ❌ | ❌ |
| Per-app firewall | ✅ | Premium only | ✅ |
| IPS / DDoS intrusion protection | ✅ | ❌ | ❌ |
| Play Store install | ❌ (sideload/F-Droid) | Limited version | ❌ (sideload) |

---

## Honest limits (we say these out loud)

- **Not 100% blocking.** First-party tracking (an app logging its *own* usage) and device fingerprinting aren't stoppable by any blocklist, free or paid. Anyone promising 100% is overselling.
- **No HTTPS/SNI deep inspection at launch.** That needs a root certificate on the device — powerful, but a bigger trust ask. Optional advanced feature, not a default.
- **No dedicated support team.** Personal/open project. Protection depends on blocklists being kept current — a real, ongoing task, not a one-time build.

---

## The bottom line

Built right, Guardian is both a real privacy tool and proof that basic safety doesn't have to be sold back to the people who need it most. It **gives back rather than gatekeeps.**
