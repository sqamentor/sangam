import hashlib,sys,os
n=int(sys.argv[1]); root=sys.argv[2]
for p in sys.argv[3:]:
    fp=os.path.join(root,p)
    print(p, hashlib.sha256(open(fp,'rb').read()).hexdigest().upper()[:n] if os.path.exists(fp) else 'NEW')
