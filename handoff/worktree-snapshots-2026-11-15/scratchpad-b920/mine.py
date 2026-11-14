import subprocess, re, os
ui = 'D:/GITHUB  Project/Custom WorkFlow/.claude/worktrees/workspace-scan-resume-06b524/sangam/sangam-ui'
out = subprocess.run(['node', 'scripts/check-i18n.mjs'], cwd=ui, capture_output=True, text=True, encoding='utf8')
missing = [l.strip() for l in (out.stderr + out.stdout).splitlines() if l.startswith('  ')]
mine = ['casework.tsx', 'compliance.tsx', 'fieldgeo.tsx', 'finance.tsx', 'programme.tsx', 'registry.tsx', 'config.tsx']
texts = {m: open(f'{ui}/src/modules/{m}', encoding='utf8').read() for m in mine}
# also options: opt.X appears as 'X' inside f.select lists
for k in missing:
    hit = []
    for m, t in texts.items():
        if k.startswith('opt.'):
            v = k[4:]
            if re.search(r"f\.select\([^)]*'" + re.escape(v) + "'", t):
                hit.append(m)
        elif k.startswith('fld.'):
            n = k[4:]
            if ("'" + k + "'") in t or re.search(r"f\.\w+\('" + re.escape(n) + r"'(\)|, undefined)", t) or re.search(r"\bc\('" + re.escape(n) + r"'\)", t):
                hit.append(m)
        elif ("'" + k + "'") in t:
            hit.append(m)
    if hit:
        print(k, ','.join(hit))
