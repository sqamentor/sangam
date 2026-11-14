import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
start = s.index('    // ---- SAP OData')
end = s.index('    // ---- shared')
new = open(sys.argv[2], encoding='utf-8').read()
s = s[:start] + new + s[end:]
old_doc = """ * SAP and ServiceNow connectors over public HTTP interfaces. No vendor library is bundled. SAP RFC needs the customer's own
 * restricted client library and is not implemented here."""
assert old_doc in s
s = s.replace(old_doc, """ * SAP and ServiceNow connectors over public HTTP interfaces. No vendor library is bundled. SAP RFC, which needs the
 * customer's own SAP JCo, is {@link SapRfc} (loaded reflectively by {@link JcoRfcClient}).""")
open(p, 'w', encoding='utf-8').write(s)
