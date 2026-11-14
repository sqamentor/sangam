import re,sys,io
sys.stdout=io.TextIOWrapper(sys.stdout.buffer,encoding='utf-8')
L=open(sys.argv[1],encoding='utf-8').read().split('\n')
for i,line in enumerate(L[9:166],start=10):
    c=[x.strip() for x in line.strip().split('|')[1:-1]]
    st=' | '.join(c[4:]) if len(c)>=5 else '??'+line[:100]
    s=st if len(st)<=420 else st[:170]+' ..... '+st[-250:]
    print(f"L{i} {c[0]} [{c[1][:12]}] {c[2][:70]} => {s}")
