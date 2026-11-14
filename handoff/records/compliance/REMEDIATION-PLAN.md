# Sangam 0.1.0 remediation plan (lane W3-X4)

This plan merges the remediation proposals of three earlier reviews into one priority list, with duplicates removed:
- W3-X1 (`MAVEN-FINDINGS.md` section 7, items 1-12, plus gaps G-1 to G-9);
- W3-X2 (`NON-MAVEN-FINDINGS.md` section 5, items 1-11, plus U-01 to U-17);
- W3-X3 (`BOUNDARY-ASSESSMENT.md` section 7, R-1 to R-15).

All evidence is dated **2026-11-12**. This lane changed nothing. Every item is a proposal for the owning Wave 4 lane.

## How to read this plan

**Priority.** Decision 54R ranks fixes in this order: correctness, authorisation, money, audit, then supply-chain integrity. Decision 112 adds that a forbidden licence must never reach a distribution.
- **P0**: blocks release. It is a money, authorisation or audit defect, a forbidden licence inside a distribution, a gate that can report PASS while skipped, or an integrity exposure on every online build.
- **P1**: needed before the 0.1.0 release gate.
- **P2**: needed before 0.1.0 general availability, or before a test-phase gate can run meaningfully.
- **P3**: hardening and later releases.

**Backlog ids.**
- M-147: SBOM and notices.
- M-148: CI and release controls.
- M-149: remediation of findings.
- M-150: cross-host equivalence.
- M-141a to M-141d: framework-extraction slices.
- "new": no backlog row yet.

**Owner answer first?** "No" means Phase A (Wave 4 can code it now). "Yes (C-nn)" means Phase B: wait for that question in `OWNER-QUESTIONS.md`.

**Root-cause rule.** Every fix removes the cause. None adds a flag, a catch-and-continue or a skip. Each fix needs a regression test, written in Wave 4 and run in the test phase.

**Status of each source finding.** The underlying findings are VERIFIED as cited, unless the item says ASSUMPTION or UNRESOLVED.

---

## P0

### RM-01 Scope the Shibboleth repository and fail on bad checksums (P0/P1)
- **Merges:** X1 V-3, G-7 and item 4. The integrator recorded it as an M-149 P1 (WAVE3-SYNC-BOARD, 14:49:14Z).
  - It is P0 for any build that may go online: Maven then asks build.shibboleth.net for every missing artifact. Evidence: `w3x1-evidence/plugins-log2.txt` shows the request for `org.apache.maven.resolver:maven-resolver-api:1.9.23`.
  - It is P1 for builds that stay strictly offline.
- **Affected:** `sangam/pom.xml:413-422` (`<repositories>`); `sangam/.mvn/` (new `maven.config` and `rrf/` filter file).
- **Root-cause fix:**
  1. Turn on Maven resolver remote-repository filtering by group id: `-Daether.remoteRepositoryFilter.groupId=true` in `sangam/.mvn/maven.config`, plus `sangam/.mvn/rrf/groupId-shibboleth-releases.txt` listing only `org.opensaml` and `net.shibboleth`.
     - The 17 Shibboleth-sourced artifacts are OpenSAML 5.2.3 and shib 9.2.3 only (X1 summary).
     - ASSUMPTION: the filter feature is available in Maven 3.9.9 / resolver 1.9.x. Confirm in the test phase.
  2. On the repository, set `<checksumPolicy>fail</checksumPolicy>` and `<snapshots><enabled>false</enabled></snapshots>`.
  3. Pass `--strict-checksums` in `maven.config` so Central is covered too.
  4. If C-20 removes the SAML closure from 0.1.0, delete the repository entry instead.
- **Acceptance criteria:**
  - A new guard test, `RepositoryScopeGuardTest` in sangam-core arch tests, reads every pom `<repositories>` and `<pluginRepositories>` entry. It fails when a non-Central repository has no `rrf` group file, or has `checksumPolicy` other than `fail`.
  - In the test phase, an online `dependency:resolve-plugins` run logs no request to build.shibboleth.net for any group other than `org.opensaml` or `net.shibboleth`.
- **Backlog:** M-149, with the guard in M-148.
- **Owner answer first?** No.

### RM-02 Payment confirmation fails closed and moves out of the host
- **Merges:** X3 SW-1, SW-3 and R-1, except the trusted-channel default, which is RM-03.
- **Affected:**
  - `sangam-billing` (new plain-Java `PaymentConfirmationPolicy`; `BillingService.java:114-121`);
  - `sangam-server/.../bridge/BillingPaymentPort.java:36-67`, which shrinks to configuration binding.
- **Root-cause fix:**
  - A connector result with no `status`, or no parseable `amount`, means NOT confirmed.
  - `BillingService` refuses `collect` when no payment port is bound. It no longer treats `payments == null` as confirmed.
- **Acceptance criteria:** unit tests in sangam-billing:
  - empty connector output gives "payment not confirmed";
  - `BillingService(..., null)` makes `collect` refuse;
  - `BillingPaymentPort` holds no conditional business logic (the RM-29 guard lists it until it is clean).
- **Backlog:** M-149.
- **Owner answer first?** No.

### RM-03 Trusted payment channels are not accepted unverified by default
- **Merges:** X3 SW-2 and the R-1 default.
- **Affected:** `BillingPaymentPort.java:39,44-46`; `sangam-billing/.../BillingConnector.java:115`, where the channel comes from connector input.
- **Root-cause fix:**
  - The default list of trusted channels is empty.
  - A non-empty list is refused in production unless explicitly acknowledged.
  - Each use needs a channel permission and writes the audit event `PAYMENT_ACCEPTED_UNVERIFIED`.
  - The channel is taken from the server-side configuration of the connector instance, never from request input.
- **Acceptance criteria:**
  - A production start with `trusted-channels` set and no acknowledgement is refused.
  - A request-supplied channel cannot widen trust.
  - The audit row is written.
- **Backlog:** M-149.
- **Owner answer first?** Yes (C-21).

### RM-04 Ledger posting, audit and GL entry become atomic
- **Merges:** X3 SW-6 and R-2. The severity depends on X3 U-2, but the fix does not (C-32).
- **Affected:** `sangam-ledger/.../JdbcLedgerStore.java:79-131`; `LedgerService.java:248-258`; `sangam-server/.../config/LedgerWiring.java:87,149-187`.
- **Root-cause fix:** `JdbcLedgerStore.post` writes the pending `LEDGER_POSTED` audit row and the GL outbox row in the same transaction as `LDG_ENTRY`. A duplicate retry re-emits them if they are missing. It never skips them.
- **Acceptance criteria:**
  - An integration test injects a failure after the ledger commit and before the audit write, then retries. Exactly one audit row and one GL outbox row must exist.
  - It runs on H2 and PostgreSQL, then Oracle and SQL Server in the test phase.
- **Backlog:** M-149.
- **Owner answer first?** No.

### RM-05 A GL connector without integration refuses to start
- **Merges:** X3 SW-7 and R-3.
- **Affected:** `LedgerWiring.java:163-167,211-218`.
- **Root-cause fix:** when `sangam.ledger.gl-connector` is set and there is no `OutboxWriter` or publisher, start-up fails with `IllegalStateException`. The null-writer `return` is removed.
- **Acceptance criteria:** a start-up test refuses that configuration.
- **Backlog:** M-149.
- **Owner answer first?** No.

### RM-06 Registry consent fails closed
- **Merges:** X3 SW-4 and R-4.
- **Affected:** `sangam-registry/.../RegistryPorts.java:41,194-200`; `RegistryModule.java:68-77`; `sangam-server/.../RegistryWiring.java:103-106`.
- **Root-cause fix:**
  - The default consent port is `DENY_ALL`, or a `DevelopmentOnly` marker.
  - `RegistryModule` uses `ProductionMode`, not its own list of profiles.
- **Acceptance criteria:**
  - A non-development start with compliance off is refused.
  - `RegistryPorts.defaults().developmentOnlyReasons()` names consent.
- **Backlog:** M-149.
- **Owner answer first?** No. This follows decisions 54R and 68: a missing capability blocks.

### RM-07 Verify eSign responses
- **Merges:** X3 SW-5 and R-5.
- **Affected:** `sangam-integration/.../connector/gov/EsignConnector.java:216-222,281-297`.
- **Root-cause fix:**
  - Verify `DocSignature` against `UserX509Certificate` and the bound document hash.
  - Verify the ESP response signature and the certificate chain against configured trust anchors.
  - An unverifiable response becomes `OutcomeUnknown` or a business failure.
  - X3 A-3: this is a technical expectation, not a legal conclusion.
- **Acceptance criteria:** tests with a valid response, a tampered signature, a wrong hash and an untrusted chain. Only the valid response succeeds. The simulator signs with a test CA.
- **Backlog:** M-149.
- **Owner answer first?** No. The trust-anchor material itself is a test-phase input.

### RM-08 One power-limit rule
- **Merges:** X3 SW-21 and R-7.
- **Affected:** `sangam-packs/.../PowerLimitGuardListener.java:34-70`; `sangam-security/.../DelegationOfPowersService.java:210-235`.
- **Root-cause fix:**
  - The listener delegates to the single plain-Java evaluator, with one DMN output contract and the posting cap.
  - A missing `amount` on a monetary case type is refused.
- **Acceptance criteria:** shared test vectors give identical results from the listener and the service, at band boundaries, with a null amount, with `allowed` absent, and at the cap.
- **Backlog:** M-149.
- **Owner answer first?** No.

### RM-09 OIDC validation is complete in production
- **Merges:** X3 SW-12 and R-8.
- **Affected:** `sangam-server/.../config/OidcConfig.java:81-92`; `SangamSecurityProperties.java:146-153`.
- **Root-cause fix:** production start is refused when the issuer is unset (JWKS only) or the audience is blank.
- **Acceptance criteria:**
  - Start-up tests: production refuses both cases.
  - Outside production, a WARN is logged.
- **Backlog:** M-149.
- **Owner answer first?** No. This follows decisions 75 and 92.

### RM-10 A failed audit write refuses the idempotent replay
- **Merges:** X3 SW-10 and the second half of R-9.
- **Affected:** `sangam-rest/.../web/IdempotencyFilter.java:190-201`.
- **Root-cause fix:** if the replay audit row cannot be written, return 503 and do not return the stored body.
- **Acceptance criteria:** a test with a failing `AuditPort` returns no stored body.
- **Backlog:** M-149.
- **Owner answer first?** No.

### RM-11 Deny-wins on the replay re-check
- **Merges:** X3 SW-13, R-9 and U-1.
- **Affected:** `sangam-server/.../bridge/SecurityPermissionBridge.java:121-129`.
- **Root-cause fix:**
  - If the override is not intended, remove `|| groups.has(...)`.
  - If it is intended, correct the Javadoc at `:28-30` and record the rule.
- **Acceptance criteria:** an object-level deny plus a persona group gives 403 on replay (or the documented behaviour, if the override is kept).
- **Backlog:** M-149.
- **Owner answer first?** Yes (C-09).

### RM-12 A skipped, blocked or `|| true` check is never PASS
- **Merges:** X1 G-4 and item 3; decision 112. It extends to every similar pattern found on 2026-11-12.
- **Affected (VERIFIED line numbers):**
  - `.github/workflows/licence-check.yml:42-45` (`dependency:tree ... || true`, then a grep that passes when `deps.txt` is missing);
  - `licence-check.yml:59-61` and `69-71` (the `--jar` loop adds nothing when no jar exists);
  - `ci-build.yml:126-127` ("no sangam-server jar produced yet", then `exit 0`) and `:132` (the step is skipped by `hashFiles`);
  - `release.yml:105-106` (sdk-java and simulators copied with `|| true`);
  - `licence-check.yml:80` (`if-no-files-found: ignore` on the licence report).
- **Root-cause fix:** CR-01 (a results ledger), CR-02 and CR-04 in `CI-RELEASE-CONTROLS.md`.
- **Acceptance criteria:**
  - Deleting a jar, or making `dependency:tree` fail, turns the job red.
  - The release job fails when any required check is not PASS.
- **Backlog:** M-148.
- **Owner answer first?** No.

### RM-13 Remove Grafana from the air-gap bundle
- **Merges:** X2 V-18, U-03 and P1-2; DR-22.
- **Affected:** `sangam/deploy/airgap/images.txt:9,27`; `deploy/airgap/save-images.sh` (it must not save it).
- **Root-cause fix:** delete the Grafana line from the bundle groups. Document that the customer pulls Grafana themselves. The dev-compose use (DR-23) is unaffected.
- **Acceptance criteria:** a CR-06 gate over the bundle image list fails on any image whose recorded licence is in `forbidden_licenses`.
- **Backlog:** M-149.
- **Owner answer first?** No. An exception would need C-36.

---

## P1

### RM-14 A Maven SBOM per distribution
- **Merges:** X1 G-1 and item 1; X2 V-26 (in part); M-147.
- **Affected:**
  - `licence-check.yml:35-39` and `release.yml:107-111` (`makeAggregateBom`);
  - the root pom (pin cyclonedx in `pluginManagement`);
  - the release modules sangam-server, sangam-control-plane, sangam-gateway, sangam-rollup, sangam-cli (if released), sdks/java, and lib-ext;
  - the validator module (RM-26).
- **Root-cause fix:**
  - Run `makeBom` per release module and name each output by its artifact.
  - Keep the aggregate only as a cross-check.
  - Add one SBOM per SDK package (npm, PyPI, NuGet, Packagist).
- **Acceptance criteria:**
  - The release staging directory holds one SBOM per release asset (CR-03).
  - The SBOM component set equals the nested-jar list of that fat jar, apart from Sangam's own modules (CR-03 check).
- **Backlog:** M-147.
- **Owner answer first?** No. Caching the plugin falls under C-19.

### RM-15 Fat-jar checks on every released jar
- **Merges:** X1 G-2.
- **Affected:** `licence-check.yml:59-61,69-71`; `release.yml:129,134`.
- **Root-cause fix:** pass `--jar` for the server, control-plane, gateway and rollup fat jars and the cli shaded jar to both `check_licences.py` and `build_tools_not_shipped.py`. Name each jar explicitly; do not glob.
- **Acceptance criteria:**
  - A planted MariaDB jar inside any fat jar fails the release (CR-04).
  - A missing jar fails.
- **Backlog:** M-148.
- **Owner answer first?** No.

### RM-16 A third-party NOTICE and licence bundle per distribution, generated by the build
- **Merges:** X1 V-13 and item 7; X2 V-03, V-28, P3 and the obligations in section 4; M-147.
- **Affected:** `deploy/licence/` (a new generator script reading the SBOM and the jar `META-INF/NOTICE*`); `release.yml` staging; `ui.Dockerfile`; every image Dockerfile (copy the bundle to `/licenses`).
- **Root-cause fix:** for each distribution, generate the following and fail if any shipped component has no licence text:
  - `THIRD-PARTY-NOTICES.txt`: the full NOTICE texts, the elections, and source-location lines for EPL, MPL and CDDL components;
  - a `licenses/` folder with one text per SPDX id, plus the bpmn.io licence and OFL.txt.
  - It also covers the 131 Maven jars and 5 npm packages that ship no licence file, and Node's LICENSE.
- **Acceptance criteria:** CR-07. The bundle exists for every distribution in `CONFLICTS-AND-OBLIGATIONS.md` section 2, and its component list equals that distribution's SBOM.
- **Backlog:** M-147.
- **Owner answer first?** No for the generator. The elections it prints come from C-02.

### RM-17 Record the dual-licence elections
- **Merges:** X1 A-1 and item 6; X2 (dompurify, Rust).
- **Affected:** `licence-policy.json` (a new `elections` section keyed by purl and version); `check_licences.py` (emit the SPDX expression into the SBOM annotation); the NOTICE generator (RM-16).
- **Root-cause fix:** one recorded election per component and version, as listed in `CONFLICTS-AND-OBLIGATIONS.md` section 3. Any shipped multi-licence component without an election fails strict mode.
- **Acceptance criteria:** the gate fails on a multi-licence component that has no election entry.
- **Backlog:** M-147.
- **Owner answer first?** Yes (C-02).

### RM-18 Harden the licence gate
- **Merges:** X1 G-3, G-5, V-14 and item 2; X2 U-11 (in part).
- **Affected:** `deploy/licence/check_licences.py` (`normalise()`, `licence_terms()`, `term_status()`); `licence-policy.json` (SPDX ids, the dead GPL-3.0 entry, `unknown_license`); `licence-check.yml:55-62`.
- **Root-cause fix:**
  - Add the missing spellings to `normalise()`: "The Apache License, Version 2.0", "Apache 2", "BSD 2-Clause License", "Eclipse Public License - Version 2.0", the LGPL-3.0 and LGPL-2.0 variants.
  - Treat a list as OR only when an election or a recorded rule says so; otherwise as AND.
  - Replace non-SPDX ids, keeping aliases.
  - Make conditional entries apply before forbidden ones, scoped by purl, so the veraPDF entry becomes live.
  - Set `unknown_license` per C-01.
- **Acceptance criteria:**
  - Unit tests over the 27 strict failures listed in `w3x1-evidence/simgate.txt`: each maps or fails for a stated reason.
  - An AND-list containing one forbidden term fails.
- **Backlog:** M-148.
- **Owner answer first?** Yes (C-01) for the strictness. The `normalise()` and AND/OR fixes are No.

### RM-19 A licence gate over image SBOMs, with an OS-package rule
- **Merges:** X2 V-26, U-04 and P1-3.
- **Affected:** `release.yml:260-277` (add `check_licences.py --sbom ../image-sbom/*.cdx.json`); `licence-policy.json` (a new `os_packages` section); `check_licences.py`.
- **Root-cause fix:**
  - Apply the image licence gate with the owner's rule for OS packages.
  - Keep the copyright files.
  - Add a source offer for GPL and LGPL OS packages.
- **Acceptance criteria:** CR-06. A test image SBOM containing an AGPL package fails. An image SBOM file that is missing also fails.
- **Backlog:** M-148.
- **Owner answer first?** Yes (C-03).

### RM-20 Pin every third-party action to a commit SHA, and keep the pins current
- **Merges:** X2 V-23, V-27 and P1-4.
- **Affected:** the 123 `uses:` lines in `.github/workflows/*.yml`; `.github/dependabot.yml`.
- **Root-cause fix:**
  - Pin to the full 40-hex commit with a version comment, credential jobs first (DR-52). The slsa generator stays on its tag.
  - Raise the Dependabot github-actions limit above 0 (C-29).
- **Acceptance criteria:** CR-10. Any `uses:` that is not a SHA, apart from the allowlisted slsa reference, fails the PR.
- **Backlog:** M-148.
- **Owner answer first?** Yes. C-19 covers the online SHA lookup; C-29 covers Dependabot.

### RM-21 Verified CI tool installs and pinned SBOM generators
- **Merges:** X1 U-2 and item 12; X2 V-24 and P1-5; DR-08, DR-54 and DR-56.
- **Affected:**
  - `release.yml:263` (syft `curl | sh`) and `:360` (cosign version);
  - `ci-build.yml:195` (grype);
  - the kubeconform step;
  - the `npx --yes @cyclonedx/cyclonedx-npm@2.1.0` calls (`licence-check.yml:54`, `release.yml:123`, `ui.yml:71`);
  - the root pom `pluginManagement` (cyclonedx-maven-plugin 2.9.1).
- **Root-cause fix:**
  - Download a fixed release asset, verify its SHA-256 against a value committed in the repository, and verify the vendor signature where one exists (cosign or GPG).
  - Install cyclonedx-npm from a committed tools `package-lock.json` with `npm ci`.
  - Pin cyclonedx-maven-plugin in the pom and cache it.
- **Acceptance criteria:** CR-11 and CR-12. A wrong committed hash fails the job.
- **Backlog:** M-148.
- **Owner answer first?** Yes (C-19), for the downloads and hash lookups.

### RM-22 Maven wrapper checksums
- **Merges:** X1 U-5 and G-7; DR-21.
- **Affected:** `sangam/.mvn/wrapper/maven-wrapper.properties`; `sangam/.mvn/wrapper/maven-wrapper.jar`.
- **Root-cause fix:**
  - Add `distributionSha256Sum` and `wrapperSha256Sum`, taken from the official published values, not from the cache.
  - Optionally switch to the wrapper's script-only mode, so that no binary is committed.
- **Acceptance criteria:** CR-13. A guard test fails when either property is absent.
- **Backlog:** M-148.
- **Owner answer first?** Yes (C-19), for the official checksum lookup.

### RM-23 Oracle JDBC out of the default test scope
- **Merges:** X1 V-8 and item 5; DR-03.
- **Affected:** `sangam/sangam-server/pom.xml:271`; the root enforcer comment; `db-matrix.yml` (the Oracle lane activates the profile).
- **Root-cause fix:** move ojdbc11 into a profile that is never active by default (for example `oracle-it`), activated only by the Oracle lane. Alternatively keep it as it is, if the owner records test use.
- **Acceptance criteria:** `dependency:tree` with default profiles lists no `com.oracle.database`. A new rule in `ReactorBannedDependenciesTest` fails on any Oracle coordinate outside the profile.
- **Backlog:** M-149.
- **Owner answer first?** Yes (C-08).

### RM-24 UI fonts
- **Merges:** X2 U-01, U-02 and V-05/V-06; DR-24 and DR-25.
- **Affected:** `sangam-ui/package.json` (the @fontsource dependencies); `sangam-ui/public/fonts/gov/central/sakal-bharati/`; `sangam-ui/FONTS.md`; `licence-policy.json` (the OFL conditional).
- **Root-cause fix:** follow the owner's answer. The recommended option serves the already-approved `deploy/fonts` Noto TTFs from `public/`, drops @fontsource, and drops Sakal Bharati unless the owner records a decision.
- **Acceptance criteria:** the strict UI licence gate (CR-05) passes in the test phase with no font outside the policy, and the UI renders Devanagari text (an existing font test, or a new one).
- **Backlog:** M-149.
- **Owner answer first?** Yes (C-05, C-06).

### RM-25 npm install scripts off everywhere
- **Merges:** X2 V-07, U-10 and P2-10; DR-26.
- **Affected:** `ui.yml:46`; `release.yml:120`; `sdks.yml` (each `npm ci`); `sangam/deploy/docker/ui.Dockerfile:28`.
- **Root-cause fix:** `npm ci --ignore-scripts`, followed by an explicit `npm rebuild esbuild` for the one package whose binary needs its install step.
- **Acceptance criteria:** CR-14. A grep guard in CI fails on any `npm ci` or `npm install` without `--ignore-scripts`. The UI build still produces `dist/`.
- **Backlog:** M-148.
- **Owner answer first?** Yes (C-15), because it is a data-protection choice.

### RM-26 The PDF validator becomes a separate service (decision 114)
- **Merges:** decision 114; X1 V-7 (now outdated); X3 R-15 (veraPDF part); CF-07.
- **Affected:**
  - `sangam/sangam-integration/pom.xml:122-128` (the optional veraPDF dependency, VERIFIED 2026-11-12);
  - the root pom (`:108,186-188` managed entry; the enforcer);
  - a new validator module and Dockerfile;
  - a server-side remote `Validator` client (M-081);
  - `licence-policy.json`.
- **Root-cause fix:**
  1. Move `VeraPdfValidator` out of sangam-integration into its own module, the only reactor module allowed to reach Rhino. The enforcer exemption is scoped to that module, so sangam-integration (a library inside the server fat jar) never carries Rhino, even as an optional dependency.
  2. The server calls the service. With no service configured, PDF output returns 422 FORMAT_NOT_AVAILABLE (decision 114).
  3. Scope the Rhino entry in the policy by distribution (C-04).
  4. Give the validator image its own SBOM, notices and MPL source location.
  5. Re-run the X1 inventory on the module.
- **Acceptance criteria:**
  - `dependency:tree` of sangam-server and sangam-integration shows no `org.mozilla:rhino` and no `org.verapdf`.
  - The server fat-jar check passes the LIC-10 pattern (CR-04).
  - A server test with no validator gives 422.
  - The validator image SBOM passes the gate with the scoped rule.
- **Backlog:** new (with M-081). The SBOM and notices are M-147.
- **Owner answer first?** Yes (C-04) for the policy scope. The module move and the 422 path are No.

### RM-27 Permissive defaults in plain-Java modules fail closed
- **Merges:** X3 SW-3, SW-8, SW-15, SW-16 and R-6.
- **Affected:** sangam-integration (`ReplayAuthorizer.java:18`, `IdempotencyService.java:87`); sangam-resources (`ResourcesContext.java:82,267`); sangam-ledger (`LedgerService.java:43-45`); sangam-registry; sangam-billing.
- **Root-cause fix:** each default either refuses or is a `DevelopmentOnly` marker checked at assembly. A new `PermissiveDefaultGuardTest` (core arch) bans `ALLOW_ALL`, `PERMIT_ALL` and no-op lambdas as port defaults for authorisation, audit, accounting, payment and consent, with a ratchet that may only shrink.
- **Acceptance criteria:** the guard exists, and each module test asserts that its default refuses.
- **Backlog:** M-149. It feeds the M-141b capability set.
- **Owner answer first?** No.

### RM-28 Latent warn-only controls refuse when configured
- **Merges:** X3 SW-11, SW-14, SW-17, SW-18 and R-10.
- **Affected:** `AuditVerifyWiring.java:56-66`; `SharedInstanceIsolationWiring.java:43-52`; `GovConnectors.java:105-107,170-172` (use the cluster-wide `JdbcReplayGuard`); `IntegrationWiring.java:414-418` (a `DocumentEraser` for `ContentService`).
- **Root-cause fix:** replace each `LOG.warn`-and-continue with a refusal, or with the real implementation.
- **Acceptance criteria:** one start-up or behaviour test per item. No warn-and-continue remains on these paths.
- **Backlog:** M-149.
- **Owner answer first?** No.

### RM-29 The boundary guard also keeps business logic out of hosts
- **Merges:** X3 R-11 and BOUNDARY 1.2.
- **Affected:** `sangam-core/src/test/.../HostFrameworkBoundaryTest.java`; `ReactorBannedDependenciesTest.java:67-119`; the root pom.
- **Root-cause fix:**
  - Add a host-thinness rule: no `Money` or `BigDecimal` arithmetic, no amount comparison and no SQL in `sangam-server/**/bridge|config`. It carries a ratchet listing today's offenders, `BillingPaymentPort` and the `LedgerWiring` lambdas.
  - Add a pom-level rule: no Spring or Quarkus at compile scope in non-host poms. Today's offenders are `sangam-rest/pom.xml:108-117`, registry, geo, field and security; their ratchet is shared with M-141a to M-141d.
  - Add the `io.quarkus` ban.
- **Acceptance criteria:** a new offender fails the guard, and the ratchet can only shrink.
- **Backlog:** M-149, coordinated with M-141a to M-141d (each slice removes its ratchet entries).
- **Owner answer first?** No.

### RM-30 Seed of the host conformance kit
- **Merges:** X3 R-12 and BOUNDARY section 4.
- **Affected:** a new host-neutral test module (later `sangam-assembly`); sangam-server tests.
- **Root-cause fix:** host-neutral scenarios (money, P-1, deny-wins, audit tamper, idempotent replay, inbox dedupe, job fencing, fail-closed on each missing required capability) run on the Spring server and on a plain-Java harness, using the AC-08 vector pattern. Each result carries its rule and DMN version.
- **Acceptance criteria:**
  - The same scenario classes produce the same results on both harnesses.
  - Removing a required capability makes the scenario refuse.
  - A build and run without any Quarkus component is part of the kit.
- **Backlog:** M-150.
- **Owner answer first?** No.

### RM-31 One crypto provider and one Commons Logging implementation in each fat jar
- **Merges:** X1 V-9, V-10 (in part), item 8 (in part) and G-9.
- **Affected:** the sangam-server pom (exclude `org.bouncycastle:bcprov-lts8on` from `io.nats:jnats`, or align jnats); the modules pulling `commons-logging` next to `jcl-over-slf4j`; the slf4j family, aligned to 2.0.20; a new `.github/scripts/duplicate_classes.py`.
- **Root-cause fix:**
  - Remove the second provider and the second `org.apache.commons.logging` implementation.
  - Add a duplicate-class check over each fat jar's `BOOT-INF/lib`.
- **Acceptance criteria:**
  - The duplicate-class check reports no `org.bouncycastle.*` or `org.apache.commons.logging.*` class duplicated across nested jars.
  - jnats TLS and NKey tests pass in the test phase. It is an ASSUMPTION that jnats works on bcprov-jdk18on 1.84.
- **Backlog:** M-149.
- **Owner answer first?** No.

### RM-32 A distribution path for the script-worker image
- **Merges:** X2 V-20, U-05, U-06 and P2-8; DR-43 and DR-44.
- **Affected:** `deploy/docker/script-worker.Dockerfile`; `release.yml`; `deploy/airgap/images.txt`; the Helm values; `compose/.env.example`.
- **Root-cause fix (if the owner approves distribution):**
  - Release build, image SBOM and notices (Node's LICENSE copied).
  - Record the nsjail and kafel commits in the Dockerfile header and in `.env.example`.
  - Pin apt versions, or use a snapshot archive.
  - Add a libnl source offer.
- **Acceptance criteria:**
  - The build fails without `NSJAIL_COMMIT` (already true).
  - The image SBOM passes the gate.
  - `/licenses` inside the image holds Node's LICENSE.
- **Backlog:** M-147 and M-149.
- **Owner answer first?** Yes (C-13, C-26).

### RM-33 Pin every image by digest
- **Merges:** X2 V-16 and P2-6.
- **Affected:** the Dockerfile `ARG *_IMAGE` defaults; compose `image:` lines; Helm `values.yaml` `digest: ""`; Testcontainers image names; `db-matrix.yml`.
- **Root-cause fix:** replace `2022-latest` and `nonroot` with versioned tags plus digests, and fill the Helm digests for production and air-gap installs.
- **Acceptance criteria:** CR-10. Any release-path image without `@sha256:` fails.
- **Backlog:** M-148 (the check) and M-149 (the values).
- **Owner answer first?** No for the check. The digest values come from the test-phase pull (Phase C).

### RM-34 Helm backup default images
- **Merges:** X2 V-22 and U-08.
- **Affected:** the Helm `values.yaml` `backup.sqlserver.image` and `backup.mysql.image`; the schema.
- **Root-cause fix:** default to `""`, with a `required` guard, as `backup.oracle.image` already does.
- **Acceptance criteria:** a `helm template` without the value fails with a clear message, in a kubeconform test.
- **Backlog:** M-149.
- **Owner answer first?** Yes (C-14).

### RM-35 Proprietary licence acceptance moves out of code
- **Merges:** X2 V-21 and U-07; DR-45.
- **Affected:** `sangam-server/.../acceptance/AcceptanceDatabase.java:163`; `deploy/compose/docker-compose.yml:177`; the db-matrix lanes.
- **Root-cause fix:** the lane runs only when the operator sets an explicit acceptance variable (for example `SANGAM_ACCEPT_MSSQL_EULA=yes`). Code never calls `.acceptLicense()` unconditionally, and compose reads the variable.
- **Acceptance criteria:** without the variable, the lane is reported as NOT RUN (never PASS, CR-01).
- **Backlog:** M-149.
- **Owner answer first?** Yes (C-07).

### RM-36 No fractional money in Double engine variables
- **Merges:** X3 U-4 and R-14.
- **Affected:** `sangam-packs/.../core/Vars.java:136-138,294-310`; `MoneyGuardTest`.
- **Root-cause fix:**
  - Money is stored as long minor units or as a string decimal, never as `DOUBLE_`.
  - `Vars.decimal` rejects `Double`.
- **Acceptance criteria:** `MoneyGuardTest` covers `Vars.engineValue`, and a fractional money value round-trips exactly.
- **Backlog:** M-149.
- **Owner answer first?** No. C-33 records the open trace.

### RM-37 The billing actor comes from the authenticated principal
- **Merges:** X3 U-8.
- **Affected:** `sangam-billing/.../BillingConnector.java:115-116`.
- **Root-cause fix:**
  - First, trace whether the invoker overrides `actor` (evidence before code).
  - Then make the receipt and audit actor the authenticated principal, with the input value at most recorded as `reported_by`.
- **Acceptance criteria:** a test shows that a forged `actor` in the input does not change the audit actor.
- **Backlog:** M-149.
- **Owner answer first?** No. C-34 records the trace.

### RM-38 MariaDB out of the published sangam-server POM's runtime closure
- **Merges:** X1 V-5; CF-14.
- **Affected:** `sangam/sangam-server/pom.xml` (the MariaDB dependency).
- **Root-cause fix:** mark the driver `<optional>true</optional>`, so the build still stages it into lib-ext (`ci-build.yml:129-130` already copies it by coordinate), but consumers of the plain jar do not inherit it.
- **Acceptance criteria:**
  - The published POM (from `help:effective-pom`) shows the driver as optional.
  - The starter's exclusion becomes redundant, and `ServerBeanSetEqualityTest` is unchanged.
- **Backlog:** M-149.
- **Owner answer first?** No.

### RM-39 A gate on new transitive components
- **Merges:** the M-148 scope (BACKLOG row M-148).
- **Affected:** `licence-check.yml`; `deploy/licence/` (a committed baseline exported from the inventory CSVs).
- **Root-cause fix:** compare each PR's per-distribution SBOMs with the baseline. Any new or changed component must have a decision-register row, or the PR fails.
- **Acceptance criteria:** CR-09.
- **Backlog:** M-148.
- **Owner answer first?** No.

### RM-40 A gate on security findings at release
- **Merges:** X2 V-25 and P3; the M-148 scope.
- **Affected:**
  - `ci-build.yml:178-203` (Trivy and Grype: `exit-code: "0"`, `continue-on-error`);
  - `security.yml:174-329` (every job is continue-on-error; semgrep `|| true` at `:248`; gitleaks `--exit-code 0` at `:286`).
- **Root-cause fix:** release-mode gating at the threshold the owner sets. PR mode may stay report-only, but it must report its results as such, never as PASS.
- **Acceptance criteria:** CR-15.
- **Backlog:** M-148.
- **Owner answer first?** Yes (C-18).

### RM-41 Fix the cryptacular condition and decide on the SAML closure
- **Merges:** X1 V-6 and item 11; decision 99.
- **Affected:** `licence-policy.json:30`. If C-20 picks option B, also `sangam-server/pom.xml:165` (spring-security-saml2-service-provider) and the root `<repositories>`.
- **Root-cause fix:** follow the owner's answer:
  - **A:** correct the wording;
  - **B:** remove the unused SAML SP closure from the 0.1.0 server, which also removes the Bouncy Castle 1.84 line, xmlsec and velocity, and RM-01's repository.
- **Acceptance criteria:** the policy text matches the jar contents (CR-03 SBOM).
- **Backlog:** M-147 (wording) and M-149 (closure).
- **Owner answer first?** Yes (C-20).

---

## P2

### RM-42 Align versions with the framework BOM, with a convergence report
- **Merges:** X1 V-4, V-11, V-12, item 9 and G-9.
- **Affected:** the root pom pins (HikariCP 6.3.0 against 7.0.2; postgresql; mssql; h2; kafka-clients, also pinned in sangam-bom; angus-mail 2.0.4; servlet-api 6.0.0; Testcontainers BOM order); the enforcer.
- **Root-cause fix:**
  - Each pin either moves to the Boot 4.1.2 managed version or gets a recorded reason, as liquibase (LIC-7) and Jackson already have.
  - Add `dependencyConvergence` in report-only mode.
- **Acceptance criteria:** a convergence report is attached to CI, and every remaining pin has a comment citing its reason.
- **Backlog:** M-149.
- **Owner answer first?** Yes (C-22), per pin.

### RM-43 Review the other duplicates
- **Merges:** X1 V-10 and item 8 (remainder).
- **Affected:** Dropwizard metrics-core (via the OpenSAML closure; it goes away with RM-41 B); okhttp and kotlin-stdlib (the OpenTelemetry sender); gson and org.json; `javax.xml.bind:jaxb-api` (via Liquibase 4.33).
- **Root-cause fix:** remove each library that no code path needs (for example, switch the OpenTelemetry exporter to the JDK HTTP sender, if supported: ASSUMPTION). Keep and record the rest.
- **Acceptance criteria:** a smaller server SBOM, with tests unchanged in the test phase.
- **Backlog:** M-149.
- **Owner answer first?** Yes (C-22).

### RM-44 Gate the build and test SBOMs
- **Merges:** X1 G-6, G-8 and item 10; DR-04 to DR-07.
- **Affected:** `licence-check.yml` (a build/test SBOM via `includeTestScope`, plus the plugin closure listing); `licence-policy.json` (a build-tool section for GPL+CPE and the BSD-ambiguous artifacts, per the owner); the root enforcer (a licence-aware rule, or one for plugins).
- **Root-cause fix:** build and test components get their own report and fail on forbidden components (for example ojdbc11 outside its profile). The build-tool rules come from C-12.
- **Acceptance criteria:** CR-17.
- **Backlog:** M-148.
- **Owner answer first?** Yes (C-12).

### RM-45 Pin the super-POM site plugin, and resolve the pitest closure
- **Merges:** X1 U-1; DR-07.
- **Affected:** root pom `pluginManagement`.
- **Root-cause fix:** pin maven-site-plugin to the version already cached, or bind it to no phase. Resolve the pitest closure in the test phase.
- **Acceptance criteria:** an offline `dependency:resolve-plugins` exits 0.
- **Backlog:** M-148.
- **Owner answer first?** Yes (C-19) for the pitest downloads. The site-plugin pin is No.

### RM-46 SDK lockfiles
- **Merges:** X2 V-10 to V-14 and P2-9; DR-31, DR-33, DR-35, DR-37 and DR-39.
- **Affected:** `sdks/typescript-nestjs` (`package-lock.json`); `sdks/php` (`composer.lock` for CI); the .NET test project (`RestorePackagesWithLockFile`); Python (constraints with hashes); `sdks/rust/sangam-worker` (commit `Cargo.lock`, or use `--locked`).
- **Root-cause fix:** generate the lockfiles in the test phase (decision 96 forbids downloads now), then require them (CR-18).
- **Acceptance criteria:** CR-18.
- **Backlog:** M-148.
- **Owner answer first?** No for the code. The generation is Phase C.

### RM-47 Gate mapping and watermark proof for bpmn.io
- **Merges:** X2 V-04 and U-11; X3 U-5; DR-27.
- **Affected:** `licence-policy.json` (explicit `LicenseRef-bpmn.io` mapping for the 11 purls); `check_licences.py` (map "SEE LICENSE IN LICENSE" by purl); `sangam-ui/src/components/BpmnDiagram.watermark.test.tsx`; the `ui.yml` watermark step.
- **Root-cause fix:**
  - The gate maps the declared text to the conditional licence by purl.
  - The watermark test asserts that the element is visible, has a non-zero size and is not overlapped, using rendered-DOM geometry. A string grep is not enough.
- **Acceptance criteria:** CR-19.
- **Backlog:** M-148.
- **Owner answer first?** No (C-24 is advisory).

### RM-48 FastAPI adapter and Sangam.Sdk.Hosting
- **Merges:** X2 U-09 and U-17; DR-32 and DR-36.
- **Affected:** `sdks/python-frameworks/fastapi`; `sdks.yml`; `sdks/dotnet`.
- **Root-cause fix:** follow the owner's scope answer. If kept, add a CI job and pinned bounds. If deferred, take the adapter out of the publish set.
- **Acceptance criteria:** the publish set equals decision 105 plus the owner's answers.
- **Backlog:** M-148, or new.
- **Owner answer first?** Yes (C-16, C-17).

### RM-49 No audit event with a blank tenant
- **Merges:** X3 SW-20.
- **Affected:** `sangam-server/.../bridge/PacksAuditSink.java:33`.
- **Root-cause fix:** refuse an event with a blank tenant, and fix its producer. Do not chain it under `defaultTenant`.
- **Acceptance criteria:** a test shows that a blank-tenant event is refused with a clear error.
- **Backlog:** M-149.
- **Owner answer first?** No.

### RM-50 Re-run the inventories
- **Merges:** X1 U-4, U-6 and U-7; DR-57 to DR-59; X2 A-01.
- **Affected:** `sangam-handoff/compliance/` (a new run); `sdks/kotlin`; `deploy/native/sdk-worker`; the validator module.
- **Root-cause fix:** after Wave 4 and the test-phase pulls, regenerate both inventories over the real fat jars, images and `dist/`.
- **Acceptance criteria:** every release asset appears in the inventory, and no row remains UNRESOLVED without an owner and a date.
- **Backlog:** M-147.
- **Owner answer first?** No (Phase C).

### RM-51 One source of truth for UI licences
- **Merges:** X2 V-08 and CF-16.
- **Affected:** `sangam-ui/scripts/list-licences.mjs`.
- **Root-cause fix:** read `licence-policy.json`. No local allowlist.
- **Acceptance criteria:** a unit test shows the script rejects OFL for a package that is not listed in the policy.
- **Backlog:** M-148.
- **Owner answer first?** No.

---

## P3

| Id | Item | Merges | Affected | Root-cause fix | Acceptance | Backlog | Owner first? |
|---|---|---|---|---|---|---|---|
| RM-52 | Operaton exit readiness | X3 R-13, U-9 | sangam-core tests, sangam-engine, docs | Guard: domain modules use only `org.operaton.bpm.engine.delegate.*` and the public API, with a ratchet; an `ACT_HI_*` archive-export spec; a generated inventory of `operaton:` extensions; a per-class split of rule against glue | Guard and ratchet written; inventory generated | new | No (C-35 advisory) |
| RM-53 | Merge the two broker adapter sets | X3 R-15 | sangam-flowable-ports, sangam-integration | One adapter per broker family | The SBOM shows one client per broker | M-149 | No |
| RM-54 | Paho maintenance risk | X1 section 4; DR-15 | sangam-flowable-ports / integration | Evaluate a maintained MQTT client under the policy | A decision recorded | M-149 | No |
| RM-55 | Signature verification of Maven artifacts | X1 V-2, G-7 | root pom or repository manager | PGP verification, or a repository manager with verification | Unsigned or mis-signed artifacts fail | M-148 | Yes (C-37) |
| RM-56 | Python and CI runtime pins | X2 P3; DR-34, DR-54, DR-55 | `ci-build.yml`, `sdks.yml`, `setup-*` steps | One pyyaml version with hashes; exact patch versions for Node, Temurin and Python on CI | Pins present (CR-18) | M-148 | No |

---

## Cross-reference: where each source item went

- **X1 items:** 1 → RM-14/RM-15; 2 → RM-18; 3 → RM-12; 4 → RM-01; 5 → RM-23; 6 → RM-17; 7 → RM-16; 8 → RM-31/RM-43; 9 → RM-42; 10 → RM-44; 11 → RM-41; 12 → RM-21.
- **X2 section 5:** 1 → RM-24/RM-47; 2 → RM-13; 3 → RM-19; 4 → RM-20; 5 → RM-21; 6 → RM-33; 7 → RM-35/RM-34; 8 → RM-32; 9 → RM-46; 10 → RM-25; 11 → RM-51/RM-16/RM-40/RM-56.
- **X3:** R-1 → RM-02/RM-03; R-2 → RM-04; R-3 → RM-05; R-4 → RM-06; R-5 → RM-07; R-6 → RM-27; R-7 → RM-08; R-8 → RM-09; R-9 → RM-10/RM-11; R-10 → RM-28 (SW-19 malware → C-10, see `PHASED-PLAN.md` B-items); R-11 → RM-29; R-12 → RM-30; R-13 → RM-52; R-14 → RM-36; R-15 → RM-26/RM-53.

Two X3 items are not separate RM entries, because they wait on owner answers and are listed as Phase B work in `PHASED-PLAN.md`:
- the SW-19 malware scanner refusal (C-10);
- requiring signed audit checkpoints and the GL export in production (C-11).

---
Generated by lane W3-X4 on 2026-11-12 (read-only; no tree edits, downloads, builds or tests).
