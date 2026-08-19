# Transfer & Setup — moving Guardian to your dev PC

Everything Guardian needs is inside the `privacy/` folder. Copy the **whole folder** to the other PC and follow this. Written so you can do it step by step.

---

## 1. Copy the `privacy/` folder — but NOT the big extracted UT1 tree

Copy the whole `privacy/` folder **except** the extracted UT1 folder, which is 141 MB of files and won't copy cleanly. You only need the small archive.

**Before copying, delete this one folder:**

```
privacy/blocklists/ut1/blacklists/      ← DELETE this (141 MB, re-created automatically)
```

**Keep this file — it's all that's needed:**

```
privacy/blocklists/ut1/blacklists.tar.gz   ← KEEP (25 MB). The build tool
                                              auto-extracts it on the dev PC.
```

So you're copying ~25 MB of UT1 instead of 141 MB. Everything else in `privacy/` is small (docs, code, the 1.4 MB compiled filter).

### Format the USB the right way first

If the copy keeps failing, it's almost always the USB format. Format it as **exFAT** — it works on Windows, Mac, and Linux and has no file-size or file-count limits.

- **Windows:** right-click the USB drive → *Format* → File system: **exFAT** → Start.
- **Mac:** Disk Utility → select the drive → *Erase* → Format: **exFAT**.

Avoid **FAT32** (can't hold files over 4 GB and chokes on big folders). NTFS works only if both PCs are Windows. **exFAT is the safe choice.**

---

## 2. Install these on the dev PC (one-time)

- **Android Studio** (free, from Google) — builds the app and runs it on a phone/emulator. Includes the Java/JDK it needs.
- **Python 3** — runs the blocklist build tool. (Usually already installed; check with `python3 --version`.)

That's it. No other tools required.

---

## 3. Do we need more blocklists? — YES

UT1 is strong on malware/phishing but **weak on ads and trackers** (its ad list is only ~4,300 domains). Since your benchmark is DuckDuckGo's tracker count, we need the tracker-focused lists. Download these and drop each at the exact path shown, then rebuild (step 4).

### Get these three (essential)

| List | Download URL | Save it to |
|------|--------------|------------|
| **StevenBlack hosts** (ads + trackers + malware, the workhorse) | https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts | `blocklists/stevenblack/hosts` |
| **EasyPrivacy** (the big tracker list — most important for the DDG benchmark) | https://easylist.to/easylist/easyprivacy.txt | `blocklists/easylist/easyprivacy.txt` |
| **EasyList** (ads) | https://easylist.to/easylist/easylist.txt | `blocklists/easylist/easylist.txt` |

### Strongly recommended (one modern, well-curated list to actually beat DDG)

| List | Download URL | Save it to |
|------|--------------|------------|
| **OISD Big** (huge, actively maintained, very low false-positive) | https://big.oisd.nl/ | `blocklists/oisd/oisd_big.txt` |

> The build tool already knows these four paths and formats. Drop the files in, and it folds them in automatically. Anything you don't download is simply skipped — no errors. This is deliberate: **no double work**, just add lists and rebuild.

**Why not more than this?** More lists mean more overlap and more risk of wrongly blocking a real site. This set already covers what the paid tools (AdGuard, etc.) use underneath. We add more later only if the Step 8 real-usage test shows a gap.

---

## 4. Rebuild the filter with the new lists

Open a terminal in the `privacy/build-tools/` folder and run:

```
python3 build_blocklist.py
```

The first time it runs it **auto-extracts** `blacklists.tar.gz` (so you never copy the 141 MB folder). It then prints how many domains each list added and writes a fresh `out/guardian-default.gbf`. Then copy that compiled filter into the app:

```
cp out/guardian-default.gbf ../app/src/main/assets/guardian-default.gbf
cp out/manifest.json         ../app/src/main/assets/blocklist-manifest.json
```

(That's the only handoff between the build tool and the app.)

---

## 5. Build & run the app

1. Open Android Studio → **Open** → select the `privacy` folder.
2. Wait for it to finish loading (first time downloads build tools).
3. Connect an Android phone with **USB debugging** on (or start the emulator).
4. Press **Run ▶**. Guardian installs and opens.
5. Tap the switch → allow the VPN prompt → the "blocked" counter starts climbing.

Full plain-language detail is in `app/HOW-TO-BUILD.md`.

---

## 6. Where we are (recap)

- ✅ Blocklist pipeline built and **verified** (0 false positives; real sites pass).
- ✅ 0.92 MB filter compiled from UT1 protection categories.
- 🟡 Android app scaffolded — needs this dev PC to build and run.
- ⚪ Add the 3–4 lists above and rebuild.
- ⚪ On-device test vs the **22,616 tracking attempts / 5 days** DDG baseline = the Phase 1 finish line.

---

## 7. What to do first on the dev PC

1. Download the 3 essential lists (+ OISD) into the paths in step 3.
2. Run the rebuild (step 4) — you'll see the domain count jump well past 269,705.
3. Open in Android Studio and press Run.

When you're on the other PC and stuck at any step, tell me which step number and I'll walk you through it.
