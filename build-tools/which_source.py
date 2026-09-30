#!/usr/bin/env python3
"""
Which source list blocks a domain?  (needs the raw lists: fetch_sources.sh)

  python3 build-tools/which_source.py akamaihd.net cdn.example.com

For each domain it walks the same parent chain as the app
(a.b.example.com -> b.example.com -> example.com) and prints every source
(UT1 category or extra list) that contains that exact entry, parsed with the
builder's own readers — so the answer matches what the build did.
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_blocklist as b


def load_sources():
    b.ensure_ut1_extracted()
    srcs = {}
    for cat in b.PROTECTION_CATEGORIES:
        srcs["ut1:" + cat] = set(b.read_category(cat))
    for name in b.EXTRA_SOURCES:
        srcs[name] = set(b.read_extra_source(name))
    srcs["curated_always_block"] = set(b.ALWAYS_BLOCK_DOMAINS)
    return srcs


def explain(domain, srcs):
    lines = []
    h = domain.lower().rstrip(".")
    while "." in h:
        hits = sorted(name for name, s in srcs.items() if h in s)
        if hits:
            lines.append(f"  {h}  <-  {', '.join(hits)}")
        h = h.split(".", 1)[1]
    return lines or ["  (not in any source)"]


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    srcs = load_sources()
    for d in sys.argv[1:]:
        print(d)
        print("\n".join(explain(d, srcs)))
