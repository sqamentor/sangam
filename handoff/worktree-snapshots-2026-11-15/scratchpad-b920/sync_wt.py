"""Re-sync the private worktree (wt-ifmswf/sangam) from the integration tree (sangam), keeping my files.

Build outputs are skipped on both sides: directories named target-* anywhere, a module-root target/ (a directory
named 'target' whose parent holds a pom.xml), and node_modules. sangam-programme/.../programme/target is SOURCE and is
copied like any other source directory.

Usage: python sync_wt.py <WT> <IT> <BACKUP_DIR> [--dry-run]
"""
import hashlib
import os
import shutil
import sys

WT, IT, BACKUP = sys.argv[1], sys.argv[2], sys.argv[3]
DRY = '--dry-run' in sys.argv

MINE = {
    'sangam-packs/tools/packgen/packs.py',
    'sangam-flowable-ports/src/main/java/org/sangam/ports/bpmn/SangamModelValidator.java',
    'sangam-flowable-ports/src/test/java/org/sangam/ports/bpmn/SangamModelValidatorTest.java',
    'sangam-packs/src/test/java/org/sangam/packs/CrossPackCallBindingTest.java',
    'sangam-server/src/test/java/org/sangam/server/acceptance/ifms/IfmsPacksIT.java',
}


def walk(root):
    out = {}
    for dirpath, dirnames, filenames in os.walk(root):
        keep = []
        for d in dirnames:
            if d.startswith('target-'):
                continue
            if d == 'target' and os.path.isfile(os.path.join(dirpath, 'pom.xml')):
                continue
            if d == 'node_modules':
                continue
            keep.append(d)
        dirnames[:] = keep
        for f in filenames:
            full = os.path.join(dirpath, f)
            out[os.path.relpath(full, root).replace('\\', '/')] = full
    return out


def sha(p):
    with open(p, 'rb') as fh:
        return hashlib.sha256(fh.read()).hexdigest()


# 1. back up my files
for rel in sorted(MINE):
    src = os.path.join(WT, rel)
    if not os.path.isfile(src):
        sys.exit(f'missing my file in WT: {rel}')
    dst = os.path.join(BACKUP, rel)
    if not DRY:
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)
    print(f'backup {rel}')

wt, it = walk(WT), walk(IT)
copied = added = same = 0
for rel, src in sorted(it.items()):
    if rel in MINE:
        continue
    dst = os.path.join(WT, rel)
    if rel in wt and sha(src) == sha(wt[rel]):
        same += 1
        continue
    if rel in wt:
        copied += 1
        print(f'update {rel}')
    else:
        added += 1
        print(f'add    {rel}')
    if not DRY:
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)

extra = sorted(set(wt) - set(it) - MINE)
print(f'same={same} updated={copied} added={added} wt-only (left as is)={len(extra)}')
for rel in extra:
    if not (rel.startswith('sangam-ui/dist/') or rel.startswith('sdks/python/.pytest_cache/')
            or '/bin/Debug/' in rel or '/obj/' in rel):
        print(f'WT-ONLY SOURCE? {rel}')
