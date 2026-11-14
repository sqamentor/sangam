"""AT-3 business run: the data the admission desk, the doctors and billing record (the forms' required fields), so the
admission runs to discharge. Without it the consent gateway (${!consentGiven}, default false) returns the case to
"Admit" forever. Both copies (sangam-server PlugAndPlayIT, sangam-onboarding black-box test) stay identical."""
import json, os, sys

ROOT = sys.argv[1]
BUSINESS = {
    "process": "sangam-sec-health-admission",
    # register form (start): patientName, admissionType, payerType are required
    "variables": {"patientName": "AT patient", "admissionType": "EMERGENCY", "payerType": "SELF"},
    # triage: triageLevel; admit: wardType, estimatedCost, consentGiven; care: decision careOutcome;
    # discharge summary: dischargeSummary; final bill: finalBillAmount (all required by the pack's forms)
    "taskVariables": {"triageLevel": "YELLOW", "wardType": "GENERAL", "estimatedCost": "50000",
                      "consentGiven": True, "careOutcome": "discharge",
                      "dischargeSummary": "Stable on discharge; review in 7 days", "finalBillAmount": "48000"},
    # admission to discharge ends the case; the run must end (not merely wait)
    "reachesWaitState": False,
}
for d in ('sangam-server/src/test/resources/acceptance/plugandplay', 'sangam-onboarding/src/test/resources/acceptance'):
    p = os.path.join(ROOT, d, 'AT-3.json')
    raw = open(p, encoding='utf-8', newline='').read()
    s = json.loads(raw)
    if s.get('business') == BUSINESS:
        print('already', p)
        continue
    assert s['business']['process'] == BUSINESS['process'], s['business']
    s['business'] = BUSINESS
    out = json.dumps(s, ensure_ascii=False, separators=(',', ':'))
    open(p, 'w', encoding='utf-8', newline='').write(out + ('\n' if raw.endswith('\n') else ''))
    print('ok', p)
