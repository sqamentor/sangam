<!-- SPDX-License-Identifier: Apache-2.0 -->
# SG-27: OneIFMS SD-301 licence exception for the Operaton DMN FEEL engine

**Decision (owner, 2026-11-08):** keep Operaton's own FEEL engine. OneIFMS SD-301 bans `org.camunda*` at its
workflow-engine boundary. This exception covers exactly one `org.camunda` artifact and its runtime closure. Nothing
else is covered.

## Scope

Only the coordinates below, at only these versions, as resolved by `org.operaton.bpm.dmn:operaton-engine-dmn:2.2.0`.
Any change of version, artifact or Operaton release has to be verified again (licence, NOTICE, CVE, behaviour tests)
before the pins move. A pin cannot move silently: `licence-policy.json` `pinned_exceptions` (watch=true) fails any
other version, and `FeelBomPinsTest` fails if the build, the BOM and the allowlist disagree. There is no wildcard:
every other `org.camunda*` component still fails (`namespace_bans`).

| Coordinate | SPDX | How it arrives (compile scope) |
|---|---|---|
| `org.camunda.feel:feel-engine:1.22.1` | Apache-2.0 | direct dependency of `operaton-engine-dmn:2.2.0`; also of `operaton-engine-feel-scala:2.2.0` |
| `org.scala-lang:scala-library:2.13.18` | Apache-2.0 | direct dependency of `operaton-engine-feel-scala` and `feel-engine`; 2.13.8/2.13.14 requests converge to 2.13.18 |
| `com.lihaoyi:fastparse_2.13:3.1.1` | MIT | through `feel-engine` |
| `com.lihaoyi:sourcecode_2.13:0.4.0` | MIT | through `fastparse` |
| `com.lihaoyi:geny_2.13:1.1.0` | MIT | through `fastparse` |
| `com.fasterxml.jackson.module:jackson-module-scala_2.13:2.22.3` | Apache-2.0 | through `feel-engine` (asks for 2.22.1; Sangam pins 2.22.3) |

MIT is Apache-2.0 compatible. `scala-reflect`, uJson and upickle are not in the tree.

## Evidence

- **Trees:** `dependency:tree -Dverbose` for sangam-engine, sangam-server and a bare consumer of
  `sangam-engine:0.1.0-SNAPSHOT` (no BOM). The bare consumer got jackson-module-scala **2.22.1** and a non-converging
  scala-library (2.13.8/2.13.14/2.13.18). The enforcer `dependencyConvergence` rule failed on it;
  `banDuplicateClasses` (extra-enforcer-rules 1.10.0) passed. `dependency:analyze-duplicate` found no duplicates. The
  sangam-bom pins fix the consumer case.
- **Licences (published POM `<licenses>`):** feel-engine "The Apache Software License, Version 2.0" (its parent
  `camunda-bpm-release-parent:2.5.1` says the same); scala-library `Apache-2.0`; fastparse, sourcecode and geny `MIT`
  (the jar MANIFEST also says `Licenses: MIT`); jackson-module-scala "The Apache Software License, Version 2.0".
- **Licence files inside the jars (~/.m2):** `scala-library-2.13.18.jar!LICENSE` (Apache-2.0 text) and `!NOTICE`;
  `jackson-module-scala_2.13-2.22.3.jar!META-INF/LICENSE` (Apache-2.0). feel-engine, fastparse, sourcecode and geny
  ship no LICENSE or NOTICE file in their jars. Their upstream repositories do:
  camunda/feel-scala `LICENSE` (Apache-2.0) and `NOTICE` ("Copyright 2020-2025 Camunda Services GmbH");
  com-lihaoyi fastparse and sourcecode `LICENSE` ("Copyright (c) 2014 Li Haoyi"); geny ("Copyright (c) 2016 Li Haoyi").
- **Attribution kept:** the repository `NOTICE` (Apache-2.0 section 4(d)) carries the scala-library NOTICE, the
  feel-scala NOTICE line and the MIT copyright lines. `deploy/airgap/generate-sbom.*` copies `NOTICE` into the SBOM
  bundle. `check_licences.py --annotate` writes the `sangam:exception*` properties onto each excepted CycloneDX
  component and into `metadata.properties`.
- **No commercial runtime:** `feel-engine-1.22.1.jar` holds 408 classes, all under `org/camunda/feel`. Its only
  service file is `javax.script.ScriptEngineFactory`. It has no `java.net` HTTP client, socket or URL connection
  classes, no telemetry and no licence-key code. The only URL constant is the OMG FEEL namespace. The POM's runtime
  dependencies are scala-library, fastparse, jackson-module-scala and slf4j. `operaton-engine-feel-scala-2.2.0`
  (35 classes) and `operaton-engine-dmn-2.2.0` (175 classes) have no HTTP client and no licence-key code.
  `operaton-engine-2.2.0` still contains Camunda 7's telemetry *data* classes (`impl/telemetry/**`) but no HTTP client
  class (no `HttpURLConnection` or `java.net.http`), so nothing is sent anywhere. The Operaton README says "no
  open-core model, no commercial editions".
- **Security, kept separate from this licence decision:** OSV.dev (`api.osv.dev/v1/query`, 2026-11-08) has no
  advisory for any of the seven exact versions, operaton-engine-dmn 2.2.0 included. CVE-2022-36944 affects Scala
  before 2.13.9 and does not apply to 2.13.18. An approved licence does not waive a later security finding; the
  regular OWASP dependency-check (`security.yml`) still covers these artifacts.
- **Boundary:** `FeelBoundaryGuardTest` (sangam-engine) fails if any `src/main` code outside
  `org.sangam.engine.feel` imports or names `org.camunda.*` or `scala.*`. Today no such code exists. Sangam code uses
  Operaton's API (`org.operaton.bpm.dmn.feel.*`).
- **Behaviour:** `FeelBehaviourH2Test` (sangam-engine, H2) pins decimal, null/error, date/time and function
  behaviour (see TESTING-GAP-REGISTER.md SG-27).

## Text for OneIFMS SD-301

> **SD-301 exception: Sangam (Operaton DMN FEEL engine).** Allowed at the workflow-engine boundary, compile/runtime
> scope only, behind Sangam's engine adapter:
> `org.camunda.feel:feel-engine:1.22.1` (Apache-2.0), with its runtime closure `org.scala-lang:scala-library:2.13.18`
> (Apache-2.0), `com.lihaoyi:fastparse_2.13:3.1.1`, `com.lihaoyi:sourcecode_2.13:0.4.0`,
> `com.lihaoyi:geny_2.13:1.1.0` (MIT) and `com.fasterxml.jackson.module:jackson-module-scala_2.13:2.22.3`
> (Apache-2.0), all as required by `org.operaton.bpm.dmn:operaton-engine-dmn:2.2.0`. These are open-source
> libraries in the Camunda namespace. They have no dependency on Camunda commercial runtime, licence keys,
> telemetry or enterprise services. Evidence is in Sangam `deploy/licence/SG-27-SD-301-exception.md`, the
> verbose dependency trees, the POM and jar licence files, a class scan of the jar, and OSV (no advisories on
> 2026-11-08). **Scope:** only these versions. Any change is verified again before use. No other `org.camunda*`
> artifact is allowed. Enforced by Sangam's licence gate (`pinned_exceptions` / `namespace_bans`) and by the
> FeelBomPinsTest and FeelBoundaryGuardTest tests.
