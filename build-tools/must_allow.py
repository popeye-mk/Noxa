"""Sites Noxa must NEVER block — one list shared by the build and the safety check.

build_blocklist.py keeps every one of these off the list even when a source
names it (MetaMask's list named sites.google.com, then storage.googleapis.com,
in Nov 2026). verify_build.py still refuses any build that blocks one of them
some other way, e.g. through a whole parent domain.
"""

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
