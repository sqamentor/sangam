import sys,re
L=open(r'D:\GITHUB  Project\sangam-handoff\WAVE3-SYNC-BOARD.md',encoding='utf-8').read().split('\n')
n=int(sys.argv[1]) if len(sys.argv)>1 else 200
for i,s in enumerate(L,1):
    if i<31: continue
    print(f"{i}[{len(s)}]: {s[:n]}")
