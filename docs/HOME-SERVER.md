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
- The key travels in a header (`X-Home-Key`). Keep the server on your home
  network only; there is no reason to open it to the internet.
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

**Never in a report:** the sites visited or the domains blocked (Noxa doesn't
keep them — the live feed is memory only), allowed lookups, the list of
installed apps, or anything about who uses the device.

## A 30-line server (Python 3, nothing to install)

Save as `home-server.py` on any computer or Raspberry Pi on your Wi-Fi, run
`python3 home-server.py`, and type that computer's address and the key into
each device's Noxa. It keeps one file per device and prints a line per report.

```python
import json, os, time
from http.server import BaseHTTPRequestHandler, HTTPServer

KEY = "change-me-to-a-long-key"          # the same key goes into every device's Noxa
FOLDER = "noxa-reports"
os.makedirs(FOLDER, exist_ok=True)

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/api/home/report" or self.headers.get("X-Home-Key") != KEY:
            self.send_response(401); self.end_headers(); return
        body = self.rfile.read(min(int(self.headers.get("Content-Length", "0")), 8192))
        try:
            r = json.loads(body)
            name = str(r["device"]["name"])[:40]
            with open(os.path.join(FOLDER, r["device"]["id"] + ".json"), "w") as f:
                json.dump(r, f, indent=1)
            print(time.strftime("%H:%M"), name, "protection", "ON" if r["protection"]["on"] else "OFF",
                  "blocked today", r["blocked_today"], "since last", r["since_last"]["total"],
                  "serious", r["since_last"]["categories"].get("Dangerous site", 0) +
                             r["since_last"]["categories"].get("Stalkerware", 0))
        except Exception as e:
            self.send_response(400); self.end_headers(); return
        self.send_response(200); self.send_header("Content-Type", "application/json")
        self.end_headers(); self.wfile.write(b'{"ok": true}')

HTTPServer(("0.0.0.0", 8790), Handler).serve_forever()
```

A device that stops reporting for a day is the signal worth acting on: Noxa
was switched off, uninstalled, or the device is away. Any home dashboard
(Home Assistant, a script, a spreadsheet) can read the JSON files.

## Not a server, not telemetry

Noxa still runs no servers of its own and still sends nothing to the
internet. This is you, talking to you. People who don't switch it on are
exactly where they were before.
