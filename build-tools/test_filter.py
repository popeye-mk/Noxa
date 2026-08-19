import struct, hashlib, random, string

def load(path):
    with open(path,"rb") as f:
        assert f.read(4)==b"GBF1"
        k=struct.unpack("<I",f.read(4))[0]
        m=struct.unpack("<Q",f.read(8))[0]
        n=struct.unpack("<Q",f.read(8))[0]
        ba=f.read()
    return k,m,n,ba

def contains(d,k,m,ba):
    h=hashlib.sha256(d.encode()).digest()
    h1=int.from_bytes(h[0:8],"little"); h2=int.from_bytes(h[8:16],"little")
    for i in range(k):
        idx=(h1+i*h2)%m
        if not (ba[idx>>3]>>(idx&7))&1: return False
    return True

k,m,n,ba=load("out/guardian-default.gbf")
print(f"loaded: k={k} m={m} items={n}")

# 1) known blocked domains must all be found
known=[l.strip() for l in open("out/merged-domains.txt")][:5000]
hits=sum(contains(d,k,m,ba) for d in known)
print(f"known-bad found: {hits}/{len(known)}  (must be 100%)")

# 2) false positives: test random domains that are NOT in the list
known_set=set(l.strip() for l in open("out/merged-domains.txt"))
fp=0; trials=200000
for _ in range(trials):
    d="".join(random.choices(string.ascii_lowercase,k=12))+".com"
    if d in known_set: continue
    if contains(d,k,m,ba): fp+=1
print(f"false positives: {fp}/{trials}  ({fp/trials:.2e})")

# 3) common legit domains should pass through (not blocked)
legit=["google.com","wikipedia.org","github.com","gov.uk","mozilla.org","signal.org"]
for d in legit:
    print(f"  {d:16} blocked={contains(d,k,m,ba)}")
