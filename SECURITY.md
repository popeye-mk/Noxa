# Security policy

## Reporting a vulnerability

Please report security problems **privately**, not in a public issue:

- GitHub: **Security → Report a vulnerability** on this repository
  (private vulnerability reporting).

Include what you found, how to reproduce it, and the Noxa version. You will
get an answer as soon as possible, normally within a week. Please give a
reasonable time for a fix before publishing details.

## Supported versions

Only the latest release gets security fixes. Noxa tells you when a newer
version is available.

## What counts

- Anything that lets another app, a website, or someone on the same network
  switch Noxa's protection off, change its lists or settings, or read its data.
- Problems in the update, blocklist-download or home-server channels.
- A way around the parent lock other than the limits already documented
  (uninstalling the app, clearing its data, Android's own VPN settings).

A domain that is wrongly blocked or missed is not a vulnerability: open a
normal issue for that.

The last review of the code and what came out of it:
[docs/SECURITY-REVIEW.md](docs/SECURITY-REVIEW.md).
