# Maven dependency, licence and supply-chain findings (lane W3-X1)

Owner directive: decision 112 (E16), read with decisions 22, 33, 54R, 56, 70, 80, 81, 90, 94, 95 and 102 in `sangam-handoff/OWNER-DECISIONS-2026-11-12.md`.
Scope: the Java/Maven side only (the reactor in `sangam/`, its build plugins, the Maven wrapper and the BOM imports). npm, container images and runtimes belong to other lanes.
Review mode: read-only. Nothing in the working tree was edited, created or deleted, apart from the `target-w3x1` build-output folders that the prescribed `-Dsangam.target=target-w3x1` Maven calls are allowed to create. Nothing was downloaded, committed or pushed, and no test was run.

| Item | Value |
|---|---|
| Tree | `D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524`, branch `claude/workspace-scan-resume-06b524`, HEAD `05c89814`, with a dirty working tree that other lanes are editing live |
| Toolchain | Maven 3.9.9 (mvnw), JDK 25.0.5+8, `-o` (offline) only, `~/.m2` = `C:\Users\Lokendra Singh\.m2\repository` |
| Runs (UTC, 2026-11-12) | `dependency:tree` reactor 14:16:26Z to 14:17:08Z (exit 0, 36 projects); `dependency:tree -Dverbose` 14:24:47Z to 14:25:21Z (exit 0); `resolve-plugins` 14:17:27Z (FAIL, see U-1), 14:19:13Z (FAIL), 14:21:11Z (`-DexcludeTransitive`, exit 0); plugin closures through a scratch reactor outside the tree 14:25:51Z to 14:26:34Z (exit 0); classification 14:41Z |
| Raw evidence | `sangam-handoff/compliance/w3x1-evidence/` (tree.txt, tree-verbose.txt, plugin-tree.txt, plugins-direct.txt, plugins-decl.txt, bompins.txt, simgate.txt, the logs, and the scripts that produced the CSV) |
| Inventory | `sangam-handoff/compliance/MAVEN-INVENTORY.csv`: 685 third-party components by exact GAV (first-party `org.sangam:*` excluded), one row per GAV and classifier |

Each `scopes` cell in the CSV has two parts:

- `use=` is the usage class:
  - `distribution`: the component is in a release asset, which is one of the fat jars of sangam-server, sangam-control-plane, sangam-gateway or sangam-rollup, the declared dependencies of the sdk-java jar, the sangam-cli shaded jar, or the MariaDB lib-ext jar.
  - `runtime`: shipped Maven scope, but only in library modules that no release asset contains.
  - `test`, `build` (plugins, annotation processors, the wrapper), `build(provided)`, and `bom`.
- `maven=` lists the raw Maven scopes, with `(optional)` marked where it applies.

The `modules` column lists the reactor modules, or `plugin:<artifactId-version>` for a build plugin's closure.

## Summary

| Decision | Count | Of which shipped (distribution + runtime) |
|---|---|---|
| ACCEPTED FOR DEFINED USE | 595 | 253 |
| ACCEPTED WITH CONDITIONS | 52 | 37 |
| REVIEW REQUIRED | 38 | 7 |
| REJECTED FOR DEFINED USE | 0 | 0 |

Components by usage class: 291 distribution, 6 runtime-only, 72 test, 309 build, 2 build(provided), 5 BOM imports.
The server fat jar alone resolves 253 third-party components. The other fat jars resolve 118 (control-plane), 84 (gateway) and 82 (rollup).

By source repository (from `_remote.repositories`):
- 481 Central;
- 168 Central that are also recorded under the mirror id `central-8fac3ebd6edbaca3c794783fa38088af0aa128e7`;
- 17 Shibboleth (OpenSAML 5.2.3 and shib 9.2.3 only);
- 17 not in the cache;
- 2 build tools that come from the wrapper.

No artifact is rejected, because no forbidden licence or component reaches a shipped scope. That is a statement about the resolved graph, not about the release controls: the controls have gaps (section 6), so a future violation could pass them.

## 1. VERIFIED facts (evidence dated 2026-11-12)

V-1 **Resolved graph.**
- `dependency:tree` resolved all 36 reactor projects offline with exit 0, giving 712 distinct GAVs including plugin closures and BOMs, of which 685 are third-party.
- Evidence: `w3x1-evidence/tree.txt` and `tree-verbose.txt` (14:16:26Z and 14:24:47Z).

V-2 **Integrity.**
- Every cached third-party file has a `.sha1` sidecar, and the SHA-1 computed over every cached jar or POM matches its sidecar: 0 mismatches.
- The SHA-256 of every cached file is in the CSV.
- Caveat: the sidecar is what the repository served alongside the artifact, so a match proves only that the file was not corrupted after download. It does not prove provenance. No PGP signature (`.asc`) is checked anywhere in the build.
- Evidence: `analyse.py` run at 14:41Z over `~/.m2/repository`.

V-3 **Shibboleth repository is not scoped.**
- Its root pom `<repositories>` entry has no group filter.
- The root pom's own comment ("used for org.opensaml / net.shibboleth artifacts only") describes intent that Maven does not enforce.
- POM-declared repositories are consulted together with Central, so Maven asks build.shibboleth.net for every artifact it does not already have. The `resolve-plugins` run at 14:19:13Z failed with `Cannot access shibboleth-releases (https://build.shibboleth.net/maven/releases/) ... org.apache.maven.resolver:maven-resolver-api:jar:1.9.23`.
- No `<checksumPolicy>fail</checksumPolicy>` is set, and Maven's default is warn.
- This is a dependency-confusion and integrity exposure for every build that is allowed online.
- Evidence: `sangam/pom.xml` lines 413-422 (repositories), `w3x1-evidence/plugins-log2.txt`.

V-4 **BOM import order** (`sangam/pom.xml`, dependencyManagement): operaton-bom 2.2.0, then explicit pins, then jackson-bom 2.22.3, junit-bom 6.0.3, testcontainers-bom 1.21.4, then spring-boot-dependencies 4.1.2. The first declaration wins.
- operaton-bom 2.2.0 manages only 3 non-Operaton artifacts: java-uuid-generator 5.2.0, joda-time 2.15.0 and mybatis 3.5.19. It shadows nothing else.
- jackson-bom 2.22.3 overrides Spring Boot's Jackson-2 line (2.21.7) for 68 artifacts. This is deliberate: policy `min_versions` sets jackson-databind at 2.22.3 or later, per ARCHITECTURE 1.7.
- **testcontainers-bom 1.21.4 overrides the Testcontainers version Spring Boot 4.1.2 manages (2.0.5).** The project is therefore on Testcontainers 1.x under Boot 4 (test scope only).
- Evidence: `w3x1-evidence/bompins.txt`, computed from the BOM POMs in `~/.m2`.

V-5 **MariaDB (LGPL-2.1-or-later) is kept out of the fat jars by configuration, and the published POM still carries it.**
- `spring-boot-maven-plugin` `<excludes>` remove `org.mariadb.jdbc:mariadb-java-client` in sangam-server (pom lines 325-330), sangam-control-plane (96-100) and sangam-rollup (87-91).
- sangam-gateway does not depend on it.
- sangam-spring-boot-starter excludes it from its dependency on sangam-server.
- The driver is a `runtime` dependency of the sangam-server POM, so any other consumer of `sangam-server:plain` inherits it.
- No fat jar is built in the tree today, so the jar contents themselves are UNRESOLVED (U-4).

V-6 **SAML closure** (decision 90).
- `spring-security-saml2-service-provider` 7.1.2 is a plain (non-optional) compile dependency of sangam-server. OpenSAML 5.2.3, shib 9.2.3, xmlsec 3.0.6, velocity-engine-core 2.4.1 and cryptacular 1.3.0 are therefore inside the default server fat jar.
- The policy condition says cryptacular is "reached only through the optional M-076 SAML relying party". That can only mean feature-optional (switched off by configuration), not absent from the distribution.

V-7 **veraPDF (decisions 94, 102) is managed but not used.**
- `org.verapdf:validation-model-jakarta:1.30.4` is in root dependencyManagement and its closure is in `~/.m2` (`org/verapdf/*`).
- No reactor module declares it. veraPDF, jai-imageio-core 1.4.0, jai-imageio-jpeg2000 1.3.0, stax-utils 20070216 and Saxon-HE 12.8 appear in no module tree, so they are absent from the CSV.
- Decisions 94 and 102 are therefore not exercised by today's graph. Re-run this inventory when M-081 wires the validator.

V-8 **Oracle JDBC in the default build.**
- `com.oracle.database.jdbc:ojdbc11:23.8.0.25.04` is a `test`-scope dependency of sangam-server in the default build, not in a CI-only profile (sangam-server/pom.xml).
- Its POM declares "Oracle Free Use Terms and Conditions (FUTC)". That name is matched by `forbidden_license_name_patterns` ("oracle free use"), and LIC-2 lists the coordinate in `forbidden_components`.
- The enforcer allows it because the bans cover compile and runtime only. The enforcer comment speaks of "CI-only driver profiles", which does not match the default test scope.
- No owner decision approving download or test use was found in OWNER-DECISIONS-2026-11-12.md.

V-9 **Two Bouncy Castle providers in the server fat jar.**
- `org.bouncycastle:bcprov-jdk18on:1.84` (with bcpkix and bcutil 1.84, via the SAML/xmlsec closure).
- `org.bouncycastle:bcprov-lts8on:2.73.12.1` (via io.nats:jnats 2.26.4).
- Both ship the `org.bouncycastle.*` packages, so classes are duplicated on one class path, and which copy loads depends on order.

V-10 **Duplicate functionality in shipped scopes.** For each item below, the deduplicated GAVs are listed in the CSV.
- **JSON:** Jackson 2 (`com.fasterxml.jackson.*` 2.22.3), Jackson 3 (`tools.jackson.core:*` 3.1.7, from Spring Boot 4) in all four fat jars, gson 2.13.2, and org.json 20251224.
- **Commons Logging:** `commons-logging:commons-logging:1.3.6` and `org.slf4j:jcl-over-slf4j:2.0.20` are both in the server fat jar. Both provide `org.apache.commons.logging.*`.
- **Logging facades:** logback 1.5.38, log4j-api with log4j-to-slf4j 2.25.5, jboss-logging 3.6.3, and operaton-commons-logging 2.2.0.
- **HTTP clients:** httpclient5 5.6.5 (with httpclient5-cache), okhttp 5.3.2 (through the OpenTelemetry okhttp sender, which also pulls kotlin-stdlib 2.3.21), jersey-client 4.0.3, and reactor-netty 1.3.8 (gateway).
- **Metrics:** Dropwizard metrics-core 4.2.39 (server, via the OpenSAML/shib closure), alongside Micrometer 1.17.2 and Prometheus client 1.7.0.
- **XML binding:** `javax.xml.bind:jaxb-api:2.3.1` (2018, via Liquibase 4.33.0) and `jakarta.xml.bind:jakarta.xml.bind-api:4.0.5`.
- **REST stacks in the server:** Spring MVC on Tomcat 11.0.27, and Jersey 4.0.3 (Operaton REST) on top of it.

V-11 **Versions pinned against the framework-managed (Spring Boot 4.1.2 BOM) version.** Evidence: `bompins.txt`.

| Artifact | Pinned at | Boot 4.1.2 manages | Note |
|---|---|---|---|
| liquibase-core | 4.33.0 | 5.0.4 | Deliberate: LIC-7 / D-7 (FSL) |
| jackson (68 artifacts) | 2.22.3 | 2.21.7 | Deliberate: min_versions |
| HikariCP | 6.3.0 | 7.0.2 | Downgrade: Boot 4 asks for 7.0.2 and gets 6.3.0 (verbose tree) |
| slf4j-api | 2.0.17 | 2.0.20 | jul-to-slf4j and jcl-over-slf4j still resolve 2.0.20, so the slf4j family is split across two versions in the fat jars |
| slf4j-simple | 2.0.17 | 2.0.20 | |
| postgresql | 42.7.12 | 42.7.14 | Older than the BOM |
| mssql-jdbc | 12.10.2.jre11 | 13.4.0.jre11 | |
| h2 | 2.3.232 | 2.4.240 | |
| kafka-clients | 3.9.2 | 4.2.2 | Also pinned in sangam-bom |
| mariadb-java-client | 3.5.11 | 3.5.10 | |
| jedis | 7.5.3 | 7.4.1 | |
| amqp-client (sangam-flowable-ports) | 5.37.1 | 5.30.0 | |
| angus-mail (sangam-channels, sangam-integration) | 2.0.4 | 2.0.5 | |
| jakarta.servlet-api (sangam-integration, provided) | 6.0.0 | 6.1.0 | |
| ojdbc11 (test) | 23.8.0.25.04 | 23.26.3.0.0 | |

Redundant pins equal to the BOM: jakarta.jms-api 3.1.0, java-uuid-generator 5.2.0, and servlet-api 6.1.0 in sangam-security.

V-12 **Same artifact at different versions across modules, in shipped or provided scopes.**
- angus-mail 2.0.4 (sangam-channels, sangam-integration) against 2.0.5 (nine other modules and the server fat jar).
- angus-activation 2.0.2 against 2.0.3.
- jakarta.servlet-api 6.0.0 (sangam-integration) against 6.1.0 (sangam-field, geo, rest, security).
- In test scope only: jna 5.13.0, and org.jetbrains:annotations 17.0.0 in sangam-ops tests against 13.0 shipped.

Nearest-wins downgrades below what a library asked for, in the server and starter: commons-io 2.22.0 requested and 2.19.0 resolved; jackson-databind 2.22.4 to 2.22.3; commons-lang3 3.21.0 to 3.20.0; commons-logging 1.4.0 to 1.3.6; HikariCP 7.0.2 to 6.3.0. Full tables are in section 7.

V-13 **NOTICE obligations.**
- 142 shipped components carry a NOTICE file inside their jar, with 60 distinct texts (section 7).
- 131 shipped jars carry no LICENSE or NOTICE file at all, so the licence text has to be supplied from the POM-declared licence.
- The repository's root `NOTICE` covers Sangam itself, Operaton and Flowable, and the SG-27 FEEL set. It does not aggregate the third-party NOTICE texts.
- No workflow step generates or packages a third-party NOTICE or THIRD-PARTY file for any distribution (searched `.github/workflows/*.yml` and `sangam/deploy/docker/*`).

V-14 **Licence gate behaviour, simulated with the repository's own `check_licences.py` on the POM-declared licence names** (`simgate.txt`, 297 shipped components):

| Mode | Errors | Warnings / result |
|---|---|---|
| Non-strict (PR gate) | 0 | 27 warnings |
| `--strict` (release gate) | 27 | Release FAIL |

- The strict-mode errors come from common spellings missing from `normalise()`: "The Apache License, Version 2.0" (OpenTelemetry, kafka-clients, jnats, jspecify), "Apache License Version 2.0", "Apache 2", "BSD 2-Clause License", "The BSD 3-Clause License (BSD3)", "Eclipse Public License - Version 2.0", cryptacular's "Apache 2 | GNU Lesser General Public License", and the jersey-server list.
- Whether CycloneDX 2.9.1 maps these names to SPDX ids first is UNRESOLVED (U-2).
- In the same simulation, every Jersey artifact that lists 10 licences, including "jQuery license" and "W3C license", passes as soon as one name is recognised, because `licence_terms()` treats every multi-entry list as an OR.

V-15 **Build-time LGPL tools** (decisions 22 and 33):
- SpotBugs 4.10.5, spotbugs-annotations 4.10.5, FindSecBugs 1.14.0, sonar-maven-plugin 5.8.0.7211 and the three sonar-scanner-lib 4.1.2.1663 artifacts resolve exactly at the listed versions.
- They appear only in plugin closures and in no module tree.
- The enforcer bans them from compile and runtime.

## 2. ASSUMPTIONS (not proven offline; each changes a conclusion if wrong)

A-1 **Multiple `<license>` entries in a POM are read as a choice (OR)** where at least one entry is policy-allowed and the others are copyleft alternatives. This covers 26 shipped components (Jakarta APIs, HK2, Jersey core and media, Angus, logback, H2, javassist, HdrHistogram, amqp-client). The election is recorded as a CONDITION in the CSV.
- Lists of more than three entries (six Jersey artifacts) are not assumed to be OR, and stay REVIEW REQUIRED.
- Each OR reading must be confirmed against the project's own licence statement (the jar LICENSE/NOTICE, or the project site), which was not browsed.

A-2 The Spring Boot repackager includes `provided`-scope dependencies in the fat jar (Spring Boot documentation behaviour). No release module has a provided dependency today, so this changes no row now. It matters because the SBOM is generated with `-DincludeProvidedScope=false` (gap G-6).

A-3 A plugin's runtime class path was approximated by resolving the plugin as a dependency (`plugin-tree.txt`). Maven-core artifacts that the plugin declares as `provided` are excluded, as the real plugin class loader excludes them. Surefire and failsafe providers were added explicitly (surefire-junit-platform 3.5.3).

A-4 "Release signal" dates come from the POM `project.build.outputTimestamp`, the manifest `Bnd-LastModified`/`Build-Date`, or the zip entry date. 203 rows have no reliable date, because reproducible builds normalise timestamps to 1980. They are marked so in the evidence column. No web lookup was made.

A-5 The id `central-8fac3ebd6edbaca3c794783fa38088af0aa128e7` is read as a Central mirror alias written by an earlier resolver session; no `settings.xml` exists now. It is consistent with W3-B's 13:00:25Z note in DOWNLOADS-WAVE3. The same 168 files are also recorded as `central`, and their SHA-1 sidecars match.

## 3. UNRESOLVED (recorded as gaps; nothing was downloaded)

U-1 **Plugin closures not fully in the offline cache.**
- maven-site-plugin 3.12.1 (super-POM default) is missing: maven-reporting-exec 1.6.0, maven-archiver 3.5.2, plexus-archiver 4.2.7, plexus-utils 3.4.2, doxia-module-* 1.11.1, jetty 9.4.46 (server, servlet, webapp, util) and commons-text 1.3.
- pitest-maven 1.30.2 is missing: maven-core, model and artifact 3.1.0, sisu 0.0.0.M2a, guava 10.0.1 and 31.1-jre, sisu-guice 3.1.0:no_aop, plexus-classworlds 2.4.
- maven-resolver-api 1.9.23 is missing.
- These rows are REVIEW REQUIRED, with sha256 and licence UNRESOLVED.
- `dependency:resolve-plugins` fails offline (logs in w3x1-evidence).

U-2 **`org.cyclonedx:cyclonedx-maven-plugin:2.9.1`**, which the CI and release SBOM steps invoke by coordinates, is not in the cache. Its POM is absent too. Its licence, hash and closure are unknown, and how it maps licence names to SPDX ids could not be checked. The SBOM step cannot run air-gapped.

U-3 **Licence text for components without a LICENSE or NOTICE in the jar** (131 shipped). The licence was taken from the POM chain only.

U-4 **Fat-jar contents.** No `sangam-server-*.jar`, control-plane, gateway or rollup jar exists in any `target*` folder. Exclusion of MariaDB and of the build tools from the real jars is configured but not observed.

U-5 **Maven distribution and wrapper provenance.**
- The SHA-256 values were computed: apache-maven-3.9.9-bin.zip `4EC3F26F...A3C`, and the committed `.mvn/wrapper/maven-wrapper.jar` `FF7F21F2...942D`.
- `maven-wrapper.properties` sets neither `distributionSha256Sum` nor `wrapperSha256Sum`, so there is no reference value to verify against.

U-6 **Modules outside the reactor.** `sdks/kotlin` (kotlin-maven-plugin 2.0.21) and `deploy/native/sdk-worker` (native-maven-plugin 1.1.18, maven-assembly-plugin 3.7.1, Error Prone and GraalVM build tools) are not in the reactor, so they are not in this inventory.

U-7 **The 32 Quarkus 3.40.1.x POM-only entries in DOWNLOADS-WAVE3** (W3-H/B49) are not in any reactor module and are not inventoried. Per decisions 54R and 80, Quarkus is not a dependency of the core.

## 4. Copyleft, dual licences and elections

| Component (shipped unless noted) | Declared | Election / status |
|---|---|---|
| org.cryptacular:cryptacular:1.3.0 | Apache 2, GNU LGPL (no version) | Apache-2.0 elected: owner decision 90, recorded in policy. See V-6 for the wording issue |
| org.mariadb.jdbc:mariadb-java-client:3.5.11 | LGPL-2.1-or-later | Conditional LIC-5: separate lib-ext jar plus source offer (`deploy/docker/server/SOURCE-OFFER.txt`). V-5 |
| ch.qos.logback:logback-classic and logback-core 1.5.38 | EPL-2.0, LGPL-2.1-only | EPL-2.0 to elect. No election recorded anywhere |
| 20+ Jakarta / HK2 / Jersey / Angus / Expressly artifacts | EPL-2.0, GPL-2.0 WITH Classpath-exception-2.0 (some with EDL) | EPL-2.0 to elect. Not recorded. EPL-2.0 brings a weak-copyleft source-availability obligation for the EPL code distributed in object form |
| javax.xml.bind:jaxb-api:2.3.1 | CDDL-1.1, GPL-2.0 WITH CPE | CDDL-1.1 to elect (allowed). Weak copyleft |
| com.h2database:h2:2.3.232 (runtime, all fat jars) | MPL-2.0, EPL-1.0 | One of the two to elect. Both are weak copyleft, and both are allowed |
| org.javassist:javassist:3.33.0-GA | MPL-1.1, LGPL-2.1, Apache-2.0 | Apache-2.0 to elect |
| org.eclipse.paho mqttv3 1.2.5 | EPL-2.0 (POM; decision 56 log says EPL-2.0 OR EDL-1.0) | Weak copyleft. Last upstream release signal 2020-07-14 (maintenance risk) |
| com.rabbitmq:amqp-client:5.37.1 (runtime-only, sangam-flowable-ports optional) | AL 2.0, GPL v2, MPL 2.0 | Apache-2.0 to elect |
| org.hdrhistogram:HdrHistogram:2.2.2 | CC0 (public domain), BSD-2-Clause | Either option |
| Six Jersey artifacts (container-servlet, client, bean-validation, entity-filtering, spring6, hk2) | 10 entries including "jQuery license" and "W3C license" | REVIEW REQUIRED (A-1) |
| Build only: javaparser-core 3.28.2, jna and jna-platform 5.17.0 (spring-boot-maven-plugin) | LGPL OR Apache-2.0 | Apache-2.0 to elect |
| Build only: dataflow-errorprone 3.41.0-eisop1, dataflow-nullaway 4.3.1 (Error Prone / NullAway processors) | GPL-2.0 WITH Classpath-exception-2.0 only | REVIEW REQUIRED: not covered by decisions 22 and 33, which name the LGPL tools only |
| Build only: SpotBugs, FindSecBugs, sonar-* (V-15) | LGPL-2.1 / LGPL-3.0 | Accepted with conditions (decisions 22 and 33) |
| Test only: ojdbc11 23.8.0.25.04 | Oracle FUTC | REVIEW REQUIRED (V-8) |
| Test only: jna 5.13.0 (Testcontainers) | LGPL-2.1-or-later OR Apache-2.0 | Apache-2.0 to elect |

No AGPL, SSPL, BUSL, FSL, Commons-Clause, "JSON" licence or non-commercial licence appears anywhere in the resolved graph. org.json 20251224 declares Public Domain, not the forbidden "JSON" licence.

## 5. Obligations

For every distribution (each fat jar, the SDK jar and the lib-ext folder):

1. **Licence texts.** Ship every licence named in the CSV `spdx` column for that distribution. That is Apache-2.0, MIT, BSD-2/3-Clause, EPL-1.0/2.0, MPL-2.0, CDDL-1.1, EDL-1.0, CC0-1.0, UPL-1.0 and the Bouncy Castle licence, plus the GPL-2.0+CPE and LGPL texts wherever an election is not yet recorded.
2. **NOTICE.** Carry the full text of each of the 60 distinct NOTICE files listed in section 7. Apache-2.0 section 4(d) requires this for Apache-licensed components that ship a NOTICE. The aggregation can be generated from each jar's `META-INF/NOTICE*`, and the entry paths are listed per component.
3. **Source availability.**
   - MariaDB LGPL: this is in place (`SOURCE-OFFER.txt`, and the sources jar copied into `dist/lib-ext`).
   - EPL-2.0, MPL-2.0 and CDDL components: state where their source is available. This is a notice line, not a source copy, as long as Sangam does not modify those components.
4. **Elections.** Record each dual-licence election in the SBOM as an SPDX expression, and in NOTICE.
5. **SG-27 set.** Keep the FEEL set's exception record. It is already annotated into the SBOM by `check_licences.py --annotate`.
6. **Trademarks.** Do not use component names or marks beyond nominative use. The root NOTICE already does this for Operaton, Flowable and Camunda.

## 6. Gaps in the current controls

G-1 **No SBOM per distribution.**
- `licence-check.yml` and `release.yml` produce ONE aggregate CycloneDX SBOM over the whole reactor (`makeAggregateBom`).
- The release ships four fat jars (server, control-plane, gateway, rollup), sdk-java jars, the lib-ext MariaDB jar and images. None of them has its own Maven SBOM, so the aggregate cannot say what is inside which artifact.
- Image SBOMs (syft) exist.

G-2 **Fat-jar check only on the server.**
- `check_licences.py --jar` and `build_tools_not_shipped.py --jar` are run on the sangam-server jar only.
- The control-plane, gateway and rollup fat jars are released (`release.yml` "Stage artefacts") without a nested-jar check.
- The sangam-cli shaded jar is not checked either.

G-3 **PR gate is not strict.**
- `licence-check.yml` runs without `--strict`, and policy `"unknown_license": "warn"`. Unknown or unmapped licences pass on every PR and fail only at release.
- On today's graph, the simulation produces 27 strict-mode failures (V-14).

G-4 **A skipped check reports PASS.**
- The "Forbidden coordinates" step runs `mvn ... dependency:tree ... || true`. If the tree fails, no `deps.txt` is written, the `grep` finds nothing, and the step passes.
- This conflicts with decision 112: "never report a skipped check as PASS".

G-5 **Weak points in `check_licences.py`:**
- (a) Any multi-entry `licenses` list is treated as OR. AND-lists of bundled code (Jersey) pass if one name is allowed.
- (b) `normalise()` lacks common spellings (V-14), and LGPL-3.0-only/-or-later and LGPL-2.0 variants. Unlisted LGPL-3.0 spellings therefore become "unknown" (a warning) instead of "forbidden".
- (c) The `conditional` entry for GPL-3.0-or-later (veraPDF) can never match, because `term_status()` checks `forbidden_licenses` first. veraPDF passes only through its MPL-2.0 alternative. That outcome is right, but the policy entry is dead.
- (d) The policy uses ids that are not SPDX ids: `EDL-1.0` (SPDX: BSD-3-Clause), `Bouncy-Castle` (SPDX: MIT), the deprecated `GPL-2.0-with-classpath-exception` and `LGPL-2.1`, and `LicenseRef-Public-Domain`.
- (e) Nothing checks NOTICE presence or aggregation.

G-6 **SBOM scope.** `-DincludeProvidedScope=false` and `-DincludeTestScope=false` are reasonable for a shipped SBOM. However, the Boot repackager may include provided dependencies (A-2), and nothing produces a build or test SBOM. Build and test components (311 rows) are reviewed by no gate at all, which is how ojdbc11 (FUTC) reaches developer machines and CI unnoticed.

G-7 **Repository and supply-chain hardening.**
- The Shibboleth repository is not group-filtered (V-3).
- There is no `checksumPolicy=fail`, no PGP verification and no lock file or verification metadata.
- The Maven wrapper has no `distributionSha256Sum` or `wrapperSha256Sum`, and a wrapper jar binary is committed in the repository (U-5).
- `cyclonedx-maven-plugin` is not pinned in a pom and is not cached (U-2).

G-8 **Enforcer coverage.** `bannedDependencies` covers compile and runtime only, and plugins not at all (acknowledged in the pom). It has no licence-based rule, so a new LGPL or GPL compile dependency is caught only later, by the SBOM gate.

G-9 **Versions and duplicates.** No `dependencyConvergence` or `requireUpperBoundDeps` rule runs. The downgrades and splits in V-11 and V-12 (slf4j 2.0.17 against 2.0.20, HikariCP 6.3.0 under Boot 4, angus-mail 2.0.4 and 2.0.5, two Bouncy Castle providers, two Commons Logging implementations) are therefore invisible to CI.

## 7. Prioritised remediation

Proposals only. Nothing was changed by this lane. Each item needs its owner lane and, where marked, an owner decision.

| # | Action | Closes | Needs owner decision? |
|---|---|---|---|
| 1 | Add Maven SBOMs per distribution (`makeBom` per release module), and run `--jar` on every released fat jar and the cli jar | G-1, G-2 | No |
| 2 | Make the PR licence gate strict, or set policy `unknown_license: fail`. Extend `normalise()` and add an SPDX expression for each elected dual licence | G-3, G-5 | Yes: strictness |
| 3 | Remove `|| true` from the forbidden-coordinates step, and fail if `deps.txt` is missing | G-4 | No |
| 4 | Scope the Shibboleth repository to `org.opensaml` and `net.shibboleth` (Maven 3.9 resolver remote-repository filtering, or a repository manager), set `checksumPolicy=fail`, and add wrapper checksums | G-7, V-3 | No |
| 5 | Decide on ojdbc11 in test scope: move it to a CI-only profile, or record an owner decision for test use | V-8 | Yes |
| 6 | Record the elections: EPL-2.0 for Jakarta, HK2, Jersey and Angus; EPL-2.0 for logback; Apache-2.0 for javassist and amqp-client; MPL-2.0 or EPL-1.0 for H2; CDDL-1.1 for jaxb-api. Review the six 10-licence Jersey jars | A-1, section 4 | Yes |
| 7 | Generate a third-party NOTICE plus licence bundle per distribution (from the jar NOTICE entries in this report) | V-13 | No |
| 8 | Resolve the duplicates: one Bouncy Castle line (exclude bcprov-lts8on, or align jnats); drop jcl-over-slf4j or commons-logging; align the slf4j family to Boot's 2.0.20; review whether Dropwizard metrics, okhttp/kotlin-stdlib and gson/org.json are needed | V-9, V-10 | Partly: technical |
| 9 | Align the pins with the Boot 4.1.2 BOM unless a recorded reason exists (HikariCP, postgresql, mssql, h2, kafka, angus-mail, servlet-api 6.0.0, testcontainers BOM order). Add `dependencyConvergence` in report-only mode | V-4, V-11, V-12, G-9 | Per pin |
| 10 | Decide on the Error Prone and NullAway dataflow artifacts (GPL-2.0+CPE, build only), and the BSD-ambiguous build artifacts (antlr 2.7.7, asm 3.3.1, stringtemplate 3.2.1, antlr4-runtime 4.7.2, jaxen 2.0.6, jdom2), or record a build-tool exception | Section 4 | Yes |
| 11 | Fix the cryptacular condition wording to say "feature-optional, present in the default server jar" | V-6 | Yes: wording |
| 12 | Cache cyclonedx-maven-plugin 2.9.1 under decision 56 so the SBOM can run air-gapped, and pin it in the pom | U-2 | Download approval |

## 8. Generated tables
