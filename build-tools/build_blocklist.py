#!/usr/bin/env python3
"""
Guardian blocklist build tool  (Phase 1, steps 2-5)

Turns the raw UT1 category lists (plus StevenBlack / EasyList / EasyPrivacy
when present) into ONE compact Bloom filter the phone can query instantly.

Design rules this follows:
  - No double work: every source is read once, merged once, compiled once.
  - Keep ALL categories on disk (already extracted); only the PROTECTION set
    is compiled into the default filter. Content categories (adult, gambling,
    social, ...) stay available for an optional, off-by-default toggle later.
  - The phone never parses raw text: it ships the compiled .gbf filter only.

Output (written to  build-tools/out/ ):
  guardian-default.gbf   <- the Bloom filter (default protection set)
  manifest.json          <- what went in, counts, parameters
  build-report.txt       <- human-readable summary
  merged-domains.txt      <- the deduped domain list (for auditing/testing)

Run:  python3 build_blocklist.py
No third-party packages required (standard library only).
"""

import os, sys, json, math, struct, hashlib, time, tarfile

# --- paths -------------------------------------------------------------------
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)                      # the privacy/ project root
UT1  = os.path.join(ROOT, "blocklists", "ut1", "blacklists")
OUT  = os.path.join(HERE, "out")

# --- which UT1 categories are PROTECTION (on by default) ---------------------
# NOTE: UT1's ad list is called "publicite", not "ads".
# These are safety/tracking categories that do NOT break normal phone use.
PROTECTION_CATEGORIES = [
    "publicite",           # ads / ad networks
    "malware",             # known bad / malware domains
    "phishing",            # phishing pages
    "cryptojacking",       # in-browser crypto miners
    "stalkerware",         # covert monitoring apps calling home
    "marketingware",       # aggressive marketing / tracking
    "ddos",                # hosts used in DDoS
    "hacking",             # attack / exploit infrastructure
    "dialer",              # premium-rate dialer scams
    "doh",                 # DNS-over-HTTPS servers apps use to bypass filtering
    "dynamic-dns",         # dynamic-DNS hosts commonly used as malware C2
    "residential-proxies", # proxy/abuse infrastructure
    "redirector",          # click-through tracking redirectors (big tracker win;
                           # watch this one in the Step 8 on-device test)
]

# EXTRA protective-ish, but higher false-positive risk. NOT on by default.
# Move any of these up into PROTECTION_CATEGORIES if the on-device test shows
# they help more than they hurt.
EXTRA_OPTIONAL = [
    "shortener",           # url shorteners (blocks bit.ly-style links)
    "warez",               # piracy sites (often malware-laden, but also content)
    "fakenews",            # editorial judgment, not a security threat
    "dangerous_material",  # content filtering, not safety
]

# CONTENT filtering: kept on disk, NOT compiled into the default filter.
# Optional, hidden, off-by-default toggle in a later phase (parental-style).
# Blocking these by default would break normal use.
CONTENT_CATEGORIES = [
    "adult", "porn", "mixed_adult", "lingerie", "gambling", "arjel",
    "social_networks", "games", "shopping", "dating", "sports", "press",
    "blog", "radio", "audio-video", "manga", "celebrity", "cooking", "chat",
    "forums", "webmail", "mail", "filehosting", "download", "jobsearch",
    "sect", "astrology", "drugs", "drogue", "violence", "agressif",
    "aggressive", "mobile-phone", "translation", "cleaning", "remote-control",
    "vpn", "proxy", "bitcoin", "sexual_education",
]

# NEVER BLOCK. These are whitelists, real banking sites, or sites for children /
# education. Blocking any of them would be a bug. Listed so it is explicit.
NEVER_BLOCK = [
    "liste_blanche", "liste_bu", "exceptions_liste_bu",   # whitelists
    "bank", "financial",                                   # real banking sites
    "child", "educational_games",                          # sites for kids
    "special", "reaffected", "update", "ai",               # infra / benign
    "associations_religieuses",
]

# Hard allowlist applied to the FINAL merged set (all sources, not just UT1).
# These UT1 category domain-lists are subtracted after the merge, so no external
# list (EasyList / EasyPrivacy / OISD / StevenBlack) can ever sinkhole a real
# bank or a whitelisted site — even if that list contains a bad domain-level rule.
ALLOWLIST_CATEGORIES = [
    "liste_blanche",           # UT1 general whitelist
    "bank", "financial",       # real banking / finance sites
    # NOTE: 'liste_bu' is deliberately EXCLUDED — it is an institutional whitelist
    # that contains ad/tracker hosts (e.g. doubleclick.net), so using it as an
    # allowlist would un-block real trackers. bank+financial+liste_blanche cover
    # the real banking/whitelist domains we care about.
]

# Individual infrastructure domains we NEVER block — sinkholing these would be a
# false-positive that breaks legitimate service. Public DNS resolvers (and their
# help/diagnostic pages) live here: some blocklists include e.g. one.one.one.one,
# which would block Guardian's own upstream resolver's domain. Both Cloudflare and
# Quad9 are listed so we can switch the upstream resolver freely later.
ALLOWLIST_DOMAINS = [
    # Quad9's info site (NOT its DoH endpoint). The DoH endpoints that used to
    # be allowlisted here (one.one.one.one, cloudflare-dns.com, dns.quad9.net,
    # dns.google) were REMOVED on purpose: browsers use them to bypass the
    # filter via "secure DNS". They are now blocked by the hagezi_doh source so
    # every browser falls back to normal DNS, which we filter. Users who really
    # want one can un-block it with the in-app allowlist.
    "quad9.net",
    # Network connectivity / captive-portal checks — blocking these makes the OS
    # think there's "no internet" even when there is. Defensive: never sinkhole.
    "connectivitycheck.gstatic.com",
    "connectivitycheck.android.com",
    "clients3.google.com",
    "captive.apple.com",
    # Legitimate VPN providers — a privacy app must not block privacy tools (and
    # Guardian's own tunnel uses these). ONLY the official domains; lookalike/scam
    # variants (mullvad-download.*, etc.) deliberately stay blocked.
    "mullvad.net",
    "protonvpn.com",
    "proton.me",
    "ivpn.net",
    # Browser ad-block list updates — a blocker must never starve OTHER blockers
    # of their filter lists (learned the hard way: Brave stuck without its lists
    # scores ~18 points worse on ad-block tests). Update endpoints only; Brave's
    # telemetry domains (p3a.brave.com etc.) deliberately stay blocked.
    "componentupdater.brave.com",
    "go-updater.brave.com",
    "brave-core-ext.s3.brave.com",
]

# --- Bloom filter parameters -------------------------------------------------
# p = target false-positive rate. Lower = fewer legit domains wrongly blocked,
# at the cost of a slightly bigger file and more hashes per lookup.
FALSE_POSITIVE_RATE = 1e-6      # ~1 in a million: very safe for a blocker
MAGIC = b"GBF1"                 # Guardian Bloom Filter, format v1


def normalize(line: str):
    """Lowercase, trim, drop comments/blanks, keep only plausible domains."""
    s = line.strip().lower()
    if not s or s.startswith("#"):
        return None
    # UT1 domain files are bare domains, but be defensive: strip any protocol,
    # path, or port that might sneak in from other sources.
    for pfx in ("http://", "https://"):
        if s.startswith(pfx):
            s = s[len(pfx):]
    s = s.split("/")[0].split(":")[0].strip()
    if "." not in s or " " in s:
        return None
    return s


def read_category(cat: str):
    path = os.path.join(UT1, cat, "domains")
    if not os.path.isfile(path):
        return []
    out = []
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            d = normalize(line)
            if d:
                out.append(d)
    return out


# ---- optional extra sources (only used if the user has downloaded them) -----
# Drop the downloaded files at these paths and re-run; missing ones are skipped.
EXTRA_SOURCES = {
    # name          : (relative path,                         format)
    "stevenblack":    ("blocklists/stevenblack/hosts",        "hosts"),
    "easyprivacy":    ("blocklists/easylist/easyprivacy.txt", "adblock"),
    "easylist":       ("blocklists/easylist/easylist.txt",    "adblock"),
    "oisd":           ("blocklists/oisd/oisd_big.txt",        "adblock"),  # optional, recommended
    # Step 1 additions — modern, low-false-positive, actively maintained:
    "hagezi_pro":     ("blocklists/hagezi/pro.txt",           "adblock"),  # HaGeZi Multi PRO
    "adguard_dns":    ("blocklists/adguard/dns.txt",          "adblock"),  # AdGuard DNS filter
    # Anti-bypass: hostnames of every public DoH resolver. Browsers (Brave/Chrome)
    # bootstrap "secure DNS" by resolving these through the SYSTEM resolver — us.
    # Sinkhole them and the browser silently falls back to normal DNS, which we
    # filter. Same idea as the Firefox use-application-dns.net canary, but for
    # every browser. Download:
    #   https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/doh.txt
    "hagezi_doh":     ("blocklists/hagezi/doh.txt",           "adblock"),  # DoH/DoT bypass
    # Improvement plan phase 1 — targeted at MEASURED gaps (social SDK 88%):
    "dandelion_am":   ("blocklists/dandelion/antimalware.txt","adblock"),  # Anti-Malware
    "facebook_sdk":   ("blocklists/social/facebook.txt",      "domains"),  # Meta pixel/SDK
    "nocoin":         ("blocklists/nocoin/hosts.txt",         "hosts"),    # cryptomining
    "phishing_army":  ("blocklists/phishing/phishing_army.txt","domains"), # phishing
}


def read_hosts(path):
    """hosts file format: '0.0.0.0 domain.com'."""
    out = []
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if len(parts) >= 2 and parts[0] in ("0.0.0.0", "127.0.0.1"):
                d = normalize(parts[1])
                if d and d != "localhost":
                    out.append(d)
    return out


def read_adblock(path):
    """Extract DNS-safe domain rules from Adblock Plus syntax (EasyList /
    EasyPrivacy / OISD). We take only domain-anchored network rules like
    '||tracker.com^' and skip exceptions (@@), cosmetic rules (##), and any
    rule with a path or wildcard — those can't be safely applied at DNS level."""
    out = []
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            s = line.strip()
            if not s or s[0] in "!#[":            # comment / metadata / cosmetic
                continue
            if s.startswith("@@"):                 # exception rule — never block
                continue
            if "##" in s or "#@#" in s or "#?#" in s:   # element hiding
                continue
            if not s.startswith("||"):             # only domain-anchored rules
                continue
            body = s[2:]
            # drop the option suffix first (e.g. '||host^$third-party')
            d_i = body.find("$")
            if d_i != -1:
                body = body[:d_i]
            # A '/' before the host terminator means this is a PATH-specific rule
            # (e.g. '||google.com/pagead/ads.js' = block one script, not the site).
            # DNS can only block whole domains, so these must be skipped — otherwise
            # the bare domain (google.com) gets sinkholed. This matches the docstring.
            caret = body.find("^")
            slash = body.find("/")
            if slash != -1 and (caret == -1 or slash < caret):
                continue
            if caret != -1:                        # '||host^...'
                # Anything after the host terminator '^' makes it resource-specific
                # (e.g. '||paypal.com^*/pixel.gif' = block one pixel, not the bank).
                # DNS can't match a path/pattern, so skip rather than sinkhole the host.
                if body[caret + 1:] != "":
                    continue
                body = body[:caret]
            if "*" in body or body == "":          # wildcards: skip (not DNS-safe)
                continue
            d = normalize(body)
            if d:
                out.append(d)
    return out


def read_domains(path):
    """Bare-domain list format: one domain per line (Phishing Army, jmdugan)."""
    out = []
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            d = normalize(line)
            if d:
                out.append(d)
    return out


def read_extra_source(name):
    rel, fmt = EXTRA_SOURCES[name]
    path = os.path.join(ROOT, rel)
    if not os.path.isfile(path):
        return []
    if fmt == "hosts":
        return read_hosts(path)
    if fmt == "domains":
        return read_domains(path)
    return read_adblock(path)


def bloom_params(n: int, p: float):
    """Return (m_bits, k_hashes) for n items at false-positive rate p."""
    if n <= 0:
        return 8, 1
    m = math.ceil(-(n * math.log(p)) / (math.log(2) ** 2))
    m = max(m, 8)
    k = max(1, round((m / n) * math.log(2)))
    return int(m), int(k)


def build_bloom(domains, m_bits, k):
    """Kirsch-Mitzenmacher double hashing over SHA-256. Documented so the
    Android side can reproduce lookups exactly."""
    ba = bytearray((m_bits + 7) // 8)
    for d in domains:
        h = hashlib.sha256(d.encode("utf-8")).digest()
        h1 = int.from_bytes(h[0:8],  "little")
        h2 = int.from_bytes(h[8:16], "little")
        for i in range(k):
            idx = (h1 + i * h2) % m_bits
            ba[idx >> 3] |= (1 << (idx & 7))
    return ba


def write_gbf(path, m_bits, k, n, ba):
    with open(path, "wb") as f:
        f.write(MAGIC)
        f.write(struct.pack("<I", k))        # uint32 num hashes
        f.write(struct.pack("<Q", m_bits))   # uint64 bit count
        f.write(struct.pack("<Q", n))        # uint64 items inserted
        f.write(bytes(ba))


def ensure_ut1_extracted():
    """The extracted UT1 tree is ~141 MB (too big to copy between PCs). So we
    transfer only the ~25 MB blacklists.tar.gz and extract it here on demand."""
    if os.path.isdir(UT1):
        return
    archive = os.path.join(ROOT, "blocklists", "ut1", "blacklists.tar.gz")
    if not os.path.isfile(archive):
        sys.exit("ERROR: neither the UT1 folder nor blacklists.tar.gz was found "
                 "at blocklists/ut1/. Download the archive and put it there.")
    print("UT1 not extracted yet — extracting %s ..." % archive)
    with tarfile.open(archive, "r:gz") as tar:
        tar.extractall(os.path.join(ROOT, "blocklists", "ut1"))
    print("Extracted.")


def main():
    os.makedirs(OUT, exist_ok=True)
    ensure_ut1_extracted()
    if not os.path.isdir(UT1):
        sys.exit("ERROR: UT1 lists not found at %s" % UT1)

    print("Reading UT1 protection categories...")
    per_cat = {}
    all_domains = set()
    for cat in PROTECTION_CATEGORIES:
        doms = read_category(cat)
        per_cat[cat] = len(doms)
        all_domains.update(doms)
        print("  %-16s %8d" % (cat, len(doms)))

    print("\nReading extra sources (skipped if not downloaded yet)...")
    for name in EXTRA_SOURCES:
        doms = read_extra_source(name)
        per_cat[name] = len(doms)
        if doms:
            all_domains.update(doms)
            print("  %-16s %8d" % (name, len(doms)))
        else:
            print("  %-16s   (not present — skipped)" % name)

    print("\nApplying NEVER-BLOCK allowlist (UT1 banks / financial / whitelists)...")
    allow = set()
    for cat in ALLOWLIST_CATEGORIES:
        allow.update(read_category(cat))
    allow.update(ALLOWLIST_DOMAINS)
    before = len(all_domains)
    all_domains -= allow
    print("  allowlist: %d domains ; removed %d from blocklist"
          % (len(allow), before - len(all_domains)))

    n = len(all_domains)
    raw_total = sum(per_cat.values())
    print("\nMerged + deduplicated: %d unique domains (from %d raw)" % (n, raw_total))

    domains = sorted(all_domains)
    m_bits, k = bloom_params(n, FALSE_POSITIVE_RATE)
    size_mb = ((m_bits + 7) // 8) / (1024 * 1024)
    print("Bloom filter: m=%d bits (%.2f MB), k=%d hashes, target FP=%.0e"
          % (m_bits, size_mb, k, FALSE_POSITIVE_RATE))

    print("Building filter...")
    t0 = time.time()
    ba = build_bloom(domains, m_bits, k)
    write_gbf(os.path.join(OUT, "guardian-default.gbf"), m_bits, k, n, ba)
    build_s = time.time() - t0

    with open(os.path.join(OUT, "merged-domains.txt"), "w") as f:
        f.write("\n".join(domains))

    manifest = {
        "format": "GBF1",
        "built_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "false_positive_target": FALSE_POSITIVE_RATE,
        "bloom": {"m_bits": m_bits, "k_hashes": k,
                  "size_mb": round(size_mb, 3), "items": n},
        "unique_domains": n,
        "raw_total": raw_total,
        "protection_categories": {c: per_cat[c] for c in PROTECTION_CATEGORIES},
        "extra_sources": {name: per_cat.get(name, 0) for name in EXTRA_SOURCES},
        "hashing": "SHA-256 -> h1=LE(bytes0-7), h2=LE(bytes8-15); "
                   "idx_i=(h1 + i*h2) mod m, i in 0..k-1",
    }
    with open(os.path.join(OUT, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)

    with open(os.path.join(OUT, "build-report.txt"), "w") as f:
        f.write("Guardian blocklist build report\n")
        f.write("Built: %s\n\n" % manifest["built_at"])
        f.write("Protection categories (on by default):\n")
        for c in PROTECTION_CATEGORIES:
            f.write("  %-16s %8d\n" % (c, per_cat[c]))
        f.write("\nExtra sources:\n")
        for name in EXTRA_SOURCES:
            f.write("  %-16s %8d\n" % (name, per_cat.get(name, 0)))
        f.write("\nUnique domains after dedupe: %d (from %d raw)\n" % (n, raw_total))
        f.write("Bloom filter: %.2f MB, k=%d, FP target %.0e\n"
                % (size_mb, k, FALSE_POSITIVE_RATE))
        f.write("Build time: %.1fs\n" % build_s)

    print("Done in %.1fs. Output in %s" % (build_s, OUT))


if __name__ == "__main__":
    main()
