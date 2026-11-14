import sys, zipfile, collections
for jar in sys.argv[1:]:
    z = zipfile.ZipFile(jar)
    hits = collections.OrderedDict()
    for n in z.namelist():
        if n.endswith(".class"):
            b = z.read(n)
            if b"org/springframework" in b:
                refs = sorted(set(x.split(b";")[0].split(b"\x01")[0][:80].decode("latin1") for x in b.split(b"Lorg/springframework")[1:] + b.split(b"\x01\x00")[0:0]))
                hits[n] = refs
    print(jar.split("\\")[-1], "classes referencing org/springframework:", len(hits))
    for k in hits: print("  ", k)
