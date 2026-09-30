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

if os.path.isfile(SHIPPED):
    old = json.load(open(SHIPPED)).get("unique_domains", 0)
    if old:
        ratio = n / old
        check(f"size sane vs shipped ({old:,} -> {n:,})", 1 - MAX_DROP <= ratio <= MAX_GROWTH, f"ratio {ratio:.3f}")

if failures:
    print(f"\nREFUSED: {len(failures)} check(s) failed — the new filter must not ship.")
    sys.exit(1)
print("\nAll build checks passed — safe to ship.")
