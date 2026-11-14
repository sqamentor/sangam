"""Compare each wt-uip2 file with the 2aaf44f version (B) and with the integration tree (I)."""
import difflib, os, subprocess, sys

INT = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524"
WT = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-uip2\sangam"
BASE = sys.argv[1] if len(sys.argv) > 1 else '2aaf44f'
LIST = os.path.join(INT, 'handoff', 'worktree-snapshots', 'wt-uip2.files.txt')


def git_show(rev, rel):
    r = subprocess.run(['git', '-C', INT, 'show', f'{rev}:sangam/{rel}'], capture_output=True)
    if r.returncode != 0:
        return None
    return r.stdout.decode('utf-8')


def read(p):
    if not os.path.exists(p):
        return None
    with open(p, encoding='utf-8', newline='') as fh:
        return fh.read()


def stat(a, b):
    if a is None or b is None:
        return 'n/a'
    if a == b:
        return 'same'
    al = a.replace('\r\n', '\n').splitlines()
    bl = b.replace('\r\n', '\n').splitlines()
    plus = minus = 0
    for l in difflib.unified_diff(al, bl, lineterm='', n=0):
        if l.startswith('+++') or l.startswith('---'):
            continue
        if l.startswith('+'):
            plus += 1
        elif l.startswith('-'):
            minus += 1
    eol = ''
    if ('\r\n' in a) != ('\r\n' in b):
        eol = ' EOL-DIFF'
    return f'+{plus}/-{minus}{eol}'


for line in open(LIST, encoding='utf-8'):
    if line.startswith('#') or not line.strip():
        continue
    kind, rel = line.strip().split(' ', 1)
    W = read(os.path.join(WT, rel))
    B = git_show(BASE, rel)
    I = read(os.path.join(INT, 'sangam', rel))
    print(f'{kind} {rel}\n    B->W {stat(B, W)}   B->I {stat(B, I)}   I==W {I == W}')
