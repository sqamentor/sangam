# Licence conflicts and obligations per distribution (lane W3-X4)

Date of every claim: **2026-11-12**. This is a read-only synthesis of W3-X1 (`MAVEN-FINDINGS.md`, `MAVEN-INVENTORY.csv`), W3-X2 (`NON-MAVEN-FINDINGS.md`, `NON-MAVEN-INVENTORY.csv`), W3-X3 (`BOUNDARY-ASSESSMENT.md`), `licence-policy.json`, and owner decisions 56, 70, 71, 79, 90, 94, 95, 96, 99, 102, 105, 112 and 114.

Status tags:
- **VERIFIED**: read from the cited file on 2026-11-12.
- **ASSUMPTION**: not proven offline.
- **UNRESOLVED**: unknown.

A "conflict" here means a mismatch between a component, the licence-policy.json rules and an owner decision. **It is not a legal conclusion.** Nothing here states that Sangam is or is not legally compliant. Questions marked C-nn are in `OWNER-QUESTIONS.md`.

---

## 1. Licence and policy conflicts

| Id | Conflict | Distribution affected | Status and evidence | Resolution path |
|---|---|---|---|---|
| CF-01 | AGPL-3.0 Grafana OSS 12.1.1 is saved into the air-gap bundle, but AGPL-3.0 is a forbidden licence for anything Sangam ships | Air-gap bundle (`observability` group) | VERIFIED: `deploy/airgap/images.txt:9,27`; `licence-policy.json:50-51`. The licence itself is UNRESOLVED from the artifact (NON-MAVEN-FINDINGS V-18) | Remove it from the bundle (RM-13). An exception needs an owner decision (C-36) |
| CF-02 | The @fontsource Noto web fonts (OFL-1.1) are not covered by the OFL conditional, which lists only PDF fonts under `pkg:generic`/`pkg:github` | UI dist and UI image | VERIFIED: `licence-policy.json:23-26`. The gate result (forbidden) is predicted from `check_licences.py:128-132` and has not been run (NON-MAVEN-FINDINGS V-05) | C-05 |
| CF-03 | Sakal Bharati: name ID 13 and the C-DAC page say OFL-1.1, but name ID 10 says "FOR NON-COMMERCIAL FREE USAGE", and no owner decision covers this font | UI dist and UI image | VERIFIED: `sangam-ui/FONTS.md` §2.1; decision 70 text in `licence-policy.json:25`. Which statement governs is UNRESOLVED; no conclusion is drawn | C-06 |
| CF-04 | libnl-route-3-200 (expected LGPL-2.1) is linked at runtime in the script-worker image. The LGPL-2.1 conditional covers only MariaDB | Script-worker image | VERIFIED: `licence-policy.json:13-16`; NON-MAVEN-FINDINGS V-20. The licence is ASSUMPTION (not pulled) | C-13 |
| CF-05 | GPL/LGPL operating-system packages sit in the shipped base images (busybox, glibc, bash, coreutils), and the policy has no rule for OS packages | Every Sangam image; postgres and prometheus in the bundle | VERIFIED that the policy is silent and that image SBOMs are not licence-gated (`release.yml:275-277`). The package list is UNRESOLVED (not pulled) | C-03 |
| CF-06 | Oracle ojdbc11 (Oracle Free Use Terms) is a default test dependency. Its licence name matches `(?i)oracle free use`, and the component is in `forbidden_components`. The policy scopes this to shipping only | Developer machines and CI (not distributed) | VERIFIED: `sangam-server/pom.xml:271`; `licence-policy.json:57,63` | C-08 |
| CF-07 | Rhino 1.7.15.1 is needed by veraPDF in the new validator container (decision 114), but `forbidden_components` bans `pkg:maven/org.mozilla/rhino` with no scope | The validator image (new) | VERIFIED: `licence-policy.json:72`; decision 114. UNRESOLVED: how the gate would treat a validator image SBOM, and whether the enforcer fails `sangam-integration`, which now declares veraPDF as optional (`sangam-integration/pom.xml:124-128`) | C-04 |
| CF-08 | Proprietary database terms are accepted inside code: `.acceptLicense()` at `AcceptanceDatabase.java:163` and `ACCEPT_EULA: "Y"` at `docker-compose.yml:177`. Oracle Free and Db2 Community are pulled by test lanes | Test and dev (not distributed) | VERIFIED (NON-MAVEN-FINDINGS V-21). Decision 112 forbids contract acceptance without authorisation | C-07 |
| CF-09 | The Helm backup defaults make a customer cluster pull `mssql/server:2022-latest` (proprietary) and `mysql:8.4` (GPL per comment) | Helm chart | VERIFIED (NON-MAVEN-FINDINGS V-22). Whether this counts as distribution by Sangam is UNRESOLVED; no legal conclusion is drawn | C-14 |
| CF-10 | Six Jersey 4.0.3 jars list ten licences with no AND/OR, yet the gate passes them by treating every list as OR | Server fat jar | VERIFIED (MAVEN-FINDINGS V-14, G-5a) | DR-01 review; RM-18 |
| CF-11 | The Error Prone and NullAway dataflow jars (GPL-2.0 WITH Classpath-exception-2.0) are build tools outside the owner's `build_time_tools` list | Build only | VERIFIED (MAVEN-FINDINGS section 4) | C-12 |
| CF-12 | The cryptacular condition says the component is "reached only through the optional M-076 SAML relying party", but the SAML closure is a non-optional compile dependency of sangam-server, and decision 99 keeps SAML off in 0.1.0 | Server fat jar | VERIFIED: `licence-policy.json:30`; MAVEN-FINDINGS V-6 | C-20 |
| CF-13 | A consumer bans `org.camunda*` (SG-27, a Maven namespace), while the bpmn.io npm packages are © Camunda Services GmbH | UI dist | VERIFIED: `licence-policy.json:88-93`; NON-MAVEN-FINDINGS V-04. Whether the ban reaches npm packages is UNRESOLVED | C-25 (no vendor or consumer contact by lanes) |
| CF-14 | MariaDB (LGPL-2.1-or-later) is a runtime dependency of the published sangam-server POM. LIC-5 covers the fat jar only, so consumers of the plain jar inherit the driver | Maven Central publication of `sangam-server` (decision 105) | VERIFIED (MAVEN-FINDINGS V-5) | RM-38 |
| CF-15 | The GraalVM native-image runtime is compiled into the shipped sdk-worker. GFTC is forbidden, and the edition used is unconfirmed | sdk-worker image | UNRESOLVED (NON-MAVEN-FINDINGS U-15) | C-30 |
| CF-16 | There are two sources of truth for UI licences. `sangam-ui/scripts/list-licences.mjs` accepts OFL-1.1 and Artistic-2.0 globally; the policy accepts neither | UI | VERIFIED (NON-MAVEN-FINDINGS V-08) | RM-51 |
| CF-17 | Policy defects: the GPL-3.0-or-later conditional for veraPDF can never match (forbidden is checked first); several ids are not SPDX (`EDL-1.0`, `Bouncy-Castle`, `GPL-2.0-with-classpath-exception`, `LicenseRef-Public-Domain`); unlisted LGPL-3.0 spellings become a warning instead of forbidden | Every SBOM gate | VERIFIED (MAVEN-FINDINGS G-5b-d) | RM-18 |
| CF-18 | The image licence for Keycloak (Apache-2.0) differs from its UBI base terms | Air-gap bundle (`runtime` group) | UNRESOLVED (NON-MAVEN-FINDINGS U-14) | C-28 |
| CF-19 | The ClamAV signature database terms are separate from the GPL-2.0 engine | Optional sidecar (not redistributed today) | UNRESOLVED (NON-MAVEN-FINDINGS U-13) | C-27 |

There is no conflict for: AGPL, SSPL, BUSL, FSL, Commons-Clause, the "JSON" licence or non-commercial licences anywhere in the resolved Maven graph (VERIFIED, MAVEN-FINDINGS section 4 closing line), and GPL, AGPL, LGPL or SSPL in the shipped npm closure (VERIFIED, NON-MAVEN-FINDINGS V-03).

---

## 2. Obligations per distribution

Notes on reading this section:
- "NOTICE" means carrying the full text of each `META-INF/NOTICE*` entry, as Apache-2.0 section 4(d) requires (this is how MAVEN-FINDINGS section 5 reads it).
- "Source location" means a notice line saying where the unmodified source of an EPL, MPL or CDDL component can be obtained. It is not a source copy (MAVEN-FINDINGS section 5.3, ASSUMPTION that no component is modified).
- **No distribution has a generated third-party NOTICE or licence bundle today** (VERIFIED: MAVEN-FINDINGS V-13, NON-MAVEN-FINDINGS V-28). Every "missing" cell below therefore traces to RM-16.

| Distribution | Third-party content (evidence) | Licence texts to carry | NOTICE | Source offer or location | Elections to record | Missing today |
|---|---|---|---|---|---|---|
| **sangam-server fat jar** | 253 components (VERIFIED, MAVEN-INVENTORY `use=distribution [... server-fatjar]`). Counted from the CSV on 2026-11-12: 127 rows with a jar NOTICE, and about 105 rows whose CSV says no LICENSE/NOTICE in the jar | Apache-2.0, MIT, BSD-2/3-Clause, EPL-1.0/2.0, MPL-2.0, CDDL-1.1, EDL (BSD-3-Clause), CC0-1.0, the Bouncy Castle licence, plus the SG-27 set texts | Full texts of the NOTICE groups in MAVEN-FINDINGS section 8 (60 distinct texts across the release jars), including Spring Boot (46 components), Spring Framework, Jersey (9), HK2 (9), Tomcat, Netty QUIC (207 lines), Kafka, PDFBox and MyBatis | EPL-2.0 (Jakarta, HK2, Jersey, Paho, logback), MPL-2.0/EPL-1.0 (H2), CDDL-1.1 (jaxb-api): location lines | logback, H2, jaxb-api, javassist, HdrHistogram, the Jakarta/HK2/Jersey/Angus set, cryptacular (recorded, decision 90) | Bundle, location lines, elections; the six Jersey 10-licence jars are under review (DR-01); paranamer's variant (DR-02) |
| **sangam-control-plane fat jar** | 118 components (70 with a NOTICE) | Apache-2.0, MIT, BSD-2/3, CC0/BSD-2 (HdrHistogram), CDDL-1.1, EPL-2.0, MPL-2.0/EPL-1.0, plus the SG-27 set | As per the jar list | EPL, MPL and CDDL location lines | logback, H2, jaxb-api, jakarta.annotation/el, HdrHistogram | Bundle, elections; paranamer (DR-02) |
| **sangam-gateway fat jar** | 84 components (47 with a NOTICE) | Apache-2.0, MIT, MIT-0, CC0/BSD-2, EPL-2.0 | As per the jar list | EPL-2.0 location line | logback, jakarta.annotation, HdrHistogram | Bundle, elections; there is no `--jar` check on this jar (MAVEN-FINDINGS G-2) |
| **sangam-rollup fat jar** | 82 components (58 with a NOTICE) | Apache-2.0, MIT, BSD-2, CC0/BSD-2, CDDL-1.1, EPL-2.0, MPL-2.0/EPL-1.0 | As per the jar list | EPL, MPL and CDDL lines | logback, H2, jaxb-api, jakarta.annotation, HdrHistogram | Bundle, elections; there is no `--jar` check |
| **sangam-cli shaded jar** | 3 Jackson components (VERIFIED, M rows 15-17). The CSV marks it "built; not a release asset". Whether it is published is UNRESOLVED | Apache-2.0 | 3 Jackson NOTICE texts | n/a | n/a | If it is released: bundle, `--jar` check (MAVEN-FINDINGS G-2) |
| **sdk-java jar** (Maven Central, decision 105) | 4 Jackson components, declared and not bundled (VERIFIED, M rows 15-17 and 21) | None bundled; the POM carries Sangam's own Apache-2.0 | n/a (not bundled) | n/a | n/a | Its own SBOM (MAVEN-FINDINGS G-1) |
| **lib-ext (MariaDB Connector/J 3.5.11)** | 1 component (M row 515) | LGPL-2.1 (the text is not in the jar) | n/a | `deploy/docker/server/SOURCE-OFFER.txt` plus the sources jar in `dist/lib-ext` (VERIFIED present, MAVEN-FINDINGS section 5.3) | n/a | The LGPL text file next to the jar (ASSUMPTION that it is missing; it was not checked in `dist/`) |
| **Published sangam-server POM / plain jar** | Runtime closure including MariaDB | n/a | n/a | n/a | n/a | CF-14 |
| **UI bundle (dist)** | 149 npm packages: 128 MIT, 11 bpmn.io, 5 Apache-2.0, 2 ISC, 2 OFL-1.1, 1 MPL/Apache (VERIFIED, NON-MAVEN-FINDINGS V-03); Sakal Bharati TTF (DR-25) | MIT (including reproduced text for the 5 packages that have no LICENSE file), ISC, Apache-2.0, the bpmn.io licence (unmodified, watermark visible), OFL.txt next to each font | Apache NOTICE files where present (e.g. path-intersection) | n/a, if the MPL election is Apache-2.0 for dompurify | dompurify: Apache-2.0 | A notices file (vite emits none); the dompurify election; resolution of the fonts (C-05, C-06) |
| **sangam-ui image** | UI dist plus nginx-unprivileged 1.28.0-alpine3.21 (busybox GPL-2.0, musl MIT, others: ASSUMPTION, not pulled) | As for the UI dist, plus the base-layer copyright files | As for the UI dist | busybox and other GPL OS packages: a source offer (subject to C-03) | n/a | Image licence gate, OS-package rule, notices file inside the image |
| **sangam-server image** (and the distroless and test-mode variants) | Fat jar, lib-ext and PDF fonts (ASSUMPTION that `deploy/fonts` ships in this image) on temurin 25 noble / distroless java25-debian13 | Fat-jar texts plus OFL-NotoSans*.txt (present, NON-MAVEN-FINDINGS V-15), the Temurin JRE licence and OS packages | Fat-jar NOTICE | MariaDB offer (present); OS packages per C-03 | As for the fat jar | Image-level notices; image licence gate |
| **sangam-control-plane / gateway / rollup images** | Each fat jar on temurin 25 noble | Jar texts plus base layers | Jar NOTICE | OS packages per C-03 | As for the jars | As for the server image |
| **sangam-sdk-worker image** | Native binary (GraalVM CE runtime compiled in, UNRESOLVED edition) on distroless base-debian12 | GraalVM CE licence (expected GPL-2.0 with the Classpath Exception: ASSUMPTION), glibc LGPL | n/a | OS packages per C-03 | n/a | Edition proof (C-30); module outside the reactor not inventoried (DR-58) |
| **sangam-simulators image** | temurin 21 noble plus the simulators jar | Jar plus base | Jar NOTICE | Per C-03 | n/a | The simulators jar is copied with `|| true` (`release.yml:106`) |
| **sangam-script-worker image** (not released today) | node binary 22.18.0, nsjail 3.4, kafel, python3, libprotobuf, libnl-route (LGPL-2.1) | Node's LICENSE (with its bundled-dependency notices), Apache-2.0 for nsjail and kafel (ASSUMPTION), PSF for Python, BSD-3 for libprotobuf, LGPL-2.1 for libnl | Node's LICENSE | libnl source offer; OS packages | n/a | Everything (C-13); Node's LICENSE is dropped today (NON-MAVEN-FINDINGS V-20) |
| **PDF validator image** (new, decision 114) | veraPDF 1.30.4 (MPL-2.0 elected, decision 94), Rhino 1.7.15.1, Saxon-HE 12.8, jai-imageio-core 1.4.0, jai-imageio-jpeg2000 1.3.0, stax-utils 20070216 (decision 102), plus a JRE base | MPL-2.0, the BSD-3-Clause nuclear-disclaimer text, JJ2000, the stax-utils BSD text, and the Rhino and Saxon licences (expected MPL-2.0: ASSUMPTION) | Per the jar NOTICE entries (UNRESOLVED; not inventoried) | MPL-2.0 source location (veraPDF, Rhino, Saxon) | veraPDF MPL-2.0 (recorded in policy) | Everything; the policy scope for Rhino (C-04) |
| **Air-gap bundle** | Sangam images, plus keycloak 26.3.3, otel-collector 0.131.1 and postgres:17 (`runtime`), plus jaeger, prometheus and Grafana (`observability`, opt-in) (VERIFIED, `images.txt:22-27`) | Each image's licence plus its OS packages | Each image's notices | Grafana AGPL is not allowed (CF-01); OS packages per C-03; Keycloak UBI terms (C-28) | n/a | Remove Grafana; verify the licences at the pull; digest pins |
| **Helm chart** | References images; the backup defaults pull mssql and mysql (CF-09) | Chart is Apache-2.0 (own code) | n/a | n/a | n/a | C-14 |
| **npm @sangam/sdk (TypeScript)** | Zero runtime dependencies (VERIFIED, NON-MAVEN-FINDINGS V-09) | Own licence only | n/a | n/a | n/a | Its own SBOM (M-147) |
| **npm @sangam/nestjs** | Nest peers, not bundled | Own licence only | n/a | n/a | n/a | Lockfile (DR-31) |
| **PyPI sangam-sdk** | Zero runtime dependencies (NON-MAVEN-FINDINGS V-11) | Own licence only | n/a | n/a | n/a | Its own SBOM |
| **PyPI sangam-sdk-fastapi** | fastapi and starlette, consumer-installed | Own licence only | n/a | n/a | n/a | Scope (C-16) |
| **NuGet Sangam.Sdk** | No PackageReference (NON-MAVEN-FINDINGS V-12) | Own licence only | n/a | n/a | n/a | Its own SBOM |
| **NuGet Sangam.Sdk.Hosting** | The in-box framework reference | Own licence only | n/a | n/a | n/a | Whether it is published (C-17) |
| **Packagist sangam/sdk** | No runtime packages | Own licence only | n/a | n/a | n/a | n/a |
| **Rust sangam-worker crate / Go module** | Crates not vendored; Go has no third-party modules | Own licence only for source distribution. A binary distribution would carry the crate licences (NON-MAVEN-FINDINGS section 4) | n/a | n/a | MIT or Apache-2.0 (Apache-2.0 for ryu) if a binary is ever shipped | n/a |

---

## 3. Dual-licence elections still to be recorded

Recording means three things: an SPDX expression in the SBOM (the CycloneDX `licenses[].expression` or an annotation), a line in the distribution NOTICE, and a policy entry where the gate needs one. Only cryptacular (decision 90) and veraPDF (decision 94) are recorded today (VERIFIED, `licence-policy.json:28-36`). **Every row below needs owner confirmation: C-02.**

| Component (rows) | Options declared | Proposed election (source) | Recorded? |
|---|---|---|---|
| logback-classic and logback-core 1.5.38 (M 6, 7) | EPL-2.0 / LGPL-2.1-only | EPL-2.0 (X1 CSV) | No |
| Jakarta annotation, el, jms and ws.rs APIs; expressly; the hk2 family (10); jersey-common and jersey-server (M 215, 216, 218, 223, 466, 467-476, 479, 480) | EPL-2.0 / GPL-2.0+CPE (plus Apache or BSD on some) | EPL-2.0 (X1 CSV) | No |
| jakarta.mail-api 2.1.5, angus-mail 2.0.4 and 2.0.5 (M 219, 455, 456) | EPL-2.0 / GPL-2.0+CPE / EDL | BSD-3-Clause (EDL) (X1 CSV) | No |
| jersey-media-json-jackson 4.0.3 (M 485) | EPL-2.0 / GPL+CPE / Apache-2.0 | Apache-2.0 (X1 CSV) | No |
| jakarta.servlet-api 6.0.0 and 6.1.0, provided (M 220, 221) | EPL-2.0 / GPL+CPE | EPL-2.0 | No |
| javax.xml.bind:jaxb-api 2.3.1 (M 228) | CDDL-1.1 / GPL-2.0+CPE | CDDL-1.1 | No |
| H2 2.3.232 (M 73) | MPL-2.0 / EPL-1.0 | EPL-1.0 (X1 CSV). X1 section 4 says either; the owner chooses | No |
| javassist 3.33.0-GA (M 495) | MPL-1.1 / LGPL-2.1 / Apache-2.0 | Apache-2.0 | No |
| amqp-client 5.37.1 (M 84) | Apache-2.0 / GPL-2.0 / MPL-2.0 | Apache-2.0 | No |
| HdrHistogram 2.2.2 (M 488) | CC0-1.0 / BSD-2-Clause | BSD-2-Clause (X1 CSV) | No |
| javaparser-core 3.28.2; jna and jna-platform 5.17.0; jna 5.13.0 (M 36, 234, 236, 235) | LGPL / Apache-2.0 | Apache-2.0 | No (build and test only) |
| dompurify 3.4.17 (N 226) | MPL-2.0 / Apache-2.0 | Apache-2.0 | No |
| Rust crates (N 397-405) | MIT / Apache-2.0; ryu Apache-2.0 / BSL-1.0 | MIT or Apache-2.0; ryu Apache-2.0 | No (only matters if a binary ships) |
| Six Jersey 10-licence jars (M 477, 478, 481-484) | Unclear (AND vs OR) | **None until the DR-01 review** | No |
| cryptacular 1.3.0 (M 448) | Apache-2.0 / LGPL | Apache-2.0 | **Yes** (decision 90), wording to fix (CF-12) |
| veraPDF 1.30.4 | GPL-3.0-or-later / MPL-2.0 | MPL-2.0 | **Yes** (decision 94); the policy entry is dead (CF-17) |

The OR reading of every multi-licence POM is an ASSUMPTION (MAVEN-FINDINGS A-1) until it has been checked against the project's own licence statement.

---

## 4. Missing licence texts and notices

| Gap | Scope | Status and evidence | Fix |
|---|---|---|---|
| 131 shipped Maven jars carry no LICENSE or NOTICE file, so the text must come from the POM-declared licence | Fat jars | VERIFIED count (MAVEN-FINDINGS V-13). The exact licence text per component is UNRESOLVED until fetched (U-3) | RM-16: generate the text from the SPDX id; verify against upstream in the test phase |
| 142 shipped jars carry a NOTICE (60 distinct texts), and nothing aggregates them | Fat jars | VERIFIED (MAVEN-FINDINGS V-13) | RM-16 |
| 5 shipped MIT npm packages have no LICENSE file (`@bpmn-io/semver-compat`, `component-props`, `component-xor`, `dom-iterator`, `domify`) | UI | VERIFIED (N rows 55, 187, 188, 224, 225) | RM-16 |
| No third-party notices for the 149 shipped npm packages (vite emits none) | UI | VERIFIED (NON-MAVEN-FINDINGS V-28) | RM-16 |
| Node's LICENSE is dropped when only the binary is copied | Script-worker image | VERIFIED (`script-worker.Dockerfile`, V-20) | RM-32 |
| OS-package copyright files and source offers | All images | UNRESOLVED (not pulled; C-03) | RM-19 |
| The repository root `NOTICE` covers only Sangam, Operaton, Flowable, the FEEL set and one bpmn.io line | Repository | VERIFIED (MAVEN-FINDINGS V-13, NON-MAVEN-FINDINGS V-28) | RM-16 |
| The validator image has no inventory, notices or SBOM | Validator image | VERIFIED: it does not exist yet (decision 114) | RM-26 |
| Licence texts for the cache-missing build closures (DR-07, DR-08) | Build | UNRESOLVED | RM-45, test phase |
| A source-offer text for libnl and for GPL OS packages | Script-worker image, all images | Not present (VERIFIED: only MariaDB has one, NON-MAVEN-FINDINGS V-28) | RM-19, RM-32 |

---
Generated by lane W3-X4 on 2026-11-12 (read-only; no tree edits, downloads, builds or tests).
