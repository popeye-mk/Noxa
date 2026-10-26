# Report to my home server (v1.14) — the format, and a tiny server anyone can run

Noxa runs on each device by itself. A family with several phones, tablets and
a TV has no single place that shows whether every device is still protected,
and whether any app on them reached a scam or spyware server. This feature
gives you that place — **in your own house**, on a computer you run.

It is **off by default**. Switch it on under *Per-app details → Settings &
tools → More settings… → Report to my home server*, type the address of your
server on your own Wi-Fi and the key it gave you, and Noxa sends a short
report once an hour.

## The rules Noxa keeps

- Only to a **private address** you typed (192.168.x.x, 10.x.x.x,
  172.16–31.x.x). Never a name — Noxa never looks one up for this — and
  never anything on the internet.
- Only on **Wi-Fi or Ethernet**. Never on mobile data, never while the
  private tunnel is on.
- Once an hour; after a failure not again within 10 minutes. At most ~4 KB.
- After joining, the key never travels again (since 1.14.6): every request is **signed** with it,
  and Noxa takes rules, the home list and backups only from answers the server
  signed with the same key — see *Signed requests and answers* below. Keep the
  server on your home network only; there is no reason to open it to the internet.
- Plain HTTP on the home network (like the Anora app and most home
  dashboards). Noxa's own HTTP calls to the internet stay HTTPS-only.

## What a report holds — and what it never holds

```json
{
  "format": "noxa-home-report",
  "version": 1,
  "sent_at": 1800000000,
  "device": {"id": "random-uuid-made-once", "name": "Kids tablet",
             "model": "Lenovo TB-X606F", "android": "11", "noxa": "1.14"},
  "protection": {"on": true, "always_on": false,
                 "blocklist_built": "2026-10-12 04:41:09", "stops_today": 0},
  "blocked_today": 123,
  "blocked_days": [10, 20, 30, 40, 50, 60, 123],
  "filters": {"family": true, "safe_search": true, "parent_lock": true,
              "managed": ["family_filter", "safe_search"]},
  "since_last": {
    "total": 53,
    "categories": {"Advertising": 42, "Analytics": 9, "Dangerous site": 2},
    "top_companies": [["Google", 30], ["Meta", 12], ["TikTok", 9]]
  },
  "apps_30d": {
    "dangerous_site": [["Chrome", 2, true]],
    "stalkerware": []
  }
}
```

- `since_last` is what was blocked since the previous report that got
  through (per category, and the companies behind it). Categories:
  Advertising, Analytics, Tracking, Marketing, Dangerous site, Stalkerware,
  Blocked by you, Router attack shield, DNS-over-HTTPS, Other.
- `apps_30d` names the apps that reached scam/malware or spyware servers in
  the last 30 days: `[name, count, is_browser]`. A browser (`true`) usually
  means a page someone visited, not spyware on the device.
- `blocked_days` is oldest → newest, today last.
- `filters` (v1.14.4): whether the family filter, safe search and the parent lock are on, and which of
  them the home server sets (`managed`) — so a home screen can show a child's device running without them.

**Never in a report:** the sites visited or the domains blocked (Noxa doesn't
keep them — the live feed is memory only), allowed lookups, the list of
installed apps, or anything about who uses the device.

## Finding the server and joining — no typing (optional for a server)

Typing a key on a TV remote is no fun, so Noxa can find the server and ask for its own key:

1. Noxa sends one UDP broadcast on the Wi-Fi to port **8791**: the text `NOXA-HOME?`.
2. A server answers (unicast, to the sender) with `NOXA-HOME 192.168.1.20:8790` — its own address.
   Noxa only accepts an answer from a private address. Since 1.14.6 it then shows the address and asks
   the user to check that it is their home server's before joining (Join / Cancel): anything on the
   Wi-Fi can answer the broadcast, and the first answer wins.
3. Noxa posts to `/api/home/join`:
   `{"format": "noxa-home-join", "device": {"id": "...", "name": "TV", "model": "..."}}` (unsigned: no key yet).
4. While the server's **door is open** (the parent opened it in the server's app, say for 10 minutes) it
   answers `{"ok": true, "key": "home-...", "message": "..."}` — a key for *that device*, shown once.
   Noxa saves the address and the key and switches the report on. When the door is closed the server answers
   403 with a plain message, and nothing happens.

A server that doesn't support this simply doesn't answer the broadcast; the user types the address and the
shared key instead. Per-device keys are better anyway: forgetting a device kills only its key.

## Rules from the server (optional)

A server may answer a report with rules for that device, and Noxa applies them (shown on the device as
"set by your home server") — since 1.14.6 only when the answer is signed (next section):

```json
{"ok": true,
 "rules": {"blocked_sites": ["tiktok.com"], "family_filter": true, "safe_search": null,
           "bedtime": {"on": true, "start": "21:00", "end": "07:00", "all": false, "apps": ["com.zhiliaoapp.musically"]}},
 "home_list_version": "3f1a…"}
```

- `blocked_sites` are blocked on the device even if its Allowed sites list has them (a child can't allow
  them back). `null` for a flag means "keep the device's own setting".
- `home_list_version`: when it changes, Noxa posts to `/api/home/list` (signed, `{"device": {"id": ...}}`)
  and expects `{"ok": true, "version": "...", "domains": ["..."]}` — the server's own block list (up to
  50,000 domains), kept on the device and blocked like the sites above.
- A report may carry `settings_backup`: the device's own Noxa settings backup (the same JSON as the app's
  Back up feature, ≤ 16 KB), sent when it changed. A server may keep it and answer `/api/home/backups`
  (`{"ok": true, "backups": [{"id", "name", "when"}]}`) and `/api/home/backup` (`{"id": ...}` →
  `{"ok": true, "settings": "<json>"}`), so a new phone can restore its settings from the home server.

## Signed requests and answers (`noxa-home-v2`, Noxa 1.14.6)

Before 1.14.6 the key went in a plain header over plain HTTP, and Noxa applied whatever rules came back.
Anyone else on the Wi-Fi who got in the middle could copy the key, or answer in the server's place —
"family filter off". Now the key travels only once, in the join answer while the parent's door is open, and both sides sign with it from then on. Keys used before 1.14.6 went over the Wi-Fi in the clear: once every device runs 1.14.6, let each device join again so it gets a fresh key.

**The signing key** `K` = SHA-256 of the key as typed (UTF-8), 32 bytes. A server can keep only that hash.

**Every keyed request** (`/api/home/report`, `/api/home/list`, `/api/home/backups`, `/api/home/backup`)
carries three headers and no `X-Home-Key`:

```
X-Home-Device: <the device id, lower case — the same as device.id in the body>
X-Home-Time:   <unix seconds>
X-Home-Sig:    hex HMAC-SHA256(K, "noxa-home-v2\n" + path + "\n" + device id + "\n" + time + "\n" + hex SHA-256(body))
```

`body` is the exact bytes sent. The join (`/api/home/join`) stays unsigned: there is no key yet.

A server accepts the request when the time is within 15 minutes of its own clock, the signature matches
(compare in constant time, e.g. `hmac.compare_digest`), it hasn't accepted the same signature before (keep
the last 30 minutes), and `device.id` in the body, if there, equals `X-Home-Device`.

**Every answer** to an accepted request — 200, but also 400, 404, 429 and so on — carries:

```
X-Home-Reply-Sig: hex HMAC-SHA256(K, "noxa-home-v2-reply\n" + the request's X-Home-Sig + "\n" + status + "\n" + hex SHA-256(answer body))
```

A refused request (401) carries no signature. Noxa checks it over the exact answer bytes and the status:

- A report answered 200 **with** a good signature: Noxa applies the rules in it, as before.
- A report answered 200 **without** one: the report counts as sent, but the rules in the answer are
  ignored and the ones already on the device stay. The device says so. (A simple receiver that only
  stores reports keeps working.)
- `/api/home/list`, `/api/home/backups` and `/api/home/backup` without a good signature: nothing is used.

**No way back to the old style.** A server may still take an unsigned request with `X-Home-Key` from a device
that has never signed (older Noxa versions), but once a device has sent one signed request it should refuse
that device's unsigned ones (401) — otherwise someone in the middle could simply strip the signature.
Forgetting a device on the server clears this too.

To check your own code, these must come out exactly:

```
key        home-TESTKEY-not-real-123
K          3c9b7b762080cf6382dd004b3ea95c867d4f445dda274e4334b68b6b788e4b93
device     aaaaaaaa-0000-0000-0000-000000000001   time 1793000000   path /api/home/report
body       {"format":"noxa-home-report","device":{"id":"aaaaaaaa-0000-0000-0000-000000000001"}}
X-Home-Sig ff6009f5b6e11d004acf33b4fc34e6d8acc55cea6cbf9e537a09225011d3e89e
answer     200  {"ok": true, "rules": {}, "home_list_version": "abc"}
X-Home-Reply-Sig  bde50926c85ec00760e2c2b6927328acecb10d9c6951920b81613c145cbae3c4
```

## A Linux PC in the same picture

Noxa runs on Android only, but a home server wants the whole house. `pc/noxa-pc.sh` makes a Linux PC
report in the same format (device `noxa` = `pc-1.0`, no blocks) plus a `pc` block: `os`, `updates_pending`,
`security_pending`, `reboot_required`, `firewall`, `auto_updates`, `quad9`, `uptime_days`. One run with sudo —
`sudo bash noxa-pc.sh setup --name "Office PC"` — finds the server on the Wi-Fi and joins while its door is open
(or takes `--address` and `--key`), sets Quad9 with DNS over TLS, switches ufw and unattended-upgrades on, and
installs an hourly systemd timer. `noxa-pc.sh remove` takes the report away; the hardening stays. Its reports are
signed the same way (it applies no rules, so it doesn't check the answer's signature).

## A 30-line server (Python 3, nothing to install)

Save as `home-server.py` on any computer or Raspberry Pi on your Wi-Fi, run
`python3 home-server.py`, and type that computer's address and the key into
each device's Noxa. It keeps one file per device and prints a line per report.
It checks each report's signature, refuses a copied one, and signs its answer.

```python
import hashlib, hmac, json, os, time
from http.server import BaseHTTPRequestHandler, HTTPServer

KEY = "change-me-to-a-long-key"            # the same key goes into every device's Noxa
K = hashlib.sha256(KEY.encode()).digest()  # Noxa signs with this; the key itself never travels
FOLDER, seen = "noxa-reports", {}          # seen: signatures already used, so a copied report is refused
os.makedirs(FOLDER, exist_ok=True)
sign = lambda *parts: hmac.new(K, "\n".join(parts).encode(), hashlib.sha256).hexdigest()

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(min(int(self.headers.get("Content-Length", "0")), 32768))
        dev, t, sig = (self.headers.get(h, "") for h in ("X-Home-Device", "X-Home-Time", "X-Home-Sig"))
        good = sign("noxa-home-v2", self.path, dev.lower(), t, hashlib.sha256(body).hexdigest())
        plain = (dev + t).isascii() and t.isdigit() and dev.replace("-", "").isalnum()
        if (self.path != "/api/home/report" or not plain or abs(time.time() - int(t)) > 900
                or sig in seen or not hmac.compare_digest(good.encode(), sig.encode())):
            self.send_response(401); self.end_headers(); return
        for s in [s for s, at in seen.items() if time.time() - at > 1800]: del seen[s]
        seen[sig] = time.time()
        try:
            r = json.loads(body); c = r["since_last"]["categories"]
            with open(os.path.join(FOLDER, dev.lower() + ".json"), "w") as f:
                json.dump(r, f, indent=1)
            print(time.strftime("%H:%M"), str(r["device"]["name"])[:40], "protection", "ON" if r["protection"]["on"] else "OFF",
                  "blocked today", r["blocked_today"], "since last", r["since_last"]["total"],
                  "serious", c.get("Dangerous site", 0) + c.get("Stalkerware", 0))
        except Exception:
            return self.answer(400, b'{"ok": false}', sig)
        self.answer(200, b'{"ok": true}', sig)

    def answer(self, status, out, sig):    # signed too, so Noxa knows the answer is yours
        self.send_response(status); self.send_header("Content-Type", "application/json")
        self.send_header("X-Home-Reply-Sig", sign("noxa-home-v2-reply", sig, str(status), hashlib.sha256(out).hexdigest()))
        self.end_headers(); self.wfile.write(out)

HTTPServer(("0.0.0.0", 8790), Handler).serve_forever()
```

### Home Assistant

The example server above keeps one JSON file per device. Home Assistant reads such a file with a
`command_line` sensor (replace the path and the device id):

```yaml
command_line:
  - sensor:
      name: "Noxa Kids tablet"
      command: "cat /config/noxa-reports/<device-id>.json"
      value_template: "{{ value_json.blocked_today }}"
      json_attributes:
        - protection
        - since_last
        - apps_30d
      scan_interval: 600
```

`protection.on`, `since_last.categories` and `apps_30d` then show up as attributes, ready for a card or an
automation ("notify me when a device reaches a spyware server"). Any home server that speaks HTTP can do the same
with a REST sensor pointed at its own API.

A device that stops reporting for a day is the signal worth acting on: Noxa
was switched off, uninstalled, or the device is away. Any home dashboard
(Home Assistant, a script, a spreadsheet) can read the JSON files.

## Not a server, not telemetry

Noxa still runs no servers of its own and still sends nothing to the
internet. This is you, talking to you. People who don't switch it on are
exactly where they were before.
