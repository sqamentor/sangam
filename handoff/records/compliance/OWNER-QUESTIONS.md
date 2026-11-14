# Owner questions from the E16 review (lane W3-X4)

Date: **2026-11-12**. These are the open questions from W3-X1, W3-X2 and W3-X3, with duplicates removed, plus three that arise from decision 114 and the M-148 scope. **37 questions in total.**

**C-01 to C-21 block Wave 4 coding.** The code for those items cannot be written correctly until the owner answers. **C-22 to C-37 do not block Wave 4 coding.** They block the test phase, the release, or nothing yet.

How to read this list:
- The "(Recommended)" option is this lane's proposal. It is not a decision.
- Nothing here is a legal conclusion.
- No question asks a lane to contact a vendor, accept terms or buy anything.
- "Source" names the original question ids: X1 = MAVEN-FINDINGS, X2 = NON-MAVEN-FINDINGS, X3 = BOUNDARY-ASSESSMENT.

---

## Part 1: questions that block Wave 4 coding

### C-01 How strict should the pull-request licence gate be?
- **Source:** X1 item 2, G-3, V-14.
- **Why it matters:**
  - Today an unknown licence is only a warning on pull requests (`"unknown_license": "warn"`, and `licence-check.yml` runs without `--strict`).
  - The same graph gives 27 failures in strict mode (VERIFIED by simulation, `w3x1-evidence/simgate.txt`).
  - Unknown licences therefore surface only at release time.
- **Options:**
  - A. Strict on every PR, with `unknown_license: fail`.
  - B. Strict on release only (today's behaviour).
  - C. Strict on PRs that touch dependency files, warn on other PRs.
- **Recommended:** A. Finding a licence problem on the PR that introduces it is cheaper and keeps the decision register honest. The fixes to `normalise()` are done first, so the 27 known spellings stop failing.
- **Blocks until answered:** RM-18 (strictness part), CR-05.

### C-02 Confirm the dual-licence elections
- **Source:** X1 item 6, A-1, section 4; X2 (dompurify, Rust crates).
- **Why it matters:**
  - 44 component versions offer a choice of licence (CONFLICTS-AND-OBLIGATIONS section 3: 34 Maven rows, dompurify and 9 Rust crates).
  - None of these elections is recorded, except cryptacular (decision 90) and veraPDF (decision 94).
  - The SBOM and the NOTICE generators need the election in order to print it.
- **Options:**
  - A. Approve the proposed elections as a block: EPL-2.0 for Jakarta, HK2, Jersey-core and logback; BSD-3-Clause (EDL) for jakarta.mail and angus-mail; Apache-2.0 for javassist, amqp-client, jersey-media-json-jackson, javaparser, JNA, dompurify and ryu; CDDL-1.1 for jaxb-api; BSD-2-Clause for HdrHistogram; MIT or Apache-2.0 for the Rust crates; and for H2 the owner picks EPL-1.0 or MPL-2.0. The six Jersey jars that list ten licences stay unelected until their files have been reviewed (DR-01).
  - B. Approve them one at a time.
- **Recommended:** A. Each proposed election is the most permissive option on offer that is already allowed by the policy. The six Jersey jars stay open because their AND/OR meaning is not proven.
- **Blocks until answered:** RM-17, the election lines in RM-16, CR-07.

### C-03 What rule applies to operating-system packages inside shipped images?
- **Source:** X2 U-04, V-26.
- **Why it matters:**
  - Every Sangam image, plus postgres and prometheus in the air-gap bundle, carries GPL/LGPL operating-system packages (busybox, glibc, bash).
  - The policy says nothing about them, and the image SBOMs are not licence-checked.
- **Options:**
  - A. Allow OS packages in base layers as separate programs, provided their copyright files are kept and a source offer is shipped. AGPL and the other forbidden licences stay forbidden, even as OS packages.
  - B. Allow only images built from a curated list of base images, and review each list.
  - C. Keep the policy silent and do not gate images (today's behaviour).
- **Recommended:** A. It is the usual practice for container images (ASSUMPTION; this is not a legal conclusion), and it lets CR-06 gate images without blocking every base layer.
- **Blocks until answered:** RM-19, CR-06, CR-20, the image rows of RM-16.

### C-04 Scope of Rhino in the veraPDF validator container, and where the module lives
- **Source:** new; decision 114; CF-07.
- **Why it matters:**
  - `licence-policy.json:72` bans `org.mozilla/rhino` in every SBOM, with no scope.
  - Decision 114 puts Rhino in a separate validator image.
  - `sangam-integration/pom.xml:124-128` declares veraPDF as an optional dependency (VERIFIED 2026-11-12). Whether the enforcer now fails that module is UNRESOLVED.
- **Options:**
  - A. Scope the Rhino entry: forbidden in every distribution except the validator image. Move `VeraPdfValidator` into its own validator module, so that no library inside the server fat jar ever reaches Rhino.
  - B. Keep the unscoped ban and grant an exception for the validator image's SBOM only, with the module left in sangam-integration.
  - C. Build the validator without Rhino. Not possible with veraPDF 1.30.4, according to decision 114.
- **Recommended:** A. A scoped rule plus a separate module keeps LIC-10 provable for the server, and keeps the module boundary the same as the image boundary.
- **Blocks until answered:** RM-26 (the policy scope and the validator SBOM gate); M-081, the validator service.

### C-05 Web fonts for the UI
- **Source:** X2 U-01, V-05.
- **Why it matters:** `@fontsource/noto-sans` and `@fontsource/noto-sans-devanagari` 5.1.0 (OFL-1.1) are outside the OFL conditional. The strict UI gate is predicted to fail; it has not been run.
- **Options:**
  - A. Serve the already-approved `deploy/fonts` Noto TTFs (decisions 70 and 95) from the UI's `public/` folder and drop @fontsource.
  - B. Widen decisions 70 and 95 to the @fontsource web fonts and add their npm purls.
- **Recommended:** A. It reuses files that are already approved and hashed, and adds no new licence scope.
- **Blocks until answered:** RM-24.

### C-06 Sakal Bharati font
- **Source:** X2 U-02, V-06.
- **Why it matters:**
  - The font is shipped in the UI.
  - Its metadata contradicts itself: name ID 13 says OFL-1.1, but name ID 10 says "FOR NON-COMMERCIAL FREE USAGE".
  - Decision 70 requires a new owner decision for any other OFL font.
- **Options:**
  - A. Drop it and use Noto Sans Devanagari.
  - B. Keep it, after the owner obtains written confirmation from C-DAC. The owner does this personally; lanes do not contact vendors.
- **Recommended:** A. It removes an unresolved statement about non-commercial use from a shipped product without losing Devanagari support.
- **Blocks until answered:** RM-24.

### C-07 Proprietary database terms accepted in test code
- **Source:** X2 U-07, V-21.
- **Why it matters:** `AcceptanceDatabase.java:163` (`.acceptLicense()`) and compose (`ACCEPT_EULA: "Y"`) accept Microsoft's terms automatically. Oracle Free and Db2 Community are pulled by test lanes. No owner record of accepting any of these terms exists, and decision 112 forbids accepting a contract without authorisation.
- **Options:**
  - A. Make acceptance an explicit operator variable. Those lanes run only when the variable is set; otherwise they report NOT-RUN.
  - B. The owner records acceptance for internal CI and test use, and the code stays as it is.
- **Recommended:** A. Code never accepts a contract, and the person who runs the lane makes the choice visibly.
- **Blocks until answered:** RM-35. It also blocks the test-phase database lanes for SQL Server, Oracle and Db2.

### C-08 Oracle ojdbc11 in the default test scope
- **Source:** X1 item 5, V-8; DR-03.
- **Why it matters:** the driver comes under the Oracle Free Use Terms. It is downloaded on every default build, and no owner record approves that.
- **Options:**
  - A. Move it to a CI-only profile that is never active by default (the Oracle lane activates it).
  - B. The owner records approval for default test use.
- **Recommended:** A. It matches the enforcer comment ("CI-only driver profiles") and keeps the driver off developer machines that do not run Oracle tests.
- **Blocks until answered:** RM-23, CR-17.

### C-09 Is the persona-group override on the replay re-check intended? (SW-13)
- **Source:** X3 U-1. The X3 report names the owner as W3-D.
- **Why it matters:** `SecurityPermissionBridge.java:121-129` lets any holder of a persona group replay a request even when sangam-security denies READ on that object. This contradicts the class Javadoc and deny-wins.
- **Options:**
  - A. Not intended: remove the override, so deny wins.
  - B. Intended: keep it, and correct the Javadoc and the rule record.
- **Recommended:** A. Decision 54R puts authorisation correctness first, and deny-wins is the documented model.
- **Blocks until answered:** RM-11.

### C-10 Is malware scanning of uploads required in production? (SW-19)
- **Source:** X3 U-6.
- **Why it matters:** by default (`sangam.malware.scanner=none`), uploads are stored unscanned and production does not refuse to start. Channel attachments already refuse (FC-14).
- **Options:**
  - A. Required in production: refuse to start without a scanner, unless an explicit, logged operator waiver is configured.
  - B. Optional, and documented as such.
- **Recommended:** A. It matches the channels behaviour and decision 54R ("a missing required capability blocks"). ClamAV is already approved as a sidecar (decision 71).
- **Blocks until answered:** the SW-19 work in Phase B (B-04).

### C-11 Are signed audit checkpoints and the GL export required in production?
- **Source:** X3 U-7.
- **Why it matters:** both are opt-in today (`AuditCheckpointWiring.java:43`, and the GL connector). Without them, audit tampering can be detected only by the hash chain, and accounting depends on configuration.
- **Options:**
  - A. Required in production profiles, so start-up is refused without them.
  - B. Required only for tenants that use the ledger.
  - C. Optional.
- **Recommended:** B. Every tenant gets audit checkpoints, and the GL export is required only where the ledger is enabled. This avoids forcing a GL connector on installations that do no accounting.
- **Blocks until answered:** B-05.

### C-12 Build-tool exception for GPL+CPE and unproven-licence build artifacts
- **Source:** X1 item 10; DR-04 to DR-06.
- **Why it matters:**
  - The Error Prone and NullAway dataflow jars are GPL-2.0 with the Classpath Exception.
  - Seven build artifacts have an ambiguous BSD variant or an unmapped licence.
  - Four build artifacts declare no licence.
  - None of these is on the owner's build-tool list.
- **Options:**
  - A. Record a build-tool exception for this exact set at these exact versions: never shipped, enforced by CR-16 and CR-17. Unknown build licences are reported, not failed.
  - B. Remove the tools (Error Prone/NullAway, pitest, the site plugin closure).
  - C. Fail on every unknown build licence.
- **Recommended:** A. Build tools are not distributed, and the existing pattern for build tools (decisions 22 and 33) already handles this kind of risk.
- **Blocks until answered:** RM-44, CR-17.

### C-13 Is the script-worker image distributed?
- **Source:** X2 U-05, V-20; DR-43.
- **Why it matters:**
  - Decision 79 approves the inputs for the test phase only.
  - The image links libnl (expected LGPL-2.1), which is outside the policy.
  - `release.yml` does not build the image, yet Helm references it.
- **Options:**
  - A. Distribute it in 0.1.0. That requires a release build, an image SBOM, Node's LICENSE, the nsjail and kafel commits recorded, pinned apt packages, and an LGPL-2.1 conditional for libnl with a source offer.
  - B. Keep it test-only in 0.1.0 and remove the Helm reference.
- **Recommended:** A, if M-061 (the script task) is in 0.1.0 scope (decision 53). Otherwise B. The script task needs the worker, so shipping a Helm reference without a released image is not a coherent state.
- **Blocks until answered:** RM-32.

### C-14 Helm backup default images
- **Source:** X2 U-08, V-22.
- **Why it matters:** the defaults make customer clusters pull `mssql/server:2022-latest` (proprietary terms, mutable tag) and `mysql:8.4`.
- **Options:**
  - A. Default to `""` with a `required` guard, as already done for Oracle.
  - B. A pinned digest plus documented customer acceptance.
- **Recommended:** A. The customer chooses and accepts their own database tooling, the same way as for LIC-2.
- **Blocks until answered:** RM-34.

### C-15 IBM install-time telemetry in npm
- **Source:** X2 U-10, V-07.
- **Why it matters:** `@carbon/grid` and `@carbon/layout` send install-time usage telemetry during `npm ci` in four places. This is a data-protection and air-gap question.
- **Options:**
  - A. `npm ci --ignore-scripts` everywhere, followed by an explicit `npm rebuild esbuild`.
  - B. Set `IBM_TELEMETRY_DISABLED=true` everywhere.
  - C. Accept the telemetry.
- **Recommended:** A. It also stops every other install script from running on build hosts, which is a supply-chain gain. B relies on a vendor switch.
- **Blocks until answered:** RM-25, CR-14.

### C-16 Is the FastAPI adapter in 0.1.0?
- **Source:** X2 U-09, V-11.
- **Why it matters:** decision 96 names Django, Laravel, Symfony and NestJS, but not FastAPI. The adapter has open version ranges and no CI job.
- **Options:**
  - A. Defer it past 0.1.0, like Django.
  - B. Keep it, with a CI job and pinned bounds, verified in the test phase like NestJS.
- **Recommended:** A. It matches how decision 96 treats the other untested Python framework adapter, and reduces what 0.1.0 has to publish.
- **Blocks until answered:** RM-48.

### C-17 Is Sangam.Sdk.Hosting published?
- **Source:** X2 U-17.
- **Why it matters:** decision 105 lists only `Sangam.Sdk`, and `sdks.yml` packs only that package.
- **Options:**
  - A. Keep Hosting as source only in 0.1.0.
  - B. Publish it as a second NuGet package.
- **Recommended:** A. It matches decision 105 as written.
- **Blocks until answered:** RM-48.

### C-18 Threshold of the security findings gate at release
- **Source:** new (M-148 scope); X2 V-25.
- **Why it matters:** Trivy, Grype, Semgrep, Gitleaks and Kubescape are all report-only today, so no security finding can stop a release.
- **Options:**
  - A. At release, fail on: a critical vulnerability with a fix available in a shipped jar or image; any verified secret; Semgrep errors in the authorisation, money or audit paths. PRs stay report-only and are labelled so.
  - B. Fail on high and critical.
  - C. Stay report-only.
- **Recommended:** A. It gates the findings that matter most, without making every release depend on upstream vulnerability noise.
- **Blocks until answered:** RM-40, CR-15.

### C-19 Approval for the downloads and online lookups that pinning needs
- **Source:** X1 item 12, U-1, U-2, U-5; X2 V-23, V-24.
- **Why it matters:** each of these needs network access, which decision 56 does not cover for Wave 4 (it covers Wave 3 needs):
  - the commit SHAs of 28 actions;
  - the official checksums of the Maven distribution and wrapper;
  - the hashes and signatures of syft, grype, kubeconform and cosign;
  - caching cyclonedx-maven-plugin 2.9.1 and cyclonedx-npm 2.1.0 (as a tools lockfile);
  - the pitest and maven-site-plugin closures.
- **Options:**
  - A. Approve them as one logged batch (each pinned, licence-checked where it is code, and recorded in a DOWNLOADS log).
  - B. Defer all of it to the test phase.
- **Recommended:** A. Without these values, Wave 4 cannot write the pins (RM-20 to RM-22). Deferring them means the release controls are coded without the values they check.
- **Blocks until answered:** RM-20, RM-21, RM-22, the pitest part of RM-45, CR-10 to CR-13.

### C-20 cryptacular wording, and the unused SAML closure in the 0.1.0 server
- **Source:** X1 item 11, V-6; decision 99.
- **Why it matters:**
  - The policy says cryptacular is "reached only through the optional M-076 SAML relying party", but the SAML SP closure is a non-optional compile dependency of the server.
  - Decision 99 keeps server SAML off in 0.1.0, so the closure is shipped but unused. It brings OpenSAML, xmlsec, velocity, Bouncy Castle 1.84, Dropwizard metrics, and the Shibboleth repository.
- **Options:**
  - A. Correct the wording only ("feature-optional, present in the default server jar").
  - B. Remove `spring-security-saml2-service-provider` from the 0.1.0 server, keep the decision 90 record for the later release, and remove the Shibboleth repository.
- **Recommended:** B. Decision 99 makes the closure dead weight. Removing it eliminates the RM-01 exposure, one of the two Bouncy Castle lines and Dropwizard metrics at the root, and `SamlWiring` already refuses `saml.enabled=true` (FC-13).
- **Blocks until answered:** RM-41; the scope of RM-01; part of RM-31 and RM-43.

### C-21 Default trusted payment channels
- **Source:** X3 R-1, SW-2.
- **Why it matters:** the default `sangam.billing.trusted-channels=counter,cash` accepts the payment amount reported for those channels with no verification. The channel string comes from connector input.
- **Options:**
  - A. An empty default. A non-empty list is refused in production unless explicitly acknowledged. Each use needs a channel permission and writes a `PAYMENT_ACCEPTED_UNVERIFIED` audit event, and the channel comes only from server-side configuration.
  - B. Keep `counter,cash` as the default, but take the channel from server-side configuration only and write the audit event.
- **Recommended:** A. Decision 54R puts money correctness first. A cash counter remains possible, but as a deliberate, audited configuration.
- **Blocks until answered:** RM-03.

---

## Part 2: questions that do not block Wave 4 coding

### C-22 Version alignment and duplicate libraries
- **Source:** X1 items 8 and 9; V-4, V-10 to V-12.
- **Why it matters:**
  - Several pins sit below the Boot 4.1.2 BOM: HikariCP 6.3.0 against 7.0.2, slf4j-api 2.0.17 against 2.0.20, postgresql, mssql, h2, kafka-clients, and Testcontainers 1.21.4 against 2.0.5.
  - Some libraries are duplicated: Dropwizard metrics, okhttp with kotlin-stdlib, gson with org.json, and jaxb-api.
- **Options:**
  - A. Align with the BOM unless a recorded reason exists, and remove unused duplicates, with each change checked in the test phase.
  - B. Keep the pins and record a reason for each.
- **Recommended:** A. It reduces untested combinations. Each pin either moves or gets its reason written down.
- **Blocks:** RM-42, RM-43 (Phase B; the pom changes cannot be verified until the test phase).

### C-23 How does production get the Oracle JDBC driver?
- **Source:** X3 U-3.
- **Why it matters:** LIC-2 says the customer supplies the driver, but the install path and documentation for it are not defined.
- **Options:**
  - A. The customer places the driver in lib-ext, documented the same way as the MariaDB separate jar, with no Sangam download.
  - B. A customer-built image layer.
- **Recommended:** A. It reuses the lib-ext mechanism.
- **Blocks:** Oracle installation docs and the Oracle E2E in the test phase.

### C-24 bpmn.io gate mapping and watermark proof
- **Source:** X2 U-11; X3 U-5; V-04.
- **Why it matters:**
  - The strict gate may fail on "SEE LICENSE IN LICENSE".
  - The watermark control is a string grep, which does not prove visibility.
- **Options:**
  - A. Map the 11 bpmn.io purls to `LicenseRef-bpmn.io` explicitly, and add a rendered-visibility test.
  - B. Keep things as they are.
- **Recommended:** A. LIC-8 already covers the licence. The change only makes the existing condition provable. It can proceed without an answer.
- **Blocks:** nothing (RM-47 proceeds under LIC-8).

### C-25 Does the consumer's `org.camunda*` ban apply to Camunda-copyrighted npm packages?
- **Source:** X2 U-12.
- **Why it matters:** the SG-27 ban is scoped to a Maven namespace, while bpmn-js and related packages are © Camunda Services GmbH.
- **Options:**
  - A. The owner confirms with the consumer that the ban is namespace-only.
  - B. Treat it as covering the npm packages too, which would need an exception or a replacement modeller (an M/L effort, BOUNDARY 3.1).
- **Recommended:** A. The owner raises it personally; lanes make no contact. The policy text names the Maven namespace only.
- **Blocks:** release sign-off for the consumer deployment.

### C-26 Approve the nsjail 3.4 and kafel commits
- **Source:** X2 U-06.
- **Why it matters:** decision 79 says "commit hash recorded", but no value exists in the tree or the handoff notes.
- **Options:**
  - A. In the test phase, the integrator proposes the 40-hex commit for tag 3.4 and the kafel submodule commit, and the owner approves them.
  - B. The owner supplies them.
- **Recommended:** A. Finding the values needs an online lookup, which belongs in the test phase.
- **Blocks:** the script-worker build in the test phase.

### C-27 Terms of the ClamAV signature database
- **Source:** X2 U-13.
- **Why it matters:** the freshclam database terms are separate from the GPL-2.0 engine.
- **Options:**
  - A. Verify the terms at the test-phase pull and record them.
  - B. Require customers to provide their own mirror.
- **Recommended:** A.
- **Blocks:** the ClamAV lane in the test phase; any air-gap mirror design.

### C-28 Keycloak image redistribution under the UBI terms
- **Source:** X2 U-14.
- **Why it matters:** Keycloak is in the air-gap `runtime` group, so Sangam redistributes it, and its UBI base terms differ from Keycloak's Apache-2.0.
- **Options:**
  - A. Verify the UBI redistribution terms at the pull, and keep the notices.
  - B. Remove Keycloak from the bundle, so the customer pulls it.
- **Recommended:** A, falling back to B if the terms do not allow redistribution.
- **Blocks:** building the air-gap bundle.

### C-29 Dependabot update pull requests
- **Source:** X2 V-27.
- **Why it matters:** `open-pull-requests-limit: 0` everywhere means SHA-pinned actions and lockfiles never get update PRs.
- **Options:**
  - A. Raise the limit for github-actions (and docker, once added). Keep 0 for Maven and npm until after 0.1.0.
  - B. Raise it everywhere.
  - C. Keep 0.
- **Recommended:** A. SHA pins without update PRs go stale, and the Maven and npm graphs are frozen for 0.1.0 by design (decision 111).
- **Blocks:** nothing in Wave 4 (part of RM-20).

### C-30 Confirm the GraalVM edition in the sdk-worker
- **Source:** X2 U-15.
- **Why it matters:** the native runtime ships inside the binary, and GFTC is forbidden.
- **Options:**
  - A. Verify at the test-phase pull that it is the Community edition with no GFTC component, and record that in the image SBOM.
- **Recommended:** A.
- **Blocks:** release of the sdk-worker image.

### C-31 Licences of components that cannot be inspected offline
- **Source:** X2 U-16. Covers the .NET test packages, phpunit, pip-licenses, cosign and the GitHub Actions.
- **Why it matters:** their licences are NOASSERTION today.
- **Options:**
  - A. Resolve each when it is restored in the test phase, and record it in the register.
- **Recommended:** A.
- **Blocks:** closing DR-33, DR-35, DR-37, DR-52, DR-53 and DR-54.

### C-32 In which transaction do ledger postings run?
- **Source:** X3 U-2.
- **Why it matters:** the severity of SW-6 depends on whether the call comes through an engine command, a relay or an external task.
- **Options:**
  - A. Do RM-04 regardless (atomic outbox), and trace the contexts for the record.
  - B. Trace first, then decide.
- **Recommended:** A. The duplicate-skip loses the audit and GL entry in every context, so the fix does not depend on the answer.
- **Blocks:** nothing.

### C-33 Is any money stored as a fractional Double in engine variables?
- **Source:** X3 U-4.
- **Options:**
  - A. Trace the pack paths, and make RM-36 refuse Double regardless.
  - B. Trace first.
- **Recommended:** A. Never storing money as a floating-point number is already a guard rule (MoneyGuardTest). The fix closes the gap the guard misses.
- **Blocks:** nothing.

### C-34 Does the invoker override the `actor` taken from billing connector input?
- **Source:** X3 U-8.
- **Options:**
  - A. Trace it, then always use the authenticated principal, keeping any input value as `reported_by` (RM-37).
  - B. Trace it and keep the input value if it is overridden upstream.
- **Recommended:** A. The audit actor must not come from request input.
- **Blocks:** nothing (RM-37 starts with the trace).

### C-35 Split of delegate classes into rule and glue
- **Source:** X3 U-9.
- **Options:**
  - A. Do it with RM-52 (Operaton exit readiness) after 0.1.0.
  - B. Do it now.
- **Recommended:** A. It is a lock-in reduction, not a 0.1.0 correctness item. RM-08 handles the one known rule divergence.
- **Blocks:** nothing.

### C-36 Grafana OSS in the air-gap bundle
- **Source:** X2 U-03, V-18; DR-22.
- **Why it matters:** AGPL-3.0 is forbidden for what Sangam ships.
- **Options:**
  - A. Remove it from the bundle (the customer pulls it).
  - B. An owner exception with an AGPL source offer for the exact version.
- **Recommended:** A. The policy already decides this. RM-13 proceeds without an answer, and only B would need one.
- **Blocks:** nothing (RM-13 is Phase A).

### C-37 Signature verification of Maven artifacts
- **Source:** X1 V-2, G-7.
- **Why it matters:** SHA-1 sidecars prove only that a file was not corrupted, not where it came from. No PGP check runs.
- **Options:**
  - A. A repository manager (a proxy) that verifies signatures, run by the owner.
  - B. A PGP verification plugin, which is a new build-tool download.
  - C. Defer it past 0.1.0.
- **Recommended:** C for 0.1.0, with A planned. RM-01 and CR-13 remove the larger exposure now, and A also serves air-gapped builds later.
- **Blocks:** RM-55 (P3).

---

## Map from the source reports

| Source id | C-nn |
|---|---|
| X1 item 2 | C-01 |
| X1 item 5 | C-08 |
| X1 item 6 | C-02 |
| X1 items 8 and 9 | C-22 |
| X1 item 10 | C-12 |
| X1 item 11 | C-20 |
| X1 item 12, U-1, U-2, U-5 | C-19 |
| X1 V-2 / G-7 | C-37 |
| X2 U-01 | C-05 |
| X2 U-02 | C-06 |
| X2 U-03 | C-36 |
| X2 U-04 | C-03 |
| X2 U-05 | C-13 |
| X2 U-06 | C-26 |
| X2 U-07 | C-07 |
| X2 U-08 | C-14 |
| X2 U-09 | C-16 |
| X2 U-10 | C-15 |
| X2 U-11 | C-24 |
| X2 U-12 | C-25 |
| X2 U-13 | C-27 |
| X2 U-14 | C-28 |
| X2 U-15 | C-30 |
| X2 U-16 | C-31 |
| X2 U-17 | C-17 |
| X2 V-27 | C-29 |
| X3 U-1 | C-09 |
| X3 U-2 | C-32 |
| X3 U-3 | C-23 |
| X3 U-4 | C-33 |
| X3 U-5 | C-24 |
| X3 U-6 | C-10 |
| X3 U-7 | C-11 |
| X3 U-8 | C-34 |
| X3 U-9 | C-35 |
| X3 R-1 (trusted channels) | C-21 |
| Decision 114 | C-04 |
| M-148 scope (security gate) | C-18 |

X1 U-3, U-4, U-6 and U-7 are not owner questions. They are evidence gaps, closed by RM-16 and RM-50 in the test phase.

---
Generated by lane W3-X4 on 2026-11-12.
