import struct, hashlib, random, string
# Load filter
with open("out/guardian-default.gbf","rb") as f:
    assert f.read(4)==b"GBF1"
    k=struct.unpack("<I",f.read(4))[0]; m=struct.unpack("<Q",f.read(8))[0]
    n=struct.unpack("<Q",f.read(8))[0]; ba=f.read()

def contains_builder(d):   # original algorithm (arbitrary precision)
    h=hashlib.sha256(d.encode()).digest()
    h1=int.from_bytes(h[0:8],"little"); h2=int.from_bytes(h[8:16],"little")
    for i in range(k):
        idx=(h1+i*h2)%m
        if not (ba[idx>>3]>>(idx&7))&1: return False
    return True

def contains_kotlin(d):    # replicate the Kotlin approach exactly
    h=hashlib.sha256(d.encode()).digest()
    h1m=int.from_bytes(h[0:8],"little")%m   # remainderUnsigned then mod
    h2m=int.from_bytes(h[8:16],"little")%m
    idx=h1m
    for i in range(k):
        if not (ba[idx>>3]>>(idx&7))&1: return False
        idx+=h2m
        if idx>=m: idx-=m
    return True

# Compare on a big mixed sample
mismatch=0; tested=0
sample=[l.strip() for l in open("out/merged-domains.txt")][:20000]
sample+=["google.com","github.com","signal.org","wikipedia.org"]
sample+=["".join(random.choices(string.ascii_lowercase,k=10))+".net" for _ in range(20000)]
for d in sample:
    tested+=1
    if contains_builder(d)!=contains_kotlin(d): mismatch+=1
print(f"tested={tested}  mismatches={mismatch}  -> {'MATCH' if mismatch==0 else 'BUG'}")
