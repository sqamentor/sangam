import re
L=open(r'D:\GITHUB  Project\sangam-handoff\WAVE3-SYNC-BOARD.md',encoding='utf-8').read().split('\n')
for i,s in enumerate(L,1):
    if i<60: continue
    p=[x.strip() for x in s.split('|')]
    if len(p)<4 or p[2] not in ('DONE','CHANGE+DONE'): continue
    full=len(re.findall(r'(?<![0-9A-Fa-f])[0-9A-Fa-f]{64}(?![0-9A-Fa-f])',s))
    short=len(re.findall(r'(?<![0-9A-Fa-f])[0-9A-F]{8,16}(?![0-9A-Fa-f])',s))
    if full==0:
        ref='refFC' if re.search(r'(?i)FILE-CLAIMS|RELEASE|final report|hashes in',s) else ''
        print(f"L{i} {p[1]} {p[2]} full=0 short={short} {ref} :: {p[3][:150]}")
