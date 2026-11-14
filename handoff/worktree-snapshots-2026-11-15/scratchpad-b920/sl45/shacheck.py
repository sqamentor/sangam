import sys, hashlib
a = open(sys.argv[1], 'rb').read(); b = open(sys.argv[2], 'rb').read()
ra, rb = hashlib.sha256(a).hexdigest()[:16], hashlib.sha256(b).hexdigest()[:16]
na = hashlib.sha256(a.replace(b'\r\n', b'\n')).hexdigest()[:16]; nb = hashlib.sha256(b.replace(b'\r\n', b'\n')).hexdigest()[:16]
status = 'RAW-IDENTICAL' if ra == rb else ('IDENTICAL-AFTER-EOL' if na == nb else 'MISMATCH')
print(f"{status:20s} tree={ra} proven={rb} normalized={na}{'' if na == nb else ' vs ' + nb}  {sys.argv[3]}")
