"""Compare regenerated packs (WT) with the integration tree (IT) and the old snapshot; count pinned call activities.

Usage: python pins.py <WT_SANGAM> <IT_SANGAM> <SNAPSHOT_DIR>
"""
import os
import re
import sys

WT, IT, SNAP = sys.argv[1], sys.argv[2], sys.argv[3]
REL = os.path.join('sangam-packs', 'src', 'main', 'resources')


def norm(p):
    with open(p, 'rb') as fh:
        return fh.read().replace(b'\r\n', b'\n')


changed, pins, snap_mismatch, other_change = [], 0, [], []
pin_re = re.compile(rb'<callActivity\b[^>]*operaton:calledElementBinding="versionTag"[^>]*>')
for d, _, fs in os.walk(os.path.join(WT, REL)):
    for f in fs:
        w = os.path.join(d, f)
        rel = os.path.relpath(w, WT)
        i = os.path.join(IT, rel)
        a = norm(w)
        b = norm(i) if os.path.exists(i) else None
        if a != b:
            changed.append(rel.replace('\\', '/'))
            n = len(pin_re.findall(a))
            pins += n
            # the only difference allowed: the inserted binding attributes
            stripped = re.sub(rb' operaton:calledElementBinding="versionTag" operaton:calledElementVersionTag="[^"]+"', b'', a)
            if b is None or stripped != b:
                other_change.append(rel)
            s = os.path.join(SNAP, rel)
            if os.path.exists(s) and norm(s) != a:
                snap_mismatch.append(rel)
            elif not os.path.exists(s):
                snap_mismatch.append(rel + ' (not in snapshot)')
print(f'changed files vs IT: {len(changed)}; pinned call activities in them: {pins}')
print(f'changes other than the binding attributes: {other_change}')
print(f'differs from old snapshot: {snap_mismatch}')
