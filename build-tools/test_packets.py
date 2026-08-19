#!/usr/bin/env python3
"""
Reference cross-check for app/src/main/java/com/guardian/app/DnsPacket.kt.

This mirrors the Kotlin packet algorithms in Python and asserts the properties
that byte-level DNS code must satisfy. It does NOT run the Kotlin itself (that
still needs device validation, Step 8), but it validates the ALGORITHM:

  * checksums are self-verifying: re-summing a region that includes a correct
    ones-complement checksum yields 0xFFFF. That catches checksum bugs
    independently of how the packet was built.
  * offsets / record layout are asserted against hand-built known packets.

Run: python3 test_packets.py
"""

TYPE_A, TYPE_AAAA, TYPE_TXT = 1, 28, 16


def u16(b, o): return (b[o] << 8) | b[o + 1]


def ones_complement(data):
    s = 0
    for i in range(0, len(data) - 1, 2):
        s += (data[i] << 8) | data[i + 1]
    if len(data) & 1:
        s += data[-1] << 8
    while s >> 16:
        s = (s & 0xFFFF) + (s >> 16)
    return (~s) & 0xFFFF


def verify_ones_complement(data):
    """A region containing a correct checksum sums to 0xFFFF."""
    s = 0
    for i in range(0, len(data) - 1, 2):
        s += (data[i] << 8) | data[i + 1]
    if len(data) & 1:
        s += data[-1] << 8
    while s >> 16:
        s = (s & 0xFFFF) + (s >> 16)
    return s & 0xFFFF


def qname(domain):
    out = bytearray()
    for label in domain.split('.'):
        out.append(len(label))
        out += label.encode('ascii')
    out.append(0)
    return bytes(out)


# ---- build a synthetic query (what an app would send) -----------------------
def ipv4_query(domain, qtype, src=(192, 168, 1, 2), dst=(10, 111, 0, 1),
               sport=40000, dport=53, dns_id=0x1234):
    dns = bytearray()
    dns += bytes([dns_id >> 8, dns_id & 0xFF, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0])
    dns += qname(domain)
    dns += bytes([qtype >> 8, qtype & 0xFF, 0, 1])          # QTYPE + QCLASS(IN)
    udp_len = 8 + len(dns)
    udp = bytearray([sport >> 8, sport & 0xFF, dport >> 8, dport & 0xFF,
                     udp_len >> 8, udp_len & 0xFF, 0, 0]) + dns
    total = 20 + len(udp)
    ip = bytearray([0x45, 0, total >> 8, total & 0xFF, 0, 0, 0, 0, 64, 17, 0, 0,
                    *src, *dst])
    ck = ones_complement(ip)
    ip[10], ip[11] = ck >> 8, ck & 0xFF
    return bytes(ip + udp)


def ipv6_query(domain, qtype, sport=40000, dport=53, dns_id=0x2222):
    src = bytes([0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2])
    dst = bytes([0xfd, 0x67, 0x75, 0x61, 0x72, 0x64, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1])
    dns = bytearray()
    dns += bytes([dns_id >> 8, dns_id & 0xFF, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0])
    dns += qname(domain)
    dns += bytes([qtype >> 8, qtype & 0xFF, 0, 1])
    udp_len = 8 + len(dns)
    udp = bytearray([sport >> 8, sport & 0xFF, dport >> 8, dport & 0xFF,
                     udp_len >> 8, udp_len & 0xFF, 0, 0]) + dns
    ip6 = bytearray([0x60, 0, 0, 0, udp_len >> 8, udp_len & 0xFF, 17, 64]) + src + dst
    return bytes(ip6 + udp)


# ---- port of DnsPacket.parseQuery ------------------------------------------
def parse_query(p):
    n = len(p)
    if n < 12:
        return None
    ver = (p[0] >> 4) & 0xF
    if ver == 4:
        if n < 28:
            return None
        l3 = (p[0] & 0xF) * 4
        proto = p[9]
    elif ver == 6:
        if n < 48:
            return None
        l3, proto = 40, p[6]
    else:
        return None
    if proto != 17:
        return None
    udp = l3
    if u16(p, udp + 2) != 53:
        return None
    dns = udp + 8
    pos = dns + 12
    labels = []
    while pos < n:
        ln = p[pos]
        if ln == 0:
            pos += 1
            break
        if ln & 0xC0:
            return None
        pos += 1
        labels.append(p[pos:pos + ln].decode('ascii'))
        pos += ln
    qtype = u16(p, pos)
    return {"domain": ".".join(labels).lower(), "qtype": qtype, "ver": ver,
            "l3": l3, "udp": udp, "dns": dns, "qend": pos + 4}


# ---- port of DnsPacket answer/response builders ----------------------------
def dns_answer(qtype):
    if qtype == TYPE_A:
        return bytes([0xC0, 0x0C, 0, TYPE_A, 0, 1, 0, 0, 0, 0x3C, 0, 4, 0, 0, 0, 0])
    if qtype == TYPE_AAAA:
        return bytes([0xC0, 0x0C, 0, TYPE_AAAA, 0, 1, 0, 0, 0, 0x3C, 0, 0x10]) + bytes(16)
    return None


def build_dns_message(p, q, fhi, flo, answer):
    question = p[q["dns"]:q["qend"]]
    an = 1 if answer else 0
    out = bytearray([question[0], question[1], fhi, flo, 0, 1, 0, an, 0, 0, 0, 0])
    out += question[12:]
    if answer:
        out += answer
    return bytes(out)


def wrap_ipv4(orig, ihl, udp_start, dns):
    total = ihl + 8 + len(dns)
    out = bytearray(total)
    out[:ihl] = orig[:ihl]
    out[2], out[3] = total >> 8, total & 0xFF
    out[8] = 64
    for i in range(4):
        out[12 + i], out[16 + i] = orig[16 + i], orig[12 + i]
    out[10] = out[11] = 0
    ck = ones_complement(out[:ihl])
    out[10], out[11] = ck >> 8, ck & 0xFF
    sp, dp = u16(orig, udp_start), u16(orig, udp_start + 2)
    ul = 8 + len(dns)
    out[ihl], out[ihl + 1] = dp >> 8, dp & 0xFF
    out[ihl + 2], out[ihl + 3] = sp >> 8, sp & 0xFF
    out[ihl + 4], out[ihl + 5] = ul >> 8, ul & 0xFF
    out[ihl + 6] = out[ihl + 7] = 0
    out[ihl + 8:] = dns
    return bytes(out)


def wrap_ipv6(orig, dns):
    ul = 8 + len(dns)
    out = bytearray(40 + ul)
    out[:40] = orig[:40]
    out[4], out[5] = ul >> 8, ul & 0xFF
    out[6], out[7] = 17, 64
    for i in range(16):
        out[8 + i], out[24 + i] = orig[24 + i], orig[8 + i]
    sp, dp = u16(orig, 40), u16(orig, 42)
    out[40], out[41] = dp >> 8, dp & 0xFF
    out[42], out[43] = sp >> 8, sp & 0xFF
    out[44], out[45] = ul >> 8, ul & 0xFF
    out[46] = out[47] = 0
    out[48:] = dns
    pseudo = bytearray(40 + ul)
    pseudo[:32] = out[8:40]
    pseudo[34], pseudo[35] = ul >> 8, ul & 0xFF
    pseudo[39] = 17
    pseudo[40:] = out[40:]
    ck = ones_complement(pseudo)
    if ck == 0:
        ck = 0xFFFF
    out[46], out[47] = ck >> 8, ck & 0xFF
    return bytes(out)


def wrap_response(orig, q, dns):
    return wrap_ipv6(orig, dns) if q["ver"] == 6 else wrap_ipv4(orig, q["l3"], q["udp"], dns)


def build_sinkhole(p, q):
    return wrap_response(p, q, build_dns_message(p, q, 0x81, 0x80, dns_answer(q["qtype"])))


def build_nxdomain(p, q):
    return wrap_response(p, q, build_dns_message(p, q, 0x81, 0x83, None))


# ---- tests ------------------------------------------------------------------
# ---- port of DnsPacket CNAME-uncloaking parser ------------------------------
def read_name(p, start, ln):
    sb = []; pos = start; g = 0
    while 0 <= pos < ln and g < 128:
        g += 1
        b = p[pos]
        if b == 0: break
        if b & 0xC0 == 0xC0:
            if pos + 1 >= ln: break
            pos = ((b & 0x3F) << 8) | p[pos + 1]; continue
        pos += 1
        if pos + b > ln: break
        sb.append(p[pos:pos + b].decode()); pos += b
    return ".".join(sb).lower()


def skip_name(p, start, ln):
    pos = start; g = 0
    while 0 <= pos < ln and g < 128:
        g += 1
        b = p[pos]
        if b == 0: return pos + 1
        if b & 0xC0 == 0xC0: return pos + 2
        pos += 1 + b
    return pos


def cname_targets(p):
    ln = len(p)
    if ln < 12: return []
    out = []
    qd = u16(p, 4); an = u16(p, 6)
    pos = 12
    for _ in range(qd):
        pos = skip_name(p, pos, ln) + 4
        if pos > ln: return out
    for _ in range(an):
        pos = skip_name(p, pos, ln)
        if pos + 10 > ln: break
        typ = u16(p, pos); rdlen = u16(p, pos + 8); rd = pos + 10
        if typ == 5 and rd + rdlen <= ln:
            out.append(read_name(p, rd, ln))
        pos = rd + rdlen
        if pos > ln: break
    return out


def dns_response_with_cname():
    # metrics.site.com  CNAME-> tracker.evilcorp.com  A-> 1.2.3.4
    header = bytes([0x12, 0x34, 0x81, 0x80, 0, 1, 0, 2, 0, 0, 0, 0])
    question = qname("metrics.site.com") + bytes([0, 1, 0, 1])       # A, IN
    cname_rdata = qname("tracker.evilcorp.com")
    ans1 = bytes([0xC0, 0x0C, 0, 5, 0, 1, 0, 0, 0, 60,               # name=ptr, CNAME
                  (len(cname_rdata) >> 8) & 0xFF, len(cname_rdata) & 0xFF]) + cname_rdata
    ans2 = qname("tracker.evilcorp.com") + bytes([0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 1, 2, 3, 4])
    return header + question + ans1 + ans2


def check(name, cond):
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}")
    assert cond, name


def main():
    print("IPv4 A-record sinkhole (doubleclick.net):")
    q = ipv4_query("doubleclick.net", TYPE_A)
    pq = parse_query(q)
    check("parsed domain", pq["domain"] == "doubleclick.net")
    check("parsed qtype A", pq["qtype"] == TYPE_A)
    r = build_sinkhole(q, pq)
    ihl = (r[0] & 0xF) * 4
    check("IP checksum valid", verify_ones_complement(r[:ihl]) == 0xFFFF)
    check("ports swapped (src now 53)", u16(r, ihl) == 53)
    check("src/dst IP swapped", r[12:16] == q[16:20] and r[16:20] == q[12:16])
    check("ANCOUNT == 1", u16(r, ihl + 8 + 6) == 1)
    check("answer is 0.0.0.0", r[-4:] == bytes(4))
    check("NOERROR rcode", (r[ihl + 8 + 3] & 0x0F) == 0)

    print("IPv4 AAAA sinkhole -> :: :")
    q = ipv4_query("ads.example.com", TYPE_AAAA)
    pq = parse_query(q)
    r = build_sinkhole(q, pq)
    ihl = (r[0] & 0xF) * 4
    check("IP checksum valid", verify_ones_complement(r[:ihl]) == 0xFFFF)
    check("answer type AAAA", u16(r, ihl + 8 + 12 + (pq["qend"] - pq["dns"] - 12) + 2) == TYPE_AAAA)
    check("answer is :: (16 zero bytes)", r[-16:] == bytes(16))

    print("Other qtype (TXT) -> NODATA:")
    q = ipv4_query("tracker.example", TYPE_TXT)
    pq = parse_query(q)
    r = build_sinkhole(q, pq)
    ihl = (r[0] & 0xF) * 4
    check("ANCOUNT == 0 (NODATA)", u16(r, ihl + 8 + 6) == 0)
    check("NOERROR rcode", (r[ihl + 8 + 3] & 0x0F) == 0)

    print("DoH canary -> NXDOMAIN:")
    q = ipv4_query("use-application-dns.net", TYPE_A)
    pq = parse_query(q)
    r = build_nxdomain(q, pq)
    ihl = (r[0] & 0xF) * 4
    check("IP checksum valid", verify_ones_complement(r[:ihl]) == 0xFFFF)
    check("RCODE == 3 (NXDOMAIN)", (r[ihl + 8 + 3] & 0x0F) == 3)

    print("IPv6 AAAA sinkhole (mandatory UDP checksum):")
    q = ipv6_query("doubleclick.net", TYPE_AAAA)
    pq = parse_query(q)
    check("parsed over IPv6", pq is not None and pq["ver"] == 6)
    r = build_sinkhole(q, pq)
    ul = u16(r, 4)
    # UDP checksum verifies over pseudo-header + UDP segment
    pseudo = bytearray(40 + ul)
    pseudo[:32] = r[8:40]
    pseudo[34], pseudo[35] = ul >> 8, ul & 0xFF
    pseudo[39] = 17
    pseudo[40:] = r[40:]
    check("IPv6 UDP checksum valid", verify_ones_complement(pseudo) == 0xFFFF)
    check("addresses swapped", r[8:24] == q[24:40] and r[24:40] == q[8:24])
    check("answer is :: (16 zero bytes)", r[-16:] == bytes(16))

    print("TCP-DNS detection:")
    # craft a minimal IPv4 TCP:53 packet
    tcp = bytearray([0x45, 0, 0, 40, 0, 0, 0, 0, 64, 6, 0, 0,
                     192, 168, 1, 2, 10, 111, 0, 1,
                     0x9C, 0x40, 0, 53, 0, 0, 0, 0, 0, 0, 0, 0, 0x50, 2, 0, 0, 0, 0, 0, 0])
    # (parse_query only handles UDP, so a TCP packet must return None)
    check("TCP:53 not parsed as UDP query", parse_query(bytes(tcp)) is None)

    print("CNAME-uncloaking parser:")
    msg = dns_response_with_cname()
    targets = cname_targets(msg)
    check("extracted exactly one CNAME", len(targets) == 1)
    check("CNAME target decoded correctly", targets == ["tracker.evilcorp.com"])
    check("compressed answer-name skipped correctly", "metrics.site.com" not in targets)

    print("\nAll packet-algorithm checks passed.")


if __name__ == "__main__":
    main()
