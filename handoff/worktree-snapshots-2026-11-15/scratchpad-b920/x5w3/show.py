import sys
L=open(r'D:\GITHUB  Project\sangam-handoff\WAVE3-SYNC-BOARD.md',encoding='utf-8').read().split('\n')
for n in sys.argv[1].split(','):
    print(f"--- L{n}: {L[int(n)-1]}\n")
