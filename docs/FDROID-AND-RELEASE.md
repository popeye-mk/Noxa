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

## Every release — from GitHub, no PC needed (v1.8+)

GitHub builds and signs the APK for you (`.github/workflows/release.yml`).

**One-time setup** (needs the keystore from the section above, on a PC once):

1. Make a one-line copy of the keystore: `base64 -w0 noxa-release.jks`
   (macOS: `base64 -i noxa-release.jks | tr -d '\n'`). Copy the output.
2. On GitHub: repo → **Settings → Secrets and variables → Actions →
   New repository secret**, four times:

   | Name | Value |
   |------|-------|
   | `NOXA_KEYSTORE_BASE64` | the long line from step 1 |
   | `NOXA_KEYSTORE_PASSWORD` | `storePassword` from keystore.properties |
   | `NOXA_KEY_ALIAS` | `keyAlias` (e.g. `noxa`) |
   | `NOXA_KEY_PASSWORD` | `keyPassword` |

   Secrets are write-only: GitHub never shows them again, workflow logs
   mask them, and the job deletes its copy when done.

**Each release** (phone or PC):

1. Make sure `versionCode`/`versionName` in `app/build.gradle.kts` and the
   `fastlane/.../changelogs/<versionCode>.txt` file are on `main`.
2. GitHub → **Releases → Draft a new release** → "Choose a tag": type
   `v1.8`, pick *Create new tag on publish* → target `main` → title
   `Noxa v1.8` → paste the changelog → **Publish release**.
3. About 3 minutes later `noxa-v1.8.apk` (signed) and its `.sha256` appear
   on the release. Done — the README's "latest release" link now serves it,
   and phones on v1.8+ get a "new version" notification within a day.

Tick "Set as a pre-release" for a test release: the in-app update notice
ignores pre-releases, so only you see it.

If the job fails with "Signing secrets missing", do the one-time setup.

**Rules learned from the first signed release (v1.8, 2026-10-01):**

1. A new or changed workflow is not trusted until it has run end-to-end
   once. Test it with a pre-release (tick "Set as a pre-release") before
   relying on it for a real release.
2. The unit-test step in `release.yml` stays identical to `build.yml`
   (`testDebugUnitTest`). If one changes, change both.
3. If a release run fails: fix the workflow on `main`, then **Actions →
   "Publish signed release" → Run workflow → enter the tag**. That rebuilds
   the same tag with the fixed workflow and replaces the attached APK.
   Plain "Re-run jobs" would use the OLD workflow file from the tag's
   commit, and deleting/recreating the tag is no longer needed.
4. Never attach the CI "Build APK" artifact to a release: it is
   **Noxa TEST** (`.test` package, test key) and won't update the real app.
5. `versionCode` must go up by one for every release, and the matching
   `fastlane/.../changelogs/<versionCode>.txt` must exist on `main`
   before the release is created.

## Every release — manually on a PC (the old way, still works)

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
- No telemetry, no analytics SDKs, no network calls except: the daily
  blocklist update and the daily "new version?" check, both anonymous GETs
  to this repo on GitHub (raw file / Releases API), DNS/DoH resolution
  itself, and the user's own WireGuard tunnel. Noxa never downloads or
  installs code by itself — the update notice only opens the release page.
- The "new version?" check only runs in copies signed with our GitHub release
  key (`AppUpdater.RELEASE_CERT_SHA256`, v1.9.1+). F-Droid builds (signed with
  F-Droid's key) and Noxa TEST builds never run it, so they're never pointed
  at a GitHub APK that couldn't install over them. If the release key is ever
  rotated, update that constant.
- `app/debug.keystore` is a PUBLIC, test-only key (password "android") for the
  `.test` debug variant, so CI test builds install over each other. Release
  builds never use it.
- The compiled blocklist (`guardian-default.gbf`) is a build artifact of
  `build-tools/build_blocklist.py` over public lists; committed so the app
  builds offline.
