import zipfile,hashlib,sys
a=zipfile.ZipFile(sys.argv[1]); b=zipfile.ZipFile(sys.argv[2])
def m(z): return {n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.startswith('org/xmlpull/v1/') and not n.endswith('/')}
ma,mb=m(a),m(b)
print('xpp3_min org/xmlpull/v1 entries',len(ma),'xmlpull entries',len(mb))
print('only in xpp3_min',sorted(set(ma)-set(mb)))
print('only in xmlpull',sorted(set(mb)-set(ma)))
for n in sorted(set(ma)&set(mb)): print('SAME' if ma[n]==mb[n] else 'DIFF', n, ma[n][:16], mb[n][:16])
print('xpp3_min other entries',sorted(n for n in a.namelist() if not n.startswith('org/xmlpull/v1/'))[:40])
print('xmlpull other entries',sorted(n for n in b.namelist() if not n.startswith('org/xmlpull/v1/'))[:40])
