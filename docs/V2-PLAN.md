# Noxa v2.0 — one service, both jobs (the plan)

**Goal in one sentence:** the honest in-tunnel score becomes 97 — Noxa's own
900k filter working *inside* the WireGuard tunnel, with the counter and
per-app stats alive, in one VpnService.

**Why:** today the tunnel and the blocker share Android's single VPN slot.
Tunnel mode = AdGuard's resolver blocks (~81%), our filter idle, counter
paused. v2.0 removes the trade-off entirely.

**Pace:** slow and staged, like phases 1–3. Each increment builds, runs on
the guinea-pig phone, and passes the full test matrix (COMMANDS.md) before
the next starts. No increment ships alone; v2.0 ships when the whole chain
is device-proven. `main` carries the work; users stay on 1.x tags until
then. Snapshot before starting: `backups/noxa-known-good-v1.2.x-pre-v2.tgz`.

---

## How it works (the architecture)

Today, tunnel mode: WireGuard's GoBackend owns the VPN interface; all
packets go to the WG engine; DNS goes to AdGuard.

v2.0: **Noxa's own VpnService owns the interface in both modes.** In tunnel
mode it runs the WireGuard engine *userspace* (same wireguard-go the
library uses) but keeps our DNS interception in front:

    app → tun (OURS) → [DNS? → our 900k filter → sinkhole or forward]
                     → [everything else → WireGuard encrypt → provider]

Same filter, same counter, same per-app attribution — the packets just exit
through WireGuard instead of the open network.

## Increments

**2.1 — Study & spike (no app changes).** Read how GoBackend drives
wireguard-go; find the seam where we can own the tun fd and hand packets to
the WG engine ourselves (GoBackend normally owns the fd). Decide: reuse
wireguard-go directly vs. the library's lower-level API. Output: a one-page
decision doc. Risk: none — nothing ships.

**2.2 — Packet plumbing prototype.** A debug-only build where OUR service
owns the tun and passes ALL traffic to the WG engine (no filtering yet).
Success = phone browses normally through the tunnel via our service, IP
hidden. This is the hard one: performance (can't copy packets lazily),
battery, MTU. Device checkpoints: speedtest, 1-hour battery watch.

**2.3 — DNS interception inside the pipe.** Splice the existing filter in
front of the WG engine for DNS packets only (the same DnsPacket +
BloomFilter code, unchanged). Success = in-tunnel ad-block score ≈ blocker
score; counter and per-app stats tick while IP is hidden.

**2.4 — Mode collapse.** The main switch and tunnel toggle stop competing:
one service, tunnel on/off is just a routing choice inside it. The
watchdog guards the ONE service (the v1.2.2 conflict class disappears
entirely). UI: counter alive everywhere; "IP hidden" becomes a state badge.

**2.5 — Endurance.** Multi-day daily-driver test on both phones + TV:
battery (target: same ~2%/7h), reconnects (Wi-Fi↔mobile), reboots, tester
scores in both modes. Only after this: tag v2.0.

## Risks (named up front)

- **Battery** — every packet (not just DNS) now flows through our loop +
  the WG engine. The v0.2 CPU fire taught us what careless loops cost;
  budget real profiling time in 2.2.
- **Complexity of ownership** — GoBackend wants to own the VpnService;
  going lower-level means more of the WG lifecycle (handshakes, roaming,
  keepalive) becomes our responsibility to not break.
- **Time** — weeks, honestly. Filter updates, small fixes, and F-Droid
  duties continue on 1.x in parallel; v2.0 never blocks a 1.x release.

## Not in v2.0

Tor-mode integration stays as-is (Orbot handoff). QR-code config import
stays a separate small feature (needs camera permission — own release).
iOS/desktop stay out of scope forever (see README).
