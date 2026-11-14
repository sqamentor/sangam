"""Post-apply verification on the integration tree."""
import difflib
import hashlib
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from filelist import CHANGED, ADDED  # noqa: E402

INT = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
WT = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-uip2\sangam"


def text(p):
    with open(p, encoding='utf-8', newline='') as fh:
        return fh.read().replace('\r\n', '\n')


def diff(a, b):
    out = [l for l in difflib.unified_diff(a.splitlines(), b.splitlines(), lineterm='', n=0)
           if l[:1] in '+-' and l[:3] not in ('+++', '---')]
    return sum(1 for l in out if l[0] == '+'), sum(1 for l in out if l[0] == '-'), out


bad = [r for r in CHANGED + ADDED
       if r != 'TESTING-GAP-REGISTER.md' and text(os.path.join(INT, r)) != text(os.path.join(WT, r))]
print('files differing from my worktree version (register excluded):', bad)

plus, minus, lines = diff(text(os.path.join(HERE, 'int-register-before.md')), text(os.path.join(INT, 'TESTING-GAP-REGISTER.md')))
print(f'register vs pre-apply: +{plus}/-{minus}')
for l in lines:
    print('   ', l[:110])

y_int = text(os.path.join(INT, 'sangam-rest', 'api', 'sangam-api.yaml'))
plus, minus, _ = diff(text(os.path.join(HERE, 'int-yaml-before.yaml')), y_int)
same = y_int == text(os.path.join(WT, 'sangam-rest', 'api', 'sangam-api.yaml'))
print(f'yaml vs pre-apply: +{plus}/-{minus}; identical to the worktree yaml: {same}')

sc = os.path.join(INT, 'sangam-server', 'src', 'main', 'java', 'org', 'sangam', 'server', 'config', 'SecurityConfig.java')
print('SecurityConfig sha256:', hashlib.sha256(open(sc, 'rb').read()).hexdigest().upper())
