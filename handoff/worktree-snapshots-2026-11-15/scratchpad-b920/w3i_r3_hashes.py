import hashlib, os
root = 'D:/GITHUB  Project/Custom WorkFlow/.claude/worktrees/workspace-scan-resume-06b524/'
files = [
    'sangam/sangam-rest/src/main/java/org/sangam/rest/adapter/operaton/EngineAdapterWorkAdapter.java',
    'sangam/sangam-engine/src/main/java/org/sangam/engine/lease/SangamLeaseTable.java',
    'sangam/sdks/testing/work-step-problems.json',
    'sangam/sdks/typescript/src/testing.ts',
    'sangam/sdks/typescript/test/work-step-problems.test.ts',
    'sangam/sdks/python/src/sangam_sdk/testing.py',
    'sangam/sdks/python/tests/test_work_step_problems.py',
    'sangam/sdks/php/src/Testing/SangamTestEngine.php',
    'sangam/sdks/php/tests/WorkStepProblemsVectorTest.php',
    'sangam/sdks/java/src/test/java/org/sangam/sdk/testing/WorkStepProblemsVectorTest.java',
    'sangam/sdks/dotnet/tests/Sangam.Sdk.Tests/Testing/WorkStepProblemsVectorTests.cs',
    'sangam/sangam-server/src/test/java/org/sangam/server/restadapter/WorkCallProblemsVectorH2Test.java',
    'sangam/sangam-server/src/test/java/org/sangam/server/restadapter/WorkStepJournalH2Test.java',
    'sangam/sangam-server/src/test/java/org/sangam/server/restadapter/OutcomeUnknownNoReofferH2Test.java',
    '.github/workflows/sdks.yml',
    'sangam/sdks/python-frameworks/fastapi/README.md',
]
out = []
for f in files:
    h = hashlib.sha256(open(root + f, 'rb').read()).hexdigest().upper()
    out.append(f'{f} {h}')
print('; '.join(out))
