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

Usage: urlhaus_to_hosts.py <export.csv or .zip> [<more.csv> ...] <out hosts file>
       urlhaus_to_hosts.py --hostfile <threatfox hostfile.txt> <out hosts file>

Nov 2026: reads online.csv (everything online now) AND recent.csv (last 30
days) and merges them, and with --hostfile filters abuse.ch ThreatFox's
ready-made hosts file through the same shared-platform rule.
"""
import csv
import io
import ipaddress
import sys
import zipfile
import zlib
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
    # Nov 2026 (ThreatFox): everyday services malware merely USES - a profile
    # page as a hidden address book, or a "what is my IP" lookup.
    "steamcommunity.com", "x.com", "twitter.com", "instagram.com", "youtube.com",
    "tiktok.com", "reddit.com", "notion.so", "trello.com", "ipify.org",
    "ip-api.com", "ipinfo.io", "icanhazip.com", "ifconfig.me", "dyndns.org",
    "myexternalip.com", "cloudflare.com", "quad9.net",
    # 23 Nov 2026, first day of ThreatFox: it named files.pythonhosted.org (every "pip install"),
    # img1.wsimg.com (the pictures of every GoDaddy-built shop), i.postimg.cc, www.sendspace.com and
    # files.constantcontact.com - places where ONE bad file was parked. The rule above, widened:
    # package stores, picture and file hosts, site builders, mail services, CDNs.
    "pythonhosted.org", "pypi.org", "npmjs.org", "npmjs.com", "unpkg.com", "jsdelivr.net",
    "rubygems.org", "crates.io", "nuget.org", "packagist.org", "maven.org", "docker.com", "docker.io",
    "gitee.com", "codeberg.org", "sourceforge.io", "launchpad.net",
    "postimg.cc", "postimages.org", "imgur.com", "imgbb.com", "ibb.co", "flickr.com", "staticflickr.com",
    "photobucket.com", "pinimg.com", "giphy.com", "tenor.com", "gyazo.com", "prnt.sc",
    "wsimg.com", "godaddy.com", "secureserver.net", "wixstatic.com", "wix.com", "squarespace.com",
    "squarespace-cdn.com", "weebly.com", "shopify.com", "wordpress.com", "wp.com", "blogger.com",
    "tumblr.com", "medium.com", "notion.site", "atlassian.net", "canva.com",
    "constantcontact.com", "mailchimp.com", "list-manage.com", "sendgrid.net", "hubspot.com",
    "hubspotusercontent.net", "zendesk.com", "freshdesk.com", "slack.com", "slack-files.com", "zoom.us",
    "sendspace.com", "zippyshare.com", "gofile.io", "pixeldrain.com", "file.io", "krakenfiles.com",
    "workupload.com", "filebin.net", "files.fm", "we.tl", "transfernow.net", "swisstransfer.com",
    "pcloud.com", "pcloud.link", "mega.io", "uploadcare.com", "cloudinary.com", "imagekit.io",
    "paste.ee", "hastebin.com", "rentry.co", "rentry.org", "justpaste.it", "telegra.ph", "controlc.com",
    "fastly.net", "akamaized.net", "akamaihd.net", "edgekey.net", "edgesuite.net", "cloudflare.net",
    "digitaloceanspaces.com", "backblazeb2.com", "wasabisys.com", "bunnycdn.com",
    "office.com", "office365.com", "outlook.com", "live.net", "msn.com", "bing.com", "azure.com",
    "windowsupdate.com", "skype.com", "linkedin.com", "licdn.com", "cdn-apple.com", "mzstatic.com",
    "gstatic.com", "gvt1.com", "ggpht.com", "ytimg.com", "googlevideo.com", "android.com",
    "amazon.com", "media-amazon.com", "adobe.com", "oracle.com", "cisco.com", "zoho.com",
    "yandex.net", "yandex.com", "mail.ru", "vk.com", "userapi.com", "baidu.com", "qq.com", "weiyun.com",
    "360safe.com", "360.cn", "steamusercontent.com", "steamstatic.com", "epicgames.com", "roblox.com",
    "rbxcdn.com", "twitch.tv", "redd.it", "redditmedia.com", "pinterest.com", "whatsapp.com",
    "signal.org", "viber.com", "spotify.com", "scdn.co", "netflix.com", "paypal.com", "paypalobjects.com",
    "ebay.com", "aliexpress.com", "alicdn.com", "wikipedia.org", "wikimedia.org", "stackoverflow.com",
    "mozilla.org", "mozilla.net", "ubuntu.com", "debian.org", "python.org", "nodejs.org",
    "samsung.com", "mi.com", "xiaomi.com", "miui.com", "huawei.com", "hicloud.com",
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
    """Pure: the set of online, non-shared host names in the CSV."""
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


def hosts_from_hostfile(text):
    """Pure: host names from a hosts-format export ("127.0.0.1 name"),
    minus IP addresses and shared platforms. Used for abuse.ch ThreatFox."""
    out = set()
    for line in text.splitlines():
        parts = line.split("#", 1)[0].split()
        if len(parts) < 2 or not is_ip(parts[0]):     # "127.0.0.1 name" only: an error page is not a list
            continue
        host = parts[1].lower().rstrip(".")
        if host in ("localhost", "localhost.localdomain") or "." not in host or is_ip(host) or shared(host):
            continue
        out.add(host)
    return out


def main(args):
    """urlhaus_to_hosts.py [--hostfile] <in> [<in> ...] <out>
    Several inputs are merged; one that is missing or empty is skipped."""
    hostfile = args[:1] == ["--hostfile"]
    if hostfile:
        args = args[1:]
    if len(args) < 2:
        print(__doc__.strip().split("Usage:")[1].split("\n\n")[0].strip()); return 2
    *srcs, dst = args
    label = "threatfox" if hostfile else "urlhaus"
    hosts = set()
    for src in srcs:
        try:
            text = read_text(src)
            hosts |= hosts_from_hostfile(text) if hostfile else hosts_from_csv(text)
        except (OSError, StopIteration, zipfile.BadZipFile, zlib.error, csv.Error, ValueError):
            continue                               # one broken export must not cost the other
    with open(dst, "w", encoding="utf-8") as f:
        f.write(f"# abuse.ch {label}, converted for Noxa\n")
        for h in sorted(hosts):
            f.write(f"0.0.0.0 {h}\n")
    print(f"  {label}: {len(hosts)} " + ("malware control / delivery names" if hostfile else "online malware hosts"))
    return 0 if hosts else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
