# Noxa — command cheat sheet

Everything runs from the project folder:

    cd ~/Desktop/privacy

---

## Build the app

    bash build-on-linux.sh              # debug build (testing)
    bash build-on-linux.sh release      # signed release build (for users)

APKs land in:
- debug:   `app/build/outputs/apk/debug/app-debug.apk`
- release: `app/build/outputs/apk/release/app-release.apk`

The APK **is** the installable app — copy it anywhere, tap it on any Android
device to install. A path alone is not a command: to grab a fresh copy with a
proper name into this folder, run

    cp app/build/outputs/apk/release/app-release.apk noxa-vX.Y.Z.apk

(replace X.Y.Z with the current version; delete the old noxa-v*.apk copy —
old versions live forever on the GitHub Releases page, no need to keep them
locally). Copy that file to the USB stick for the TV:

    cp noxa-vX.Y.Z.apk /media/stojan/Ventoy/

Release signing needs `keystore.properties` + `noxa-release.jks` in this
folder (backed up on the USB stick — NEVER commit them, .gitignore blocks it).

## Install on the phone (USB debugging on)

    ~/.guardian-build/android-sdk/platform-tools/adb install -r app/build/outputs/apk/release/app-release.apk

`-r` = update in place, keeps stats. Release-over-release works; switching
debug↔release needs an uninstall first (different signatures) — the error
looks like `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`:

    ~/.guardian-build/android-sdk/platform-tools/adb uninstall com.guardian.app
    ~/.guardian-build/android-sdk/platform-tools/adb install app/build/outputs/apk/release/app-release.apk

No adb? Copy the APK to the phone and tap it.

## Install on the Android TV

Copy the APK to a USB stick → plug into TV → open it with a file manager
(File Commander / FX) → allow "install unknown apps" → install.
Noxa doesn't show in the TV launcher: open via Settings → Apps → Noxa.

---

## Update the blocklist filter

    # (optional) refresh the raw source lists first:
    curl -fL -o blocklists/hagezi/pro.txt      "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/pro.txt"
    curl -fL -o blocklists/hagezi/doh.txt      "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/doh.txt"
    curl -fL -o blocklists/adguard/dns.txt     "https://raw.githubusercontent.com/AdguardTeam/AdGuardSDNSFilter/master/Filters/filter.txt"
    curl -fL -o blocklists/oisd/oisd_big.txt   "https://big.oisd.nl"
    curl -fL -o blocklists/stevenblack/hosts   "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    curl -fL -o blocklists/easylist/easylist.txt     "https://easylist.to/easylist/easylist.txt"
    curl -fL -o blocklists/easylist/easyprivacy.txt  "https://easylist.to/easylist/easyprivacy.txt"
    curl -fL -o blocklists/dandelion/antimalware.txt "https://raw.githubusercontent.com/DandelionSprout/adfilt/master/Alternate%20versions%20Anti-Malware%20List/AntiMalwareAdGuardHome.txt"
    curl -fL -o blocklists/nocoin/hosts.txt    "https://raw.githubusercontent.com/hoshsadiq/adblock-nocoin-list/master/hosts.txt"
    curl -fL -o blocklists/phishing/phishing_army.txt "https://phishing.army/download/phishing_army_blocklist_extended.txt"
    # NOTE: blocklists/social/facebook.txt is CURATED BY HAND — never overwrite it.

    # rebuild + verify + ship into the app:
    python3 build-tools/build_blocklist.py
    cd build-tools && python3 test_filter.py && cd ..
    cp build-tools/out/guardian-default.gbf app/src/main/assets/guardian-default.gbf
    cp build-tools/out/manifest.json app/src/main/assets/blocklist-manifest.json

    # push so every install auto-updates within a day:
    git add -A
    git commit -m "Filter update: <what changed>"
    git push origin main

Phones/TVs pick the new filter up automatically (once a day), or instantly
via "Check for blocklist update" in the app + toggling protection off/on.
A filter push does NOT need a new APK or release.

---

## Publish a new app version (only when APP CODE changed)

1. Bump `versionCode` (+1 every time) and `versionName` in `app/build.gradle.kts`
2. Write `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`
3. Then:

    bash build-on-linux.sh release
    git add -A
    git commit -m "vX.Y.Z: <what changed>"
    git push origin main
    git tag vX.Y.Z
    git push origin vX.Y.Z
    cp app/build/outputs/apk/release/app-release.apk ~/Desktop/noxa-vX.Y.Z.apk

4. GitHub → Releases → Draft a new release → choose tag vX.Y.Z →
   attach the APK → Publish.

### Full copy-paste example (replace 1.0.1 with the new version everywhere)

    cd ~/Desktop/privacy
    bash build-on-linux.sh release
    cp app/build/outputs/apk/release/app-release.apk noxa-v1.0.1.apk
    git add -A
    git commit -m "v1.0.1: <what changed>"
    git push origin main
    git tag v1.0.1
    git push origin v1.0.1

Then in the browser:
1. https://github.com/popeye-mk/Noxa/releases/new
2. Choose a tag → v1.0.1 (pick the existing one, don't type a new one)
3. Title: Noxa 1.0.1
4. Description: what changed, in plain words (reuse the changelog text)
5. Attach noxa-v1.0.1.apk from the privacy folder → Publish release

---

## Git basics used here

    git status                  # what changed
    git log --oneline -5        # recent commits
    git pull                    # fetch changes made on GitHub's website first!
    rm -f .git/index.lock       # if git complains "index.lock exists"

Login: username `popeye-mk`, password = Personal Access Token (not the
GitHub password).

## Where things live

    app/                      the Android app (Kotlin)
    app/src/main/assets/      compiled filter shipped inside the APK
    build-tools/              filter builder + verification tests
    blocklists/               raw list sources (local only, not on GitHub)
    fastlane/                 F-Droid store listing (descriptions, screenshots)
    backups/                  known-good code snapshots (local only)
    docs/                     all documentation, incl. FDROID-AND-RELEASE.md
    noxa-release.jks          signing key (SECRET — local + USB only)
    keystore.properties       signing password (SECRET — local + USB only)

F-Droid request: https://gitlab.com/fdroid/rfp/-/issues/4287
