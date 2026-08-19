# Noxa — Release & F-Droid guide

## One-time: create your signing key (on your PC, keep it forever)

```
cd ~/Desktop/privacy
keytool -genkeypair -v -keystore noxa-release.jks -alias noxa \
  -keyalg RSA -keysize 4096 -validity 10000
```

Pick a strong password and WRITE IT DOWN — losing this key means you can
never update the app for existing users.

Then create `keystore.properties` in the repo root (it is .gitignored —
NEVER commit it):

```
storeFile=noxa-release.jks
storePassword=YOUR_PASSWORD
keyAlias=noxa
keyPassword=YOUR_PASSWORD
```

Back up BOTH files (`noxa-release.jks` + `keystore.properties`) somewhere
safe outside this folder (USB stick).

## Every release

1. Bump `versionCode` (+1, always) and `versionName` in
   `app/build.gradle.kts`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
3. Build signed: `bash build-on-linux.sh release`
   → APK at `app/build/outputs/apk/release/app-release.apk`
4. Rename it: `cp app/build/outputs/apk/release/app-release.apk noxa-v1.0.apk`
5. Commit + push, then tag:
   ```
   git tag v1.0
   git push origin v1.0
   ```
6. On GitHub: Releases → "Draft a new release" → choose tag `v1.0` →
   attach `noxa-v1.0.apk` → publish.

## F-Droid submission (once, after the first GitHub release exists)

1. Screenshots into `fastlane/metadata/android/en-US/images/phoneScreenshots/`
   (see README.txt there), commit + push.
2. Create an account on https://gitlab.com (F-Droid lives on GitLab).
3. Open a "Request for Packaging" issue:
   https://gitlab.com/fdroid/rfp/-/issues/new
   — give the repo URL, the license (AGPL-3.0), and note that it builds
   with plain gradle (`assembleRelease`), no proprietary dependencies.
4. Wait — review typically takes a few weeks. They build from your git tag
   themselves and sign with their own key (that's normal).

Notes for the F-Droid reviewers (also useful in the RFP text):
- No proprietary dependencies: only `com.wireguard.android:tunnel` (Apache-2.0)
  and `desugar_jdk_libs`.
- No telemetry, no analytics SDKs, no network calls except: user-initiated
  blocklist update from this repo's raw GitHub URL, DNS/DoH resolution
  itself, and the user's own WireGuard tunnel.
- The compiled blocklist (`guardian-default.gbf`) is a build artifact of
  `build-tools/build_blocklist.py` over public lists; committed so the app
  builds offline.
