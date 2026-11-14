import sys,re
L=open(r'D:\GITHUB  Project\sangam-handoff\WAVE3-SYNC-BOARD.md',encoding='utf-8').read().split('\n')
pat=sys.argv[1]; lo=int(sys.argv[2]) if len(sys.argv)>2 else 60
for i,s in enumerate(L,1):
    if i<lo: continue
    for m in re.finditer(pat,s):
        a=max(0,m.start()-200); print(f"L{i} [{s[:60]}] ...{s[a:m.end()+200]}...")
