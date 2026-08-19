# Guardian app — how to build it (plain language)

This folder is a real Android app project (Phase 1). Here's what's in it and how to turn it into an installable app. You don't need to understand the code to follow this.

## What's here

```
app/
├── HOW-TO-BUILD.md                 ← this file
├── build.gradle.kts                ← app build settings
└── src/main/
    ├── AndroidManifest.xml         ← app permissions + components
    ├── assets/
    │   ├── guardian-default.gbf    ← the compiled blocklist (0.92 MB)
    │   └── blocklist-manifest.json ← what's inside it
    ├── java/com/guardian/app/
    │   ├── MainActivity.kt         ← the single on/off switch screen
    │   ├── GuardianVpnService.kt   ← the local filter (checks each lookup)
    │   ├── DnsPacket.kt            ← reads the domain out of each request
    │   └── BloomFilter.kt          ← instant "is this blocked?" check
    └── res/                        ← the screen layout + text
```

## What each part does, in one line

- **The switch** (`MainActivity`): the whole interface — tap on, you're protected.
- **The filter** (`GuardianVpnService`): watches which servers your apps try to reach and blocks the known trackers/ads. Nothing leaves your phone — it's a *local* filter, not a real VPN.
- **The blocklist** (`guardian-default.gbf`): 269,705 known bad domains, compiled so lookups are instant and it only takes ~1 MB.

## To build it into an app (needs a computer, one-time setup)

1. Install **Android Studio** (free, from Google). This is the standard tool that turns the project into an installable app.
2. Open Android Studio → **Open** → pick this `privacy` folder.
3. Let it finish loading (it downloads the Android build tools the first time — takes a few minutes).
4. Plug in an Android phone with **USB debugging** on, or start the built-in emulator.
5. Press the green **Run ▶** button. Guardian installs and opens.
6. Tap the switch. Android asks "allow Guardian to set up a VPN connection?" — say yes (this is the permission that lets it filter locally). The counter starts climbing as trackers get blocked.

## What still needs doing (honest status)

- The **blocklist and the instant-lookup logic are done and tested** (verified: catches all known-bad domains, zero false blocks on normal sites).
- The **traffic-filtering plumbing is written but not yet tested on a real phone** — that's the next step, and it's exactly what "Step 8: test against real usage" in the Phase 1 plan covers.
- Once it runs on a phone, we compare its block count against your DuckDuckGo baseline (22,616 in 5 days) to prove it works before calling Phase 1 done.

You don't have to do the build yourself — when you're ready, I can walk you through it one step at a time, or set it up so a developer can take it straight from here.
