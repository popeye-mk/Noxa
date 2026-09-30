#!/usr/bin/env python3
"""
Score a compiled filter against the public ad-block test used by
adblock.turtlecute.org (d3ward/toolz adblock_data.json): the % of its test
hosts the app would block, using the app's exact lookup (parent walk).

  python3 build-tools/test_site_coverage.py [filter.gbf ...] [--data adblock_data.json]
  python3 build-tools/test_site_coverage.py --compare OLD.gbf NEW.gbf   # exit 1 if NEW < OLD

Note: the live test also counts things DNS can't see (same-domain/cosmetic
ads), so the site's % can differ slightly; this measures the DNS part, which
is what the filter controls.
"""
import hashlib, json, os, struct, sys, urllib.request

DATA_URL = "https://raw.githubusercontent.com/d3ward/toolz/master/src/data/adblock_data.json"


def load_gbf(path):
    raw = open(path, "rb").read()
    k = struct.unpack("<I", raw[4:8])[0]; m = struct.unpack("<Q", raw[8:16])[0]
    ba = raw[24:]

    def contains(d):
        h = hashlib.sha256(d.encode()).digest()
        h1 = int.from_bytes(h[0:8], "little"); h2 = int.from_bytes(h[8:16], "little")
        for i in range(k):
            idx = (h1 + i * h2) % m
            if not (ba[idx >> 3] >> (idx & 7)) & 1:
                return False
        return True

    def blocked(host):
        h = host.lower().rstrip(".")
        while "." in h:
            if contains(h):
                return True
            h = h.split(".", 1)[1]
        return False
    return blocked


def load_data(path=None):
    if path:
        return json.load(open(path))
    with urllib.request.urlopen(DATA_URL, timeout=30) as r:
        return json.load(r)


def hosts(data):
    out = []
    for cat, groups in data.items():
        for group, hs in groups.items():
            for h in hs:
                out.append((cat, group, h))
    return out


def score(gbf, data, verbose=True):
    blocked = load_gbf(gbf)
    hs = hosts(data)
    miss = [(c, g, h) for c, g, h in hs if not blocked(h)]
    pct = 100.0 * (len(hs) - len(miss)) / len(hs)
    if verbose:
        print(f"{gbf}: {len(hs) - len(miss)}/{len(hs)} test hosts blocked = {pct:.1f}%")
        for c, g, h in miss:
            print(f"   miss  {c} / {g}: {h}")
    return pct, {h for _, _, h in miss}


if __name__ == "__main__":
    args = sys.argv[1:]
    data_path = None
    if "--data" in args:
        i = args.index("--data"); data_path = args[i + 1]; del args[i:i + 2]
    data = load_data(data_path)
    if args and args[0] == "--compare":
        old, new = args[1], args[2]
        po, mo = score(old, data, verbose=False)
        pn, mn = score(new, data)
        print(f"\nshipped {po:.1f}%  ->  new {pn:.1f}%")
        lost = sorted(mn - mo)
        if lost:
            print("REGRESSION — newly unblocked test hosts:", ", ".join(lost))
            sys.exit(1)
        print("No test host lost.")
    else:
        for g in (args or [os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                                        "app", "src", "main", "assets", "guardian-default.gbf")]):
            score(g, data)
