import io, json
H = r"D:\GITHUB  Project\sangam-handoff"
p = H + r"\BACKLOG-0.1.0-MASTER.md"
s = io.open(p, encoding="utf-8", newline="").read()
nl = "\r\n" if "\r\n" in s else "\n"
lines = s.split(nl)
rows = [
"| M-141a | W3-H 2026-11-12T12:37:03Z; decisions 72-75 | Security jar split: the 6 Spring files of sangam-security/oidc (ClientJwtVerifier, IdentityResolutionFilter, SangamAuthenticationToken, SangamResourceServerConfiguration, SecurityWebSupport, TokenAuthenticationFilters) and the Spring Security compile dependencies move to a host module (sangam-security-spring or sangam-server); sangam-security keeps SangamSecurity, the engine plugin and plain-Java SAML logic. DoD: 0 Spring imports and no Spring compile dependency in sangam-security main, its 6 ratchet entries removed, ServerBeanSetEqualityTest unchanged, security switch-off still refused (decision 75) | M-139 | M-139 | sangam-security, new host module | HIGH: auth path; bean-set equality | M | no | 4 |",
"| M-141b | W3-H 2026-11-12T12:37:03Z | EngineAssembly in a plain-Java sangam-assembly module: settings record, engine plugin order of EngineConfigurationCustomizer and the module-port capability set as host-neutral code; EngineWiring and EngineConfigurationCustomizer call it; P-1 transaction sharing stays in the Spring host. DoD: same plugin list and order asserted by a test, a missing REQUIRED capability refuses start, 0 Spring imports in sangam-assembly (guard) | M-137 | M-141a | new sangam-assembly, sangam-server config | HIGH: engine construction | M | no | 4 |",
"| M-141c | W3-H 2026-11-12T12:37:03Z | The 4 sangam-rest non-host files naming Spring (TxSupport, ReadTransactions, OperatonOpsAdapter, JdbcAuditExportAdapter) move behind core Transactions or into the REST host area; ratchet 19 to 15 | M-139 | M-141b | sangam-rest | MEDIUM | S | no | 4, else 0.2 |",
"| M-141d | W3-H 2026-11-12T12:37:03Z | The registry (7), field (1) and geo (1) controllers move into the REST host area or a module host package; ratchet to 6 or less | M-139 | M-141c | sangam-registry, sangam-field, sangam-geo | LOW | S | no | 4, else 0.2 |",
]
if not any(l.startswith("| M-141a |") for l in lines):
    i = next(k for k, l in enumerate(lines) if l.startswith("| M-141 |"))
    lines[i + 1:i + 1] = rows
    io.open(p, "w", encoding="utf-8", newline="").write(nl.join(lines))
sp = H + r"\backlog-status.json"
j = json.load(open(sp, encoding="utf-8"))
for k in ("M-141a", "M-141b", "M-141c", "M-141d"):
    j.setdefault(k, {"status": "NOT_STARTED", "note": "W3-H sub-item, Wave 4"})
j["M-145"].update(status="EVIDENCE", note="W3-X1 MAVEN-INVENTORY.csv (685 components: 595/52/38/0) + W3-X2 NON-MAVEN-INVENTORY.csv (487 rows: 349/79/58/1 Grafana) in sangam-handoff/compliance; decision register synthesis pending W3-X3")
open(sp, "w", encoding="utf-8", newline="\n").write(json.dumps(j, ensure_ascii=False, indent=2) + "\n")
print("ok")
