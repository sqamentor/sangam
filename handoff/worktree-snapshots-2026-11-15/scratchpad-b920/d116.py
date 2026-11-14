import io, json, datetime
H = r"D:\GITHUB  Project\sangam-handoff"
W = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524"
now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
D = [
 ("C-01", "Strict licence gate on every PR: unknown_license fail, licence-check.yml --strict; normalise() fixed first so the 27 known spellings pass (RM-18, CR-05)."),
 ("C-02", "Dual-licence elections approved as a block: EPL-2.0 for Jakarta, HK2, Jersey-core, logback; BSD-3-Clause (EDL) for jakarta.mail and angus-mail; Apache-2.0 for javassist, amqp-client, jersey-media-json-jackson, javaparser, JNA, dompurify, ryu; CDDL-1.1 for jaxb-api; BSD-2-Clause for HdrHistogram; MIT or Apache-2.0 for the Rust crates; H2: MPL-2.0. The six Jersey jars with ten listed licences stay unelected until their files are reviewed (DR-01)."),
 ("C-03", "OS packages in base image layers are allowed as separate programs, with their copyright files kept and a source offer shipped; AGPL and the other forbidden licences stay forbidden even as OS packages (RM-19, CR-06, CR-20)."),
 ("C-04", "Rhino policy entry scoped: forbidden in every distribution except the veraPDF validator image; VeraPdfValidator moves to its own validator module so no library in the server fat jar reaches Rhino (RM-26, M-081)."),
 ("C-05", "UI web fonts: serve the approved deploy/fonts Noto TTFs (decisions 70, 95) from the UI public folder and drop @fontsource (RM-24)."),
 ("C-06", "Sakal Bharati font dropped from the UI; Noto Sans Devanagari is used (RM-24)."),
 ("C-07", "Code never accepts database terms: acceptance (SQL Server .acceptLicense(), ACCEPT_EULA, Oracle Free, Db2 Community) becomes an explicit operator variable; without it those lanes report NOT-RUN (RM-35)."),
 ("C-08", "Oracle ojdbc11 moves to a CI-only profile that is never active by default (RM-23, CR-17)."),
 ("C-09", "Replay persona-group override is not intended: removed, deny wins (RM-11)."),
 ("C-10", "Malware scanning of uploads is required in production: start-up refused without a scanner unless an explicit, logged operator waiver is configured (B-04)."),
 ("C-11", "Signed audit checkpoints required for every tenant in production; the GL export required where the ledger is enabled (B-05)."),
 ("C-12", "Build-tool exception for exactly the listed GPL+CPE and unproven-licence build artifacts at these versions: never shipped, enforced by CR-16 and CR-17; unknown build licences are reported, not failed (RM-44)."),
 ("C-13", "The script-worker image is distributed in 0.1.0 (M-061 is in scope): release build, image SBOM, Node LICENSE, nsjail and kafel commits recorded, pinned apt packages, LGPL-2.1 conditional for libnl with a source offer (RM-32)."),
 ("C-14", "Helm backup images default to empty with a required guard, as for Oracle (RM-34)."),
 ("C-15", "npm ci --ignore-scripts everywhere, followed by an explicit npm rebuild esbuild; no install-time telemetry (RM-25, CR-14)."),
 ("C-16", "FastAPI adapter deferred past 0.1.0, like Django (RM-48); the W3-I python-fastapi CI job is removed or disabled with it."),
 ("C-17", "Sangam.Sdk.Hosting stays source only in 0.1.0 (RM-48); the dotnet-hosting CI job tests it but does not publish it."),
 ("C-18", "Release security gate fails on: a critical vulnerability with a fix available in a shipped jar or image, any verified secret, Semgrep errors in authorisation, money or audit paths; PRs stay report-only and labelled so (RM-40, CR-15)."),
 ("C-19", "The downloads and online lookups that pinning needs are approved as one logged batch: 28 action commit SHAs, Maven distribution and wrapper checksums, syft/grype/kubeconform/cosign hashes and signatures, cyclonedx-maven-plugin 2.9.1 and cyclonedx-npm 2.1.0, pitest and maven-site-plugin closures; each pinned, licence-checked where it is code, and logged in a DOWNLOADS log (RM-20..RM-22, CR-10..CR-13)."),
 ("C-20", "spring-security-saml2-service-provider is removed from the 0.1.0 server together with the Shibboleth repository; the decision 90 record stays for the later release; SamlWiring keeps refusing saml.enabled=true (RM-41, RM-01)."),
 ("C-21", "No trusted payment channels by default: a non-empty sangam.billing.trusted-channels is refused in production unless explicitly acknowledged; each use needs a channel permission, writes PAYMENT_ACCEPTED_UNVERIFIED, and the channel comes only from server-side configuration (RM-03)."),
]
out = [f"\n## 116-136. E16 owner questions C-01 to C-21 ({now}, owner via AskUserQuestion: 'Accept all recommended'; H2 election MPL-2.0)\n",
       "Source: sangam-handoff/compliance/OWNER-QUESTIONS.md (W3-X4). Each answer is the recommended option written there. None of them is a legal conclusion, and none authorises contacting a vendor or accepting terms.\n"]
for n, (c, t) in enumerate(D, start=116):
    out.append(f"- **{n} ({c})**: {t}\n")
p = H + r"\OWNER-DECISIONS-2026-11-12.md"
s = io.open(p, encoding="utf-8").read()
if "## 116-136." not in s:
    io.open(p, "a", encoding="utf-8", newline="").write("".join(out))
sp = H + r"\backlog-status.json"
j = json.load(open(sp, encoding="utf-8"))
j["M-068"].update(status="CODED", note="store + routes + decision 115 (W3-B), UI picker (W3-J2), pinner in EngineWiring + package-deploy proof (PackageDeploy bound only by DeploymentPort.deployPackage), raw pkg: names and sangam-package source refused 422 (W3-F 15:26:10Z, gate exit 0)")
j["M-145"].update(status="CODED", note="inventories (X1, X2) + DECISION-REGISTER, CONFLICTS-AND-OBLIGATIONS (X4) in sangam-handoff/compliance; decisions 116-136")
j["M-146"].update(status="CODED", note="BOUNDARY-ASSESSMENT (X3), REMEDIATION-PLAN 56 items (X4); remediation is M-149")
j["M-149"]["note"] = "REMEDIATION-PLAN.md RM-01..RM-56 (P0 first: Shibboleth/SAML removal, billing fail-closed, ledger atomicity, consent deny, eSign verify, power limit, OIDC prod refuse, replay deny-wins, never-PASS-when-skipped, Grafana removal); decisions 116-136 answer the blocking questions; Wave 4"
open(sp, "w", encoding="utf-8", newline="\n").write(json.dumps(j, ensure_ascii=False, indent=2) + "\n")
REG = W + r"\sangam\TESTING-GAP-REGISTER.md"
s = io.open(REG, encoding="utf-8", newline="").read()
nl = "\r\n" if "\r\n" in s else "\n"
rl = s.split(nl)
row = "| SL-179 | LOW scope (W3-F M-068) | A package that contains event or channel models is deployed as an app bundle, and that path carries no package-deploy proof, so any sangam:operationRef in such a package is refused at deploy. | Carry the PackageDeploy proof through the app-bundle split for the BPMN part of the bundle, with an H2 test; until then the refusal is explicit, never a silent unpinned reference. | OPEN |"
assert row.count("|") == 6
if not any(l.startswith("| SL-179 |") for l in rl):
    i = next(k for k, l in enumerate(rl) if l.startswith("| SL-178 |"))
    rl.insert(i + 1, row)
    io.open(REG, "w", encoding="utf-8", newline="").write(nl.join(rl))
b = [
 f"{now} | integrator | ACK | W3-F/B47 DONE package-deploy proof accepted (raw pkg:/sangam-package refused 422 MODEL_INVALID; PackageDeploy bound only by DeploymentPort.deployPackage; Pinner pin(PackageDeploy, ref); gate exit 0 enforcer on). App-bundle packages with operationRef are refused: SL-179. M-068 set CODED.",
 f"{now} | integrator | INFO | Owner decisions 116-136 (E16 C-01..C-21, all recommended; H2 elected MPL-2.0) recorded. They feed Wave 4 (M-147, M-148, M-149). W3-X4 deliverables (DECISION-REGISTER, CONFLICTS-AND-OBLIGATIONS, REMEDIATION-PLAN, CI-RELEASE-CONTROLS, PHASED-PLAN, OWNER-QUESTIONS) accepted; the integrator posts their CHANGE/DONE here because that lane writes only in compliance/.",
]
io.open(H + r"\WAVE3-SYNC-BOARD.md", "a", encoding="utf-8", newline="").write("".join(x + "\n" for x in b))
print("ok")
