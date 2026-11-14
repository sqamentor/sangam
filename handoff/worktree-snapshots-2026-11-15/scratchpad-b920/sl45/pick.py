import sys, glob, os, xml.etree.ElementTree as ET
for f in sorted(glob.glob(os.path.join(sys.argv[1], "TEST-*.xml"))):
    r = ET.parse(f).getroot()
    n = r.get("name").split(".")[-1]
    if n in ("EngineRestTenantRefusalTest", "ObservabilityMdcScopeTest", "EngineRestUiParityTest", "DecisionCitationGuardTest", "TaskFormEndpointTest", "EngineRestTenantFilterTest"):
        print(f"  {n}: tests={r.get('tests')} failures={r.get('failures')} errors={r.get('errors')}")
