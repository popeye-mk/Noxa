#!/usr/bin/env python3
"""
Strict gate for a freshly built filter (build-tools/out/). Exits non-zero —
so the weekly rebuild ships NOTHING — unless every check passes:

  1. file format/size match the header, SHA-256 matches the manifest
  2. every domain in merged-domains.txt is found (no false negatives)
  3. false-positive rate on random names stays near the 1e-6 target
  4. must-never-block domains (banks' infra, connectivity checks, resolvers'
     info pages, VPN providers, common sites) are NOT blocked
  5. known trackers ARE blocked
  6. size sanity vs the filter currently shipped in the app: refuse a list
     that shrank by more than MAX_DROP (a big source silently failed) or
     grew absurdly (a source went haywire)

Run:  python3 build-tools/verify_build.py
"""
import hashlib, json, os, random, string, struct, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
SHIPPED = os.path.join(os.path.dirname(HERE), "app", "src", "main", "assets", "blocklist-manifest.json")
MAX_DROP = 0.15      # refuse if the new list has >15% fewer domains
MAX_GROWTH = 1.60    # refuse if it's >60% bigger

MUST_ALLOW = [
    # Shared infrastructure: blocking the whole domain breaks thousands of
    # unrelated sites/apps. (Each was once blocked via a site-restricted
    # browser rule — see _site_restricted in build_blocklist.py.)
    "akamaihd.net", "cloudfront.net", "global.ssl.fastly.net", "b-cdn.net",
    "cdn77.org", "workers.dev", "files.wordpress.com",
    "firebase.googleapis.com", "firebaseinstallations.googleapis.com",
    "imgur.com", "i.imgur.com", "t.co", "bit.ly",
    # Cloud hosting & storage: phishing pages live on these too, so lists
    # sometimes name the whole provider — but blocking it breaks every app
    # that stores photos/files/updates there (seen: Viber media on S3).
    "amazonaws.com", "s3.amazonaws.com", "s3.eu-central-1.amazonaws.com",
    "storage.googleapis.com", "firebasestorage.googleapis.com", "firebaseapp.com",
    "blob.core.windows.net", "azurewebsites.net", "azureedge.net",
    "github.io", "objects.githubusercontent.com", "pages.dev", "r2.dev",
    "netlify.app", "vercel.app", "herokuapp.com", "digitaloceanspaces.com",
    "dropbox.com", "dl.dropboxusercontent.com", "docs.google.com", "drive.google.com",
    "sites.google.com", "sharepoint.com", "onedrive.live.com",
    "dl-media.viber.com", "media.cdn.viber.com",
    # A whole country zone (once blocked via a $badfilter misread).
    "example.pl.ua",
    "google.com", "www.google.com", "wikipedia.org", "github.com", "mozilla.org",
    "signal.org", "apple.com", "microsoft.com", "gov.uk", "bbc.co.uk",
    "connectivitycheck.gstatic.com", "connectivitycheck.android.com",
    "clients3.google.com", "captive.apple.com", "quad9.net",
    "mullvad.net", "protonvpn.com", "proton.me", "ivpn.net",
    "raw.githubusercontent.com",   # the app's own filter-update source
    # Everyday apps: a list that blocks these breaks people's phones. Their
    # TRACKING endpoints (connect.facebook.net, pixels...) are blocked
    # separately and are not on this list.
    "facebook.com", "www.facebook.com", "m.facebook.com", "edge-chat.facebook.com",
    "instagram.com", "www.instagram.com", "i.instagram.com",
    "whatsapp.com", "web.whatsapp.com", "g.whatsapp.net", "mmg.whatsapp.net",
    "messenger.com", "scontent.xx.fbcdn.net", "static.xx.fbcdn.net",
]
MUST_BLOCK = [
    "doubleclick.net", "googlesyndication.com", "google-analytics.com",
    "app-measurement.com", "adnxs.com", "scorecardresearch.com",
    "use-application-dns.net", "connect.facebook.net", "udc.yahoo.com",
]

failures = []
def check(name, ok, detail=""):
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}{('  — ' + detail) if detail else ''}")
    if not ok:
        failures.append(name)

gbf_path = os.path.join(OUT, "guardian-default.gbf")
raw = open(gbf_path, "rb").read()
manifest = json.load(open(os.path.join(OUT, "manifest.json")))
check("magic GBF1", raw[:4] == b"GBF1")
k = struct.unpack("<I", raw[4:8])[0]
m = struct.unpack("<Q", raw[8:16])[0]
n = struct.unpack("<Q", raw[16:24])[0]
ba = raw[24:]
check("body size == ceil(m/8)", len(ba) == (m + 7) // 8, f"{len(ba)} vs {(m + 7) // 8}")
check("sha256 matches manifest", hashlib.sha256(raw).hexdigest() == manifest.get("sha256"))
check("k sane (1..64)", 1 <= k <= 64, f"k={k}")

def contains(d):
    h = hashlib.sha256(d.encode()).digest()
    h1 = int.from_bytes(h[0:8], "little"); h2 = int.from_bytes(h[8:16], "little")
    for i in range(k):
        idx = (h1 + i * h2) % m
        if not (ba[idx >> 3] >> (idx & 7)) & 1:
            return False
    return True

def blocked(host):   # same parent walk as the app (BloomFilter.matchesHostOrParent)
    h = host.lower().rstrip(".")
    while "." in h:
        if contains(h):
            return True
        h = h.split(".", 1)[1]
    return False

domains = [l.strip() for l in open(os.path.join(OUT, "merged-domains.txt")) if l.strip()]
check("item count matches header", len(domains) == n, f"{len(domains)} vs {n}")
sample = domains if len(domains) <= 50000 else random.Random(1).sample(domains, 50000)
missing = [d for d in sample if not contains(d)]
check("no false negatives (sample of %d)" % len(sample), not missing, ", ".join(missing[:5]))

# False-positive sanity. The filter targets 1e-6, i.e. ~1 hit per million
# random names, so this catches a BROKEN build (wrong m/k, bit array mostly
# set: hundreds of hits) without tripping on chance: the old 200k-trial
# "< 2 hits" rule refused a good build on 2 hits (expected 0.2, ~2% odds).
dset = set(domains)
rng = random.Random(7); fp = 0; trials = 1_000_000
for _ in range(trials):
    d = "".join(rng.choices(string.ascii_lowercase, k=14)) + ".com"
    if d not in dset and contains(d):
        fp += 1
check("false-positive rate <= 2e-5 (1M random names)", fp <= 20, f"{fp}/{trials}")

bad_allow = [d for d in MUST_ALLOW if blocked(d)]
check("must-never-block domains pass", not bad_allow, ", ".join(bad_allow))
if bad_allow:
    # Say WHICH source list is responsible, so the fix is obvious.
    try:
        import which_source
        srcs = which_source.load_sources()
        for d in bad_allow:
            print(f"    why {d} is blocked:")
            print("\n".join("    " + l for l in which_source.explain(d, srcs)))
    except Exception as e:
        print(f"    (couldn't trace sources: {e})")
not_blocked = [d for d in MUST_BLOCK if not blocked(d)]
check("known trackers blocked", not not_blocked, ", ".join(not_blocked))

# Dangerous-site filter: same format checks, every entry blocked by the main
# filter, and its own false-positive sanity (a FP here = a wrong "scam" label).
t_raw = open(os.path.join(OUT, "threats.gbf"), "rb").read()
t_k = struct.unpack("<I", t_raw[4:8])[0]; t_m = struct.unpack("<Q", t_raw[8:16])[0]
t_n = struct.unpack("<Q", t_raw[16:24])[0]; t_ba = t_raw[24:]
check("threats.gbf format", t_raw[:4] == b"GBF1" and len(t_ba) == (t_m + 7) // 8 and 1 <= t_k <= 64)
check("threats sha256 matches manifest", hashlib.sha256(t_raw).hexdigest() == manifest.get("threats_sha256"))
t_domains = [l.strip() for l in open(os.path.join(OUT, "threat-domains.txt")) if l.strip()]
check("threats count matches header/manifest", len(t_domains) == t_n == manifest.get("threats_domains"), f"{len(t_domains)} / {t_n}")
check("threats list sane (50k..2M)", 50_000 <= t_n <= 2_000_000, f"{t_n:,}")

def t_contains(d):
    h = hashlib.sha256(d.encode()).digest()
    h1 = int.from_bytes(h[0:8], "little"); h2 = int.from_bytes(h[8:16], "little")
    for i in range(t_k):
        idx = (h1 + i * h2) % t_m
        if not (t_ba[idx >> 3] >> (idx & 7)) & 1:
            return False
    return True
t_sample = t_domains if len(t_domains) <= 20000 else random.Random(3).sample(t_domains, 20000)
check("threats: no false negatives (sample)", all(t_contains(d) for d in t_sample))
t_unblocked = [d for d in t_sample if not blocked(d)]
check("threats: every entry also blocked by the main filter", not t_unblocked,
      f"{len(t_unblocked)} not blocked, e.g. {t_unblocked[:8]!r}")
t_rng = random.Random(11); t_fp = 0
for _ in range(300_000):
    d = "".join(t_rng.choices(string.ascii_lowercase, k=14)) + ".com"
    if d not in dset and t_contains(d):
        t_fp += 1
check("threats: false-positive rate <= 2e-5 (300k names)", t_fp <= 6, f"{t_fp}/300000")
check("threats: everyday sites not flagged", not any(t_contains(d) for d in MUST_ALLOW))

stalk_path = os.path.join(OUT, "stalkerware.txt")
stalk = [l.strip() for l in open(stalk_path)] if os.path.isfile(stalk_path) else []
stalk = [d for d in stalk if d]
check("stalkerware list present and sane (300..5000)", 300 <= len(stalk) <= 5000, f"{len(stalk)} domains")
check("stalkerware hash matches manifest",
      hashlib.sha256(("\n".join(sorted(set(stalk))) + "\n").encode()).hexdigest() == manifest.get("stalkerware_sha256"))
check("stalkerware domains are all blocked by the filter", all(blocked(d) for d in stalk[:2000]))

# v1.10 risky web endings (optional strict mode). Optional file: if the
# source failed it's absent and the manifest hash is empty — phones keep theirs.
risky_path = os.path.join(OUT, "risky-tlds.txt")
if os.path.isfile(risky_path):
    import build_blocklist
    risky = [l.strip() for l in open(risky_path) if l.strip()]
    check("risky endings list sane (20..400)", 20 <= len(risky) <= 400, f"{len(risky)}")
    check("risky endings hash matches manifest",
          hashlib.sha256(("\n".join(risky) + "\n").encode()).hexdigest() == manifest.get("risky_tlds_sha256"))
    bad_tlds = [t for t in risky if t in build_blocklist.NEVER_RISKY_TLDS]
    check("risky endings never include everyday ones (.com, .nl, .mk, ...)", not bad_tlds, ", ".join(bad_tlds))
    hit = [d for d in MUST_ALLOW if any(d == t or d.endswith("." + t) for t in risky)]
    check("risky endings don't cover any must-allow site", not hit, ", ".join(hit[:8]))
else:
    check("no risky endings => manifest has no hash", not manifest.get("risky_tlds_sha256"))

if os.path.isfile(SHIPPED):
    old = json.load(open(SHIPPED)).get("unique_domains", 0)
    if old:
        ratio = n / old
        check(f"size sane vs shipped ({old:,} -> {n:,})", 1 - MAX_DROP <= ratio <= MAX_GROWTH, f"ratio {ratio:.3f}")

if failures:
    print(f"\nREFUSED: {len(failures)} check(s) failed — the new filter must not ship.")
    sys.exit(1)
print("\nAll build checks passed — safe to ship.")
