"""Compare the approve-401 worktree change set against the 2aaf44f base and the integration tree (CR-insensitive)."""
import hashlib, os, subprocess, sys

WT = r"C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-auth401\sangam"
IT = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
REPO = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524"
BASES = sys.argv[1:] or ["2aaf44f", "f553b43", "HEAD"]

FILES = """
sangam-config/src/main/java/org/sangam/config/api/ConfigConnector.java
sangam-onboarding/src/test/resources/acceptance/AT-1.json
sangam-onboarding/src/test/resources/acceptance/AT-3.json
sangam-onboarding/src/test/resources/acceptance/AT-4.json
sangam-onboarding/src/test/resources/acceptance/AT-6.json
sangam-packs/src/main/java/org/sangam/packs/core/ConnectorCallDelegate.java
sangam-packs/src/main/java/org/sangam/packs/core/Vars.java
sangam-packs/src/main/java/org/sangam/packs/sim/SimulatorConnectorGateway.java
sangam-packs/src/main/resources/packs/ifms-bill/pack.json
sangam-packs/src/main/resources/packs/ifms-bill/sangam-ifms-bill.bpmn
sangam-packs/src/main/resources/packs/ifms-pao-chain/sangam-ifms-pao-chain.bpmn
sangam-packs/src/main/resources/packs/ifms-receipts/sangam-ifms-receipt.bpmn
sangam-packs/tools/packgen/gov_ifms.py
sangam-programme/src/main/java/org/sangam/programme/target/TargetService.java
sangam-server/src/main/java/org/sangam/server/config/SecurityConfig.java
sangam-server/src/test/java/org/sangam/server/acceptance/gov/E17bDomainIT.java
sangam-server/src/test/java/org/sangam/server/acceptance/plugandplay/PlugAndPlayIT.java
sangam-server/src/test/java/org/sangam/server/acceptance/resilience/T35RestoreIT.java
sangam-server/src/test/resources/acceptance/plugandplay/AT-1.json
sangam-server/src/test/resources/acceptance/plugandplay/AT-3.json
sangam-server/src/test/resources/acceptance/plugandplay/AT-4.json
sangam-server/src/test/resources/acceptance/plugandplay/AT-6.json
sangam-config/src/test/java/org/sangam/config/api/BillConnectorContractTest.java
sangam-packs/src/test/java/org/sangam/packs/ConnectorInputRenameTest.java
sangam-server/src/main/java/org/sangam/server/web/ServerWebProblemHandler.java
sangam-server/src/test/java/org/sangam/server/web/ServerWebProblemHandlerTest.java
TESTING-GAP-REGISTER.md
deploy/README.md
deploy/SUPPORT-MATRIX.md
""".split()


def norm(b):
    return None if b is None else b.replace(b"\r\n", b"\n")


def h(b):
    return "-" if b is None else hashlib.sha256(b).hexdigest()[:10]


def read(p):
    try:
        with open(p, "rb") as f:
            return f.read()
    except FileNotFoundError:
        return None


def show(rev, rel):
    r = subprocess.run(["git", "-C", REPO, "--no-optional-locks", "show", f"{rev}:sangam/{rel}"], capture_output=True)
    return r.stdout if r.returncode == 0 else None


print("file | wt | it | " + " | ".join(BASES) + " | wt==it | wt==bases | it==bases")
for rel in FILES:
    w = norm(read(os.path.join(WT, rel)))
    i = norm(read(os.path.join(IT, rel)))
    bs = [norm(show(b, rel)) for b in BASES]
    print(f"{rel} | {h(w)} | {h(i)} | " + " | ".join(h(b) for b in bs)
          + f" | {w == i} | {[w == b for b in bs]} | {[i == b for b in bs]}")
