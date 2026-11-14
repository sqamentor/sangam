# Phased plan for E16 remediation (lane W3-X4)

Date: **2026-11-12**. This plan uses the item ids of `REMEDIATION-PLAN.md` (RM-nn), `CI-RELEASE-CONTROLS.md` (CR-nn) and `OWNER-QUESTIONS.md` (C-nn).

The order follows the owner's instruction of 2026-11-12: code and unit tests first, then docs, then push to main, then tests stage by stage, then the E2E report. That instruction is recorded in project memory, not in this tree, so treat the reference as an ASSUMPTION.

No phase starts a download, image pull, vendor contact or terms acceptance without the approval named in it. Nothing in Phases A or B is reported as PASS until Phase C runs it.

---

## Phase A: Wave 4 can code now, with no owner input

Each item has a root-cause fix and a written regression test (not run until Phase C). The items are grouped so that they can run in parallel without touching the same files. Lane names are not assigned here: **owner (to name)**, integrator to allocate.

### A-1 Money, authorisation and audit correctness (P0)

| Item | Files (main) | Test written |
|---|---|---|
| RM-02 Payment confirmation fails closed and leaves the host (no trusted-channel change) | sangam-billing, `BillingPaymentPort.java` | Empty connector output gives "not confirmed"; null port refuses |
| RM-04 Ledger, audit and GL entry are atomic | `JdbcLedgerStore.java`, `LedgerService.java`, `LedgerWiring.java` | Failure injection, then retry; exactly one audit row and one GL row (H2 and PostgreSQL) |
| RM-05 GL connector without integration is refused | `LedgerWiring.java` | Start-up refusal |
| RM-06 Consent fails closed | `RegistryPorts.java`, `RegistryModule.java`, `RegistryWiring.java` | Non-development start with compliance off is refused |
| RM-07 eSign verification | `EsignConnector.java`, simulator | Valid, tampered, wrong-hash and untrusted-chain vectors |
| RM-08 One power-limit rule | `PowerLimitGuardListener.java`, `DelegationOfPowersService.java` | Shared vectors |
| RM-09 OIDC iss/aud refused in production | `OidcConfig.java` | Production refusal tests |
| RM-10 Audit failure on replay refuses the replay | `IdempotencyFilter.java` | A failing AuditPort returns no stored body |
| RM-36 No Double money in engine variables | `Vars.java`, `MoneyGuardTest` | Round-trip exact; Double refused |
| RM-37 Billing actor comes from the principal (trace first) | `BillingConnector.java` and the invoker | A forged actor is ignored |
| RM-49 No audit event with a blank tenant | `PacksAuditSink.java` | Refusal |

### A-2 Structural guards (P1)

| Item | Files | Test written |
|---|---|---|
| RM-27 Permissive defaults fail closed, plus `PermissiveDefaultGuardTest` | integration, resources, ledger, registry, billing; core arch tests | The guard, and one module test per default |
| RM-28 Warn-only controls refuse (SW-11, SW-14, SW-17, SW-18) | `AuditVerifyWiring`, `SharedInstanceIsolationWiring`, `GovConnectors`, `IntegrationWiring` (plus a `DocumentEraser` implementation) | One test per item |
| RM-29 Host-thinness guard and pom-level framework ban, with ratchets shared with M-141a to M-141d | `HostFrameworkBoundaryTest`, `ReactorBannedDependenciesTest`, root pom | A new offender fails |
| RM-30 Seed of the host conformance kit (M-150) | New test module, server tests | The same scenarios on two harnesses |

### A-3 Supply chain and build integrity (P0/P1)

| Item | Files | Notes |
|---|---|---|
| RM-01 Shibboleth repository scoped, `checksumPolicy=fail`, strict checksums, `RepositoryScopeGuardTest` | `sangam/pom.xml:413-422`, `sangam/.mvn/maven.config`, `sangam/.mvn/rrf/` | If C-20 later picks B, the repository is deleted instead. The guard stays |
| RM-12 A skipped check is never PASS (CR-01, CR-02, CR-04 inputs-missing logic, CR-16) | `licence-check.yml`, `ci-build.yml`, `release.yml`, new `no_silent_pass.py`, `governance.yml` | |
| RM-13 Grafana out of the air-gap bundle | `deploy/airgap/images.txt`, `save-images.sh` | |
| RM-14 Per-distribution Maven SBOMs (CR-03), plus the SBOM-equals-jar script | `licence-check.yml`, `release.yml`, root pom | Pinning the cyclonedx version waits on C-19; the per-module structure does not |
| RM-15 `--jar` on every released jar (CR-04) | `licence-check.yml`, `release.yml` | |
| RM-16 NOTICE and licence bundle generator (CR-07), without the elections | `deploy/licence/` (new generator), Dockerfiles, `release.yml` | The elections are filled in Phase B (C-02) |
| RM-18 Gate hardening: `normalise()`, AND/OR, SPDX ids, live conditional entries | `check_licences.py`, `licence-policy.json` (ids only) | Strictness waits on C-01 |
| RM-26 (part) Move `VeraPdfValidator` into its own validator module; the server's remote Validator client with a 422 fallback (decision 114) | `sangam-integration/pom.xml:122-128`, new module, server client (M-081) | The Rhino policy scope waits on C-04 |
| RM-31 One Bouncy Castle provider and one Commons Logging implementation; `duplicate_classes.py` | sangam-server pom, `.github/scripts/` | |
| RM-33 (check only) Pin check for actions and images (CR-10), written in report mode | `.github/scripts/pins_check.py`, `governance.yml` | It becomes gating once C-19 lets the pins exist |
| RM-38 MariaDB optional in the published server POM | `sangam-server/pom.xml` | |
| RM-39 Gate on new transitive components (CR-09) and the decision-register export (CR-08) | `licence-check.yml`, `deploy/licence/` | The baseline comes from the W3-X1/X2 CSVs |
| RM-45 (part) Pin maven-site-plugin to the cached version, or unbind it | root pom | |
| RM-46 (part) Code that requires lockfiles (CR-18), plus `--locked` and `--require-hashes` flags | `sdks.yml` | The lockfiles themselves are created in Phase C |
| RM-47 bpmn.io purl mapping and rendered watermark test (CR-19) | `licence-policy.json`, `check_licences.py`, `BpmnDiagram.watermark.test.tsx`, `ui.yml` | Under LIC-8 |
| RM-51 `list-licences.mjs` reads the policy | `sangam-ui/scripts/list-licences.mjs` | |
| RM-56 CI runtime and pyyaml pins | `ci-build.yml`, `sdks.yml` | |

### A-4 Docs (after code, per the owner order)
- `sangam/deploy/licence/README` (or the existing docs) describes the results ledger, the per-distribution SBOMs and the NOTICE bundle.
- `deploy/airgap` docs: Grafana is pulled by the customer.
- `M-015` doc: a link to the host-thinness guard and the conformance kit.

**Phase A exit criteria:**
- Every item has its code, its test source and a file claim released.
- The `HostFrameworkBoundaryTest`, `PermissiveDefaultGuardTest` and `RepositoryScopeGuardTest` sources exist.
- No item is reported as PASS.

---

## Phase B: waits on owner answers

Each B item starts the moment its question is answered. The answer-to-work mapping is fixed, so no re-analysis is needed.

| B item | Waits on | Work once answered |
|---|---|---|
| B-01 | C-01 | `--strict` on PRs, `unknown_license: fail` (RM-18, CR-05) |
| B-02 | C-02 | Election entries in the policy, SBOM annotation and NOTICE lines (RM-17; finish RM-16) |
| B-03 | C-03 | The `os_packages` policy section, the image licence gate and source offers (RM-19, CR-06, CR-20) |
| B-04 | C-10 | SW-19: refuse a production start without a malware scanner, unless an explicit, logged waiver is set (`MalwareScanWiring.java:30`, `PortsWiring.java:362-368`); a test |
| B-05 | C-11 | Audit checkpoints and/or GL export required in production profiles (`AuditCheckpointWiring.java:43`, `LedgerWiring`); start-up tests |
| B-06 | C-04 | Rhino scope in the policy, the validator image SBOM and its notices (finish RM-26) |
| B-07 | C-05, C-06 | UI fonts (RM-24) |
| B-08 | C-07 | Operator-acceptance variable for the proprietary database lanes (RM-35) |
| B-09 | C-08 | The `oracle-it` profile and the Banned-dependencies rule (RM-23) |
| B-10 | C-09 | Replay deny-wins (RM-11) |
| B-11 | C-12 | Build-tool policy section and the build/test SBOM gate (RM-44, CR-17) |
| B-12 | C-13, C-26 | Script-worker release path (RM-32), or remove the Helm reference |
| B-13 | C-14 | Helm backup defaults (RM-34) |
| B-14 | C-15 | `--ignore-scripts` everywhere (RM-25, CR-14) |
| B-15 | C-16, C-17 | SDK publish set (RM-48) |
| B-16 | C-18 | Release security gate (RM-40, CR-15) |
| B-17 | C-19 | Action SHA pins, verified tool installs, SBOM generators pinned and cached, wrapper checksums, pitest closure (RM-20, RM-21, RM-22, RM-45; CR-10 to CR-13 become gating) |
| B-18 | C-20 | Policy wording, or removal of the SAML closure and of the Shibboleth repository (RM-41; adjusts RM-01, RM-31, RM-43) |
| B-19 | C-21 | Trusted payment channels (RM-03) |
| B-20 | C-22 | Align versions and remove duplicates (RM-42, RM-43) |
| B-21 | C-29 | Dependabot limits (part of RM-20) |
| B-22 | C-37 | Repository manager or PGP verification (RM-55; after 0.1.0 if C is chosen) |

The questions C-01 to C-21 block Wave 4 coding; they are the B items above that come from that range. The questions C-22 to C-37 feed Phase C or later.

---

## Phase C: test phase and release

The steps run stage by stage. Each step writes its CR-01 ledger line, and anything that has not run stays NOT-RUN or BLOCKED.

1. **Approved online resolution and pulls** (decision 56/106 batches plus C-19). Log every download.
   - Cache cyclonedx-maven-plugin 2.9.1, cyclonedx-npm and the pitest closure.
   - Look up the action SHAs and the official checksums.
   - Pull images and record their digests (RM-33 values).
   - Verify the licences of: Keycloak UBI (C-28), GraalVM CE (C-30), the ClamAV database (C-27), the .NET, PHP and pip packages and the actions (C-31).
   - Propose the nsjail and kafel commits (C-26).
2. **Lockfiles generated and committed:** nestjs, composer, .NET, Python with hashes, Cargo (RM-46).
3. **Build the release assets for real:**
   - the server, control-plane, gateway, rollup and cli jars;
   - the validator image;
   - the UI `dist/`;
   - the SDK packages;
   - every image.
4. **Run the gates:** CR-02 to CR-07, CR-08, CR-09, CR-12, CR-14, CR-16 to CR-20. Then:
   - inspect the fat-jar contents (MariaDB, build tools and Rhino absent: X1 U-4);
   - confirm that `dist/` has no dev-only packages (X2 A-01);
   - confirm the bpmn.io watermark is visible.
5. **Re-run the inventories** (RM-50). Regenerate MAVEN-INVENTORY and NON-MAVEN-INVENTORY over:
   - the real jars, images and `dist/`;
   - the validator module;
   - `sdks/kotlin`;
   - `deploy/native/sdk-worker`.
   Update `DECISION-REGISTER.md`: no row may stay REVIEW REQUIRED or UNRESOLVED without an owner and a date.
6. **Run the test sources written in Phases A and B:**
   - unit tests and guards;
   - H2 and PostgreSQL ITs;
   - Oracle and SQL Server ITs, behind the C-07 and C-08 mechanisms;
   - the host conformance kit on two harnesses (M-150), plus a build and run without Quarkus.
7. **Security gate** at the C-18 threshold (CR-15).
8. **Release gate:** the `release-gate` job (CR-01, CR-22) passes only if every required ledger line is PASS. The evidence pack contains the ledger, the per-distribution SBOMs, the notices and the licence reports.
9. **E2E report**, per the owner order.

**Release blockers that remain if not closed in Phase C:**
- any REVIEW REQUIRED entry that covers a shipped component: DR-01, DR-02, DR-24, DR-25, DR-41, DR-42, DR-43 (if distributed), DR-46/DR-47 (bundle images), DR-57;
- any REJECTED entry still present in a distribution (DR-22);
- any required ledger line that is not PASS.

---
Generated by lane W3-X4 on 2026-11-12 (read-only synthesis).
