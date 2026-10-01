# Noxa — on-device test plan

Run this on a real phone before every public release. Automated checks
(CI unit tests, packet checks, the filter gate) cover the DNS engine; this
covers what only a human can see. Tick each line, note the phone model and
Android version, and file anything that fails as an issue.

A test build ("Noxa TEST", debug APK) installs next to the real app. Only one
VPN can run at a time: turn the other one OFF first.

## 1. Core protection (5 min)

| # | Do | Pass when |
|---|----|-----------|
| 1.1 | Turn the switch ON, accept the VPN prompt | Status turns green; notification "Noxa is protecting you" appears |
| 1.2 | Open a browser, load `adblock.turtlecute.org`, run the test | Score ≥ 97% (same-site/cosmetic ads are not DNS-blockable) |
| 1.3 | Load 3 everyday sites (news, shopping, video) | All load normally, images/videos play |
| 1.4 | Open 3 everyday apps (messaging, maps, a game) | All work normally |
| 1.5 | Watch the notification for a minute of use | Text changes to "N tracking attempts blocked today" and N grows |
| 1.6 | Turn the switch OFF, then ON again | Both take effect within ~2 s; no crash |

## 2. Live feed (3 min)

| # | Do | Pass when |
|---|----|-----------|
| 2.1 | Main screen → "Watch it live", use an app | Lines appear within seconds, newest on top, red = blocked, green = allowed |
| 2.2 | Tap "Showing: everything" | Switches to blocked-only and back |
| 2.3 | Tap a **red** line → "Allow …" | Toast confirms; the site appears in Settings & tools → Allowed sites |
| 2.4 | Tap a **green** line → "Block …" | Toast confirms; the site appears in "My blocked sites"; new lookups of it show as ⛔ "On your block list" |
| 2.5 | Remove both entries again from their lists | Lists empty; lookups behave as before |

## 3. Pause, tile, widget (5 min)

| # | Do | Pass when |
|---|----|-----------|
| 3.1 | Notification → "Pause 5 min" | Protection stops; "Noxa is paused … back on at HH:MM" notification; main screen says Paused |
| 3.2 | Wait 5 min (or tap "Resume now") | Protection returns by itself; paused notification disappears |
| 3.3 | Pull down the shade → edit tiles → add "Noxa" | Tile shows Protected/Off matching the real state |
| 3.4 | Tap the tile OFF, then ON | State changes within ~2 s; subtitle updates |
| 3.5 | Long-press home screen → Widgets → add Noxa | Widget shows status + "N blocked today" |
| 3.6 | Tap the widget button | Turns protection on/off; text updates within 30 s |

## 4. Lists, updates, backup (5 min)

| # | Do | Pass when |
|---|----|-----------|
| 4.1 | Settings & tools → "Update protection now" | "Updated to <date> — already active" the first time, "Already up to date" after |
| 4.2 | Right after 4.1, load a site | Still works — no restart needed |
| 4.3 | "Back up my settings" → save the file | A `noxa-settings.json` file is created |
| 4.4 | Add a site to Allowed sites, then "Restore settings from a backup" | Toast "Restored: …"; the earlier lists are back (merge, nothing deleted) |
| 4.5 | "Fix an app that won't work" → exclude an app → OFF/ON | That app bypasses Noxa (its lookups no longer appear in Live) |

## 4b. v1.8 additions (5 min)

| # | Do | Pass when |
|---|----|-----------|
| 4b.1 | Settings & tools → "DNS provider" → pick Quad9 | Toast "Using Quad9"; sites keep loading; Live feed keeps flowing (no off/on needed) |
| 4b.2 | Pick "Your own server…" → enter `9.9.9.9`, leave URL empty → Use it | Toast "Using 9.9.9.9"; sites load |
| 4b.3 | Enter an invalid IP (e.g. `999.1.1.1`) | Toast says nothing changed |
| 4b.4 | Back to Cloudflare | Toast confirms |
| 4b.5 | In Chrome open `https://flexispy.com` | Red "⚠ Stalkerware · Spyware" line in Live; once per day a "Spyware site reached (blocked)" notification naming the browser |
| 4b.6 | Settings & tools → "Check for a new Noxa version" | "You have the latest version (…)" |
| 4b.7 | Per-app details | Apps named properly; little or nothing under "system / unknown" |

## 5. Survival (overnight)

| # | Do | Pass when |
|---|----|-----------|
| 5.1 | Leave protection on overnight, phone locked | Next morning: still protected, counter kept growing, no "stopped" |
| 5.2 | Reboot the phone | Protection comes back on by itself within ~15 min (or at once with Always-on VPN) |
| 5.3 | Switch Wi-Fi ↔ mobile data | Sites keep loading; Live feed keeps flowing |
| 5.4 | Battery: Settings → Battery → app usage after a day | Noxa well under the browser/social apps |

## 6. Tunnel mode (only if you use it)

| # | Do | Pass when |
|---|----|-----------|
| 6.1 | Hide my IP → paste config → on | Switch stays ON; status says tunnel mode; an IP-check site shows the provider's IP |
| 6.2 | Turn the main switch OFF | Tunnel goes down; real IP is back |

## Reporting

Phone: ______  Android: ______  Build: ______  Date: ______

Failures (step number + what happened + screenshot if possible):
