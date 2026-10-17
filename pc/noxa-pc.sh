#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# noxa-pc — a Linux PC reports to your home server the way Noxa does
# (docs/HOME-SERVER.md), and gets the basics right while it's at it.
#
#   sudo bash noxa-pc.sh setup [--name "Office PC"] [--address 192.168.1.20:8790] [--key home-...]
#       1. finds the home server on the Wi-Fi (or uses --address) and joins
#          while the server's door is open (or uses --key)
#       2. DNS → Quad9 with DNS over TLS (malware and phishing names refused)
#       3. firewall on (ufw, nothing allowed in; SSH kept if it runs)
#       4. automatic security updates on (unattended-upgrades)
#       5. an hourly report (systemd timer): updates pending, reboot needed,
#          firewall, auto-updates, DNS — no files, no history, no browsing
#   bash noxa-pc.sh report      one report now
#   sudo bash noxa-pc.sh remove   the timer and the key go; the hardening stays
#
# Needs: Ubuntu/Debian-like Linux with systemd, python3, apt. Nothing else.
# ---------------------------------------------------------------------------
set -euo pipefail

CONF=/etc/noxa-pc
LIB=/usr/local/lib/noxa-pc
VERSION="pc-1.0"
DISCOVERY_PORT=8791

say() { printf '%s\n' "$*"; }
need_root() { [ "$(id -u)" = 0 ] || { say "Run this with sudo."; exit 1; }; }

device_id() {
  # a stable id for this PC, never its real machine-id
  printf '%s' "noxa-pc:$(cat /etc/machine-id 2>/dev/null || hostname)" | sha256sum | cut -c1-32
}

# ---------------------------------------------------------------- the report
report() {
  [ -r "$CONF/key" ] && [ -r "$CONF/address" ] || { say "Not set up yet: sudo bash noxa-pc.sh setup"; exit 1; }
  local name model pending security reboot firewall auto dns quad9 up os
  name=$(cat "$CONF/name" 2>/dev/null || hostname)
  model=$(cat /sys/devices/virtual/dmi/id/product_name 2>/dev/null || echo "Linux PC")
  os=$(. /etc/os-release 2>/dev/null && printf '%s' "${PRETTY_NAME:-Linux}")
  pending=$(apt list --upgradable 2>/dev/null | grep -c '/' || true)
  security=$(apt list --upgradable 2>/dev/null | grep -ci 'security' || true)
  reboot=$([ -f /run/reboot-required ] && echo true || echo false)
  firewall=$([ "$(systemctl is-active ufw 2>/dev/null)" = active ] && echo true || echo false)
  auto=$(systemctl is-enabled unattended-upgrades 2>/dev/null | grep -q -E 'enabled|static' && echo true || echo false)
  dns=$(resolvectl status 2>/dev/null | grep -m1 -E 'Current DNS Server|DNS Servers' | sed 's/.*: *//' || true)
  quad9=$(printf '%s' "$dns" | grep -q -E '9\.9\.9\.9|quad9' && echo true || echo false)
  up=$(awk '{printf "%d", $1/86400}' /proc/uptime)
  python3 - "$(cat "$CONF/address")" "$(cat "$CONF/key")" "$(device_id)" "$name" "$model" "$os" "$pending" "$security" \
    "$reboot" "$firewall" "$auto" "$quad9" "$up" "$VERSION" <<'PY'
import json, socket, sys, time
addr, key, did, name, model, os_name, pending, security, reboot, firewall, auto, quad9, up, version = sys.argv[1:]
host, _, port = addr.partition(":")
port = int(port or 8790)
b = lambda s: s == "true"
body = json.dumps({
    "format": "noxa-home-report", "version": 1, "sent_at": int(time.time()),
    "device": {"id": did, "name": name[:40], "model": model[:40], "android": "", "noxa": version},
    "protection": {"on": b(firewall) and b(auto), "always_on": None, "blocklist_built": "", "stops_today": 0},
    "blocked_today": 0, "blocked_days": [], "since_last": {"total": 0, "categories": {}, "top_companies": []},
    "apps_30d": {"dangerous_site": [], "stalkerware": []},
    "pc": {"os": os_name[:60], "updates_pending": int(pending), "security_pending": int(security),
           "reboot_required": b(reboot), "firewall": b(firewall), "auto_updates": b(auto), "quad9": b(quad9),
           "uptime_days": int(up)},
}).encode()
req = (f"POST /api/home/report HTTP/1.1\r\nHost: {host}:{port}\r\nUser-Agent: noxa-pc\r\nContent-Type: application/json\r\n"
       f"X-Home-Key: {key}\r\nContent-Length: {len(body)}\r\nConnection: close\r\n\r\n").encode() + body
s = socket.create_connection((host, port), timeout=8)
s.sendall(req); data = b""
while len(data) < 65536:
    chunk = s.recv(4096)
    if not chunk: break
    data += chunk
s.close()
status = data.split(b" ", 2)[1].decode() if data.startswith(b"HTTP/") else "?"
print("home server answered", status, "—", "ok" if status == "200" else data.split(b"\r\n\r\n", 1)[-1][:120].decode(errors="replace"))
PY
}

# ---------------------------------------------------------------- joining
discover_and_join() {
  local name="$1" address="$2" key="$3"
  python3 - "$name" "$address" "$key" "$(device_id)" "$DISCOVERY_PORT" <<'PY'
import json, re, socket, sys
name, address, key, did, dport = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], int(sys.argv[5])
private = lambda ip: re.match(r"^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)", ip) is not None
if not address:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1); s.settimeout(3)
    s.sendto(b"NOXA-HOME?", ("255.255.255.255", dport))
    try:
        while True:
            data, (ip, _) = s.recvfrom(256)
            text = data.decode("ascii", "replace").strip()
            if text.startswith("NOXA-HOME ") and private(ip):
                address = text[len("NOXA-HOME "):].strip(); break
    except socket.timeout:
        pass
    s.close()
if not address:
    print("NO_SERVER"); sys.exit(0)
host, _, port = address.partition(":"); port = int(port or 8790)
if not private(host):
    print("NOT_HOME"); sys.exit(0)
if not key:
    body = json.dumps({"format": "noxa-home-join", "device": {"id": did, "name": name[:40], "model": "Linux PC"}}).encode()
    req = (f"POST /api/home/join HTTP/1.1\r\nHost: {host}:{port}\r\nContent-Type: application/json\r\n"
           f"Content-Length: {len(body)}\r\nConnection: close\r\n\r\n").encode() + body
    try:
        s = socket.create_connection((host, port), timeout=8); s.sendall(req); data = b""
        while True:
            c = s.recv(4096)
            if not c: break
            data += c
        s.close()
    except OSError as e:
        print("NO_ANSWER " + address); sys.exit(0)
    status = data.split(b" ", 2)[1].decode() if data.startswith(b"HTTP/") else "?"
    try: answer = json.loads(data.split(b"\r\n\r\n", 1)[-1])
    except Exception: answer = {}
    if status != "200":
        print("REFUSED " + answer.get("message", status)); sys.exit(0)
    key = answer.get("key", "")
print("OK", address, key)
PY
}

setup() {
  need_root
  local name="" address="" key=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --name) name="$2"; shift 2 ;;
      --address) address="$2"; shift 2 ;;
      --key) key="$2"; shift 2 ;;
      *) shift ;;
    esac
  done
  [ -n "$name" ] || name=$(hostname)
  mkdir -p "$CONF" "$LIB"; chmod 700 "$CONF"
  say "==> [1/5] the home server"
  local result
  result=$(discover_and_join "$name" "$address" "$key")
  case "$result" in
    OK*)  address=$(printf '%s' "$result" | awk '{print $2}'); key=$(printf '%s' "$result" | awk '{print $3}')
          printf '%s' "$address" > "$CONF/address"; printf '%s' "$key" > "$CONF/key"; chmod 600 "$CONF/key"
          printf '%s' "$name" > "$CONF/name"
          say "    joined $address as \"$name\"" ;;
    NO_SERVER) say "    no home server answered on this network. Is its door open? Or give --address and --key."; exit 1 ;;
    NOT_HOME)  say "    that address is not a home address."; exit 1 ;;
    NO_ANSWER*) say "    no answer from ${result#NO_ANSWER }."; exit 1 ;;
    REFUSED*)  say "    ${result#REFUSED }"; exit 1 ;;
  esac

  say "==> [2/5] DNS → Quad9 with DNS over TLS"
  if systemctl is-active --quiet systemd-resolved; then
    mkdir -p /etc/systemd/resolved.conf.d
    cat > /etc/systemd/resolved.conf.d/quad9.conf <<'EOF'
[Resolve]
DNS=9.9.9.9#dns.quad9.net 149.112.112.112#dns.quad9.net 2620:fe::fe#dns.quad9.net
DNSOverTLS=yes
EOF
    systemctl restart systemd-resolved; say "    done (resolvectl status shows dns.quad9.net)"
  else
    say "    skipped: systemd-resolved is not running here; set Quad9 in your network settings by hand"
  fi

  say "==> [3/5] firewall on (ufw)"
  if command -v ufw >/dev/null; then
    systemctl is-active --quiet ssh 2>/dev/null && ufw allow OpenSSH >/dev/null && say "    SSH kept open (it runs here)"
    ufw default deny incoming >/dev/null; ufw default allow outgoing >/dev/null; ufw --force enable >/dev/null
    say "    done"
  else
    say "    ufw is not installed (apt install ufw), skipped"
  fi

  say "==> [4/5] automatic security updates"
  command -v unattended-upgrade >/dev/null || DEBIAN_FRONTEND=noninteractive apt-get install -y -q unattended-upgrades >/dev/null 2>&1 || true
  printf 'APT::Periodic::Update-Package-Lists "1";\nAPT::Periodic::Unattended-Upgrade "1";\n' > /etc/apt/apt.conf.d/20auto-upgrades
  systemctl enable --now unattended-upgrades >/dev/null 2>&1 && say "    done" || say "    could not enable unattended-upgrades"

  say "==> [5/5] the hourly report"
  cp "$0" "$LIB/noxa-pc.sh"; chmod 755 "$LIB/noxa-pc.sh"
  cat > /etc/systemd/system/noxa-pc.service <<EOF
[Unit]
Description=noxa-pc: report this PC's state to the home server
After=network-online.target
[Service]
Type=oneshot
ExecStart=/usr/bin/bash $LIB/noxa-pc.sh report
EOF
  cat > /etc/systemd/system/noxa-pc.timer <<'EOF'
[Unit]
Description=noxa-pc: every hour
[Timer]
OnBootSec=3min
OnUnitActiveSec=1h
Persistent=true
[Install]
WantedBy=timers.target
EOF
  systemctl daemon-reload; systemctl enable --now noxa-pc.timer >/dev/null
  say "    timer on; first report now:"
  bash "$LIB/noxa-pc.sh" report || true
  say "Done. This PC reports to the home server once an hour."
}

remove() {
  need_root
  systemctl disable --now noxa-pc.timer >/dev/null 2>&1 || true
  rm -f /etc/systemd/system/noxa-pc.timer /etc/systemd/system/noxa-pc.service; systemctl daemon-reload
  rm -rf "$CONF" "$LIB"
  say "Removed the report and the key. Quad9, the firewall and automatic updates stay as they are."
}

case "${1:-}" in
  setup) shift; setup "$@" ;;
  report) report ;;
  remove) remove ;;
  *) sed -n '2,18p' "$0" ;;
esac
