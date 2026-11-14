import sys
for p in sys.argv[1:]:
    b=open(p,'rb').read()
    t=b.replace(b'\r\n',b'\n').replace(b'\n',b'\r\n')
    if t!=b: open(p,'wb').write(t)
