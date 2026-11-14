import sys, io, hashlib, datetime, os
TREE = 'D:/GITHUB  Project/Custom WorkFlow/.claude/worktrees/workspace-scan-resume-06b524/'
HO = 'D:/GITHUB  Project/sangam-handoff/'
FILES = [
    'SANGAM.md',
    'sangam/pom.xml',
    'sangam/sangam-field/src/main/java/org/sangam/field/template/ChecklistValidator.java',
    'sangam/sangam-field/src/main/java/org/sangam/field/scoring/ScoringService.java',
    'sangam/sangam-field/src/test/java/org/sangam/field/template/ChecklistValidatorNumberTest.java',
    'sangam/sangam-field/src/test/java/org/sangam/field/scoring/ScoringServiceNumberTest.java',
    'sangam/sangam-server/pom.xml',
    'sangam/sangam-rest/pom.xml',
    'sangam/sangam-server/src/main/java/org/sangam/server/config/WorkflowConnectorsWiring.java',
    'sangam/sangam-server/src/test/java/org/sangam/server/config/WorkflowConnectorsWiringTest.java',
    'sangam/sangam-rest/src/main/java/org/sangam/rest/port/EventPort.java',
    'sangam/sangam-rest/src/main/java/org/sangam/rest/web/EventFormController.java',
    'sangam/sangam-server/src/main/java/org/sangam/server/bridge/PortsEventBridge.java',
    'sangam/sangam-server/src/main/java/org/sangam/server/config/PortsWiring.java',
    'sangam/sangam-server/src/test/java/org/sangam/server/bridge/PortsEventBridgeEventGridTest.java',
    'sangam/sangam-server/src/main/java/org/sangam/server/config/SecurityConfig.java',
    'sangam/sangam-rest/src/main/java/org/sangam/rest/port/TaskPort.java',
    'sangam/sangam-rest/src/test/java/org/sangam/rest/port/TaskAmountTest.java',
    'sangam/sangam-server/src/test/java/org/sangam/server/restadapter/TaskIndexSortH2Test.java',
    'sangam/sangam-rest/src/main/java/org/sangam/rest/modules/connectorshealth/HealthHooksFallbackConfiguration.java',
    'sangam/sangam-rest/src/main/java/org/sangam/rest/modules/connectorshealth/HealthHooksFallbackController.java',
    'sangam/sangam-rest/src/test/java/org/sangam/rest/modules/connectorshealth/HealthHooksFallbackControllerTest.java',
    'sangam/sangam-rest/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports',
    'sangam/sangam-server/src/main/resources/application.yaml',
    'sangam/sangam-server/src/main/java/org/sangam/server/config/HealthConnectorsWiring.java',
    'sangam/sangam-server/src/test/java/org/sangam/server/config/CdsServicesSecurityH2Test.java',
    'sangam/deploy/SUPPORT-MATRIX.md',
    'sangam/sangam-rest/src/main/java/org/sangam/rest/adapter/operaton/DmnTesting.java',
    'sangam/sangam-rest/src/main/java/org/sangam/rest/modules/ops/OpsRestConfiguration.java',
    'sangam/TESTING-GAP-REGISTER.md',
    'sangam/sangam-server/src/main/java/org/sangam/server/config/AiWiring.java',
    'sangam/sangam-server/src/test/java/org/sangam/server/config/AiVectorIndexSelectionTest.java',
    'sangam/sangam-ledger/README.md',
    'sangam/sangam-config/README.md',
    'sangam/sangam-server/src/main/java/org/sangam/server/bridge/DscPurgeScheduler.java',
    'sangam/sangam-server/src/main/java/org/sangam/server/config/DscPurgeWiring.java',
    'sangam/sangam-server/src/test/java/org/sangam/server/bridge/DscPurgeSchedulerTest.java',
    'sangam/sangam-server/src/test/java/org/sangam/server/config/DscPurgeWiringTest.java',
    'sangam/deploy/licence/release-checks.json',
]

def now():
    return datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')

def sha(p):
    full = TREE + p
    if not os.path.exists(full):
        return 'absent'
    return hashlib.sha256(open(full, 'rb').read()).hexdigest().upper()

def append(name, line):
    path = HO + name
    data = io.open(path, encoding='utf-8', newline='').read()
    nl = '\r\n' if '\r\n' in data else '\n'
    if not data.endswith(nl):
        data += nl
    data += line + nl
    io.open(path, 'w', encoding='utf-8', newline='').write(data)

if __name__ == '__main__':
    cmd = sys.argv[1]
    if cmd == 'hashes':
        for f in FILES:
            print(sha(f), f)
    elif cmd == 'now':
        print(now())
    elif cmd == 'board':
        # argv: TYPE text targets
        t, text, targets = sys.argv[2], sys.argv[3], sys.argv[4]
        assert '|' not in text and '|' not in targets
        line = f'{now()} | integrator/INT-H2 | {t} | {text} | {targets}'
        append('WAVE4-SYNC-BOARD.md', line)
        print(line)
    elif cmd == 'claim':
        text = sys.argv[2]
        line = f'{now()} INT-H2 {text}'
        append('FILE-CLAIMS.md', line)
        print(line)
