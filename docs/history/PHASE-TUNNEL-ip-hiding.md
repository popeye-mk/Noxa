# Guardian — Optional privacy tunnel (hide my IP)

**Status: ✅ WORKING & VALIDATED — no longer "experimental".** Tunnel + blocking-while-tunnelling combo proven on device with **two providers (Proton + Mullvad)**. With "Hide my IP" on: public IP is the provider's (confirmed via ipleak) AND ads/trackers are blocked at the same time. Config import via file picker. "Experimental" label removed after the Mullvad test. Backups: `...-v0.4-tunnel-working.tgz`, `...-v0.5-combo-working.tgz`.

The one feature that hides your **public IP** (encrypted DNS only hid your lookups). Built deliberately, on the live folder with frozen backups so a failed attempt costs nothing.

**Mission rule (unchanged):** Guardian runs **no servers**. The tunnel routes through infrastructure *the user* trusts — their own **WireGuard** endpoint, or **Tor** via Orbot. And it must stay **simple** for non-technical users (paste a config / one tap), never raw network settings.

---

## The hard constraint (why this is the tough one)

**Android allows only ONE active VPN at a time.** Everything before this only touched **DNS** packets and let real traffic flow past. A tunnel must grab **all** traffic, encrypt it, and send it to the endpoint — and because you can't run two VPNs, Guardian itself has to *become* the tunnel. That's the crux.

## Architecture options (decide as we get it testable)

- **A — Mode switch (simplest, likely v1).** "Block on-device" (today's `GuardianVpnService`) **or** "Hide my IP" (WireGuard tunnel via the official `GoBackend`) — mutually exclusive, user flips between them. In tunnel mode, blocking is done by pointing the tunnel's DNS at a filtering resolver (e.g. Quad9). **Tradeoff:** tunnel mode loses the local 700k Bloom filter + per-app stats/firewall. Simple, shippable, honest.
- **B — Integrated (ideal, hard).** Guardian keeps owning the VpnService, does DNS blocking as now, and feeds allowed packets into an embedded **wireguard-go userspace device** for encryption. Keeps *all* features + tunnel together. Much more engineering; the real long-term goal.
- **C — Cheat.** Don't combine — use a separate WireGuard app when you want your IP hidden, Guardian when you want blocking. Zero build; you lose blocking while tunneling.

**Leaning:** ship **A** first (simple, real IP-hiding, low risk), keep **B** as the north star.

## UX design (decided with the user)

- The tunnel is a **toggle**, exactly like "Encrypt my DNS" — **default OFF**. The user flips it **on when they need it** (untrusted/public WiFi) and off at home.
- This lines up with option A perfectly: **tunnel OFF = normal on-device blocking; tunnel ON = WireGuard tunnel** (IP hidden, traffic encrypted from the local network, blocking via a filtering resolver). The toggle *is* the mode switch.
- Recommended endpoint for the "public WiFi" use case: **Proton VPN (WireGuard, free tier is fine)** — fast, reputable, no-logs. **Tor via Orbot** stays the max-anonymity / fully-free alternative.
- **Future nicety (ties into Phase 5's "public WiFi auto-hardening"):** auto-enable the tunnel on open/untrusted WiFi and relax on trusted home WiFi — so the user doesn't even have to remember. Manual toggle first, auto-detect later.

## Two optional paths (user's call — offer both, Tor first)

Both live on the one "Hide my IP" screen, clearly labelled, one tap deeper. The user picks; neither is in the default face.

- **Path A — Tor (free, easiest, a bit slower).** No account, no config, no payment. Best non-tech option. Uses **Orbot** (the Tor app). Honest catch: Android's one-VPN rule means Guardian hands off to Orbot rather than running Tor inside itself — so "one tap" is really "install Orbot once, then flip it on."
- **Path B — WireGuard provider (faster, needs a config).** For people who want speed. Import made non-tech by **QR-code scan** (point camera at the provider's QR) — text-paste stays only as a power-user fallback.

## Increments (each builds + installs before the next)

1. **[done] Scaffolding, no dependency.** `TunnelActivity` — the screen exists, saves a pasted config, plain-language.
2. **[in progress] Tor path (dependency-free).** A "Use Tor (free)" button: if Orbot is installed, launch it; if not, send the user to install it. Guided, non-tech, no new library. Build stays green.
3. **QR-code import for WireGuard configs.** Camera + a QR decoder → scan the provider's QR instead of pasting. (Adds a camera permission + decoder library.)
4. **[done] Add the WireGuard library + validate config.** `com.wireguard.android:tunnel:1.0.20230706` + core-library desugaring. Built clean on the first try (native libs `libwg-*.so` packaged unstripped — harmless warning). "Check & save" validates a pasted config with the real `Config.parse`.
5. **[✅ DONE — works on device]** Establish the WireGuard tunnel + on/off toggle. `TunnelController` (GoBackend) brings a saved config UP/DOWN; the "Hide my IP" switch works; **public IP confirmed changed on the Mi 11T Pro.** Mode-switch: turning the tunnel on stops Guardian's blocking VPN (one-VPN rule). Backed up as `guardian-known-good-v0.4-tunnel-working.tgz`.
6. **[✅ DONE — proven on device] Blocking WHILE tunnelling — the "all-in-one" combo.** Went with the pragmatic flavour: a "Block ads & trackers in the tunnel" toggle (default on) rewrites the config's DNS to AdGuard's blocking resolver, so the tunnel gives IP-hidden **+** tracker-blocking together. Verified: adblock-tester ≈ 68 with the tunnel up, IP = provider's. Two flavours were:
   - **Pragmatic (achievable, recommended):** when the tunnel is up, point its DNS at a **tracker-blocking resolver** (e.g. AdGuard DNS, or the provider's own blocking DNS). Gives IP-hidden **+** tracker-blocking together, simply. Tradeoff: it's resolver-based blocking, not Guardian's local 700k Bloom filter.
   - **Purist (our local filter + tunnel):** Guardian owns the tun, filters DNS against the Bloom filter, and feeds allowed packets into a wireguard-go userspace device. Keeps *our* blocking. But the high-level WireGuard library (GoBackend) owns the tun and doesn't expose this — it'd mean reimplementing the backend. Big, uncertain, likely **overkill**. Reserved as a distant maybe.

## Exit criteria

- With the tunnel on, a "what's my IP" check shows a **different IP** (the endpoint's), traffic works, and it's presented simply (paste config / one tap) — no jargon.
- With it off, Guardian returns to normal on-device blocking. Nothing regresses.

## Safety workflow

Work happens on the live folder; the frozen backup is the fallback. If an increment can't be made to work, **restore the backup and ship the known-good original** — the tunnel is optional and never blocks a good release.
