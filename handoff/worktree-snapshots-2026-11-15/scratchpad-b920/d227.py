import os
import sys

os.chdir(sys.argv[1])


def edit(p, pairs):
    s = open(p, encoding='utf-8').read()
    for o, n, cnt in pairs:
        assert s.count(o) == cnt, (p, o, s.count(o))
        s = s.replace(o, n)
    open(p, 'w', encoding='utf-8', newline='').write(s)


g = 'sangam-persistence/tools/generate_changelogs.py'
edit(g, [
    ('{str1k} 1000 chars, {text}', '{str1k} 1000 chars, {vc1024} 1024 chars, {text}', 1),
    ('"{vc512}": "VARCHAR(512)", "{asciistr}": "VARCHAR(255)",',
     '"{vc512}": "VARCHAR(512)", "{vc1024}": "VARCHAR(1024)", "{asciistr}": "VARCHAR(255)",', 2),
    ('"{vc512}": "VARCHAR2(512 CHAR)",', '"{vc512}": "VARCHAR2(512 CHAR)", "{vc1024}": "VARCHAR2(1024 CHAR)",', 1),
    ('"{vc512}": "NVARCHAR(512)",', '"{vc512}": "NVARCHAR(512)", "{vc1024}": "NVARCHAR(1024)",', 1),
    ('"{vc55}": "VARCHAR(55)", "{vc512}": "VARCHAR(512)",\n',
     '"{vc55}": "VARCHAR(55)", "{vc512}": "VARCHAR(512)", "{vc1024}": "VARCHAR(1024)",\n', 1),
    ('"{vc512}": 1024, "{asciistr}"', '"{vc512}": 1024, "{vc1024}": 2048, "{asciistr}"', 1),
    ('''    ], ["tenant_id", "source_system", "definition_id"], []),
]
''', '''    ], ["tenant_id", "source_system", "definition_id"], []),
    # Decision 227 (W4-H REQUEST 2026-11-13T02:15:49Z): one row per tenant and DSC certificate, exactly as
    # sangam-integration db/sangam-dsc-enrolment-reference.sql (SHA-256 97A820C5...F61B); read and written by
    # JdbcDscCertificateEnrolments with conditional UPDATEs. certificate_sha256 is lower-case hex of the DER.
    ("0045-08", "DSC certificate enrolment (decision 227, JdbcDscCertificateEnrolments)", "INTEGRATION_DSC_CERTIFICATE", [
        ("tenant_id", "{str64}", "NOT NULL"), ("certificate_sha256", "{str64}", "NOT NULL"),
        ("enrolment_id", "{str64}", "NOT NULL"), ("principal_id", "{str}", "NOT NULL"),
        ("issuer_dn", "{vc1024}", "NOT NULL"), ("serial_hex", "{str64}", "NOT NULL"),
        ("subject_dn", "{vc1024}", "NOT NULL"), ("not_before", "{ts}", "NOT NULL"), ("not_after", "{ts}", "NOT NULL"),
        ("certificate", "{blob}", "NOT NULL"), ("status", "{vc16}", "NOT NULL"), ("enrolled_at", "{ts}", "NOT NULL"),
        ("decided_by", "{str}", ""), ("decided_at", "{ts}", ""), ("decision_reason", "{vc512}", ""),
    ], ["tenant_id", "certificate_sha256"], [("INTEGRATION_DSC_CERTIFICATE_ID", True, ["tenant_id", "enrolment_id"]),
                                             ("INTEGRATION_DSC_CERTIFICATE_PRINCIPAL", False, ["tenant_id", "principal_id"]),
                                             ("INTEGRATION_DSC_CERTIFICATE_STATUS", False, ["tenant_id", "status"])]),
]
''', 1),
])
print('ok')
