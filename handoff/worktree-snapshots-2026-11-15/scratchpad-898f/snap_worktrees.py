import os, sys, shutil, filecmp, json
MAIN = r"D:\GITHUB  Project\Custom WorkFlow\sangam"
S = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad"
OUT = r"D:\GITHUB  Project\Custom WorkFlow\handoff\worktree-snapshots"
WS = ['wt-auth401','wt-iso','wt-ifmswf','wt-packs4','wt-bulk2','wt-bulk','wt-fwtd','wt-comp','wt-perf','wt-uip2','wt-qdl','wt-small','wt-forms','wt-uiparity']
SKIP = {'node_modules', '.git', '__pycache__', 'dist', '.venv', 'venv'}
def skip_dir(d):
    return d in SKIP or d == 'target' or d.startswith('target-')
summary = {}
for w in WS:
    root = os.path.join(S, w, 'sangam')
    if not os.path.isdir(root):
        root = os.path.join(S, w)
    if not os.path.isdir(root):
        summary[w] = 'missing'; continue
    changed, added = [], []
    for dp, dns, fns in os.walk(root):
        rel_dir = os.path.relpath(dp, root)
        # keep the source package sangam-programme/.../programme/target
        dns[:] = [d for d in dns if not (skip_dir(d) and not (d == 'target' and 'src' + os.sep in os.path.join(rel_dir, d) + os.sep))]
        for f in fns:
            rel = os.path.normpath(os.path.join(rel_dir, f))
            src = os.path.join(root, rel); dst = os.path.join(MAIN, rel)
            if not os.path.exists(dst):
                kind = added
            elif not filecmp.cmp(src, dst, shallow=False):
                kind = changed
            else:
                continue
            kind.append(rel.replace('\\', '/'))
            t = os.path.join(OUT, w, rel)
            os.makedirs(os.path.dirname(t), exist_ok=True)
            shutil.copy2(src, t)
    summary[w] = {'changed': len(changed), 'added': len(added)}
    with open(os.path.join(OUT, w + '.files.txt'), 'w', encoding='utf-8') as fh:
        fh.write('# files that differ from the main tree (changed) or exist only here (added)\n')
        for r in changed: fh.write('M ' + r + '\n')
        for r in added: fh.write('A ' + r + '\n')
print(json.dumps(summary, indent=1))
