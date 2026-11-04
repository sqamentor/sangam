# experiments: E1, E8, E10, E11 on both engines

Implements SANGAM.md XI.4 experiments **E1** (corpus coverage), **E8** (timers across DST), **E10-lite** (platform
fit) and **E11** (BPMN gap set) for Flowable 8.0.0 and Operaton 2.2.0, through the engine-neutral
`EngineAdapter` (`product-api`). Results: `spike/results/E1.json`, `E8.json`, `E10.json`, `E11.json`; analysis in
`spike/results/E1-E11-report.md`.

## Run

```
.\mvnw.cmd -q -pl experiments verify                 # both engines, PostgreSQL 17 (Testcontainers)
.\mvnw.cmd -q -pl experiments verify -Dexp.db=h2     # quick pre-run on in-memory H2
.\mvnw.cmd -q -pl experiments verify -Pflowable      # Flowable only (exact adapter-flowable dependency mediation)
.\mvnw.cmd -q -pl experiments verify -Poperaton      # Operaton only
```

`-Dexp.writeResults=false` keeps `spike/results/*.json` untouched (fragments still go to `target/exp-results/`).
Needs the other modules in the local repository (`.\mvnw.cmd -q -DskipTests install`), JDK 21+ and Docker.

The build fails only on **harness** errors. Engine outcomes (a model that does not deploy, a wrong fire count) are the
measurement: they are recorded as `fail` in the JSON and printed as `[engine] <scenario> <status>` in
`target/surefire-reports/<execution>/*-output.txt`.

## Class path: one JVM per engine

Both adapters are test dependencies of this module, but the engines never share a JVM:

| Surefire execution | Engine | Test classes | Removed from the class path (`classpathDependencyExcludes`) |
|---|---|---|---|
| `flowable` | Flowable 8.0.0 | E1, E10, E11 | `adapter-operaton`, `org.operaton.*`, FEEL/Scala, `jackson-module-scala`, Jackson 2 core/databind, paranamer, `jakarta.el` |
| `operaton` | Operaton 2.2.0 | E1, E10, E11 | `adapter-flowable`, `org.flowable:*`, Jackson 3 (`tools.jackson.core`), angus/jakarta mail+activation, commons-io/codec/lang3, `jcl-over-slf4j` |
| `flowable-e8-kolkata`, `flowable-e8-berlin` | Flowable | E8 only, `-Duser.timezone=Asia/Kolkata` / `Europe/Berlin` | as `flowable` |
| `operaton-e8-kolkata`, `operaton-e8-berlin` | Operaton | E8 only, per zone | as `operaton` |

The system property `exp.engine` selects the adapter; engine-specific calls live in
`hooks/flowable/FlowableHooks` and `hooks/operaton/OperatonHooks` (loaded by name, so the other engine's classes are
never touched). Every hook a scenario uses is engine-specific handling and is listed in the JSON.

**Conflict found on the combined dependency list.** With both adapters declared, Maven mediates
`jackson-annotations` to Flowable's 2.20, while Operaton's `jackson-databind` 2.22.1 (pulled by the FEEL engine) needs
2.22: Operaton fails at engine build with `NoClassDefFoundError: com/fasterxml/jackson/annotation/JsonSerializeAs`.
The default profile therefore imports `jackson-bom` `${version.jackson}` (2.22.1), the same alignment as
`adapter-operaton` (ledger W-1). Side effect: the Flowable execution runs with jackson-annotations 2.22 instead of 2.20.
Other shared artifacts are mediated once for both executions (seen in `target/e10/tree-experiments.txt`):
`spring-beans/core` (Flowable alone: 7.0.3, Operaton alone: 7.0.10), `joda-time` (2.14.0 / 2.15.0),
`java-uuid-generator` (5.1.0 / 5.2.0), `jspecify` (1.0.0 / 1.0.1). For a run with exactly one adapter's own mediation,
use `-Pflowable` or `-Poperaton`: the profile declares only that adapter and excludes the other engine's hook package
from test compilation.

MyBatis is 3.5.19 in both engines, so there is no MyBatis conflict.

## Database

`-Dspike.db=postgres|oracle|sqlserver|mysql` selects the database (the pom maps it to `exp.db`). Oracle Free 23, SQL Server 2022 (`READ_COMMITTED_SNAPSHOT ON`) and MySQL 8.4 (utf8mb4_bin, `-Dspike.mysql.driver=mysql|mariadb`) start through `simulators` `Databases`, one container per JVM, one user/schema (Oracle) or database per engine instance. Their results go to `results/<E>-<db>.json` (`-h2` for the H2 pre-run), so the PostgreSQL files of Stage B are never overwritten. The Oracle, SQL Server and MySQL drivers are test scope only (LIC-2, LIC-4).

`-Dspike.engines=operaton` or `flowable` activates that engine's profile (same as `-Poperaton` / `-Pflowable`); the default runs both. `-Dskip.operaton.e8=true` / `-Dskip.flowable.e8=true` skip only the E8 executions. E1 alone on Oracle:

```
.\mvnw.cmd -pl experiments verify -Dspike.db=oracle -Dtest=E1CorpusCoverageTest -Dsurefire.failIfNoSpecifiedTests=false -Dskip.flowable.e8=true -Dskip.operaton.e8=true
```

`-Dexp.db=postgres` (default): one `postgres:17-alpine` container per JVM (Testcontainers 1.21.4, `api.version=1.44`
for Docker 29), a fresh database per test class. The container is stopped by a JVM shutdown hook (and by Ryuk if the JVM
dies). `-Dexp.db=h2`: in-memory H2 2.3.232.

## What each experiment does

- **E1** (`E1CorpusCoverageTest`): one engine instance per JVM; WF-1 (6 band edges + capital R1 with payment external
  work and lockVersion fencing probes, return-and-revise, two rejects, two informational divergence runs), WF-2
  (AC-04 forward 3 / return 2, return to initiator, concurrent parallel-join completion), WF-3 (SLA timer with
  setClock/executeDueJobs, clarification message, second SLA, inspection, eSign and DigiLocker external work, two
  rejects), the CMMN case. Every scenario records the corpus files resolved by `Corpus.resolve` (variant or shared),
  run-time workarounds observed (e.g. an extra `executeDueJobs` after an external completion), checks and details.
  `engineSpecificHandling` in the JSON counts variant elements + configuration items + run-time items.
- **E11** (`E11GapSetTest`): each `gap-*.bpmn` deployed through the adapter; deploy error text; a minimal runtime
  assertion per element; classification. If the standard deployment is rejected and the engine can deploy without BPMN
  validation (Flowable `disableBpmnValidation()`), the same assertion is re-run as a diagnostic (`withoutValidation`);
  this never upgrades the classification.
- **E8** (`E8DstTimerTest`): inline model, `userTask` + non-interrupting boundary `timeCycle R/PT1H` → `e8_tick` user
  task per fire. Clock stepped in 5-minute (and 1-minute for Berlin) steps; after each step due jobs run through
  `EngineHooks.executeDueJobsBounded` (the adapter's `executeDueJobs` steps, capped at 20 job executions per step),
  because at the autumn fold both engines refire the timer without end and the adapters' own bounds (100 rounds /
  10,000 executions per call) let one call spin for minutes. A window stops at 3x the expected fires (`runaway`).
  Windows: Asia/Kolkata 24 h; Europe/Berlin 2026-10-25 autumn fold (6 h); Europe/Berlin 2026-03-29 spring gap (6 h);
  plus an informational single-jump catch-up run. **One zone per JVM**: an earlier pre-run that switched
  `TimeZone.setDefault` inside one JVM showed Operaton cycles shifted by 30 minutes after a Kolkata run, because
  Operaton caches the JVM zone in `DateTimeUtil.JVM_DEFAULT_DATE_TIME_ZONE` (static final). That is a harness
  artefact, so zones are now separate JVMs.
- **E10** (`E10PlatformFitTest`): JDK, engine start + deploy + run on PostgreSQL, JDK 25 status, LIC-1…4 class and
  jar checks on the engine JVM's class path, Jackson/Spring jars, JSR-223 script engines, and a scan of the dependency
  trees in `target/e10/`. `tree-experiments.txt` is written by the build (`maven-dependency-plugin` `tree`); the
  per-adapter files are produced with:

  ```
  .\mvnw.cmd -q -pl adapter-flowable dependency:tree -DoutputFile="<spike>\experiments\target\e10\tree-adapter-flowable.txt"
  .\mvnw.cmd -q -pl adapter-operaton dependency:tree -DoutputFile="<spike>\experiments\target\e10\tree-adapter-operaton.txt"
  .\mvnw.cmd -q -pl adapter-flowable dependency:list -DincludeScope=runtime -DoutputFile="<spike>\experiments\target\e10\runtime-adapter-flowable.txt"
  .\mvnw.cmd -q -pl adapter-operaton dependency:list -DincludeScope=runtime -DoutputFile="<spike>\experiments\target\e10\runtime-adapter-operaton.txt"
  ```

  (missing files are reported as `missing` in `E10.json`).
