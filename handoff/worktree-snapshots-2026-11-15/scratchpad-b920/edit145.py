import sys, os
D = sys.argv[1]
GLOBAL = [('deployAll(null)', 'deployAll(PackTestTenant.ID)'),
          ('processDefinitionWithoutTenantId()', 'processDefinitionTenantId(PackTestTenant.ID)')]
FILES = ['AcceptanceSteps', 'AcceptanceStepsTest', 'AllPacksSmokeTest', 'BankPaymentConcurrenceTest',
         'BatchFanOutDirectiveTest', 'ChangeAdoptionTest', 'CheckFailClosedTest', 'FundsCheckFailClosedTest',
         'IntlPfmCasePacksTest', 'JointApprovalSeatsTest', 'P3DuplicateSeatTest', 'PackAcceptanceTest',
         'PackBusinessCalendarTest', 'PackDeploymentTest', 'PackDriverWorkersTest', 'PayerApiPacksTest',
         'PilotScenarioTest', 'ScenarioDriverPropertiesTest', 'SectorConformanceTest', 'UnknownOutcomeLookupTest']
SPECIFIC = {
    'AcceptanceStepsTest': [('engine.getRepositoryService().createDeployment().addModelInstance(',
                             'engine.getRepositoryService().createDeployment().tenantId(PackTestTenant.ID).addModelInstance(')],
    'ChangeAdoptionTest': [('engine.getRepositoryService().createDeployment().name("pack:" + PAY + "@1.1.0")',
                            'engine.getRepositoryService().createDeployment().tenantId(PackTestTenant.ID)\n'
                            '                .name("pack:" + PAY + "@1.1.0")')],
    'BatchFanOutDirectiveTest': [('engine.getRepositoryService().createDeployment()\n',
                                  'engine.getRepositoryService().createDeployment().tenantId(PackTestTenant.ID)\n')],
    'PackDriverWorkersTest': [('engine.getRepositoryService().createDeployment()\n',
                               'engine.getRepositoryService().createDeployment().tenantId(PackTestTenant.ID)\n')],
    'ScenarioDriverPropertiesTest': [('engine.getRepositoryService().createDeployment().addModelInstance(key',
                                      'engine.getRepositoryService().createDeployment().tenantId(PackTestTenant.ID)'
                                      '.addModelInstance(key')],
    'PilotScenarioTest': [('/** Starts the shared (tenant-less) definition; tenant deployments of the same key exist in this engine. */',
                           '/** Starts the definition of the test tenant; another tenant\'s deployments of the same key exist in this engine. */')],
}
total = 0
for name in FILES:
    p = os.path.join(D, name + '.java')
    with open(p, encoding='utf-8', newline='') as fh:
        s = fh.read()
    crlf = '\r\n' in s
    if crlf:
        s = s.replace('\r\n', '\n')
    n = 0
    for old, new in GLOBAL:
        n += s.count(old)
        s = s.replace(old, new)
    for old, new in SPECIFIC.get(name, []):
        c = s.count(old)
        if c != 1:
            sys.exit(f'{name}: expected 1 match, found {c}: {old[:70]!r}')
        s = s.replace(old, new)
        n += 1
    if crlf:
        s = s.replace('\n', '\r\n')
    with open(p, 'w', encoding='utf-8', newline='') as fh:
        fh.write(s)
    total += n
    print(name, n)
print('total', total)
