# Maven dependency, licence and supply-chain findings (lane W3-X1)

Owner directive: decision 112 (E16), read with decisions 22, 33, 54R, 56, 70, 80, 81, 90, 94, 95 and 102 in `sangam-handoff/OWNER-DECISIONS-2026-11-12.md`.
Scope: the Java/Maven side only (the reactor in `sangam/`, its build plugins, the Maven wrapper and the BOM imports). npm, container images and runtimes belong to other lanes.
Review mode: read-only. Nothing in the working tree was edited, created or deleted. The `-Dsangam.target=target-w3x1` Maven calls created no build folder; checked at 2026-11-12T14:47:15Z. Nothing was downloaded, committed or pushed, and no test was run.

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

### 8.1 Decision lists (generated from MAVEN-INVENTORY.csv)

### REJECTED FOR DEFINED USE (0)

| gav | use | spdx | reason / conditions |
|---|---|---|---|

### REVIEW REQUIRED (38)

| gav | use | spdx | reason / conditions |
|---|---|---|---|
| com.thoughtworks.paranamer:paranamer:2.8.3 | distribution ['control-plane-fatjar', 'server-fatjar'] | BSD-3-Clause? (name "BSD" ambiguous; URL says bsd-3-clause) | licence BSD-3-Clause? (name "BSD" ambiguous; URL says bsd-3-clause) is ambiguous or unmapped; do not infer; no LICENSE/NOTICE file inside the jar: licence text must be supplied from the POM URL |
| org.glassfish.jersey.containers:jersey-container-servlet:4.0.3 | distribution ['server-fatjar'] | EPL-2.0 ; GPL-2.0-only WITH Classpath-exception-2.0 ; BSD-3-Clause (EDL-1.0) ; BSD-2-Clause ; Apache-2.0 ; LicenseRef-Public-Domain ; BSD-3-Clause ; MIT? (named "jQuery license"; not an SPDX id) ; MIT ; W3C? (W3C document-licence URL 1999-04-05; SPDX W3C-19980720 vs W3C differ) (AND/OR unstated) | POM lists 10 licences without AND/OR semantics (bundled third-party code); unproven terms: MIT? (named "jQuery license"; not an SPDX id), W3C? (W3C document-licence URL 1999-04-05; SPDX W3C-19980720 vs W3C differ); needs a per-file review of the jar's licence/NOTICE content before an election can be recorded |
| org.glassfish.jersey.core:jersey-client:4.0.3 | distribution ['server-fatjar'] | EPL-2.0 ; GPL-2.0-only WITH Classpath-exception-2.0 ; BSD-3-Clause (EDL-1.0) ; BSD-2-Clause ; Apache-2.0 ; LicenseRef-Public-Domain ; BSD-3-Clause ; MIT? (named "jQuery license"; not an SPDX id) ; MIT ; W3C? (W3C document-licence URL 1999-04-05; SPDX W3C-19980720 vs W3C differ) (AND/OR unstated) | POM lists 10 licences without AND/OR semantics (bundled third-party code); unproven terms: MIT? (named "jQuery license"; not an SPDX id), W3C? (W3C document-licence URL 1999-04-05; SPDX W3C-19980720 vs W3C differ); needs a per-file review of the jar's licence/NOTICE content before an election can be recorded |
| org.glassfish.jersey.ext:jersey-bean-validation:4.0.3 | distribution ['server-fatjar'] | EPL-2.0 ; GPL-2.0-only WITH Classpath-exception-2.0 ; BSD-3-Clause (EDL-1.0) ; BSD-2-Clause ; Apache-2.0 ; LicenseRef-Public-Domain ; BSD-3-Clause ; MIT? (named "jQuery license"; not an SPDX id) ; MIT ; W3C? (W3C document-licence URL 1999-04-05; SPDX W3C-19980720 vs W3C differ) (AND/OR unstated) | POM lists 10 licences without AND/OR semantics (bundled third-party code); unproven terms: MIT? (named "jQuery license"; not an SPDX id), W3C? (W3C document-licence URL 1999-04-05; SPDX W3C-19980720 vs W3C differ); needs a per-file review of the jar's licence/NOTICE content before an election can be recorded |
| org.glassfish.jersey.ext:jersey-entity-filtering:4.0.3 | distribution ['server-fatjar'] | EPL-2.0 ; GPL-2.0-only WITH Classpath-exception-2.0 ; BSD-3-Clause (EDL-1.0) ; BSD-2-Clause ; Apache-2.0 ; LicenseRef-Public-Domain ; BSD-3-Clause ; MIT? (named "jQuery license"; not an SPDX id) ; MIT ; W3C? (W3C document-licence URL 1999-04-05; SPDX W3C-19980720 vs W3C differ) (AND/OR unstated) | POM lists 10 licences without AND/OR semantics (bundled third-party code); unproven terms: MIT? (named "jQuery license"; not an SPDX id), W3C? (W3C document-licence URL 1999-04-05; SPDX W3C-19980720 vs W3C differ); needs a per-file review of the jar's licence/NOTICE content before an election can be recorded |
| org.glassfish.jersey.ext:jersey-spring6:4.0.3 | distribution ['server-fatjar'] | EPL-2.0 ; GPL-2.0-only WITH Classpath-exception-2.0 ; BSD-3-Clause (EDL-1.0) ; BSD-2-Clause ; Apache-2.0 ; LicenseRef-Public-Domain ; BSD-3-Clause ; MIT? (named "jQuery license"; not an SPDX id) ; MIT ; W3C? (W3C document-licence URL 1999-04-05; SPDX W3C-19980720 vs W3C differ) (AND/OR unstated) | POM lists 10 licences without AND/OR semantics (bundled third-party code); unproven terms: MIT? (named "jQuery license"; not an SPDX id), W3C? (W3C document-licence URL 1999-04-05; SPDX W3C-19980720 vs W3C differ); needs a per-file review of the jar's licence/NOTICE content before an election can be recorded |
| org.glassfish.jersey.inject:jersey-hk2:4.0.3 | distribution ['server-fatjar'] | EPL-2.0 ; GPL-2.0-only WITH Classpath-exception-2.0 ; BSD-3-Clause (EDL-1.0) ; BSD-2-Clause ; Apache-2.0 ; LicenseRef-Public-Domain ; BSD-3-Clause ; MIT? (named "jQuery license"; not an SPDX id) ; MIT ; W3C? (W3C document-licence URL 1999-04-05; SPDX W3C-19980720 vs W3C differ) (AND/OR unstated) | POM lists 10 licences without AND/OR semantics (bundled third-party code); unproven terms: MIT? (named "jQuery license"; not an SPDX id), W3C? (W3C document-licence URL 1999-04-05; SPDX W3C-19980720 vs W3C differ); needs a per-file review of the jar's licence/NOTICE content before an election can be recorded |
| antlr:antlr:2.7.7 | build | BSD-? (name "BSD License" ambiguous; variant unproven) | licence BSD-? (name "BSD License" ambiguous; variant unproven) is ambiguous or unmapped; do not infer |
| asm:asm:3.3.1 | build | BSD-? (name "BSD" ambiguous; variant unproven) | licence BSD-? (name "BSD" ambiguous; variant unproven) is ambiguous or unmapped; do not infer |
| com.oracle.database.jdbc:ojdbc11:23.8.0.25.04 | test | LicenseRef-Oracle-FUTC | forbidden component (LIC-2 Oracle JDBC is customer-supplied (bring your own driver)) present in test scope; licence-policy forbids only shipping, and no owner record found that approves downloading/using it in tests |
| commons-beanutils:commons-beanutils:1.7.0 | build | UNKNOWN | no licence declared in the POM or any parent POM in ~/.m2 |
| dom4j:dom4j:1.1 | build | UNKNOWN | no licence declared in the POM or any parent POM in ~/.m2 |
| io.github.eisop:dataflow-errorprone:3.41.0-eisop1 | build | GPL-2.0-only WITH Classpath-exception-2.0 | GPL-2.0-only WITH Classpath-exception-2.0 in build scope; not one of the owner-listed build_time_tools / LGPL exceptions (decisions 22, 33, 56) |
| jaxen:jaxen:2.0.6 | build | BSD-? (name "BSD License 2.0" ambiguous; variant unproven) | licence BSD-? (name "BSD License 2.0" ambiguous; variant unproven) is ambiguous or unmapped; do not infer |
| org.antlr:antlr4-runtime:4.7.2 | build | BSD-? (name "The BSD License" ambiguous; variant unproven) | licence BSD-? (name "The BSD License" ambiguous; variant unproven) is ambiguous or unmapped; do not infer |
| org.antlr:stringtemplate:3.2.1 | build | BSD-? (name "BSD licence" ambiguous; variant unproven) | licence BSD-? (name "BSD licence" ambiguous; variant unproven) is ambiguous or unmapped; do not infer |
| org.apache.maven.doxia:doxia-module-apt:1.11.1 | build | UNKNOWN | no licence declared in the POM or any parent POM in ~/.m2; artifact not in the offline cache (UNRESOLVED); jar NOT in offline cache: sha256 and licence text UNRESOLVED |
| org.apache.maven.doxia:doxia-module-confluence:1.11.1 | build | UNKNOWN | no licence declared in the POM or any parent POM in ~/.m2; artifact not in the offline cache (UNRESOLVED); jar NOT in offline cache: sha256 and licence text UNRESOLVED |
| org.apache.maven.doxia:doxia-module-docbook-simple:1.11.1 | build | UNKNOWN | no licence declared in the POM or any parent POM in ~/.m2; artifact not in the offline cache (UNRESOLVED); jar NOT in offline cache: sha256 and licence text UNRESOLVED |
| org.apache.maven.doxia:doxia-module-fml:1.11.1 | build | UNKNOWN | no licence declared in the POM or any parent POM in ~/.m2; artifact not in the offline cache (UNRESOLVED); jar NOT in offline cache: sha256 and licence text UNRESOLVED |
| org.apache.maven.doxia:doxia-module-markdown:1.11.1 | build | UNKNOWN | no licence declared in the POM or any parent POM in ~/.m2; artifact not in the offline cache (UNRESOLVED); jar NOT in offline cache: sha256 and licence text UNRESOLVED |
| org.apache.maven.doxia:doxia-module-twiki:1.11.1 | build | UNKNOWN | no licence declared in the POM or any parent POM in ~/.m2; artifact not in the offline cache (UNRESOLVED); jar NOT in offline cache: sha256 and licence text UNRESOLVED |
| org.apache.maven.doxia:doxia-module-xdoc:1.11.1 | build | UNKNOWN | no licence declared in the POM or any parent POM in ~/.m2; artifact not in the offline cache (UNRESOLVED); jar NOT in offline cache: sha256 and licence text UNRESOLVED |
| org.apache.maven.reporting:maven-reporting-exec:1.6.0 | build | UNKNOWN | no licence declared in the POM or any parent POM in ~/.m2; artifact not in the offline cache (UNRESOLVED); jar NOT in offline cache: sha256 and licence text UNRESOLVED |
| org.apache.maven:maven-archiver:3.5.2 | build | UNKNOWN | no licence declared in the POM or any parent POM in ~/.m2; artifact not in the offline cache (UNRESOLVED); jar NOT in offline cache: sha256 and licence text UNRESOLVED |
| org.apache.maven:maven-core:3.1.0 | build | UNKNOWN | no licence declared in the POM or any parent POM in ~/.m2; artifact not in the offline cache (UNRESOLVED); jar NOT in offline cache: sha256 and licence text UNRESOLVED |
| org.checkerframework:dataflow-nullaway:4.3.1 | build | GPL-2.0-only WITH Classpath-exception-2.0 | GPL-2.0-only WITH Classpath-exception-2.0 in build scope; not one of the owner-listed build_time_tools / LGPL exceptions (decisions 22, 33, 56) |
| org.codehaus.plexus:plexus-archiver:4.2.7 | build | UNKNOWN | no licence declared in the POM or any parent POM in ~/.m2; artifact not in the offline cache (UNRESOLVED); jar NOT in offline cache: sha256 and licence text UNRESOLVED |
| org.codehaus.plexus:plexus-i18n:1.0-beta-10 | build | UNKNOWN | no licence declared in the POM or any parent POM in ~/.m2 |
| org.codehaus.plexus:plexus-utils:3.4.2 | build | UNKNOWN | no licence declared in the POM or any parent POM in ~/.m2; artifact not in the offline cache (UNRESOLVED); jar NOT in offline cache: sha256 and licence text UNRESOLVED |
| org.cyclonedx:cyclonedx-maven-plugin:2.9.1 | build | UNKNOWN | no licence declared in the POM or any parent POM in ~/.m2; artifact not in the offline cache (UNRESOLVED); jar NOT in offline cache: sha256 and licence text UNRESOLVED |
| org.eclipse.jetty:jetty-server:9.4.46.v20220331 | build | UNKNOWN | no licence declared in the POM or any parent POM in ~/.m2; artifact not in the offline cache (UNRESOLVED); jar NOT in offline cache: sha256 and licence text UNRESOLVED |
| org.eclipse.jetty:jetty-servlet:9.4.46.v20220331 | build | UNKNOWN | no licence declared in the POM or any parent POM in ~/.m2; artifact not in the offline cache (UNRESOLVED); jar NOT in offline cache: sha256 and licence text UNRESOLVED |
| org.eclipse.jetty:jetty-util:9.4.46.v20220331 | build | UNKNOWN | no licence declared in the POM or any parent POM in ~/.m2; artifact not in the offline cache (UNRESOLVED); jar NOT in offline cache: sha256 and licence text UNRESOLVED |
| org.eclipse.jetty:jetty-webapp:9.4.46.v20220331 | build | UNKNOWN | no licence declared in the POM or any parent POM in ~/.m2; artifact not in the offline cache (UNRESOLVED); jar NOT in offline cache: sha256 and licence text UNRESOLVED |
| org.jdom:jdom2:2.0.6.1 | build | UNMAPPED ("Similar to Apache License but with the acknowledgment clause removed"; JDOM-style licence, no exact SPDX match verified) | licence UNMAPPED ("Similar to Apache License but with the acknowledgment clause removed"; JDOM-style licence, no exact SPDX match verified) is ambiguous or unmapped; do not infer |
| oro:oro:2.0.8 | build | UNKNOWN | no licence declared in the POM or any parent POM in ~/.m2 |
| uk.org.lidalia:sysout-over-slf4j:1.0.2 | build | MIT? ("MIT X11 License"; SPDX has MIT and X11 separately) | licence MIT? ("MIT X11 License"; SPDX has MIT and X11 separately) is ambiguous or unmapped; do not infer |

### ACCEPTED WITH CONDITIONS (52)

| gav | use | spdx | reason / conditions |
|---|---|---|---|
| ch.qos.logback:logback-classic:1.5.38 | distribution ['control-plane-fatjar', 'gateway-fatjar', 'rollup-fatjar', 'server-fatjar'] | EPL-2.0 OR LGPL-2.1-only | multi-licence POM (EPL-2.0 / LGPL-2.1-only); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect EPL-2.0; record the election in the SBOM (SPDX expression) and NOTICE; EPL-2.0 weak copyleft: unmodified, licence text kept, source location stated; no LICENSE/NOTICE file inside the jar: licence text must be supplied from the POM URL |
| ch.qos.logback:logback-core:1.5.38 | distribution ['control-plane-fatjar', 'gateway-fatjar', 'rollup-fatjar', 'server-fatjar'] | EPL-2.0 OR LGPL-2.1-only | multi-licence POM (EPL-2.0 / LGPL-2.1-only); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect EPL-2.0; record the election in the SBOM (SPDX expression) and NOTICE; EPL-2.0 weak copyleft: unmodified, licence text kept, source location stated; no LICENSE/NOTICE file inside the jar: licence text must be supplied from the POM URL |
| com.fasterxml.jackson.module:jackson-module-scala_2.13:2.22.3 | distribution ['control-plane-fatjar', 'server-fatjar'] | Apache-2.0 | SG-27 owner exception (SG-27 owner exception: Operaton DMN FEEL engine (JSON values of feel-engine)); exact version only (watch=true); evidence deploy/licence/SG-27-SD-301-exception.md |
| com.h2database:h2:2.3.232 | distribution ['control-plane-fatjar', 'rollup-fatjar', 'server-fatjar'] | MPL-2.0 OR EPL-1.0 | multi-licence POM (MPL-2.0 / EPL-1.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect EPL-1.0; record the election in the SBOM (SPDX expression) and NOTICE; EPL-1.0 weak copyleft: unmodified, licence text kept, source location stated; no LICENSE/NOTICE file inside the jar: licence text must be supplied from the POM URL |
| com.lihaoyi:fastparse_2.13:3.1.1 | distribution ['control-plane-fatjar', 'server-fatjar'] | MIT | SG-27 owner exception (SG-27 owner exception: Operaton DMN FEEL engine (parser of feel-engine)); exact version only (watch=true); evidence deploy/licence/SG-27-SD-301-exception.md; no LICENSE/NOTICE file inside the jar: licence text must be supplied from the POM URL |
| com.lihaoyi:geny_2.13:1.1.0 | distribution ['control-plane-fatjar', 'server-fatjar'] | MIT | SG-27 owner exception (SG-27 owner exception: Operaton DMN FEEL engine (via fastparse)); exact version only (watch=true); evidence deploy/licence/SG-27-SD-301-exception.md; no LICENSE/NOTICE file inside the jar: licence text must be supplied from the POM URL |
| com.lihaoyi:sourcecode_2.13:0.4.0 | distribution ['control-plane-fatjar', 'server-fatjar'] | MIT | SG-27 owner exception (SG-27 owner exception: Operaton DMN FEEL engine (via fastparse)); exact version only (watch=true); evidence deploy/licence/SG-27-SD-301-exception.md; no LICENSE/NOTICE file inside the jar: licence text must be supplied from the POM URL |
| com.rabbitmq:amqp-client:5.37.1 | runtime | Apache-2.0 OR GPL-2.0 (only/or-later not stated) OR MPL-2.0 | multi-licence POM (Apache-2.0 / GPL-2.0 (only/or-later not stated) / MPL-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect Apache-2.0; record the election in the SBOM (SPDX expression) and NOTICE; no LICENSE/NOTICE file inside the jar: licence text must be supplied from the POM URL |
| jakarta.annotation:jakarta.annotation-api:3.0.0 | distribution ['control-plane-fatjar', 'gateway-fatjar', 'rollup-fatjar', 'server-fatjar'] | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | multi-licence POM (EPL-2.0 / GPL-2.0-only WITH Classpath-exception-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect EPL-2.0; record the election in the SBOM (SPDX expression) and NOTICE; EPL-2.0 weak copyleft: unmodified, licence text kept, source location stated |
| jakarta.el:jakarta.el-api:6.0.1 | distribution ['control-plane-fatjar', 'server-fatjar'] | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | multi-licence POM (EPL-2.0 / GPL-2.0-only WITH Classpath-exception-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect EPL-2.0; record the election in the SBOM (SPDX expression) and NOTICE; EPL-2.0 weak copyleft: unmodified, licence text kept, source location stated |
| jakarta.jms:jakarta.jms-api:3.1.0 | distribution ['server-fatjar'] | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | multi-licence POM (EPL-2.0 / GPL-2.0-only WITH Classpath-exception-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect EPL-2.0; record the election in the SBOM (SPDX expression) and NOTICE; EPL-2.0 weak copyleft: unmodified, licence text kept, source location stated |
| jakarta.mail:jakarta.mail-api:2.1.5 | distribution ['server-fatjar'] | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 OR BSD-3-Clause (EDL-1.0) | multi-licence POM (EPL-2.0 / GPL-2.0-only WITH Classpath-exception-2.0 / BSD-3-Clause (EDL-1.0)); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect BSD-3-Clause (EDL-1.0); record the election in the SBOM (SPDX expression) and NOTICE |
| jakarta.ws.rs:jakarta.ws.rs-api:4.0.0 | distribution ['server-fatjar'] | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | multi-licence POM (EPL-2.0 / GPL-2.0-only WITH Classpath-exception-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect EPL-2.0; record the election in the SBOM (SPDX expression) and NOTICE; EPL-2.0 weak copyleft: unmodified, licence text kept, source location stated |
| javax.xml.bind:jaxb-api:2.3.1 | distribution ['control-plane-fatjar', 'rollup-fatjar', 'server-fatjar'] | CDDL-1.1 OR GPL-2.0-only WITH Classpath-exception-2.0 | multi-licence POM (CDDL-1.1 / GPL-2.0-only WITH Classpath-exception-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect CDDL-1.1; record the election in the SBOM (SPDX expression) and NOTICE; CDDL-1.1 weak copyleft: unmodified, licence text kept, source location stated |
| org.camunda.feel:feel-engine:1.22.1 | distribution ['control-plane-fatjar', 'server-fatjar'] | Apache-2.0 | SG-27 owner exception (SG-27 owner exception: Operaton DMN FEEL engine); exact version only (watch=true); evidence deploy/licence/SG-27-SD-301-exception.md; no LICENSE/NOTICE file inside the jar: licence text must be supplied from the POM URL |
| org.cryptacular:cryptacular:1.3.0 | distribution ['server-fatjar'] | Apache-2.0 OR LGPL (version not stated) | Owner decision 90 (2026-11-12): cryptacular 1.3.0 is dual-licensed Apache-2.0 OR LGPL-3.0; Sangam elects Apache-2.0. Reached only through the optional M-076 SAML relying party (OpenSAML 5.2.3). Exactly this version; any other version is re-approved.; VERIFY CONDITION WORDING: spring-security-saml2-service-provider/OpenSAML are non-optional compile dependencies of sangam-server, so cryptacular is inside the default server fat jar; "optional" in the policy can only mean feature-optional (disabled by configuration); no LICENSE/NOTICE file inside the jar: licence text must be supplied from the POM URL |
| org.eclipse.angus:angus-mail:2.0.4 | runtime | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 OR BSD-3-Clause (EDL-1.0) | multi-licence POM (EPL-2.0 / GPL-2.0-only WITH Classpath-exception-2.0 / BSD-3-Clause (EDL-1.0)); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect BSD-3-Clause (EDL-1.0); record the election in the SBOM (SPDX expression) and NOTICE |
| org.eclipse.angus:angus-mail:2.0.5 | distribution ['server-fatjar'] | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 OR BSD-3-Clause (EDL-1.0) | multi-licence POM (EPL-2.0 / GPL-2.0-only WITH Classpath-exception-2.0 / BSD-3-Clause (EDL-1.0)); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect BSD-3-Clause (EDL-1.0); record the election in the SBOM (SPDX expression) and NOTICE |
| org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5 | distribution ['server-fatjar'] | EPL-2.0 | EPL-2.0 weak copyleft: ship unmodified; keep licence text; state where the source is available |
| org.glassfish.expressly:expressly:5.0.0 | distribution ['server-fatjar'] | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | multi-licence POM (EPL-2.0 / GPL-2.0-only WITH Classpath-exception-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect EPL-2.0; record the election in the SBOM (SPDX expression) and NOTICE; EPL-2.0 weak copyleft: unmodified, licence text kept, source location stated; no LICENSE/NOTICE file inside the jar: licence text must be supplied from the POM URL |
| org.glassfish.hk2.external:aopalliance-repackaged:4.0.3 | distribution ['server-fatjar'] | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | multi-licence POM (EPL-2.0 / GPL-2.0-only WITH Classpath-exception-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect EPL-2.0; record the election in the SBOM (SPDX expression) and NOTICE; EPL-2.0 weak copyleft: unmodified, licence text kept, source location stated |
| org.glassfish.hk2:class-model:4.0.3 | distribution ['server-fatjar'] | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | multi-licence POM (EPL-2.0 / GPL-2.0-only WITH Classpath-exception-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect EPL-2.0; record the election in the SBOM (SPDX expression) and NOTICE; EPL-2.0 weak copyleft: unmodified, licence text kept, source location stated |
| org.glassfish.hk2:hk2-api:4.0.3 | distribution ['server-fatjar'] | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | multi-licence POM (EPL-2.0 / GPL-2.0-only WITH Classpath-exception-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect EPL-2.0; record the election in the SBOM (SPDX expression) and NOTICE; EPL-2.0 weak copyleft: unmodified, licence text kept, source location stated |
| org.glassfish.hk2:hk2-core:4.0.3 | distribution ['server-fatjar'] | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | multi-licence POM (EPL-2.0 / GPL-2.0-only WITH Classpath-exception-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect EPL-2.0; record the election in the SBOM (SPDX expression) and NOTICE; EPL-2.0 weak copyleft: unmodified, licence text kept, source location stated |
| org.glassfish.hk2:hk2-locator:4.0.3 | distribution ['server-fatjar'] | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | multi-licence POM (EPL-2.0 / GPL-2.0-only WITH Classpath-exception-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect EPL-2.0; record the election in the SBOM (SPDX expression) and NOTICE; EPL-2.0 weak copyleft: unmodified, licence text kept, source location stated |
| org.glassfish.hk2:hk2-runlevel:4.0.3 | distribution ['server-fatjar'] | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | multi-licence POM (EPL-2.0 / GPL-2.0-only WITH Classpath-exception-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect EPL-2.0; record the election in the SBOM (SPDX expression) and NOTICE; EPL-2.0 weak copyleft: unmodified, licence text kept, source location stated |
| org.glassfish.hk2:hk2-utils:4.0.3 | distribution ['server-fatjar'] | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | multi-licence POM (EPL-2.0 / GPL-2.0-only WITH Classpath-exception-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect EPL-2.0; record the election in the SBOM (SPDX expression) and NOTICE; EPL-2.0 weak copyleft: unmodified, licence text kept, source location stated |
| org.glassfish.hk2:hk2:4.0.3 | distribution ['server-fatjar'] | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | multi-licence POM (EPL-2.0 / GPL-2.0-only WITH Classpath-exception-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect EPL-2.0; record the election in the SBOM (SPDX expression) and NOTICE; EPL-2.0 weak copyleft: unmodified, licence text kept, source location stated |
| org.glassfish.hk2:osgi-resource-locator:3.0.0 | distribution ['server-fatjar'] | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | multi-licence POM (EPL-2.0 / GPL-2.0-only WITH Classpath-exception-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect EPL-2.0; record the election in the SBOM (SPDX expression) and NOTICE; EPL-2.0 weak copyleft: unmodified, licence text kept, source location stated |
| org.glassfish.hk2:spring-bridge:4.0.3 | distribution ['server-fatjar'] | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | multi-licence POM (EPL-2.0 / GPL-2.0-only WITH Classpath-exception-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect EPL-2.0; record the election in the SBOM (SPDX expression) and NOTICE; EPL-2.0 weak copyleft: unmodified, licence text kept, source location stated |
| org.glassfish.jersey.core:jersey-common:4.0.3 | distribution ['server-fatjar'] | EPL-2.0 ; GPL-2.0-only WITH Classpath-exception-2.0 ; Apache-2.0 ; LicenseRef-Public-Domain (AND/OR unstated) | POM lists 4 licences without AND/OR semantics; every term is policy-allowed or a GPL+CPE alternative; record the EPL-2.0 election and carry all listed licence texts |
| org.glassfish.jersey.core:jersey-server:4.0.3 | distribution ['server-fatjar'] | EPL-2.0 ; GPL-2.0-only WITH Classpath-exception-2.0 ; Apache-2.0 ; BSD-3-Clause (AND/OR unstated) | POM lists 4 licences without AND/OR semantics; every term is policy-allowed or a GPL+CPE alternative; record the EPL-2.0 election and carry all listed licence texts |
| org.glassfish.jersey.media:jersey-media-json-jackson:4.0.3 | distribution ['server-fatjar'] | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 OR Apache-2.0 | multi-licence POM (EPL-2.0 / GPL-2.0-only WITH Classpath-exception-2.0 / Apache-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect Apache-2.0; record the election in the SBOM (SPDX expression) and NOTICE |
| org.hdrhistogram:HdrHistogram:2.2.2 | distribution ['control-plane-fatjar', 'gateway-fatjar', 'rollup-fatjar', 'server-fatjar'] | CC0-1.0 OR BSD-2-Clause | multi-licence POM (CC0-1.0 / BSD-2-Clause); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect BSD-2-Clause; record the election in the SBOM (SPDX expression) and NOTICE |
| org.javassist:javassist:3.33.0-GA | distribution ['server-fatjar'] | MPL-1.1 OR LGPL-2.1 (only/or-later not stated) OR Apache-2.0 | multi-licence POM (MPL-1.1 / LGPL-2.1 (only/or-later not stated) / Apache-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect Apache-2.0; record the election in the SBOM (SPDX expression) and NOTICE; no LICENSE/NOTICE file inside the jar: licence text must be supplied from the POM URL |
| org.mariadb.jdbc:mariadb-java-client:3.5.11 | distribution ['lib-ext separate jar (excluded from sangam-control-plane fat jar)', 'lib-ext separate jar (excluded from sangam-rollup fat jar)', 'lib-ext separate jar (excluded from sangam-server fat jar)'] | LGPL-2.1-or-later | LIC-5: separate replaceable jar in /opt/sangam/lib-ext with source offer; never inside the fat jar (checked by --jar); also: runtime dependency of the published sangam-server POM (plain jar consumers inherit it unless excluded, as sangam-spring-boot-starter does); no LICENSE/NOTICE file inside the jar: licence text must be supplied from the POM URL |
| org.scala-lang:scala-library:2.13.18 | distribution ['control-plane-fatjar', 'server-fatjar'] | Apache-2.0 | SG-27 owner exception (SG-27 owner exception: Operaton DMN FEEL engine (runtime of feel-engine)); exact version only (watch=true); evidence deploy/licence/SG-27-SD-301-exception.md |
| com.github.javaparser:javaparser-core:3.28.2 | build | LGPL (version not stated) OR Apache-2.0 | multi-licence POM (LGPL (version not stated) / Apache-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect Apache-2.0; record the election in the SBOM (SPDX expression) and NOTICE |
| com.github.spotbugs:spotbugs-annotations:4.10.5 | build | LGPL-2.1 (only/or-later not stated) | build-time tool only (owner 2026-11-12: build-time only, never shipped); exact version 4.10.5; never in a shipped scope, fat jar or image (build_time_tools (owner 2026-11-12): spotbugs-annotations (LGPL-2.1, via SpotBugs) is build-time only, never shipped) |
| com.github.spotbugs:spotbugs:4.10.5 | build | LGPL-2.1 (only/or-later not stated) | build-time tool only (owner 2026-11-12: build-time tool, never shipped); exact version 4.10.5; never in a shipped scope, fat jar or image (build_time_tools (owner 2026-11-12): SpotBugs is an LGPL-2.1 build-time tool, never shipped) |
| com.h3xstream.findsecbugs:findsecbugs-plugin:1.14.0 | build | LGPL (version not stated) | build-time tool only (owner 2026-11-12: build-time tool, never shipped); exact version 1.14.0; never in a shipped scope, fat jar or image (build_time_tools (owner 2026-11-12): FindSecBugs is an LGPL-3.0 build-time tool, never shipped) |
| jakarta.servlet:jakarta.servlet-api:6.0.0 | build(provided) | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | multi-licence POM (EPL-2.0 / GPL-2.0-only WITH Classpath-exception-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect EPL-2.0; record the election in the SBOM (SPDX expression) and NOTICE |
| jakarta.servlet:jakarta.servlet-api:6.1.0 | build(provided) | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | multi-licence POM (EPL-2.0 / GPL-2.0-only WITH Classpath-exception-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect EPL-2.0; record the election in the SBOM (SPDX expression) and NOTICE |
| net.java.dev.jna:jna-platform:5.17.0 | build | LGPL-2.1-or-later OR Apache-2.0 | multi-licence POM (LGPL-2.1-or-later / Apache-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect Apache-2.0; record the election in the SBOM (SPDX expression) and NOTICE |
| net.java.dev.jna:jna:5.13.0 | test | LGPL-2.1-or-later OR Apache-2.0 | multi-licence POM (LGPL-2.1-or-later / Apache-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect Apache-2.0; record the election in the SBOM (SPDX expression) and NOTICE |
| net.java.dev.jna:jna:5.17.0 | build | LGPL-2.1-or-later OR Apache-2.0 | multi-licence POM (LGPL-2.1-or-later / Apache-2.0); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect Apache-2.0; record the election in the SBOM (SPDX expression) and NOTICE |
| org.apache.maven.wrapper:maven-wrapper:3.1.1 | build | Apache-2.0 | build tool only; a binary jar is committed in the repository and no wrapperSha256Sum is set: record and verify its checksum |
| org.apache.maven:apache-maven:3.9.9:bin | build | Apache-2.0 | build tool only; maven-wrapper.properties has no distributionSha256Sum, so the wrapper does not verify the download: add the checksum |
| org.sonarsource.scanner.lib:sonar-scanner-download-cache:4.1.2.1663 | build | LGPL-3.0 (only/or-later not stated) | build-time tool only (owner 2026-11-12: build-time only, never shipped); exact version 4.1.2.1663; never in a shipped scope, fat jar or image (build_time_tools (owner 2026-11-12): sonar-scanner-download-cache (LGPL-3.0, via sonar-maven-plugin) is build-time only, never shipped) |
| org.sonarsource.scanner.lib:sonar-scanner-java-library-batch-interface:4.1.2.1663 | build | LGPL-3.0 (only/or-later not stated) | build-time tool only (owner 2026-11-12: build-time only, never shipped); exact version 4.1.2.1663; never in a shipped scope, fat jar or image (build_time_tools (owner 2026-11-12): sonar-scanner-java-library-batch-interface (LGPL-3.0, via sonar-maven-plugin) is build-time only, never shipped) |
| org.sonarsource.scanner.lib:sonar-scanner-java-library:4.1.2.1663 | build | LGPL-3.0 (only/or-later not stated) | build-time tool only (owner 2026-11-12: build-time only, never shipped); exact version 4.1.2.1663; never in a shipped scope, fat jar or image (build_time_tools (owner 2026-11-12): sonar-scanner-java-library (LGPL-3.0, via sonar-maven-plugin) is build-time only, never shipped) |
| org.sonarsource.scanner.maven:sonar-maven-plugin:5.8.0.7211 | build | LGPL-3.0 (only/or-later not stated) | build-time tool only (owner 2026-11-12: build-time tool, never shipped); exact version 5.8.0.7211; never in a shipped scope, fat jar or image (build_time_tools (owner 2026-11-12): sonar-maven-plugin is an LGPL-3.0 build-time tool, never shipped) |


### Generated: one artifact resolved at more than one version

| group:artifact | version -> where (module:scope) |
|---|---|
| com.fasterxml.jackson.core:jackson-annotations | **2.21** (1): plugin:spring-boot-maven-plugin-4.1.2:build<br>**2.22** (35): plugin:pitest-maven-1.30.2:build, sangam-ai:compile, sangam-billing:compile, sangam-case:compile, sangam-cell:compile, sangam-channels:compile ... |
| com.github.luben:zstd-jni | **1.5.5-11** (2): plugin:maven-jar-plugin-3.4.2:build, plugin:spotbugs-maven-plugin-4.10.5.0:build<br>**1.5.6-3** (1): plugin:maven-dependency-plugin-3.8.1:build<br>**1.5.6-4** (4): sangam-flowable-ports:runtime, sangam-integration:runtime, sangam-server:runtime, sangam-spring-boot-starter:runtime |
| com.google.code.findbugs:jsr305 | **1.3.9** (1): plugin:pitest-maven-1.30.2:build<br>**3.0.2** (6): plugin:nullaway-0.14.3:build, plugin:spotbugs-4.10.5:build, plugin:spotbugs-maven-plugin-4.10.5.0:build, plugin:spring-boot-maven-plugin-4.1.2:build, sangam-server:compile, sangam-spring-boot-starter:compile |
| com.google.code.gson:gson | **2.13.2** (4): plugin:sonar-maven-plugin-5.8.0.7211:build, sangam-integration:compile, sangam-server:compile, sangam-spring-boot-starter:compile<br>**2.14.0** (2): plugin:spotbugs-4.10.5:build, plugin:spotbugs-maven-plugin-4.10.5.0:build |
| com.google.errorprone:error_prone_annotations | **2.11.0** (1): plugin:nullaway-0.14.3:build<br>**2.41.0** (4): plugin:sonar-maven-plugin-5.8.0.7211:build, sangam-integration:compile, sangam-server:compile, sangam-spring-boot-starter:compile<br>**2.48.0** (2): plugin:spotbugs-4.10.5:build, plugin:spotbugs-maven-plugin-4.10.5.0:build<br>**2.52.0** (1): plugin:error_prone_core-2.52.0:build |
| com.google.guava:failureaccess | **1.0.1** (1): plugin:nullaway-0.14.3:build<br>**1.0.2** (2): sangam-server:compile, sangam-spring-boot-starter:compile<br>**1.0.3** (1): plugin:error_prone_core-2.52.0:build |
| com.google.guava:guava | **10.0.1** (1): plugin:pitest-maven-1.30.2:build<br>**31.1-jre** (1): plugin:nullaway-0.14.3:build<br>**33.0.0-jre** (2): sangam-server:compile, sangam-spring-boot-starter:compile<br>**33.7.2-jre** (1): plugin:error_prone_core-2.52.0:build |
| com.google.j2objc:j2objc-annotations | **1.3** (1): plugin:nullaway-0.14.3:build<br>**2.8** (2): sangam-server:compile, sangam-spring-boot-starter:compile<br>**3.1** (1): plugin:error_prone_core-2.52.0:build |
| com.thoughtworks.qdox:qdox | **2.0.3** (1): plugin:pitest-maven-1.30.2:build<br>**2.2.0** (3): plugin:maven-compiler-plugin-3.14.0:build, plugin:maven-failsafe-plugin-3.5.3:build, plugin:maven-surefire-plugin-3.5.3:build |
| commons-beanutils:commons-beanutils | **1.11.0** (1): plugin:pitest-maven-1.30.2:build<br>**1.7.0** (1): plugin:maven-site-plugin-3.12.1:build<br>**1.9.4** (2): plugin:maven-dependency-plugin-3.8.1:build, plugin:spotbugs-maven-plugin-4.10.5.0:build |
| commons-codec:commons-codec | **1.11** (2): plugin:maven-site-plugin-3.12.1:build, plugin:pitest-maven-1.30.2:build<br>**1.16.1** (2): plugin:maven-jar-plugin-3.4.2:build, plugin:spotbugs-maven-plugin-4.10.5.0:build<br>**1.17.0** (2): plugin:maven-dependency-plugin-3.8.1:build, plugin:maven-shade-plugin-3.6.0:build<br>**1.17.1** (1): plugin:spring-boot-maven-plugin-4.1.2:build<br>**1.18.0** (1): plugin:maven-enforcer-plugin-3.6.1:build<br>**1.19.0** (1): plugin:sonar-maven-plugin-5.8.0.7211:build<br>**1.21.0** (2): sangam-server:compile, sangam-spring-boot-starter:compile |
| commons-io:commons-io | **2.11.0** (3): plugin:maven-compiler-plugin-3.14.0:build, plugin:maven-jar-plugin-3.4.2:build, plugin:maven-resources-plugin-3.3.1:build<br>**2.16.1** (3): plugin:maven-dependency-plugin-3.8.1:build, plugin:maven-shade-plugin-3.6.0:build, plugin:spring-boot-maven-plugin-4.1.2:build<br>**2.19.0** (15): plugin:jacoco-maven-plugin-0.8.16:build, plugin:maven-enforcer-plugin-3.6.1:build, sangam-ai:provided, sangam-control-plane:compile, sangam-engine:compile, sangam-integration:provided ...<br>**2.20.0** (1): plugin:sonar-maven-plugin-5.8.0.7211:build<br>**2.21.0** (1): plugin:spotbugs-maven-plugin-4.10.5.0:build<br>**2.22.0** (1): plugin:spotbugs-4.10.5:build<br>**2.6** (3): plugin:maven-clean-plugin-3.2.0:build, plugin:maven-site-plugin-3.12.1:build, plugin:pitest-maven-1.30.2:build |
| commons-logging:commons-logging | **1.2** (3): plugin:maven-dependency-plugin-3.8.1:build, plugin:maven-site-plugin-3.12.1:build, plugin:spotbugs-maven-plugin-4.10.5.0:build<br>**1.3.5** (2): plugin:pitest-maven-1.30.2:build, plugin:spring-boot-maven-plugin-4.1.2:build<br>**1.3.6** (24): sangam-ai:test, sangam-billing:compile, sangam-case:compile, sangam-cell:compile, sangam-channels:compile, sangam-compliance:compile ... |
| io.micrometer:micrometer-commons | **1.16.8** (1): plugin:spring-boot-maven-plugin-4.1.2:build<br>**1.17.2** (15): sangam-ai:test, sangam-billing:compile, sangam-control-plane:compile, sangam-field:compile, sangam-gateway:compile, sangam-geo:compile ... |
| io.micrometer:micrometer-observation | **1.16.8** (1): plugin:spring-boot-maven-plugin-4.1.2:build<br>**1.17.2** (15): sangam-ai:test, sangam-billing:compile, sangam-control-plane:compile, sangam-field:compile, sangam-gateway:compile, sangam-geo:compile ... |
| jakarta.servlet:jakarta.servlet-api | **6.0.0** (1): sangam-integration:provided<br>**6.1.0** (4): sangam-field:provided, sangam-geo:provided, sangam-rest:provided, sangam-security:provided |
| net.java.dev.jna:jna | **5.13.0** (2): sangam-ops:test, sangam-server:test<br>**5.17.0** (1): plugin:spring-boot-maven-plugin-4.1.2:build |
| org.apache.commons:commons-compress | **1.24.0** (2): sangam-ops:test, sangam-server:test<br>**1.26.1** (2): plugin:maven-jar-plugin-3.4.2:build, plugin:spotbugs-maven-plugin-4.10.5.0:build<br>**1.26.2** (2): plugin:maven-dependency-plugin-3.8.1:build, plugin:maven-shade-plugin-3.6.0:build<br>**1.27.1** (1): plugin:spring-boot-maven-plugin-4.1.2:build<br>**1.28.0** (1): plugin:sonar-maven-plugin-5.8.0.7211:build |
| org.apache.commons:commons-lang3 | **3.12.0** (1): plugin:maven-resources-plugin-3.3.1:build<br>**3.14.0** (2): plugin:maven-jar-plugin-3.4.2:build, plugin:maven-shade-plugin-3.6.0:build<br>**3.16.0** (1): plugin:spring-boot-maven-plugin-4.1.2:build<br>**3.17.0** (1): plugin:maven-dependency-plugin-3.8.1:build<br>**3.18.0** (3): plugin:maven-enforcer-plugin-3.6.1:build, plugin:pitest-maven-1.30.2:build, plugin:sonar-maven-plugin-5.8.0.7211:build<br>**3.20.0** (13): sangam-ai:provided, sangam-control-plane:compile, sangam-engine:compile, sangam-integration:provided, sangam-onboarding:provided, sangam-ops:test ...<br>**3.21.0** (2): plugin:spotbugs-4.10.5:build, plugin:spotbugs-maven-plugin-4.10.5.0:build<br>**3.8.1** (1): plugin:maven-site-plugin-3.12.1:build |
| org.apache.commons:commons-text | **1.12.0** (1): plugin:maven-dependency-plugin-3.8.1:build<br>**1.13.1** (13): sangam-ai:provided, sangam-control-plane:compile, sangam-engine:compile, sangam-integration:provided, sangam-onboarding:provided, sangam-ops:test ...<br>**1.14.0** (1): plugin:pitest-maven-1.30.2:build<br>**1.15.0** (2): plugin:spotbugs-4.10.5:build, plugin:spotbugs-maven-plugin-4.10.5.0:build<br>**1.3** (1): plugin:maven-site-plugin-3.12.1:build |
| org.apache.logging.log4j:log4j-api | **2.25.5** (5): sangam-control-plane:compile, sangam-gateway:compile, sangam-rollup:compile, sangam-server:compile, sangam-spring-boot-starter:compile<br>**2.26.1** (2): plugin:spotbugs-4.10.5:build, plugin:spotbugs-maven-plugin-4.10.5.0:build |
| org.apache.logging.log4j:log4j-to-slf4j | **2.25.5** (5): sangam-control-plane:compile, sangam-gateway:compile, sangam-rollup:compile, sangam-server:compile, sangam-spring-boot-starter:compile<br>**2.26.1** (1): plugin:spotbugs-maven-plugin-4.10.5.0:build |
| org.apache.maven:maven-archiver | **3.5.2** (1): plugin:maven-site-plugin-3.12.1:build<br>**3.6.2** (3): plugin:maven-dependency-plugin-3.8.1:build, plugin:maven-jar-plugin-3.4.2:build, plugin:spotbugs-maven-plugin-4.10.5.0:build |
| org.apache.maven:maven-artifact | **3.0** (1): plugin:maven-site-plugin-3.12.1:build<br>**3.1.0** (1): plugin:pitest-maven-1.30.2:build |
| org.apache.maven:maven-model | **2.2.1** (1): plugin:maven-site-plugin-3.12.1:build<br>**3.1.0** (1): plugin:pitest-maven-1.30.2:build |
| org.apache.maven:maven-plugin-api | **2.2.1** (1): plugin:maven-site-plugin-3.12.1:build<br>**3.1.0** (1): plugin:pitest-maven-1.30.2:build |
| org.apache.maven.doxia:doxia-core | **1.11.1** (2): plugin:maven-site-plugin-3.12.1:build, plugin:pitest-maven-1.30.2:build<br>**2.0.0** (1): plugin:maven-dependency-plugin-3.8.1:build<br>**2.1.0** (1): plugin:spotbugs-maven-plugin-4.10.5.0:build |
| org.apache.maven.doxia:doxia-integration-tools | **1.11.1** (1): plugin:maven-site-plugin-3.12.1:build<br>**2.0.0** (2): plugin:maven-dependency-plugin-3.8.1:build, plugin:spotbugs-maven-plugin-4.10.5.0:build |
| org.apache.maven.doxia:doxia-module-apt | **1.11.1** (1): plugin:maven-site-plugin-3.12.1:build<br>**2.0.0** (2): plugin:maven-dependency-plugin-3.8.1:build, plugin:spotbugs-maven-plugin-4.10.5.0:build |
| org.apache.maven.doxia:doxia-module-xdoc | **1.11.1** (1): plugin:maven-site-plugin-3.12.1:build<br>**2.0.0** (2): plugin:maven-dependency-plugin-3.8.1:build, plugin:spotbugs-maven-plugin-4.10.5.0:build |
| org.apache.maven.doxia:doxia-module-xhtml5 | **1.11.1** (2): plugin:maven-site-plugin-3.12.1:build, plugin:pitest-maven-1.30.2:build<br>**2.0.0** (2): plugin:maven-dependency-plugin-3.8.1:build, plugin:spotbugs-maven-plugin-4.10.5.0:build |
| org.apache.maven.doxia:doxia-sink-api | **1.0** (1): plugin:jacoco-maven-plugin-0.8.16:build<br>**1.11.1** (2): plugin:maven-site-plugin-3.12.1:build, plugin:pitest-maven-1.30.2:build<br>**2.0.0** (1): plugin:maven-dependency-plugin-3.8.1:build<br>**2.1.0** (1): plugin:spotbugs-maven-plugin-4.10.5.0:build |
| org.apache.maven.doxia:doxia-site-renderer | **1.11.1** (2): plugin:maven-site-plugin-3.12.1:build, plugin:pitest-maven-1.30.2:build<br>**2.0.0** (2): plugin:maven-dependency-plugin-3.8.1:build, plugin:spotbugs-maven-plugin-4.10.5.0:build |
| org.apache.maven.doxia:doxia-skin-model | **1.11.1** (2): plugin:maven-site-plugin-3.12.1:build, plugin:pitest-maven-1.30.2:build<br>**2.0.0** (2): plugin:maven-dependency-plugin-3.8.1:build, plugin:spotbugs-maven-plugin-4.10.5.0:build |
| org.apache.maven.reporting:maven-reporting-api | **3.0** (1): plugin:jacoco-maven-plugin-0.8.16:build<br>**3.1.0** (1): plugin:pitest-maven-1.30.2:build<br>**3.1.1** (1): plugin:maven-site-plugin-3.12.1:build<br>**4.0.0** (2): plugin:maven-dependency-plugin-3.8.1:build, plugin:spotbugs-maven-plugin-4.10.5.0:build |
| org.apache.maven.reporting:maven-reporting-impl | **3.1.0** (1): plugin:pitest-maven-1.30.2:build<br>**4.0.0** (2): plugin:maven-dependency-plugin-3.8.1:build, plugin:spotbugs-maven-plugin-4.10.5.0:build |
| org.apache.maven.resolver:maven-resolver-api | **1.4.1** (5): plugin:maven-dependency-plugin-3.8.1:build, plugin:maven-failsafe-plugin-3.5.3:build, plugin:maven-surefire-plugin-3.5.3:build, plugin:spotbugs-maven-plugin-4.10.5.0:build, plugin:spring-boot-maven-plugin-4.1.2:build<br>**1.9.18** (2): plugin:maven-deploy-plugin-3.1.2:build, plugin:maven-install-plugin-3.1.2:build<br>**1.9.23** (1): plugin:maven-enforcer-plugin-3.6.1:build |
| org.apache.maven.resolver:maven-resolver-util | **1.4.1** (4): plugin:maven-dependency-plugin-3.8.1:build, plugin:maven-failsafe-plugin-3.5.3:build, plugin:maven-surefire-plugin-3.5.3:build, plugin:spring-boot-maven-plugin-4.1.2:build<br>**1.9.18** (2): plugin:maven-deploy-plugin-3.1.2:build, plugin:maven-install-plugin-3.1.2:build<br>**1.9.23** (1): plugin:maven-enforcer-plugin-3.6.1:build |
| org.apache.maven.shared:file-management | **3.1.0** (1): plugin:maven-jar-plugin-3.4.2:build<br>**3.2.0** (1): plugin:jacoco-maven-plugin-0.8.16:build |
| org.apache.maven.shared:maven-common-artifact-filters | **3.1.1** (1): plugin:pitest-maven-1.30.2:build<br>**3.4.0** (4): plugin:maven-dependency-plugin-3.8.1:build, plugin:maven-failsafe-plugin-3.5.3:build, plugin:maven-surefire-plugin-3.5.3:build, plugin:spring-boot-maven-plugin-4.1.2:build |
| org.apache.maven.shared:maven-shared-utils | **3.3.3** (1): plugin:pitest-maven-1.30.2:build<br>**3.3.4** (2): plugin:maven-clean-plugin-3.2.0:build, plugin:maven-site-plugin-3.12.1:build<br>**3.4.2** (3): plugin:maven-compiler-plugin-3.14.0:build, plugin:maven-dependency-plugin-3.8.1:build, plugin:spotbugs-maven-plugin-4.10.5.0:build |
| org.apache.maven.surefire:maven-surefire-common | **3.2.5** (1): plugin:pitest-maven-1.30.2:build<br>**3.5.3** (2): plugin:maven-failsafe-plugin-3.5.3:build, plugin:maven-surefire-plugin-3.5.3:build |
| org.apache.maven.surefire:surefire-api | **3.2.5** (1): plugin:pitest-maven-1.30.2:build<br>**3.5.3** (3): plugin:maven-failsafe-plugin-3.5.3:build, plugin:maven-surefire-plugin-3.5.3:build, plugin:surefire-junit-platform-3.5.3:build |
| org.apache.maven.surefire:surefire-booter | **3.2.5** (1): plugin:pitest-maven-1.30.2:build<br>**3.5.3** (2): plugin:maven-failsafe-plugin-3.5.3:build, plugin:maven-surefire-plugin-3.5.3:build |
| org.apache.maven.surefire:surefire-extensions-api | **3.2.5** (1): plugin:pitest-maven-1.30.2:build<br>**3.5.3** (2): plugin:maven-failsafe-plugin-3.5.3:build, plugin:maven-surefire-plugin-3.5.3:build |
| org.apache.maven.surefire:surefire-extensions-spi | **3.2.5** (1): plugin:pitest-maven-1.30.2:build<br>**3.5.3** (2): plugin:maven-failsafe-plugin-3.5.3:build, plugin:maven-surefire-plugin-3.5.3:build |
| org.apache.maven.surefire:surefire-logger-api | **3.2.5** (1): plugin:pitest-maven-1.30.2:build<br>**3.5.3** (3): plugin:maven-failsafe-plugin-3.5.3:build, plugin:maven-surefire-plugin-3.5.3:build, plugin:surefire-junit-platform-3.5.3:build |
| org.apache.maven.surefire:surefire-shared-utils | **3.2.5** (1): plugin:pitest-maven-1.30.2:build<br>**3.5.3** (3): plugin:maven-failsafe-plugin-3.5.3:build, plugin:maven-surefire-plugin-3.5.3:build, plugin:surefire-junit-platform-3.5.3:build |
| org.apache.velocity:velocity-engine-core | **2.4** (2): plugin:maven-dependency-plugin-3.8.1:build, plugin:spotbugs-maven-plugin-4.10.5.0:build<br>**2.4.1** (2): sangam-server:compile, sangam-spring-boot-starter:compile |
| org.bouncycastle:bcprov-jdk18on | **1.84** (2): sangam-server:compile, sangam-spring-boot-starter:compile<br>**1.85.2** (1): plugin:sonar-maven-plugin-5.8.0.7211:build |
| org.checkerframework:checker-qual | **3.41.0** (2): sangam-server:compile, sangam-spring-boot-starter:compile<br>**4.2.3** (1): plugin:spotbugs-maven-plugin-4.10.5.0:build<br>**4.3.1** (1): plugin:nullaway-0.14.3:build |
| org.codehaus.plexus:plexus-archiver | **4.10.0** (1): plugin:maven-dependency-plugin-3.8.1:build<br>**4.2.7** (1): plugin:maven-site-plugin-3.12.1:build<br>**4.9.2** (2): plugin:maven-jar-plugin-3.4.2:build, plugin:spotbugs-maven-plugin-4.10.5.0:build |
| org.codehaus.plexus:plexus-classworlds | **2.4** (1): plugin:pitest-maven-1.30.2:build<br>**2.6.0** (3): plugin:maven-dependency-plugin-3.8.1:build, plugin:maven-site-plugin-3.12.1:build, plugin:spotbugs-maven-plugin-4.10.5.0:build |
| org.codehaus.plexus:plexus-component-annotations | **2.0.0** (2): plugin:maven-dependency-plugin-3.8.1:build, plugin:pitest-maven-1.30.2:build<br>**2.1.0** (2): plugin:maven-site-plugin-3.12.1:build, plugin:spotbugs-maven-plugin-4.10.5.0:build |
| org.codehaus.plexus:plexus-interpolation | **1.26** (2): plugin:maven-resources-plugin-3.3.1:build, plugin:maven-site-plugin-3.12.1:build<br>**1.27** (3): plugin:maven-dependency-plugin-3.8.1:build, plugin:maven-jar-plugin-3.4.2:build, plugin:spotbugs-maven-plugin-4.10.5.0:build |
| org.codehaus.plexus:plexus-io | **3.4.2** (2): plugin:maven-jar-plugin-3.4.2:build, plugin:spotbugs-maven-plugin-4.10.5.0:build<br>**3.5.1** (1): plugin:maven-dependency-plugin-3.8.1:build |
| org.codehaus.plexus:plexus-java | **1.2.0** (1): plugin:pitest-maven-1.30.2:build<br>**1.4.0** (3): plugin:maven-compiler-plugin-3.14.0:build, plugin:maven-failsafe-plugin-3.5.3:build, plugin:maven-surefire-plugin-3.5.3:build |
| org.codehaus.plexus:plexus-utils | **3.0.24** (1): plugin:jacoco-maven-plugin-0.8.16:build<br>**3.4.1** (1): plugin:sonar-maven-plugin-5.8.0.7211:build<br>**3.4.2** (2): plugin:maven-jar-plugin-3.4.2:build, plugin:maven-site-plugin-3.12.1:build<br>**3.5.1** (2): plugin:maven-resources-plugin-3.3.1:build, plugin:maven-shade-plugin-3.6.0:build<br>**4.0.1** (5): plugin:maven-compiler-plugin-3.14.0:build, plugin:maven-dependency-plugin-3.8.1:build, plugin:maven-deploy-plugin-3.1.2:build, plugin:maven-install-plugin-3.1.2:build, plugin:spotbugs-maven-plugin-4.10.5.0:build<br>**4.0.2** (1): plugin:maven-enforcer-plugin-3.6.1:build<br>**4.0.3** (1): plugin:pitest-maven-1.30.2:build |
| org.codehaus.plexus:plexus-velocity | **1.2** (2): plugin:maven-site-plugin-3.12.1:build, plugin:pitest-maven-1.30.2:build<br>**2.2.0** (2): plugin:maven-dependency-plugin-3.8.1:build, plugin:spotbugs-maven-plugin-4.10.5.0:build |
| org.codehaus.plexus:plexus-xml | **3.0.0** (3): plugin:maven-compiler-plugin-3.14.0:build, plugin:maven-deploy-plugin-3.1.2:build, plugin:maven-install-plugin-3.1.2:build<br>**3.0.1** (3): plugin:maven-dependency-plugin-3.8.1:build, plugin:maven-enforcer-plugin-3.6.1:build, plugin:spotbugs-maven-plugin-4.10.5.0:build<br>**3.0.2** (1): plugin:pitest-maven-1.30.2:build |
| org.eclipse.angus:angus-activation | **2.0.2** (2): sangam-channels:runtime, sangam-integration:runtime<br>**2.0.3** (9): sangam-ai:runtime, sangam-compliance:runtime, sangam-connectors-health:runtime, sangam-modeller:runtime, sangam-ops:runtime, sangam-rest:runtime ... |
| org.eclipse.angus:angus-mail | **2.0.4** (2): sangam-channels:compile, sangam-integration:compile<br>**2.0.5** (9): sangam-ai:compile, sangam-compliance:compile, sangam-connectors-health:compile, sangam-modeller:compile, sangam-ops:compile, sangam-rest:compile ... |
| org.eclipse.sisu:org.eclipse.sisu.inject | **0.0.0.M2a** (1): plugin:pitest-maven-1.30.2:build<br>**0.9.0.M3** (2): plugin:maven-dependency-plugin-3.8.1:build, plugin:spotbugs-maven-plugin-4.10.5.0:build |
| org.eclipse.sisu:org.eclipse.sisu.plexus | **0.0.0.M2a** (1): plugin:pitest-maven-1.30.2:build<br>**0.9.0.M3** (2): plugin:maven-dependency-plugin-3.8.1:build, plugin:spotbugs-maven-plugin-4.10.5.0:build |
| org.jetbrains:annotations | **13.0** (2): sangam-server:runtime, sangam-spring-boot-starter:runtime<br>**17.0.0** (1): sangam-ops:test |
| org.junit.platform:junit-platform-commons | **1.12.1** (1): plugin:surefire-junit-platform-3.5.3:build<br>**6.0.3** (32): sangam-ai:test, sangam-billing:test, sangam-case:test, sangam-channels:test, sangam-cli:test, sangam-compliance:test ... |
| org.junit.platform:junit-platform-engine | **1.12.1** (1): plugin:surefire-junit-platform-3.5.3:build<br>**6.0.3** (32): sangam-ai:test, sangam-billing:test, sangam-case:test, sangam-channels:test, sangam-cli:test, sangam-compliance:test ... |
| org.junit.platform:junit-platform-launcher | **1.12.1** (1): plugin:surefire-junit-platform-3.5.3:build<br>**6.0.3** (1): sangam-packs:test |
| org.ow2.asm:asm | **9.11** (3): plugin:pitest-maven-1.30.2:build, plugin:spotbugs-4.10.5:build, plugin:spotbugs-maven-plugin-4.10.5.0:build<br>**9.11.1** (1): plugin:jacoco-maven-plugin-0.8.16:build<br>**9.7** (2): plugin:maven-dependency-plugin-3.8.1:build, plugin:maven-shade-plugin-3.6.0:build<br>**9.7.1** (7): plugin:maven-compiler-plugin-3.14.0:build, plugin:maven-failsafe-plugin-3.5.3:build, plugin:maven-surefire-plugin-3.5.3:build, sangam-control-plane:test, sangam-rollup:test, sangam-server:test ... |
| org.ow2.asm:asm-commons | **9.10.1** (2): sangam-server:compile, sangam-spring-boot-starter:compile<br>**9.11** (3): plugin:pitest-maven-1.30.2:build, plugin:spotbugs-4.10.5:build, plugin:spotbugs-maven-plugin-4.10.5.0:build<br>**9.11.1** (1): plugin:jacoco-maven-plugin-0.8.16:build<br>**9.7** (1): plugin:maven-shade-plugin-3.6.0:build |
| org.ow2.asm:asm-tree | **9.11** (3): plugin:pitest-maven-1.30.2:build, plugin:spotbugs-4.10.5:build, plugin:spotbugs-maven-plugin-4.10.5.0:build<br>**9.11.1** (1): plugin:jacoco-maven-plugin-0.8.16:build<br>**9.7** (1): plugin:maven-shade-plugin-3.6.0:build |
| org.slf4j:jul-to-slf4j | **1.7.12** (1): plugin:pitest-maven-1.30.2:build<br>**2.0.20** (5): sangam-control-plane:compile, sangam-gateway:compile, sangam-rollup:compile, sangam-server:compile, sangam-spring-boot-starter:compile |
| org.slf4j:slf4j-api | **1.7.12** (1): plugin:pitest-maven-1.30.2:build<br>**1.7.36** (9): plugin:jacoco-maven-plugin-0.8.16:build, plugin:maven-compiler-plugin-3.14.0:build, plugin:maven-dependency-plugin-3.8.1:build, plugin:maven-failsafe-plugin-3.5.3:build, plugin:maven-jar-plugin-3.4.2:build, plugin:maven-resources-plugin-3.3.1:build ...<br>**2.0.17** (30): sangam-ai:compile, sangam-billing:compile, sangam-case:compile, sangam-cell:compile, sangam-channels:compile, sangam-compliance:compile ...<br>**2.0.20** (2): plugin:spotbugs-4.10.5:build, plugin:spotbugs-maven-plugin-4.10.5.0:build |
| org.yaml:snakeyaml | **2.6** (21): sangam-ai:compile, sangam-billing:compile, sangam-channels:compile, sangam-compliance:compile, sangam-config:compile, sangam-connectors-health:compile ...<br>**2.7** (1): plugin:error_prone_core-2.52.0:build |

### Generated: Maven nearest-wins mediation in shipped scopes (requested -> resolved)

| group:artifact | requested | resolved | modules |
|---|---|---|---|
| com.google.errorprone:error_prone_annotations | 2.23.0 | 2.41.0 | sangam-server, sangam-spring-boot-starter |
| commons-io:commons-io | 2.22.0 | 2.19.0 | sangam-server, sangam-spring-boot-starter |
| org.bouncycastle:bcpkix-jdk18on | 1.82 | 1.84 | sangam-server, sangam-spring-boot-starter |
| org.bouncycastle:bcprov-jdk18on | 1.82 | 1.84 | sangam-server, sangam-spring-boot-starter |
| org.eclipse.angus:angus-mail | 2.0.5 | 2.0.4 | sangam-channels |

### Generated: versions overridden by dependencyManagement / BOMs in shipped scopes (library asked -> managed)

| group:artifact | asked | managed to | modules |
|---|---|---|---|
| com.fasterxml.jackson.core:jackson-annotations | 2.21 | 2.22 | sangam-control-plane, sangam-gateway, sangam-rollup, sangam-server ... |
| com.fasterxml.jackson.core:jackson-core | 2.12.7 | 2.22.3 | sangam-server, sangam-spring-boot-starter |
| com.fasterxml.jackson.core:jackson-databind | 2.12.7.2 | 2.22.3 | sangam-server, sangam-spring-boot-starter |
| com.fasterxml.jackson.core:jackson-databind | 2.17.1 | 2.22.3 | sangam-ai, sangam-channels, sangam-compliance, sangam-connectors-health ... |
| com.fasterxml.jackson.core:jackson-databind | 2.21.4 | 2.22.3 | sangam-server, sangam-spring-boot-starter |
| com.fasterxml.jackson.core:jackson-databind | 2.21.7 | 2.22.3 | sangam-control-plane, sangam-server, sangam-spring-boot-starter |
| com.fasterxml.jackson.core:jackson-databind | 2.22.4 | 2.22.3 | sangam-server, sangam-spring-boot-starter |
| com.fasterxml.jackson.datatype:jackson-datatype-jdk8 | 2.21.7 | 2.22.3 | sangam-server, sangam-spring-boot-starter |
| com.fasterxml.jackson.datatype:jackson-datatype-jsr310 | 2.21.4 | 2.22.3 | sangam-server, sangam-spring-boot-starter |
| com.fasterxml.jackson.datatype:jackson-datatype-jsr310 | 2.21.7 | 2.22.3 | sangam-server, sangam-spring-boot-starter |
| com.fasterxml.jackson.datatype:jackson-datatype-jsr310 | 2.22.4 | 2.22.3 | sangam-server, sangam-spring-boot-starter |
| com.zaxxer:HikariCP | 7.0.2 | 6.3.0 | sangam-control-plane, sangam-rollup, sangam-server, sangam-spring-boot-starter |
| commons-codec:commons-codec | 1.16.1 | 1.21.0 | sangam-server, sangam-spring-boot-starter |
| commons-codec:commons-codec | 1.18.0 | 1.21.0 | sangam-server, sangam-spring-boot-starter |
| commons-logging:commons-logging | 1.3.5 | 1.3.6 | sangam-integration, sangam-server, sangam-spring-boot-starter |
| commons-logging:commons-logging | 1.4.0 | 1.3.6 | sangam-integration, sangam-server, sangam-spring-boot-starter |
| io.micrometer:micrometer-observation | 1.16.8 | 1.17.2 | sangam-control-plane, sangam-field, sangam-gateway, sangam-geo ... |
| jakarta.activation:jakarta.activation-api | 2.1.0 | 2.1.4 | sangam-server, sangam-spring-boot-starter |
| jakarta.activation:jakarta.activation-api | 2.1.3 | 2.1.4 | sangam-channels, sangam-integration |
| jakarta.xml.bind:jakarta.xml.bind-api | 3.0.1 | 4.0.5 | sangam-server, sangam-spring-boot-starter |
| org.apache.commons:commons-lang3 | 3.17.0 | 3.20.0 | sangam-control-plane, sangam-engine, sangam-persistence, sangam-rollup ... |
| org.apache.commons:commons-lang3 | 3.21.0 | 3.20.0 | sangam-server, sangam-spring-boot-starter |
| org.apache.httpcomponents.client5:httpclient5 | 5.3.1 | 5.6.5 | sangam-server, sangam-spring-boot-starter |
| org.apache.httpcomponents.core5:httpcore5 | 5.2.5 | 5.4.4 | sangam-server, sangam-spring-boot-starter |
| org.eclipse.angus:angus-mail | 2.0.4 | 2.0.5 | sangam-channels, sangam-rest, sangam-server, sangam-spring-boot-starter |
| org.hibernate.validator:hibernate-validator | 8.0.5.Final | 9.1.5.Final | sangam-server, sangam-spring-boot-starter |
| org.jetbrains.kotlin:kotlin-stdlib | 2.2.20 | 2.3.21 | sangam-server, sangam-spring-boot-starter |
| org.jspecify:jspecify | 1.0.0 | 1.0.1 | sangam-flowable-ports, sangam-integration, sangam-server, sangam-spring-boot-starter |
| org.scala-lang:scala-library | 2.13.14 | 2.13.18 | sangam-ai, sangam-case, sangam-cell, sangam-channels ... |
| org.scala-lang:scala-library | 2.13.8 | 2.13.18 | sangam-ai, sangam-case, sangam-cell, sangam-channels ... |
| org.slf4j:jcl-over-slf4j | 1.7.36 | 2.0.20 | sangam-ai, sangam-channels, sangam-compliance, sangam-connectors-health ... |
| org.slf4j:slf4j-api | 1.7.25 | 2.0.17 | sangam-ai, sangam-case, sangam-cell, sangam-channels ... |
| org.slf4j:slf4j-api | 1.7.36 | 2.0.17 | sangam-ai, sangam-case, sangam-cell, sangam-channels ... |
| org.slf4j:slf4j-api | 2.0.13 | 2.0.17 | sangam-ai, sangam-channels, sangam-compliance, sangam-connectors-health ... |
| org.slf4j:slf4j-api | 2.0.20 | 2.0.17 | sangam-ai, sangam-case, sangam-cell, sangam-channels ... |
| org.yaml:snakeyaml | 2.4 | 2.6 | sangam-server, sangam-spring-boot-starter |
| org.yaml:snakeyaml | 2.5 | 2.6 | sangam-ai, sangam-ops, sangam-rest, sangam-server ... |

### Generated: NOTICE files inside jars that ship in a release fat jar (deduplicated by content)

#### NOTICE ae9f252069f0: carried for 2 component(s)

Components: com.fasterxml.jackson.core:jackson-annotations:2.22 (META-INF/NOTICE); com.fasterxml.jackson.core:jackson-databind:2.22.3 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 14 non-empty lines):

```text
# Jackson JSON processor
Jackson is a high-performance, Free/Open Source JSON processing library.
It was originally written by Tatu Saloranta (tatu.saloranta@iki.fi), and has
been in development since 2007.
It is currently developed by a community of developers.
## Copyright
[...]
```

#### NOTICE 085ba4a82445: carried for 1 component(s)

Components: com.fasterxml.jackson.core:jackson-core:2.22.3 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 27 non-empty lines):

```text
# Jackson JSON processor
Jackson is a high-performance, Free/Open Source JSON processing library.
It was originally written by Tatu Saloranta (tatu.saloranta@iki.fi), and has
been in development since 2007.
It is currently developed by a community of developers.
## Copyright
[...]
```

#### NOTICE 3654b49f0f8e: carried for 1 component(s)

Components: com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.22.3 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 14 non-empty lines):

```text
# Jackson JSON processor
Jackson is a high-performance, Free/Open Source JSON processing library.
It was originally written by Tatu Saloranta (tatu.saloranta@iki.fi), and has
been in development since 2007.
It is currently developed by a community of developers.
## Copyright
[...]
```

#### NOTICE da5301b68390: carried for 3 component(s)

Components: com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.22.3 (META-INF/NOTICE); com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.3 (META-INF/NOTICE); com.fasterxml.jackson.module:jackson-module-parameter-names:2.22.3 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 12 non-empty lines):

```text
# Jackson JSON processor
Jackson is a high-performance, Free/Open Source JSON processing library.
It was originally written by Tatu Saloranta (tatu.saloranta@iki.fi), and has
been in development since 2007.
It is currently developed by a community of developers.
## Licensing
[...]
```

#### NOTICE 43fd0f0fd821: carried for 1 component(s)

Components: com.fasterxml.jackson.jakarta.rs:jackson-jakarta-rs-json-provider:2.22.3 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 15 non-empty lines):

```text
# Jackson JSON processor
Jackson is a high-performance, Free/Open Source JSON processing library.
It was originally written by Tatu Saloranta (tatu.saloranta@iki.fi), and has
been in development since 2007.
It is currently developed by a community of developers, as well as supported
commercially by FasterXML.com.
[...]
```

#### NOTICE fe30ca533a0e: carried for 1 component(s)

Components: com.fasterxml.uuid:java-uuid-generator:5.2.0 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 4 non-empty lines):

```text
Java UUID generator library has been written by Tatu Saloranta (tatu.saloranta@iki.fi)
Other developers who have contributed code are:
* Eric Bie contributed extensive unit test suite which has helped ensure high implementation
  quality
```

#### NOTICE 1a8c67aaade2: carried for 1 component(s)

Components: com.fasterxml:classmate:1.7.3 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 5 non-empty lines):

```text
Java ClassMate library was originally written by Tatu Saloranta (tatu.saloranta@iki.fi)
Other developers who have contributed code are:
* Brian Langel
## Copyright
Copyright 2007-, Tatu Saloranta (tatu.saloranta@iki.fi)
```

#### NOTICE cad671f84bfc: carried for 1 component(s)

Components: commons-codec:commons-codec:1.21.0 (META-INF/NOTICE.txt)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 4 non-empty lines):

```text
Apache Commons Codec
Copyright 2002-2026 The Apache Software Foundation
This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

#### NOTICE ea19a5683487: carried for 1 component(s)

Components: commons-fileupload:commons-fileupload:1.6.0 (META-INF/NOTICE.txt)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 4 non-empty lines):

```text
Apache Commons FileUpload
Copyright 2002-2025 The Apache Software Foundation
This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

#### NOTICE 773282bdc406: carried for 1 component(s)

Components: commons-io:commons-io:2.19.0 (META-INF/NOTICE.txt)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 4 non-empty lines):

```text
Apache Commons IO
Copyright 2002-2025 The Apache Software Foundation
This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

#### NOTICE 4efe7026c74a: carried for 1 component(s)

Components: commons-logging:commons-logging:1.3.6 (META-INF/NOTICE.txt)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 4 non-empty lines):

```text
Apache Commons Logging
Copyright 2001-2026 The Apache Software Foundation
This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

#### NOTICE bea7ecbd4557: carried for 1 component(s)

Components: io.micrometer:context-propagation:1.2.1 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 32 non-empty lines):

```text
Micrometer Context Propagation
Copyright (c) 2017-Present VMware, Inc. All Rights Reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
   https://www.apache.org/licenses/LICENSE-2.0
[...]
```

#### NOTICE 3fd84e244f4f: carried for 6 component(s)

Components: io.micrometer:micrometer-commons:1.17.2 (META-INF/NOTICE); io.micrometer:micrometer-core:1.17.2 (META-INF/NOTICE); io.micrometer:micrometer-jakarta9:1.17.2 (META-INF/NOTICE); io.micrometer:micrometer-observation:1.17.2 (META-INF/NOTICE); io.micrometer:micrometer-registry-otlp:1.17.2 (META-INF/NOTICE); io.micrometer:micrometer-registry-prometheus:1.17.2 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 32 non-empty lines):

```text
Micrometer
Copyright (c) 2017-Present VMware, Inc. All Rights Reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
   https://www.apache.org/licenses/LICENSE-2.0
[...]
```

#### NOTICE fd84c0c135c6: carried for 2 component(s)

Components: io.micrometer:micrometer-tracing-bridge-otel:1.7.2 (META-INF/NOTICE); io.micrometer:micrometer-tracing:1.7.2 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 32 non-empty lines):

```text
Micrometer Tracing
Copyright (c) 2017-Present VMware, Inc. All Rights Reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
   https://www.apache.org/licenses/LICENSE-2.0
[...]
```

#### NOTICE de46fc2a1cff: carried for 5 component(s)

Components: io.netty:netty-codec-native-quic:4.2.19.Final:linux-aarch_64 (META-INF/license/NOTICE.harmony.txt); io.netty:netty-codec-native-quic:4.2.19.Final:linux-x86_64 (META-INF/license/NOTICE.harmony.txt); io.netty:netty-codec-native-quic:4.2.19.Final:osx-aarch_64 (META-INF/license/NOTICE.harmony.txt); io.netty:netty-codec-native-quic:4.2.19.Final:osx-x86_64 (META-INF/license/NOTICE.harmony.txt); io.netty:netty-codec-native-quic:4.2.19.Final:windows-x86_64 (META-INF/license/NOTICE.harmony.txt)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 4 non-empty lines):

```text
Apache Harmony
Copyright 2006, 2010 The Apache Software Foundation.
This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

#### NOTICE b875e6ab7e46: carried for 5 component(s)

Components: io.netty:netty-codec-native-quic:4.2.19.Final:linux-aarch_64 (META-INF/NOTICE.txt); io.netty:netty-codec-native-quic:4.2.19.Final:linux-x86_64 (META-INF/NOTICE.txt); io.netty:netty-codec-native-quic:4.2.19.Final:osx-aarch_64 (META-INF/NOTICE.txt); io.netty:netty-codec-native-quic:4.2.19.Final:osx-x86_64 (META-INF/NOTICE.txt); io.netty:netty-codec-native-quic:4.2.19.Final:windows-x86_64 (META-INF/NOTICE.txt)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 207 non-empty lines):

```text
The Netty Project
                            =================
Please visit the Netty web site for more information:
  * https://netty.io/
Copyright 2014 The Netty Project
The Netty Project licenses this file to you under the Apache License,
[...]
```

#### NOTICE 9d80664e28e6: carried for 1 component(s)

Components: jakarta.activation:jakarta.activation-api:2.1.4 (META-INF/NOTICE.md)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 57 non-empty lines):

```text
# Notices for Jakarta Activation
This content is produced and maintained by the Jakarta Activation project.
* Project home: https://projects.eclipse.org/projects/ee4j.jaf
## Trademarks
Jakarta Activation is a trademark of the Eclipse Foundation.
## Copyright
[...]
```

#### NOTICE 6db57df0d73a: carried for 1 component(s)

Components: jakarta.annotation:jakarta.annotation-api:3.0.0 (META-INF/NOTICE.md)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 28 non-empty lines):

```text
# Notices for Jakarta Annotations
This content is produced and maintained by the Jakarta Annotations project.
* Project home: https://projects.eclipse.org/projects/ee4j.ca
## Trademarks
Jakarta Annotations™ is a trademark of the Eclipse Foundation.
## Copyright
[...]
```

#### NOTICE 2504c3bcebab: carried for 1 component(s)

Components: jakarta.el:jakarta.el-api:6.0.1 (META-INF/NOTICE.md)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 30 non-empty lines):

```text
# Notices for Jakarta Expression Language
This content is produced and maintained by the Jakarta Expression Language project.
* Project home: https://projects.eclipse.org/projects/ee4j.el
## Trademarks
Jakarta Expression Language is a trademark of the Eclipse
Foundation.
[...]
```

#### NOTICE 992ed62dd3ee: carried for 1 component(s)

Components: jakarta.inject:jakarta.inject-api:2.0.1 (META-INF/NOTICE.md)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 24 non-empty lines):

```text
# Notices for Eclipse Jakarta Dependency Injection
This content is produced and maintained by the Eclipse Jakarta Dependency Injection project.
* Project home: https://projects.eclipse.org/projects/cdi.batch
## Trademarks
Jakarta Dependency Injection is a trademark of the Eclipse Foundation.
## Copyright
[...]
```

#### NOTICE 91e35d84c23d: carried for 1 component(s)

Components: jakarta.jms:jakarta.jms-api:3.1.0 (META-INF/NOTICE.md)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 30 non-empty lines):

```text
# Notices for the Jakarta Messaging project
This content is produced and maintained by the Jakarta Messaging project.
* Project home: https://projects.eclipse.org/projects/ee4j.messaging
## Trademarks
Eclipse Project for JMS is a trademark of the Eclipse Foundation.
## Copyright
[...]
```

#### NOTICE 2c1f3f79908a: carried for 1 component(s)

Components: jakarta.mail:jakarta.mail-api:2.1.5 (META-INF/NOTICE.md)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 48 non-empty lines):

```text
# Notices for Jakarta Mail
This content is produced and maintained by the Jakarta Mail project.
* Project home: [https://projects.eclipse.org/projects/ee4j.mail](https://projects.eclipse.org/projects/ee4j.mail)
## Trademarks
Jakarta Mail is a trademark of the Eclipse Foundation.
## Copyright
[...]
```

#### NOTICE 1982508fa811: carried for 1 component(s)

Components: jakarta.ws.rs:jakarta.ws.rs-api:4.0.0 (META-INF/NOTICE.md)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 38 non-empty lines):

```text
# Notices for Jakarta RESTful Web Services
This content is produced and maintained by the **Jakarta RESTful Web Services**
project.
* Project home: https://projects.eclipse.org/projects/ee4j.jaxrs
## Trademarks
**Jakarta RESTful Web Services** is a trademark of the Eclipse Foundation.
[...]
```

#### NOTICE 01b9159f1e6f: carried for 1 component(s)

Components: jakarta.xml.bind:jakarta.xml.bind-api:4.0.5 (META-INF/NOTICE.md)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 32 non-empty lines):

```text
[//]: # " Copyright (c) 2018, 2024 Oracle and/or its affiliates. All rights reserved. "
[//]: # "  "
[//]: # " This program and the accompanying materials are made available under the "
[//]: # " terms of the Eclipse Distribution License v. 1.0, which is available at "
[//]: # " http://www.eclipse.org/org/documents/edl-v10.php. "
[//]: # "  "
[...]
```

#### NOTICE 59b72b201e4d: carried for 1 component(s)

Components: joda-time:joda-time:2.15.0 (META-INF/NOTICE.txt)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 5 non-empty lines):

```text
=============================================================================
= NOTICE file corresponding to section 4d of the Apache License Version 2.0 =
=============================================================================
This product includes software developed by
Joda.org (https://www.joda.org/).
```

#### NOTICE e0f7e27e59ba: carried for 1 component(s)

Components: org.apache.commons:commons-collections4:4.5.0 (META-INF/NOTICE.txt)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 4 non-empty lines):

```text
Apache Commons Collections
Copyright 2001-2025 The Apache Software Foundation
This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

#### NOTICE be2013abc70b: carried for 1 component(s)

Components: org.apache.commons:commons-lang3:3.20.0 (META-INF/NOTICE.txt)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 4 non-empty lines):

```text
Apache Commons Lang
Copyright 2001-2025 The Apache Software Foundation
This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

#### NOTICE 814243c4f509: carried for 1 component(s)

Components: org.apache.commons:commons-pool2:2.13.1 (META-INF/NOTICE.txt)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 4 non-empty lines):

```text
Apache Commons Pool
Copyright 2001-2025 The Apache Software Foundation
This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

#### NOTICE 7fd843f19560: carried for 1 component(s)

Components: org.apache.commons:commons-text:1.13.1 (META-INF/NOTICE.txt)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 4 non-empty lines):

```text
Apache Commons Text
Copyright 2014-2025 The Apache Software Foundation
This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

#### NOTICE 03753d60b82d: carried for 1 component(s)

Components: org.apache.httpcomponents.client5:httpclient5-cache:5.6.5 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 4 non-empty lines):

```text
Apache HttpClient Cache
Copyright 2010-2021 The Apache Software Foundation
This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
```

#### NOTICE 77f8b1ab1840: carried for 1 component(s)

Components: org.apache.httpcomponents.client5:httpclient5:5.6.5 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 4 non-empty lines):

```text
Apache HttpClient
Copyright 1999-2021 The Apache Software Foundation
This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
```

#### NOTICE ec3e3c77f665: carried for 1 component(s)

Components: org.apache.httpcomponents.core5:httpcore5-h2:5.4.4 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 4 non-empty lines):

```text
Apache HttpComponents Core HTTP/2
Copyright 2005-2021 The Apache Software Foundation
This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
```

#### NOTICE c72be29cb094: carried for 1 component(s)

Components: org.apache.httpcomponents.core5:httpcore5:5.4.4 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 4 non-empty lines):

```text
Apache HttpComponents Core HTTP/1.1
Copyright 2005-2021 The Apache Software Foundation
This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
```

#### NOTICE d10d19ff9c68: carried for 1 component(s)

Components: org.apache.kafka:kafka-clients:3.9.2 (NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 17 non-empty lines):

```text
Apache Kafka
Copyright 2026 The Apache Software Foundation.
This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
This distribution has a binary dependency on jersey, which is available under the CDDL
License. The source code of jersey can be found at https://github.com/jersey/jersey/.
[...]
```

#### NOTICE b19a6c215fba: carried for 1 component(s)

Components: org.apache.logging.log4j:log4j-api:2.25.5 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 4 non-empty lines):

```text
Apache Log4j API
Copyright 1999-2026 The Apache Software Foundation
This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
```

#### NOTICE 0351bac977a6: carried for 1 component(s)

Components: org.apache.logging.log4j:log4j-to-slf4j:2.25.5 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 4 non-empty lines):

```text
Log4j API to SLF4J Adapter
Copyright 1999-2026 The Apache Software Foundation
This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
```

#### NOTICE c72ffec943f2: carried for 1 component(s)

Components: org.apache.pdfbox:fontbox:3.0.8 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 8 non-empty lines):

```text
Apache FontBox
Copyright 2008-2026 The Apache Software Foundation
This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
Based on source code contributed to the original FontBox project.
Copyright (c) 2006-2007, www.fontbox.org
[...]
```

#### NOTICE 69667c9be39a: carried for 1 component(s)

Components: org.apache.pdfbox:pdfbox-io:3.0.8 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 4 non-empty lines):

```text
Apache PDFBox io
Copyright 2002-2026 The Apache Software Foundation
This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
```

#### NOTICE c35da37bf574: carried for 1 component(s)

Components: org.apache.pdfbox:pdfbox:3.0.8 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 17 non-empty lines):

```text
Apache PDFBox
Copyright 2002-2026 The Apache Software Foundation
This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
Based on source code contributed to the original PDFBox project.
Copyright (c) 2002-2007, www.pdfbox.org
[...]
```

#### NOTICE 264648d4e8a0: carried for 1 component(s)

Components: org.apache.santuario:xmlsec:3.0.6 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 4 non-empty lines):

```text
Apache XML Security for Java
Copyright 2000-2025 The Apache Software Foundation
This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
```

#### NOTICE 3b51e06f197b: carried for 2 component(s)

Components: org.apache.sshd:sshd-common:2.15.0 (META-INF/NOTICE); org.apache.sshd:sshd-sftp:2.15.0 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 4 non-empty lines):

```text
Apache MINA SSHD
Copyright 2018-2025 The Apache Software Foundation
This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
```

#### NOTICE becf221e2496: carried for 1 component(s)

Components: org.apache.sshd:sshd-core:2.15.0 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 4 non-empty lines):

```text
Apache MINA SSHD
Copyright 2008-2025 The Apache Software Foundation
This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
```

#### NOTICE 9c9bec88192b: carried for 1 component(s)

Components: org.apache.tomcat.embed:tomcat-embed-core:11.0.27 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 28 non-empty lines):

```text
Apache Tomcat
Copyright 1999-2026 The Apache Software Foundation
This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
The original XML Schemas for Java EE Deployment Descriptors:
 - javaee_5.xsd
[...]
```

#### NOTICE a7ecc7b28381: carried for 2 component(s)

Components: org.apache.tomcat.embed:tomcat-embed-el:11.0.27 (META-INF/NOTICE); org.apache.tomcat.embed:tomcat-embed-websocket:11.0.27 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 4 non-empty lines):

```text
Apache Tomcat
Copyright 1999-2026 The Apache Software Foundation
This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
```

#### NOTICE c88b17d98c19: carried for 1 component(s)

Components: org.apache.velocity:velocity-engine-core:2.4.1 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 4 non-empty lines):

```text
Apache Velocity - Engine
Copyright 2000-2024 The Apache Software Foundation
This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
```

#### NOTICE 203414df1bdc: carried for 1 component(s)

Components: org.eclipse.angus:angus-activation:2.0.3 (META-INF/NOTICE.md)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 28 non-empty lines):

```text
# Notices for Eclipse Angus
This content is produced and maintained by the Eclipse Angus project.
* Project home: https://projects.eclipse.org/projects/ee4j.angus
## Trademarks
Eclipse Angus is a trademark of the Eclipse Foundation.
## Copyright
[...]
```

#### NOTICE 29895af1e6a3: carried for 1 component(s)

Components: org.eclipse.angus:angus-mail:2.0.5 (META-INF/NOTICE.md)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 31 non-empty lines):

```text
# Notices for Jakarta Mail
This content is produced and maintained by the Jakarta Mail project.
* Project home: https://projects.eclipse.org/projects/ee4j.mail
## Trademarks
Jakarta Mail is a trademark of the Eclipse Foundation.
## Copyright
[...]
```

#### NOTICE a54ace272590: carried for 9 component(s)

Components: org.glassfish.hk2.external:aopalliance-repackaged:4.0.3 (META-INF/NOTICE.md); org.glassfish.hk2:class-model:4.0.3 (META-INF/NOTICE.md); org.glassfish.hk2:hk2-api:4.0.3 (META-INF/NOTICE.md); org.glassfish.hk2:hk2-core:4.0.3 (META-INF/NOTICE.md); org.glassfish.hk2:hk2-locator:4.0.3 (META-INF/NOTICE.md); org.glassfish.hk2:hk2-runlevel:4.0.3 (META-INF/NOTICE.md); org.glassfish.hk2:hk2-utils:4.0.3 (META-INF/NOTICE.md); org.glassfish.hk2:hk2:4.0.3 (META-INF/NOTICE.md); org.glassfish.hk2:spring-bridge:4.0.3 (META-INF/NOTICE.md)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 38 non-empty lines):

```text
# Notices for Eclipse GlassFish
This content is produced and maintained by the Eclipse GlassFish project.
* Project home: https://projects.eclipse.org/projects/ee4j.glassfish
## Trademarks
Eclipse GlassFish, and GlassFish are trademarks of the Eclipse Foundation.
## Copyright
[...]
```

#### NOTICE 5a9735059b0d: carried for 1 component(s)

Components: org.glassfish.hk2:osgi-resource-locator:3.0.0 (META-INF/NOTICE.md)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 36 non-empty lines):

```text
# Notices for Eclipse GlassFish
This content is produced and maintained by the Eclipse GlassFish project.
* Project home: https://projects.eclipse.org/projects/ee4j.glassfish
## Trademarks
Eclipse GlassFish, and GlassFish are trademarks of the Eclipse Foundation.
## Copyright
[...]
```

#### NOTICE 066ffb05907b: carried for 9 component(s)

Components: org.glassfish.jersey.containers:jersey-container-servlet:4.0.3 (META-INF/NOTICE.md); org.glassfish.jersey.core:jersey-client:4.0.3 (META-INF/NOTICE.md); org.glassfish.jersey.core:jersey-common:4.0.3 (META-INF/NOTICE.md); org.glassfish.jersey.core:jersey-server:4.0.3 (META-INF/NOTICE.md); org.glassfish.jersey.ext:jersey-bean-validation:4.0.3 (META-INF/NOTICE.md); org.glassfish.jersey.ext:jersey-entity-filtering:4.0.3 (META-INF/NOTICE.md); org.glassfish.jersey.ext:jersey-spring6:4.0.3 (META-INF/NOTICE.md); org.glassfish.jersey.inject:jersey-hk2:4.0.3 (META-INF/NOTICE.md); org.glassfish.jersey.media:jersey-media-json-jackson:4.0.3 (META-INF/NOTICE.md)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 90 non-empty lines):

```text
# Notice for Jersey
This content is produced and maintained by the Eclipse Jersey project.
*  Project home: https://projects.eclipse.org/projects/ee4j.jersey
## Trademarks
Eclipse Jersey is a trademark of the Eclipse Foundation.
## Copyright
[...]
```

#### NOTICE deebe038a37d: carried for 1 component(s)

Components: org.glassfish.jersey.core:jersey-common:4.0.3 (META-INF/NOTICE.markdown)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 31 non-empty lines):

```text
# Notice for Jersey Core Common module
This content is produced and maintained by the Eclipse Jersey project.
* https://projects.eclipse.org/projects/ee4j.jersey
## Trademarks
Eclipse Jersey is a trademark of the Eclipse Foundation.
## Copyright
[...]
```

#### NOTICE 2f022fafdb8c: carried for 1 component(s)

Components: org.glassfish.jersey.core:jersey-server:4.0.3 (META-INF/NOTICE.markdown)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 32 non-empty lines):

```text
# Notice for Jersey Core Server module
This content is produced and maintained by the Eclipse Jersey project.
* https://projects.eclipse.org/projects/ee4j.jersey
## Trademarks
Eclipse Jersey is a trademark of the Eclipse Foundation.
## Copyright
[...]
```

#### NOTICE 5391e11c5820: carried for 1 component(s)

Components: org.glassfish.jersey.ext:jersey-bean-validation:4.0.3 (META-INF/NOTICE.markdown)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 26 non-empty lines):

```text
# Notice for Jersey Bean Validation module
This content is produced and maintained by the Eclipse Jersey project.
* https://projects.eclipse.org/projects/ee4j.jersey
## Trademarks
Eclipse Jersey is a trademark of the Eclipse Foundation.
## Copyright
[...]
```

#### NOTICE 12a58116d517: carried for 1 component(s)

Components: org.glassfish.jersey.media:jersey-media-json-jackson:4.0.3 (META-INF/NOTICE.markdown)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 26 non-empty lines):

```text
# Notice for Jersey Json Jackson module
This content is produced and maintained by the Eclipse Jersey project.
* https://projects.eclipse.org/projects/ee4j.jersey
## Trademarks
Eclipse Jersey is a trademark of the Eclipse Foundation.
## Copyright
[...]
```

#### NOTICE 4d4d25502878: carried for 1 component(s)

Components: org.mybatis:mybatis:3.5.19 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 61 non-empty lines):

```text
MyBatis
Copyright 2010-2023
This product includes software developed by
The MyBatis Team (https://www.mybatis.org/).
iBATIS
   This product includes software developed by
[...]
```

#### NOTICE 213674b2d3ef: carried for 1 component(s)

Components: org.scala-lang:scala-library:2.13.18 (NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 13 non-empty lines):

```text
Scala
Copyright (c) 2002-2025 EPFL
Copyright (c) 2011-2025 Lightbend, Inc. dba Akka
Scala includes software developed at
LAMP/EPFL (https://lamp.epfl.ch/) and
Akka (https://akka.io/).
[...]
```

#### NOTICE 33ed49a8e578: carried for 46 component(s)

Components: org.springframework.boot:spring-boot-actuator-autoconfigure:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-actuator:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-autoconfigure:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-health:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-http-codec:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-http-converter:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-jackson2:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-jackson:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-jdbc:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-jersey:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-micrometer-metrics:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-micrometer-observation:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-micrometer-tracing:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-netty:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-opentelemetry:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-persistence:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-reactor-netty:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-reactor:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-security-oauth2-resource-server:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-security:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-servlet:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-sql:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-starter-actuator:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-starter-jackson:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-starter-jdbc:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-starter-jersey:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-starter-logging:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-starter-micrometer-metrics:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-starter-opentelemetry:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-starter-reactor-netty:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-starter-security-oauth2-resource-server:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-starter-security:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-starter-tomcat-runtime:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-starter-tomcat:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-starter-validation:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-starter-webflux:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-starter-webmvc:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-starter:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-tomcat:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-transaction:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-validation:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-web-server:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-webflux:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot-webmvc:4.1.2 (META-INF/NOTICE.txt); org.springframework.boot:spring-boot:4.1.2 (META-INF/NOTICE.txt)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 5 non-empty lines):

```text
Spring Boot 4.1.2
Copyright (c) 2012-2026 VMware, Inc.
This product is licensed to you under the Apache License, Version 2.0
(the "License"). You may not use this product except in compliance with
the License.
```

#### NOTICE a70e92764597: carried for 10 component(s)

Components: org.springframework:spring-aop:7.0.10 (META-INF/notice.txt); org.springframework:spring-beans:7.0.10 (META-INF/notice.txt); org.springframework:spring-context:7.0.10 (META-INF/notice.txt); org.springframework:spring-core:7.0.10 (META-INF/notice.txt); org.springframework:spring-expression:7.0.10 (META-INF/notice.txt); org.springframework:spring-jdbc:7.0.10 (META-INF/notice.txt); org.springframework:spring-tx:7.0.10 (META-INF/notice.txt); org.springframework:spring-web:7.0.10 (META-INF/notice.txt); org.springframework:spring-webflux:7.0.10 (META-INF/notice.txt); org.springframework:spring-webmvc:7.0.10 (META-INF/notice.txt)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 9 non-empty lines):

```text
Spring Framework 7.0.10
Copyright (c) 2002-2026 Broadcom Inc.
This product is licensed to you under the Apache License, Version 2.0
(the "License"). You may not use this product except in compliance with
the License.
This product may include a number of subcomponents with separate
[...]
```

#### NOTICE c1f33c3f8270: carried for 1 component(s)

Components: tools.jackson.core:jackson-core:3.1.7 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 27 non-empty lines):

```text
# Jackson JSON processor
Jackson is a high-performance, Free/Open Source JSON processing library.
It was originally written by Tatu Saloranta (tatu.saloranta@iki.fi), and has
been in development since 2007.
It is currently developed by a community of developers.
## Copyright
[...]
```

#### NOTICE 56c7711d1344: carried for 1 component(s)

Components: tools.jackson.core:jackson-databind:3.1.7 (META-INF/NOTICE)

Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, 14 non-empty lines):

```text
# Jackson JSON processor
Jackson is a high-performance, Free/Open Source JSON processing library.
It was originally written by Tatu Saloranta (tatu.saloranta@iki.fi), and has
been in development since 2007.
It is currently developed by a community of developers.
## Copyright
[...]
```


---
Generated by lane W3-X1 at 2026-11-12T14:46:53Z (read-only review; no tree edits, no downloads, no tests, no commit).
