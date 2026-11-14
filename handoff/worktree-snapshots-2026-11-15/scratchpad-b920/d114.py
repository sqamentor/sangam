import io, json, datetime, hashlib
H = r"D:\GITHUB  Project\sangam-handoff"
R = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
REG = R + r"\TESTING-GAP-REGISTER.md"
now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
dec = H + r"\OWNER-DECISIONS-2026-11-12.md"
io.open(dec, "a", encoding="utf-8", newline="").write(
 f"\n## 114. veraPDF runs as a separate validator, Rhino stays out of the server ({now}, owner via AskUserQuestion)\n\n"
 "Question: veraPDF 1.30.4 (decision 94) depends on org.mozilla:rhino 1.7.15.1 to evaluate its validation-profile rules; LIC-10 / D-8 keep Rhino out of the default server build and the enforcer (SL-106) refuses it. "
 "Answer (recommended option): veraPDF runs in its own small container, like the script worker; the server sends it the PDF bytes and reads the report. Rhino never enters the server or its fat jar. "
 "Until the validator service exists, the server has no validator and PDF output answers 422 FORMAT_NOT_AVAILABLE (fail closed, never an unvalidated PDF). "
 "Consequences: sangam-server/pom.xml does not depend on validation-model-jakarta; the in-process VeraPdfValidator in sangam-integration (optional dependency) becomes the core of the validator service; a remote Validator client in the server is Wave 4 work under M-081; LIC-10 and the fat-jar guard in server.Dockerfile stay unchanged.\n")
sp = H + r"\backlog-status.json"
j = json.load(open(sp, encoding="utf-8"))
j["M-081"].update(status="PARTIAL", note="renderer, fonts, Unicode mapping and in-process VeraPdfValidator CODED (W3-E); decision 114: veraPDF moves to a separate validator container (Rhino out of the server), remote Validator client + service image in Wave 4; server answers 422 FORMAT_NOT_AVAILABLE until then; image fonts + SANGAM_PDF_FONTS DONE (W3-M)")
j["M-044"].update(status="CODED", note="PROCESS_TRACE 0043-12 (W3-G), ProcessTraceRecorder in the start command's transaction, children inherit the root trace, ProcessInstance.traceId on reads (W3-F), UI trace link (W3-J); history-removal cleanup open (SL-177)")
j["M-146"].update(status="EVIDENCE", note="W3-X3 BOUNDARY-ASSESSMENT.md: 10 SILENT-WEAKENING findings (billing confirm, ledger audit/GL atomicity, consent, eSign, OIDC issuer/audience, replay override, ...), R-1..R-12 remediation; synthesis pending")
open(sp, "w", encoding="utf-8", newline="\n").write(json.dumps(j, ensure_ascii=False, indent=2) + "\n")
pom = R + r"\sangam-server\pom.xml"
h = hashlib.sha256(open(pom, "rb").read()).hexdigest().upper()
lines = [
 f"{now} | integrator | DONE | BROKEN (veraPDF pulls org.mozilla:rhino, enforcer SL-106) closed: validation-model-jakarta removed again from sangam-server/pom.xml (SHA-256 {h}). Owner decision 114: veraPDF runs as a separate validator container; the server keeps no validator until the Wave 4 client lands and answers 422 FORMAT_NOT_AVAILABLE. Nobody skips the enforcer.",
 f"{now} | integrator | ACK | W3-F/B47 DONE M-044 trace writer accepted (incl. PATCHES B-35 JobExecutorSetup per-chain interceptor fix). PROCESS_TRACE cleanup on history removal recorded as SL-177. W3-F waits for W3-B's ApiContractStore DONE for the M-068 pinner.",
 f"{now} | integrator | ACK | W3-M/B54 DONE decision 113 compose one-shot + keycloak-secret override and M-081 image fonts / SANGAM_PDF_FONTS accepted; helm lint not run (no helm offline), recorded for the test phase. Open point: KC_BOOTSTRAP_ADMIN_PASSWORD must equal the secret-file password on a first start.",
 f"{now} | integrator | ACK | W3-G/B48 DONE 0043-13 CH_INBOUND_SEEN media columns (ADDITIONAL_CHANGESETS 80 -> 81) lifts the schema hold on W3-C's durable media change. W3-G's REQUEST to W3-J (amount sort sends currency) is closed by W3-J2's DONE 14:44:51Z (amount sort only with a valid currency).",
 f"{now} | integrator | ACK | W3-X3 BOUNDARY-ASSESSMENT.md received; E16 synthesis starts now from X1, X2 and X3.",
]
io.open(H + r"\WAVE3-SYNC-BOARD.md", "a", encoding="utf-8", newline="").write("".join(l + "\n" for l in lines))
io.open(H + r"\FILE-CLAIMS.md", "a", encoding="utf-8", newline="").write(f"{now} integrator CLAIM+RELEASE decision 114 revert: sangam/sangam-server/pom.xml {h}\n")
s = io.open(REG, encoding="utf-8", newline="").read()
nl = "\r\n" if "\r\n" in s else "\n"
rl = s.split(nl)
rows = [
"| SL-177 | LOW retention (W3-F M-044) | When the history of a process instance is removed (history cleanup, DPDP erasure), its PROCESS_TRACE row stays, so a trace id outlives the instance it describes. | Delete PROCESS_TRACE rows in the same history-removal path that deletes the instance history, with a test on H2; check the DPDP erasure path too. | OPEN (Wave 4) |",
"| SL-178 | MEDIUM capability (owner decision 114) | veraPDF needs Rhino, which LIC-10 keeps out of the server, so the server has no PDF validator and PDF output answers 422 FORMAT_NOT_AVAILABLE until the separate validator container and its client exist. | Build the validator service around VeraPdfValidator and a server-side remote Validator client (fail closed on timeout or unreachable service); add the image to release and images.txt; acceptance test of a Latin and Devanagari sample through the service. | OPEN (Wave 4) |",
]
for r in rows:
    assert r.count("|") == 6
if not any(l.startswith("| SL-177 |") for l in rl):
    i = next(k for k, l in enumerate(rl) if l.startswith("| SL-176 |"))
    rl[i + 1:i + 1] = rows
    io.open(REG, "w", encoding="utf-8", newline="").write(nl.join(rl))
print("ok", h)
