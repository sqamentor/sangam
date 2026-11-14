"""Re-sync the approve-401 worktree (wt-auth401/sangam) from the integration tree (sangam), keeping my change set.

Build outputs are skipped on both sides: directories named target-* anywhere, and a module-root target/ (a directory
named 'target' whose parent holds a pom.xml). sangam-programme/.../programme/target is SOURCE and is synced.

My modified files are re-based: for each, the integration version must equal (ignoring CR) the base my change was
made on; then my content is written with the integration file's line-ending style. Anything else is a two-sided
change and is reported, not written. My new files must not exist in the integration tree.

Usage: python a401_sync.py <WT> <IT> <BACKUP_DIR> [--apply]
"""
import hashlib
import os
import shutil
import subprocess
import sys

WT, IT, BACKUP = sys.argv[1], sys.argv[2], sys.argv[3]
APPLY = '--apply' in sys.argv
REPO = os.path.dirname(IT)

# file -> base revision of my change (2aaf44f: the old approve-401 work; HEAD: my 2026-11-09 phase-1 edits)
MODIFIED = {
    'sangam-config/src/main/java/org/sangam/config/api/ConfigConnector.java': '2aaf44f',
    'sangam-packs/src/main/java/org/sangam/packs/core/ConnectorCallDelegate.java': '2aaf44f',
    'sangam-packs/src/main/java/org/sangam/packs/core/Vars.java': '2aaf44f',
    'sangam-packs/src/main/java/org/sangam/packs/sim/SimulatorConnectorGateway.java': '2aaf44f',
    'sangam-packs/tools/packgen/gov_ifms.py': '2aaf44f',
    'sangam-server/src/test/java/org/sangam/server/acceptance/gov/E17bDomainIT.java': '2aaf44f',
    'sangam-server/src/test/java/org/sangam/server/acceptance/plugandplay/PlugAndPlayIT.java': '2aaf44f',
    'sangam-server/src/test/java/org/sangam/server/acceptance/resilience/T35RestoreIT.java': '2aaf44f',
    'sangam-server/src/test/resources/acceptance/plugandplay/AT-1.json': '2aaf44f',
    'sangam-server/src/test/resources/acceptance/plugandplay/AT-3.json': '2aaf44f',
    'sangam-server/src/test/resources/acceptance/plugandplay/AT-4.json': '2aaf44f',
    'sangam-server/src/test/resources/acceptance/plugandplay/AT-6.json': '2aaf44f',
    'sangam-onboarding/src/test/resources/acceptance/AT-1.json': '2aaf44f',
    'sangam-onboarding/src/test/resources/acceptance/AT-3.json': '2aaf44f',
    'sangam-onboarding/src/test/resources/acceptance/AT-4.json': '2aaf44f',
    'sangam-onboarding/src/test/resources/acceptance/AT-6.json': '2aaf44f',
    'sangam-server/src/main/java/org/sangam/server/security/DevTokenFilter.java': 'HEAD',
    'sangam-security/src/main/java/org/sangam/security/oidc/TokenAuthenticationFilters.java': 'HEAD',
    'sangam-server/src/main/resources/application.yaml': 'HEAD',
    'sangam-config/src/main/java/org/sangam/config/catalogue/DocumentTypeCatalogue.java': 'HEAD',
    'sangam-server/src/main/java/org/sangam/server/bridge/PortsEventBridge.java': 'HEAD',
}
NEW = [
    'sangam-config/src/test/java/org/sangam/config/api/BillConnectorContractTest.java',
    'sangam-packs/src/test/java/org/sangam/packs/ConnectorInputRenameTest.java',
    'sangam-server/src/main/java/org/sangam/server/web/ServerWebProblemHandler.java',
    'sangam-server/src/test/java/org/sangam/server/web/ServerWebProblemHandlerTest.java',
    'sangam-server/src/test/java/org/sangam/server/web/RequestStatusContractH2Test.java',
]
if '--mirror' in sys.argv:
    # after the change set was applied to the integration tree, the worktree simply follows it (no files of mine)
    MODIFIED, NEW = {}, []
MINE = set(MODIFIED) | set(NEW)


def walk(root):
    out = {}
    for dirpath, dirnames, filenames in os.walk(root):
        keep = []
        for d in dirnames:
            if d.startswith('target-'):
                continue
            if d == 'target' and os.path.isfile(os.path.join(dirpath, 'pom.xml')):
                continue
            keep.append(d)
        dirnames[:] = keep
        for f in filenames:
            full = os.path.join(dirpath, f)
            out[os.path.relpath(full, root).replace('\\', '/')] = full
    return out


def read(p):
    with open(p, 'rb') as fh:
        return fh.read()


def sha(b):
    return hashlib.sha256(b).hexdigest()


def git_show(rev, rel):
    r = subprocess.run(['git', '-C', REPO, '--no-optional-locks', 'show', f'{rev}:sangam/{rel}'], capture_output=True)
    return r.stdout if r.returncode == 0 else None


def norm(b):
    return b.replace(b'\r\n', b'\n')


def eol_style(b):
    crlf = b.count(b'\r\n')
    lf = b.count(b'\n') - crlf
    return 'CRLF' if crlf and not lf else 'LF' if lf and not crlf else ('MIXED' if crlf and lf else 'NONE')


def with_style(lf_content, style):
    return lf_content.replace(b'\n', b'\r\n') if style == 'CRLF' else lf_content


wt, it = walk(WT), walk(IT)
problems = []

# 1. back up my change set exactly as it is now
for rel in sorted(MINE):
    src = os.path.join(WT, rel)
    if not os.path.isfile(src):
        problems.append(f'missing in WT: {rel}')
        continue
    dst = os.path.join(BACKUP, rel)
    if APPLY:
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)

# 2. my modified files: two-sided check and re-base
plan = {}
for rel, rev in sorted(MODIFIED.items()):
    mine = read(os.path.join(WT, rel))
    base = git_show(rev, rel)
    cur = read(os.path.join(IT, rel)) if rel in it else None
    if base is None or cur is None:
        problems.append(f'no base or no integration version: {rel}')
        continue
    style = eol_style(cur)
    if norm(cur) != norm(base):
        problems.append(f'TWO-SIDED: {rel} (integration changed since {rev})')
        continue
    if style == 'MIXED':
        problems.append(f'MIXED EOL in integration version: {rel}')
    plan[rel] = with_style(norm(mine), style)
    print(f'rebase {rel}: base={rev} eol={style} changed={norm(mine) != norm(cur)}')

# 3. my new files must be new on the integration side too
for rel in NEW:
    if rel in it:
        problems.append(f'NEW FILE EXISTS in integration tree: {rel}')
    else:
        print(f'new    {rel} eol={eol_style(read(os.path.join(WT, rel)))}')

# 4. everything else follows the integration tree
updated = added = same = 0
for rel, src in sorted(it.items()):
    if rel in MINE:
        continue
    b = read(src)
    if rel in wt and sha(b) == sha(read(wt[rel])):
        same += 1
        continue
    if rel in wt:
        updated += 1
    else:
        added += 1
    if APPLY:
        dst = os.path.join(WT, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)

extra = sorted(set(wt) - set(it) - MINE)
stale_sources = [r for r in extra if ('/src/' in ('/' + r) or r.endswith('.py') or r.endswith('pom.xml'))
                 and '/bin/' not in r and '/obj/' not in r and '__pycache__' not in r]
import collections
print('wt-only by top dir:', dict(collections.Counter(r.split('/')[0] + '/' + (r.split('/')[1] if '/' in r else '') for r in extra)))
print(f'same={same} updated={updated} added={added} wt-only={len(extra)} (sources among them: {len(stale_sources)})')
for r in stale_sources:
    print(f'WT-ONLY SOURCE (removed on --apply): {r}')
    if APPLY:
        os.remove(os.path.join(WT, r))

if APPLY:
    for rel, content in plan.items():
        with open(os.path.join(WT, rel), 'wb') as fh:
            fh.write(content)

print('PROBLEMS:' if problems else 'no problems')
for p in problems:
    print('  ' + p)
