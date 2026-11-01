#!/usr/bin/env python3
"""Weekly source health: did any list source break?

Compares this build's per-source counts (out/manifest.json) with the list the
app ships now (app/src/main/assets/blocklist-manifest.json). A source that
delivered domains last time and now delivers NOTHING, or lost more than half,
usually means its download broke (URLhaus went to 0 for weeks in Oct 2026
before anyone noticed). This never stops a build — the other gates do that —
it writes out/source-health.txt, which the publish job turns into a GitHub
issue so the owner hears about it.

Usage: source_health.py <new manifest> <shipped manifest> <report out>
Exit code 0 always.
"""
import json
import sys

DROP = 0.5   # report if a source lost more than half its domains


def problems(new_counts, old_counts):
    """Pure, tested: plain-language lines, one per broken-looking source."""
    out = []
    for name in sorted(old_counts):
        old = int(old_counts.get(name) or 0)
        if old <= 0:
            continue                      # was already empty/skipped: nothing new to say
        if name not in new_counts:
            out.append(f"{name}: missing from this build (had {old:,} domains)")
            continue
        new = int(new_counts.get(name) or 0)
        if new == 0:
            out.append(f"{name}: delivered 0 domains (had {old:,}) — its download probably failed")
        elif new < old * DROP:
            out.append(f"{name}: dropped from {old:,} to {new:,} domains")
    return out


def main(new_path, old_path, report_path):
    try:
        new = json.load(open(new_path)).get("extra_sources", {})
        old = json.load(open(old_path)).get("extra_sources", {})
    except (OSError, ValueError) as e:
        new, old = {}, {}
        print(f"source health: could not read manifests ({e})")
    lines = problems(new, old)
    with open(report_path, "w") as f:
        f.write("\n".join(lines) + ("\n" if lines else ""))
    if lines:
        print("SOURCE PROBLEMS:"); [print("  - " + l) for l in lines]
    else:
        print("All list sources delivered as usual.")
    return 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:4]))
