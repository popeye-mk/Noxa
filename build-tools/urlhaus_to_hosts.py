#!/usr/bin/env python3
"""abuse.ch URLhaus -> hosts file for build_blocklist.py.

Since October 2026 abuse.ch no longer serves the keyless hostfile
(/downloads/hostfile/ answers 410 Gone); the feed is now a CSV export
behind a free Auth-Key. This turns that CSV into the same hosts format the
build always read ("0.0.0.0 host"), so nothing else changes.

Rules (the old hostfile behaved the same way):
- only URLs that are online right now (url_status == "online")
- only host names: IP addresses are skipped (the build drops them anyway)
- never a shared platform where ONE bad upload doesn't make the whole site
  bad (github.com, Google Drive, Dropbox, Discord CDN, cloud storage...):
  blocking those would break the normal internet for everyone

Usage: urlhaus_to_hosts.py <recent.csv or .zip> <out hosts file>
"""
import csv
import io
import ipaddress
import sys
import zipfile
from urllib.parse import urlsplit

# A host equal to, or under, one of these is never taken from URLhaus.
SHARED_PLATFORMS = (
    "github.com", "githubusercontent.com", "gitlab.com", "bitbucket.org",
    "sourceforge.net", "google.com", "googleusercontent.com", "googleapis.com",
    "dropbox.com", "dropboxusercontent.com", "discord.com", "discordapp.com",
    "discordapp.net", "live.com", "onedrive.com", "1drv.ms", "sharepoint.com",
    "microsoft.com", "windows.net", "azureedge.net", "amazonaws.com",
    "cloudfront.net", "mediafire.com", "mega.nz", "wetransfer.com",
    "pastebin.com", "telegram.org", "t.me", "archive.org", "yandex.ru",
    "disk.yandex.ru", "box.com", "catbox.moe", "transfer.sh", "4shared.com",
    "apple.com", "icloud.com", "facebook.com", "fbcdn.net", "whatsapp.net",
)


def shared(host):
    return any(host == p or host.endswith("." + p) for p in SHARED_PLATFORMS)


def is_ip(host):
    try:
        ipaddress.ip_address(host.strip("[]"))
        return True
    except ValueError:
        return False


def read_text(path):
    data = open(path, "rb").read()
    if data[:2] == b"PK":                         # the export may come zipped
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            name = next(n for n in z.namelist() if n.lower().endswith(".csv"))
            data = z.read(name)
    return data.decode("utf-8", errors="ignore")


def hosts_from_csv(text):
    """Pure, tested: the set of online, non-shared host names in the CSV."""
    cols = ["id", "dateadded", "url", "url_status", "last_online", "threat",
            "tags", "urlhaus_link", "reporter"]           # abuse.ch's documented order
    out = set()
    for row in csv.reader(io.StringIO(text)):
        if not row:
            continue
        first = row[0].strip()
        if first.startswith("#"):
            # "# id,dateadded,url,..." comment header: take the column order from it
            names = [c.strip().lstrip("#").strip() for c in row]
            if "url" in names and "url_status" in names:
                cols = names
            continue
        if first == "id":                                  # plain (uncommented) header
            cols = [c.strip() for c in row]
            continue
        rec = dict(zip(cols, row))
        if rec.get("url_status", "").strip().lower() != "online":
            continue
        try:
            host = (urlsplit(rec.get("url", "").strip()).hostname or "").lower().rstrip(".")
        except ValueError:
            continue
        if not host or "." not in host or is_ip(host) or shared(host):
            continue
        out.add(host)
    return out


def main(src, dst):
    hosts = hosts_from_csv(read_text(src))
    with open(dst, "w", encoding="utf-8") as f:
        f.write("# abuse.ch URLhaus (online malware hosts), converted for Noxa\n")
        for h in sorted(hosts):
            f.write(f"0.0.0.0 {h}\n")
    print(f"  urlhaus: {len(hosts)} online malware hosts")
    return 0 if hosts else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
