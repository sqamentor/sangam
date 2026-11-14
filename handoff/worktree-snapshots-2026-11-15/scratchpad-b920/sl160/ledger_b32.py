import os, re, sys
# Static replica of sangam-engine EnginePatchLedgerGuardTest (no test run).
root = sys.argv[1]
IMPL = re.compile(r'\borg\.operaton\.bpm\.engine\.impl\.')
SESS = re.compile(r'(?i)\bcustomSessionFactories\b|CustomSessionFactories\(')
FJF = re.compile(r'implements\s+(?:[\w.]+\.)?FailedJobCommandFactory\b|setFailedJobCommandFactory\s*\(')
TM = re.compile(r'extends\s+(?:org\.operaton\.bpm\.engine\.impl\.persistence\.entity\.)?TaskManager\b')
ROW = re.compile(r'^\|\s*`([^`]+)`\s*\|')
ledger = open(os.path.join(root, 'sangam-engine/PATCHES.md'), encoding='utf-8').read()

def allow(heading):
    out, inside = set(), False
    for line in re.split(r'\r?\n', ledger):
        if line.startswith('#'):
            inside = line.startswith(heading)
            continue
        if inside:
            m = ROW.search(line)
            if m and m.group(1) != 'impl':
                out.add(m.group(1))
    return out

impl, sess, fjf, tms, scanned = set(), set(), set(), [], 0
for dp, dn, fn in os.walk(root):
    rel_dir = os.path.relpath(dp, root)
    dn[:] = [d for d in dn if not (d.startswith('target') or d in ('node_modules', '.git'))]
    for f in fn:
        p = os.path.join(dp, f)
        r = os.path.relpath(p, root).replace(chr(92), '/')
        if not (r.endswith('.java') and '/src/main/' in r):
            continue
        scanned += 1
        t = open(p, encoding='utf-8').read()
        if IMPL.search(t): impl.add(r)
        if SESS.search(t): sess.add(r)
        if FJF.search(t): fjf.add(r)
        tms += [r for _ in TM.finditer(t)]
listed = allow('### Allow-list A:') | allow('### Allow-list B:')
print('scanned', scanned)
print('A/B unlisted', sorted(impl - listed))
print('A/B stale', sorted(listed - impl))
print('C diff', sorted(sess ^ allow('### Allow-list C:')))
print('D diff', sorted(fjf ^ allow('### Allow-list D:')))
print('TaskManager count', len(tms))
