"""Re-sync wt-uip2/sangam from the integration tree, keeping the UI-PARITY-2 changes.

Phase 'backup': copy my files (wt-uip2 versions) to uip2-work/mine/<rel>.
Phase 'sync':   mirror INT/sangam into WT/sangam (skip node_modules, target-*, module-root target/),
                delete WT files that INT does not have (same skips, plus dist/ and my new files).
Phase 'merge':  put my changes on top of the synced INT versions (CRLF like INT); the register and the
                OpenAPI yaml are handled separately (script edit / generator).
"""
import filecmp
import os
import shutil
import subprocess
import sys

INT_ROOT = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524"
INT = os.path.join(INT_ROOT, 'sangam')
WT = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-uip2\sangam"
WORK = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\uip2-work"
MINE = os.path.join(WORK, 'mine')
BASE_REV = '2aaf44f'

CHANGED = """
TESTING-GAP-REGISTER.md
sangam-flowable-ports/src/main/java/org/sangam/ports/history/HistoryQueryService.java
sangam-rest/api/gen_openapi.py
sangam-rest/api/sangam-api.yaml
sangam-rest/src/main/java/org/sangam/rest/adapter/operaton/OperatonHistoryAdapter.java
sangam-rest/src/main/java/org/sangam/rest/adapter/operaton/OperatonInstanceControlAdapter.java
sangam-rest/src/main/java/org/sangam/rest/adapter/operaton/OperatonProcessAdapter.java
sangam-rest/src/main/java/org/sangam/rest/adapter/operaton/OperatonTaskAdapter.java
sangam-rest/src/main/java/org/sangam/rest/config/SangamRestAutoConfiguration.java
sangam-rest/src/main/java/org/sangam/rest/port/HistoryPort.java
sangam-rest/src/main/java/org/sangam/rest/port/ProcessPort.java
sangam-rest/src/main/java/org/sangam/rest/port/TaskPort.java
sangam-rest/src/main/java/org/sangam/rest/web/HistoryAuditController.java
sangam-rest/src/main/java/org/sangam/rest/web/InstanceControlController.java
sangam-rest/src/main/java/org/sangam/rest/web/ProcessController.java
sangam-rest/src/main/java/org/sangam/rest/web/TaskController.java
sangam-server/src/main/java/org/sangam/server/bridge/PortsHistoryBridge.java
sangam-server/src/main/java/org/sangam/server/config/PortsWiring.java
sangam-ui/scripts/i18n-dynamic.json
sangam-ui/src/styles.css
sangam-ui/src/api/adapters.ts
sangam-ui/src/api/endpoints.ts
sangam-ui/src/api/types.ts
sangam-ui/src/areas/ops/InstanceDetail.tsx
sangam-ui/src/areas/tasklist/taskForm.test.ts
sangam-ui/src/areas/tasklist/TaskInbox.tsx
sangam-ui/src/i18n/locales/en.json
sangam-ui/src/i18n/locales/hi.json
sangam-ui/src/modules/engine.tsx
sangam-ui/src/modules/operaton-parity.test.tsx
sangam-ui/src/modules/operaton.tsx
""".split()

ADDED = """
sangam-server/src/test/java/org/sangam/server/ui/SangamUiParityApiTest.java
sangam-ui/src/components/BpmnDiagram.test.tsx
sangam-ui/src/modules/ui-parity-2.test.tsx
sangam-ui/src/plugins/host.test.tsx
""".split()

# handled outside the plain "take my version" merge
SPECIAL = {'TESTING-GAP-REGISTER.md', 'sangam-rest/api/sangam-api.yaml'}


def skip_dir(parent, name, for_delete=False):
    if name == 'node_modules' or name.startswith('target-') or name in ('.git', '__pycache__'):
        return True
    if name == 'target' and os.path.exists(os.path.join(parent, 'pom.xml')):
        return True   # module-root build output only; src/**/target packages are source
    if for_delete and name == 'dist':
        return True
    return False


def walk(root, for_delete=False):
    for dp, dns, fns in os.walk(root):
        dns[:] = [d for d in dns if not skip_dir(dp, d, for_delete)]
        for f in fns:
            yield os.path.relpath(os.path.join(dp, f), root).replace('\\', '/')


def read_text(p):
    with open(p, encoding='utf-8', newline='') as fh:
        return fh.read()


def write_text(p, s):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'w', encoding='utf-8', newline='') as fh:
        fh.write(s)


def norm(s):
    return s.replace('\r\n', '\n')


def to_eol(s, crlf):
    s = norm(s)
    return s.replace('\n', '\r\n') if crlf else s


def git_show(rel):
    r = subprocess.run(['git', '-C', INT_ROOT, 'show', f'{BASE_REV}:sangam/{rel}'], capture_output=True)
    return None if r.returncode else r.stdout.decode('utf-8')


def backup():
    for rel in CHANGED + ADDED:
        src = os.path.join(WT, rel)
        dst = os.path.join(MINE, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)
    print('backed up', len(CHANGED) + len(ADDED), 'files to', MINE)


def sync():
    copied = deleted = 0
    int_files = set(walk(INT))
    for rel in sorted(int_files):
        s = os.path.join(INT, rel)
        d = os.path.join(WT, rel)
        if not os.path.exists(d) or not filecmp.cmp(s, d, shallow=False):
            os.makedirs(os.path.dirname(d), exist_ok=True)
            shutil.copy2(s, d)
            copied += 1
    gone = []
    for rel in sorted(walk(WT, for_delete=True)):
        if rel not in int_files and rel not in ADDED:
            os.remove(os.path.join(WT, rel))
            gone.append(rel)
            deleted += 1
    print('copied', copied, 'deleted', deleted)
    for g in gone:
        print('  deleted', g)


def merge():
    for rel in CHANGED + ADDED:
        if rel in SPECIAL:
            continue
        mine = read_text(os.path.join(MINE, rel))
        target = os.path.join(WT, rel)
        if rel in ADDED:
            write_text(target, to_eol(mine, True))
            print('added   ', rel)
            continue
        cur = read_text(target)
        base = git_show(rel)
        if base is None or norm(base) != norm(cur):
            sys.exit('integration version differs from the base, merge by hand: ' + rel)
        write_text(target, to_eol(mine, '\r\n' in cur))
        print('merged  ', rel)


if __name__ == '__main__':
    {'backup': backup, 'sync': sync, 'merge': merge}[sys.argv[1]]()
