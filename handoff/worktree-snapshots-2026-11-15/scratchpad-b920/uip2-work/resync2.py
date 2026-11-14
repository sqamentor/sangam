"""Second re-sync of wt-uip2 from the CURRENT integration tree, keeping my changes.

  save   copy my current worktree versions (filelist CHANGED + ADDED) to uip2-work/mine2
  base   snapshot the current integration versions of the same files to uip2-work/base-int2 (merge base of the apply)
  sync   mirror INT/sangam into WT/sangam (skip node_modules, target-*, module-root target/); delete WT files INT lacks
         (except filelist.ADDED and build outputs)
Then apply_hunks.py --base base-int --mine mine2 --target WT/sangam puts my changes onto the synced files.
"""
import filecmp
import hashlib
import json
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from filelist import CHANGED, ADDED  # noqa: E402

INT = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
WT = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-uip2\sangam"


def skip_dir(parent, name, for_delete=False):
    if name == 'node_modules' or name.startswith('target-') or name in ('.git', '__pycache__'):
        return True
    if name == 'target' and os.path.exists(os.path.join(parent, 'pom.xml')):
        return True
    return for_delete and name == 'dist'


def walk(root, for_delete=False):
    for dp, dns, fns in os.walk(root):
        dns[:] = [d for d in dns if not skip_dir(dp, d, for_delete)]
        for f in fns:
            yield os.path.relpath(os.path.join(dp, f), root).replace('\\', '/')


def copy_set(src_root, dst_dir, rels, manifest=None):
    for rel in rels:
        s = os.path.join(src_root, rel)
        if not os.path.exists(s):
            if manifest is not None:
                manifest[rel] = None
            continue
        d = os.path.join(dst_dir, rel)
        os.makedirs(os.path.dirname(d), exist_ok=True)
        shutil.copy2(s, d)
        if manifest is not None:
            manifest[rel] = hashlib.sha256(open(s, 'rb').read()).hexdigest()


def main(cmd):
    if cmd == 'save':
        copy_set(WT, os.path.join(HERE, 'mine2'), CHANGED + ADDED)
        print('saved', len(CHANGED + ADDED), 'files to mine2')
    elif cmd == 'base':
        m = {}
        out = os.path.join(HERE, 'base-int2')
        copy_set(INT, out, CHANGED + ADDED, m)
        json.dump(m, open(os.path.join(out, 'manifest.json'), 'w'), indent=1)
        print('base-int2:', sum(1 for v in m.values() if v), 'files,', sum(1 for v in m.values() if not v), 'absent')
    elif cmd == 'sync':
        copied = 0
        int_files = set(walk(INT))
        for rel in sorted(int_files):
            s, d = os.path.join(INT, rel), os.path.join(WT, rel)
            if not os.path.exists(d) or not filecmp.cmp(s, d, shallow=False):
                os.makedirs(os.path.dirname(d), exist_ok=True)
                shutil.copy2(s, d)
                copied += 1
        gone = [rel for rel in sorted(walk(WT, for_delete=True)) if rel not in int_files and rel not in ADDED]
        for rel in gone:
            os.remove(os.path.join(WT, rel))
        print('copied', copied, 'deleted', len(gone))
        for g in gone:
            print('  deleted', g)
    elif cmd == 'sync-others':
        # bring every file that is NOT mine up to the integration version; never touch my files
        mine = set(CHANGED) | set(ADDED)
        int_files = set(walk(INT))
        for rel in sorted(int_files - mine):
            s, d = os.path.join(INT, rel), os.path.join(WT, rel)
            if not os.path.exists(d) or not filecmp.cmp(s, d, shallow=False):
                os.makedirs(os.path.dirname(d), exist_ok=True)
                shutil.copy2(s, d)
                print('  updated', rel)
        for rel in sorted(walk(WT, for_delete=True)):
            if rel not in int_files and rel not in mine:
                os.remove(os.path.join(WT, rel))
                print('  deleted', rel)


if __name__ == '__main__':
    main(sys.argv[1])
