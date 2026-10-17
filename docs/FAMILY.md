# Noxa for a family (v1.14) — the optional parent features

All of these are **off by default** and live under *Per-app details → Settings
& tools → More settings…*. Noxa stays a one-switch blocker for everyone else.

| Feature | What it does | Where |
|---|---|---|
| **Parent lock** | A PIN before protection can be switched off, paused, or weakened (an app bypassing Noxa, allowing a blocked site, taking a site off the block list, changing the home-server settings). The Quick Settings tile, the widget and the notification can no longer switch protection off — they open the app, which asks. Blocking *more* never needs the PIN. | More settings → Parent lock |
| **Bedtime** | Quiet hours per device ("21:00 to 07:00", may cross midnight). Every app, or the ones you choose, get no internet in that window. Calls and texts don't use DNS and keep working. The live feed shows "Bedtime (set by a parent)". | More settings → Bedtime |
| **Safe search** | Google, YouTube, Bing, DuckDuckGo and Yandex are forced into their safe / restricted mode: a lookup of the search site is answered with a CNAME to the site's own safe-mode address (forcesafesearch.google.com, restrict.youtube.com, strict.bing.com, safe.duckduckgo.com, familysearch.yandex.ru) plus its real addresses — the technique family DNS services use, done on the device. Nothing is blocked outright. | More settings → Safe search |
| **Family filter** | Adult, gambling and dating sites blocked (UT1's adult, porn, mixed_adult, lingerie, gambling, arjel and dating categories, minus Noxa's never-block list). Several million domains, rebuilt weekly and published as a rolling GitHub release asset; downloaded only when you switch it on (about 10 MB, Wi-Fi), verified by SHA-256. Education sites (UT1's sexual_education) are deliberately left alone. Your own Allowed sites win. | More settings → Family filter |
| **Report to my home server** | One screen in your own house for every device: see [HOME-SERVER.md](HOME-SERVER.md). | More settings → Report to my home server |

## Honest limits

- **Uninstalling.** Android lets anyone uninstall an app. A parent lock can't
  stop that; a home server (see HOME-SERVER.md) notices when a device goes
  silent and says so.
- **DNS only.** Everything here works for lookups that pass through Noxa. An
  app that brings its own encrypted DNS slips past, as with every DNS-level
  filter (Noxa blocks the common DoH endpoints and the browsers' DoH canary,
  which closes the usual gaps).
- **Lists are lists.** The family filter misses some sites and may be wrong
  about others. Allowed sites fix a wrong block; "My blocked sites" fixes a
  miss.
- **Safe search** covers the five search sites above. A search site not on
  the list isn't covered.
- **Bedtime** cuts internet, not the device: offline games still run.

## Setting up a child's device (5 minutes)

1. Install Noxa, switch protection on, allow the battery exemption, set
   Always-on VPN with "block connections without VPN" (Protection check-up
   shows the way).
2. More settings → **Parent lock** → set a PIN.
3. More settings → **Safe search** → on. **Family filter** → on (downloads once).
4. More settings → **Bedtime** → on, pick the hours and the apps.
5. Optional: **Report to my home server** → address, key, the device's name.
