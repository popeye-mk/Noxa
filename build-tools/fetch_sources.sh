#!/usr/bin/env bash
# Download every raw blocklist source into blocklists/ (the paths
# build_blocklist.py expects). Used by the weekly GitHub rebuild; works
# locally too:  bash build-tools/fetch_sources.sh
#
# UT1 is required (it's the base of the filter) — failure aborts.
# Every other source is best-effort: a missing one is reported, the build
# skips it, and verify_build.py's size gate refuses to ship a filter that
# shrank suspiciously because a big source went missing.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BL="$ROOT/blocklists"
mkdir -p "$BL"
failed=()

get() {   # get <url> <dest relative to blocklists/>
  local url="$1" dest="$BL/$2"
  mkdir -p "$(dirname "$dest")"
  if curl -fsSL --retry 3 --retry-delay 5 --max-time 300 -A "Noxa-blocklist-builder" \
       -o "$dest.part" "$url" && [ -s "$dest.part" ]; then
    mv "$dest.part" "$dest"
    printf '  ok    %-40s %8s lines\n' "$2" "$(wc -l < "$dest")"
  else
    rm -f "$dest.part"
    printf '  FAIL  %s  (%s)\n' "$2" "$url"
    failed+=("$2")
  fi
}

echo "==> UT1 (required)"
get "https://dsi.ut-capitole.fr/blacklists/download/blacklists.tar.gz" "ut1/blacklists.tar.gz"
if [ ! -s "$BL/ut1/blacklists.tar.gz" ]; then
  echo "UT1 download failed — aborting (never build a filter without its base)."
  exit 1
fi
rm -rf "$BL/ut1/blacklists"   # force a fresh extract of the new archive

echo "==> Extra sources (best-effort)"
get "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"                        "stevenblack/hosts"
get "https://easylist.to/easylist/easyprivacy.txt"                                          "easylist/easyprivacy.txt"
get "https://easylist.to/easylist/easylist.txt"                                             "easylist/easylist.txt"
get "https://big.oisd.nl/"                                                                  "oisd/oisd_big.txt"
get "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/pro.txt"          "hagezi/pro.txt"
# AdGuard DNS filter: the old AdGuardSDNSFilter/master/Filters path now 404s;
# the compiled list is published from AdGuard's HostlistsRegistry.
get "https://raw.githubusercontent.com/AdguardTeam/HostlistsRegistry/main/filters/general/filter_1_DnsFilter/filter.txt" "adguard/dns.txt"
get "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/doh.txt"          "hagezi/doh.txt"
get "https://raw.githubusercontent.com/DandelionSprout/adfilt/master/Alternate%20versions%20Anti-Malware%20List/AntiMalwareAdGuardHome.txt" "dandelion/antimalware.txt"
# NOT fetched: jmdugan corporations/facebook/all. Despite being listed as a
# "Meta pixel/SDK" source, it blocks ALL of Facebook, Instagram, WhatsApp and
# Messenger (facebook.com, whatsapp.net, fbcdn.net...). Meta's tracking
# endpoints are already covered by the other lists (connect.facebook.net etc.).
get "https://raw.githubusercontent.com/hoshsadiq/adblock-nocoin-list/master/hosts.txt"      "nocoin/hosts.txt"
get "https://phishing.army/download/phishing_army_blocklist_extended.txt"                   "phishing/phishing_army.txt"
# v1.10 stronger malware protection:
# HaGeZi Threat Intelligence Feeds, "mini" (~180k of the most important
# malware / phishing / scam / C2 feeds — sized for phones).
get "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/tif.mini.txt"     "hagezi/tif_mini.txt"
# abuse.ch URLhaus: hosts serving malware downloads right now (small, fresh).
get "https://urlhaus.abuse.ch/downloads/hostfile/"                                           "urlhaus/hostfile.txt"
# HaGeZi "Most Abused TLDs" (no-exclusions version): the web endings scammers
# use most. NOT added to the main filter — it powers the optional
# "Strict scam protection" switch in the app (off by default).
get "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/spam-tlds-adblock.txt" "hagezi/spam_tlds.txt"

if [ ${#failed[@]} -gt 0 ]; then
  echo "WARNING: ${#failed[@]} optional source(s) failed: ${failed[*]}"
fi
exit 0
