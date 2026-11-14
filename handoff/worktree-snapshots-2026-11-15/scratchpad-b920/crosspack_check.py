"""Same logic as CrossPackCallBindingTest, for a given packs resource root: count cross-pack calls and unpinned ones.

Usage: python crosspack_check.py <.../src/main/resources/packs>
"""
import os
import re
import sys

root = sys.argv[1]
PROCESS = re.compile(r'<process\b[^>]*\bid="([^"]+)"[^>]*operaton:versionTag="([^"]+)"')
CALL = re.compile(r'<callActivity\b[^>]*>')
CALLED = re.compile(r'\bcalledElement="([^"]+)"')
files = []
for d, _, fs in os.walk(root):
    for f in fs:
        if f.endswith('.bpmn'):
            files.append(os.path.join(d, f))
pack_of, version_of = {}, {}
for f in files:
    txt = open(f, encoding='utf-8').read()
    for m in PROCESS.finditer(txt):
        pack_of[m.group(1)] = os.path.relpath(f, root).split(os.sep)[0]
        version_of[m.group(1)] = m.group(2)
cross, problems = 0, 0
for f in files:
    pack = os.path.relpath(f, root).split(os.sep)[0]
    for c in CALL.finditer(open(f, encoding='utf-8').read()):
        m = CALLED.search(c.group())
        if not m or m.group(1) not in pack_of or pack_of[m.group(1)] == pack:
            continue
        cross += 1
        want = f'operaton:calledElementBinding="versionTag" operaton:calledElementVersionTag="{version_of[m.group(1)]}"'
        if want not in c.group():
            problems += 1
print(f'cross-pack calls={cross} unpinned={problems} -> test would {"FAIL" if problems or cross == 0 else "PASS"}')
