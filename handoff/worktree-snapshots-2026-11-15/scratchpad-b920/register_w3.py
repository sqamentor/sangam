import io
p = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam\TESTING-GAP-REGISTER.md"
s = io.open(p, encoding="utf-8", newline="").read()
nl = "\r\n" if "\r\n" in s else "\n"
lines = s.split(nl)
rows = [
"| SL-170 | LOW scope (W3-A M-064, integrator ruling 2026-11-12) | Azure Event Grid is outbound only in 0.1.0: the inbound pull delivery API could not be verified offline, and push delivery would need a new webhook authentication path. Service Bus CloudEvents binary mode also stays refused because the REST encoding of custom properties is unverified. | Verify the Event Grid pull API and the Service Bus property encoding against the live services before enabling either; until then both are refused with a clear error, never silently degraded. | OPEN (deferred past 0.1.0) |",
"| SL-171 | MEDIUM deploy (W3-K M-061, W3-M Helm) | The script-worker container needs SYS_ADMIN and private cgroupns (decision 78). Whether Kubernetes also needs procMount Unmasked or systempaths unconfined is not known offline; the seccomp policy does not yet filter clone(CLONE_NEWUSER) by argument (SW-1), and the kafel and nsjail syntax is unconfirmed (SW-2). | Run ScriptSandboxEscapeIT in the built image (decision 79, test phase), then settle the Helm securityContext; the OPEN POINT comment in deploy/helm/sangam/values.yaml marks the place. | OPEN (test phase) |",
"| SL-172 | LOW determinism debt (W3-F C3-05) | LockScope.DEPTH stays a ThreadLocal in sangam-core: it marks the thread that holds the L2 lock, delegates cannot carry a flag through their fixed signatures, and a nested REQUIRES_NEW command hides the outer command context. The determinism ratchet DETERMINISM_ALLOWED is 1 for it. | Keep it in the ratchet; revisit when the assembly layer (M-141) gives commands an explicit context. Enforcement of rule 5 itself is done (decision 86, HttpTransport.execute). | OPEN (tracked debt) |",
"| SL-173 | LOW scope (owner decision 96) | The Django, Laravel and Symfony framework adapters of M-027 are deferred past 0.1.0 (no pip or Composer downloads); the NestJS adapter is written without the @nestjs packages and is checked only in the test phase. | Build the three adapters in a later release; verify NestJS in the test phase. | OPEN (deferred) |",
"| SL-174 | MEDIUM correctness (W3-I finding) | The TypeScript and Python SDK test engines answer 409 NO_LEASE for completed work, while the server answers 404, so a worker tested against the fake behaves differently against the server. | Make the test engines return exactly the server status and problem code; add a contract test that compares them. | OPEN (W3-I) |",
"| SL-175 | LOW security (W3-D finding) | sangam-security's own filter path (PrincipalResolver.syncIdp) would mirror API-key scopes as IdP memberships. The server does not use that path today. | Remove or guard the mirroring so an API key can never create an IdP membership; add a regression test. | OPEN |",
]
i = next(k for k, l in enumerate(lines) if l.startswith("| SL-169 |"))
if not any(l.startswith("| SL-170 |") for l in lines):
    for r in rows:
        assert r.count("|") == 6, r[:20]
    lines[i + 1:i + 1] = rows
    io.open(p, "w", encoding="utf-8", newline="").write(nl.join(lines))
print("ok")
