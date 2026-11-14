"""Unified diff between git rev:sangam/<rel> (or integration tree when rev == 'I') and wt-uip2/<rel>."""
import difflib, os, subprocess, sys

INT = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524"
WT = os.environ.get('WT', r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-uip2\sangam")
rev = sys.argv[1]
ctx = int(os.environ.get('CTX', '2'))
for rel in sys.argv[2:]:
    if rev == 'I':
        with open(os.path.join(INT, 'sangam', rel), encoding='utf-8', newline='') as fh:
            a = fh.read()
    else:
        a = subprocess.run(['git', '-C', INT, 'show', f'{rev}:sangam/{rel}'], capture_output=True).stdout.decode('utf-8')
    with open(os.path.join(WT, rel), encoding='utf-8', newline='') as fh:
        b = fh.read()
    al = a.replace('\r\n', '\n').splitlines()
    bl = b.replace('\r\n', '\n').splitlines()
    print('#' * 20, rel)
    for l in difflib.unified_diff(al, bl, f'{rev}', 'wt', lineterm='', n=ctx):
        print(l)
