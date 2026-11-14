"""The APPROVE-401 change set (relative to sangam/), shared by the EOL check and the SHA-256 identity check."""
MODIFIED = [
    'sangam-config/src/main/java/org/sangam/config/api/ConfigConnector.java',
    'sangam-config/src/main/java/org/sangam/config/catalogue/DocumentTypeCatalogue.java',
    'sangam-packs/src/main/java/org/sangam/packs/core/ConnectorCallDelegate.java',
    'sangam-packs/src/main/java/org/sangam/packs/core/Vars.java',
    'sangam-packs/src/main/java/org/sangam/packs/sim/SimulatorConnectorGateway.java',
    'sangam-packs/tools/packgen/gov_ifms.py',
    'sangam-packs/src/main/resources/packs/ifms-bill/pack.json',
    'sangam-packs/src/main/resources/packs/ifms-bill/sangam-ifms-bill.bpmn',
    'sangam-packs/src/main/resources/packs/ifms-pao-chain/sangam-ifms-pao-chain.bpmn',
    'sangam-packs/src/main/resources/packs/ifms-receipts/sangam-ifms-receipt.bpmn',
    'sangam-security/src/main/java/org/sangam/security/oidc/TokenAuthenticationFilters.java',
    'sangam-server/src/main/java/org/sangam/server/security/DevTokenFilter.java',
    'sangam-server/src/main/java/org/sangam/server/bridge/PortsEventBridge.java',
    'sangam-server/src/main/resources/application.yaml',
    'sangam-server/src/test/java/org/sangam/server/acceptance/gov/E17bDomainIT.java',
    'sangam-server/src/test/java/org/sangam/server/acceptance/plugandplay/PlugAndPlayIT.java',
    'sangam-server/src/test/java/org/sangam/server/acceptance/resilience/T35RestoreIT.java',
    'sangam-server/src/test/resources/acceptance/plugandplay/AT-1.json',
    'sangam-server/src/test/resources/acceptance/plugandplay/AT-3.json',
    'sangam-server/src/test/resources/acceptance/plugandplay/AT-4.json',
    'sangam-server/src/test/resources/acceptance/plugandplay/AT-6.json',
    'sangam-onboarding/src/test/resources/acceptance/AT-1.json',
    'sangam-onboarding/src/test/resources/acceptance/AT-3.json',
    'sangam-onboarding/src/test/resources/acceptance/AT-4.json',
    'sangam-onboarding/src/test/resources/acceptance/AT-6.json',
]
NEW = [
    'sangam-config/src/test/java/org/sangam/config/api/BillConnectorContractTest.java',
    'sangam-packs/src/test/java/org/sangam/packs/ConnectorInputRenameTest.java',
    'sangam-server/src/main/java/org/sangam/server/web/ServerWebProblemHandler.java',
    'sangam-server/src/test/java/org/sangam/server/web/ServerWebProblemHandlerTest.java',
    'sangam-server/src/test/java/org/sangam/server/web/RequestStatusContractH2Test.java',
]
DOCS = ['TESTING-GAP-REGISTER.md']
ALL = MODIFIED + NEW + DOCS
