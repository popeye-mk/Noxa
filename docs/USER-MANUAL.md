# Noxa — User Manual

*Ad & tracker blocker for Android. Also available as [PDF](Noxa-User-Manual.pdf).*

## 1. What is Noxa

Noxa protects your phone by blocking ads, trackers, and tracking attempts,
entirely on-device, without sending your data to external servers. It works
in the background through a local VPN service.

## 2. Installing

1. On your phone, open **github.com/popeye-mk/Noxa/releases/latest**
2. Download the **noxa-vX.Y.Z.apk** file
3. Tap the downloaded file (notification, or Files → Downloads)
4. Android asks to allow installing from this source — allow it once
5. Tap **Install**

## 3. Enabling protection

1. Open the **Noxa** app
2. Tap the main switch to turn it on
3. On first launch, Android asks permission to set up a VPN connection —
   tap **OK / Allow** (see FAQ: nothing leaves your phone)
4. When the switch turns violet and you see **"Protected — trackers and ads
   are being blocked"**, protection is active

## 4. Important: turn off battery restrictions

For Noxa to keep working at all times (screen off, phone idle), the battery
management system must not be allowed to kill it in the background. Without
this step, protection can silently stop after a while. Steps differ by
manufacturer — find your device below.

### Stock Android / Pixel / Nokia / Motorola

1. **Settings → Apps → Noxa**
2. **Battery** (or Battery usage details)
3. Select **Unrestricted**

> If the option is greyed out, leave Noxa running for a few hours — Android
> unlocks this menu once it has recorded some battery usage for the app.

### Samsung

1. **Settings → Apps → Noxa → Battery**
2. Select **Unrestricted** (not "Optimized" or "Sleeping")
3. Recommended: **Settings → Device care → Battery → Background usage
   limits** → add Noxa to **"Never sleeping apps"**

### Xiaomi / Redmi / POCO (MIUI / HyperOS)

1. **Settings → Apps → Manage apps → Noxa**
2. **Battery saver → No restrictions**
3. **Autostart** → enable for Noxa

> ⚠ On MIUI/HyperOS, VPN apps are sometimes blocked from Autostart by the
> system itself — a platform limitation, not a Noxa bug. If Autostart won't
> stay on: try disabling **MIUI Optimization** (Settings → Additional
> settings → Developer options), and lock Noxa in the Recents screen
> (long-press its card → lock icon). All three settings matter — one alone
> is usually not enough on this brand.

### Oppo / Realme / OnePlus (ColorOS)

1. **Settings → Battery → App battery usage → Noxa → Don't optimize**
2. **Settings → App management → Auto-launch** → enable Noxa
3. Recents screen → long-press the Noxa card → **lock** icon

### Huawei / Honor (EMUI / MagicOS)

1. **Settings → Battery → App launch**
2. Find Noxa, turn **off** automatic management
3. Manually enable all three: **Auto-launch**, **Secondary launch**,
   **Run in background**

### Vivo (Funtouch OS / OriginOS)

1. **Settings → Battery → High background power consumption → Noxa** → enable
2. **Settings → Apps & permissions → Autostart** → enable Noxa

## 5. How to check protection is working

- The VPN icon (key/shield) stays visible in the notification bar while
  Noxa is active
- Open Noxa: the **"tracking attempts blocked"** counter keeps increasing
  as you use the phone
- If the VPN icon disappears without you turning Noxa off, revisit the
  battery steps above for your phone model

## 6. If an app misbehaves with Noxa on

- A **site** won't load? Add its address in **Per-app details → Allowed
  sites** — it will never be blocked again.
- An **app** shows "no internet" or refuses to start (some streaming and
  banking apps detect VPNs)? **Per-app details → "App won't work with Noxa
  on? Exclude it"** → pick the app → toggle protection off and on. The app
  then bypasses Noxa entirely (it works, but isn't protected).

## 7. FAQ

**Why does Noxa need a VPN connection?**
It's the standard way an Android app can filter other apps' traffic without
root access. Noxa doesn't send your traffic to any server — filtering
happens locally, on the phone. The code is open; you can verify.

**Does Noxa slow down my internet?**
It shouldn't — local filtering adds a negligible delay.

**Can I turn protection off temporarily?**
Yes — same switch on the main screen.

**Does the app use a lot of battery?**
No. Measured on real devices: about 2% over 7 hours. Comparable to a
messaging app idling in the background.

---

*Manual version 1.1 — for Noxa v1.1.1. Battery/autostart steps checked
against current (2026) MIUI/HyperOS, ColorOS, EMUI and OneUI documentation.*
