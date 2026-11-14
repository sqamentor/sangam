"""Mirror the integration tree's repo root into a destination folder.

usage: mirror_root.py <dest> [--with-sangam]
Skips .git, .claude, node_modules, target-*, module-root target/ (a dir next to a pom.xml); never skips
src/**/target packages. Without --with-sangam the sangam/ folder is left alone (it is synced separately).
"""
import filecmp
import os
import shutil
import sys

INT = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524"
dest = sys.argv[1]
with_sangam = '--with-sangam' in sys.argv


def skip(parent, name, top):
    if top and name in ('.git', '.claude'):
        return True
    if top and name == 'sangam' and not with_sangam:
        return True
    if name == 'node_modules' or name.startswith('target-'):
        return True
    if name == 'target' and os.path.exists(os.path.join(parent, 'pom.xml')):
        return True
    return False


copied = same = 0
for dp, dns, fns in os.walk(INT):
    top = os.path.normpath(dp) == os.path.normpath(INT)
    dns[:] = [d for d in dns if not skip(dp, d, top)]
    for f in fns:
        if top and f == '.git':
            continue
        s = os.path.join(dp, f)
        d = os.path.join(dest, os.path.relpath(s, INT))
        if os.path.exists(d) and filecmp.cmp(s, d, shallow=False):
            same += 1
            continue
        os.makedirs(os.path.dirname(d), exist_ok=True)
        shutil.copy2(s, d)
        copied += 1
print('copied', copied, 'unchanged', same, '->', dest)
