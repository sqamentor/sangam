import sys
p = sys.argv[1]
b = open(p, "rb").read().decode("utf-8")
anchor = '    ], ["tenant_id", "object_type", "external_id"], []),\r\n]\r\n'
assert b.count(anchor) == 1, b.count(anchor)
new = (
    '    ], ["tenant_id", "object_type", "external_id"], []),\r\n'
    '    # SL-40 tenant custom fields (sangam-rest TenantCustomFieldRegistry, JdbcTenantCustomFieldStore): one row per\r\n'
    '    # effective-dated version of a field key on an extensible object type (form:<formKey> or a registered object type).\r\n'
    '    # effective_to is exclusive and NULL while open-ended; a new version closes the previous one on its start date and\r\n'
    '    # retirement only sets effective_to, so nothing is deleted. Field key: x_[a-z][a-z0-9_]{0,62} (at most 65 chars).\r\n'
    '    ("0034-01", "tenant custom field definitions, effective-dated versions (SL-40)", "TENANT_CUSTOM_FIELD", [\r\n'
    '        ("tenant_id", "{str64}", "NOT NULL"), ("object_type", "{str}", "NOT NULL"), ("field_key", "{vc80}", "NOT NULL"),\r\n'
    '        ("version", "{int}", "NOT NULL"), ("label", "{str}", ""), ("required_flag", "{small}", "NOT NULL"),\r\n'
    '        ("read_only_flag", "{small}", "NOT NULL"), ("schema_json", "{text}", "NOT NULL"),\r\n'
    '        ("effective_from", "{date}", "NOT NULL"), ("effective_to", "{date}", ""), ("created_by", "{str}", "NOT NULL"),\r\n'
    '        ("created_at", "{ts}", "NOT NULL"), ("retired_by", "{str}", ""), ("retired_at", "{ts}", ""),\r\n'
    '    ], ["tenant_id", "object_type", "field_key", "version"], []),\r\n'
    ']\r\n'
)
b = b.replace(anchor, new)
open(p, "wb").write(b.encode("utf-8"))
print("ok")
