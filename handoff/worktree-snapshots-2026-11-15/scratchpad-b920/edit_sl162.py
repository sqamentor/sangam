import sys
root = sys.argv[1]
s = None


def rep(a, b):
    global s
    a = a.replace('\n', '\r\n'); b = b.replace('\n', '\r\n')
    assert s.count(a) == 1, (s.count(a), a[:90])
    s = s.replace(a, b)


p = root + '/sangam-persistence/tools/generate_changelogs.py'
s = open(p, 'rb').read().decode('utf-8')
rep('''    ("0038-06", "M-073 audit events by tenant and time (checkpoint window and entry count)", "AUDIT_EVENT",
     [], [("AUDIT_EVENT_TIME", False, ["tenant_id", "occurred_at"])]),
]''', '''    ("0038-06", "M-073 audit events by tenant and time (checkpoint window and entry count)", "AUDIT_EVENT",
     [], [("AUDIT_EVENT_TIME", False, ["tenant_id", "occurred_at"])]),
    # SL-162: compliance columns that sangam-compliance ComplianceSchema declares and its code reads and writes, but the
    # 0018 changesets never created, so those statements failed on every real database. Types equal ComplianceSchema;
    # ComplianceChangelogParityH2Test (sangam-server) compares every CMP_* table of the changelog with it.
    ("0041-01", "SL-162 CMP_BREACH risk assessment and law-enforcement delay (BreachNotificationPlanner)", "CMP_BREACH",
     [("risk_outcome", "{str64}", ""), ("risk_json", "{text}", ""), ("high_risk", "{small}", "DEFAULT 0 NOT NULL"),
      ("delay_until", "{ts}", ""), ("delay_ref", "{str}", "")], []),
    ("0041-02", "breach notification plan instance, claimed once by a conditional UPDATE (second-start guard, M-010c)",
     "CMP_BREACH", [("plan_process_instance_id", "{str64}", "")], []),
    ("0041-03", "SL-162 CMP_PURPOSE special condition of a legitimate use (LegitimateUseRegistry)", "CMP_PURPOSE",
     [("special_condition", "{str64}", "")], []),
    ("0041-04", "SL-162 CMP_RIGHTS_REQUEST one extension and the access exception (DataSubjectRightsService)",
     "CMP_RIGHTS_REQUEST", [("extended", "{small}", "DEFAULT 0 NOT NULL"), ("extension_reason", "{text}", ""),
                            ("access_exception", "{str64}", "")], []),
]''')
open(p, 'wb').write(s.encode('utf-8'))

p = root + '/sangam-persistence/src/main/java/org/sangam/persistence/SangamSchema.java'
s = open(p, 'rb').read().decode('utf-8')
rep('''     * M-073 alters AUDIT_CHECKPOINT.checkpoint_no + AUDIT_CHECKPOINT_NO and the AUDIT_EVENT_TIME index (0038-05..06).
     * The changelog holds {@code PRODUCT_TABLES.size() + ADDITIONAL_CHANGESETS} changesets.
     */
    public static final int ADDITIONAL_CHANGESETS = 70;''', '''     * M-073 alters AUDIT_CHECKPOINT.checkpoint_no + AUDIT_CHECKPOINT_NO and the AUDIT_EVENT_TIME index (0038-05..06);
     * plus the SL-162 compliance alters CMP_BREACH risk/delay columns, CMP_BREACH.plan_process_instance_id,
     * CMP_PURPOSE.special_condition and CMP_RIGHTS_REQUEST extension/exception columns (0041-01..04).
     * The changelog holds {@code PRODUCT_TABLES.size() + ADDITIONAL_CHANGESETS} changesets.
     */
    public static final int ADDITIONAL_CHANGESETS = 74;''')
open(p, 'wb').write(s.encode('utf-8'))

p = root + '/sangam-compliance/src/main/java/org/sangam/compliance/ComplianceSchema.java'
s = open(p, 'rb').read().decode('utf-8')
nl = '\r\n' if '\r\n' in s else '\n'
a = 'risk_outcome {str64}, risk_json {text}, high_risk {small} DEFAULT 0 NOT NULL, delay_until {ts}, delay_ref {str},'
b = a + nl + '              plan_process_instance_id {str64},'
assert s.count(a) == 1
s = s.replace(a, b)
open(p, 'wb').write(s.encode('utf-8'))
print('ok')
