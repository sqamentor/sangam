import os

ROOT = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam\sangam-ui"


def edit(rel, pairs):
    p = os.path.join(ROOT, rel)
    b = open(p, 'rb').read().decode('utf-8')
    nl = '\r\n' if '\r\n' in b else '\n'
    for old, new in pairs:
        o = old.replace('\n', nl)
        n = new.replace('\n', nl)
        c = b.count(o)
        if c != 1:
            raise SystemExit(f"{rel}: expected 1 match, found {c}: {old[:80]!r}")
        b = b.replace(o, n)
    open(p, 'wb').write(b.encode('utf-8'))
    print('edited', rel)
