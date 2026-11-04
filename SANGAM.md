# Project Sangam — Consolidated Master Document

> One file holding **all content of four sources**, followed by a cross-verification register.
> Built on 2026-11-04 by an automated merge (headings demoted one level; text otherwise verbatim). The completeness check is at the end of Part V.

## ★ APPROVED BASELINE v1.0 (2026-11-05)

> **What this is:** the approved **specification baseline** for Sangam: every decision, requirement, specification, test and gate needed to start development.
>
> **What this is not:** built or tested software. Implementation starts at **Stage B**. Nothing listed here is implemented, and no test in the catalogue has been run.
>
> **How to use it:** read this section first, then the locations in the table **"Where the current specification lives"**. Everything else in this file is supporting history (Parts I–IX, Appendices A–D), kept verbatim for traceability.

### सारांश (Hindi)

**Sangam** भारत के केंद्र और राज्य सरकारों के approval workflows के लिए एक self-hostable workflow platform है:
- **दायरा:** बिल/भुगतान, e-Office file movement, अनुदान स्वीकृति, लाइसेंस/परमिट।
- **Standards:** BPMN 2.0.2 + DMN 1.6 + CMMN 1.1।
- **भाषाएँ:** Java, .NET, PHP, Python और Node के SDK; बाकी किसी भी भाषा के लिए REST।
- **Databases:** Oracle, SQL Server और PostgreSQL।
- **Deployment:** State Data Centre या government cloud, air-gapped install समेत।

**अब तक की स्थिति:**
- 13 में से 12 फैसले बंद हैं; सिर्फ base engine (D-1) Stage B के spike से तय होगा।
- 62 requirements की एक normative table बन चुकी है।
- 11 critical specs लिखे जा चुके हैं।
- 89 tests का catalogue है।
- सभी 12 सरकारी शर्तें official sources से पुष्ट हैं।
- सरकार पर कोई निर्भरता नहीं है: हर integration adapter और simulator के रूप में बनेगा।
- जो नहीं मिला, वह 14 parked items में दर्ज है।

**अगला कदम:** Stage B, यानी दोनों engines पर spike चलाकर base engine का चुनाव।

### Baseline at a glance

| Area | Baseline |
|---|---|
| Product envelope | All four workflow families (P1 bill/payment, P2 e-Office file movement, P3 grant/scheme sanction, P4 licence/permit); all central and state departments; on-prem State DC + government cloud + air-gapped (IX.1–IX.2) |
| First release path | Reference pilot: a shared government approval core + four packs, on synthetic data and simulators (IX.2–IX.5, ADR-D-13) |
| Delivery | Stages A–E (ADR-D-2, VIII.9). **Stage A is complete**: scope, criteria and government research |
| Standards | BPMN 2.0.2, DMN 1.6 (1.7 tracked), CMMN 1.1 isolated and retire-safe; AI agentTask (A2A) + read-only MCP; CloudEvents, AsyncAPI 3, Arazzo; XES/OCEL 2.0 (Part I §3.3a, VI.7, R44) |
| Engine | One engine, chosen by the Stage B spike between Flowable and Operaton (ADR-D-1, PROVISIONAL) |
| API / SDKs | REST first (ADR-D-5). Tier-1 SDKs: Java, .NET, PHP, Python, TypeScript/Node; Tier 2: Go, Rust, Kotlin; others via REST/OpenAPI (ADR-D-4) |
| Databases | Tier 1: PostgreSQL, Oracle, SQL Server; Tier 2: MySQL/MariaDB, DB2; drivers for Oracle/DB2/MySQL are customer-supplied (R17, CF-11, LIC-2…5) |
| JDK / migrations / scripts | JDK 25 LTS (R5); Liquibase 4.33.0 pinned (ADR-D-7); no in-engine scripts in release 1, an out-of-process script worker instead (ADR-D-8) |
| HA/DR | RPO ≤ 5 min, RTO ≤ 1 h, 99.5 % (ADR-D-12) |
| Government compliance | GOV-1…GOV-12 final: 8 VERIFIED, 4 VERIFIED with a parked sub-item, 0 blocking (IX.7e) → R50–R62 |
| Commercial | Implementation services, AMC/support, managed hosting on empanelled cloud, workflow packs + training, plus anything government policy requires (ADR-D-11) |

### Decision register (19 decisions)

| ADR | Decision | Status |
|---|---|---|
| D-1 | Base engine: **Operaton 2.2.x** (score 85.2 vs Flowable 72.1; XI.10). Flowable strengths carried over as product-layer features or ports | **FINAL**: confirmed by the owner (X.0d), after the VI.7 decision of 2026-11-05 |
| D-2 | Stages A–E (VIII.9) | DECIDED |
| D-3 | Government / regulated integrator (VI.7) | DECIDED |
| D-4 | SDK tiers (IX.6) | DECIDED |
| D-5 | REST first (X.0) | DECIDED |
| D-6 | Scripts (by D-8) | CLOSED |
| D-7 | Liquibase 4.33.0 + SQL changelogs; 5.x after legal review (VI.7, LIC-7) | DECIDED |
| D-8 | Out-of-process script worker; no GraalJS (VI.7, LIC-1) | DECIDED |
| D-9 | Market claim is a hypothesis (VIII.2) | CLOSED |
| D-10 | Release approval through gates G1–G8 (VIII.2) | CLOSED |
| D-11 | Revenue model (X.0) | DECIDED |
| D-12 | HA/DR targets (X.0) | DECIDED |
| D-13 | Self-reliant development, no government dependency (X.0b) | DECIDED |
| D-14 | Universal open-source product; all premium features free (X.0c) | DECIDED |
| D-15 | Tier-1 databases PostgreSQL, Oracle, SQL Server, MySQL (X.0c) | DECIDED |
| D-16 | Operaton as a dependency + Sangam plugins; engine patches only where needed, offered upstream (X.0d) | DECIDED |
| D-17 | Licence Apache 2.0 (X.0d) | DECIDED |
| D-18 | Public GitHub repository `sqamentor/sangam` (X.0d) | DECIDED |
| D-19 | Stage C: all workstreams run in parallel (X.0d) | DECIDED |

### Where the current specification lives (read only these)

| Topic | Authoritative location |
|---|---|
| **All requirements R1–R62** (normative wording, failure behaviour, stage, tests) | **X.1** + resolutions **X.1a**, **X.5.1** |
| Locks / transactions | **VI.1** + A-7 (X.1a) + L6 (X.2a) |
| Outbox, inbox, back-pressure | **VI.2** + A-5 |
| Worker lease, operationId, unknown outcome, retries | **VI.3** + A-1, A-2, A-8 |
| Authorization, tenancy | **VI.4** + A-3 |
| Cancel / delete / erase | **VI.5** + A-4 + A-9 (records retention wins) |
| Exclusivity | **VI.6** + A-7 |
| Partitions, API idempotency, audit/replay data, package versioning, forms | **X.2.1–X.2.5** |
| Government approval core, packs, roles, delegation | **IX.3–IX.5** |
| Government compliance | **IX.7e** (final) → R50–R62 |
| Licences / redistribution | **X.3** + **X.3a** |
| User, operator and admin journeys; screens; error codes; audit events | **X.4** + X.4a + X.5.2 |
| Acceptance criteria | **IX.8** (AC-01…AC-11, approved) |
| Tests | **T01–T89**: T01–T32 Part III §16; T33–T40 Part VI; T41–T49 VIII.4; T50 IX.8; T51–T77 X.2; T78–T88 X.4/X.4a; T89 X.5.1 |
| Gates | **G1–G8** (Part III §18 / VII §12); G1 moved to go-to-market (ADR-D-13) |
| Delivery plan | **VIII.9** (Stages A–E, phase→stage mapping, rule CF-9a) |
| Parked items | **X.6** (PK-01…PK-14) |
| What the old text said and what replaced it | **X.5.3** superseded-statements register; precedence Part X > IX > VIII > VII > VI > V > I–IV |

### Stage status

| Stage | Status | Exit evidence |
|---|---|---|
| A: need and scope | **COMPLETE** (2026-11-05) | Envelope, reference-pilot scope, AC approved, GOV research final, journeys, licence inventory |
| B: technical proof | **COMPLETE** (2026-11-05): G2 + G3 passed, ADR-D-1 = Operaton (XI.10) | D-1 spike on both engines: authorization (VI.4), lease (VI.3), idempotency (X.2.2), recovery; BPMN gap reproduction (CF-13); licence inventory regenerated at the chosen tag (LIC-11) |
| C: full pilot | Pending | AC-01…AC-11 on PostgreSQL + Oracle + SQL Server with the Tier-1 SDKs |
| D: production readiness | Pending | Security, load, upgrade, failover/restore (RPO/RTO), CERT-In-style audit evidence |
| E: expansion | Pending | Driven by adoption |

### Known limits of this baseline (honest statement)

- Effort estimates (143–220 person-months, Part II §15.1) **exclude** R36–R62, Part VI and Part X. They must be re-estimated after Stage B (M09, M11).
- Performance targets (R22) are hypotheses until benchmarked in Stage B/C.
- Risk scores (FMEA) are subjective. Release approval uses gates, not percentages (D-10).
- Every requirement and test is **SPECIFIED**; none is **IMPLEMENTED** or **TESTED**.

### Integrity of this document

Final end-to-end check, 2026-11-05:
- **27 sources** were compared line by line with this file, **0 lines missing**. The sources are:
  - the retired DESIGN.md and ANALYSIS.md, located in the Recycle Bin by their SHA-256;
  - the independent review;
  - the master reassessment;
  - the PDF raw text;
  - every merged research and specification file.
- Part VII also matches its source exactly, blank lines included.
- Lines that were later edited in place are preserved verbatim in **Appendix E**.

### Sign-off

| Role | Name | Decision | Date |
|---|---|---|---|
| Project owner | — | Approved ("I Approved Go Ahead", 2026-11-05) | 2026-11-05 |
| Baseline editor | Claude (AI assistant) | Compiled and cross-verified | 2026-11-05 |

---

## How to read this document

| Part | Content | Source | SHA-256 |
|---|---|---|---|
| Part I | Architecture design (verbatim) | `DESIGN.md` | `3005ae03d3ffa33a0cb9f3c1d98c3c4f036d06b5a28c8efbeac15c3f338718d9` |
| Part II | Pre-development analysis (verbatim) | `ANALYSIS.md` | `59039de2722e95bf215f93bf72a3f65adce3b0a41b645293917409b40cf7dcdc` |
| Part III | Independent end-to-end review (verbatim) | `Sangam_End_to_End_Independent_Review.md` | `68e0f199eda3c915e216223a76176893e58471da7febe2cbd35cfadac5ef1df5` |
| Part IV | Content of the PDF report that is new or different, plus a structure map | `Project Sangam — End-to-End Architecture, Market & Pre-Development Analysis Report.pdf` (27 pages) | `2ebab37431bcc3eb46c512c9832785df906b504d550d3aac8c3baea1bfcba3e3` |
| Part V | Cross-verification register: integrity checks, accepted corrections, new requirements R36–R49, open decisions D-1…D-12, completeness check | this merge | — |
| Part VI | Critical specifications replacing R3, R4, R9, R19, R23 and R35, plus decision records D-1, D-2, D-3, D-7 and D-8 | added 2026-11-04 | — |
| Part VII | Master reassessment (verbatim) | `SANGAM_Master_Reassessment_HI.md` | `29a86486dd58c405d6e06fbe3c183bdf74cbb3b1ab372f5a77671791ded90c24` |
| Part VIII | Reconciliation of Part VII: M01–M12, amendments A-1…A-6, tests T41–T49, decision alignment, Current Implementation Baseline index | added 2026-11-04 | — |
| Part IX | Stage A pack: owner inputs, product envelope vs pilot, shared government approval core, four workflow packs, roles/delegation model, deployment and SDK matrix (ADR-D-4), government research items GOV-1…12, acceptance criteria AC-01…11, exit checklist | added 2026-11-04 | — |
| Part X | Finalisation: owner decisions batch 1 (AC approved, D-5, D-11, D-12), decision register, normative requirements, further specs, licence inventory, journeys | added 2026-11-04 | — |
| Part XI | Stage B (D-1 spike) plan: pinned candidates Flowable 8.0.0 and Operaton 2.2.0, product-layer architecture, corpus WF-1…WF-3 + GAP set, experiments E1–E11, weighted scoring, environment, workstreams, timeline | approved 2026-11-05 | — |
| Appendix A | Full raw text extracted from the PDF (all 27 pages), so nothing from it is lost | PDF | — |
| Appendix B | GOV-1…GOV-6 verification report (verbatim) | research, 2026-11-04 | — |
| Appendix C | GOV-7…GOV-12 verification report (verbatim) | research, 2026-11-04 | — |
| Appendix D | GOV gap closure via Chrome reading of official pages (verbatim) | research, 2026-11-04 | — |
| Appendix E | In-place edit log: verbatim originals of lines later edited | 2026-11-05 | — |

**Note on old file names:** `DESIGN.md` and `ANALYSIS.md` were retired on 2026-11-04, after a second completeness check (0 missing lines) moved them to the Recycle Bin. Inside Parts I–III, any reference to `DESIGN.md` now means **Part I**, and any reference to `ANALYSIS.md` means **Part II**. Section numbers such as "DESIGN §4.3" or "ANALYSIS §9" still apply within those parts.

**Precedence rule:** Part V wins over Parts I–IV where it marks a correction **ACCEPTED** or **VERIFIED**. DESIGN.md §16 still wins over earlier DESIGN sections. Parts I–IV are kept word for word for traceability.

---
# Part I — Architecture Design (from DESIGN.md)

## Universal Workflow Engine — Architecture Design

> Working name: **Sangam** (संगम, the meeting of two rivers: Operaton + Flowable).
> The name is a placeholder. Do not use "Flowable", "Camunda" or "Operaton" in the product name, because those are trademarks even though the code is Apache 2.0.
>
> Based on a code-level study of:
> - `reference/operaton`: Operaton 2.3.0-SNAPSHOT, a Camunda 7 fork
> - `reference/flowable-engine`: Flowable 8.1.0-SNAPSHOT
>
> Date: 2026-11-04

---

### सारांश (Summary in Hindi)

**सीधा जवाब:** दोनों codebases को "copy-paste करके जोड़ना" संभव नहीं है। दोनों का execution core अलग है:
- Operaton = PVM + atomic operations + execution tree compaction
- Flowable = flat agenda + operations

इसलिए सही रास्ता यह है:

1. **एक core चुनो, दूसरे की खूबियाँ उसमें port करो।** Core के लिए **Flowable का agenda model और shared-services architecture** लेंगे। यह architecture के हिसाब से ज़्यादा साफ है, CMMN और events में आगे है, और इसमें legacy tree-compaction की जटिलता नहीं है।
2. **Operaton से ये लेंगे:**
   - Fine-grained authorization
   - पूरा FEEL (DMN)
   - Migration, modification और restart की richness
   - Batch framework
   - History pipeline, removal-time cleanup
   - SKIP LOCKED job acquisition
   - Liquibase migrations
   - Modern UI (webapps-neo)
   - Test assertions
3. **दोनों की साझा कमियाँ नए design से हटाएँगे:**
   - सिर्फ Java-centric होना
   - DB ही अकेला coordination medium होना
   - Optimistic-lock storms
   - History का synchronous लिखा जाना
   - Observability का न होना
   - बहुत बड़ी config classes
   - Java serialization
4. **Any tech stack (PHP, .NET, Go, Rust, Node, Python, Java, serverless):** Engine एक **protocol-first standalone service** होगा: gRPC + REST + events (CloudEvents)। सभी भाषाओं के SDK एक ही contract से generate होंगे। Expressions के लिए **FEEL** (language-neutral) और scripts के लिए **WASM sandbox** होगा।
5. **Standards:**
   - पूरी तरह: BPMN 2.0.2, CMMN 1.1, DMN 1.6 (full FEEL; DMN 1.7 beta को track करेंगे)
   - DMN engine: **Apache KIE Drools DMN** (DMN 1.6, TCK में 99.9% pass) default होगा। Operaton का feel-scala engine optional रहेगा, ताकि Camunda जैसा व्यवहार चाहिए तो वह भी मिल सके। (§3.4 देखें)
   - Extra: AI agents (A2A 1.0.1 + MCP 2026-07-28), Arazzo 1.1.0, CloudEvents, AsyncAPI, OpenAPI, JSON-Schema forms, process mining logs (XES / OCEL 2.0)
   - Import compatibility: Camunda 7/Operaton और Flowable models
6. **Licensing:** दोनों Apache 2.0 हैं। मिलाना legal है, बस LICENSE/NOTICE और "modified" attribution रखना होगा।
7. **Engineering principles (§14):** System analysis, design, development, security और lifecycle के लिए नियम तय हैं, और हर नियम का एक **मापने योग्य gate** है जो CI में अपने-आप जाँचा जाता है:
   - ISO/IEC 25010 quality model
   - NIST SSDF, OWASP ASVS L3, SLSA L3
   - Conformance test kits (BPMN MIWG, DMN TCK)
   - 10+ साल का lifecycle plan (LTS, compatibility policy, zero-downtime upgrades)
   - हर Operaton/Flowable खूबी और कमी के लिए traceability: requirement → design → test

---

### 1. What the research found

#### 1.1 Side-by-side

| Area | Operaton (Camunda 7 fork) | Flowable 8 | Winner → Sangam takes |
|---|---|---|---|
| **Execution core** | PVM graph (`ActivityImpl`) + ~35 atomic operations + trampoline; execution tree with compaction and `LegacyBehavior` for pre-7.3 shapes | Flat agenda (`DefaultFlowableEngineAgenda`, FIFO `Runnable` ops) directly on `BpmnModel`; future ops for async service tasks | **Flowable** (simpler, no legacy tree shapes) |
| **Activity instances** | Derived from the execution tree at query time | First-class runtime table `ACT_RU_ACTINST` | **Flowable** |
| **Model** | Two BPMN models: SAX `BpmnParse` for runtime, DOM `model-api` for tooling | One `BpmnModel` (StAX converter) used by parser, validator and runtime | **Flowable** model + Operaton's **fluent builder API** idea |
| **CMMN** | CMMN 1.1, enabled, some TODOs | CMMN with its own agenda, persistent sentry memory (`SentryPartInstanceEntity`), richer lifecycle states, case migration | **Flowable** |
| **DMN** | **Full FEEL** via feel-scala 1.22, DRD, hit policies | JUEL with FEEL-like pre-parsing, **no real FEEL** (verified: zero `feel` matches in `flowable-dmn-engine/src/main/java`) | **Neither is best**: Operaton is far ahead of Flowable, but Apache KIE Drools DMN has better TCK results (§3.4) |
| **Engine composition** | One monolithic engine with BPMN+CMMN+DMN inside | Engines + configurators + shared services (job, task, variable, identitylink, entitylink, eventsubscription, batch) told apart by `SCOPE_TYPE_` | **Flowable** pattern, rebuilt with a clean SPI |
| **Job executor** | DB polling, exponential backoff, priorities, priority-range pools, deployment-aware, new opt-in `SKIP LOCKED` | Separate acquire threads for async and timer jobs, **global acquire lock**, post-commit direct hand-off, split job tables (async/timer/suspended/deadletter/external) | **Merge**: Flowable hand-off + table split; Operaton priorities + SKIP LOCKED as default |
| **External workers** | External tasks (fetch-and-lock, topics, variables filter); long-poll only within one JVM; **Java client only** | External worker jobs for BPMN **and CMMN**; REST only; clients in separate repos | **Redesigned** (see §5) |
| **Events / messaging** | **None**: no Kafka/AMQP; only API correlation and in-JVM Spring events | **Event registry**: Kafka/JMS/Rabbit channels, key/tenant detectors, correlation, start/boundary/catch events | **Flowable** + CloudEvents + outbox |
| **History** | Pluggable producer→handler pipeline, removal-time strategies, TTL enforcement, cleanup windows, parallel cleanup | Levels incl. per-element config; async history reduced to hooks; timer-based bulk cleanup | **Operaton** pipeline + Flowable per-element levels, written via **outbox** |
| **Migration / dynamic** | Very strong: migration plans with validators, modification (start before/after, cancel), restart from history, batch versions | Migration (JSON plans, 1→N, N→1 mappings, pre/post scripts), change-state, **dynamic injection** of tasks/subprocesses, case migration | **Both**: union of the features |
| **Batch** | Seed/job/monitor batch framework, many batch types | `BatchService` + batch parts | **Operaton** framework |
| **Authorization** | **Fine-grained**: 22 resource types, grant/revoke, permission bitmasks, enforced in SQL | **None** in OSS: only coarse privileges + REST interceptor hooks | **Operaton** + ABAC / policy engine |
| **Multi-tenancy** | Row-level `TENANT_ID_` + tenant checks | Row-level **plus DB-per-tenant routing** (`TenantAwareDataSource`) + change-tenant tooling | **Flowable** + Operaton's tenant checks |
| **Scripting** | JSR-223 (GraalJS, Groovy), Spin JSON/XML | **Secure Rhino sandbox** (class whitelist, CPU/memory limits), static Groovy | **WASM + GraalJS isolate sandbox**; no Rhino (Rhino-style sandbox escaped in CVE-2025-0982; ANALYSIS R6) |
| **Schema migration** | Per-DB SQL **plus Liquibase** | Per-DB SQL only | **Liquibase** as the single source |
| **Databases** | H2, Postgres, MySQL, MariaDB, Oracle, MSSQL, DB2 | Same + **CockroachDB**, HSQL | **Union** |
| **Runtimes** | Embedded, Spring Boot 4, **Quarkus**, Jakarta EE, Run distro | Embedded, Spring Boot 4 (+ GraalVM native sample) | Embedded JVM + standalone server + native image |
| **UI** | Legacy AngularJS (end of life) + **new webapps-neo (Preact, bpmn-js/dmn-js/form-js)** | **No OSS UI** | **Operaton webapps-neo**, extended with a modeler |
| **Observability** | DB meter tables, health service, **no Micrometer/OTel** | Micrometer-style observation hook in the job executor | **OpenTelemetry everywhere** (new) |
| **Testing** | JUnit 5 extension + **AssertJ process assertions** + Testcontainers per DB + ArchUnit | JUnit 5 extensions, `@Deployment`, mock behavior factory | **Both** |
| **Legacy weight** | `LegacyBehavior`, ~171 deprecations, AngularJS | `flowable5-*` (~750 files) still built, ~367 deprecations | **Neither**: clean start |

#### 1.2 Weaknesses and how Sangam removes them

| # | Weakness (where) | Sangam's fix |
|---|---|---|
| W1 | **Java-only deep integration** (both): JavaDelegate and Spring beans are first class, other languages get REST only | Protocol-first: gRPC + REST + events; generated SDKs for 8+ languages; FEEL expressions instead of Java beans; JSON variables (§5) |
| W2 | **DB is the only coordination medium**; polling, OLE storms on acquisition (Operaton) | `SKIP LOCKED` by default + post-commit direct hand-off + Postgres `LISTEN/NOTIFY` or broker wake-up (§4.4) |
| W3 | **Optimistic-lock exceptions on parallel joins** and concurrent correlation on one instance (both) | Per-instance serialization: instance-row lock + automatic retry interceptor; optional instance-partitioned executors (§4.3) |
| W4 | **Long-polling not cluster-aware** (Operaton) | Wake-up bus (NOTIFY / NATS / Redis) shared by all API nodes; gRPC server streaming (§5.2) |
| W5 | **Synchronous inline history** (Operaton); **async history only hooks** (Flowable) | Transactional **outbox** → history projector → SQL / Elasticsearch / OpenSearch / Kafka; sync mode for strict audit (§4.6) |
| W6 | **No metrics or tracing** (Operaton), weak in Flowable | OpenTelemetry traces (W3C trace-context passed to workers), Micrometer/Prometheus metrics, structured logs with instance IDs |
| W7 | **5,000+ line mutable god-config** (both) | Immutable typed config (`EngineConfig` records + builders), one module per feature with an SPI, validated at start-up |
| W8 | **62 tables, duplicated deployment and property tables** (Flowable) | One deployment/resource store for all model types; tables grouped by service; target ~35 tables |
| W9 | **No fine-grained authorization** (Flowable) | Operaton's resource/permission model + ABAC policies (OPA / Cedar optional) + OIDC (§7) |
| W10 | **No real FEEL in DMN** (Flowable); incomplete TCK coverage in the feel-scala based engine (Operaton) | DMN engine behind an SPI; default Apache KIE Drools DMN (DMN 1.6, conformance level 3); FEEL used for BPMN conditions too (§3.4) |
| W11 | **No event broker integration** (Operaton) | Event gateway: Kafka, NATS, RabbitMQ, JMS, SQS/SNS, Azure Service Bus, Google Pub/Sub, webhooks; CloudEvents format |
| W12 | **No OSS UI** (Flowable); **AngularJS EOL** (Operaton) | One modern UI: cockpit, tasklist, admin, modeler (§8) |
| W13 | **Legacy compatibility code** (`LegacyBehavior`, `flowable5-*`) | Not carried over. Old models are converted at import time instead (§3.3) |
| W14 | **Java serialization of variables** (both, off by default but present) | Removed entirely; variables are JSON / primitives / files / references |
| W15 | **Thread-bound ThreadLocal design**, no virtual threads (both) | JDK 25 LTS virtual threads (no `synchronized` pinning since JEP 491) for workers and I/O, bounded by semaphores; explicit context passing in the new core |
| W16 | **Hand-written SQL upgrade scripts for 7–8 DBs** (both) | Liquibase changelog as the single source; generated per-DB SQL for DBAs who need scripts |
| W17 | **Embedded-only scripting with weak sandbox** (Operaton) | Secure JS sandbox + WASM scripts with CPU/memory/time limits and no host access by default |
| W18 | **External-task client only in Java** (Operaton) | Official SDKs: Java, Kotlin, .NET, Go, Rust, Python, Node/TypeScript, PHP |

---

### 2. Core decisions

| Decision | Choice | Why |
|---|---|---|
| **Engine language** | **Java, JDK 25 LTS baseline** for the core (raised from 21 after the evidence review, ANALYSIS R5) | Lets us **reuse** proven Apache-2.0 code from both projects (parsers, FEEL, CMMN, DMN, migration). A Rust/Go rewrite would take years before reaching parity. Any-stack support comes from the **protocol**, not the core language. |
| **Base to fork** | **Flowable engine core** (agenda, model, shared services, CMMN, event registry) | Cleaner execution model; Operaton's best parts are features that can be layered on top |
| **Ported from Operaton** | Authorization, DMN engine integration and history (with Drools DMN as the default engine, §3.4), migration/modification/restart, batch framework, history pipeline + removal time, SKIP LOCKED, priorities, Liquibase, webapps-neo, AssertJ assertions, Testcontainers setup, ArchUnit rules | Operaton is clearly stronger in each of these |
| **Expression language** | **FEEL** as default everywhere (conditions, I/O mapping, DMN); JUEL only as a compatibility option | FEEL is a standard and language-neutral. JUEL ties expressions to Java beans |
| **Variables** | Typed JSON (string, number, boolean, date, json, file, reference) | Every language can read and write them |
| **Persistence** | Relational first (JDBC + MyBatis, reusing mappings) behind a **Storage SPI** | Proven and transactional; the SPI keeps a door open for a partitioned high-scale backend later (§10) |
| **Server framework** | Standalone server on Spring Boot 4 (native image via Spring AOT); thin starters for Spring Boot and Quarkus for embedded JVM users | Both upstream projects already support Spring Boot 4; Quarkus support comes from Operaton's extension |
| **Delivery semantics** | **At-least-once** to workers, plus idempotency keys and exactly-once state changes inside the engine transaction | The only honest guarantee across networks |

---

### 3. Standards coverage

#### 3.1 Core standards (complete support)

| Standard | Engine | Notes |
|---|---|---|
| **BPMN 2.0.2** | BPMN agenda runtime | All executable elements: tasks, gateways (incl. event-based, complex), all event types (timer, message, signal, error, escalation, compensation, conditional, link, terminate, cancel), sub-processes, event sub-processes, transactions, call activities, multi-instance, compensation, data objects |
| **CMMN 1.1** | CMMN agenda runtime | Stages, human/process/case/decision tasks, milestones, sentries (persistent), repetition, manual activation, required rules, event listeners, case migration |
| **DMN 1.6** (latest formal OMG version; 1.7 is in beta) | DMN engine SPI, default Apache KIE Drools DMN (§3.4) | Decision tables (all hit policies), literal expressions, DRD, BKM, decision services, contexts, boxed expressions, full FEEL. Track 1.7 |

> **Spec status, verified on the official pages on 2026-11-04:**
> - **BPMN 2.0.2**: formal, January 2014, OMG `formal/13-12-09` (same text as ISO/IEC 19510:2013). The OMG page lists no beta or newer version.
> - **CMMN 1.1**: formal, December 2016, OMG `formal/16-12-01`. The OMG page lists no beta or newer version. Adoption is declining, so Sangam supports CMMN fully but also offers adaptive BPMN (ad-hoc sub-processes + event sub-processes) as the more widely tooled way to model flexible cases.
> - **DMN 1.6**: formal, OMG `formal/25-12-02`. The OMG page gives September 2026 as its publication/adoption date.
> - **DMN 1.7**: beta. Machine-readable files are published (`dtc/26-05-*`, `dtc/26-06-*`).

#### 3.2 Extra standards and modes

| Addition | Purpose | How it fits |
|---|---|---|
| **AI agents: A2A 1.0.1 (May 2026) + MCP (spec revision 2026-07-28, incl. the Tasks extension for long-running work)** (Linux Foundation) | Agentic workflows: a process step is handled by an AI agent, or an agent starts and queries processes | New `agentTask` (BPMN extension) calls an A2A agent with timeouts, budgets, human approval gates, full audit and a durable step journal (§3.3d L8); the engine itself is exposed as an MCP server (**read-only by default**; write tools create approval tasks, ANALYSIS R14) and an A2A agent |
| **Arazzo 1.1.0** (OpenAPI Initiative) | Standard description of a sequence of API calls | Import an Arazzo document as a sub-process of HTTP tasks, or export a process's API steps as Arazzo |
| **XES (IEEE 1849) / OCEL 2.0** | Process-mining event logs | Export history as XES or object-centric OCEL logs for process-mining tools |
| **CloudEvents 1.0** | Standard event envelope in and out | Format of the event gateway |
| **AsyncAPI 3 / OpenAPI 3.1** | Machine-readable contracts | Event gateway and REST are published as specs; SDKs are generated from them |
| **JSON Schema forms** (form-js compatible) | User-task forms in any frontend | Stored as deployment resources and rendered by the UI or by your own app |
| **Saga / compensation** | Distributed transactions across microservices | BPMN compensation + a "saga" template and SDK helpers |
| **State machine (simple FSM)** | Small lifecycles such as order or ticket status | Expressed as a BPMN subset; optional SCXML import later |

#### 3.3 Import compatibility (migration from existing systems)

- Read BPMN, CMMN and DMN files with **both** extension namespaces: `http://camunda.org/schema/1.0/*` and Operaton's, plus `http://flowable.org/*`. Map them to Sangam extensions at deployment time.
- `class=` / JavaDelegate references keep working only in **embedded JVM mode**. In server mode the importer reports them and suggests a worker topic instead.
- Optional **compatibility REST facades** (Camunda 7 REST and Flowable REST subsets), so existing clients can move gradually.
- Data migration tool: copy running instances from an Operaton or Flowable database into Sangam (Phase 4).

#### 3.3a Agreed standards scope (decided by the project owner, 2026-11-04)

| Group | Standards | Status |
|---|---|---|
| Business core | BPMN 2.0.2, DMN 1.6 (track 1.7), CMMN 1.1 | **Agreed**, full support |
| AI | `agentTask` over A2A with time/cost limits, human approval and full audit; engine exposed as an MCP server | **Agreed** |
| Integration | CloudEvents, AsyncAPI 3, Arazzo | **Agreed** |
| Analysis | History export as XES and OCEL 2.0 for process-mining tools | **Agreed** |
| ~~Developers~~ | CNCF Serverless Workflow (YAML) and code-first SDK DSL | **Removed for now** (2026-11-04). Can be revisited later; the design does not depend on them |

#### 3.3b CMMN: full features, safe to retire later

CMMN 1.1 has no successor and its adoption is declining. Sangam keeps every CMMN capability, but builds it so that it can be discontinued one day without harming the rest of the system:

- **Separate module.** `sangam-runtime-cmmn` is an optional plug-in. The core, the BPMN runtime and the shared services have **no compile-time dependency** on it (enforced by ArchUnit). BPMN↔CMMN calls go through the neutral `ProcessInstanceService` / `CaseInstanceService` interfaces (Flowable's pattern).
- **Own tables.** CMMN runtime and history live in their own table group, added and removed by their own Liquibase changelog.
- **Shared services by scope type.** Tasks, variables, jobs and identity links serve CMMN through `scopeType = cmmn`, so dropping CMMN removes no shared code.
- **Feature parity in BPMN.** Every CMMN construct gets a documented BPMN equivalent (ad-hoc sub-process, event sub-process, conditional events, milestones as intermediate events).
- **Converter.** A CMMN → BPMN converter and a case-to-process migration tool. If CMMN is retired, existing cases move over automatically.
- **Deprecation policy.** Any retirement follows §14.7: announce, at least 2 minor releases and 12 months, migration guide.

#### 3.3c DMN 1.7 beta: what it is and how Sangam handles it

What the official OMG pages show (2026-11-04):
- **DMN 1.7 Beta 1** is published "for informational purposes": spec `dtc/26-06-01`, XSD `dtc/26-06-03`. OMG says the **formal 1.6 must be followed for compliance**.
- The change-bar document and the full issue tracker are **OMG-members only**, so the complete change list is not public.
- Items that the public tracker marks as **deferred from 1.6 to 1.7** include:
  - concrete `integer` and `real` types, with `number` becoming abstract (DMN16-15)
  - unary tests as first-class FEEL values (DMN16-7)
  - test cases inside the decision model (DMN16-6)
  - grey-box decision-service dependencies (DMN16-77)
  - clarifications for null optional parameters, `string()` output and month-end date arithmetic
- Whether all of these are actually in 1.7 Beta 1 cannot be confirmed from public pages.
- No statement on backward compatibility is published. The numeric type change is the one most likely to affect existing models.
- No engine has announced 1.7 support yet; Apache KIE Drools currently goes up to 1.6.

**Policy:**
1. **Target DMN 1.6 formal** for compliance now.
2. **Read 1.7 Beta 1 in full** (the public PDF, `dtc/26-06-01`) and record each change in the coverage checklist, marking it compatible or breaking.
3. **Preview support.** The parser accepts the 1.7 namespace behind a `dmn.preview-1.7` flag, so models can be tried without affecting production.
4. **Adopt 1.7 as the default** only when OMG makes it formal and the DMN engine passes the TCK for it. Then decide per change: compatible changes are enabled for everyone; breaking changes apply only to models that declare the 1.7 namespace. 1.6 models keep their exact behaviour.
5. **Test cases in the model (DMN16-6)** fit Sangam's quality goals. Sangam can support them early as an extension, running embedded test cases on deployment.

#### 3.3d Lessons taken from YAML DSLs and durable execution (agreed 2026-11-04)

Serverless Workflow and code-first DSLs are out of scope (§3.3a), and Sangam will **not** become a Temporal-style replay engine:
- It would be a second engine, plus a deterministic runtime in every SDK language.
- It would give up diagrams, business visibility and token-level modification/migration.

Their best ideas still fit a BPMN token engine, so these are adopted:

| # | Lesson | Learned from | Sangam feature |
|---|---|---|---|
| L1 | Workflows reviewed like code | YAML DSLs in Git | **BPMN semantic diff and review tool**: CLI + UI that shows added/removed/changed elements, flows and expressions instead of raw XML diffs; usable in PR checks |
| L2 | Call an API by reference | Serverless Workflow `call` tasks | Service tasks can name an **OpenAPI operationId, AsyncAPI operation or gRPC method** directly; inputs and outputs are validated against that contract |
| L3 | Behaviour-readable conformance tests | Serverless Workflow CTK (Gherkin) | Engine semantics tests written as Gherkin scenarios next to the code tests (§14.4) |
| L4 | Easy unit testing for developers | Temporal SDK test servers | Every SDK ships an **in-memory/test-container engine**, time-skipping for timers, and assertions on process state |
| L5 | Safe upgrades by replay | Temporal replay tests | **Replay testing**: before a new model version is deployed or instances are migrated, recorded instance event logs are replayed against it, and differences are reported |
| L6 | Ask a running instance | Temporal queries/updates | **Query and update handlers**: a model declares named queries (read-only, FEEL over instance state) and updates (validated changes); exposed in API and SDKs |
| L7 | Very long-lived instances | Temporal continue-as-new | **Continue-as-new**: an instance can close itself and start a fresh one with carried-over state and a link, which keeps history and runtime size bounded |
| L8 | Durable AI agent loops | Temporal's agent use case | **Durable agent journal inside `agentTask`**: every LLM call and tool call is recorded with its result. After a crash the agent resumes from the journal without repeating expensive or side-effecting calls. Budgets, timeouts and approvals apply per step |
| L9 | Full event history per instance | Temporal event history | **Per-instance append-only event log** (fed by the outbox): used for audit, time-travel debugging, replay testing (L5) and XES/OCEL export |
| L10 | Work with what teams already use | — | **Temporal interop**: a connector task that starts a Temporal workflow and waits for its result, and an inbound adapter so Temporal workflows can start or correlate Sangam processes |

#### 3.4 DMN engine choice (decision changed after checking the DMN TCK)

The official **DMN TCK** results page currently lists:

| Engine | Licence | TCK result |
|---|---|---|
| **Apache KIE Drools 10.2.0** | Apache 2.0 | 3388/3391, "full compliance level 3"; DMN 1.1–1.6 supported |
| **jDMN 10.0.0** (Goldman Sachs) | Apache 2.0 | 3391/3391, "runtime compliance level 3"; interpreter and Java code generator |
| Trisotech 12.12.4 | Commercial | 3390/3391 |
| Camunda DMN-Scala 1.9.0 (the feel-scala family Operaton uses) | Apache 2.0 | 2850/3391 (385 not supported) |
| Flowable | — | Not listed |

Caveats:
- The TCK site says it reflects DMN 1.4, so it does not yet test 1.6 features.
- The listed DMN-Scala result is for **1.9.0**. Operaton uses feel-scala **1.22.1**, whose current TCK score is not published there. It must be measured before ruling it out.

**Decision:**
- The DMN engine sits behind a `DecisionEngine` SPI.
- The default is **Apache KIE Drools DMN**: highest conformance, DMN 1.6 support, Apache 2.0, active Apache project.
- **jDMN** is the lightweight alternative. It is useful when the Drools dependency set is too heavy, or when decisions should be compiled to Java.
- **feel-scala** remains available for teams migrating from Camunda/Operaton who rely on its exact FEEL behaviour.
- Phase 1 runs the TCK against all three, and the default is confirmed by the measured result, not by this table alone.

---

### 4. Engine architecture

```
                         ┌──────────────────────────── API layer ────────────────────────────┐
  Any language  ───────► │  gRPC  │  REST (OpenAPI 3.1)  │  Event gateway (CloudEvents)  │  UI │
  (SDKs/HTTP/broker)     └────────┬──────────────┬──────────────────┬──────────────────────────┘
                                  ▼              ▼                  ▼
                         ┌────────────────── Command pipeline ──────────────────┐
                         │ auth → tenant → tracing → retry → tx → CommandContext │
                         └───────────────────────────┬───────────────────────────┘
          ┌─────────────────────┬────────────────────┼─────────────────────┬────────────────────┐
          ▼                     ▼                    ▼                     ▼                    ▼
   BPMN runtime          CMMN runtime          DMN engine         AI agent tasks        Batch engine
   (agenda)              (agenda)              (Drools DMN SPI)   (A2A / MCP)           (seed/job/monitor)
          └─────────────────────┴──────── Shared services ─┴────────────────────────────────────┘
          job · task · variable · identity-link · entity-link · event-subscription · history · authz · tenancy
                                             │
                    ┌────────────────────────┼─────────────────────────┐
                    ▼                        ▼                         ▼
             Storage SPI (JDBC)       Outbox → projectors        Wake-up bus
             Postgres/Oracle/MSSQL/   (history, events,          (NOTIFY / NATS /
             MySQL/MariaDB/DB2/       webhooks, search)          Redis / in-JVM)
             H2/CockroachDB
```

#### 4.1 Module layout (proposed)

```
sangam/
├── sangam-api/                 public Java API (services, queries) — no impl classes leak
├── sangam-model/               BpmnModel, CmmnModel, DmnModel, fluent builders
├── sangam-parsers/             bpmn, cmmn, dmn, legacy-namespace importers
├── sangam-core/                command pipeline, CommandContext, agenda base, config SPI
├── sangam-runtime-bpmn/        BPMN agenda + behaviors
├── sangam-runtime-cmmn/        CMMN agenda + behaviors
├── sangam-dmn/                 DecisionEngine SPI + adapters: Drools DMN (default), jDMN, feel-scala
├── sangam-feel/                FEEL evaluation for BPMN/CMMN expressions + custom-function SPI
├── sangam-services/            job, task, variable, identitylink, entitylink, eventsub, batch, history
├── sangam-security/            authorization (resources/permissions + ABAC), identity SPI, OIDC/LDAP
├── sangam-storage-jdbc/        MyBatis mappings, dialects, Liquibase changelog
├── sangam-executor/            job acquisition, worker dispatch, timers, retries, incidents
├── sangam-gateway-workers/     pull (long-poll/gRPC stream), push (HTTP/serverless), broker
├── sangam-gateway-events/      Kafka, NATS, RabbitMQ, JMS, SQS/SNS, Azure SB, Pub/Sub, webhooks
├── sangam-sandbox/             secure JS + WASM script runtime
├── sangam-observability/       OpenTelemetry, Micrometer, health
├── sangam-server/              standalone server (REST + gRPC + UI), Docker image, native image
├── sangam-spring-boot-starter/ embedded mode for Spring apps
├── sangam-quarkus-extension/   embedded mode for Quarkus apps
├── sangam-test/                JUnit extension, AssertJ assertions, Testcontainers profiles
├── proto/ + openapi/ + asyncapi/   the language-neutral contracts (source of truth)
├── sdks/  java · dotnet · go · rust · python · node-ts · php · kotlin
├── ui/    cockpit · tasklist · admin · modeler (Preact, from webapps-neo)
└── deploy/  helm chart · k8s operator · docker-compose · terraform examples
```

#### 4.2 Execution core

- **Agenda model from Flowable**: one FIFO agenda per command, operations such as `ContinueProcess`, `TakeOutgoingSequenceFlows`, `TriggerExecution`, `EndExecution` and `ExecuteInactiveBehaviors`.
- **Future operations** for non-blocking calls (`planFutureOperation`).
- **First-class activity instances** (Flowable's `ACT_RU_ACTINST`). This makes Operaton-style **modification** ("start before X", "cancel activity instance Y") straightforward to port without tree reconstruction.
- **No execution-tree compaction** and no `LegacyBehavior`.
- **Debug agenda** (from Flowable), extended with a step debugger in the UI.

#### 4.3 Concurrency: no more optimistic-lock storms (W3)

1. Every command that touches a process instance takes a **row lock on the root instance** (`SELECT … FOR UPDATE` on one row) before it changes the tree. Parallel joins and concurrent message correlation queue up instead of failing.
2. A built-in **retry interceptor** retries remaining optimistic-lock conflicts transparently, with jitter.
3. **Exclusive jobs** are grouped per root instance (Operaton's call-hierarchy option, on by default).
4. **Scale-out option:** route jobs by `hash(rootInstanceId) % partitions` to executor partitions (Kubernetes lease per partition), so one node owns an instance's async work at a time.

#### 4.4 Job executor: best of both (W2)

- **Tables:** `JOB` (executable), `TIMER_JOB`, `DEADLETTER_JOB`, `EXTERNAL_TASK`, as in Flowable's split, which keeps the hot table small. Suspended jobs become a state column instead of a table.
- **Acquisition:** `FOR UPDATE SKIP LOCKED` by default (dialect variants already exist in Operaton's `Job.xml`). Flowable's global acquire lock is kept as a fallback for databases without SKIP LOCKED.
- **Post-commit hand-off** (Flowable `JobAddedTransactionListener`): new async jobs run immediately on the creating node without waiting for a poll.
- **Wake-up bus:** Postgres `LISTEN/NOTIFY`, NATS or Redis pub/sub, so idle nodes do not poll aggressively.
- **Priorities and priority-range pools** (Operaton), due-date ordering, timers preferred.
- **Retries:** ISO-8601 retry cycles (`R3/PT5M`) and lists of intervals (Operaton), then dead-letter + **incident**, with retry or resolve from the UI/API.
- **Virtual threads** for job execution; a bounded concurrency limit per job type.

#### 4.5 Transactions and the outbox

Every state change and its side-effect records commit **in one DB transaction**:
- history events
- outgoing events and webhooks
- worker wake-ups

Side-effect records go to an `OUTBOX` table. A relay publishes them afterwards (at-least-once, ordered per instance). This gives reliable integration with brokers without XA/2PC.

#### 4.6 History (W5)

- **Pipeline from Operaton:** `HistoryEventProducer` → `HistoryEventHandler` (pluggable).
- **Levels from both:** none / instance / activity / audit / full, plus **per-element overrides** (Flowable).
- **Modes:**
  - `sync`: history rows in the same transaction (strict audit)
  - `outbox` (default): projected asynchronously, which keeps runtime transactions short
  - `external`: history goes only to Elasticsearch/OpenSearch, Kafka or a data lake
- **Cleanup:** removal time + TTL enforcement + cleanup windows + parallel cleanup (Operaton); `ROOT_PROC_INST_ID_` + `REMOVAL_TIME_` on every history row so bulk deletes or partition drops are cheap.

#### 4.7 Dynamic changes (union of both)

| Capability | Source |
|---|---|
| Migration plans with validation, auto-mapping and batch execution | Operaton |
| 1→N and N→1 activity mappings, JSON migration documents, pre/post scripts | Flowable |
| Modification: start before/after, cancel activity instance, transition instances | Operaton |
| Change state / move tokens | Flowable |
| **Dynamic injection** of a user task or sub-process into one running instance | Flowable |
| Restart a finished instance from history | Operaton |
| Case (CMMN) migration | Flowable |
| Everything above available as a **batch** with progress monitoring | Operaton batch framework |

---

### 5. Any tech stack: the polyglot layer (W1)

**Principle:** the engine has **no knowledge of your language**. Every integration goes over a published contract (`proto/`, `openapi/`, `asyncapi/`). Java gets no hidden extra power except optional embedded mode.

#### 5.1 Four ways to run a task

| Mode | How it works | Best for |
|---|---|---|
| **Pull worker** | Worker subscribes to a topic → long-poll REST or **gRPC server stream** → lock → complete / fail / BPMN error / extend lock | Long-running services in PHP, .NET, Go, Rust, Python, Node, Java |
| **Push (HTTP)** | Engine calls your endpoint (signed request, idempotency key, timeout) → sync result or async callback | Webhooks, existing REST services, legacy systems |
| **Serverless** | Built-in connectors for AWS Lambda, Azure Functions, Google Cloud Run/Functions, Cloudflare Workers, Knative | Pay-per-use, scale-to-zero |
| **Broker** | Engine publishes a CloudEvent to Kafka / NATS / RabbitMQ / SQS / Service Bus; worker replies on a reply channel with correlation | Event-driven microservices, very high fan-out |
| *Embedded (JVM only)* | JavaDelegate / Spring bean in the same transaction | Java teams that want it, and migration from Camunda/Flowable |

Common rules for all modes:
- Variables are **JSON**.
- Each task carries `taskId`, `idempotencyKey`, `traceparent` and `tenantId`.
- Locks expire, and workers can **heartbeat** to extend them.
- Errors can be technical (→ retries → incident) or business (→ BPMN error / escalation event).

#### 5.2 Cluster-aware long polling (W4)

- Any API node accepts the poll.
- A new task fires a wake-up on the bus, so the waiting poll returns at once, even if it waits on another node.
- With gRPC, a worker keeps one stream open, and the server pushes tasks with flow control (credits), so there is no busy polling.

#### 5.3 SDKs

- **Languages:** Java/Kotlin, .NET (C#), Go, Rust, Python, Node/TypeScript, PHP. Clients are generated from `proto/` and `openapi/`, plus a thin hand-written ergonomic layer.
- **Each SDK provides:**
  - client API (deploy, start, correlate, query, tasks, decisions)
  - worker runtime (subscriptions, concurrency, backoff, heartbeats, graceful shutdown)
  - test helpers: in-memory or Testcontainers engine, time-skipping for timers, process-state assertions (§3.3d L4)
  - query and update handler calls (§3.3d L6)
- **Framework adapters:** Laravel/Symfony (PHP), ASP.NET Core hosted service (.NET), Express/NestJS (Node), FastAPI/Django (Python), Spring/Quarkus (Java).

#### 5.4 Expressions and scripts that are not Java-specific

- **FEEL** is the default for gateways, conditions, I/O mapping and DMN. It looks the same no matter which language the team uses.
- **Scripts:**
  - sandboxed JavaScript (Flowable's secure-JS ideas: class whitelist, CPU/memory/instruction limits)
  - **WASM modules**, which can be written in Rust, Go, C#, AssemblyScript, etc., and run with fuel limits and no network or file access unless granted
- Groovy and other JSR-223 languages only in embedded mode, off by default.

#### 5.5 Deployment modes

| Mode | Use |
|---|---|
| **Standalone server** (Docker image, Helm chart, K8s operator) | Default for every non-JVM stack |
| **Native image** (GraalVM) | Fast start and small memory; sidecar or edge use |
| **Embedded** (Spring Boot / Quarkus starter) | JVM apps that want in-process speed |
| **Managed / SaaS** (later) | Multi-tenant hosted version |

#### 5.6 Database support

Databases are supported in tiers (ANALYSIS R17):
- **Tier 1:** PostgreSQL (reference), Oracle, SQL Server. Full CI on every PR.
- **Tier 2:** MySQL, MariaDB, DB2. Run nightly.
- **Experimental:** CockroachDB, YugabyteDB. They stay experimental until their retry semantics pass the chaos suite.
- **Tests only:** H2.

All schema changes come from one **Liquibase** changelog. DBAs get generated per-DB SQL. CI runs the full test suite on every DB through Testcontainers, reusing Operaton's per-DB profiles.

---

### 6. Event gateway (W11)

- **Event registry from Flowable:**
  - event definitions + channel definitions (JSON deployments)
  - key, tenant and payload detectors (JSON pointer, XPath, headers)
  - correlation keys
  - unique-instance start
- **Channels:** Kafka, NATS JetStream, RabbitMQ, JMS, AWS SQS/SNS/EventBridge, Azure Service Bus/Event Grid, Google Pub/Sub, MQTT (IoT), inbound/outbound **webhooks**.
- **Format:** CloudEvents 1.0 (structured and binary), described in AsyncAPI.
- **Outbound** events always go through the outbox (§4.5).
- **BPMN/CMMN usage:** message start/catch/boundary events, send tasks and CMMN event listeners can bind to channels without code.
- **Lifecycle event stream:** optional stream of every engine lifecycle event (instance started, task created, incident, …). External systems such as analytics, CRM or notifications subscribe instead of polling.

---

### 7. Security and multi-tenancy (W9)

- **Authentication:** OIDC/OAuth2 (Keycloak, Entra ID, Auth0, Okta), API keys/mTLS for workers, LDAP (Operaton plugin) for identity lookup.
- **Authorization:**
  - **Operaton's model**: resources × permissions, grant/revoke, user/group/global, enforced inside SQL queries so lists are filtered efficiently
  - **ABAC conditions** (e.g. "amount < 10,000 and region = user.region") and an optional external policy engine (OPA or Cedar)
- **Tenancy (Flowable + Operaton):**
  - row-level `TENANT_ID_` with automatic tenant checks
  - **or** database-per-tenant routing
  - shared definitions with tenant overrides
  - change-tenant tooling
- **Secrets:** connector credentials come from a secret-provider SPI (Vault, AWS/Azure/GCP secret managers, K8s secrets). They are never stored as process variables.
- **Audit:** user-operation log (Operaton), immutable audit stream option.
- **Variable encryption:** fields marked sensitive are encrypted at rest and masked in the UI and logs.

---

### 8. User interface (W12)

Built on **Operaton webapps-neo** (Preact, Vite, bpmn-js, dmn-js, form-js, i18next, Playwright, a11y checks):

- **Cockpit:**
  - instances, incidents, heatmaps
  - modification and migration wizards
  - batch monitor
  - history
  - live trace link (OTel)
- **Tasklist:** filters, forms (JSON Schema / form-js), claim/delegate, CMMN case view.
- **Admin:** users, groups, tenants, authorizations, channels, secrets, executor status.
- **Modeler:** web modeler for BPMN, CMMN, DMN and forms, with deploy and simulate.
- **Embeddable components** (web components), so a PHP/.NET/React app can show a task form or process diagram without its own UI.

> **License note:** bpmn-js, dmn-js and form-js are under the bpmn.io license. Free use is allowed, but the bpmn.io watermark must stay visible. A CMMN modeler (cmmn-js) is no longer maintained, so a CMMN editor has to be built or forked.

---

### 9. Observability and operations (W6)

- **OpenTelemetry tracing:**
  - one span per command, job and worker task
  - `traceparent` sent to workers and on events, so a trace covers engine → PHP worker → Kafka → .NET worker
- **Metrics:** Micrometer → Prometheus/OTLP (job queue depth, acquisition latency, lock conflicts, worker latency per topic, instance counts, incidents, DMN evaluations). Operaton's meter-log stays for licence-style usage counting.
- **Health:** Operaton's `HealthService` + Kubernetes readiness/liveness probes.
- **Ops:** Helm chart, operator (rolling upgrade with schema lock), backup and restore guide, Grafana dashboards, alert rules.

---

### 10. Honest limits and later options

1. **Throughput ceiling.** A relational engine typically handles thousands of instances per second on a strong database cluster, but not millions. For extreme scale, the Storage SPI allows a later **partitioned log-based backend** (an event-sourced, Raft-replicated design). That is a very large project and should only start when real load needs it.
2. **Code-first workflows and the Serverless Workflow DSL** are removed from scope for now. If they come back, they should compile to the BPMN model; Temporal-style **deterministic replay** of arbitrary code is a different paradigm and stays out of scope.
3. **"Every scenario"** is a direction, not a promise. Stream processing, ETL and low-latency trading belong to other tools. Sangam integrates with them through events.
4. **Merging upstream fixes** gets harder as the fork drifts. Track the upstream repos and cherry-pick security fixes on a schedule.

---

### 11. Roadmap

| Phase | Scope | Exit criteria |
|---|---|---|
| **0. Foundation** | Fork Flowable core; remove `flowable5-*` and other legacy code; rename packages; new immutable config; Liquibase; CI on all DBs; OTel | Existing Flowable BPMN/CMMN test suites pass on Postgres + Oracle + MSSQL + MySQL |
| **1. MVP** | BPMN + DMN (DecisionEngine SPI; TCK run against Drools DMN, jDMN and feel-scala to confirm the default) + job executor (SKIP LOCKED, hand-off, priorities) + pull workers (REST + gRPC) + JSON variables + REST/gRPC API + SDKs for Java, .NET, Go, Node, Python, PHP (with test engine) + API calls by OpenAPI/gRPC reference + Docker image | A PHP worker and a .NET worker complete tasks in one process; 1,000 instances/s benchmark on Postgres |
| **2. Enterprise core** | Authorization port + OIDC; multi-tenancy; history pipeline + outbox + cleanup; per-instance event log + replay testing; query/update handlers; continue-as-new; BPMN semantic diff tool; incidents; batch framework; migration/modification/restart; UI cockpit + tasklist + admin | Feature parity with Operaton for operations teams |
| **3. Universal** | CMMN hardening; event gateway (Kafka, NATS, RabbitMQ, SQS, webhooks, CloudEvents); push and serverless connectors; Rust SDK; WASM scripts; AI agent tasks (A2A) with durable step journal + MCP server; XES/OCEL export; Temporal interop connector; web modeler; Helm + operator | Same process can be started from Kafka, run steps on Lambda and in Go, and be modified from the UI |
| **4. Migration & scale** | Importers for Camunda 7/Operaton and Flowable models; compatibility REST facades; runtime data migration tool; partitioned executors; history to OpenSearch | A real Camunda 7 or Flowable app moves over without rewriting its models |

---

### 12. Legal checklist (Apache 2.0)

- Keep the original `LICENSE` and copy both projects' `NOTICE` content into Sangam's `NOTICE`.
- Keep existing copyright headers in copied files and add "Modified by …" where you change them.
- Do not use "Camunda", "Flowable" or "Operaton" in the product name or logo. Saying "compatible with …" is fine.
- Check third-party licences: Drools DMN (Apache KIE), jDMN and feel-scala are Apache 2.0; the bpmn.io licence requires the watermark.
- Add a dependency-licence scan (e.g. license-maven-plugin + `cyclonedx` SBOM) to CI.

---

### 13. Key upstream files to start from

**Flowable (base):**
- `modules/flowable-engine/src/main/java/org/flowable/engine/impl/agenda/DefaultFlowableEngineAgenda.java` and the other operations in that package
- `modules/flowable-engine/.../impl/interceptor/CommandInvoker.java`
- `modules/flowable-engine-common/.../common/engine/impl/AbstractEngineConfiguration.java` (composition model, to be replaced by a clean SPI)
- `modules/flowable-job-service/.../impl/asyncexecutor/*`
- `modules/flowable-cmmn-engine/.../impl/agenda/operation/*`
- `modules/flowable-event-registry/.../impl/pipeline/*`, `modules/flowable-event-registry-spring/*`
- `modules/flowable-secure-javascript/*`
- `modules/flowable-external-job-rest/*`

**Operaton (ports):**
- `engine/.../impl/cfg/auth/*`, `impl/db/AuthorizationCheck*`, `mapping/entity/Authorization.xml` (authorization)
- `engine-dmn/engine/.../DefaultDmnEngineConfiguration.java`, `engine-dmn/feel-api/*`, `engine-dmn/feel-scala/*` (DMN integration pattern and the optional feel-scala adapter)
- `engine/.../impl/migration/*`, `impl/cmd/ProcessInstanceModification*`, `RestartProcessInstances*` (dynamic changes)
- `engine/.../impl/batch/*` (batch framework)
- `engine/.../impl/history/*`, `impl/jobexecutor/historycleanup/*` (history pipeline and cleanup)
- `engine/src/main/resources/.../mapping/entity/Job.xml` (SKIP LOCKED dialect variants)
- `engine/src/main/resources/.../db/liquibase/*`
- `webapps-neo/*` (UI)
- `test-utils/assert/*`, `test-utils/testcontainers/*`, `test-utils/archunit/*`

---

### 14. Engineering principles and quality system

Goal: a system that lives for **10+ years**, stays **compatible** across upgrades, and reaches high quality in **security, code, reliability and operations**.

"100% pure" cannot be proven for any real system. What *can* be done is to state every quality goal as a **measurable gate** and enforce it automatically on every change. A rule that no machine checks will drift, so every principle below names its enforcement.

#### 14.1 System analysis principles

| # | Principle | What it means for Sangam | Enforcement |
|---|---|---|---|
| A1 | **Requirements are traceable** | Every requirement has an ID (`REQ-…`) and links to its design section, ADR and tests. Each Operaton/Flowable strength from §1.1 becomes a `REQ-KEEP-*`; each weakness from §1.2 becomes a `REQ-FIX-W*` | Traceability matrix generated in CI; a requirement without a test fails the release |
| A2 | **Quality attributes are explicit** | Use the **ISO/IEC 25010** quality model (functional suitability, performance, compatibility, usability, reliability, security, maintainability, portability) with numeric targets (§14.6) | Targets are tested by benchmarks and fitness functions |
| A3 | **Analyse the domain, not the tools** | Model concepts (process, case, decision, task, event, worker, tenant) in a ubiquitous-language glossary before code (Domain-Driven Design) | Glossary in `docs/`; ArchUnit checks package names against bounded contexts |
| A4 | **Decide architecture on trade-offs** | Major decisions go through an ATAM-style review: scenarios, sensitivity points, risks | Recorded as an ADR (A5) |
| A5 | **Write decisions down** | Architecture Decision Records (MADR format, as Operaton already does in `docs/decisions`) | PR template requires an ADR link for architectural changes |
| A6 | **Threat model before build** | STRIDE threat model per component (API, worker gateway, sandbox, event gateway, UI) | Reviewed each release; open high risks block the release |
| A7 | **Learn from the source systems** | Before porting a feature, read its upstream tests and open issues; known upstream bugs become regression tests | Ported features must carry their upstream test cases |

#### 14.2 System design principles

| # | Principle | Application | Enforcement |
|---|---|---|---|
| D1 | **Hexagonal architecture (ports and adapters)** | The core has no dependency on Spring, Quarkus, MyBatis, Kafka or HTTP. Storage, messaging, identity, secrets and scripting are ports (SPIs) with adapters | ArchUnit: `sangam-core` and runtimes may only depend on `sangam-api`, `sangam-model` and SPI modules |
| D2 | **Modular monolith first, services later** | One deployable server with strict internal modules; splitting into services later needs no redesign | Module dependency graph checked in CI; no cycles |
| D3 | **Contract-first APIs** | `proto/`, `openapi/` and `asyncapi/` are the source of truth; code and SDKs are generated from them | Breaking-change detection (`buf breaking`, `oasdiff`) fails the PR |
| D4 | **SOLID and small units** | No class over ~800 lines and no config god-object (fixes W7). Composition over inheritance; immutable value objects | Checkstyle/PMD limits + ArchUnit |
| D5 | **Explicit state, explicit transitions** | Every entity lifecycle (instance, task, job, incident, batch) is a documented state machine; illegal transitions are impossible by construction | State-machine tests generated from the documented models |
| D6 | **Idempotency everywhere** | Every external command accepts an idempotency key; every worker callback is safe to repeat | Contract tests replay each call twice |
| D7 | **Fail safe, degrade gracefully** | Timeouts, circuit breakers and bulkheads per connector; back-pressure on workers; the engine keeps running if a broker is down (the outbox buffers) | Chaos tests (§14.4) |
| D8 | **Secure by design and by default** | Deny by default, least privilege, secure defaults (auth on, scripts sandboxed, no Java serialization) | Default-config security test suite |
| D9 | **Backward compatibility is a feature** | Expand → migrate → contract for every schema and API change; old clients keep working for the support window (§14.7) | Compatibility matrix: N-1 and N-2 SDKs against the current server; old and new engine nodes on one schema during rolling upgrade |
| D10 | **Observability by design** | Every command, job and worker call emits a trace span, metrics and structured logs with instance IDs | Tests assert that spans and metrics exist for each core operation |
| D11 | **Determinism in the core** | No hidden clocks, randomness or thread-locals in the engine core; time and IDs come from injectable providers | Static check bans `System.currentTimeMillis`, `new Date()` and `ThreadLocal` in core packages |
| D12 | **Standards before inventions** | Use BPMN/CMMN/DMN/FEEL/CloudEvents/OpenAPI/OIDC wherever they fit. Sangam extensions live in their own XML namespace and are documented | Conformance kits (§14.4) |

#### 14.3 System development principles

| # | Principle | Application |
|---|---|---|
| P1 | **Trunk-based development** | Small PRs, short-lived branches, feature flags for unfinished work |
| P2 | **Test-first for the engine core** | TDD for behaviors and commands; every bug fix starts with a failing test |
| P3 | **Test pyramid** | Unit → engine integration (H2 + Postgres) → DB matrix (all databases via Testcontainers) → contract tests (SDKs) → end-to-end (UI, Playwright) |
| P4 | **Mutation testing** | PIT on core modules, so tests are proven to catch faults and not just raise coverage |
| P5 | **Property-based and model-based tests** | jqwik generates random BPMN/CMMN models and event orders and checks invariants: no lost tokens, no double completion, history matches runtime |
| P6 | **Fuzz all parsers and inputs** | XML (BPMN/CMMN/DMN), FEEL, JSON variables and API payloads are fuzzed with Jazzer, covering XXE, billion-laughs and deserialization attacks |
| P7 | **Code review rules** | Two reviewers for core and security-sensitive modules; CODEOWNERS per module |
| P8 | **Documentation as code** | Docs, C4 diagrams, ADRs and the API reference live in the repo and build in CI; a feature without docs is not done |
| P9 | **Definition of Done** | Code + tests + docs + ADR (if architectural) + migration (if schema) + changelog entry + security review (if it touches auth, sandbox, parsing or networking) |
| P10 | **Reproducible builds** | Pinned dependencies, Maven wrapper, hermetic builds; the same commit gives the same binary |

#### 14.4 Conformance and correctness

| Area | How it is proven |
|---|---|
| BPMN | **BPMN MIWG** (Model Interchange Working Group) test suite for import/export; execution-semantics suite seeded from the upstream Operaton and Flowable BPMN tests |
| DMN / FEEL | **DMN TCK** (Technology Compatibility Kit); results published per release |
| CMMN | Ported Operaton + Flowable CMMN suites; lifecycle state-machine tests |
| Engine semantics (readable) | Gherkin scenarios for core BPMN/CMMN behaviour, alongside the code tests (§3.3d L3) |
| Model upgrades | Replay testing of recorded instance event logs against each new model version (§3.3d L5) |
| AI agent tasks | Crash-and-resume tests: kill the worker mid-agent-loop and check that no LLM or tool call is repeated (§3.3d L8) |
| Consistency | Jepsen-style tests: kill nodes, partition the network and pause the DB under load, then check that no job is lost or run twice non-idempotently, and that history equals runtime |
| Upgrades | Every release upgrades a populated database from each supported older version, with instances in flight |

#### 14.5 Security programme

| Layer | Standard / practice | Gate |
|---|---|---|
| Secure development process | **NIST SSDF (SP 800-218)**, **OWASP SAMM** maturity tracking | Yearly self-assessment published |
| Application security | **OWASP ASVS Level 3** for API, UI and worker gateway | ASVS checklist tests in CI; external penetration test before each major release |
| Supply chain | **SLSA Level 3** builds, **SBOM** (CycloneDX) for every artefact, **Sigstore**-signed releases and images, **OpenSSF Scorecard** | Release pipeline refuses unsigned or unscanned artefacts |
| Dependencies | OSV-Scanner / OWASP Dependency-Check, Renovate, licence scanning | A critical/high CVE with an available fix blocks the merge |
| Static analysis | Error Prone + NullAway (JSpecify, as Operaton started), SpotBugs + FindSecBugs, CodeQL, Semgrep | Zero new high findings |
| Secrets | Gitleaks pre-commit and in CI; runtime secrets only through the secret-provider SPI | A leaked secret blocks the merge |
| Runtime hardening | Distroless images, non-root, read-only filesystem, seccomp, CIS Kubernetes benchmark for the Helm chart | Trivy/Grype image scan in CI |
| Sandboxing | JS/WASM scripts with CPU, memory, time and instruction limits; no file/network access unless granted per deployment | Escape-attempt test suite + fuzzing |
| Data protection | TLS 1.3, mTLS for workers, field-level encryption of sensitive variables, masking in logs and UI, GDPR/DPDP erase and export APIs | Masking and erasure tests |
| Identity and access | OIDC, MFA through the IdP, deny-by-default authorization, full audit log | Authorization matrix test: every API × every role |
| Vulnerability handling | `SECURITY.md`, private disclosure, GitHub security advisories/CVEs, fix SLA: critical 7 days, high 30 days | Tracked per release |

#### 14.6 Quality targets (ISO/IEC 25010 → numbers)

Starting targets; Phase 1 benchmarks refine them.

| Attribute | Target |
|---|---|
| Functional suitability | 100 % of `REQ-*` traced to passing tests; DMN TCK and BPMN MIWG results published |
| Performance | Per reference process, on 16-vCPU app + 16-vCPU PostgreSQL: R1 (one service task) ≥ 2,000 PI/s; R5 (realistic: DMN, 5 external tasks, join, user task, 20 variables) ≥ 300 PI/s with p99 command latency < 50 ms; job hand-off p99 < 100 ms. These are hypotheses until the Phase-1 benchmark against Operaton/Flowable on the same hardware (ANALYSIS §14, R22) |
| Reliability | 99.95 % availability for a 3-node cluster; zero lost jobs in chaos tests; RPO 0 and RTO < 5 min with a replicated DB |
| Compatibility | All supported DBs green on every commit; SDKs N-1/N-2 compatible; Camunda 7 / Flowable model import passes |
| Security | ASVS L3; zero open critical/high CVEs at release; SLSA L3 |
| Maintainability | Core line coverage ≥ 85 %, mutation score ≥ 70 %; no module cycles; Sonar maintainability rating A |
| Portability | Linux/Windows/macOS, x86-64 and ARM64, JVM and native image, Kubernetes and plain Docker |
| Usability | UI meets WCAG 2.2 AA (axe/pa11y in CI, as webapps-neo already does); i18n for at least 10 languages including Hindi |

#### 14.7 Lifecycle management (maximum life of the system)

- **Versioning:** Semantic Versioning for engine, APIs and SDKs. The API version (`/v1`, gRPC package `sangam.v1`) changes only with a major release.
- **Release train:** a minor release every 3 months; an **LTS** release every 2 years with 5 years of security fixes.
- **Compatibility policy:**
  - Public API and REST/gRPC: no breaking change within a major version.
  - Deprecation: announced, kept for at least 2 minor releases and 12 months, with a migration guide.
  - Database: forward-only Liquibase migrations in expand/contract style, so **rolling, zero-downtime upgrades** work.
  - Models: a deployed BPMN/CMMN/DMN model keeps running after any engine upgrade; running instances are never forced to migrate.
- **Upgrade tooling:** OpenRewrite recipes for Java users (Operaton already uses this approach), SDK codemods, and an upgrade-checker CLI that scans models and config before an upgrade.
- **End of life:** published dates for every version, plus data-export tools so no user is locked in.
- **Dependency lifecycle:** follow Java LTS releases; upgrade frameworks within 6 months of their release; no release-candidate dependencies in releases (Flowable currently builds on RC versions).
- **Governance:** public roadmap, RFC process for big changes, DCO sign-off; consider a neutral foundation once outside contributors join, so the project does not depend on one company.

#### 14.8 Traceability from Operaton and Flowable

| Source | Becomes | Proven by |
|---|---|---|
| Strength kept from Operaton or Flowable (e.g. authorization, event registry) | `REQ-KEEP-<area>` | Ported upstream tests + new Sangam tests |
| Weakness W1–W18 | `REQ-FIX-W<n>` | A **fitness function** that fails if the weakness returns. Examples: W2 → chaos benchmark fails if acquisition conflicts pass a threshold; W7 → ArchUnit fails if a config class exceeds the size limit; W14 → test fails if a Java-serialized variable type can be registered |
| New capability (polyglot SDKs, AI agent tasks, outbox) | `REQ-NEW-<area>` | Contract and end-to-end tests |

#### 14.9 Quality gates in the pipeline

```
PR opened
 ├─ reproducible build ─ unit tests ─ static analysis (Error Prone, NullAway, SpotBugs, CodeQL, Semgrep)
 ├─ ArchUnit + module-cycle check ─ contract breaking-change check (buf, oasdiff)
 ├─ secret scan ─ dependency CVE + licence scan
 ├─ coverage + mutation thresholds (core)
 └─ DB matrix tests (Postgres, Oracle, MSSQL, MySQL, MariaDB, DB2, H2, CockroachDB)
Nightly on main
 ├─ fuzzing, chaos/consistency tests, performance regression benchmarks
 ├─ DMN TCK, BPMN MIWG
 └─ upgrade tests from every supported version
Release
 ├─ SBOM + Sigstore signatures + SLSA provenance
 ├─ image scan + CIS checks for the Helm chart
 ├─ traceability report (all REQ-* green) + ASVS checklist
 └─ published conformance and benchmark results
```

---

### 15. Study status and sources

#### 15.1 What was studied and verified

| Item | How | Result |
|---|---|---|
| Operaton source (whole repo, 2.3.0-SNAPSHOT) | Code-level study of every module | §1 |
| Flowable source (all 100 modules, 8.1.0-SNAPSHOT) | Code-level study of every module | §1 |
| Flowable DMN has no FEEL | `grep -i feel` in `flowable-dmn-engine/src/main/java` | 0 matches: confirmed |
| Flowable has no authorization service | grep for `AuthorizationService` / `AuthorizationEntity` in `flowable-engine` | 0 matches: confirmed |
| Operaton SKIP LOCKED acquisition | grep in `Job.xml` | 7 matches: confirmed; 0 in Flowable job-service |
| Operaton feel-scala version | root `pom.xml` | 1.22.1 (latest release): confirmed |
| BPMN, CMMN, DMN latest versions | Official OMG spec pages | §3.1 |
| DMN 1.7 beta | OMG machine-readable files page | Confirmed |
| DMN engine conformance | Official DMN TCK results page | §3.4 (decision changed) |
| Drools DMN version and licence | Apache KIE DMN page | DMN 1.6, Apache 2.0, conformance level 3 |
| jDMN, feel-scala licences | GitHub repos | Apache 2.0 |
| Serverless Workflow | GitHub repo and releases | 1.0.1, CNCF Sandbox, has CTK |
| Arazzo | OAI GitHub repo | 1.1.0 |
| A2A | GitHub releases | 1.0.1 (May 2026) |
| MCP | Official specification page | Revision 2026-07-28 |
| BPMN MIWG | GitHub repo | Interchange tests only (import/export), not execution; active |

#### 15.2 Not studied yet (next study round)

To decide nothing on assumptions, these remain:

1. **Full spec texts.** BPMN 2.0.2 (~530 pages), CMMN 1.1, DMN 1.6, and the DMN 1.7 Beta 1 PDF (its change bars are members-only, so the comparison with 1.6 has to be done by reading both). Goal: a clause-by-clause coverage checklist, with each executable element mapped to Operaton support, Flowable support and the Sangam plan.
2. **Upstream issue trackers.** Open bugs and long-standing feature requests in Operaton and Flowable (GitHub issues, forums). These are the real-world weaknesses that code reading can miss.
3. **The two GitHub organisations' other repos.** Operaton: docs, Helm charts, extensions, the migration recipe. Flowable: examples and the polyglot external-worker clients (Python, Go, JS, .NET).
4. **Other engines to learn from.** These are not to be copied, but their design lessons matter: Camunda 8 / Zeebe (partitioned log architecture), Temporal (durable execution), Apache KIE / jBPM / Kogito, Apache Airflow, AWS Step Functions, Conductor (Orkes), Kestra, Windmill. They show what modern users expect.
5. **Measured performance.** Benchmarks of both engines on the same Postgres setup, to set real targets for §14.6.
6. **Measured TCK runs.** DMN TCK against feel-scala 1.22.1, Drools DMN and jDMN (see §3.4).
7. **Security history.** Past CVEs of Operaton/Camunda 7 and Flowable, and the classes of bugs behind them (XXE, script injection, deserialization, authorization bypass), to make sure the security programme covers each class.

#### 15.3 Sources

- OMG BPMN: https://www.omg.org/spec/BPMN
- OMG CMMN: https://www.omg.org/spec/CMMN
- OMG DMN 1.6: https://www.omg.org/spec/DMN/1.6
- OMG DMN machine-readable files (1.7 beta): https://www.omg.org/spec/DMN/machine-readable
- DMN TCK results: https://dmn-tck.github.io/tck/
- Apache KIE Drools DMN: https://kie.apache.org/drools/dmn/
- jDMN: https://github.com/goldmansachs/jdmn
- feel-scala: https://github.com/camunda/feel-scala
- Serverless Workflow: https://github.com/serverlessworkflow/specification
- Arazzo: https://github.com/OAI/Arazzo-Specification
- A2A: https://github.com/a2aproject/A2A/releases
- MCP: https://modelcontextprotocol.io/specification
- BPMN MIWG test suite: https://github.com/bpmn-miwg/bpmn-miwg-test-suite

---

### 16. Evidence-driven revisions (from ANALYSIS.md)

The pre-development analysis ([ANALYSIS.md](ANALYSIS.md)) checked every design element against market evidence, production failures of Camunda 7 / Operaton / Flowable, CVEs, and the source code. It produced **35 required changes (R1–R35, ANALYSIS §19)**.

**Precedence.** These changes are part of this design. Where they differ from earlier sections, they win. The most important ones were also applied directly above:
- JDK 25 baseline
- no Rhino
- read-only MCP by default
- database tiers
- performance targets per reference process

Summary by area:

| Area | Revisions |
|---|---|
| Job executor and locking | R1 NOTIFY only from the outbox relay; R2 per-DB acquisition SQL; R3 global lock order + two-step acquisition; R20 fencing tokens + heartbeats; R21 reset-expired for all job tables; R23 concurrency classifier + silent end on concurrent delete; R35 exclusivity checked at completion |
| Outbox and history | R4 commit-visibility guard, dedup, lag SLO; R29 retention on by default, cleanup as a leader-elected service, 256 KB inline variable limit |
| Expressions and DMN | R8 Drools footprint check + jDMN fallback; R10 one pinned FEEL engine with strict null; R33 FEEL type contract |
| Security | R6 WASM / GraalJS isolates, no Rhino; R11 JSON limits; R14 MCP read-only + approvals + taint; R24 one hardened XML factory; R25 no expressions in queries; R26 worker-bound job operations; R27 connector egress control; R28 safe defaults |
| Platform | R5 JDK 25; R7 native image for a light profile only; R9 gRPC streaming with long-poll fallback; R12 Liquibase as a single migration job; R13 OTel span/link rule; R30 bounded caches + 24 h soak gate; R31 UTC/DST timer policy; R32 upgrade matrix over module combinations |
| Scope | R15 XES primary; R16 AsyncAPI for docs, JSON Schema/proto for codegen; R17 DB tiers; R18 SDK tiers (Tier 1: Java, .NET, PHP, TypeScript, Python, Go); R19 denormalised authorization for lists; R22 benchmark-first targets; R34 fill the BPMN gaps of the Flowable base |

---

# Part II — Pre-Development Analysis (from ANALYSIS.md)

## Sangam — Pre-Development Analysis

> Companion to [DESIGN.md](DESIGN.md). Purpose: to check, **before development starts**, whether the design holds together, where it can fail, and how each failure is prevented.
>
> Date: 2026-11-04. Evidence collected from:
> - the two local source trees (Operaton `ccea971`, Flowable `f6017e3`)
> - official standards pages
> - vendor documentation and benchmarks
> - GitHub issues, JIRA and forums
> - CVE and security-notice databases
>
> Sources are given inline.

---

### सारांश (Hindi summary)

- **क्या design टिकेगा?** हाँ, लेकिन evidence के आधार पर **35 सुधार** (R1–R35) ज़रूरी हैं (§19)। इनमें से 6 बहुत ज़रूरी हैं:
  1. NOTIFY को transaction से बाहर करना
  2. Lock का एक तय क्रम और fencing tokens
  3. JDK 25 baseline
  4. Rhino sandbox हटाना
  5. Databases को tiers में बाँटना
  6. MCP server को default में read-only रखना
- **Market में जगह:** "BPMN + CMMN + DMN + authorization + multi-tenancy + पूरा free UI, सब Apache 2.0 में" आज किसी के पास नहीं है **[F]**। यही Sangam की सबसे मज़बूत जगह है।
- **कहाँ नहीं जीतेंगे:** Raw throughput में Zeebe या Temporal से आगे होने का दावा नहीं करेंगे। Code-first developer experience में Temporal आगे है। Low-code integrations में n8n आगे है।
- **Standards gap:** Flowable base में Operaton की तुलना में ये चीज़ें नहीं हैं, जिन्हें port करना होगा:
  - Link events
  - Signal/message/compensation end events
  - Process-level conditional start
  - हर activity पर I/O mapping
  - External-task semantics

  DMN की कमियाँ (boxed expressions, BKM, 1.6) Drools DMN चुनने से भर जाती हैं।
- **Failure zero?** यह साबित नहीं किया जा सकता। §9 के FMEA में जो 24 failure modes मिले, उन सबके लिए रोकने और पकड़ने का तरीका तय है। उनका कुल risk score (RPN) 4,581 से घटकर 712 रह जाता है, यानी लगभग 84% कम **[I]**।
- **Success की संभावना [I]:**

  | किस लक्ष्य की बात है | संभावना |
  |---|---|
  | Technical MVP | 75–85% |
  | पूरा scope (Phase 3), 8–10 engineers की team के साथ | 45–55% |
  | पूरा scope (Phase 3), 3–4 लोगों की team के साथ | 10–20% |
  | Scope को सुझाए गए तरीके से tier करने पर, 6–10 लोगों की team | 60–70% |

  सबसे बड़ा खतरा technology नहीं है। सबसे बड़ा खतरा **scope और team का आकार** है।

---

### 0. Method and rules

#### 0.1 Evidence rules

| Tag | Meaning | Allowed use |
|---|---|---|
| **[F]** | Fact, with a source (URL, file path, or a measurement in this repo) | Can drive a decision |
| **[V]** | Vendor claim: published by the product's own company | Context only; never the sole basis for a decision |
| **[I]** | Inference: reasoning from facts, with its basis shown | Can drive a decision only if the reasoning is written down |
| **[U]** | Unverified / not found | Cannot drive a decision; becomes a study or test item |

Further rules:
- Missing evidence is stated as missing; it is not filled with a guess.
- Every risk gets a **preventive action** and a **detection method** (test, metric or gate).
- "Zero failure" is a target approached through prevention, detection and recovery. No real system can prove it, so it is never claimed as a property.
- Scores in the FMEA (§9), the risk register (§17) and the success estimate (§18) are expert judgements **[I]**, derived from the evidence cited next to them.

#### 0.2 Analyses performed

| # | Analysis | Question | § |
|---|---|---|---|
| 1 | Stakeholders & use cases | Who uses it, for what, and what matters most? | 1 |
| 2 | 5W1H per design element | Why, what, who, when, where, how | 2 |
| 3 | Standards coverage gaps | What the Flowable base lacks, element by element | 3 |
| 4 | Market & competition | How it compares with 14 market products | 4 |
| 5 | SWOT at 4 levels | Architecture, functional, technical, overall | 5 |
| 6 | Technical-choice evidence | Is each choice proven in production elsewhere? | 6 |
| 7 | Interface & conflict analysis | Which components conflict when combined? | 7 |
| 8 | Quality trade-offs (ATAM-style) | Where do goals pull against each other? | 8 |
| 9 | FMEA | How can each part fail; severity, occurrence, detection | 9 |
| 10 | Fault trees | Chains to lost jobs, double execution, stuck instances, breaches | 10 |
| 11 | STRIDE + CVE classes | Attacks, and what already happened upstream | 11 |
| 12 | Upstream production failures | How Camunda 7 / Operaton / Flowable fail in the field | 12 |
| 13 | Scenario simulation | Step-by-step walk-throughs, including crashes | 13 |
| 14 | Capacity model | Are the performance targets reachable? | 14 |
| 15 | Codebase & porting effort | Code size and work involved | 15 |
| 16 | Pre-mortem | "It is 2028 and Sangam failed. Why?" | 16 |
| 17 | Risk register | Project-level risks | 17 |
| 18 | Success probability + comparison verdict | Overall chances; better than the others in which ways? | 18 |
| 19 | Required design changes | What DESIGN.md must change as a result | 19 |

---

### 1. Stakeholders and use cases

#### 1.1 Stakeholders

| ID | Stakeholder | Needs most | Evidence that the need is real |
|---|---|---|---|
| S1 | **App developer in any language** (PHP, .NET, Go, Node, Python, Java) | Simple SDK, local test engine, clear errors | Requests for an official .NET SDK in Flowable #4134 **[F]**; Camunda dropped its Go client in 8.6 **[F]**, so non-Java users are under-served |
| S2 | **Process modeller / business analyst** | Visual BPMN/DMN/CMMN modeller, simulation | Flowable has no OSS UI **[F]**; the Camunda 8 web modeler needs a production licence **[F]** |
| S3 | **Business user** | Tasklist, forms, mobile-friendly UI | Same as S2 |
| S4 | **Operations / SRE** | Easy deploy, metrics, safe upgrades, incident handling | Upgrade failures (Flowable #4008, #2814), history growth (Flowable #3422: 2 TB bytearray) **[F]** |
| S5 | **Security / compliance officer** | Authorization, audit, secure defaults, CVE handling | Camunda Notice 157 RCE; Flowable CVE-2026-91197 XXE **[F]** |
| S6 | **Teams migrating from Camunda 7** | Model/API compatibility, free licence | C7 CE end of life Oct 2025; C8 "not a drop-in replacement" **[F]** |
| S7 | **Teams migrating from Flowable OSS** | A UI, authorization, the same models | Flowable OSS has no UI and no authorization **[F]** |
| S8 | **AI-agent builders** | Durable agent steps, approvals, MCP | Temporal's $300M round driven by agents **[F]**; MCP attack research **[F]** |
| S9 | **ISVs / SaaS builders embedding a workflow engine** | Multi-tenancy, permissive licence, white-labelling | Kestra paywalls multi-tenancy; n8n is fair-code; Camunda 8 has a production licence **[F]** |
| S10 | **Process-mining analysts** | Clean event logs (XES/OCEL) | pm4py supports OCEL 2.0 **[F]** |

#### 1.2 Use cases with priority (MoSCoW)

| ID | Use case | Stakeholders | Priority |
|---|---|---|---|
| UC1 | Start a process from any language and complete service steps with a worker in that language | S1 | **Must** |
| UC2 | Model, deploy and version BPMN + DMN in a web modeller | S2 | **Must** |
| UC3 | Human tasks with forms, claim/delegate, deadlines | S3 | **Must** |
| UC4 | Monitor, retry, modify and migrate running instances | S4 | **Must** |
| UC5 | Fine-grained permissions and tenant isolation | S5, S9 | **Must** |
| UC6 | Import and run existing Camunda 7 / Operaton / Flowable models | S6, S7 | **Should** (Phase 4 in DESIGN, core import earlier) |
| UC7 | Start or continue processes from Kafka/RabbitMQ/webhooks, and emit events | S1, S4 | **Should** |
| UC8 | Case management with CMMN | S2, S3 | **Should** |
| UC9 | AI agent step with budget, approval and crash-safe resume | S8 | **Should** |
| UC10 | Expose processes to AI assistants via MCP | S8 | **Could** (read-only first) |
| UC11 | Export event logs for process mining | S10 | **Could** |
| UC12 | Serverless execution of steps (Lambda, Functions) | S1 | **Should** |

---

### 2. 5W1H per design element

| Element | What | Why (evidence) | Who uses / owns | When (phase) | Where | How |
|---|---|---|---|---|---|---|
| **Flowable core as base** | Agenda engine, shared services, CMMN, event registry | Simpler core with no tree compaction [F §1.1]; CMMN event listeners exist only in Flowable [F §3.3] | Core team | 0 | `sangam-core`, runtimes | Fork, strip `flowable5-*` (83k lines [F §15]), rename |
| **Operaton ports** | Authorization, migration, modification, restart, batch, history pipeline, cleanup, SKIP LOCKED | Operaton is stronger in each [F §1.1]; ~27k lines + tests [F §15] | Core team | 1–2 | `sangam-services`, `sangam-security` | Re-implement against the Flowable model, using the Operaton code and tests as specification |
| **DMN engine SPI** | Drools DMN default, jDMN and feel-scala optional | TCK: Drools 3388/3391, DMN-Scala 1.9 2850/3391 [F]; Flowable DMN has no FEEL [F]; Operaton does not recognise DMN 1.6 namespaces [F §3.2] | Modellers | 1 | `sangam-dmn` | Adapter per engine; TCK run in CI |
| **Job executor** | SKIP LOCKED, post-commit hand-off, priorities, fencing | Flowable deadlock reports on all DBs [F]; SKIP LOCKED proven in River, pg-boss, Hatchet [F/V] | Ops | 1 | `sangam-executor` | Two-step acquisition, fixed lock order (§7 C2) |
| **Outbox + history projector** | One transaction for state and side effects; asynchronous history | Synchronous history slows commits 5.7× (Flowable 2018 benchmark) [V]; outbox is a documented pattern [F] | Ops, auditors | 2 | `sangam-services/history` | Polling relay with commit-visibility guard (§7 C3) |
| **Worker gateway** | REST long-poll, gRPC streaming, push, broker | Zeebe moved to gRPC streaming and cut activation delay [F]; non-Java clients are under-served [F] | S1 | 1 | `sangam-gateway-workers` | Streaming with long-poll fallback (§6 #6) |
| **SDKs** | Clients + workers + test engine | Temporal needed a shared Rust core for 7 SDKs; Camunda dropped Go [F] | S1 | 1–3 | `sdks/` | Tiered SDKs (§19 R18) |
| **Event gateway** | Kafka, NATS, RabbitMQ, SQS…; CloudEvents | No broker integration in Operaton [F]; Flowable event registry exists [F] | S1, S4 | 3 | `sangam-gateway-events` | Port the Flowable event registry; outbox for outbound |
| **Authorization** | Resources × permissions + ABAC | Flowable has none [F]; C7 SQL authorization slows with many groups (CAM-7441, CAM-5364) [F] | S5 | 2 | `sangam-security` | SQL filtering with a denormalised permission table; OPA/Cedar for single checks |
| **Multi-tenancy** | Row-level + DB-per-tenant | Flowable has both [F]; schema lock deadlock on tenant registration (Flowable #4215) [F] | S9 | 2 | `sangam-security`, storage | Tenant registration outside the schema lock |
| **Sandbox** | WASM + JS isolates | Rhino-style sandbox escaped: CVE-2025-0982, CVSS 10 [F] | S5 | 3 | `sangam-sandbox` | Chicory/Endive WASM; GraalJS isolate; no Rhino |
| **UI** | Cockpit, tasklist, admin, modeller | Flowable has none [F]; Operaton webapps-neo exists (Preact) [F] | S2–S4 | 2–3 | `ui/` | Fork webapps-neo; bpmn-js/dmn-js/form-js |
| **AI `agentTask` + MCP** | A2A agent step with durable journal; MCP server | Durable journals proven (Temporal, Restate) [V]; MCP tool poisoning and CVE-2025-6514 [F] | S8 | 3 | runtime extension | Journal per step; read-only MCP by default |
| **XES/OCEL export** | Process-mining logs | pm4py supports both [F]; OCEL support in commercial tools is unclear [U] | S10 | 3 | history projector | XES primary, OCEL validated against pm4py |
| **CMMN module** | Full CMMN, isolated | No successor spec [F]; Flowable CMMN richer [F §3.3] | S2 | 3 | `sangam-runtime-cmmn` | Plug-in; ArchUnit isolation |
| **Observability** | OTel traces, Micrometer | Operaton has no OTel/Micrometer [F]; OTel overhead about 5% p95 (Elastic) [V] | S4 | 0 | `sangam-observability` | Span links for async work (§6 #11) |
| **Schema management** | Liquibase, tiered DBs | Liquibase lock races on Oracle/DB2 (#1584) [F]; Camunda dropped Galera [F] | S4 | 0 | `sangam-storage-jdbc` | One migration job; DB tiers |

---

### 3. Standards coverage gaps (element level)

Source: code study of both trees (parse handlers, behaviours, validators). **[F]**

#### 3.1 BPMN: what the Flowable base lacks compared with Operaton (must be added)

| Gap | Operaton evidence | Decision |
|---|---|---|
| Link intermediate catch/throw events | `IntermediateCatchLinkEventActivityBehavior` | **Must**: port |
| Signal end event, message end event, message intermediate throw | `BpmnParse` service-task-like events | **Must**: port |
| Compensation end event; compensation event sub-process | `parseCompensationEventSubprocess` | **Must**: port |
| Process-level conditional start; automatic conditional evaluation on variable change | `ConditionalEventBehavior`, `createConditionEvaluation()` | **Must**: port |
| I/O mapping on any activity (`inputOutput`); connector framework | `parseActivityInputOutput`, connect plugin | **Must**: needed for polyglot workers and for Camunda import |
| Send task with delegates; receive task correlated by `messageRef` | `BpmnParse` | **Must** |
| DMN business-rule task with `decisionRef` and result mappers | `DmnBusinessRuleTaskActivityBehavior` | **Must**. Flowable's businessRuleTask runs Drools *rules*, not DMN |
| Separate asyncBefore/asyncAfter on the multi-instance body and inner activity; retry cycles; job priorities; incidents | Operaton job/MI parsing | **Must** |
| External-task API semantics (topics, fetch-and-lock with variables, extend lock, BPMN error) | `ExternalTaskActivityBehavior` | **Must**: the polyglot worker API builds on this |
| Process instance modification, restart, async correlation, condition-evaluation API | Operaton services | **Must** (Phase 2) |
| `camunda:` / `operaton:` extension elements in the parser | Flowable reads only plain `camunda:` attributes (`BpmnXMLUtil` l.338) | **Should**: required for UC6 |

#### 3.2 BPMN: missing in both engines

| Gap | Decision |
|---|---|
| Complex gateway (parsed, ignored at runtime) | **Should**: rarely used, but needed for full executable conformance; Phase 3 |
| Event-based gateway with `instantiate` / parallel instantiate | **Could** |
| Multiple / parallel-multiple start, intermediate and boundary events | **Should**: Phase 3 |
| Standard loop characteristics | **Should**: simple to add |
| Data stores, data input/output associations at runtime | **Could**: low demand; JSON variables cover most needs |
| Executable message flows between pools (correlation through the diagram) | **Could**: correlation by API remains the default |

#### 3.3 DMN and CMMN

- **DMN.**
  - Gaps in both engines: boxed expressions (context, invocation, list, relation, function definition), BKM, item-definition type checking, DMN 1.6.
  - **These are filled by choosing Drools DMN**: conformance level 3 and DMN 1.1–1.6 support per Apache KIE [F]. This confirms the §3.4 DMN decision with element-level evidence.
  - The adapter must still provide Operaton's implicit required-decision chaining and Flowable's PRIORITY / OUTPUT ORDER hit policies and decision services. Drools covers these as part of standard DMN [I, verify in the TCK run].
- **CMMN.**
  - Flowable is clearly stronger: it has timer, user and generic event listeners, which Operaton lacks entirely (registration commented out in `DefaultCmmnElementHandlerRegistry`) [F]. It also has extra lifecycle rules.
  - Missing in both: discretionary items / planning tables, case file items, case-file-item on-parts.
  - Decision: **Could**, consistent with the "retire-safe CMMN" policy.

---

### 4. Market and competition

Evidence: official docs, vendor benchmarks and GitHub data as of 2026-11-04; full sources are listed in §20.

| Product | Model | DB / storage | Language reach | Licence | Best published performance |
|---|---|---|---|---|---|
| Camunda 8 | BPMN, DMN (no CMMN) | Raft log + RocksDB; secondary storage Elasticsearch/OpenSearch, or RDBMS from 8.9 | Java + Spring official; Go deprecated | Camunda License: **production needs a paid licence** | ~240 PI/s on 3 brokers, 480 on 6 (32 KiB payload) [V] |
| Temporal | Code (replay) | Cassandra / PG / MySQL | 7 official SDKs | MIT | Cloud >150k actions/s top tier [V]; self-hosted not published |
| AWS Step Functions | JSON (ASL) | Managed | Any AWS SDK | Proprietary | Throttled per account; 256 KiB payload, 25k-event history limit [F] |
| Conductor OSS / Orkes | JSON DSL | Redis / PG / MySQL / Cassandra + ES | 8 SDKs | Apache 2.0 + commercial | 210 workflows/s, 1,450 tasks/s on a laptop [V] |
| Kestra | YAML | PG / MySQL (OSS) | Any language as scripts | Apache 2.0; **RBAC, multi-tenancy paid** | Not found |
| n8n | Visual low-code | PG / MySQL / SQLite + Redis | JS/Python nodes | Fair-code (not OSI) | 220 exec/s per instance [V]; slows over time [F/3P] |
| Airflow | Python DAG | PG / MySQL | Python | Apache 2.0 | Batch-oriented; starts "within minutes" [F] |
| Apache KIE | BPMN, DMN, SW | RDBMS | Java/Quarkus | Apache 2.0 (incubating) | Not found |
| Flowable | BPMN, CMMN, DMN (JUEL) | 8+ RDBMS | Java; REST | Apache 2.0; **UI commercial** | 6,605 PI/s trivial process with history, 16 vCPU (2018) [V] |
| Operaton | BPMN, CMMN, DMN (FEEL) | 7 RDBMS | Java; REST | Apache 2.0, no paid edition | C7 guide: 100–500 PI/s on a laptop [V] |
| Restate | Code (journal) | Own log | 7 SDKs | BSL 1.1 | Not published |
| Inngest | Code | Managed/self | TS, Python, Go | SSPL → Apache later | Not comparable |
| Windmill | Scripts + flows | PG only | 10+ script languages | AGPL + proprietary EE | Leads its own benchmark [V] |
| Hatchet / DBOS | Code | PG | Several | MIT | Hatchet: 10k tasks/s single node; 2,000 runs/s on an 8-vCPU RDS [V] |

**Positioning conclusions:**
1. **Open slot [F]:** no product ships BPMN + CMMN + DMN + fine-grained authorization + multi-tenancy + cockpit/tasklist/modeller together under Apache 2.0. This is Sangam's defensible position.
2. **Migration market [F]:** Camunda 7 CE reached end of life (Oct 2025), and Camunda 8 is neither drop-in nor free in production.
3. **Do not claim throughput leadership [I]:** partitioned-log engines (Zeebe) and Temporal Cloud scale further than a single-primary RDBMS.
4. **Competitive movement to watch [F]:** Camunda 8.9 added RDBMS secondary storage. That removes the "you need Elasticsearch" argument against Camunda, but not the licence argument.
5. **SDK breadth is expensive [F]:** Camunda deprecated its Go client, and Temporal built a shared Rust core. Eight first-class SDKs is an ongoing cost, not a one-time task (see §19 R18).

---

### 5. SWOT at four levels

#### 5.1 Architecture level

| Strengths | Weaknesses |
|---|---|
| Agenda core without tree compaction [F]; first-class activity instances (`ACT_RU_ACTINST`) [F] | Large inherited codebase: ~520k lines to own after the fork [F §15] |
| Hexagonal ports keep Spring/MyBatis/brokers out of the core (D1) | The single RDBMS is the throughput ceiling [F, C7 sizing guide] |
| Outbox decouples history, events and wake-ups from commits | More moving parts (relay, projector, wake-up bus) than either upstream [I] |
| CMMN isolated as a plug-in | Porting Operaton features means re-implementing them on a different model [I §15] |

| Opportunities | Threats |
|---|---|
| Storage SPI leaves room for a partitioned backend later | Camunda 8.9 RDBMS storage narrows the architectural gap [F] |
| DB-per-tenant gives horizontal scale for SaaS without new storage [I] | Upstream Flowable keeps changing the same core; merge cost rises over time [I] |
| gRPC streaming is proven in Zeebe [F] | Native image is unlikely to work for the full engine [F/I §6 #13] |

#### 5.2 Functional level

| Strengths | Weaknesses |
|---|---|
| Union of both engines' features: ad-hoc sub-process, event registry and CMMN listeners (Flowable) plus link/signal/compensation events, conditional start, modification and restart (Operaton) [F §3] | Many features to port before parity (§3.1: 11 BPMN gaps) |
| DMN at conformance level 3 via Drools [F] | CMMN has declining demand [F]; work that may be little used |
| Migration + modification + dynamic injection + replay testing | Complex gateway / multiple events missing in both upstreams [F] |
| AI agent tasks with a durable journal: unique among BPMN engines [I] | Code-first DX is weaker than Temporal's [F §4] |

| Opportunities | Threats |
|---|---|
| Camunda 7 teams need a free, compatible home [F] | A2A/MCP are young and change fast [F]; rework risk |
| Process-mining export is a differentiator for audit-heavy industries [I] | Users may expect low-code connectors like n8n's (hundreds of integrations) [F] |

#### 5.3 Technical level

| Strengths | Weaknesses |
|---|---|
| Proven building blocks: SKIP LOCKED, outbox, Liquibase, OTel, gRPC streaming [F §6] | Several "proven-with-caveats" choices need per-DB work: SKIP LOCKED on Oracle/MySQL, Liquibase locks [F] |
| JSON-only variables remove a whole CVE class (C7 deserialization notices) [F] | Eight databases multiply test and fix cost [F/I] |
| One hardened XML factory is possible (Operaton already hardened; Flowable not) [F] | Drools adds a large dependency tree to the SBOM [I] |

| Opportunities | Threats |
|---|---|
| JDK 25 virtual threads without pinning (JEP 491) [F] | NOTIFY's global commit lock if misused [F] |
| WASM on the JVM is maturing (Chicory → Endive under the Bytecode Alliance) [F] | Sandbox escapes (CVE-2025-0982 class) [F]; MCP attacks (CVE-2025-6514) [F] |

#### 5.4 Overall

| Strengths | Weaknesses |
|---|---|
| A clear, empty market position [F §4]; Apache 2.0; standards-complete; free UI | Scope is very large for one team (§15, §17) |
| Strong evidence base: most risks are known and have counter-measures | No funded company or community yet [F §4: every competitor above Operaton has funding] |

| Opportunities | Threats |
|---|---|
| C7 end of life; Flowable OSS without UI; AI-agent demand | Being "good at everything" can mean being finished with nothing; competitors move faster with money [I] |

---

### 6. Technical-choice evidence review

Full evidence was collected per choice. Summary:

| # | Choice | Verdict | Top risk (evidence) | Mitigation adopted (§19) |
|---|---|---|---|---|
| 1 | SKIP LOCKED queues; NOTIFY wake-up | Proven with caveats; NOTIFY inside the transaction is **Risky** | NOTIFY takes a cluster-wide lock at commit; about 2.9k → 60k writes/s once batched (DBOS 2026) [F]; PG bloat from old xmin [F]; MySQL gap locks (bug 91209) [F]; Oracle FETCH FIRST + FOR UPDATE quirks [F] | R1, R2 |
| 2 | Root-instance row lock | Proven with caveats | Flowable instance-lock deadlocks on MSSQL, Oracle, PG, MySQL (forum threads) [F] | R3 |
| 3 | Outbox + relay | Proven with caveats | Polling `id > last` skips late commits; duplicates [F] | R4 |
| 4 | Flowable executor at scale | Proven with caveats | Only vendor numbers (4–6k jobs/s) [V] | R22 |
| 5 | Drools DMN embedded | Proven on JVM; unproven in native image | Build-time codegen needed for native [F/I]; heavy dependencies | R8 |
| 6 | gRPC streaming | Proven with caveats (Zeebe) | L7 proxies, connection pinning, max message size, orphaned locks [F] | R9 |
| 7 | WASM / JS sandbox | Chicory/GraalJS proven with caveats; **Rhino risky** | CVE-2025-0982 (CVSS 10) [F] | R6 |
| 8 | FEEL everywhere | Proven with caveats | Silent `null`; behaviour changed between feel-scala versions (#582) [F] | R10 |
| 9 | JSON-only variables | **Proven** | JSON DoS (depth/size) [I] | R11 |
| 10 | Liquibase | Proven with caveats | Lock-table races on Oracle/DB2 (#1584); stuck locks [F] | R12 |
| 11 | OTel through async work | Proven with caveats | Wrong parent/link model creates huge traces [I] | R13 |
| 12 | Virtual threads | Proven on JDK 24+; risky on 21 | Pinning on `synchronized` before JEP 491 [F] | R5 |
| 13 | Native image | **Risky** | MyBatis/OGNL/runtime deployment vs closed world [F/I] | R7 |
| 14 | A2A / MCP / journal | A2A/MCP **risky**; journal proven | Tool poisoning, prompt injection, CVE-2025-6514 [F] | R14 |
| 15 | XES / OCEL | XES proven; OCEL proven with caveats | Thin OCEL consumer support [F/U] | R15 |
| 16 | CloudEvents / AsyncAPI 3 | Proven for docs; codegen risky | Generator gaps (`allOf`) [F] | R16 |
| 17 | Eight databases | **Risky** | Matrix cost; CockroachDB requires retries on 40001 [F] | R17 |
| 18 | Authorization in SQL | Proven with caveats | 1 s → 30 s queries (CAM-7441); >280 groups slow (CAM-5364) [F] | R19 |

---

### 7. Interface and conflict analysis

Components were checked pairwise (N²). These pairs conflict unless a rule is set:

| ID | Components | Conflict | Resolution (rule) |
|---|---|---|---|
| C1 | Wake-up bus × command transaction | NOTIFY at commit serializes all job-creating commits [F] | Wake-ups come only from the outbox relay, batched; polling as fallback |
| C2 | Root-instance lock × job acquisition | Opposite lock order gives a deadlock [F/I] | **Global lock order:** root instance → executions → jobs. Acquisition commits first (mark owned), execution locks the instance in a new transaction. `NOWAIT`/timeout + retry |
| C3 | Outbox relay × history / UI freshness × vacuum | A slow relay delays history and UI; outbox rows add bloat [I] | Relay-lag SLO and alert; partitioned outbox with pruning; UI reads runtime tables for live state |
| C4 | gRPC streaming × executor partitions × Kubernetes load balancing | Stream lands on a node that does not own the partition; streams stick to one pod [F/I] | Any node can dispatch any job (partitions only guide timers and acquisition); MAX_CONNECTION_AGE; un-lock unacknowledged jobs when a stream closes |
| C5 | Native image × MyBatis × Drools × runtime deployment | Closed-world limits [F/I] | Native only for a "worker gateway / light" profile; the full engine runs on the JVM |
| C6 | Several FEEL engines × behaviour after upgrade | Same condition evaluated differently by Drools, jDMN and feel-scala [I] | One FEEL engine for BPMN/CMMN expressions, pinned per model version; strict-null mode |
| C7 | Virtual threads × JDBC pool | Unlimited virtual threads just queue on a small pool [I] | Semaphore per job type; pool sizing rule; JFR pinning check |
| C8 | SQL authorization × ABAC × tenancy × 8 dialects | Query-plan explosion [F/I] | Denormalised permission table; ABAC only for single-resource checks; plan tests per Tier-1 DB |
| C9 | MCP server × authorization | A prompt-injected LLM acts with the user's rights [F] | MCP read-only by default; write tools create an approval task; tool output is tainted data |
| C10 | OTel × "no ThreadLocal in core" (D11) | OTel context is ThreadLocal-based [I] | Explicit context object in the core; the OTel bridge lives in adapters |
| C11 | Liquibase × rolling upgrade × DB-per-tenant | Schema lock deadlocks with tenant registration (Flowable #4215) [F] | Migrations as one Kubernetes Job before rollout; tenant registration outside the schema lock |
| C12 | Lock expiry × long jobs × heartbeats | Expired lock gives a second execution [F, Flowable forum] | Fencing token (`lockVersion`); heartbeats; lock time per job type |
| C13 | History cleanup × engine start-up | Cleanup job reconfiguration fails the start (CAM-8866) [F] | Cleanup as a leader-elected service, never part of start-up |
| C14 | CMMN plug-in × shared services | Hidden dependency would block retirement [I] | ArchUnit rule; `scopeType` only |
| C15 | AsyncAPI codegen × multi-language SDKs | Generator gaps [F] | Generate types from JSON Schema/proto; AsyncAPI for docs and contract tests |
| C16 | Partition routing × exclusive jobs × external workers | Exclusive external jobs block each other (Flowable #3509) [F] | Exclusivity check per root instance at completion time, not per job at acquisition |
| C17 | JSON variables × FEEL dates | Zoned vs unzoned comparison errors (FEEL/SCALA-01008) [F] | Written type contract (JSON → FEEL); mismatches rejected at deployment validation |

---

### 8. Quality trade-offs (ATAM-style)

| Trade-off | Tension | Decision | Sensitivity point |
|---|---|---|---|
| Consistency vs throughput | Instance locks and synchronous writes make things correct but slower | Correctness first: instance lock + outbox | Per-instance concurrency; history mode |
| Audit strictness vs latency | Synchronous history = immediate audit; outbox = faster commits | `outbox` default, `sync` per deployment | Relay lag |
| Security vs ease of use | Auth on, scripts sandboxed, no expressions in queries | Secure by default; a "dev mode" profile for local use only | Default profile |
| Compatibility vs clean design | Camunda/Flowable import wants old extensions | Import-time conversion, not runtime legacy paths | Importer coverage |
| Breadth vs quality | 8 DBs, 8 SDKs, 3 standards, AI, events | **Tier everything** (DB tiers, SDK tiers, MoSCoW) | Team size |
| Portability vs performance | Generic SQL vs per-DB tuned SQL | Per-DB acquisition SQL for Tier-1 DBs | Dialect count |
| Native start-up vs features | Native image restricts runtime deployment | JVM for the full engine; native for light profile | — |

---

### 9. FMEA (failure mode and effects analysis)

S = severity, O = occurrence, D = detection difficulty, each 1–10; RPN = S×O×D. "Before" is the upstream situation; "after" is with the Sangam counter-measures. Scores are judgements **[I]** based on the evidence column.

| # | Failure mode | Effect | Evidence | S | O | D | RPN before | Prevention | Detection | O′ | D′ | RPN after |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| F1 | Job executed twice after lock expiry | Duplicate payment/email | Flowable forum threads (P3) | 9 | 6 | 6 | 324 | Fencing token, heartbeats, idempotency keys | Jepsen test; duplicate-completion metric | 2 | 3 | 54 |
| F2 | Job lost (never runs) | Instance stuck | Flowable #3913, #3782 | 9 | 4 | 7 | 252 | Reset-expired for all job tables; outbox | "Oldest due job age" alert | 2 | 2 | 36 |
| F3 | Deadlock between instance and job locks | Failed commands, retries | Flowable forum (all DBs) | 6 | 7 | 4 | 168 | Global lock order; two-step acquisition | Per-DB deadlock chaos test; classifier metric | 3 | 2 | 36 |
| F4 | OLE storm at parallel joins | Stalled flows | Flowable #2124; Camunda forum | 7 | 7 | 4 | 196 | Instance lock + retry interceptor | Model-based tests | 2 | 3 | 42 |
| F5 | Commit throughput collapse via NOTIFY | System-wide slowdown | Recall.ai, DBOS | 8 | 5 | 6 | 240 | NOTIFY only from relay, batched | Commit-latency SLO | 1 | 3 | 24 |
| F6 | Queue table bloat | Slower acquisition over time | PgQue PR #80 | 6 | 6 | 5 | 180 | Autovacuum tuning; idle-in-transaction timeouts | xmin-age alert | 3 | 2 | 36 |
| F7 | History tables grow without bound | Disk full, slow queries | Flowable #3422 (2 TB) | 7 | 8 | 3 | 168 | Retention on by default; payload limit; partitions | Table-size metrics | 2 | 2 | 28 |
| F8 | History cleanup blocks start-up | Outage on deploy | CAM-8866 | 8 | 3 | 5 | 120 | Cleanup as leader-elected service | Start-up test with cleanup conflicts | 1 | 2 | 16 |
| F9 | Memory leak (caches, script engines) | OOM, restarts | CAM-10553; Operaton #2761 | 7 | 5 | 6 | 210 | Bounded caches, weak classloader refs | 24 h soak gate | 2 | 2 | 28 |
| F10 | Timer burst on DST change | Thousands of extra instances | Operaton #3973 (open) | 8 | 4 | 7 | 224 | UTC storage, `lastFiredInterval`, explicit timezone | DST property tests | 1 | 2 | 16 |
| F11 | XXE in a secondary parser | Data disclosure | CVE-2026-91197; local code [F] | 8 | 4 | 6 | 192 | One hardened XML factory; ban others by rule | Semgrep/ArchUnit; fuzzing | 1 | 2 | 16 |
| F12 | Expression injection via queries/filters | RCE | Camunda Notice 157; Operaton default `true` [F] | 10 | 4 | 5 | 200 | No expressions in queries at all | Security default tests | 1 | 2 | 20 |
| F13 | Script sandbox escape | RCE | CVE-2025-0982 | 10 | 3 | 6 | 180 | WASM / isolates; no Rhino; no host access | Escape test suite | 1 | 4 | 40 |
| F14 | Worker accesses another worker's jobs | Data leak, sabotage | Flowable #4282 (open) | 8 | 5 | 5 | 200 | Worker-bound job operations | Authorization matrix tests | 1 | 2 | 16 |
| F15 | SSRF through push connectors | Internal network access | Inference from §5.1 | 8 | 4 | 6 | 192 | Egress allow-list; block private/metadata ranges | SSRF test suite | 1 | 3 | 24 |
| F16 | Prompt-injected MCP/agent action | Unauthorized changes | MCP research; CVE-2025-6514 | 9 | 5 | 7 | 315 | Read-only MCP; approval tasks; taint; budgets | Red-team tests; audit alerts | 2 | 4 | 72 |
| F17 | FEEL `null` treated as false | Wrong branch taken silently | Camunda forum | 7 | 6 | 7 | 294 | Strict-null mode → incident | Lint at deployment | 2 | 3 | 42 |
| F18 | FEEL behaviour changes after upgrade | Different decisions | feel-scala #582 | 8 | 4 | 7 | 224 | Pinned FEEL engine per model version; TCK | Replay testing (L5) | 1 | 2 | 16 |
| F19 | Upgrade script fails for some module combinations | Failed upgrade | Flowable #4008, #2814 | 8 | 5 | 4 | 160 | Liquibase; module-combination upgrade matrix | Upgrade CI | 2 | 2 | 32 |
| F20 | Liquibase lock stuck or racing | Blocked deploys | Liquibase #1584 | 6 | 4 | 3 | 72 | Single migration job; session lock | Deploy check | 2 | 2 | 24 |
| F21 | Authorization queries become slow | UI timeouts | CAM-7441, CAM-5364 | 6 | 6 | 4 | 144 | Denormalised permissions; benchmarks with 300 groups | Query-latency SLO | 2 | 2 | 24 |
| F22 | gRPC streams break behind proxies | Workers idle | Camunda forum | 5 | 6 | 4 | 120 | Long-poll fallback; documented proxy settings | Worker-idle alert | 2 | 2 | 20 |
| F23 | False incident when instance deleted during job | Noise, wasted retries | Operaton #4017 | 4 | 5 | 4 | 80 | Concurrent delete ends job silently | Unit test | 1 | 2 | 8 |
| F24 | Performance target missed | Product credibility | C7 guide: DB is the limit | 7 | 6 | 3 | 126 | Benchmark first; targets per reference process (§14) | Nightly benchmark | 3 | 2 | 42 |

**Totals:** RPN before 4,581, after 712, which is about an **84% reduction [I]**. The highest remaining risks are F16 (AI/MCP), F1 (duplicate execution), F17 and F4. They stay on the watch list.

---

### 10. Fault trees for the top hazards

**T1: Side effect executed twice** (OR):
- Lock expired while the job was running (AND):
  - lock time < job duration, **and**
  - no heartbeat, **and**
  - no fencing check on completion → **blocked** by R20 (fencing) + heartbeats
- Worker retried after a network timeout, **and** the external system is not idempotent → **reduced** by idempotency keys (the SDKs send them by default); **residual**: external systems that ignore keys
- Outbox relay crashed after publish and before mark → consumer dedup on `eventId` (R4)

**T2: Job never executes** (OR):
- Lock owner died and there is no sweeper for that table → R21 (reset-expired for all tables)
- Hand-off lost after commit (node crash) → acquisition picks it up (the job row is committed)
- Wake-up lost → polling fallback

**T3: Instance stuck or corrupted** (OR):
- OLE at a join without retry → R3/R4 (instance lock + retry)
- Delete raced with a running job → R23
- Model upgrade changed semantics → R10 (pinned FEEL) + L5 replay testing

**T4: Security breach** (OR):
- XML parser not hardened → R24 (single factory)
- Expression in query → R25
- Script escape → R6
- Cross-worker access → R26
- SSRF → R27
- Prompt-injected tool call → R14

---

### 11. STRIDE threat model with upstream CVE classes

| Component | S (spoofing) | T (tampering) | R (repudiation) | I (info disclosure) | D (denial of service) | E (privilege) | Upstream evidence | Control |
|---|---|---|---|---|---|---|---|---|
| REST/gRPC API | OIDC, mTLS | Signed requests for push | Audit log | No SQL/stack traces in errors | `queryMaxResultsLimit`, rate limits | Authorization matrix | C7 Notices 16, 24, 35 | §14.5, R28 |
| Worker gateway | Worker identity (API key/mTLS) | Fencing token | Completion log | Variable filter per worker | Credits, back-pressure | Worker-bound jobs | Flowable #4282 | R20, R26 |
| Parsers (BPMN/DMN/CMMN) | — | Deployment signing (optional) | Deployment audit | XXE | Entity expansion, size limits | — | CVE-2026-91197, C7 Notices 4, 17, 34, 55 | R24 |
| Expressions / scripts | — | — | — | Sandbox, no host access | Fuel/CPU limits | No expressions in queries | C7 Notice 157; CVE-2025-0982 | R6, R25 |
| Variables | — | JSON only | — | Field encryption, masking | Depth/size limits | No deserialization | C7 Notices 1, 2, 6, 14, 18, 25 | R11 |
| Connectors | — | Response validation | Call log | Secrets from secret provider | Timeouts, bulkheads | Egress allow-list | SSRF inferred | R27 |
| UI | Session hardening | CSRF tokens | — | XSS-safe rendering of names | — | Role-based screens | C7 XSS/CSRF notices (9, 10, 15, 48, 114, 124, 123, 3, 12, 22, 50) | §14.5 |
| AI agent / MCP | Agent card allow-list | Taint tool output | Journal + audit | Data minimisation to LLM | Budgets | Read-only default, approvals | MCP tool poisoning; CVE-2025-6514 | R14 |
| Distribution | — | Signed artefacts | SBOM | No dev consoles (H2) | — | — | C7 Notices 60, 75, 77 (H2 console RCE) | §14.5 SLSA |

---

### 12. Upstream production failure evidence (summary)

18 recurring problems were found (P1–P18). Status against the design:

| Status | Problems |
|---|---|
| Covered by the original design | P8 slow history queries (external history), P11 long transactions (outbox), P12 cluster issues, P14 DB ceiling (acknowledged), P15 CMMN stagnation, P17 worker integration |
| Partly covered; completed by §19 | P1 OLE at joins, P3 duplicate execution, P5 deadlocks, P6 history growth, P13 upgrades |
| Gaps found; fixed by §19 | P2 exclusive external jobs, P4 stale locks, P7 cleanup issues, P9 memory leaks, P10 DST timers, P16 FEEL dates, P18 false incidents |

Verified directly in the local code **[F]**:
- Flowable `ProcessDiagramLayoutFactory.parseXml` creates `DocumentBuilderFactory.newInstance()` with no hardening (line 102).
- Operaton `enableExpressionsInStoredQueries = true` by default (`ProcessEngineConfigurationImpl` line 784).

Upstream defaults that Sangam must not copy **[F]**:

| Setting | Operaton default | Flowable default |
|---|---|---|
| Authorization | off | — (no authorization service) |
| Query result limit | `Integer.MAX_VALUE` | — |
| Retries | three immediate retries | three retries |
| Job lock time | 5 min | 1 h |
| SKIP LOCKED | off | — |
| History cleanup | not scheduled | off (365-day keep once enabled) |
| Safe XML flags | — | `false` in the engine configs; only the Spring Boot properties turn them on |

---

### 13. Scenario simulation (walk-throughs)

Each scenario was traced step by step through the components in DESIGN.md, with the §19 changes applied. "✔" marks a step the design handles; "⚠" marks a residual risk.

#### S1: Order process across stacks (happy path)
1. A PHP shop calls `POST /v1/process-instances` with an idempotency key.
   - The API authenticates (OIDC) and checks the `CREATE_INSTANCE` permission. ✔
   - Transaction T1 inserts the instance, executions, activity instance, JSON variables, and the first external task. It also writes outbox rows for history and wake-up, then commits. ✔
2. DMN business-rule task "discount" (Drools adapter) runs inside T1 and writes its result variable. ✔
   - The FEEL type contract checks the date variables, so there is no zoned/unzoned surprise. ✔
3. The outbox relay sends a batched wake-up. A .NET worker holding a gRPC stream gets the task, and the server consumes a credit. ✔
4. The worker completes the task with its fencing token, and transaction T2 runs:
   - takes the instance lock;
   - completes the task;
   - the parallel gateway forks two branches, an external task (Go) and an HTTP push to a Lambda.
5. Both branches finish at almost the same time and arrive at the join, in T3 and T4.
   - T3 takes the root-instance lock first, and T4 waits on it (lock order: instance → executions → jobs).
   - Result: no OLE, no deadlock. ✔
6. A user task appears in the tasklist. It is visible only to the right group and tenant (authorization query on the denormalised table). ✔
7. The process ends. The outbox emits a CloudEvent `order.completed` to Kafka. The projector writes history, and the XES export picks it up nightly. ✔

#### S2: Worker crashes mid-task
- The .NET worker dies after charging a card but before completing the task.
- The lock expires (lock time comes from the job type's p99 runtime). The task is re-delivered with a new fencing token. ✔
- The worker SDK sends the same idempotency key to the payment API, so the payment is not repeated **if the payment API honours keys**. ⚠ Residual: external systems without idempotency.
- If the old worker comes back and tries to complete, its stale token is rejected. ✔

#### S3: Engine node crashes right after commit
- The job row is committed, so the hand-off on the dead node is lost, but the job is not.
- Acquisition on another node picks it up through SKIP LOCKED within the poll interval. ✔
- The outbox rows are committed and published by the relay on a surviving node (relay leadership via lease). ✔

#### S4: Two messages correlate to the same instance at the same moment
- Both commands take the root-instance lock in order. The second waits, then sees the updated state. ✔
- If the lock wait exceeds the timeout, the retry interceptor retries with jitter. ✔
- On MSSQL, the dialect classifier maps error 1205 (deadlock victim) to "retry". ✔

#### S5: `agentTask` crashes mid-loop
- The agent has made 3 LLM calls and 2 tool calls, all recorded in the journal.
- The worker restarts. The journal replays the recorded results, so no LLM call or tool call is repeated, and the loop continues from step 6. ✔
- A tool returns text with an embedded instruction to "migrate all instances". The output is marked tainted, and the MCP/engine write action requires a human approval task. ✔ ⚠ Residual: approval fatigue if approvals are too frequent.

#### S6: Rolling upgrade with a schema change and a new model version
1. The Kubernetes Job runs Liquibase (expand step only). Old nodes keep working. ✔
2. New nodes roll out. Old and new run on the same schema (N-1 compatibility test). ✔
3. Model v2 is deployed. Replay testing runs recorded v1 event logs against v2 and reports differences before instances are migrated. ✔
4. FEEL behaviour is pinned per model version, so v1 instances keep v1 semantics. ✔
5. The contract step (dropping old columns) runs one release later. ✔

#### S7: DST change with an hourly timer
- The cycle is stored with an explicit timezone, and due dates are stored in UTC. The `lastFiredInterval` check stops the repeated local hour from firing again.
- Result: one instance per hour, not thousands. ✔

#### S8: Kafka is down for 2 hours
- Outbound events wait in the outbox, and the relay backs off. Commands keep committing. ✔
- An outbox-size alert fires. Partitions keep the table healthy. ✔
- Inbound correlation stops, and that is visible in the event-gateway lag metric. ⚠ Residual: business impact of delayed events; this is operational, not a failure of the design.

#### S9: Tenant isolation
- A user of tenant A queries tasks. The tenant filter and the authorization filter are both added in SQL, so no tenant-B rows can appear. ✔
- Under DB-per-tenant, the query goes to tenant A's database, and tenant registration does not take the schema lock (C11). ✔

#### S10: Malicious BPMN upload
- A DTD with external entities is rejected by the single hardened parser, and entity expansion is capped. ✔
- A script task tries to reach `java.lang.Runtime`. The WASM/isolate sandbox has no host access, so the attempt is denied and logged. ✔

**Simulation result [I]:** all 10 scenarios complete correctly with the §19 changes. Without them, S2 (duplicate run), S4 (deadlock on some DBs), S7 (DST burst) and S10 (Flowable's diagram-layout parser) would fail in ways already seen upstream.

---

### 14. Capacity and performance model

#### 14.1 Evidence

| Source | Result |
|---|---|
| Flowable 6.3 benchmark (2018), 16 vCPU + local PG [V] | Empty process: 6,605 PI/s with history, 37,453 without. 50 variables + user task: about 350/s. Parallel sub-processes with timers: about 144/s |
| Camunda 7 sizing guide [V] | Notebook: 100–500 PI/s; "the natural limit is the database" |
| Hatchet [V] | 2,000 runs/s at 83% CPU on an 8-vCPU RDS; at least 5 transactions per task |
| Zeebe [V] | About 240 PI/s on 3 brokers with a 32 KiB payload; scales by partitions |

#### 14.2 Write model for a reference process [I]

Reference process "R5":
- start
- 1 DMN decision
- 5 external service tasks
- 1 parallel fork/join
- 1 user task
- 20 JSON variables
- audit history through the outbox

| Phase | Transactions | Row writes (approx.) |
|---|---|---|
| Start + DMN | 1 | 30 |
| 5 external tasks (lock + complete) | 10 | 60 |
| Join | 2 | 10 |
| User task (create + complete) | 2 | 15 |
| End | 1 | 10 |
| History projection (relay) | batched | 100 |
| **Total** | **~16** | **~225** |

At **1,000 PI/s** this means about 16,000 transactions/s and 225,000 row writes/s on one primary database. That is at the upper limit of a large, well-tuned PostgreSQL server [I]. It is far above the Flowable data point for comparable processes (about 350/s for a process with 50 variables and a user task) [V].

#### 14.3 Revised targets (replace DESIGN.md §14.6 performance row)

| Reference process | Target on 16-vCPU app + 16-vCPU PG (Tier-1) | Basis |
|---|---|---|
| R1: start → 1 service task → end, history via outbox | ≥ 2,000 PI/s | Flowable trivial-process numbers [V], with outbox instead of synchronous history |
| R5: realistic process (above) | ≥ 300 PI/s; p99 command latency < 50 ms | Write model above; Flowable 350/s data point [V] |
| Job hand-off latency | p99 < 100 ms | Post-commit hand-off + streaming |
| Scale beyond one database | DB-per-tenant / tenant sharding | Linear with database count for multi-tenant loads [I] |

These are **hypotheses to be confirmed in the Phase-1 benchmark**, which also runs Operaton and Flowable on the same hardware as a baseline. Benchmarks report *completed* instances per second, use realistic payloads, have history on, and state the hardware. This follows Bernd Rücker's method [F].

#### 14.4 Workers (Little's law)

Concurrent tasks in flight = arrival rate × task duration. For example, R5 at 300 PI/s × 5 tasks × 200 ms = 300 concurrent tasks. That needs a worker pool sized above that number, plus a DB pool sized for the engine side, not for the workers [I]. The SDK documentation will include this formula.

---

### 15. Codebase and porting effort (measured)

Measured on the local clones on 2026-11-04 by counting lines of `src/main` Java files (comments and blank lines included). **[F]**

| Code area | Files | Lines |
|---|---|---|
| **Operaton total** (main code) | 5,672 | 520,924 |
| Operaton test files | 3,659 | — |
| **Flowable total** (main code) | 5,615 | 603,390 |
| Flowable test files | 2,417 | — |
| Flowable `flowable5-engine` (to be removed) | 748 | 83,316 |
| Flowable BPMN engine | 1,115 | 124,676 |
| Flowable engine-common | 383 | 40,424 |
| Flowable CMMN engine | 557 | 69,575 |
| Flowable DMN engine (replaced by Drools DMN adapter) | 148 | 13,469 |
| Flowable job service | 172 | 19,505 |
| Flowable event registry | 138 | 14,331 |
| Operaton authorization (`impl/cfg/auth`) | 6 | 1,867 |
| Operaton migration | 99 | 8,702 |
| Operaton batch | 62 | 5,202 |
| Operaton history | 73 | 9,736 |
| Operaton history cleanup | 13 | 1,338 |
| Operaton CMMN | 151 | 16,307 |
| Operaton DMN engine module | 192 | 13,850 |
| Operaton REST | 608 | 56,233 |

What this means:
- The Flowable base is about 0.5 million lines even after removing `flowable5-engine` **[F]**.
- The Operaton packages to port add up to about 27,000 lines of core code before tests **[F]**.
- **Authorization is under-counted.** It also spreads through query mappings (`Authorization.xml`, `Commons.xml`) and many commands, so porting it touches every Flowable query **[I]**.
- **Porting means re-implementation.** Operaton's migration and modification code is built on the PVM tree; the Flowable base uses the agenda and `ACT_RU_ACTINST` **[I]**.
- Operaton's 3,659 test files are a valuable specification for the ported features **[F]**.

#### 15.1 Effort estimate [I]

Assumptions:
- Experienced engine developers.
- Ported/re-implemented engine code including tests progresses at about 40–60 lines per person-day.
- New platform code (gateways, SDKs, UI) is estimated per component.

| Work package | Person-months (range) |
|---|---|
| Phase 0: fork, strip, rename, config rework, Liquibase consolidation, CI DB matrix, OTel | 12–18 |
| BPMN gaps (§3.1) + Camunda/Operaton extension import | 10–16 |
| Executor hardening (SKIP LOCKED per DB, fencing, lock order, classifier, timers/DST) | 8–12 |
| DMN SPI + Drools/jDMN/feel-scala adapters + TCK | 4–6 |
| Authorization + multi-tenancy + OIDC | 12–18 |
| History pipeline + outbox + cleanup + event log + replay testing | 10–14 |
| Migration / modification / restart / batch | 10–14 |
| Worker gateway (REST + gRPC) + push/serverless connectors | 8–12 |
| SDKs: Tier 1 (Java, .NET, PHP, TypeScript, Python, Go) incl. test engines | 18–30 |
| UI (cockpit, tasklist, admin, modeller from webapps-neo) | 18–28 |
| Event gateway (port event registry + channels + CloudEvents) | 6–10 |
| AI agentTask + journal + MCP (read-only first) | 5–8 |
| CMMN hardening + isolation + converter | 6–10 |
| Security programme (threat models, fuzzing, sandbox, ASVS, pen-test prep) | 8–12 |
| Docs, examples, benchmarks, release engineering | 8–12 |
| **Total to the end of Phase 3** | **≈ 143–220 person-months** |

With a team of 8–10 engineers, that is roughly **15–28 months** to Phase 3 (143 ÷ 10 to 220 ÷ 8, before ramp-up). A 3–4 person team would need about **3–6 years** for the full scope [I]. This is the single most important planning fact in this analysis.

---

### 16. Pre-mortem: "It is 2028 and Sangam failed. Why?"

| Likely cause | Early warning sign | Prevention now |
|---|---|---|
| Scope too large; nothing finished | Phase 1 slips more than 30% | Strict MoSCoW (§1.2); ship MVP = BPMN + DMN + workers + Tier-1 SDKs + basic UI |
| Team too small for the codebase | Review backlog grows; CVE fixes are late | Size the team to §15.1, or cut the scope |
| Authorization port stalls the core | Queries rewritten piecemeal | Design the permission model and query filter first as one work package |
| Performance target missed publicly | Benchmarks below the claims | Publish only measured numbers (§14.3) |
| Upstream Flowable fixes not merged | Fork drift grows | Scheduled upstream sync; security watch on Flowable/Operaton advisories |
| SDKs become stale | Issues pile up in non-Java SDKs | SDK tiers; generated core plus thin ergonomic layer |
| No adoption / community | Few outside contributors | Target the C7-migration audience with import tools and guides |
| Camunda 8 or another vendor fills the open slot | Licence changes from competitors | Move fast on the differentiators: free UI + standards + authorization |
| AI standards churn | A2A/MCP breaking releases | Keep agent features behind an adapter; version them |
| Security incident damages trust | First CVE handled badly | `SECURITY.md`, private disclosure and fix SLAs from day one |

---

### 17. Project risk register

Probability (P) and impact (I) are rated 1–5; scores are **[I]**.

| ID | Risk | P | I | Score | Mitigation | Owner role |
|---|---|---|---|---|---|---|
| PR1 | Under-staffing relative to scope | 4 | 5 | 20 | Team plan from §15.1; scope tiers | Project lead |
| PR2 | Authorization port complexity | 4 | 4 | 16 | Separate design spike in Phase 0 | Architect |
| PR3 | Performance below targets | 3 | 4 | 12 | Benchmark-first, revised targets | Performance lead |
| PR4 | Fork drift from upstream Flowable | 4 | 3 | 12 | Sync schedule, minimal changes in hot files early | Core lead |
| PR5 | Multi-DB maintenance cost | 4 | 3 | 12 | DB tiers | QA lead |
| PR6 | SDK maintenance cost | 4 | 3 | 12 | SDK tiers, generation | DX lead |
| PR7 | AI/MCP security incident | 3 | 4 | 12 | Read-only default, red team | Security lead |
| PR8 | UI effort under-estimated | 3 | 4 | 12 | Reuse webapps-neo; MVP UI scope | UI lead |
| PR9 | Competitor licence/feature changes | 3 | 3 | 9 | Differentiate quickly | Product lead |
| PR10 | Trademark/licence mistake | 2 | 4 | 8 | §12 legal checklist in DESIGN.md | Project lead |
| PR11 | Key-person dependency | 3 | 3 | 9 | Pairing, docs as code | Project lead |
| PR12 | Funding/time runs out before MVP | 3 | 5 | 15 | MVP first; milestones with demos | Sponsor |

---

### 18. Success probability and comparison verdict

#### 18.1 "Will it be better?" by dimension (evidence-based)

| Dimension | vs Flowable | vs Operaton | vs market leaders | Verdict |
|---|---|---|---|---|
| **Performance** | About equal on the same core; better acquisition (SKIP LOCKED, hand-off), outbox history [I] | Better: agenda core, no tree compaction, SKIP LOCKED by default [I] | **Not better** than Zeebe/Temporal at very large scale [F/I]; competitive at small/medium scale on one database [V/I] | Better than both bases; not a throughput leader |
| **Functionality / standards** | Better: adds Operaton events, I/O mapping, authorization, FEEL DMN [F §3] | Better: adds ad-hoc sub-process, CMMN listeners, event registry, DMN 1.6 [F §3] | Unique combination (BPMN+CMMN+DMN+AI) [F §4] | **Better** |
| **Database** | Same breadth, tiered for quality | Broader | Broader than Camunda 8 (Raft + secondary), Temporal, Windmill [F] | **Better** (with tiers) |
| **Tech-stack reach** | Much better: Tier-1 SDKs vs REST only [F] | Much better: Operaton has a Java client only [F] | Comparable to Temporal/Conductor in SDK count; less mature at first [F/I] | Better than both bases; on par later |
| **Ease of use / understanding** | Better: has a UI [F] | Better: modern UI instead of AngularJS [F] | Less easy than n8n/Kestra for simple automations [F] | Better than both bases |
| **Modern UI** | Better (Flowable has none) | Better (webapps-neo as a base) | Comparable to Camunda 8 if delivered; Camunda's is licensed [F] | Better than both bases |
| **Plug and play** | Better: one container + Postgres, Helm | Similar to Operaton Run | Similar to Kestra/Conductor; simpler than Camunda 8 self-managed [F/I] | Good |

#### 18.2 Success probability [I]

Each number is a judgement from the evidence in §6, §9, §15 and §17.

| Success definition | Probability | Main driver |
|---|---|---|
| Technical MVP (BPMN + DMN + workers + Tier-1 SDKs + basic UI) works and meets the revised R1/R5 targets | **75–85%** | Building blocks are proven (§6); the main risks have counter-measures (§9) |
| Full scope to Phase 3 on the §15.1 timeline with 8–10 engineers | **45–55%** | Size of the authorization, UI and SDK work |
| Full scope to Phase 3 with a team of 3–4 | **10–20%** | Effort of 143–220 person-months (§15.1) |
| Scope tiered as recommended (§19), team of 6–10 | **60–70%** | Removes the largest project risks |
| Market adoption | **Not estimable** [U] | Depends on community, funding and timing, not only on engineering |

**Why not 100%:** the residual risks are external systems without idempotency (S2), AI/MCP attack evolution (F16), performance of realistic processes (F24) and project resources (PR1). Each has a detection method, so a failure is noticed early and contained. It is not prevented with certainty.

---

### 19. Required design changes (to apply in DESIGN.md)

| ID | Change | Source |
|---|---|---|
| R1 | NOTIFY only from the outbox relay, batched; never inside a command transaction; polling fallback | §6 #1, C1, F5 |
| R2 | Per-DB acquisition SQL (Oracle two-step, MSSQL UPDLOCK+READPAST+ROWLOCK on an index seek, MySQL lock-footprint tests); autovacuum and xmin alerts | §6 #1, F6 |
| R3 | Global lock order: root instance → executions → jobs; two-step acquisition; `NOWAIT`/timeout + retry | C2, F3 |
| R4 | Outbox: commit-visibility guard, consumer dedup by `eventId`, lag SLO, partitioned table | §6 #3, C3 |
| R5 | JDK baseline **25 LTS** (was 21+) | §6 #12 |
| R6 | No Rhino; scripts via WASM (Chicory/Endive) or GraalJS isolates; no host access | §6 #7, F13 |
| R7 | Native image only for a light/worker-gateway profile | §6 #13, C5 |
| R8 | Measure Drools DMN footprint; jDMN as fallback; TCK across three engines | §6 #5 |
| R9 | gRPC streaming with REST long-poll fallback, MAX_CONNECTION_AGE, un-lock on stream close, proxy guide | §6 #6, C4, F22 |
| R10 | One pinned FEEL engine for BPMN/CMMN per model version; strict-null mode (null → incident) | §6 #8, C6, F17, F18 |
| R11 | JSON depth/size limits; Jackson default typing disabled | §6 #9 |
| R12 | Liquibase as a single Kubernetes Job with a session lock; online-safe change allowlist per DB | §6 #10, C11, F20 |
| R13 | OTel rule: parent spans for synchronous continuations, links for async jobs/timers/workers; explicit context in the core | §6 #11, C10 |
| R14 | MCP server read-only by default; write tools create approval tasks; tainted tool output; agent-card allowlist | §6 #14, C9, F16 |
| R15 | XES as primary export; OCEL validated against pm4py in CI | §6 #15 |
| R16 | AsyncAPI for docs and contract tests; event types generated from JSON Schema/proto | §6 #16, C15 |
| R17 | **DB tiers:** Tier 1 PostgreSQL, Oracle, SQL Server (every PR). Tier 2 MySQL, MariaDB, DB2 (nightly). Experimental: CockroachDB, YugabyteDB. H2 for tests only | §6 #17 |
| R18 | **SDK tiers:** Tier 1 Java, .NET, PHP, TypeScript, Python, Go (generated core + ergonomic layer + test engine). Tier 2 Rust, Kotlin (generated) | §4, PR6 |
| R19 | Authorization: denormalised permission table for list queries; ABAC/OPA/Cedar only for single-resource checks; benchmark 10k tasks × 300 groups per Tier-1 DB | §6 #18, C8, F21 |
| R20 | Fencing tokens (`lockVersion`) on every lock; stale completions rejected; lock time per job type; heartbeats | P3, C12, F1 |
| R21 | Reset-expired sweeper for **all** job tables, timers included | P4, F2 |
| R22 | Revised performance targets per reference process (§14.3); benchmark against Operaton/Flowable on the same hardware | §14, F24 |
| R23 | Concurrent instance delete ends a job silently (no retries, no incident); per-dialect concurrency classifier (deadlock/OLE/lock-timeout); MSSQL `READ_COMMITTED_SNAPSHOT` start-up check | P5, P18, F23 |
| R24 | One hardened XML factory; ban other XML factories by Semgrep/ArchUnit; entity and size limits | CVE-2026-91197, F11 |
| R25 | No expression evaluation in queries, filters or REST input; saved filters use literal values + fixed placeholders | C7 Notice 157, F12 |
| R26 | Worker-bound job operations; bulk operations need admin permission | Flowable #4282, F14 |
| R27 | Connector egress control: allow-list, private/metadata ranges blocked, redirect limits, DNS pinning | F15 |
| R28 | Safe defaults: authorization on, `queryMaxResultsLimit` 1,000, SKIP LOCKED on, retry `R3/PT1M` with jitter, no SQL/stack traces in errors | §12 |
| R29 | Retention on by default (TTL warning, not refusal); cleanup as a leader-elected service; max inline variable 256 KB, larger as references; history partitioned by removal time | P6, P7, F7, F8 |
| R30 | Bounded caches with weak classloader references; cache metrics; 24 h soak gate | P9, F9 |
| R31 | Timer policy: UTC due dates, explicit timezone on cycles, `lastFiredInterval`, catch-up policy, DST property tests | P10, F10 |
| R32 | Upgrade matrix over module combinations and DB-per-tenant; behaviour-changing defaults behind a flag for one LTS | P13, F19 |
| R33 | FEEL type contract for JSON variables; zoned/unzoned mismatch rejected at deployment validation; defined behaviour for decision errors | P16, C17 |
| R34 | Fill the BPMN gaps of the Flowable base (§3.1) as Must items; gaps in both (§3.2) as Should/Could | §3 |
| R35 | Exclusivity checked per root instance at completion, not per job at acquisition (external workers) | P2, C16 |

---

### 20. Sources

Standards and conformance:
- OMG BPMN https://www.omg.org/spec/BPMN · CMMN https://www.omg.org/spec/CMMN · DMN 1.6 https://www.omg.org/spec/DMN/1.6 · DMN 1.7 beta https://www.omg.org/spec/DMN/1.7/Beta1
- DMN TCK https://dmn-tck.github.io/tck/ · Apache KIE DMN https://kie.apache.org/drools/dmn/

Market:
- Camunda 8.8 https://docs.camunda.io/docs/reference/announcements-release-notes/880/880-announcements · secondary storage https://docs.camunda.io/docs/self-managed/concepts/secondary-storage/ · licence https://camunda.com/blog/2024/04/licensing-update-camunda-8-self-managed/ · scalability https://camunda.github.io/zeebe-chaos/tags/scalability · Go client deprecation https://camunda.com/blog/2024/09/deprecating-zbctl-and-go-clients/ · job streaming https://camunda.com/blog/2024/03/reducing-job-activation-delay-zeebe/ · benchmarking method https://blog.bernd-ruecker.com/how-to-benchmark-your-camunda-8-cluster-48ada4b047b6 · C7 sizing https://docs.camunda.io/docs/components/best-practices/architecture/sizing-your-environment-c7
- Temporal https://docs.temporal.io/cloud/limits · funding https://www.businesswire.com/news/home/20260217453156/en
- Step Functions quotas https://docs.aws.amazon.com/step-functions/latest/dg/limits.html
- Conductor https://thenewstack.io/orkes-to-maintain-conductor-project-as-netflix-steps-back/ · https://orkes.io/blog/running-a-billion-workflows-per-month-with-netflix-conductor/
- Kestra https://kestra.io/docs/architecture/deployment-architecture · https://www.kestra.io/docs/enterprise/enterprise-edition
- n8n https://docs.n8n.io/hosting/scaling/performance-benchmarking
- Airflow https://airflow.apache.org/docs/apache-airflow/3.3.2/authoring-and-scheduling/event-scheduling.html
- Apache KIE board https://whimsy.apache.org/board/minutes/kie.html
- Flowable benchmark https://www.flowable.com/blog/engineering/flowable-6-3-0-performance-benchmark · async executor https://www.flowable.com/blog/engineering/handling-asynchronous-operations-with-flowable-part-3-performance-benchmarks · OSS comparison https://documentation.flowable.com/latest/develop/oss-comparison
- Operaton https://github.com/operaton/operaton
- Restate https://restate.dev/blog/the-anatomy-of-a-durable-execution-stack-from-first-principles/ · Inngest https://www.inngest.com/docs/usage-limits/inngest · Windmill https://windmill.dev/docs/misc/benchmarks/competitors · Hatchet https://docs.hatchet.run/self-hosting/benchmarking · DBOS https://dbos.dev/blog/dbos-vs-aws-step-functions-benchmark

Technical choices:
- NOTIFY https://recall.ai/blog/postgres-listen-notify-does-not-scale · https://dbos.dev/blog/postgres-listen-notify-scalability
- Queue bloat https://github.com/NikolayS/PgQue/pull/80 · MySQL https://bugs.mysql.com/bug.php?id=91209
- Outbox https://oneuptime.com/blog/post/2026-07-22-transactional-outbox-duplicate-events/view
- Flowable deadlocks https://forum.flowable.org/t/transaction-deadlock-act-ru-execution/7381 · https://forum.flowable.org/t/deadlock-update-act-ru-execution/12403
- WASM https://bytecodealliance.org/articles/endive-and-the-next-chapter-of-webassembly-on-the-jvm · GraalJS sandbox https://graalvm.org/jdk25/security-guide/sandboxing · Rhino CVE-2025-0982 https://cvefeed.io/vuln/detail/CVE-2025-0982
- FEEL https://docs.camunda.io/docs/components/modeler/feel/language-guide/feel-error-handling/ · https://github.com/camunda/feel-scala/issues/582
- Liquibase https://github.com/liquibase/liquibase/issues/1584
- OTel messaging https://github.com/open-telemetry/semantic-conventions/blob/main/docs/messaging/messaging-spans.md
- Virtual threads https://foojay.io/today/virtual-thread-pinning-field-guide/
- MCP security https://hol.org/blog/mcp-tool-poisoning-ai-agent-protocol-attack-surface
- OCEL 2.0 https://arxiv.org/html/2403.01982v1
- Authorization performance https://jira.camunda.com/browse/CAM-7441 · https://jira.camunda.com/browse/CAM-5364

Upstream failures and security:
- Flowable issues #2124, #3509, #3913, #3782, #3422, #3866, #4008, #2814, #4215, #4216, #4282 https://github.com/flowable/flowable-engine/issues
- Operaton issues #3973, #3984, #2761, #4017 https://github.com/operaton/operaton/issues
- Camunda JIRA CAM-8866, CAM-9964, CAM-10553, CAM-7974 https://jira.camunda.com
- Camunda security notices https://docs.camunda.org/security/notices/
- CVE-2026-91197 https://www.thehackerwire.com/vulnerability/CVE-2026-91197/ (CVE ID taken from a third-party site; the underlying unhardened parser was confirmed in local code)

---

# Part III — Independent End-to-End Review (verbatim)

## Sangam — Independent End-to-End Review

**Review date:** 4 November 2026 (India)  
**Inputs:** `ANALYSIS.md` (791 lines) and `DESIGN.md` (759 lines).  
**Decision:** Proceed with a bounded product-validation and engineering-validation stage. Do not freeze the current design as an implementation-ready specification or commit to its full delivery plan yet.

### 1. कार्यकारी निष्कर्ष

आपका प्रस्ताव एक उपयोगी reusable workflow product बन सकता है। इसकी सबसे अच्छी दिशा है: अलग-अलग applications में human approvals, business rules, external systems और audit को एक भरोसेमंद platform से चलाना। लेकिन दोनों documents अभी engineering ambition, implementation plan और market claims को कुछ जगह एक ही स्तर पर रखते हैं।

मेरी मुख्य सलाह: **पहले चुने हुए ग्राहक का वास्तविक workflow पूरा करके दिखाइए; उसके बाद उसी evidence से engine में आवश्यक बदलाव तय कीजिए।** Flowable को fork करना एक विकल्प है, अभी प्रमाणित रूप से सबसे अच्छा विकल्प नहीं। Camunda 7 migration प्राथमिक बाजार हो तो Operaton पर कम बदलाव वाला रास्ता भी मापना चाहिए।

सबसे पहले सुधारने वाली बातें हैं: external side effects की duplicate/unknown-result handling; inbound message durability; tenant और permission consistency; model/rule/form version binding; audit/replay के वास्तविक contracts; licensing; और roadmap में security, usable UI तथा migration की सही जगह।

**महत्वपूर्ण सीमा:** मुझे application source code, repository clones, build outputs, benchmark logs या test reports नहीं दिए गए हैं। यह दोनों प्रस्तावित documents की स्वतंत्र समीक्षा और महत्वपूर्ण public claims की जाँच है। इससे application के implemented होने, secure होने या production-ready होने का प्रमाण नहीं मिलता।

### 2. Evidence, scope and method

Both supplied files were read in full, including the 35 revisions, 24 FMEA rows, ten walkthroughs, effort estimates, standards scope and lifecycle promises. The FMEA arithmetic and effort-table totals were independently recomputed. Important market, standards, licensing and security claims were checked against primary sources. No application tests or upstream builds were run.

The source snapshots mentioned by the documents—Operaton `ccea971`, Flowable `f6017e3`—were not supplied. Public current documentation is useful for challenge and corroboration; it does not reproduce those exact source snapshots. In particular, this review does not independently certify their LOC counts, every missing BPMN element, the full dependency tree, or alleged vulnerable code paths.

Input fingerprints:

| Input | SHA-256 |
|---|---|
| ANALYSIS.md | `59039de2722e95bf215f93bf72a3f65adce3b0a41b645293917409b40cf7dcdc` |
| DESIGN.md | `3005ae03d3ffa33a0cb9f3c1d98c3c4f036d06b5a28c8efbeac15c3f338718d9` |

Evidence labels used here:

| Label | Meaning |
|---|---|
| Document finding | Directly visible in the supplied files, including an internal contradiction or missing specification. |
| Externally checked | Supported by the linked primary source on the review date; not an independent benchmark of that product. |
| Engineering judgment | A recommendation or deduction, with its reason stated. |
| Validation required | Needs source, tests, customer evidence or a specified deployment configuration before being accepted. |

Roles considered: product owner, enterprise/workflow architect, distributed-systems engineer, database engineer, application and UX designer, security engineer, SRE, QA/conformance lead, developer-experience lead, open-source maintainer and commercial product reviewer. These are review perspectives, not claims of separate reviewers or independent certification.

### 3. Overall assessment

| Area | Assessment | What would change the assessment |
|---|---|---|
| Problem relevance | Plausible and useful | A named buyer, a costly existing problem and a paid pilot. |
| Architectural direction | Sound in several fundamentals | A working vertical slice with bounded engine changes. |
| Engine selection | Prematurely finalized | Same workflow corpus on minimally modified Flowable and Operaton. |
| Analysis discipline | Broad and thoughtful, with overconfident conclusions | Reproducible evidence, calibrated claims and unresolved-risk tracking. |
| Functional completeness | Ambitious backlog | Explicit supported profiles and release-level acceptance criteria. |
| Distributed correctness | Important unsafe gaps remain | Failure-injection tests at every effect/commit boundary. |
| Security and tenancy | Good controls named; enforcement incomplete | One authorization contract across APIs, lists, workers, exports and caches. |
| Application usability | Feature inventory rather than complete product design | Screen flows, permission states, forms and usability evidence. |
| Operations | Targets are not a deployable HA/DR design | Real failover, restore and upgrade rehearsals. |
| Market differentiation | Overstated | A measured advantage over the buyer's actual alternative. |
| Delivery and economics | Large cost acknowledged, total lifecycle cost missing | Critical-path plan, staffing/runway and recurring-support budget. |

This is a useful pre-development dossier. It is not yet a validated product specification, an implementation audit, an executable simulation, or a funded business case.

### 4. Strong decisions to retain

1. Choose one execution engine rather than splicing two execution models together.
2. Make the external protocol the polyglot boundary; a Java core can serve non-Java applications well.
3. Keep a modular monolith initially, with explicit module dependencies and adapters.
4. Use a relational database for transactional engine state until workload evidence justifies something else.
5. Treat workers as at-least-once and distinguish technical failures from business outcomes.
6. Commit outbound event intent with state through an outbox.
7. Prefer portable typed data over Java object serialization.
8. Put DMN evaluation behind a deliberate integration boundary, while controlling semantic differences.
9. Keep CMMN optional at deployment and dependency levels.
10. Treat authorization, tenant isolation, secure parsing and secrets as first-class design concerns.
11. Reuse upstream tests, add semantic and failure tests, and publish measured conformance results.
12. Tier databases and SDKs instead of promising equal support everywhere immediately.
13. Retain benchmark hypotheses rather than marketing throughput claims.
14. Maintain versioned contracts, migration discipline, SBOMs and a public vulnerability process.

Several caveats already appear in the documents—MIWG is interchange testing, the TCK is not complete DMN 1.6 coverage, native image is restricted by R7, and external payments require downstream idempotency. The problem is that stronger promises elsewhere do not consistently respect those caveats.

### 5. Corrections to factual and comparative claims

| Claim or implication in the documents | Independent finding | Required correction |
|---|---|---|
| An empty Apache-licensed BPMN+CMMN+DMN+authorization+tenancy+UI market position is a fact | Operaton already presents substantial overlap: the three standards, operations/task/admin applications and an open-source platform. Its modeler and ecosystem further narrow the gap. A particular all-in-one web experience may still differ. [S01–S03] | Treat the exact bundle as a positioning hypothesis, not proof of no competition or a defensible moat. |
| Operaton's UI is essentially an AngularJS disadvantage | Its 2.2 release documents a Preact webapps-neo technical preview. Sangam intends to reuse that work. [S03] | Compare stable versus preview explicitly. Reusing the same UI is not by itself a durable competitive advantage. |
| AI agent workflows make Sangam uniquely positioned among BPMN engines | Camunda documents native AI agent loops, governed tool activities and external-agent integration. Operaton also documents MCP integration. [S01, S04] | Compete on measurable recovery, governance, deployment and cost characteristics, not generic AI availability. |
| Flowable has only REST and Java-level reach | Flowable publishes external-worker clients including Python, JavaScript, Go and .NET. Exact OSS-engine/version compatibility and client maturity need testing. [S05] | Distinguish REST transport, available client libraries and fully supported SDKs. Do not equate REST with no polyglot support. |
| Flowable lacks DMN task integration because businessRuleTask uses Drools rules | That specific BPMN task mapping can be a real compatibility gap, but Flowable also publishes a DMN execution API and decision-task functionality. [S06] | Say “different element/extension/result semantics”; do not turn that into “no DMN execution capability.” Verify the pinned source's behavior. |
| Everything in the proposed distribution is Apache 2.0 | The bpmn-js license requires its visible watermark. Liquibase 5.0+ is FSL, with later conversion provisions, rather than immediately Apache 2.0. [S07, S08] | Maintain a version-level license/redistribution matrix. Apache-licensed project code can coexist with other dependencies, but the entire distribution must not be described inaccurately. |
| GraalJS isolates are automatically an available open-source sandbox | GraalVM documentation distinguishes sandbox policies and editions; its Community Edition does not provide the documented sandboxing facility. ISOLATED is not the same threat model as UNTRUSTED. [S09] | Select the exact artifact, edition, policy, runtime and license. Consider process-isolated execution as a separate design option; benchmark it. |
| Drools has the highest conformance in the displayed table | The displayed and checked TCK results are jDMN 3391/3391 and Drools 3388/3391. The table's DMN-Scala entry is a DMN engine, not a standalone measurement of Operaton's current FEEL library. [S10] | Drools remains a credible candidate; select on required features, footprint, semantics and operations, not an inaccurate ranking. |
| DMN 1.6 formal and 1.7 beta | Confirmed by OMG's current pages. Apache KIE documents DMN 1.6 support. [S11, S12] | Keep the formal/preview distinction and add version-specific tests; accepting a namespace does not implement its semantics. |
| A2A 1.0.1 and MCP 2026-07-28 | Confirmed by the respective primary release/specification pages. [S13, S14] | Pin protocol versions and maintain negotiation/interoperability tests. Protocol adoption is not recovery correctness. |
| Camunda production licensing is an opening | Current licensing documentation requires an enterprise license for the relevant compiled production components. [S15] | Valid commercial context; compare total ownership cost and migration risk as well. |
| Camunda 8.9 has RDBMS storage | Confirmed specifically for secondary/query storage; primary execution remains Zeebe/RocksDB. [S16] | Preserve the distinction. It is not the same architecture as a transactional JDBC engine. |
| n8n currently uses MySQL as a supported backend | Current documentation says MySQL/MariaDB support was removed in 2.0; PostgreSQL/SQLite are the documented backends. [S17] | Refresh the competitor row and identify version/edition. |
| CVE-2026-91197 is verified Flowable evidence | The supplied citation is third-party. This review did not obtain a readable authoritative record establishing the claimed issue. | Mark the identifier and affected versions unverified until vendor/CNA evidence is obtained. The alleged parser weakness needs its own pinned-source reproduction. |
| CVE-2025-0982 directly proves Flowable's Rhino vulnerability | This CVE concerns Google Cloud Application Integration's Rhino-based JavaScript task implementation. Google's release notes confirm Rhino deprecation. [S18] | Use it as a relevant failure class; do not claim Flowable is affected by that CVE without evidence. |
| CVE-2025-6514 establishes a generic MCP/prompt-injection flaw | The authoritative record describes OS command injection in mcp-remote through an authorization endpoint URL. [S19] | Separate client implementation vulnerability, OAuth/network validation and prompt injection threat classes. |
| RPN reduced by 84% | Arithmetic is correct: 4581 → 712, a 84.46% reduction in assigned score. | It is a projected reduction in subjective scores, not measured real-world failure probability or loss. |
| Technical success is 75–85%, etc. | No calibrated probability model or comparable historical dataset is supplied. | Keep only as explicitly subjective planning judgment, or replace with evidence gates. Do not sell these as quantified confidence. |
| 143–220 person-months | Sum of the 15 rows is correct. | It is an estimate, excludes Phase 4 and continuing operations, and cannot be converted into elapsed time solely by division. |
| ISO/IEC 25010 without an edition, with eight characteristics | The 2023 standard describes nine characteristics. [S20] | State the chosen edition and map requirements to it consistently. |

The market table also combines Hatchet and DBOS into one row and mixes hosted quotas, local task throughput, process throughput and different payload/history configurations. Those are useful research leads, not a valid performance ranking.

### 6. Product and market analysis

#### 6.1 The actual job the product should perform

A useful positioning hypothesis is: **A self-hostable workflow platform that lets organizations coordinate approvals, rules and services, with explainable execution, controlled changes and reliable operational recovery.**

Customers buy fewer stalled approvals, shorter integration projects, safer changes, audit evidence and predictable ownership cost. They do not buy an agenda data structure, a count of supported standards, or a table-count reduction.

“Any stack” should mean a documented integration contract with tested language clients. “Any workflow” should mean a clearly defined supported business-workflow envelope, with explicit exclusions and extension points.

#### 6.2 Choose an initial buyer

| Initial buyer | Pain worth validating | First offer | Main risk |
|---|---|---|---|
| ISV embedding approvals in its own product | Rebuilding tasks, permissions, audit and customer-specific rules repeatedly | Embeddable task UI, a small reliable SDK set, tenant controls, supported deployment | Too much engine sophistication and too little application integration. |
| Government/regulated-system integrator | Department routing, delegation, audit, on-premise deployment, database constraints | A reusable approval platform and a supported pilot | Procurement, operational ownership and business-rule specificity. |
| Camunda 7 user | Supported continuity and affordable modernization | Compatibility assessment, migration tooling, support contract | Operaton already provides a lower-change route. |
| Flowable user | Product UI, governance or integration gaps | A supported product layer around required workflows | Different customers may need different gaps filled. |
| AI-agent platform team | Human authorization and recoverable side effects | Governed agent steps with durable business outcomes | Crowded market and unresolved execution semantics. |

Engineering judgment: choose one of the first two as the first product wedge if you can reach those buyers. Choose the migration segment only if a demonstrably safe migration offer can ship early. Do not launch all five propositions at once.

#### 6.3 Competition and buyer alternatives

| Alternative | Why a buyer might choose it | What Sangam must demonstrate |
|---|---|---|
| Operaton | Continuity with Camunda 7, community governance, existing process operations tools | A concrete improvement beyond the same engine/UI building blocks, without excessive migration risk. [S01–S03] |
| Flowable ecosystem | Existing process/case/decision integration and familiar development model | Better fit for the exact deployment, governance and UX requirement; compare OSS and commercial offerings separately. [S05, S06] |
| Camunda 8 | Existing process platform, operations tooling and agent orchestration | Lower validated ownership cost or materially simpler deployment/integration for the chosen customer. [S04, S15, S16] |
| Temporal | Code-defined durable workflows and established retry/recovery concepts | A business-facing approval/modeling experience that the buyer values; do not compete purely on SDK count. [S21] |
| Conductor | JSON-defined orchestration, system tasks and human/AI workflow capabilities | A standards/governance or application-fit advantage, verified with the same workflow. [S22] |
| n8n / Kestra / Windmill | Fast automation or developer workflows within their respective product and license models | Faster delivery of the chosen approval use case, rather than a contest over connector counts. [S23–S25] |
| Apache KIE and related ecosystem | Strong decision tooling and existing automation building blocks | A complete, supportable application experience; standards alone are insufficient. [S12] |
| Cloud-native workflow services | Managed operations and integration with the buyer's cloud | The value of self-hosting, data location, database choice or portability for that buyer. |
| Existing custom approval code | Already integrated and understood by the organization | A credible migration path and reduced future change/support effort. |

These are buyer-oriented comparisons, not claims that all products have identical semantics. A free product can still have a high operational cost. A paid product can be cheaper overall when it avoids a large internal maintenance team.

#### 6.4 Missing market evidence

The documents contain no validated buyer interviews, paid design partners, current customer switching costs, procurement path, funnel data, willingness-to-pay evidence, support demand, or unit economics. Funding raised by another vendor does not validate demand for this particular platform.

Before broad engine redevelopment, run a bounded validation exercise: approximately 8–12 relevant interviews, 2–3 serious design partners, and at least one paid or contractually concrete pilot if feasible. These are proposed validation goals, not market statistics or a success guarantee. Record the rejected alternatives and why each buyer would switch.

#### 6.5 Revenue model and sustainability

| Offer | Charging basis to test | What must actually be delivered |
|---|---|---|
| Assessment/migration | Fixed assessment and scoped migration project | Compatibility report, reconciliation, rollback/run-off plan and acceptance evidence. |
| Supported self-hosted distribution | Annual support/maintenance by deployment or agreed service tier | Security fixes, upgrade guidance, supported versions, response ownership and DR assistance. |
| Managed private or hosted service | Base service charge plus transparent resource consumption | Operations, backup, isolation, monitoring, recovery and cost controls. |
| Reusable vertical workflow packages | Implementation plus maintenance | Reusable domain mappings and templates with controlled customer configuration. |
| Training and partner enablement | Training/certification/services fee | Practical material and repeatable delivery quality, rather than engine internals alone. |

Do not make revenue depend on withdrawing the very security, tenancy or audit features used to justify the open platform. If a commercial extension model is chosen, define it before making an “everything free forever” promise. Support and managed-service models can coexist with permissively licensed core code, but the economics still need proof.

Budget equation: **development effort × fully loaded monthly staffing cost + infrastructure + commercial dependencies + security/QA + support + sales/administration + contingency.** Revenue is not profit; service revenue is not recurring product revenue. Track pilot conversion, activation time, support hours per customer, retained customers, contribution margin and actual cost per supported deployment.

### 7. Base-engine decision and architecture

#### 7.1 Do not choose the base solely because its internals appear simpler

Flowable's agenda/shared-service approach is a plausible base. But the proposed work changes its configuration, persistence layout, executor, expressions, history, security and public surface while porting PVM-oriented features from Operaton. Much of the inherited maturity must therefore be revalidated.

An activity-instance table helps identify activities; it does not make migration/modification semantics automatically straightforward. Event subscriptions, timers, compensation, multi-instance state, variables, boundary events, child instances and audit consequences all matter.

| Strategy | Best fit | Main cost/risk | Review recommendation |
|---|---|---|---|
| Product layer over stable Flowable APIs | Case/event-heavy use cases and new applications | Gaps that public extension points cannot address | Include in the first spike. |
| Product layer over stable Operaton APIs | Camunda 7 continuity and operational parity | Case/event gaps and future ecosystem direction | Include when migration is a real buyer requirement. |
| Deep Flowable fork plus Operaton ports | Confirmed requirements inaccessible through extension points | High maintenance surface and semantic regression risk | Defer until a gap-by-gap proof justifies it. |
| New engine from scratch | A genuinely different execution contract | Long correctness and ecosystem development | Not justified by these documents. |

A stable product API may hide selected runtime details, but it must expose capability differences. It does not make live engine databases interchangeable or make every model portable. Avoid building and maintaining two complete engine implementations as the first release.

#### 7.2 Proposed practical structure

Separate the product/control plane (model packages, approvals, tenant policy, release governance, UI) from the execution plane (runtime, tasks, workers, timers, state transitions). Keep them as modules in one deployable initially if that is simpler. Integrations and hostile script execution can have separate operational boundaries when justified.

| Component | Initial direction | Required constraint |
|---|---|---|
| Runtime | One stable engine/version selected by the pilot | Small, documented patch surface; reproducible baseline tests. |
| Core deployment | Modular JVM server | Do not promise later service extraction without distributed-systems redesign. |
| JDK | Java 25 is a reasonable proposed LTS baseline | Certify drivers, frameworks, build tools, deployment targets and patch support together. Virtual threads do not increase database connection capacity. |
| Database | PostgreSQL reference; Oracle/SQL Server where first-buyer commitments require them | Exact supported versions, isolation, collation, time and migration semantics. |
| DMN | One default evaluator; SPI for controlled extension | Versioned model artifacts and a known semantic contract. Avoid supporting three interchangeable defaults immediately. |
| External API | REST first unless streaming has a proven need | Canonical schemas, idempotency and uniform authorization. Add gRPC against the same semantics. |
| SDKs | Java plus the one or two languages actually used by pilot systems | Full worker lifecycle; generated types alone are not an SDK. Expand based on demand. |
| Events | Durable inbox/outbox and a minimal connector set | Polling recovery is authoritative; wake-ups are an optimization. |
| UI | Existing components with targeted product flows | Audit preview maturity, accessibility, license terms and actual API coupling. |
| Identity | OIDC plus service identities | Deny by default, tenant binding and revoke behavior. |
| Scripts | Prefer external workers for first release | Any untrusted runtime needs a tested isolation boundary and resource limits. |
| Operations | Plain supported deployment plus Helm when needed | An operator, Terraform catalog and native image are independent investments, not MVP prerequisites. |

Spring Boot and Quarkus integrations cannot simply be transferred between engine implementations without testing. Keep frameworks out of product-domain contracts where practical, but do not perform a wholesale core refactor only to achieve an aesthetic purity goal.

The target of approximately 35 tables is not a quality requirement. Correct invariants, indexes, query plans, upgrade safety and retention behavior are. Similarly, a storage SPI does not make a future Raft/log execution backend a routine adapter change.

### 8. Distributed correctness: highest-priority engineering findings

#### E01 — A fencing token is not payment deduplication

**Where:** DESIGN §2, §5; ANALYSIS F1, T1, S2, R20.

Rejecting an old worker's completion protects engine state. It does not undo or prevent an external charge already made by that worker. The documents correctly mention downstream idempotency in S2, but T1 overstates the protection when it describes the lock-expiry branch as blocked.

Required contract: a stable business-operation identifier across retries, workflow migration and recovery; request-payload fingerprint; downstream idempotency or an application ledger/outcome lookup; duplicate/conflicting-request behavior; retention of keys long enough for recovery; and an explicit unknown-outcome state. If the downstream system cannot deduplicate or report outcomes, retries of irreversible effects may require reconciliation or human resolution.

**Acceptance:** kill the worker after the downstream operation succeeds but before engine completion; repeat the same request; introduce a timeout with an unknown result; verify the business effect, not merely the number of engine completions.

#### E02 — The agent journal has an unhandled crash window

**Where:** DESIGN L8, §14.4; ANALYSIS S5.

Recording every completed call avoids repeating calls whose results were committed. It does not resolve a crash after the external call succeeds and before the journal result is durable. This affects LLM billing as well as side-effecting tools. A2A remote-task durability is also a separate boundary from the local journal.

Persist a call intent with a stable call ID before dispatch; record provider/request handles where available; recover through provider idempotency or outcome lookup; represent uncertainty rather than blindly reissuing an action. Track prompt/tool/model versions, policy, actual cost and budget reservations. Approval should bind the exact action and payload, with expiry and revalidation. A retry after changing the payload is not the same approved action.

Replace “no call is repeated after a crash” with a bounded guarantee describing providers and operations for which duplicate suppression is actually possible.

#### E03 — Outbound outbox needs an equally explicit inbound inbox

**Where:** DESIGN §4.5 and §6.

The event gateway lists transports and correlation, but does not specify a durable inbox, acknowledgement boundary, early-arrival policy or correlation deduplication lifecycle.

Persist or process the incoming message durably before acknowledging it. Define tenant-bound deduplication using the source and event/message identifier, uniqueness of business correlation keys, delivery before subscription, duplicate start events, multiple matching instances, missing instances, stale messages and retention. Broker redelivery after consumer failure must be safe. A message delivered before a process reaches its catch event must be deliberately buffered, rejected, retried or expired.

**Acceptance:** deliver before subscription, duplicate after commit/before broker ACK, reorder messages, and restore an older database backup while the broker still contains newer messages.

#### E04 — “Commit-visibility guard” is not yet an algorithm

**Where:** ANALYSIS C3/R4; DESIGN §4.5.

An increasing ID is not a commit-order watermark: transaction A can allocate 100 and commit after transaction B allocates and commits 101. A relay that advances permanently past 101 can miss 100. A delay window only reduces the risk; it does not prove safety.

Specify a durable pending/claimed/published lifecycle, crash-expiring relay leases, per-destination acknowledgement state, a per-instance sequence, and idempotent consumers. A published-to-Kafka flag must not imply a history projector also received the record. Per-instance ordering with multiple relays needs an actual ordering rule. Decide whether a poison event blocks that instance or produces an explicitly tracked sequence gap.

AWS's outbox guidance also calls out duplicate delivery and consumer idempotency. [S26] The outbox is a reliability pattern, not an exactly-once network guarantee.

#### E05 — Root locks require a complete transaction and lock specification

**Where:** DESIGN §4.3; ANALYSIS C2/R3.

Serializing commands on an instance can reduce optimistic-lock conflicts. It can also create a hot root and head-of-line blocking. A parent process, call activity, event subscription, batch migration and cancellation can touch more than one root or shared resource.

Define a deterministic order for multiple instance locks; enumerate subscription, task, variable and batch locks; bound transaction duration; classify retryable errors by database; make retries side-effect safe. Avoid remote HTTP/agent calls while holding engine locks. Keep optimistic-version checks where they are still needed. Validate concurrency safety and liveness, not only absence of one exception class.

#### E06 — Unlock-on-disconnect can create concurrent workers

**Where:** ANALYSIS C4 versus R9.

C4 discusses unacknowledged dispatches; R9 says unlock on stream close more broadly. A broken stream does not prove the worker stopped executing. Releasing an acknowledged, active job immediately can create a second owner while the first is still calling a business system.

Document acquired, dispatched, acknowledged, executing, cancelling, expired and completed states. Distinguish undelivered offers from acknowledged work. Use bounded leases, durable attempt IDs, heartbeats and fencing; make cancellation cooperative and auditable. Test network interruption separately from process termination.

#### E07 — Completion serialization is not execution exclusivity

**Where:** ANALYSIS C16/R35.

Checking exclusivity only at completion can serialize engine state updates while allowing two external effects to run concurrently. That is acceptable only if “exclusive” promises state-update serialization rather than sequential business effects.

Define the intended contract per job type. For genuinely exclusive external work, acquire a root/business-resource lease before dispatch and propagate a fencing mechanism where the receiver supports it. Use test cases where the second worker begins before the first finishes; a test that only checks completion order is insufficient.

#### E08 — Deletion cannot make an external action disappear

**Where:** ANALYSIS R23/F23.

Suppressing a false retry/incident after a concurrent delete can be reasonable inside the engine. Silently ending an already dispatched payment or another significant action hides unresolved outcomes.

Define cancellation intent, a tombstone, late-result handling, retention of operation IDs, worker acknowledgement and reconciliation. Business cancellation, technical instance deletion and privacy erasure are different operations. The audit record should preserve what happened even when the UI no longer shows an active instance.

#### E09 — Partition leases and autoscaling need fencing too

**Where:** DESIGN §4.3 and Phase 4.

`hash(rootInstanceId) % partitions` changes ownership when the partition count changes. Kubernetes lease ownership alone does not stop a paused old owner from resuming. Specify stable routing epochs, lease fencing, draining and handover. Measure whether partitioned acquisition adds value beyond normal database coordination before implementing it.

#### E10 — Outbox capacity is finite

**Where:** ANALYSIS S8.

“Kafka down for two hours, commands keep committing” requires a storage/headroom assumption. Partitioning does not create capacity. Define expected event rate, payload size, outage budget, disk thresholds and recovery drain rate. At a threshold, deliberately throttle, reject or degrade specified operations. A recovery rate equal to the incoming rate never clears the backlog.

#### E11 — Wake-ups should be hints with minimal information

**Where:** R1 and DESIGN §5.2.

Batched relay-driven wake-ups are a sensible performance hypothesis. PostgreSQL supports transactional NOTIFY, so “never” is a project policy, not a correctness law of PostgreSQL. [S27] Test the actual workload before asserting a universal performance effect.

Do not put tenant-sensitive payloads in a broadly visible notification channel. Polling must recover missed signals, listener restarts and notification queues. Avoid broadcast storms where every API node wakes for every task.

#### E12 — Idempotency is an API lifecycle, not a header

**Where:** DESIGN D6, §5.

Specify scope `(tenant, command type, key)`, payload fingerprint, concurrent duplicate handling, success/error response reuse, retention, conflicting reuse, retries after timeout and authorization revalidation. Store the command result atomically with engine state where possible. Otherwise “exactly-once state changes” is an intent without an implemented request contract.

### 9. State, standards and model lifecycle

#### E13 — Audit history is not a replayable execution history

**Where:** DESIGN L5/L9, §4.6, §14.4.

The design supports history levels including none and per-element suppression, but also promises replay, time-travel debugging and a complete event log. These features need different data guarantees. A projected audit trail may omit inputs, routing decisions, nondeterministic responses, versioned bindings or external results required to reconstruct execution.

Define separate purposes: operational history; mandatory security/business audit; optional diagnostic payloads; and replay fixtures. State the minimum event schema, ordering, schema evolution and retention for each. Record model, rules, forms, expression profile, connector contract and external outcomes by immutable version/hash.

Historical test execution against a new model can be useful as **scenario regression**. It is not proof that a changed model will behave identically on every unobserved branch, and the old execution path may no longer map to the new model. Do not promise Temporal-style replay semantics merely because events are retained.

#### E14 — Version the complete executable package

**Where:** DESIGN §3.3, §4.7, §14.7.

Pin BPMN, called subprocesses, DMN, form schema, scripts, connector templates, policy versions and relevant type definitions. Specify deployment-scoped versus latest-version binding. A running model can keep its XML yet change behavior because an unpinned decision, function, form or lookup changes.

Support draft, review, approved, deployed, active and retired states; signed/hash-addressed artifacts; environment promotion; and separate model author/deployer authority. Concurrent editing, deployment approval, version comparison and rollback/run-off are product requirements.

#### E15 — Full standards support conflicts with the proposed backlog

**Where:** DESIGN §3.1/§3.3a/§3.3b versus ANALYSIS §3.

The design promises full executable BPMN and full CMMN, while complex/multiple-event behavior is deferred and discretionary items, planning tables and case-file features are Could items. This is not a reason to cancel the long-term scope; it is a reason to distinguish the goal from release support.

Create a clause/element matrix with: standard version, conformance profile, parser/import/export/editor/runtime support, limitations, tests and release. Unsupported executable content should fail deployment explicitly rather than parse successfully and do nothing. Separate syntactic interchange from runtime equivalence. MIWG testing is correctly identified in DESIGN §14.4 as interchange testing; preserve that qualification.

#### E16 — CMMN retirement cannot promise universal automatic conversion

**Where:** DESIGN §3.3b.

Modular isolation is valuable. Automatic semantic equivalence is a different problem: planning/discretionary behavior, sentries, repetition and case-file evolution do not generally become simple BPMN milestones without design decisions.

Offer a supported conversion subset, diagnostics for unsupported constructs, manual review, explicit state migration mapping and a run-off option for existing cases. Determine whether any initial customer actually needs CMMN. “Adoption declining” is not sufficient evidence to promise retirement or deprioritize a contractual requirement.

#### E17 — Full engine conformance and end-user modeler capability must match

**Where:** DESIGN §3.4 and §8.

A runtime supporting DMN boxed expressions and decision services does not mean a selected dmn-js-based editor can author every required construct. Likewise, the CMMN editor is acknowledged as additional work. Maintain a modeler/runtime compatibility matrix and round-trip tests for every supported construct. Decide when external-authoring import is acceptable.

#### E18 — FEEL contracts need precise null, number and time behavior

**Where:** R10/R33, DESIGN §5.4.

FEEL legitimately uses null. A global “null means incident” policy would change legitimate decisions. Apply strict handling to contexts requiring a Boolean routing result or mandatory output; define other contexts explicitly. Static schema checking catches some errors, but unknown runtime inputs require runtime validation too.

Define decimals/money without accidental binary floating-point conversion; integers beyond JavaScript's exact numeric range; missing versus null; dates, local date-times, offset date-times and durations; enumeration and schema evolution. Use the same contract across Java, .NET, TypeScript and other SDKs.

Pin semantic behavior per model. Do not interpret that as retaining vulnerable ancient evaluator binaries indefinitely. Security fixes and semantic compatibility need regression suites, compatibility modes and an explicit migration policy.

#### E19 — Forms are an independent versioned contract

**Where:** DESIGN §3.2, §8.

form-js uses its own JSON-based form representation; “JSON Schema forms” is not automatically the same thing. [S28] Define data-validation schema, layout/rendering schema, extension points, server-side validation, conditional fields, attachments, accessibility and version binding. A client-side hidden field is not an authorization boundary.

#### E20 — Running-instance migration is a specialist product

**Where:** DESIGN §3.3, §4.7, Phase 4.

Model import, REST compatibility, library migration and live execution-state migration are four different capabilities. Changing namespaces does not transform timers, subscriptions, local-variable scopes, compensation stacks, incidents, active workers, attachments or history correctly.

Start with compatibility classification and new-instance cutover while old instances run off, where business constraints allow it. For live migration: inventory and dry-run; unsupported-state rejection; freeze/drain workers; mapping and reconciliation; cutover ownership; rollback conditions; and duplicate-effect protection. Compare counts plus business invariants, not just row counts. Do not promise “without rewriting models” for all vendor extensions.

#### E21 — Continue-as-new and dynamic modification need business constraints

**Where:** DESIGN L6/L7 and §4.7.

Continue-as-new can help bound one execution history but does not eliminate retained audit storage. Define atomic handover, pending messages, business-key uniqueness, original deadlines and a navigable chain. Arbitrary update handlers or token movement can bypass required approvals; restrict commands, require reasons and re-evaluate affected business obligations. Completed payments do not become reversible by rewinding a token.

### 10. Security, authorization and tenant isolation

| Finding | Required design | Acceptance evidence |
|---|---|---|
| List ABAC differs from single-object ABAC | Lists, counts, exports, search, dashboards and direct GET must use equivalent policy semantics. Denormalized grants need defined freshness and policy-version behavior. | A user who cannot open an item cannot infer it from search, totals or cached results. |
| Authorization model port is treated as a package | Cover all commands, queries, batch jobs, deployment, task lifecycle, field reads, worker variables and historic data. | Endpoint × action × resource × tenant tests, including denied cases and background execution. |
| Tenant ID arrives from workers/events | Bind tenant scope to authenticated identity and authorized routing; never trust a supplied tenant string alone. | Forged tenant headers/claims/payloads fail on every entry point. |
| Shared definitions and tenant override | Define whether fallback is allowed, which version it resolves to, and who can change it. | Tenant A cannot execute or inspect tenant B's definitions, keys or cached results. |
| Permission caches and revocation | Specify invalidation latency and check policy again before sensitive completion. | A removed approver cannot use an already-open form to approve later. |
| Approval changes after review | Bind approval to payload hash/version, rule version and authorized role assignment. | Editing amount, beneficiary or document invalidates/requires renewed approval. |
| Human versus system actions | Preserve actor, service principal, originating user, assignment and decision basis. | Automatic decisions are visibly distinct and cannot impersonate a human approver. |
| Agent read access can leak data | Scope reads, fields, searches and model-provider data transfer; limit allowed tools and egress. | Prompt injection cannot expand permissions or export unrelated tenant data. |
| Human approval is treated as a universal defense | Validate specific proposed actions, budgets, audience and recipient/resource scope; recheck authorization on execution. | Generic approval cannot be reused for a different tool payload. |
| Script/FEEL/plugin trust boundaries | Restrict custom functions and host access; isolate hostile code; validate dependencies and enforce resource limits. | Escape, infinite loop, large allocation and malicious import tests. |
| XML hardening too narrowly described | Cover DOM, SAX, StAX, transformations, schema validation, diagram generation and resource resolution. | No network/file access through any parser/rendering route; malformed input bounded. |
| Connector SSRF | Tenant-scoped destination policy, DNS and redirects, metadata protection, authenticated callbacks and response limits. | DNS rebinding, redirects, alternate encodings and private-network policy tests. Internal enterprise endpoints may need explicit approved access. |
| File/reference variables | Object-store authorization, tenant binding, upload scan, size/type checks, signed-URL expiry and orphan cleanup. | A copied reference or expired URL does not expose another tenant's documents. |
| Encryption and secrets | Key ownership, rotation, backup/restore and search behavior; prevent secrets in logs/history/LLM traces. | Rotate and restore keys with data; scan logs and exports. |
| Immutable audit versus erasure/retention | Separate mandatory audit metadata from sensitive payload; define legal holds and approved retention policy for each deployment. | Erasure/retention tests include backups, object storage, projections and holds. No automatic legal-compliance claim. |
| Administrative operations | Least privilege, controlled break-glass, dual control where needed and immutable operation reasons. | Migration, bulk cancel, tenant transfer and permission changes are attributable and reviewable. |

An ABAC rule such as `amount < 10000` cannot be enforced only when opening one object while a list exposes all amounts. This is both a privacy and a workflow-correctness issue. OPA/Cedar placement is secondary to consistent policy evaluation.

ASVS Level 3, SLSA Level 3 and WCAG 2.2 AA are useful targets. Scanners, signed artifacts or axe results alone do not establish them. Define applicable requirements and manual/external assessment. Supply-chain controls need builder isolation and provenance validation; accessibility needs keyboard and assistive-technology testing as well as automation.

### 11. Application completeness: what engine features do not provide

#### 11.1 Missing or under-specified business behavior

| Area | Required behavior before calling the application complete |
|---|---|
| Organizational model | Effective-dated office/department/role assignments, multiple positions per person and delegation authority. |
| Routing | Candidate calculation, explicit assignee selection, no-candidate fallback, multi-department rules and reassignment. |
| Segregation of duties | Initiator/checker/final-approver separation by person and assignment; controlled exceptions. |
| Approval patterns | Sequential, parallel ALL, conditional ANY/quorum, amount bands, rework and cancellation. |
| State semantics | Draft, submitted, pending, returned, rejected, approved, dispatched, acknowledged, reconciled and closed as appropriate. Approval is not automatically payment completion. |
| Delegation | Valid-from/to, absence handling, suspended users, substituted authority and full audit. |
| Data changes | Draft saves, optimistic form versions, concurrent edit handling and invalidation of prior approvals. |
| Forms/documents | Validation, attachment security, reasons/comments, required evidence and output documents. |
| Service levels | Business calendars, holidays, reminders, escalation, paused clocks and recalculation rules. |
| Rules | Effective dates, amount/currency boundaries, policy version, change approval and explainable decision evidence. |
| Exceptions | Failed integrations, partial completion, unknown outcomes, retries, compensation and manual reconciliation. |
| Notifications | Preferences, recipient resolution, duplicate control, delivery failure, localization and accessibility. |
| Audit and reporting | Who did what, under which authority and rule, against which data, with what downstream result. |
| Tenant customization | Configuration overlays and versioned extensions, with a bounded extension API; avoid a code fork per customer/state. |
| Operational actions | Retry, resolve, migrate, suspend and cancel with guarded permissions and visible consequences. |
| Search and scale | Permission-aware pagination, stable sorting, useful filters and large-volume export controls. |

#### 11.2 Minimum end-to-end screen flows

A usable pilot needs more than a cockpit: tenant/application onboarding; role/assignment setup; model/form/rule selection; draft creation; validation and submission; approver selection; work inbox; task detail/action; return-and-resubmit; process/audit timeline; incident/reconciliation workbench; and controlled configuration/version promotion. Administration and production permissions must be usable from the product or a supported administrative process.

For each screen specify loading, empty, unauthorized, stale-data, conflict, network-error, success and partial-success states. Role-aware menus are convenience; server-side action enforcement is the security boundary. Define keyboard operation, mobile constraints, Hindi/other-language text expansion, dates, currency and accessible error messages.

#### 11.3 Representative approval scenario for this product direction

If the first deployment is an IFMIS-style approval platform, use a scenario such as initiator → selected financial checker → final approver, with amount-based routing and a downstream financial integration. This is a proposed acceptance scenario, not a claim that these two files already contain the full government business rules.

Test all of the following together: multiple eligible checkers; explicit selection and recorded assignment ID; two mandatory parallel approvals; one-of-two conditional approval; the same person occupying two roles; delegate expiry; returned application and resubmission; amount changed after approval; approver transferred mid-process; automatic versus human steps; duplicate submit/callback; and the downstream system committing while its response is lost.

Use synthetic actors and simulated external services in a demo while keeping the actual routing, permission, persistence and recovery logic active. Auto and manual modes should follow the same audited state machine, with different authorized actors—not separate bypass paths.

### 12. Database, performance and operations

#### 12.1 Performance model review

R5 is substantially more useful than a bare “1000 instances per second” goal. The listed ~16 transactions/~225 row writes are illustrative, however: some operations can share transactions, and indexes, WAL, authorization, variable sizes, isolation, outbox updates and projections change cost. A 2018 vendor benchmark cannot calibrate today's new architecture precisely.

Measure starts, completed instances, commands, worker activations, task completions and decisions separately. A human task makes end-to-end completion time depend on humans; benchmark it with a declared synthetic completion policy and report that policy. Do not count fast starts while a growing backlog hides lack of completion capacity.

The Little's-law example is valid as an average service-work concurrency estimate: 300 process starts/completions per second × five service tasks × 0.2 seconds ≈ 300 tasks in flight under its assumptions. It does not include waiting human tasks, queueing, retry overhead or tail-latency headroom. Illustratively, a steady arrival rate of 300/s with one day's average lifetime implies about 25.92 million active instances. Capacity must therefore cover lifetime and retained history, not just throughput.

| Benchmark dimension | What to report |
|---|---|
| Workload | Versioned model, branches, payload sizes, variables, tenant distribution, human-task completion policy. |
| Environment | Exact CPU/RAM/storage/network, database version/config, replica durability, pools, app/JDK versions. |
| Security | Authentication, authorization and realistic group counts enabled. |
| History/events | Intended audit level, outbox and projectors active; consumers both healthy and delayed. |
| Load | Open-loop arrivals plus observed completions; p50/p95/p99, error rate, queue age and resource saturation. |
| Distribution | Many small instances plus hot roots and noisy tenants. |
| Duration | Warm-up, steady state, overload/recovery and a meaningful soak. |
| Failure | Worker/network/database disruption under load; lost/duplicated business outcomes and recovery duration. |
| Comparison | Same business model, semantics, hardware and durability for baseline engines. |

Do not infer that an agenda core is faster than a PVM core, or that tenant sharding scales linearly, without these measurements.

#### 12.2 Multi-database support

Liquibase can organize migrations; it does not eliminate dialect differences. Test isolation, deadlock classification, SKIP LOCKED/READPAST behavior, lock escalation, indexes, LOB/JSON handling, timestamp precision, sequences, case/accent sensitivity, uniqueness, empty-string behavior and online DDL. Exact engine/driver versions belong in the support matrix.

Database portability means the same intended behavior works on supported engines. It does not mean that changing a config value moves existing data. Oracle↔SQL Server cutover needs export/transformation, reconciliation, outage/dual-write policy where applicable, external-effect deduplication and a tested recovery plan.

DB-per-tenant requires provisioning, credentials, connection-pool budgets, schema-version tracking, rollout batches, failed-tenant recovery and backup inventory. Cross-tenant reporting is a separately authorized capability. Live tenant transfer is a migration with identity, secrets, policy and event-routing effects.

#### 12.3 HA and disaster recovery

Three application nodes do not deliver the availability target by themselves. PostgreSQL documents asynchronous replication as its default; committed transactions may be absent on a failover target. [S29] “Replicated database” therefore does not establish RPO 0.

Specify the failure boundary for RPO/RTO: process crash, host/AZ failure, region failure, corruption, operator error or lost credentials. State synchronous commit requirements, fencing and failover behavior. Recovery must include database, object storage, model artifacts, keys, inbox/outbox, broker offsets and external-operation records. Replication is not a backup against corruption.

For a 30-day month, 99.95% availability allows roughly 21.6 minutes of unavailability; define the measured service boundary and error-budget policy. Test runtime service separately from history/UI freshness. Readiness should not indiscriminately fail because an optional analytics broker is unavailable.

After restore, previously completed external operations may be replayed by restored old engine state. Stable business IDs and reconciliation must survive this scenario. Define per-tenant restore effects in a shared database and prove consistency of encryption keys and referenced files.

#### 12.4 Upgrades, retention and observability

Expand/contract migrations need per-database online-DDL review, backfill ownership, compatibility windows and rollback/run-forward plans. A Kubernetes Job is one deployment mechanism; VM/plain Docker users need an equivalent controlled migration command. A session lock is an extra design choice, not a substitute for compatibility testing.

Retention must handle active long-running instances, legal holds, partially projected events, references, search indexes and backups. The append-only audit promise requires a threat model and verifiable tamper evidence or protected storage; an ordinary mutable SQL table is not inherently immutable. Partitioning by removal time needs policies for unknown or changed removal times.

Control metric cardinality: tenant, instance and user IDs are generally unsuitable as unbounded metric labels. Keep necessary IDs in secured logs/traces. Trace links can be appropriate across long async gaps, but do not impose one universal parent/link rule on every async operation. Define sampling, PII redaction, context size, retention and behavior when telemetry export fails.

Virtual threads need bounded database/connector concurrency and cancellation. Current JDK changes remove major synchronized-related pinning cases, not every possible bottleneck or native interaction. [S30] Test actual drivers and workloads.

### 13. Review of every proposed revision R1–R35

These are assessments of proposed controls, not evidence that any control has been implemented.

| Revision | Assessment | Additional requirement |
|---|---|---|
| R1 — relay-driven NOTIFY | Retain as project policy/hypothesis | Poll fallback, minimal payloads, outage/queue behavior and measured benefit. |
| R2 — acquisition SQL by database | Retain | Pin database/isolation versions; test correctness, starvation, escalation and fairness, not syntax alone. |
| R3 — global lock order | Incomplete | Include multiple roots, subscriptions, batch/cancellation paths and bounded transactions. |
| R4 — outbox guard/dedup/lag | Incomplete | Specify actual claim/ack algorithm, per-consumer cursor, ordering and poison-record handling. |
| R5 — Java 25 | Reasonable baseline | Certify complete framework/driver/deployment matrix and patch support; do not equate it with no pinning. |
| R6 — remove Rhino, use WASM/isolates | Revise | Threat model and exact runtime edition/license; UNTRUSTED policy where applicable; host-function review and process isolation option. |
| R7 — native only for light profile | Retain | Remove broader native parity promises and publish the supported profile. |
| R8 — DMN footprint/TCK | Retain | Test modeler coverage, semantic edge cases and actual versions; avoid three simultaneous full support burdens without demand. |
| R9 — streaming fallback/unlock | Revise urgently | Do not release acknowledged executing work simply because a stream closes; define ACK/lease lifecycle. |
| R10 — pinned FEEL and strict null | Revise | Predicate-specific strictness and secure semantic compatibility; valid null values remain valid. |
| R11 — bounded JSON/default typing off | Retain | Limits on total request/model/payload growth, numbers, references and decompression; enforce before expensive parsing. |
| R12 — controlled migration job | Retain with deployment expansion | Plain VM/Docker mechanism, per-DB online DDL and safe failed-migration recovery. |
| R13 — OTel links/context | Refine | Per-operation semantic conventions; control cardinality, sensitive data and exporter failures. |
| R14 — read-only MCP/approval tasks | Necessary but insufficient | Read permissions/data minimization; tool identity; action-bound approvals; cancellation, budget and unknown-outcome handling. |
| R15 — XES first/OCEL tests | Sensible deferral | Domain object identities/relationships and lifecycle timestamps; engine history alone may not yield meaningful object-centric logs. |
| R16 — AsyncAPI docs/schema generation | Retain | One canonical semantic schema and automated consistency across proto/OpenAPI/AsyncAPI. |
| R17 — database tiers | Retain | Align roadmap/CI claims; declare exact support, licensed CI images and actual hardware availability. |
| R18 — SDK tiers | Still too wide for first pilot | Six Tier-1 worker SDKs are a large product obligation; start with required pilot languages and expand using evidence. |
| R19 — permissions table/list filtering | Revise urgently | List and single-resource ABAC equivalence, revocation/cache behavior, historical and field access; benchmark realistic distributions. |
| R20 — fencing/heartbeats | Retain with narrower guarantee | Engine fencing does not deduplicate remote effects; use stable business-operation IDs and reconciliation. |
| R21 — expired-job sweeper | Retain | DB-authoritative lease time, legal state transitions, concurrent sweeper fencing and recovery metrics. |
| R22 — reference-process benchmarks | Retain | Include total backlog, realistic instance lifetime, durability, resource cost and all committed Tier-1 databases. |
| R23 — silent end on concurrent delete | Revise urgently | Suppress spurious engine errors without hiding dispatched business effects; retain cancellation/tombstone/reconciliation evidence. |
| R24 — hardened XML factory | Retain, widen | Cover every parser/transformer/resource resolver; verify the CVE independently of the code weakness. |
| R25 — no expressions in queries | Clarify | Distinguish arbitrary client expressions from versioned, reviewed named-query handlers in L6; both need bounded evaluation and permissions. |
| R26 — worker-bound operations | Retain | Bind to authenticated service principal, tenant, task lease and topic; a client-supplied worker name is insufficient. |
| R27 — connector egress | Retain, tailor | Explicitly approved internal endpoints for enterprise use; redirects/DNS/callback signatures and per-tenant policy. |
| R28 — safe defaults | Retain | Per-tenant limits, bounded bytes, pagination and per-operation retry policy. Non-idempotent effects must not receive automatic blind retries. |
| R29 — retention/variable limits | Refine | Audit minimum, active-instance retention, legal holds, references and non-SQL copies; partition lifecycle rules. |
| R30 — bounded caches/soak | Retain | Invalidation, tenant/model keys and representative load; weak references and 24 hours alone do not prove leak freedom. |
| R31 — UTC/timezone/catch-up | Retain, specify semantics | Distinguish elapsed hourly cadence from local calendar cadence; define DST fold/gap and business-calendar behavior. |
| R32 — upgrade matrix/default flags | Retain | Executable support window and critical path; old active models may outlive a single flag/LTS window. |
| R33 — FEEL type contract | Retain, complete | Runtime as well as deployment validation, precise decimals/time, and explicit error/null/result behavior. |
| R34 — fill BPMN gaps | Scope decision needed | Reproduce each alleged gap on pinned upstream code; publish release conformance profile and unsupported-element behavior. |
| R35 — exclusive at completion | Revise urgently | State-update serialization and business-effect exclusivity are different contracts; validate the intended one before dispatch. |

### 14. Analysis-method review

The use of stakeholders, MoSCoW, SWOT, interface analysis, FMEA, STRIDE, pre-mortem and traceability is valuable. However, breadth of named techniques is not a substitute for verifiable results.

| Method | Good feature | Limitation to fix |
|---|---|---|
| Evidence tags | Explicit fact/vendor/inference/unknown categories | Several future capabilities and uniqueness claims are marked as facts. Use separate observed, planned, tested and accepted statuses. |
| 5W1H | Connects components to reasons and users | A reason such as “competitor has no feature” needs exact version/edition and evidence. |
| Standards gap analysis | Calls out missing semantics instead of only formats | Keyword absence is not proof of missing behavior. Read dependencies, call paths and tests, then run a minimal reproducer. |
| SWOT | Recognizes scope and fork drift | Some strengths are proposed features, while competitors are judged on current limitations. Compare current with current and planned with planned. |
| Pairwise conflict analysis | Finds useful coupling risks | Seventeen documented interactions do not demonstrate exhaustive N-squared coverage. Add the component inventory and missing interactions. |
| ATAM-style analysis | Names real trade-offs | Add concrete stimulus/environment/response measures and stakeholder priorities; the table is not a completed formal evaluation. |
| FMEA | Correct arithmetic and preventive/detection columns | Residual scores are assigned, not measured. Keep high-severity hazards visible even when their RPN decreases. Add recovery, owner, evidence and closure criteria. |
| Fault trees | Useful failure decomposition | “Blocked” is too strong for remote effects; include uncertain outcomes, restore and common-cause failures. |
| STRIDE | Broad component coverage | Add data-flow diagrams, assets/trust boundaries, actor privilege and abuse cases. Distinguish threat classes from product-specific CVEs. |
| Walkthroughs | Good design exercises | Ten narrated successes are not executable simulations. Replace checkmarks with pending acceptance tests until run. |
| Capacity estimate | Better than a bare TPS claim | Validate persistence operations, lifetime, storage and failure recovery, not just CPU/transaction throughput. |
| LOC estimates | Useful inventory | LOC/person-day is a weak predictor for semantic ports, security review and maintaining a fork. Use prototypes and work-package uncertainty. |
| Success estimates | Explicitly called inference | Avoid apparent precision without calibration; stage-gates are more actionable. |

Requirements should be classified into implemented, inherited-and-validated, planned, deferred and unsupported. “Covered by design” and “fixed” must not mean the same thing. Every high-impact control needs evidence before risk is considered retired.

### 15. Roadmap, staffing and lifecycle corrections

#### 15.1 Current dependency problems

1. Authorization/OIDC and durable outbox are Phase 2, while Phase 1 already exposes APIs/workers. A private engineering demonstration can precede them; a real customer pilot cannot omit the relevant controls.
2. The first usable tasklist/admin functions arrive after a large backend scope. A human-workflow product needs a minimal usable application early.
3. The graphical modeler arrives in Phase 3 despite modeling being a Must use case. Decide whether a supported external modeler is the initial authoring route, or deliver a bounded internal editor earlier.
4. Migration is Phase 4 although migration demand is a principal market argument. Bring assessment/import/compatibility earlier if that is the first market; advanced live-state migration can remain separate.
5. Phase 0 includes a broad rename/refactor/schema redesign before customer value is established. Keep inherited internals stable where possible while the product contract and critical gaps are tested.
6. The Phase-1 roadmap still carries 1000 instances/s although R22 revises performance by reference process. DESIGN §16 establishes precedence, but stale sections still make implementation and release planning error-prone. Consolidate them.
7. Database tiers conflict with older “all databases on every commit” promises, and native-light restrictions conflict with broader portability wording. Replace old commitments with one authoritative support matrix.
8. Model immutability, automatic CMMN conversion, replay, continuing old behavior and five-year support create long-term obligations that are not captured fully in the delivery estimate.

#### 15.2 Proposed evidence-led sequence

| Stage | Deliverable | Exit decision |
|---|---|---|
| A. Product and baseline validation | One primary buyer/use case; compatibility corpus; stable runtime candidate comparison; license inventory | A real advantage and a feasible, supported technical route exist. |
| B. Complete vertical pilot | Model/rule/form package; secured tasklist; required worker languages; audit; inbox/outbox; cancellation/reconciliation; chosen DBs | Representative business process completes and recovers from injected failures. |
| C. Production hardening | Authorization coverage, tenant tests, restore/failover, upgrade rehearsal, operational dashboards, support procedures | Defined pilot service levels and recovery objectives are met. |
| D. Product expansion | Additional languages, brokers, forms, tenancy profiles and migration tools driven by pilot evidence | Adoption/support metrics justify each expansion. |
| E. Advanced scope | Full chosen CMMN profile, agent journal, advanced dynamic migration, extra DBs, native-light profile or partitioned execution | Dedicated requirements, ownership, license review and semantic acceptance tests. |

The long-term full-feature vision can remain. A release must state which part of that vision is supported. Sequencing is not permission to silently drop agreed requirements.

#### 15.3 Team and effort interpretation

The 143–220 person-month total is arithmetically sound. It does not include the entire migration/scale phase, indefinite security support, commercial delivery, all legal/compliance work or every operational responsibility. UI reuse, CMMN editor work and cross-runtime semantic ports may have larger uncertainty than their line items suggest.

An 8–10-person team does not consist of ten interchangeable units. Core semantics, database concurrency, authorization, UI, SDKs, QA and operations have different skill and review needs. Dependencies, ramp-up and required independent reviews affect the critical path. Establish named ownership and backup maintainers for core/security/operations before making support commitments.

Re-estimate using measured spikes: one difficult migration case; one complete permission-aware list and action flow; worker failure recovery; one SDK; and one upgrade on each committed Tier-1 DB. Report estimate ranges and excluded work. Do not replace the documents' speculative success percentage with another invented percentage.

#### 15.4 Long-lived platform obligations

A minor release every three months plus an LTS every two years with five-year fixes can mean approximately three concurrent LTS branches, alongside current work. Budget backports, compatible dependencies, test environments and documentation accordingly. “Upgrade every framework within six months” must not force unsupported or unnecessary breaking changes into supported trains.

Keep a fork patch ledger: upstream commit, rationale, touched subsystem, regression test, merge strategy and security relevance. Prefer upstream contributions where feasible. Document who owns security advisories, release signing, package names, trademarks, contributor rights and product end-of-life/export commitments.

### 16. Required acceptance tests and release evidence

These are proposed meaningful tests, not tests executed during this review.

| ID | Test | Required observable outcome |
|---|---|---|
| T01 | Duplicate process-start requests, simultaneous and after timeout | One intended business instance; repeated response consistent; conflicting payload rejected. |
| T02 | Crash after downstream payment success, before completion | No duplicate payment for supported idempotent integration; otherwise an explicit unresolved outcome. |
| T03 | Old worker resumes after lease expiry | Stale engine mutation rejected; business-effect handling remains safe. |
| T04 | gRPC stream breaks while worker still runs | No unjustified immediate concurrent redispatch of acknowledged work. |
| T05 | Two external tasks marked exclusive | Actual intended execution exclusivity, not just serialized completion. |
| T06 | Late commit with lower outbox ID | Every committed event eventually delivered. |
| T07 | Relay crash after publish/before mark | Duplicate safe at each consumer; no missing history/export destination. |
| T08 | Poison outbox event and later same-instance events | Documented block/gap policy; visible repair path. |
| T09 | Incoming event before subscription, and duplicate after commit | Documented buffering/rejection; no lost signal or duplicate business start. |
| T10 | Parent/child modification, timer and concurrent cancellation | No corruption/deadlock livelock; consistent cancellation outcome. |
| T11 | Delete instance during external action | Tombstone/audit/late response and reconciliation preserved. |
| T12 | Crash after agent tool response before journal commit | Idempotent recovery or explicit uncertainty; no unsupported “never repeat” claim. |
| T13 | Tenant spoofing in API, worker, broker and file reference | Cross-tenant access denied across every entry point. |
| T14 | ABAC direct read versus list/count/export | Equivalent visibility and permissions. |
| T15 | Approver permission revoked after form load | Sensitive action denied or reauthorized according to policy. |
| T16 | Amount/document/beneficiary changed after approval | Prior approval invalidated or controlled reapproval required. |
| T17 | Initiator also has checker/approver assignment | Segregation-of-duties policy enforced by person and assignment. |
| T18 | Decimal/large integer/date/null through all supported SDKs | Identical intended business values and branch decisions. |
| T19 | DMN/rule/form updated while process remains active | Explicit version binding honored. |
| T20 | Unsupported executable BPMN/CMMN construct | Clear deployment rejection or documented non-executable treatment. |
| T21 | Model modification and migration with timers/MI/compensation | Supported invariants preserved; unsupported states rejected. |
| T22 | DST gap/fold, holiday calendar, long downtime | Exactly the documented schedule/catch-up behavior. |
| T23 | Hostile XML/JSON/script/connector destination | Bounded resource use; no unauthorized filesystem/network/host access. |
| T24 | Large tenant plus many small tenants | Enforced quotas and bounded starvation; no unbounded queue/pool impact. |
| T25 | Broker outage beyond designed buffer window | Defined throttling/degradation and measurable backlog recovery. |
| T26 | DB failover under acknowledged writes | RPO/RTO measured for the specified topology and failure boundary. |
| T27 | Restore after real external effects have already completed | No blind duplicate effects; reconciliation and deduplication retained. |
| T28 | Rolling N/N-1 upgrade with active processes | Schema, job and protocol compatibility; a working recovery plan. |
| T29 | Tenant provisioning/migration failure mid-batch | Other tenants continue; failed tenant repair is controlled and observable. |
| T30 | Retention/erasure with hold and referenced objects | Required evidence retained; permitted sensitive data handled across all copies. |
| T31 | Stable-load benchmark with audit/auth enabled | Completed throughput, tail latency, errors, queue age and cost meet declared targets. |
| T32 | First-time user completes real task flow | Usable onboarding and errors; keyboard/accessibility and required languages validated. |

Release evidence should include the exact build/model versions; environment manifest; test output; benchmark raw data; known limitations; upgrade/restore runbooks; compatibility and license matrix; SBOM/provenance; and an accountable owner for every unresolved high-impact risk.

Do not inflate the PR pipeline with every expensive tool on every trivial change. Run targeted unit/contract/security/affected-DB checks on PRs, broader matrices and mutations on schedules or changed core modules, and complete required release evidence before shipping. Resolve the current inconsistent gate descriptions explicitly.

### 17. Section-by-section coverage ledger

The ledger shows where every major section of both input documents was considered. It indicates review coverage, not exhaustive proof of all possible defects.

#### ANALYSIS.md

| Input section | Review disposition |
|---|---|
| Summary | Preserve scope warning; correct uniqueness, risk-reduction interpretation and confidence figures (§5, §14). |
| §0 Method/rules | Keep evidence taxonomy; separate future plans from verified implementation (§2, §14). |
| §1 Stakeholders/use cases | Add buyer, budget owner, support team and real adoption evidence; align Must items with pilot (§6, §11). |
| §2 5W1H | Retain structure; qualify source/edition and avoid unproven feature-absence reasoning (§5). |
| §3 Standards gaps | Reproduce at pinned versions; formal profile; modeler/runtime parity; no universal CMMN conversion (§9). |
| §4 Market | Refresh competitor facts; compare jobs-to-be-done and lifecycle cost; eliminate unsupported uniqueness (§5–§6). |
| §5 SWOT | Distinguish delivered strengths from proposed strengths; include community, distribution and support burdens (§6, §14–§15). |
| §6 Technical choices | Review runtime licenses, database semantics, journals, versioning and resource boundaries (§7–§10, §12). |
| §7 Interfaces/conflicts | Add inbound durability, list ABAC, migration/restore and complete lock semantics (§8–§10). |
| §8 Trade-offs | Turn general trade-offs into measurable scenarios and buyer priorities (§12, §14, §16). |
| §9 FMEA | Totals verified; controls remain unimplemented hypotheses; add recovery and evidence (§5, §14). |
| §10 Fault trees | Correct fencing/side-effect protection; extend for uncertain outcomes, restore and common causes (§8). |
| §11 STRIDE | Add data flows/trust boundaries, revocation, read-side AI data leaks and dependency-specific CVEs (§5, §10). |
| §12 Production failures | Preserve regression-test leads; verify source/version and actual affected behavior before claiming fixed (§2, §14). |
| §13 Scenarios | Treat as walkthroughs; add effect/commit crash windows, finite capacity, state cancellation and DR (§8, §16). |
| §14 Capacity | Keep realistic R5; include active lifetime, storage, queues, durability and reproducible benchmarks (§12). |
| §15 Code/effort | Totals checked; upstream LOC not independently reproduced; improve estimation method and exclusions (§2, §15). |
| §16 Pre-mortem | Add failure to win paying users, operational sustainability and loss of migration simplicity (§6, §15). |
| §17 Risk register | Keep owners; add detection evidence, deadline, residual acceptance and lifecycle cost (§14–§16). |
| §18 Verdict/probability | Future superiority is not verified; replace spurious precision with gates (§3, §5, §18). |
| §19 R1–R35 | Every revision assessed individually (§13). |
| §20 Sources | Prefer pinned primary evidence; separate third-party reports and inaccessible/unverified records (§5, §19). |

#### DESIGN.md

| Input section | Review disposition |
|---|---|
| Summary/name | Treat Sangam as placeholder; product identity/clearance not validated by this technical review. Core reuse principle retained. |
| §1 Research/weaknesses | Compare stable snapshots and actual tests; several market/absence claims need correction (§5). |
| §2 Core decisions | One runtime is sensible; baseline choice needs a buyer-driven spike; specify delivery semantics (§7–§8). |
| §3.1–§3.3 Standards/import | Define support profiles and distinguish import, API and live-state compatibility (§9). |
| §3.3a Agreed scope | Keep long-term intent visible; releases must distinguish delivered from deferred requirements (§15). |
| §3.3b CMMN retirement | Isolation useful; automatic equivalence/migration not guaranteed (§9 E16). |
| §3.3c DMN preview | Formal/beta distinction valid; namespace acceptance is not semantic support (§5, §9). |
| §3.3d Durable ideas | Semantic diff useful; replay/journal/continue-as-new need contracts; Temporal interop is a connector, not shared execution semantics (§8–§9). |
| §3.4 DMN selection | Correct conformance ranking and library/engine distinction; validate footprint and editor support (§5, §9). |
| §4 Architecture/modules | Bound refactor and patch surface; contract/UoW detail; avoid table-count goal and easy-service-extraction claim (§7). |
| §4.2–§4.4 Core/concurrency/jobs | Multi-root order, lease state machine, external exclusivity, fair scheduling and fencing (§8). |
| §4.5–§4.6 Outbox/history | Add inbox; exact relay algorithm; distinguish audit and replay; retention/backpressure (§8–§9, §12). |
| §4.7 Dynamic changes | Preserve business invariants, authorization and external-effect audit; migration is specialized (§9–§11). |
| §5 Polyglot/execution modes | Specify API idempotency, callbacks, worker lifecycle and fewer initial SDKs; certify embedded behavior separately (§7–§9). |
| §5.4–§5.6 Expressions/deployment/DB | Typed money/time/null, exact sandbox and licenses, native-light scope and DB semantics (§5, §9, §12). |
| §6 Events | Durable inbound contract, correlation rules, object identity and backpressure (§8). |
| §7 Security/tenancy | Uniform list/object policies, trusted tenant binding, revocation, secrets and tenant lifecycle (§10). |
| §8 UI | Complete user flows, preview maturity, form-schema separation, modeler parity and manual accessibility (§9, §11). |
| §9 Operations | Conditional HA/DR, cardinality/privacy, upgrades and support runbooks (§12). |
| §10 Limits | Preserve honest workload limits; a new storage paradigm still requires execution redesign (§7, §12). |
| §11 Roadmap | Move pilot safety/usability earlier; migration early if its buyers are primary; reduce initial breadth (§15). |
| §12 Legal checklist | Add exact dependency/artifact licenses, transitive notices, sandbox editions and distribution responsibilities (§5, §19). |
| §13 Upstream files | Useful starting inventory; not proof of a small or complete port. Require commit-pinned behavior/tests (§2, §7). |
| §14.1 Analysis principles | Keep traceability/ADRs; tests are evidence of requirements, not automatic proof of completeness (§14). |
| §14.2 Design principles | Keep clear contracts; distinguish local modularity from distributed extraction; complete idempotency (§7–§9). |
| §14.3 Development principles | Useful engineering practices; calibrate tooling cost and maintain review ownership (§15–§16). |
| §14.4 Conformance | Keep MIWG/runtime distinction; strengthen recovery/replay claims and versioned evidence (§9, §16). |
| §14.5 Security program | Define applicable requirements and manual assurance, not checklist certification (§10). |
| §14.6 Quality targets | Benchmark assumptions; conditional RPO/RTO; consistent DB/native matrix; current quality-model edition (§5, §12). |
| §14.7 Lifecycle | Budget simultaneous LTS branches, security patches and old model behavior (§15). |
| §14.8 Traceability | Preserve links; add delivery/evidence status and business requirement coverage (§14, §16). |
| §14.9 Gates | Tier and reconcile expensive checks; publish actual outcomes (§16). |
| §15 Study status/sources | Refresh the pending/completed status; distinguish document assertions from reproduced source evidence (§2, §5). |
| §16 Revision precedence | Correctly provides an override mechanism; consolidate conflicting earlier prose/roadmap into one executable specification (§15). |

### 18. Concrete decisions and open evidence register

#### Release decision gates

| Gate | Pass evidence | Decision if absent |
|---|---|---|
| G1 — Buyer/problem | Named primary use case, accountable buyer and validated adoption reason | Continue discovery, not broad feature construction. |
| G2 — Base selection | Same test corpus and constraints on viable existing-runtime options | Keep base provisional; avoid deep fork/refactor. |
| G3 — License/dependency route | Exact artifacts/versions, redistribution and sandbox policy accepted | Change dependency/profile or obtain suitable rights; do not use an inaccurate Apache-only claim. |
| G4 — Correct business outcomes | T01–T12 and critical type/version tests pass | No production money/action workflows. |
| G5 — Isolation/authority | Cross-tenant, list policy, revocation and approval-binding tests pass | No multi-tenant or real-user pilot with sensitive data. |
| G6 — Operability | Restore, failover, upgrade and backlog-recovery evidence | Limit to controlled evaluation. |
| G7 — Usable application | Representative human workflow and exception flow work end to end | Do not label engine demonstration a complete application. |
| G8 — Sustainable delivery | Staffing, support ownership, cost/runway and explicit supported scope | Reduce release promises before contractual commitments. |

The documents do not supply enough evidence to mark these gates passed. They do provide a useful starting backlog for making them pass.

#### Information to resolve during the next working stage

This is an evidence backlog, not a request to stop this review for answers:

- First buyer and buying reason; first three business workflows; alternatives already considered.
- Stable upstream release/version and the exact patches genuinely required.
- Expected active instances, arrival rate, lifetime, payload, history retention and tenant distribution.
- First required databases, languages, deployment environment and support responsibilities.
- Rules for identity/assignments, delegation, segregation of duties and organization changes.
- Which integrations support idempotency/outcome lookup, and which require reconciliation.
- Required standard profiles and modeler authoring capabilities.
- Model/rule/form/policy version-binding and migration/run-off obligations.
- Data sensitivity, retention/legal hold, disaster boundary and acceptable downtime.
- Exact OSS/commercial license promise and funded maintenance runway.
- Actual staffing, independent review capacity and continuing customer support obligations.

**Recommended immediate work:** consolidate the two specifications, build the narrow compatibility/failure-test corpus, compare minimally changed runtime options and produce one complete secured business-flow pilot. A full new workflow platform becomes a justified investment only when that evidence supports its customer value and operating burden.

### 19. Primary-source register

Primary pages were inspected or returned as relevant primary-source search results during this review. Availability and product behavior must still be pinned to exact supported releases. Source links support the stated external facts; the engineering recommendations are this review's judgments.

| ID | Source | Use in review |
|---|---|---|
| S01 | [Operaton FAQ](https://operaton.org/faq/) | Apache license, migration/ecosystem and MCP offering. |
| S02 | [Operaton introduction](https://docs.operaton.org/docs/documentation/introduction/) | Standards, modeler and web application coverage. |
| S03 | [Operaton 2.2 release notes](https://docs.operaton.org/docs/documentation/reference/release-notes/2_2/) | Preact webapps-neo technical preview. |
| S04 | [Camunda AI agents](https://docs.camunda.io/docs/components/agentic-orchestration/ai-agents/) | Native/external agent orchestration. |
| S05 | [Flowable external clients](https://documentation.flowable.com/latest/develop/external-clients), [official .NET client](https://github.com/flowable/flowable-external-client.net), [official JS client](https://github.com/flowable/flowable-external-client-js) | Existing polyglot client offerings; OSS compatibility still to test. |
| S06 | [Flowable DMN REST API](https://www.flowable.com/open-source/docs/dmn/ch07-REST), [Decision Task](https://documentation.flowable.com/latest/reactmodel/bpmn/reference/decision-task) | DMN execution capability versus element/edition compatibility. |
| S07 | [bpmn-js license](https://github.com/bpmn-io/bpmn-js/blob/develop/LICENSE) | Visible watermark requirement. |
| S08 | [Liquibase 5.0 release notes](https://docs.liquibase.com/community/release-notes/5-0), [license](https://github.com/liquibase/liquibase/blob/main/LICENSE.txt) | FSL license change and exact-term review. |
| S09 | [GraalVM 25 security guide](https://www.graalvm.org/jdk25/security-guide/), [SandboxPolicy API](https://docs.oracle.com/en/graalvm/jdk/25/sdk/org/graalvm/polyglot/SandboxPolicy.html) | Edition limitations and ISOLATED/UNTRUSTED distinction. |
| S10 | [DMN TCK results](https://dmn-tck.github.io/tck/), [TCK scope](https://dmn-tck.github.io/tck/about.html) | Versioned published test results and limits. |
| S11 | [OMG DMN 1.6](https://www.omg.org/spec/DMN/1.6), [DMN 1.7 Beta 1](https://www.omg.org/spec/DMN/1.7/Beta1) | Formal and beta status. |
| S12 | [Apache KIE DMN](https://kie.apache.org/drools/dmn/) | Documented conformance and DMN version support. |
| S13 | [A2A releases](https://github.com/a2aproject/A2A/releases) | A2A 1.0.1 release. |
| S14 | [MCP specification](https://modelcontextprotocol.io/specification/2026-07-28) | Dated protocol baseline and task capability. |
| S15 | [Camunda licensing](https://docs.camunda.io/docs/reference/licenses/) | Production licensing by component. |
| S16 | [Camunda 8.9 secondary storage](https://docs.camunda.io/docs/8.9/self-managed/concepts/secondary-storage/) | RDBMS secondary versus primary execution storage. |
| S17 | [n8n 2.0 breaking changes](https://github.com/n8n-io/n8n-docs/blob/main/docs/changelog/v20-breaking-changes.md) | Removal of MySQL/MariaDB as storage backends. |
| S18 | [Google Application Integration release notes](https://docs.cloud.google.com/application-integration/docs/release-notes#January_23_2025) | Rhino deprecation in that product; not a Flowable CVE determination. |
| S19 | [NVD CVE-2025-6514](https://nvd.nist.gov/vuln/detail/cve-2025-6514) | mcp-remote authorization-endpoint command injection. |
| S20 | [ISO/IEC 25010:2023](https://www.iso.org/standard/78176.html) | Nine-characteristic current edition. |
| S21 | [Temporal activities documentation](https://github.com/temporalio/documentation/blob/main/docs/encyclopedia/activities/activities.mdx), [workflow documentation](https://docs.temporal.io/workflows) | Activity idempotency and code-defined execution. |
| S22 | [Conductor human task](https://docs.conductor-oss.org/documentation/configuration/workflowdef/systemtasks/human-task.html), [system tasks](https://docs.conductor-oss.org/documentation/configuration/workflowdef/systemtasks/index.html) | Human and other orchestration capabilities. |
| S23 | [n8n license guidance](https://support.n8n.io/article/can-i-use-your-license-for-my-use-case) | Sustainable Use License and product-use constraints. |
| S24 | [Kestra multi-tenancy](https://kestra.io/docs/enterprise/governance/tenants) | Edition-specific tenant capability. |
| S25 | [Windmill repository license](https://github.com/windmill-labs/windmill/blob/main/LICENSE) | Mixed license model; compare actual files/features. |
| S26 | [AWS transactional outbox guidance](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html) | Duplicate delivery and idempotent consumers. |
| S27 | [PostgreSQL NOTIFY](https://www.postgresql.org/docs/current/sql-notify.html) | Transactional notification semantics and visibility. |
| S28 | [form-js form schema](https://github.com/bpmn-io/form-js/blob/develop/docs/FORM_SCHEMA.md) | JSON form format, distinct from generic data schema. |
| S29 | [PostgreSQL replication documentation](https://www.postgresql.org/docs/current/warm-standby.html) | Asynchronous default and conditional synchronous durability. |
| S30 | [Oracle JDK 24 release notes](https://www.oracle.com/java/technologies/javase/24all-relnotes.html) | JEP 491 synchronization/pinning improvement. |

Additional qualification: CVE-2026-91197 was not independently confirmed from readable authoritative evidence during this review. Its absence from readable results is not proof that the identifier is invalid. The underlying alleged XML path should be assessed separately against the supplied commit once the source is available.




---

# Part IV — PDF Report: New Content, Differences and Structure Map

## PDF report (C) vs DESIGN.md (A) and ANALYSIS.md (B): what is new or changed

Inputs:
- A = `D:\GITHUB  Project\Custom WorkFlow\DESIGN.md` (760 lines)
- B = `D:\GITHUB  Project\Custom WorkFlow\ANALYSIS.md` (792 lines)
- C = `report.txt`, text extracted from the 27-page PDF "Project Sangam — Comprehensive End-to-End Analysis Report"

Method: I read all 27 pages of C and checked every table row, number, phase, name and count against A and B. A §16 says the R1–R35 revisions take precedence over earlier sections of A. So where C follows an R-change and not A's original text (for example DB tiers, SDK tiers, JDK 25, no Rhino, native image only for a light profile), I count that as a restatement, not a contradiction.

---

### 1. Structure map

| PDF page | PDF section / heading (translated) | Corresponds to |
|---|---|---|
| 1 | Title: "End-to-End Architecture, Market Intelligence & Pre-Development Audit — Project Sangam, complete analysis report"; subtitle; baseline sources; date; list of evaluation roles | **NEW** (title, framing, roles). Baseline commits/versions come from B header and A header |
| 1 | Executive summary | A "सारांश" (summary) + B "सारांश" + B §4 (open slot) + B §9 (RPN 4,581 → 712) + B §19 (R1–R35) |
| 1 | §0 Methodology, evidence rules and the 5 expert roles | Evidence-tag table = B §0.1. "5 expert roles", "zero-omission" and "19 dimensions under 5 roles" = **NEW** (B §0.2 lists the 19 analyses, but not the roles) |
| 2 | §1 Stakeholders (S1–S10) | B §1.1 |
| 2–3 | §1.1 12 use cases with MoSCoW (UC1–UC12) | B §1.2 |
| 3 | §2 "Role 1: Chief Distributed Systems Architect — core architecture comparison" | Role framing **NEW**; content as below |
| 3–5 | §2.1 Operaton vs Flowable 8 vs Sangam (22-point table) | A §1.1 (22 rows), with R-changes from A §16 / B §19 merged into the winner column |
| 5–6 | §2.2 18 shared weaknesses W1–W18 and Sangam's fix | A §1.2, with R-changes merged in |
| 6–8 | §2.3 5W1H per design element | B §2 |
| 9–10 | §2.4 "Proposed 22-module layout" and key upstream source files | A §4.1 and A §13 (the "22" count is C's own; see contradiction X9) |
| 10 | §3 "Role 2: Standards, polyglot and AI platform specialist" | Role framing **NEW** |
| 10–11 | §3.1 Element-level standards gap analysis (11 Flowable-base gaps + 6 gaps in both) | B §3.1 + B §3.2 + R34. Phase assignments for 7 gaps = **NEW** (item N4) |
| 11 | §3.2 DMN 1.6 / 1.7 Beta policy and the "retire-safe" CMMN strategy | B §3.3, A §3.3b, A §3.3c, A §3.4 |
| 12 | §3.3 10 lessons from YAML DSLs and durable execution (L1–L10) | A §3.3a (scope decision) + A §3.3d |
| 12 | §4 "Role 4: Market and product strategist" | Role framing **NEW** |
| 12–13 | §4.1 Comparison with 14 market products | B §4 table (positioning conclusions 1–5 not reproduced) |
| 14 | §4.2 SWOT at four levels | B §5.1–5.4 (merged into one table) |
| 14–15 | §4.3 Comparison on 7 dimensions ("Will Sangam be better?") | B §18.1 |
| 15 | §5 Technical evidence, interface conflicts, ATAM (no role named) | — |
| 15–16 | §5.1 Evidence review of 18 technical choices | B §6 |
| 16–17 | §5.2 17 interface conflicts C1–C17 | B §7 |
| 17–18 | §5.3 ATAM quality trade-offs | B §8 (sensitivity-point column not reproduced) |
| 18 | §6 "Role 3: CISO and reliability analysis (FMEA, fault trees, STRIDE, simulations)" | Role framing **NEW** |
| 18–20 | §6.1 FMEA: 24 failure modes, 84% lower RPN | B §9 |
| 20 | §6.2 Fault trees T1–T4 "and STRIDE + CVE controls" | B §10. The STRIDE table in B §11 is **not** reproduced, although the heading names it |
| 20–21 | §6.3 Upstream production failures (P1–P18), local code checks, dangerous defaults | B §12. P1–P18 status table not reproduced. The "Sangam safe default (R28)" column is **NEW** (item N7) |
| 21–22 | §6.4 Ten end-to-end scenario simulations S1–S10 | B §13 |
| 22 | §7 "Role 5: Engineering Delivery Director" | Role framing **NEW** |
| 22 | §7.1 Capacity and performance model (R5 write model, targets, Little's law) | B §14.2, §14.3, §14.4 (the §14.1 evidence table is not reproduced) |
| 22–23 | §7.2 Measured codebase and effort for 15 work packages | B §15 and §15.1 |
| 23–24 | §7.3 Project risk register (PR1–PR12), pre-mortem, success probability | B §17 and §18.2. B §16 pre-mortem is named in the heading but **not** reproduced |
| 24 | §8 Engineering principles, roadmap, 35 required changes; §8.1 Quality system and 10+ year lifecycle | A §14.1, §14.3, §14.6, §14.7, §12 (the roadmap, A §11, is named but not reproduced) |
| 25–26 | §8.2 Full table of all 35 required changes R1–R35 | B §19 / A §16 |
| 26–27 | §9 "Expert verdict and 7 decisive strategic recommendations (My Verdict & 7 Strategic Imperatives)" | **NEW** (items N9–N16) |

---

### 2. New content (not in A or B)

**N1 (p.1) Report title and stated scope.**
- Title: "End-to-End Architecture, Market Intelligence & Pre-Development Audit".
- Hindi title: "Project Sangam (संगम) — संपूर्ण विश्लेषण रिपोर्ट" (complete analysis report).
- Subtitle: "Comprehensive Evaluation of DESIGN.md & ANALYSIS.md Across Architecture, Polyglot Standards, Security, Market Positioning, and Delivery Feasibility".
- Page footer: "Project Sangam (संगम) — Comprehensive End-to-End Analysis Report, Page n of 27".
- The executive summary calls Sangam not just an attempt to join two engines, but "an evidence-based engineering blueprint to fill a large open slot in the enterprise workflow market".

**N2 (p.1) Five evaluation roles.** The report says it was evaluated from five expert roles:
1. Chief Distributed Systems Architect
2. Standards, Polyglot & AI Platform Specialist
3. Chief Information Security Officer (CISO)
4. Market & Product Strategist
5. Engineering Delivery Director

The header also lists them as "Evaluation Roles: Chief Distributed Systems Architect, Standards & AI Specialist, CISO, Market Strategist, Engineering Delivery Director".

**N3 (p.1, plus section headings) Method claims and how the report is organised.**
- It claims a "Zero-Omission" evaluation of every point in DESIGN.md and ANALYSIS.md.
- It says the 19 pre-development analysis dimensions were examined under the 5 roles.
- It maps roles to sections as follows:
  - Role 1 (Architect) → §2 (core comparison, weaknesses, 5W1H, module layout)
  - Role 2 (Standards/AI) → §3 (gaps, DMN/CMMN policy, L1–L10)
  - Role 4 (Market) → §4 (market, SWOT, 7 dimensions)
  - Role 3 (CISO) → §6 (FMEA, fault trees, defaults, scenarios)
  - Role 5 (Delivery) → §7 (capacity, codebase, effort, risks, success)
  - §5 (technical choices, conflicts, ATAM) and §8 (principles, R1–R35) have no role.
- The roles appear out of order in the document: Role 4 is in §4 and Role 3 is in §6.

**N4 (pp.10–11) Phase assignments for the BPMN gaps of the Flowable base.** B §3.1 says only "Must: port", plus "Must (Phase 2)" for gap #10. C adds a phase to each gap:

| Gap # (Flowable base) | Gap | C decision |
|---|---|---|
| 1 | Link intermediate catch/throw events | Must: port **in Phase 1** |
| 2 | Signal end event, message end event, message intermediate throw | Must: port **in Phase 1** |
| 3 | Compensation end event; compensation event sub-process | Must: port **in Phase 1** |
| 4 | Process-level conditional start; automatic condition evaluation on variable change | Must: port **in Phase 1** |
| 5 | I/O mapping on any activity (`inputOutput`); connector framework | Must: for polyglot workers and C7 import (no phase given) |
| 6 | Send task with delegates; receive task correlated by `messageRef` | Must: port **in Phase 1** |
| 7 | DMN business-rule task with `decisionRef` and result mappers | Must: port **in Phase 1** |
| 8 | Separate asyncBefore/asyncAfter on the MI body and inner activity; retry cycles; job priorities; incidents | Must: port **in Phase 1** |
| 9 | External-task API semantics | Must: basis of the polyglot worker API (no phase given) |
| 10 | Modification, restart, async correlation, condition-evaluation API | Must: Phase 2 (same as B). C gives the evidence as "Operaton runtime/history services"; B says "Operaton services" |
| 11 | `camunda:` / `operaton:` extension elements in the parser | Should: for UC6 migration (same as B) |

A §11 Phase 1 does not list these gaps, and B §15.1 costs them (10–16 PM) without giving a phase. So the Phase 1 timing is new.

**N5 (p.12) L10 source label.** In the L1–L10 table, L10 (Temporal interop) has "Learned from: Polyglot Enterprise". A §3.3d has "—" there.

**N6 (p.13) Market-table additions that are not in B §4.**
- Flowable OSS licence: "Apache 2.0; UI **and control** commercial [F]". B has "UI commercial". "Control" may mean the Flowable Control product. Uncertain; raw text: `UI और कंट्रोल कमर्शियल हैं [F]`.
- Kestra and Apache KIE performance: "No public benchmark found **[U]**". B has "Not found" with no tag.
- Restate licence: "BSL 1.1 **[F]**". B gives no tag.
- Flowable product name: "Flowable OSS". B has "Flowable".

**N7 (pp.20–21) Defaults table with a new "Sangam safe default (R28)" column.** B §12 has only the Operaton and Flowable columns. C's full table:

| Setting | Operaton default [F] | Flowable default [F] | Sangam safe default (R28) |
|---|---|---|---|
| Authorization | Off | Absent (no service) | On (deny by default) |
| Query max results limit | `Integer.MAX_VALUE` (DoS risk) | Unlimited | 1,000 (configurable) |
| Job retries | Three immediate retries (no interval) | Three retries | `R3/PT1M` with jitter |
| Job lock time | 5 minutes | 1 hour | p99 per job type + heartbeat + fencing |
| SKIP LOCKED acquisition | Off | Absent | On (by default) |
| History cleanup / retention | Not scheduled | Off (365 days once enabled) | On (leader-elected service, TTL warning) |
| Safe XML flags | Hardened | `false` in engine configs (on only in Spring Boot) | Single hardened XML factory (others banned) |

New elements in this table:
- The whole Sangam column. It is assembled from R20, R24, R28 and R29.
- "(configurable)" for the 1,000 limit.
- "(DoS risk)" for the Operaton limit.
- "(no interval)" for the Operaton retries.
- "Hardened" in the Operaton XML cell. B's table has "—" there, although B §5.3 says "Operaton already hardened".
- "Unlimited" for Flowable (see X11).

**N8 (p.23) Work-package annotations that are not in B §15.1.**
- Work package 14, Security Programme: "STRIDE, Jazzer fuzzing, WASM sandbox, ASVS L3, pen-test preparation". B has "threat models, fuzzing, sandbox, ASVS, pen-test prep".
- Work package 15, Documentation, Examples, Benchmarks, Release Engineering: adds "(SLSA L3)".
- Person-month ranges are unchanged.

**N9 (p.26) Overall verdict.** After a complete "post-mortem" of DESIGN.md and ANALYSIS.md from all five roles, the author concludes that the design is "exceptionally mature and solid" technically and architecturally. The seven recommendations below are offered to make the project "100% successful" in practice (see X4).

**N10 (p.26) Recommendation 1: avoid the "scope vs team size" trap; build the wedge MVP first.**
- It calls the 143–220 person-month estimate in B §15.1 "the biggest truth".
- If the core team is smaller than 8–10 engineers, spend "100% of the energy" in the first 6–9 months on only these:
  - PostgreSQL
  - BPMN
  - Drools DMN
  - Outbox
  - SKIP LOCKED
  - Authorization
  - webapps-neo UI
  - 4 main SDKs: Java, Python, Node/TS, .NET
- It claims this is enough to win the "Camunda 7 EOL + Flowable OSS" market.
- See X1, X2 and X3.

**N11 (p.27) Recommendation 2: run an "Authorization Query Spike (R19)" first in Phase 0.**
- Operaton's authorization model will touch every Flowable list query.
- Before the team changes "hundreds of MyBatis XML files", in the **first month of Phase 0**:
  - design the schema of the denormalised permission table;
  - pass the benchmark of 10,000 tasks × 300 groups on PostgreSQL, Oracle and MSSQL.
- B has a "separate design spike in Phase 0" (PR2) and the 10k × 300 benchmark (R19). The first-month timing, the pass-before-rewrite gate and the "hundreds of MyBatis XML files" claim are new.

**N12 (p.27) Recommendation 3: move the Camunda 7 / Flowable XML model importer (UC6) from Phase 4 to "Phase 1.5".**
- Reason given: the largest source of early adoption is teams leaving Camunda 7 (S6).
- Ship the XML namespace mapper (`camunda:` and `flowable:` → Sangam) right after Phase 1, so users can try Sangam without changing their models.
- Keep only the running-instance DB migration tool in Phase 4.
- See X5.

**N13 (p.27) Recommendation 4: practical CI database-matrix discipline in Phase 1.**
- Tier 1 has PostgreSQL, Oracle and SQL Server (R17). Even so, to keep early coding speed:
  - make PostgreSQL the mandatory per-PR gate;
  - run the heavy Oracle and MSSQL Testcontainers suites in daily or milestone builds;
  - enforce them on every PR from Phase 2.
- See X6.

**N14 (p.27) Recommendation 5: keep CMMN in "zero-touch isolation" until Phase 3.**
- Flowable's CMMN engine already works, and new market demand for CMMN is low.
- In Phase 0, only move it into the `sangam-runtime-cmmn` module.
- In the first 12 months, spend no core-team time on the CMMN → BPMN converter or a CMMN web modeler.
- See X7.

**N15 (p.27) Recommendation 6: a "Step Journal Helper" in the SDKs for non-idempotent external systems (residual risk of simulation S2).**
- `agentTask` (L8) has a durable step journal that prevents repeated calls after a crash. Offer the same journal mechanism for ordinary service-task workers as an SDK helper: `context.step("chargeCard", ...)`.
- C claims this protects external payment and legacy APIs that do not support idempotency keys from duplicate execution.
- This is a new feature and a new API name. See X8.

**N16 (p.27) Recommendation 7: publish a comparative "Open Benchmark Report (R22)" at the end of Phase 1.**
- As soon as the agenda core, the outbox and SKIP LOCKED are ready, publish a transparent benchmark of the R1 and R5 workflows for Operaton, Flowable and Sangam on the same 16-vCPU hardware.
- Reason given: this proven data builds Sangam's credibility with the open-source community and enterprise architects.
- B §14.3 already runs Operaton and Flowable as a Phase 1 baseline, and A §14.9 publishes benchmark results at release. The public comparative report at the end of Phase 1 is the new part.

#### Uncertain (garbled extraction)

- p.13: `UI और कंट्रोल कमर्शियल हैं [F]`. "कंट्रोल" (control) could be the Flowable Control product or a garble. Listed under N6.
- p.22: `0. S10 (दुर्भावनापूण BPMN अपलोड)`. The list number "10." was cut to "0."; the content is S10 (malicious BPMN upload). No content issue.
- p.1: `बि` followed by a line break is a broken "बल्कि" ("but rather"). p.11: `इि सिट` is a broken "इम्प्लिसिट" (implicit, for required-decision chaining). p.11: `ि ति` is a broken "स्थिति" (status). p.5 and elsewhere: `कॉि ग` is "कॉन्फ़िग" (config). p.9: `पि क` is "पब्लिक" (public). Reading through these breaks gave no ambiguous values.
- No number in C was unreadable. All FMEA, effort, code-size and risk numbers were read in full.

---

### 3. Contradictions / changed values

| # | C value (page) | A/B value (section) | Note |
|---|---|---|---|
| X1 | Recommendation 1: "4 main SDKs (Java, Python, Node/TS, .NET)" in the first 6–9 months (p.26) | Tier 1 SDKs = Java, .NET, PHP, TypeScript, Python, Go (B R18 / A §16). Phase 1 SDKs = Java, .NET, Go, Node, Python, PHP (A §11). Phase 1 exit criterion: "A PHP worker and a .NET worker complete tasks in one process" (A §11) | C drops PHP and Go. Without PHP, the Phase 1 exit criterion cannot be met |
| X2 | Recommendation 1 MVP = PostgreSQL + BPMN + Drools DMN + Outbox + SKIP LOCKED + **Authorization** + webapps-neo UI (p.26) | Authorization port, history pipeline + outbox, and the UI cockpit/tasklist are **Phase 2** (A §11). MVP = BPMN + DMN + workers + Tier-1 SDKs + basic UI, with no authorization (B §16, B §18.2). Phase 0 exit criterion: test suites pass on Postgres + Oracle + MSSQL + MySQL (A §11) | C moves authorization and the outbox into the MVP, and restricts the MVP to PostgreSQL only |
| X3 | Recommendation 1: a team **smaller than 8–10** delivers that MVP in **6–9 months** (p.26) | Phase 0 alone is 12–18 PM. Authorization + tenancy is 12–18 PM, history/outbox 10–14, SDKs 18–30 for six (B §15.1). With 3–4 people, the full scope takes 3–6 years (B §15.1) | My inference from B's numbers: the listed MVP is roughly 70–110+ PM, against 36–54 PM available for 6 people over 6–9 months. B gives no 6–9-month MVP timeline |
| X4 | "to make this project **100% successful**" (p.26) | "Zero failure ... is never claimed as a property" (B §0.1). "Why not 100%" section: residual risks remain (B §18.2) | Contradicts B's stated rule |
| X5 | Model importers in **"Phase 1.5"**, right after Phase 1 (p.27) | Importers for Camunda 7/Operaton and Flowable models are in **Phase 4** (A §11, A §3.3). B UC6: "Should (Phase 4 in DESIGN, core import earlier)" (B §1.2) | Changes the roadmap phase. B already hints at earlier core import, but gives no phase |
| X6 | Phase 1: only PostgreSQL gates every PR; Oracle and MSSQL run daily or at milestones; per-PR from Phase 2 (p.27) | "Tier 1: PostgreSQL (reference), Oracle, SQL Server. Full CI on every PR" (A §5.6, B R17). PR pipeline runs the DB matrix on every PR (A §14.9). CI on all DBs in Phase 0 (A §11) | Contradicts the Tier-1 per-PR rule during Phase 1 |
| X7 | CMMN moved into `sangam-runtime-cmmn` in **Phase 0**; no converter or CMMN modeler work in the first 12 months (p.27) | CMMN module, "When (phase)" = **3** (B §2). CMMN hardening + isolation + converter is one work package (B §15.1). CMMN hardening is in Phase 3 (A §11) | Moves isolation earlier (Phase 0 instead of Phase 3) and explicitly defers the converter and CMMN editor |
| X8 | Step Journal Helper means non-idempotent external APIs "will also be saved from duplicate execution" (p.27) | Residual risk: external systems without idempotency (B §13 S2). Fault tree T1: "residual: external systems that ignore keys" (B §10) | C claims to close a risk that B keeps as residual. A journal cannot cover a crash after the external call and before the journal write (my note) |
| X9 | "Proposed **22**-module layout" (p.9) | A §4.1 gives no count. Its tree has 20 `sangam-*` modules plus 4 other entries (proto/openapi/asyncapi, sdks, ui, deploy), 24 in total. C's own tree has the same 24 entries | The count matches neither 20 nor 24 |
| X10 | Operaton ports: "~27k lines + **3,659 tests**" (p.6) | "~27k lines + tests" (B §2). 3,659 is the number of **test files** in all of Operaton (B §15) | C implies the 3,659 belong to the ported packages, and calls files "tests" |
| X11 | Flowable query result limit: "**Unlimited**" (p.20) | "—" (B §12 table) | B gives no Flowable value; C states one |
| X12 | Operaton `enableExpressionsInStoredQueries = true` "**has been the cause of** Camunda Security Notice 157 (RCE)" (p.20) | F12 lists "Camunda Notice 157; Operaton default `true`" as evidence side by side (B §9, B §12) | C states a direct causal link that B does not |
| X13 | S3 (business user) evidence: "Tasklist absent in Flowable OSS; Operaton's old AngularJS UI is end of life" (p.2) | S3 evidence: "Same as S2" (B §1.1) | Changed evidence text. The facts appear in A §1.1 |
| X14 | S10: pm4py has "**full** support" for OCEL 2.0 (p.2) | "pm4py supports OCEL 2.0" (B §1.1) | "Full" is not in B |
| X15 | Functionality vs market leaders: "unique combination → **clearly the best** (Better)" (p.14) | Verdict "**Better**" (B §18.1) | Stronger wording |
| X16 | Tech-stack vs market leaders: "on par with Temporal/Conductor in SDK count; **far ahead** of both bases" (p.15) | "Comparable to Temporal/Conductor in SDK count; **less mature at first**". Verdict: "Better than both bases; **on par later**" (B §18.1) | The caveat about early maturity is dropped |
| X17 | Modern UI vs market: "on par with Camunda 8 (whose UI is licensed) → Better" (p.15) | "Comparable to Camunda 8 **if delivered**" (B §18.1) | The condition "if delivered" is dropped |
| X18 | Plug & play: "**far** simpler than Camunda 8 self-managed" (p.15) | "simpler than Camunda 8 self-managed" (B §18.1) | Stronger wording |
| X19 | S5 (`agentTask` crash): result "✔ success" only (p.21) | "✔ ⚠ Residual: approval fatigue if approvals are too frequent" (B §13 S5) | Residual risk dropped |
| X20 | S8 (Kafka down 2 h): result "✔ success" only (p.21) | Also an outbox-size alert, and "⚠ Residual: business impact of delayed events" because inbound correlation stops (B §13 S8) | Residual risk and inbound-stop detail dropped |
| X21 | L4: in-memory/Testcontainers engine "in **every Tier-1 SDK**" (p.12) | "Every SDK ships an in-memory/test-container engine" (A §3.3d L4) | Narrower. Consistent with R18 (the test engine is listed for Tier 1 only), so this follows the precedence rule but differs from A §3.3d |

Checked and found **consistent**, so not listed above:
- all 24 FMEA rows (S, O, D, RPN before and after, totals 4,581 → 712, 84%)
- all 15 effort ranges and the 143–220 PM total
- all code-size numbers
- PR1–PR12 scores and owners
- success probabilities (75–85 / 45–55 / 10–20 / 60–70%)
- R5 write model (~16 transactions, ~225 row writes; 16,000 tx/s and 225,000 writes/s at 1,000 PI/s)
- targets (≥ 2,000 / ≥ 300 PI/s, p99 < 50 ms, hand-off < 100 ms) and Little's law (300 concurrent tasks)
- TCK scores, DMN 1.6/1.7 identifiers and C1–C17
- R1–R35 text and sources
- the 22-row comparison
- W1–W18 (with R-changes merged in)
- the 14-product market table values

---

### 4. Items in C that are restatements

- p.1 executive summary: covered by A Summary, B Summary, B §4 conclusion 1, B §9 totals and B §19.
- p.1 evidence tags [F]/[V]/[I]/[U]: covered by B §0.1.
- p.2 S1–S10: covered by B §1.1 (S3 and S10 wording changed, X13 and X14).
- pp.2–3 UC1–UC12 with MoSCoW: covered by B §1.2.
- pp.3–5 22-point comparison: covered by A §1.1, with winner cells updated from A §16 / B §19 (R6, R7, R17, R19 and others) and A §14.3 (testing).
- pp.5–6 W1–W18: covered by A §1.2, with R1, R3, R13, R18, R19 and R29, D4 and D11 merged in.
- pp.6–8 5W1H (16 elements): covered by B §2 (X10 is the one changed value; it omits B's "Operaton does not recognise DMN 1.6 namespaces").
- pp.9–10 module tree and upstream files: covered by A §4.1 and A §13.
  - Annotations in the tree follow R6, R7, R10, R18, R20, A §3.3b, W7 and §6 (MQTT).
  - The upstream file list leaves out `flowable-event-registry-spring/*` and `flowable-secure-javascript/*` from A §13.
- pp.10–11 gap tables: covered by B §3.1, §3.2 and R34 (new phases are N4).
- p.11 DMN gaps, 1.7 policy, CMMN: covered by B §3.3, A §3.3b, §3.3c and §3.4.
  - It leaves out "case-file-item on-parts" from the CMMN gaps in both engines.
  - It leaves out policy steps 2 and 4 of A §3.3c.
- p.12 L1–L10 and scope removal: covered by A §3.3a and §3.3d (X21 and N5 are the differences).
- pp.12–13 14-product table: covered by B §4 (additions in N6; positioning conclusions 1–5 not reproduced).
- p.14 SWOT: covered by B §5.1–5.4.
- pp.14–15 7 dimensions: covered by B §18.1 (wording changes X15–X18).
- pp.15–16 18 technical choices: covered by B §6.
- pp.16–17 C1–C17: covered by B §7.
- pp.17–18 ATAM: covered by B §8.
- pp.18–20 FMEA F1–F24: covered by B §9 (it adds tool names jqwik and Jazzer, and R-numbers in the cells, all from B §19 / A §14.3).
- p.20 fault trees T1–T4: covered by B §10.
- p.20 local code checks: covered by B §12 (X12 is the causal claim).
- pp.20–21 defaults: covered by B §12 (new column is N7).
- pp.21–22 S1–S10: covered by B §13 (X19 and X20 drop residual risks).
- p.22 capacity: covered by B §14.2–14.4.
- pp.22–23 codebase and effort: covered by B §15 and §15.1 (annotations in N8).
- pp.23–24 risks and success: covered by B §17 and §18.2 (it adds months and years from B §15.1; it leaves out the "market adoption: not estimable [U]" row).
- p.24 principles and lifecycle: covered by A §14.1, §14.2, §14.3, §14.6, §14.7 and §12.
- pp.25–26 R1–R35: covered by B §19 and A §16.

A/B content that C does **not** reproduce, so it is still only in A/B:
- A §2 core decisions, §3.1 and §3.2 standards tables, §3.3 import compatibility
- A §4 architecture diagram and §4.2–4.7
- A §5 polyglot layer details (four task modes, framework adapters), §6 event gateway, §7 security, §8 UI, §9 observability
- A §10 honest limits, §11 roadmap table, §14.4, §14.5, §14.8, §14.9, §15 study status and sources
- B §0.2, §4 positioning conclusions, §11 STRIDE table, §12 P1–P18 status table, §14.1 evidence, §16 pre-mortem, §20 sources

---

### 5. Final count

- **New items: 16** (N1–N16), plus 1 uncertain item (Flowable "UI and control commercial", included under N6).
- **Contradictions / changed values: 21** (X1–X21).

---

# Part V — Cross-Verification Register (all four sources)

> **Purpose:** check the four sources against each other and against primary evidence, and record what is confirmed, what is corrected, and what is still open.
>
> **Precedence:** where Part V **accepts** a correction, that correction overrides the earlier text in Parts I–IV. Parts I–IV are kept word for word, so nothing is lost, but the accepted correction is what counts.
>
> **Status values:**
> - **VERIFIED**: checked in this session against a primary source or the local code.
> - **ACCEPTED**: the correction is adopted on the reviewer's cited evidence and is consistent with our own data; it was not independently re-fetched.
> - **OPEN**: needs a decision or more evidence.
> - **REJECTED**: the claim is contradicted by evidence.

## V.1 Integrity checks

| # | Check | Result | Evidence |
|---|---|---|---|
| CV-1 | The independent review reviewed exactly the current DESIGN.md and ANALYSIS.md | **VERIFIED.** The review's SHA-256 fingerprints match the files: DESIGN `3005ae03…718d9`, ANALYSIS `59039de2…7dcdc` | `Get-FileHash` on 2026-11-04 |
| CV-2 | FMEA totals 4,581 → 712 (≈84%) | **VERIFIED** three times: by ANALYSIS, by the review, and by recomputing every row (S×O×D) in this session | ANALYSIS §9 |
| CV-3 | Effort total 143–220 person-months | **VERIFIED** (sum of 15 rows) by ANALYSIS and the review. The review adds that it excludes Phase 4 and ongoing operations, and cannot be turned into elapsed time just by dividing by team size. **ACCEPTED** | ANALYSIS §15.1, Review §15.3 |
| CV-4 | PDF page count and coverage | 27 pages extracted to text. Unique content and contradictions are reported in Part IV | Part IV |
| CV-5 | Line-level completeness of this merge | See V.6 (automated check) | build script |

## V.2 Review corrections to factual and comparative claims (Review §5)

| # | Earlier claim (Part I/II/IV) | Correction | Status | Our own evidence |
|---|---|---|---|---|
| X-1 | "Empty Apache-2.0 market slot (BPMN+CMMN+DMN+authorization+tenancy+UI)" tagged **[F]** | Operaton already offers the three standards, authorization, row-level tenancy and webapps under Apache 2.0, with no paid edition. The exact bundle is a **positioning hypothesis**, not a fact or a moat | **ACCEPTED** | Our own Operaton study shows authorization (22 resource types), `TENANT_ID_` tenancy, legacy webapps and webapps-neo, all Apache 2.0. The [F] tag was wrong; it is now **[I]** |
| X-2 | Operaton UI = AngularJS disadvantage | webapps-neo is a technical preview (2.2). Reusing the same UI is not a lasting advantage | **ACCEPTED** | Our study: neo is off by default (`operaton.bpm.webapp.neo.enabled`) |
| X-3 | AI agent tasks make Sangam "unique among BPMN engines" | Camunda documents native AI-agent orchestration; Operaton documents MCP integration | **ACCEPTED** (review sources S01, S04) | — |
| X-4 | Flowable reach = REST/Java only | Official external-worker clients exist for Java, TypeScript/JS, Python, .NET and Go | **VERIFIED** (Flowable docs). Whether they work with OSS: **OPEN** | documentation.flowable.com/latest/develop/external-clients |
| X-5 | Flowable lacks DMN task integration | Flowable has DMN execution (`flowable:type="dmn"`, DMN REST, decision task). The gap is different element/result semantics for `businessRuleTask` | **ACCEPTED**. Consistent with our own gap study, which found `DmnActivityBehavior` | Standards gap report |
| X-6 | "Apache 2.0 everything" | bpmn-js needs its watermark (already noted in DESIGN §8/§12). **Liquibase 5.0+ is FSL** | **VERIFIED** (Liquibase 5.0 release notes). Operaton pins Liquibase **4.33.0** (`operaton/pom.xml` l.30). **OPEN decision D-7** | — |
| X-7 | GraalJS isolates as an OSS sandbox | GraalVM: "Sandboxing is not available in GraalVM Community Edition" | **VERIFIED** (GraalVM 25 security guide). R6 must name the edition and licence or use another isolation (WASM, process isolation). **OPEN decision D-8** | — |
| X-8 | "Drools has the highest conformance" | jDMN 3391/3391 is highest; Drools 3388/3391 | **ACCEPTED**. Our own table showed jDMN 3391/3391; the "highest" wording in DESIGN §3.4 was wrong | DMN TCK page |
| X-9 | DMN 1.6 formal / 1.7 beta | Confirmed | **VERIFIED** (OMG pages, earlier in session) | — |
| X-10 | A2A 1.0.1, MCP 2026-07-28 | Confirmed | **VERIFIED** (earlier in session) | — |
| X-11 | Camunda 8 production licence | Confirmed, per component | **ACCEPTED** | Market study |
| X-12 | Camunda 8.9 RDBMS storage | Secondary/query storage only; primary execution stays Zeebe/RocksDB | **ACCEPTED**. Consistent with the market study | — |
| X-13 | n8n uses MySQL | n8n 2.0 removed MySQL/MariaDB; PostgreSQL and SQLite remain | **VERIFIED** (n8n 2.0 breaking changes). ANALYSIS §4 n8n row is corrected | — |
| X-14 | CVE-2026-91197 is verified evidence | Only a third-party source. The CVE ID is **[U]**; the unhardened parser at `ProcessDiagramLayoutFactory.java:102` is **[F]** (local code) | **ACCEPTED** | Local grep |
| X-15 | CVE-2025-0982 proves Flowable's Rhino is vulnerable | It is Google Application Integration's Rhino task. Use it as a failure **class** only | **ACCEPTED** | — |
| X-16 | CVE-2025-6514 = generic MCP/prompt-injection flaw | It is OS command injection in `mcp-remote` through an authorization-endpoint URL | **ACCEPTED** | — |
| X-17 | RPN −84% | The arithmetic is correct, but it is a projected change in *subjective* scores, not measured failure probability | **ACCEPTED** | CV-2 |
| X-18 | Success probabilities 75–85% etc. | No calibrated model behind them. Replace with **evidence gates G1–G8** (Review §18) | **ACCEPTED**. Percentages remain only as labelled subjective judgement | — |
| X-19 | ISO/IEC 25010 with 8 characteristics, no edition | The 2023 edition has 9 characteristics | **ACCEPTED**. ISO page was not reachable (HTTP 403). From prior knowledge [I], the 2023 edition adds *safety* and renames usability → interaction capability and portability → flexibility | — |
| X-20 | Market table rows comparable | Hatchet and DBOS are merged into one row, and the table mixes hosted quotas, task throughput and process throughput. Use it as research leads, not a ranking | **ACCEPTED** | — |

## V.3 Review engineering findings (E01–E21) against R1–R35

The review assesses every revision R1–R35 (Review §13). Consolidated status:

| Status | Revisions | Meaning |
|---|---|---|
| Retain as is | R2, R5, R7, R8, R11, R12 (plus a VM/Docker migration command), R15, R16, R17, R21, R22, R26, R28, R30, R32 | Adopt, with the review's added requirement |
| Retain, refine or widen | R1, R13, R20, R24, R27, R29, R31, R33 | Adopt, with the review's extra requirement |
| Incomplete | R3, R4 | Needs a full lock specification (E05) and a full outbox algorithm (E04) |
| Revise | R6 (edition/licence, X-7), R10 (strict null only for Boolean/mandatory contexts, E18), R14 (necessary but insufficient), R18 (too wide for the first pilot), R25 (clarify vs named queries), R34 (reproduce gaps on pinned code) | Rewrite before implementation |
| **Revise urgently** | **R9** (do not unlock acknowledged work when a stream closes, E06), **R19** (list/single-object ABAC equivalence), **R23** (do not hide dispatched business effects, E08), **R35** (state-update serialization ≠ business-effect exclusivity, E07) | Blocking: the original wording could cause duplicate or hidden business effects |

New requirements from the review, not covered by any earlier R item:
- E01: business-operation ID and unknown-outcome state
- E02: agent-journal crash window and call intents
- E03: **durable inbound inbox**
- E09: partition epochs and fencing
- E10: finite outbox capacity
- E12: idempotency as an API lifecycle
- E13: audit ≠ replay
- E14: version the whole executable package
- E15: release conformance profiles
- E16: no universal CMMN → BPMN conversion
- E17: modeller/runtime parity
- E19: forms as a separate contract
- E20: migration as a specialist product
- E21: limits on continue-as-new and modification

They are adopted as requirements **R36–R49** (mapping: R36=E01, R37=E02, R38=E03, R39=E09, R40=E10, R41=E12, R42=E13, R43=E14, R44=E15, R45=E16, R46=E17, R47=E19, R48=E20, R49=E21). Their acceptance tests are T01–T32 (Review §16).

## V.4 Conflicts between sources that need a decision (OPEN)

These are not merge errors: the sources genuinely recommend different things. They are listed for the "one by one research and finalisation" step.

| ID | Topic | Parts I/II (DESIGN/ANALYSIS) | Part III (Review) | Part IV (PDF) | Evidence still needed |
|---|---|---|---|---|---|
| D-1 | Base engine | Fork Flowable, port Operaton features | Keep provisional; spike product layers over stable Flowable **and** Operaton; deep fork only on gap-by-gap proof | see Part IV | Same workflow corpus on both engines (Review G2) |
| D-2 | Delivery sequence | Phases 0–4 (DESIGN §11) | Stages A–E, buyer first, vertical pilot (Review §15.2) | see Part IV | Buyer/use-case choice (G1) |
| D-3 | First market | Camunda 7 migration + ISVs + many personas | Choose one wedge: ISV embedding or regulated/government integrator (Review §6.2) | see Part IV | 8–12 buyer interviews, 2–3 design partners |
| D-4 | SDK scope at start | Tier 1 = 6 languages (R18) | Java + 1–2 pilot languages | see Part IV | Pilot systems' languages |
| D-5 | API at start | REST + gRPC streaming | REST first; gRPC when there is a proven need | see Part IV | Load and latency needs of the pilot |
| D-6 | Scripts in the first release | WASM + JS isolates | Prefer external workers; sandbox later | see Part IV | Threat model + licence (X-7) |
| D-7 | Schema migration tool licence | Liquibase as the single source | Liquibase 5+ is FSL | — | Pin 4.x (Apache 2.0, as Operaton 4.33.0 does), accept FSL, or switch tools |
| D-8 | JS sandbox | GraalJS isolates | Not in Community Edition | — | Oracle GraalVM licence vs WASM-only vs process isolation |
| D-9 | Market claim | "Empty slot" [F] | Hypothesis only (X-1) | PDF repeats "[F]" | Buyer evidence |
| D-10 | Success metric | Percentages | Evidence gates G1–G8 | PDF repeats percentages | — (X-18 accepted) |
| D-11 | Revenue / sustainability | Not covered | Offers and budget equation (Review §6.5) | see Part IV | Business model decision |
| D-12 | HA/DR targets | 99.95%, RPO 0 with a replicated DB | RPO 0 needs synchronous replication; define failure boundaries | — | Topology decision |

## V.4b PDF report: recommendations and contradictions

The full item list (16 new items N1–N16, 21 contradictions) is in Part IV. Status of the items that affect decisions:

| PDF item | Status | Reason |
|---|---|---|
| Verdict "exceptionally mature and solid" / "100% successful" | **REJECTED** | It contradicts ANALYSIS §0.1 (zero failure is never claimed) and Review §3 (the dossier is not yet a validated specification) |
| "Empty slot" and success percentages repeated as fact | **Superseded** by X-1 and X-18 | — |
| Wedge MVP in the first 6–9 months | **OPEN** (joins D-2, D-3) | The timeline does not fit ANALYSIS §15.1 effort unless the scope is cut hard. It agrees in spirit with Review Stage B |
| MVP with only 4 SDKs (PHP and Go dropped) | **OPEN** (joins D-4) | It conflicts with the user's stated PHP/.NET requirement and with the DESIGN Phase 1 exit test |
| Authorization and outbox in the MVP | **ACCEPTED in direction** | Review §15.1 also says a real pilot cannot omit them |
| Authorization benchmark in month 1 of Phase 0 | **ACCEPTED** | Matches PR2 (authorization port risk) and R19 |
| Model importer at "Phase 1.5" | **OPEN** (joins D-2) | The review also suggests migration earlier if Camunda 7 users are the first market |
| Only PostgreSQL gating PRs in Phase 1 | **OPEN** | Conflicts with R17 (Tier 1 = PG, Oracle, MSSQL); the review suggests limiting to the databases the first buyer needs |
| CMMN isolated in Phase 0; no converter for 12 months | **ACCEPTED in direction** | Consistent with E16 (no universal conversion) |
| `context.step(...)` journal helper in SDKs | **ACCEPTED as a feature**; the claim that it removes duplicate risk is **REJECTED** | E01/E02: a crash window remains |
| Public comparison benchmark at the end of Phase 1 | **ACCEPTED** | Matches R22 and Review §12.1 |
| "22 modules", "3,659 tests of ported code", "Flowable query limit unlimited", causal link to Notice 157 | **REJECTED as stated** | The module tree has 20 modules + 4 other entries; 3,659 is all Operaton test files; the Flowable limit was not checked; ANALYSIS does not make that causal link |
| Residual risks dropped from S5/S8; market caveats dropped | **REJECTED** | The ANALYSIS wording with residual risks and caveats stands |

## V.5 Corrections applied in this merge

The earlier parts stay verbatim. For quick reference, these statements in Parts I/II/IV are **superseded**:
1. "Open slot … [F]" → **[I] positioning hypothesis** (X-1).
2. "AI agent tasks … unique among BPMN engines" → not unique (X-3).
3. "Flowable … REST only / Java client only" → official multi-language clients exist (X-4).
4. "Drools … highest conformance" → jDMN highest; Drools 3388/3391 (X-8).
5. ANALYSIS §4 n8n storage "PG / MySQL / SQLite" → PostgreSQL / SQLite since 2.0 (X-13).
6. CVE-2026-91197 "[F]" → CVE ID **[U]**; parser weakness **[F]** (X-14).
7. CVE-2025-0982 / CVE-2025-6514 descriptions narrowed (X-15, X-16).
8. Success percentages → subjective only; gates G1–G8 govern (X-18).
9. ISO/IEC 25010 → the 2023 edition, 9 characteristics (X-19).
10. R9, R19, R23, R35 → must be rewritten per V.3 before any implementation.
11. GraalJS isolate sandbox → not available in Community Edition (X-7).
12. "Liquibase … Apache" → version-dependent licence (X-6).


## V.6 Completeness check (automated)

Every non-empty line of every source was searched for in this file (heading markers ignored).

| Source | Non-empty lines | Found | Missing |
|---|---|---|---|
| DESIGN.md | 618 | 618 | 0 |
| ANALYSIS.md | 648 | 648 | 0 |
| Independent Review (.md) | 561 | 561 | 0 |
| PDF unique-content report | 242 | 242 | 0 |
| Cross-verification register | 113 | 113 | 0 |
| PDF raw text | 2898 | 2898 | 0 |

**Result:** all lines present.


---

# Part VI — Critical Specifications and Decision Records (2026-11-04)

> **Precedence:** Part VI **replaces** the text of R3, R4, R9, R19, R23 and R35 everywhere in Parts I–V. It also records decisions D-1, D-2, D-3, D-7 and D-8.
>
> **Evidence tags** follow Part II §0.1.
>
> **SQL:** written for PostgreSQL. The dialect differences are listed in VI.1.6.
>
> **Status:** these are specifications. Nothing here is implemented, and nothing is considered done until its acceptance tests (Part III §16, T01–T32, plus the new T33–T40 below) pass.

## VI.1 R3 — Lock and transaction specification (replaces R3)

### VI.1.1 Lock domains and the single global order

| Order | Lock domain | How it is taken | Held by |
|---|---|---|---|
| L1 | **Business-resource lease** (only for `exclusive="resource:…"`, VI.6) | Lease row, taken in the acquisition transaction | Acquisition transaction, briefly |
| L2 | **Root instance row**, one or more | `SELECT id FROM RT_ROOT WHERE id = ANY(:roots) ORDER BY id FOR UPDATE` | Every command that changes the state of an instance tree |
| L3 | Executions, activity instances, event subscriptions, user tasks and variables of that root | **Not locked separately**: protected by L2, plus `REV_` optimistic versions as a second guard | — |
| L4 | Job and external-task rows of that root | Row update by id with a version check | Execution and completion transactions |
| L5 | Outbox, inbox and audit inserts | Inserts only, no locks taken | Same transaction |

Rules:
1. **Total order.** A transaction takes locks only in the order L1 → L2 → L4. When it needs several roots, it locks them in ascending `id` order. Because every transaction follows the same order, no lock cycle can form, so there are no deadlocks between engine transactions (proof sketch: a deadlock needs a cycle in the wait-for graph, and a total order rules out cycles). Waits caused by outside SQL, such as DBA scripts, are bounded by `lock_timeout`.
2. **The root set is resolved first.** Before taking locks, a command looks up the root ids it will touch, read-only. A child started by a call activity shares its parent's root id (the Flowable `rootProcessInstanceId` model), so one L2 lock covers the whole tree. If a command finds that it needs a root outside its locked set, it aborts and retries with the bigger set.
3. **One root per transaction by default.** Batch operations (migration, modification, bulk cancel) handle one root per transaction. Message correlation to several instances creates one transaction per instance. "Correlate all" locks at most `maxRootsPerCommand` roots (default 20) in ascending order, and splits larger sets into a batch.
4. **Acquisition never takes L2.** Job and external-task acquisition is its own short transaction: it marks rows owned with `FOR UPDATE SKIP LOCKED` and commits. Execution happens in a new transaction that takes **L2 first**, then re-reads the job by id and checks `lock_version` (VI.3).
5. **No remote work while L2 is held.** A `LockScope` guard throws `IllegalRemoteCallInLockScope` if connector, HTTP, LLM, broker or user code is called inside a locked transaction. Side effects are recorded as outbox rows or external tasks and carried out after commit.
6. **Bounded transactions.** Defaults are `lock_timeout = 2 s`, `statement_timeout = 5 s` and a 10 s watchdog per command. All are configurable per deployment.
7. **Retries.** Lock timeouts, deadlock victims, optimistic-lock errors and serialization failures are classified as retryable (VI.1.6). A command is retried up to 5 times with exponential back-off and full jitter (base 20 ms, cap 1 s). After that:
   - a job gets its retry handling (incident when retries run out);
   - an API call returns `409 CONFLICT_RETRY_EXHAUSTED`.

   Retries are safe because a command only changes database state; all external effects go through the outbox or external tasks.
8. **Hot roots.** A metric, `root_lock_wait_seconds`, is kept per process definition (not per instance, to keep metric cardinality low). The modelling guide says to split very busy roots, for example by using message-started child processes. It also documents a hard limit: one root is serialized, so it is not designed for more than about 50 state-changing commands per second.

### VI.1.2 Acceptance

- **T10** (from Part III §16).
- **T33:** 1,000 concurrent correlations and completions on one root, mixed with timers, on every Tier-1 DB. Pass when there are zero deadlocks and zero lost updates, and every command either commits or returns `409` after its retries.
- **T34:** chaos run with random `lock_timeout` injection. Pass when no command leaves partial state.

### VI.1.6 Dialects and error classification

| DB | L2 statement | Timeouts | Retryable codes |
|---|---|---|---|
| PostgreSQL | `FOR UPDATE` | `SET LOCAL lock_timeout`, `statement_timeout` | 40001, 40P01, 55P03 |
| Oracle | `FOR UPDATE WAIT 2` | resource-manager or JDBC query timeout | ORA-00060, ORA-30006, ORA-08177 |
| SQL Server | `WITH (UPDLOCK, ROWLOCK)` on an index seek; RCSI required (start-up check) | `SET LOCK_TIMEOUT 2000` | 1205, 1222, 3960 |
| MySQL / MariaDB (Tier 2) | `FOR UPDATE` | `innodb_lock_wait_timeout` | 1213, 1205 |

## VI.2 R4 — Outbox and inbox algorithm (replaces R4; also adds R38 inbox)

### VI.2.1 Tables

```
OUTBOX(id BIGINT PK, tenant_id, root_id, root_seq BIGINT, event_type, event_id UUID UNIQUE,
       payload JSONB, created_at, PARTITION BY RANGE(created_at))
OUTBOX_DELIVERY(outbox_id, destination, root_id, root_seq,
       state  -- PENDING | CLAIMED | DONE | PARKED | SKIPPED
       , attempts, next_attempt_at, claim_owner, claim_epoch BIGINT, claim_expires_at, last_error,
       PRIMARY KEY(outbox_id, destination))
RT_ROOT(id, ..., next_outbox_seq BIGINT)   -- incremented under the L2 lock
```

### VI.2.2 Write path

The write path runs inside the command transaction, which holds L2:
1. `root_seq = RT_ROOT.next_outbox_seq++`. This is safe because L2 serializes all transactions on that root, so **`root_seq` order equals commit order per root**. That removes the late-commit gap described in Part III E04 without any global watermark.
2. Insert one `OUTBOX` row, plus one `OUTBOX_DELIVERY` row per subscribed destination (history projector, event channels, webhooks, wake-up). Each delivery starts in state `PENDING`.

### VI.2.3 Relay

The relay can run on any number of nodes.
1. **Claim.** In one short transaction, pick deliveries that are due and are the head of their (root, destination) line:

   ```sql
   UPDATE OUTBOX_DELIVERY d SET state='CLAIMED', claim_owner=:node,
          claim_epoch=claim_epoch+1, claim_expires_at=now()+:leaseTtl
   WHERE (outbox_id, destination) IN (
     SELECT outbox_id, destination FROM OUTBOX_DELIVERY x
     WHERE x.state='PENDING' AND x.next_attempt_at<=now()
       AND NOT EXISTS (SELECT 1 FROM OUTBOX_DELIVERY y
                       WHERE y.root_id=x.root_id AND y.destination=x.destination
                         AND y.root_seq<x.root_seq AND y.state IN ('PENDING','CLAIMED','PARKED'))
     ORDER BY x.next_attempt_at LIMIT :batch FOR UPDATE SKIP LOCKED)
   RETURNING ...;
   ```

   The scan is based on state, not on an id watermark, so a row that commits late is simply picked up on a later pass.
2. **Publish** outside any database transaction. The CloudEvents `id` is `event_id`.
3. **Mark.**

   ```sql
   UPDATE … SET state='DONE' WHERE outbox_id=? AND destination=? AND claim_epoch=:myEpoch AND state='CLAIMED'
   ```

   If zero rows change, this node lost its claim (fencing). The publish may be duplicated, which is allowed.
4. **Crash recovery.** A sweeper resets `CLAIMED` rows whose `claim_expires_at < now()` back to `PENDING`. The event will be published again, so **consumers must dedup on `event_id`**. Sangam's own consumers (history projector, wake-up) are idempotent by construction (upsert by `event_id`).
5. **Failures.** Failed publishes back off exponentially. After `maxAttempts` (default 10) the delivery goes to `PARKED`. What happens next depends on the destination's policy:
   - `ordered=true` (default for the history projector and ordered channels): the parked row **blocks** later events of that root for that destination. An alert fires, and the repair API (`requeue`, or `skip` with a reason) is audited.
   - `ordered=false` (webhooks): the row becomes `SKIPPED` with a gap record, and later events continue.
6. **Wake-ups.** The relay sends one batched `NOTIFY` per claim cycle, carrying no payload. Pollers fall back to a 1 s poll, which stays authoritative.

### VI.2.4 Capacity (R40 / E10)

- Configured budget: `outboxMaxBytes`, which defaults to 20% of the planned database storage.
- At **50%**: alert.
- At **80%**: new process starts and inbound events get `503 BACKPRESSURE`. Continuations of running instances still commit.
- At **95%**: only completions and cancellations are accepted.
- The drain rate is measured and shown. The operations guide sizes the budget from: event rate × average payload × allowed outage hours × 1.5.

### VI.2.5 Retention

When every delivery of a partition is `DONE` or `SKIPPED`, the daily partition is dropped. A partition that still holds open rows is kept, and an alert fires.

### VI.2.6 Inbox (R38 / E03)

```
INBOX(tenant_id, source, message_id, received_at, state, correlation_key, payload, PRIMARY KEY(tenant_id, source, message_id))
```

1. **Receive and commit.** The consumer inserts the message, then commits, then **acks the broker**. A duplicate key means the message is a redelivery: ack it and drop it.
2. **Process.** Processing claims inbox rows the same way the relay claims deliveries (SKIP LOCKED, epoch).
3. **No matching subscription yet.** The message is kept `BUFFERED` until a subscription appears or its TTL runs out. The TTL is set per event definition (default 24 h). On expiry the message goes to `EXPIRED`, with an audit entry and an optional dead-letter channel. The policy per event definition is `buffer`, `reject` or `start-new`.
4. **Duplicate start events.** These are prevented by a unique constraint on (tenant, definition key, business key) when `uniqueStart=true`.
5. **Tenant.** The tenant comes from the channel binding and the authenticated source, **never from the payload alone**.

### VI.2.7 Acceptance

- **T06, T07, T08, T09, T25** (from Part III §16).
- **T35:** restore the database to an older backup while the broker still holds newer messages. The inbox dedup plus business-operation IDs must prevent duplicate business starts.

## VI.3 R9 — Worker task lease lifecycle (replaces R9; also adds R36 operation identity)

### VI.3.1 States

```
AVAILABLE --offer(stream)--> OFFERED --ack--> LOCKED --heartbeat--> LOCKED
OFFERED --offer timeout (10 s) or stream closed--> AVAILABLE           (never acked: safe to re-offer)
LOCKED --complete(lock_version)--> COMPLETED
LOCKED --fail(retries>0)--> AVAILABLE (after back-off, lock_version+1)
LOCKED --fail(retries=0)--> INCIDENT
LOCKED --bpmnError--> COMPLETED_WITH_ERROR (routed by the model)
LOCKED --outcomeUnknown--> UNKNOWN_OUTCOME --resolve--> COMPLETED | AVAILABLE(same op_id) | FAILED
LOCKED --lease expired--> AVAILABLE (lock_version+1, attempt+1)       (stream state is ignored)
LOCKED --cancel requested--> CANCEL_REQUESTED --worker ack--> CANCELLED
CANCEL_REQUESTED --lease expired without ack--> UNKNOWN_OUTCOME (reconciliation item)
LOCKED --release(lock_version)--> AVAILABLE                            (explicit unacquire by worker)
```

### VI.3.2 Rules

1. **A closed stream releases only `OFFERED` tasks.** A `LOCKED` (acknowledged) task keeps its lease until it expires, the worker releases it, or the worker completes it. This fixes the concurrent-owner risk in Part III E06.
2. **Fencing.** Every worker call carries `(taskId, lockVersion, attemptId)`. A call with a stale `lockVersion` gets `409 STALE_LEASE` and changes nothing.
3. **Leases.** The lease length is set per topic (default 5 min, maximum 24 h). Workers heartbeat at lease/3. The heartbeat response carries `cancelRequested`.
4. **Business-operation identity (R36 / E01).** `operationId = UUIDv5(tenant, rootId, activityInstanceId, loopCounter, retrySafeKey)`.
   - It stays **stable across retries, redelivery, lease expiry, migration and restore**.
   - It is passed to the worker as its idempotency key, and the SDKs forward it to downstream calls by default.
   - The engine keeps `(operationId, requestFingerprint, outcome)` in `OPERATION_LEDGER` for at least as long as instance history is retained.
   - A worker that cannot know whether a downstream call succeeded reports `outcomeUnknown`. That opens a **reconciliation item** with three actions: *confirm success with result*, *retry with the same operationId*, or *mark failed*.
5. **No blind automatic retries for non-idempotent effects.** A topic marked `effect=irreversible` goes to `UNKNOWN_OUTCOME` instead of being retried when its lease expires.

### VI.3.3 Acceptance

- **T02, T03, T04** (from Part III §16).
- **T36:** cut the network for 2× lease length while the worker keeps running. Pass when there is exactly one completion and the second attempt reuses the same `operationId`.

## VI.4 R19 — Authorization: one policy for lists and objects (replaces R19)

### VI.4.1 Rules

1. **One policy, two compiled forms.** Each policy compiles to:
   - an **object check** `allow(principal, action, resource)`;
   - a **SQL predicate** `visible(principal, action)`.

   A policy can be attached to a list-visible action (`READ`, `READ_TASK`, `READ_HISTORY`, `READ_VARIABLE`) only if it compiles to SQL. That means it may only use attributes that are stored as columns. Deploying a list-visible policy that cannot compile to SQL is rejected.
2. **Effective-grant table** (denormalised, maintained in the same transaction as grant and membership changes):

   ```
   AUTH_EFFECTIVE(tenant_id, principal_id, resource_type, resource_id /* or '*' */,
                  perms BIGINT, condition_id NULL, policy_version BIGINT)
   ```

   - **List predicate:** `EXISTS (SELECT 1 FROM AUTH_EFFECTIVE g WHERE g.tenant_id=r.tenant_id AND g.principal_id = ANY(:principals) AND g.resource_type=:t AND (g.resource_id=r.id OR g.resource_id='*') AND (g.perms & :p)<>0 AND (g.condition_id IS NULL OR <compiled condition on r>))`.
   - `:principals` = the user plus their groups. Above 200 groups, the query joins a materialised membership table instead.
3. **One path for every read.** List, count, search, export, dashboard and get-by-id all go through `AuthorizedQueryBuilder`. An ArchUnit rule forbids mapper calls for protected resource types from any other path. A **property test** checks that, for random policies and data, `list(principal) == { r | allow(principal, READ, r) }`, and the same for counts.
4. **Field-level access.** Variable and field masks per permission (`READ_VARIABLE`, `READ_SENSITIVE`) are applied in the projection layer for lists and objects alike.
5. **Revocation.**
   - Every grant or membership change increments `policy_version`.
   - Read caches are keyed by `(principal, policy_version)`, with a maximum staleness of 5 s (configurable; 0 means no cache).
   - **Sensitive actions** (complete or approve a task, modify, migrate, cancel, change a grant) **re-check the object policy inside the command transaction**, without any cache.
   - An approval is bound to the task's payload hash and version (R43 / E14), so changing the amount or beneficiary invalidates it.
6. **Tenant binding.** The tenant comes from the authenticated identity or channel binding. A tenant id supplied in a request is only a filter inside the allowed set, never a grant.
7. **Background work.** Jobs run as the system principal and record `onBehalfOf`. Workers see only the variables granted to their topic.
8. **History.** The same resource types apply to history (`HISTORIC_*`). History rows carry denormalised `tenant_id`, `definition_key` and owner columns so the same predicates work there.

### VI.4.2 Acceptance

- **T13, T14, T15, T16, T17** (from Part III §16).
- **T37:** list/count/export equivalence property test on every Tier-1 DB.
- **T38:** performance with 10k tasks × 300 groups (R19 benchmark), p95 list query < 200 ms.

## VI.5 R23 — Cancel, delete and erase never hide external effects (replaces R23)

### VI.5.1 Three different operations

| Operation | Who | Effect on runtime | Effect on external work |
|---|---|---|---|
| **Business cancel** | Permitted user/API, with a reason | Runs the model's cancel and compensation paths | Open external tasks → `CANCEL_REQUESTED` |
| **Technical delete** | Administrator, with a reason, audited | Removes runtime rows after the steps below | Same as cancel, plus tombstones |
| **Erasure** (privacy) | Data-protection role | Scrubs the payload in runtime, history, outbox, inbox and object store; keeps the audit metadata and legal holds | Never deletes an open reconciliation item |

### VI.5.2 Rules

1. Before runtime rows are removed, every task in `OFFERED` or `LOCKED` state goes to `CANCEL_REQUESTED`. A **tombstone** row is written:

   ```
   TOMBSTONE(task_id, operation_id, root_id, tenant_id, lock_version, state, created_at, expires_at)
   ```

   `expires_at` = lease end + the reconciliation retention period (default 30 days).
2. **Internal jobs** of the deleted instance (async continuations, timers) are dropped quietly: no retries, no incident. This is the part of the old R23 that stays.
3. **A late worker result** (complete, fail, outcomeUnknown) that matches a tombstone:
   - gets `410 GONE_INSTANCE_DELETED` back;
   - is written to the audit log;
   - opens a reconciliation item `LATE_RESULT_AFTER_DELETE` when the result says an effect happened.

   The instance is never brought back to life.
4. **A lease that expires** on a tombstoned task, with no acknowledgement of the cancel, becomes `UNKNOWN_OUTCOME` in the reconciliation workbench.
5. **Audit.** An `INSTANCE_DELETED` / `INSTANCE_CANCELLED` audit event records the actor, the reason and the list of open operation IDs. The audit stays visible after the instance has left the UI.

### VI.5.3 Acceptance

- **T11** (from Part III §16).
- **T39:** delete an instance while a payment worker is mid-call and then completes. Pass when the late result is audited, a reconciliation item exists, and the instance stays deleted.

## VI.6 R35 — Exclusivity contract (replaces R35)

### VI.6.1 Modes

`sangam:exclusive` can be set on an activity or a topic.

| Mode | Guarantee | Mechanism |
|---|---|---|
| `state` (default) | Engine state updates for one root are serialized | L2 root lock (VI.1); external effects **may overlap** |
| `execution` | At most **one locked task per (root, exclusiveGroup)** at a time; external effects never overlap within the group | `EXCLUSIVE_LEASE(tenant_id, scope_key, holder_task_id, lock_version, expires_at, PK(tenant_id, scope_key))` with `scope_key = root_id + ':' + group`. A lease row is inserted in the **acquisition** transaction; a candidate is skipped if a live lease exists. The lease is released on complete, fail, release or expiry, and lease expiry follows VI.3 |
| `resource:<FEEL>` | At most one locked task per **business resource** (e.g. an account number) across all instances of the tenant | Same table, with `scope_key = 'res:' + evaluated key`. The lease's `lock_version` is passed to the worker as `resourceFence`, for downstream systems that support fencing |

Rules:
- Acquisition never locks the instance row, so exclusive external tasks of one topic no longer block each other (fixes Flowable #3509). Order within a scope is FIFO by creation time.
- Completion still takes L2 (VI.1), so engine state stays consistent in every mode.

### VI.6.2 Acceptance

- **T05** (from Part III §16): the second worker must not *start* while the first is running in `execution` mode.
- **T40:** the same check for the `resource:` mode across two instances.

## VI.7 Decision records

| ID | Decision | Status | Evidence | Revisit when |
|---|---|---|---|---|
| **D-7** Schema migration tool | **Pin Liquibase 4.33.0** (Apache 2.0, the version Operaton uses). Write changelogs as **per-dialect "formatted SQL"** files so that moving to Flyway OSS (Apache 2.0) is a mechanical change. Do not adopt Liquibase 5.x (FSL) until a legal review confirms that FSL's "competing use" limit does not affect Sangam's distribution | **DECIDED** (technical). Legal review is an open action | 4.33.0 is the last 4.x release, and 5.0–5.0.4 are FSL **[F]** (GitHub releases, 5.0 notes); Flyway OSS is Apache 2.0 **[F]**; Operaton pins 4.33.0 **[F]**. No 4.x release after 5.0 was seen, so 4.x security fixes are not assured **[I]** | When a security issue hits 4.33.0, or ~Sept 2027, when FSL code converts to Apache/MIT two years after release **[F, release notes]** |
| **D-8** Script sandbox | **Release 1: no in-engine scripts.** The engine evaluates FEEL only. Script tasks run in an **out-of-process script worker** (container with seccomp, no network unless allowed, cgroup CPU/memory/time limits) for JavaScript and Python, using the normal worker lease protocol (VI.3). In-process WASM (Chicory, Apache 2.0) is a later option, once fuel/interrupt limits are verified. **GraalJS is not used**, because Community Edition has no sandbox and Oracle GraalVM would add a licence obligation to the distribution | **DECIDED** | GraalVM: "Sandboxing is not available in GraalVM Community Edition" **[F]**; Chicory Apache 2.0 **[F]**, execution limits not confirmed **[U]**; the review recommends external workers first (Part III §7.2) | When a buyer needs in-engine scripts, or Chicory/Endive documents execution limits |
| **D-1** Base engine | **PROVISIONAL, with a fixed procedure.** A spike of about 4–6 weeks runs the same corpus on (a) stable Flowable 8.x and (b) stable Operaton 2.x, each behind the same thin product API. The corpus has three workflows: an amount-routed approval with delegation; an external payment with the failure injections T02/T03/T11; and an event/case scenario. Score each candidate on: corpus coverage without engine patches (30%), number and size of required engine patches (25%), failure-test pass rate (20%), R5 benchmark on PostgreSQL (10%), fit with the first market from D-3 (15%). **Rule:** choose the candidate with the higher score. A deep fork is allowed only for a *Must* gap that cannot be built through extension points, and each such gap is recorded in a patch ledger | **PROVISIONAL** (depends on D-3) | The review's §7.1 options table; our own studies show Flowable is stronger for case/events and Operaton for Camunda 7 continuity, authorization and migration **[F]** | After the spike |
| **D-2** Delivery sequence | **Recommended:** replace the Phase 0–4 timeline with the review's **Stages A–E**: product/baseline validation → full vertical pilot → production hardening → expansion → advanced scope. Phases 0–4 stay as the long-term feature list. Every stage exit needs its gate (G1–G8) | **DECIDED** 2026-11-04 by the project owner. Full record, stage gates and phase mapping in VIII.9 | Review §15.2; PDF recommendation 1 (wedge MVP) agrees in direction | When a stage gate fails |
| **D-3** First market | **Government / regulated-system integrator**: an approval platform with department routing, delegation, audit, on-premise deployment and enterprise databases. Consequences:<br>(1) The D-1 spike corpus uses the IFMIS-style acceptance scenario from Part III §11.3.<br>(2) The Tier-1 databases stay PostgreSQL + Oracle + SQL Server (R17); the PDF's "PostgreSQL only" option is rejected.<br>(3) Pilot priorities: authorization and segregation of duties, delegation, audit, Hindi/English UI, on-premise operations.<br>(4) SDK languages are taken from the pilot's systems (D-4). | **DECIDED** by the project owner, 2026-11-04 | Review §6.2 and §11.3 | When the first design partner is signed (G1) |

---

# Part VII — Master Reassessment (verbatim)

> Source: `SANGAM_Master_Reassessment_HI.md` (SHA-256 `29a86486dd58c405d6e06fbe3c183bdf74cbb3b1ab372f5a77671791ded90c24`). It reviewed the 5,742-line master, before Part VI existed. Text is verbatim; only the headings are demoted one level.

## SANGAM.md — Consolidated Master की स्वतंत्र पुनःसमीक्षा

**तारीख:** 4 नवम्बर 2026  
**Reviewed source:** SANGAM.md, 5,742 lines, 51,919 whitespace-separated words.  
**निर्णय:** सीमित architecture experiments और एक सुरक्षित, पूरा business-flow prototype आगे बढ़ाना उचित है। पूरे platform की delivery, production readiness या deep fork को अभी validated मानना उचित नहीं है।

### 1. मेरा स्पष्ट निष्कर्ष

Sangam का उद्देश्य उपयोगी है: human approvals, business rules, external services, audit और operational recovery को applications में दोबारा इस्तेमाल करने योग्य बनाना। Single execution core, protocol-first workers, relational state, modular monolith, transactional outbox और versioned contracts अच्छे आधार हैं।

नए SANGAM.md ने पिछली समीक्षा की कई महत्वपूर्ण corrections स्वीकार की हैं। इस कारण इसे केवल पुराने flawed design की तरह पढ़ना गलत होगा। लेकिन consolidation ने अभी conflicting proposals को एक final implementation specification में नहीं बदला है। सबसे सटीक स्थिति है: **research और review archive व्यवस्थित है; कुछ architecture contracts, release scope और delivery evidence अभी अधूरे हैं।**

तीन अलग प्रश्नों को अलग रखिए:

| प्रश्न | इस सामग्री से क्या निष्कर्ष निकलता है |
|---|---|
| क्या समस्या और product direction उपयोगी हैं? | हाँ, plausible हैं; buyer validation बाकी है। |
| क्या सही engineering concerns पहचान लिए गए हैं? | काफी व्यापक coverage है; कई corrections अब स्वीकार हैं। |
| क्या implementation सुरक्षित, performant और production-ready सिद्ध है? | नहीं; source checkout, running application, builds और test/benchmark evidence उपलब्ध नहीं हैं। |

मेरी प्राथमिकता: **पहले एक ग्राहक का पूरा workflow, permissions और failure recovery सहित सिद्ध करें; उसी evidence से engine changes, SDKs, databases और विस्तार का निर्णय लें।**

### 2. मैंने क्या सत्यापित किया, और क्या उपलब्ध नहीं है

#### 2.1 Content integrity

Parts I–III को उपलब्ध मूल DESIGN.md, ANALYSIS.md और पिछली independent review से programmatically मिलाया। Heading level, blank lines और horizontal separators को normalize करने के बाद तीनों का substantive content उसी क्रम में लगातार मौजूद है। यह केवल हर line कहीं मिल जाने की जाँच से मजबूत है। मूल तीन files के SHA-256 भी master में लिखे hashes से मिलते हैं।

| Input | SHA-256 |
|---|---|
| SANGAM.md | `53bc7186b68cd351043c6a2cb9096388496a40023da604bea1f7e0d56bde71af` |
| DESIGN.md | `3005ae03d3ffa33a0cb9f3c1d98c3c4f036d06b5a28c8efbeac15c3f338718d9` |
| ANALYSIS.md | `59039de2722e95bf215f93bf72a3f65adce3b0a41b645293917409b40cf7dcdc` |
| Previous independent review | `68e0f199eda3c915e216223a76176893e58471da7febe2cbd35cfadac5ef1df5` |

Parts IV–V, their new recommendations and difference registers, and the PDF extraction were considered alongside the earlier detailed review. Appendix A has page markers 1–27. **The actual PDF is not attached**, so its hash, original pagination, table-cell alignment and extraction fidelity cannot independently be certified from this attachment. A printed PDF hash is an assertion until compared with the actual bytes.

V.6's line-presence check demonstrates a useful merge property. It does not establish semantic correctness, independence of evidence, preserved table relationships, implementation completeness or the absence of all possible omissions. No review can turn that check into a guarantee that every possible defect has been found.

#### 2.2 Arithmetic and requirement coverage

- Recomputed all 24 FMEA rows: each S×O×D product matches; totals are 4,581 and 712. The projected score reduction is 84.46%, **not measured real-world risk reduction**.
- Recomputed all 15 effort ranges: 143–220 person-months is the correct sum. Its scope exclusions and uncertainty remain.
- All R1–R35 occur in the Part V classification; none is omitted there.
- Part V adds R36–R49, lists 12 open decisions, and retains the earlier T01–T32 tests and G1–G8 gates through Part III.
- Part IV records 16 new PDF items and 21 differences. Some are genuine conflicting commitments; others are wording, framing, timing or added scope. They should not all be interpreted as defects of equal severity.

#### 2.3 Evidence boundaries

No Sangam application code, reproducible upstream checkout, executable simulation, CI output, live database, benchmark raw data or original PDF was supplied. “Verified in this session” and “local grep” inside the master are statements made by that document's author. They are not independent source-code verification performed in this reassessment.

This report evaluates documents, engineering implications and selected current primary-source claims. It does not assert that proposed controls are implemented. The source documents have not been edited.

### 3. What improved in this master

| Improvement | Why it matters | Remaining work |
|---|---|---|
| Prior content preserved with hashes and precedence | Decisions and critiques can be traced | Put the approved current specification before the archive |
| 20 factual/comparative corrections registered | Reduces unsupported uniqueness, license and security claims | Preserve exact version, source and verification status per claim |
| R1–R35 classified | Unsafe revisions are explicitly identified | Rewrite the actual normative requirements |
| R36–R49 adopted | Inbox, business idempotency, versioning and migration now have requirement IDs | Add contracts, owners, dependencies and test mappings |
| 12 decisions exposed | Conflicts are visible rather than silently resolved | Close decisions with evidence; distinguish policy from validation |
| “100% successful” and journal duplicate guarantees rejected | Removes serious overclaims | Make the corrected wording unavoidable in implementation views |
| Auth and outbox moved toward the initial usable product | A real pilot needs them | Align roadmap, cost and release scope |

These are meaningful document improvements. They are not yet evidence that engineering or market risks have been eliminated.

### 4. Remaining problems in the consolidation itself

| ID | Finding and location | Required action |
|---|---|---|
| M01 | Parts I–IV keep old behavior while Part V overrides it. An implementer must mentally assemble the current spec. | Add a concise **Current Implementation Baseline** with one authoritative statement per requirement and links to history. |
| M02 | R9, R19, R23 and R35 are identified as urgent, but their replacement contracts are not written in full. | Resolve them before implementing those subsystems. Add state transitions, authority, failure outcomes and tests. |
| M03 | “Retain as is” also means “with the review's added requirement.” These are different dispositions. | Use “retain with amendments” where an additional obligation exists. |
| M04 | VERIFIED, ACCEPTED, OPEN and REJECTED mix evidence quality, decision state and delivery state. Additional variants appear without definitions. | Track three fields: evidence; decision; implementation/validation. ACCEPTED must never imply tested. |
| M05 | Intro precedence mentions ACCEPTED/VERIFIED, while important PDF statements are marked REJECTED or Superseded. | Make all final dispositions authoritative, including explicit rejection and supersession. Specify the replacement text. |
| M06 | D-9 and D-10 remain open although X-1 and X-18 already adopt the relevant policy. | Close “market claim is a hypothesis” and “use evidence gates” as policy decisions. Keep buyer validation and gate completion open separately. |
| M07 | Unverified Flowable query limits and unsupported causal claims are grouped under “REJECTED,” defined as contradicted by evidence. | Use UNVERIFIED/UNSUPPORTED when the evidence is absent; reserve FALSE/CONTRADICTED for a demonstrated contradiction. |
| M08 | Multiple documents repeat the same inputs. Rechecking arithmetic three times is not three independent risk assessments. | Count independent evidence sources, implementations and experiments, not repetitions of conclusions. |
| M09 | New requirements and `context.step(...)` expand scope without a corresponding work-package/ownership update. | Re-estimate after defining their semantics; avoid adding them invisibly to the old 143–220 PM total. |
| M10 | PDF authorization spike assumes a Flowable port, while D-1 still asks whether that is the right base. | Test the same permission contract on candidate bases before committing to a large port. |
| M11 | Part IV challenges 6–9 months using an inferred 70–110+ PM MVP. That inference is not a decomposed estimate either. | Treat both figures as unvalidated. Fix MVP scope and estimate it directly; do not compare a reduced release with the whole platform. |
| M12 | Reused IDs have different meanings: X1 vs X-1, R1/R5 workflows vs R revisions, D design principles vs D decisions. | Introduce stable namespaces such as CLAIM-01, PDF-DIFF-01, REQ-01, WF-01 and ADR-01. |

The original three sources' ordered preservation is verified. The most important next document improvement is therefore **decision clarity**, not another concatenation or more expert-role labels.

### 5. Market judgment after rechecking primary sources

Selected official sources were re-opened on 4 November 2026. Product documentation describes the vendor/project's published offering; it is not our benchmark or proof of feature parity.

| Competitor evidence | Implication for Sangam |
|---|---|
| Operaton describes Apache 2.0 availability, a Camunda 7 transition path, support providers and an MCP server. [S1] | Free licensing, migration demand and MCP cannot alone establish an unoccupied market position. |
| Operaton 2.2 documents a Preact webapps-neo technical preview, disabled by default; its SKIP LOCKED work is also preview and not an automatic performance gain. [S2] | Compare supported releases and measured behavior. Reusing the same UI is useful engineering reuse, not durable differentiation. |
| Flowable lists Java, TypeScript/JavaScript, Python, .NET and Go worker clients in its enterprise documentation. [S3] | “Java/REST only” is too broad. Exact OSS/version compatibility still needs a test. |
| Camunda documents native and external AI-agent orchestration. [S4] | “AI agent tasks exist” is not a unique selling point. |

#### 5.1 Which first market is coherent?

| First buyer | What must be demonstrated | Engine-selection consequence |
|---|---|---|
| Camunda 7 migration team | Model, extension, API and operational compatibility; migration assessment; low transition risk | Operaton deserves a serious baseline comparison; a Flowable-based semantic port must earn its additional migration burden. |
| ISV embedding approvals and rules | Easy integration, tenant isolation, usable tasklist/forms, predictable maintenance and license terms | Product-layer value over a stable engine may matter more than rewriting the execution core. |
| Regulated/public-sector integrator | Required databases, delegation and maker-checker controls, audit, recovery, on-premise deployment and support accountability | Do not remove Oracle/SQL Server or required languages merely to simplify a generic MVP. Validate the actual buyer constraints. |

These are alternative entry strategies, not established customer facts. The document does not provide verified buyer interviews, purchase commitments or a validated market-size calculation.

My default recommendation is an **approval-and-rules platform for one identifiable ISV/integrator workflow** unless real Camunda 7 migration prospects are already available. If they are, compatibility becomes the lead product requirement and the candidate-engine ranking may change.

#### 5.2 What would make the product worth adopting?

Measure time to the first working workflow, effort to migrate representative models, recovery effort after incidents, permission correctness, upgrade/restore effort, human-task usability and total support cost. Compare against the buyer's actual alternative, including continuing with an existing engine plus a thin application layer.

Potential commercial offers are implementation/migration assistance, supported distributions, operational support and maintained domain packages. These are proposed offers, not validated demand. An Apache license does not fund maintenance by itself. A plan needs funded staffing, support obligations, customer concentration assumptions and a maintenance budget.

A useful economic check is: annual recurring support revenue minus recurring delivery/support costs must contribute enough to cover core maintenance and security/release work. Do not insert revenue, conversion or success percentages without evidence.

### 6. Six engineering blockers that need concrete contracts

#### 6.1 Worker disconnect and lease ownership — R9

A broken stream can occur while the worker continues executing. Releasing an acknowledged job immediately can start a second worker against the same business action.

The contract must distinguish an offer not yet acknowledged from acknowledged/running work. Define ACK deadline, lease expiry using authoritative time, heartbeat rules, reconnect/resume behavior, ownership/fencing checks and cancellation. Transport disconnection alone is not evidence that execution stopped. Reclaiming even an unacknowledged offer needs a rule that a worker cannot execute before a valid ACK/lease handshake. T03 and T04 are minimum tests.

#### 6.2 Business effects and the journal crash window — R20, R36, R37

A worker can charge a card, then crash before recording success. The engine sees no result. Retrying can charge twice; assuming success can hide a failure. A local journal cannot by itself atomically commit with an arbitrary remote API.

Separate identities: a business-operation ID remains stable across retries of the same intended action; an attempt/lease ID changes for execution ownership; a new legitimate business action gets a new operation ID. Specify provider idempotency, result lookup, retention and reconciliation.

| Integration capability | Recovery behavior |
|---|---|
| Idempotency key plus stable outcome lookup | Retry/query with the same operation identity according to the provider's retention and semantics |
| Outcome lookup but no idempotent execution | Query before any controlled retry; handle races and ambiguous results explicitly |
| Neither reliable idempotency nor outcome lookup | Record UNKNOWN_OUTCOME; require reconciliation or a documented business decision before repeating the effect |

`context.step(...)` can replay a durably recorded result and coordinate call intents. Its contract must not promise universal exactly-once remote execution. Journal storage also needs retention, sensitive-data handling and ownership; SDK syntax alone does not supply those properties. T02, T03, T12 and T27 cover different boundaries.

#### 6.3 Authorization equivalence — R19

A task forbidden by a direct ABAC check must not leak through list results, counts, exports, search, history, file references or cached responses. A denormalized permission table is one implementation choice, not the policy definition.

Define resource/action/tenant/principal semantics, deny precedence, group membership changes, policy versions, cache invalidation and field-level access. Avoid relying on post-filtering a page as the only enforcement: it can leak counts and produce incorrect pagination. Reauthorize sensitive actions when permission or relevant business state changes.

The 10,000-task/300-group benchmark is a starting workload. It must include skewed tenants, overlapping grants/denies, cold caches, large memberships, revocations, pagination and policy changes. Fast incorrect filtering is a failure. T13–T17 are necessary.

#### 6.4 Deletion, cancellation and exclusivity — R23 and R35

“Delete instance” cannot erase an already-dispatched payment or notification. Preserve a tombstone, cancellation intent, audit and late-result handling. Suppress spurious internal retry noise while retaining business uncertainty and reconciliation work. Define who can cancel, what can still finish and which compensations are supported. T11 is essential.

Serializing completion updates prevents some engine-state races. It does not stop two external actions from executing simultaneously. Define the exclusivity scope—instance, root, business entity or another key—and enforce the necessary ownership before dispatch. Do not hold a database transaction open across the network call. A lease can fence engine writes; fencing the downstream resource requires its cooperation. T05 must observe the external action, not just completion order.

#### 6.5 Outbox and inbound inbox — R4, R38, R40

An outbox needs an actual relay algorithm. An increasing sequence ID is not necessarily commit order: transaction A can reserve a lower ID, transaction B commit first, and a high-watermark relay can permanently skip A when it later commits. Use an algorithm that accounts for pending committed rows, durable claims/retries and ordering requirements. Explain per-destination acknowledgement, duplicate consumption, poison records, retention and repair. AWS also explicitly warns that outbox delivery can duplicate events. [S7]

Inbound messages need a complementary contract: persist before acknowledging acceptance; deduplicate by source/tenant/message identity; define correlation cardinality, early arrival, missing subscription, retries and expiry. For multiple independent databases, do not imply one local transaction covers them all. T06–T09 and T25 are minimum evidence.

Finite capacity is part of correctness: declare buffer budget, admission control, alerting, backpressure and recovery rate. If incoming rate is λ and recovery capacity is μ, a backlog B drains in approximately B/(μ−λ) only when μ>λ under the modeled workload. A two-hour outage walkthrough does not prove sustainable recovery.

#### 6.6 Locking and durable ownership — R3, R39

A simple root→execution→job order does not fully specify operations touching multiple roots, subscriptions, batches, timers, cancellation or tenant provisioning. Document the complete lock set, stable ordering, transaction bounds, retry classification and fairness.

Partition movement needs owner epochs, stale-owner rejection and in-flight recovery. Reducing or changing partitions must not strand jobs or silently assign the same work to two owners. T10 and T24 help, but targeted partition-rebalance tests must be added.

### 7. Complete R1–R49 review and closure map

The following is a review disposition, **not a claim that any item is implemented or passed**. Existing T IDs are defined in §12. “Add test” means the current T01–T32 list is related but not sufficient for that particular requirement.

| Req | Disposition / required closure | Relevant evidence |
|---|---|---|
| R1 | Keep relay-driven wake-up policy; specify poll fallback, payload limits, notification outage behavior and measured benefit | T06–T08, T25, T31; add lost-wakeup test |
| R2 | Keep dialect-specific acquisition; verify isolation, escalation, fairness and starvation | T10, T24, T31 on committed DBs |
| R3 | Incomplete: full multi-resource lock specification | T10; add lock-order property/concurrency tests |
| R4 | Incomplete: claim/ack, commit visibility, ordering, poison records and consumer semantics | T06–T08, T25 |
| R5 | JDK 25 is a proposed baseline; certify actual supported frameworks, drivers and patches | T28, T31 plus build/runtime matrix |
| R6 | Rewrite exact sandbox runtime/edition/license, host capabilities and resource limits | T23 plus escape/isolation tests |
| R7 | Keep native-light restriction; publish which APIs/features are supported | Profile compatibility/build evidence |
| R8 | Keep DMN conformance/footprint evaluation; select supported engine scope deliberately | T18–T20; pinned TCK results |
| R9 | Urgent: ACK/lease lifecycle; no immediate reclaim solely on stream close | T03, T04 |
| R10 | Rewrite context-specific null behavior and semantic version binding | T18, T19; null and upgrade corpus |
| R11 | Keep typed JSON; enforce total bytes, depth, number/reference and decompression limits | T18, T23 |
| R12 | Keep controlled migrations; add VM/Docker operation and failed-migration recovery | T28, T29 |
| R13 | Refine context/trace links; protect privacy, cardinality and core behavior during exporter failures | T24, T31; add exporter-failure test |
| R14 | Expand beyond read-only MCP: read authority, action-bound approvals, budget and outcome uncertainty | T12–T17, T23; AI-specific tests |
| R15 | Keep XES-first; define event/object identity and meaningful domain timestamps | Export semantic fixtures |
| R16 | Keep contract-driven generation; one semantic source across protocol formats | Cross-schema/protocol compatibility tests |
| R17 | Keep DB tiers; resolve support promises and CI/release gates | T10, T26, T28, T29, T31 per committed DB |
| R18 | Resolve initial SDK scope against actual applications; do not silently drop promised PHP/.NET behavior | T01–T04, T18, T28 across supported SDKs |
| R19 | Urgent: uniform list/object/count/export/field policy and revocation semantics | T13–T17 plus policy performance |
| R20 | Keep fencing/heartbeats with a narrower guarantee for engine mutation | T02–T05, T27 |
| R21 | Keep sweeper; use authoritative lease time and valid state transitions | T03, T04; concurrent-sweeper test |
| R22 | Keep comparable benchmark workloads; add lifetime, backlog, enabled controls and cost | T24, T25, T31 |
| R23 | Urgent: cancellation/tombstone/late-result/reconciliation contract | T10, T11, T27 |
| R24 | Keep hardened XML policy; include all parsers, transformers and resolvers | T23; parser inventory and fuzz corpus |
| R25 | Clarify arbitrary expressions versus approved named-query handlers | T13, T14, T23 plus named-query tests |
| R26 | Bind jobs to authenticated principal, tenant, lease and allowed topic | T03, T13; worker-bound API matrix |
| R27 | Keep connector egress controls; define approved internal destinations and redirect/DNS/callback rules | T23 plus SSRF/callback tests |
| R28 | Keep secure defaults; configure per-tenant quotas and operation-specific retry rules | T01, T13, T23–T25 |
| R29 | Refine retention for active processes, audit, holds, references and non-SQL copies | T25, T30 |
| R30 | Keep bounded caches; add invalidation, tenant/model keys and representative soak | T19, T24, T31 plus memory/GC evidence |
| R31 | Specify elapsed-time versus calendar schedules, DST and downtime catch-up | T22 |
| R32 | Keep upgrade matrix; reconcile old-model support with LTS/default-flag windows | T19, T28, T29 |
| R33 | Complete runtime and deployment type validation, precise decimals and time/null behavior | T18, T19 |
| R34 | Reproduce alleged upstream gaps; publish release-specific semantic support | T20, T21 plus pinned gap corpus |
| R35 | Urgent: distinguish state serialization from actual business-effect exclusivity | T05 |
| R36 | Adopted E01: stable business-operation ID and UNKNOWN_OUTCOME lifecycle | T02, T03, T27 |
| R37 | Adopted E02: durable call intent and journal crash-window handling | T12; also ordinary worker step-helper tests |
| R38 | Adopted E03: durable inbound inbox and correlation lifecycle | T09 plus TTL/multiple-match tests |
| R39 | Adopted E09: partition epochs and stale-owner fencing | Add rebalance/failover/in-flight tests; T24 is only related |
| R40 | Adopted E10: finite outbox capacity and controlled overload | T25 plus recovery-drain measurement |
| R41 | Adopted E12: API idempotency scope, payload hash, concurrency, result and retention | T01 plus expiry/key-reuse tests |
| R42 | Adopted E13: separate audit evidence, diagnostics and deterministic replay requirements | Add replay-input completeness and privacy tests; T19/T30 only cover parts |
| R43 | Adopted E14: version the executable package, not only FEEL | T19, T21, T28 |
| R44 | Adopted E15: explicit supported standards profile per release | T20 plus coverage matrix |
| R45 | Adopted E16: bounded CMMN conversion, manual handling and run-off policy | Add supported/unsupported CMMN corpus; T21 alone is insufficient |
| R46 | Adopted E17: modeler/runtime semantic parity | T20, T32 plus round-trip/authoring corpus |
| R47 | Adopted E19: separate form schema/rendering/data contract | T16, T19, T32 plus form version fixtures |
| R48 | Adopted E20: model import, API compatibility and live-state migration as distinct contracts | T21, T27, T28 plus real migration corpus |
| R49 | Adopted E21: continue-as-new/modification must preserve business obligations | Add carry-forward, correlation, timer, approval and audit-continuity tests |

The statement “their acceptance tests are T01–T32” is directionally useful but too broad. R39, R42, R45 and R49 illustrate why a real many-to-many traceability map and additional focused tests are required. No count of tests demonstrates exhaustive correctness.

### 8. Application और design को पूरा product बनाने के लिए क्या बाकी है

The UI is more than a modeler plus tasklist. The document names applications and personas, but does not supply complete screen flows or usable application evidence. Operaton's neo frontend also reuses its own backend logic [S2]; reusing that frontend over a different runtime does not remove backend API, authorization, history and migration integration work.

| Existing use case | Minimum complete product behavior |
|---|---|
| UC1 — polyglot work | Start, retry, cancel, heartbeat, idempotency and errors have the same business meaning across required languages |
| UC2 — modeling/versioning | Draft, validate, compare, approve, publish and retire; unsupported constructs visible before deployment |
| UC3 — human tasks | Claim/delegate/reassign, maker-checker, deadlines, absence/escalation, form validation and concurrent-edit handling |
| UC4 — operations | Incident diagnosis, safe retry/cancel/modify, effect visibility, audit and controlled migration; no destructive one-click ambiguity |
| UC5 — permissions/tenancy | Consistent views/actions, organization changes, revocation, provisioning, tenant export and deletion |
| UC6 — import | Assessment report, supported extension mapping, rejected/changed semantics, representative model corpus and explicit live-state limits |
| UC7 — messages | Durable receipt, duplicate/early/late message behavior, correlation errors and repair UI |
| UC8 — case management | Chosen CMMN profile and operator lifecycle; isolation does not itself establish conversion correctness |
| UC9 — agent step | Tool authority, approval binding, budget, cancellation, journal, unknown outcomes and model/tool version evidence |
| UC10 — MCP | Scope every read as well as writes; redact sensitive fields; record tool identity and requested action |
| UC11 — mining/export | Correct business-object identities, event ordering, lifecycle times, privacy and tenant-aware export |
| UC12 — serverless step | Authentic callback, duplicate callback handling, timeout/cancel semantics and external provider reconciliation |

#### 8.1 A representative vertical pilot

Use a concrete approval workflow: submit a request, validate data, evaluate a rule, assign an approver, execute an external action and retain an auditable outcome. Test rejection, reassignment, permission revocation, amount/beneficiary change after approval, duplicate submission, external timeout, worker crash, cancellation and restore.

Approval must bind to the actual action and material business values, not only a task ID or an earlier screen. A changed amount or beneficiary needs the documented reapproval rule. An actor with both initiator and approver roles must still satisfy segregation-of-duties policy.

For users, design empty/loading/error states, expired sessions, stale forms, conflicting claims, validation errors and recoverable incidents. Include keyboard use, screen-reader behavior, required languages/localization and mobile layouts. An accessible component library does not automatically make the workflow accessible.

For operators, show what is known, what is pending and what is uncertain. “Job failed” is insufficient when a payment may have succeeded. Support a reconciliation queue with ownership and evidence. This is both application quality and a potential differentiator.

#### 8.2 Versioning and standards

Bind the executable package: BPMN/CMMN model, DMN/rule version, FEEL semantics, forms, script/worker contract, called subprocesses, connector configuration and relevant policy references. Decide which policies must be evaluated live for revocation rather than frozen forever. Record enough version information for explanation and safe changes.

Do not equate a JSON form format with generic JSON Schema. Define form rendering, data validation, draft persistence, attachments, conditional fields, field-level authority and older in-flight form versions separately.

A standards claim needs a versioned execution profile, supported elements/options and explicit rejection behavior. Modeler import/render success, MIWG interchange and runtime semantic conformance answer different questions. A universal CMMN-to-BPMN converter is not justified; publish a supported subset and run-off/manual migration strategy.

FEEL null must be interpreted by context; valid optional null values should not universally become incidents. Money, decimals, large integers, zoned/unzoned times and serialization across SDKs need precise contracts. Deployment checks cannot validate every dynamic runtime value.

### 9. Technology-stack, security and operational assessment

| Layer | Recommended stance | Required proof / constraint |
|---|---|---|
| Engine | Compare minimal changes on Flowable and Operaton before deep fork | Same workflow, compatibility, permission and recovery corpus; measured patch/maintenance burden |
| Core structure | Modular monolith with clear service and persistence boundaries | Keep useful upstream internals until replacing them has demonstrated value; module/table/class counts are not quality outcomes |
| JVM/frameworks | Keep a single declared baseline and supported profiles | Compile/runtime/driver matrix; security updates; virtual threads still need bounded DB pools and concurrency |
| Persistence | Relational transactional state; exact supported DB versions | Dialect/isolation, indexes, vacuum/cleanup, deadlocks, quotas, failover and upgrades |
| Tenant model | Explicit row-level or DB-per-tenant operating model | Trusted tenant binding, administrative boundaries, provisioning failure, exports, backup and per-tenant migrations |
| Workers/API | Stable lifecycle contract before many SDKs/transports | REST first is a reasonable default proposal; gRPC justified by measured need; preserve any required pilot transport |
| Rules | One deliberately supported DMN engine/profile initially where feasible | TCK plus business edge cases, footprint, modeler support and semantic versioning |
| Events | Durable inbox/outbox; wake-up transport only an optimization | Correctness must survive dropped wake-ups and broker outages; bound buffering and replay |
| UI | Reuse where compatible; own complete user/operations flows | Preview maturity, backend contracts, permission parity, forms and accessibility |
| Scripts | External workers first unless in-process scripting is essential | Choose exact isolation boundary, artifact, edition, license and host capabilities |
| Telemetry | OpenTelemetry/Micrometer with deliberate data policy | Bounded cardinality, sensitive-data suppression, exporter outage behavior and meaningful SLOs |
| Build/release | Versioned dependencies, SBOM, provenance and reproducibility | Actual pipeline evidence and supported release matrix; labels alone are insufficient |

#### 9.1 Licensing and provenance

Liquibase's official 5.0 release notes document the move to FSL. GraalVM's JDK 25 security guide says the documented sandboxing facility is unavailable in Community Edition. [S5, S6] These are material stack decisions; a broad “Apache 2.0 everything” statement is inadequate.

The master says the studied Operaton pom pins Liquibase 4.33.0. Without that pinned checkout this reassessment does not certify the exact pom. Even a permissible old version needs a security/maintenance route, not permanent freezing to avoid a licensing decision.

Create a version-level inventory for distributed code, runtimes, drivers, UI packages, fonts/icons, container images and transitive components. Record notices, attribution/watermark duties, modifications and redistribution conditions. The prior review's bpmn-js finding remains part of that work; do not automatically generalize one component's exact license to every related package.

For copied or ported code, keep upstream commit, files, rationale, license/notice and regression tests. A port of behavior can cost more than its line count suggests. A rename-heavy fork makes later security merges harder; measure that cost before broad cleanup.

#### 9.2 Security and AI

Authentication is not authorization. Bind tenant, principal, worker lease, connector and callback authority end to end. Include service accounts, key rotation, secret access, webhook authentication, replay protection and administrative break-glass audit.

Parser hardening must include every XML factory, import, transform, resource resolver and related archive path. Also bound compressed input, total payload, depth and expensive validation. The unconfirmed CVE identifier and the alleged parser weakness are separate evidence questions. A grep result alone does not prove an exploitable path or a completed fix.

Read-only AI/MCP tools can disclose sensitive data. Tool outputs are untrusted input; taint labels and prompt instructions are not authorization enforcement. Bind approvals to tool identity, action arguments, affected resource and expiry; recheck authority at execution. Set per-step and per-workflow budgets and cancellation rules. Record external-model/tool versions and effects, while controlling PII and secret retention. “Replay recorded result” is different from recomputing nondeterministic LLM behavior.

ASVS, SSDF, SAMM, SLSA, fuzzing, mutation tests and penetration testing can all support assurance. Their names or target levels are not certifications or evidence of a passed review. Define applicability, owners and the exact artifacts each claim requires.

#### 9.3 Capacity, HA and recovery

The R5 arithmetic of roughly 16 transactions and 225 row writes per process is a useful model. At 1,000 instances/s its multiplication is correct. Calling that a universal upper physical limit of PostgreSQL is not established: row size, indexes, WAL, contention, history, hardware, batching and durability settings materially change capacity. Treat both the model and the proposed throughput as hypotheses until measured.

Measure completed work, not only accepted starts. Include p95/p99 latency, oldest pending work, queue depth, errors, resources, cost and backlog recovery with auth/audit enabled. Long-lived human workflows also accumulate active instances. At a steady arrival rate λ and mean lifetime W, expected active population is approximately λW under the model's assumptions. Short synthetic flows can miss the dominant storage/query workload.

Replicated DB does not automatically mean RPO 0. PostgreSQL documents asynchronous loss windows and configurable synchronous replication. [S8] Define which acknowledged writes survive which failures, which replica can be promoted, how split-brain is prevented and what happens when the synchronous replica is unavailable. RPO 0 is conditional on a topology/failure boundary; it is not protection against every deletion, corruption or regional disaster.

A restore can rewind engine state after external actions already completed. Preserve or reconcile downstream operation identities so recovery does not blindly repeat payments or messages. Rehearse failover, backup restore, old/new rolling upgrades, partial tenant migrations and dependency outages separately.

Retention must cover active-state growth, audit minima, legal holds, deleted tenants, object references, backups, search/export copies and journals. Default cleanup cannot delete required evidence merely because a generic TTL elapsed. A 24-hour soak is useful evidence, not proof of a ten-year leak-free lifecycle.

### 10. All 12 decisions: my recommended disposition

These are recommendations for the next approved baseline. They do not silently alter the user's requirements or certify unresolved evidence.

| Decision | Recommendation | Closure evidence |
|---|---|---|
| D-1 Base engine | Keep provisional; compare minimally modified candidates; deep fork only for demonstrated gaps | Same representative model/recovery/authorization corpus; patch and maintenance estimate |
| D-2 Delivery sequence | Use buyer validation → complete pilot → hardening → expansion; map old phase labels to that sequence | One scoped release backlog, dependencies, owner and exit criteria |
| D-3 First market | Select one first buyer/workflow; keep other markets as later hypotheses | Buyer problem, adoption reason, operating constraints and pilot commitment |
| D-4 SDKs | Support required pilot languages well; default Java plus 1–2 actual application languages | Integration inventory and identical contract tests; preserve explicit PHP/.NET acceptance if required |
| D-5 API/transport | Stable REST lifecycle first unless the pilot proves streaming is required | Latency/load/proxy experiments and recovery semantics |
| D-6 Scripts at launch | Prefer external workers for initial custom code; add scripts only for an explicit need | Threat model, isolation tests and version/license matrix |
| D-7 Migration tool | Choose a maintained, licensable route; evaluate pinned 4.x, acceptable FSL use or an alternative explicitly | Exact artifact terms, maintenance plan, supported DB migration tests |
| D-8 JS sandbox | Choose a documented runtime/edition or process-isolated alternative; WASM also needs host/resource controls | Escape/resource-bound tests, operational overhead and approved distribution profile |
| D-9 Market claim | Close the policy: uniqueness/open-slot remains a hypothesis | Keep customer/competitor validation as a separate open task |
| D-10 Success metric | Close the policy: use G1–G8; remove uncalibrated probability from release approval | Gate evidence remains pending; archive subjective percentages if history is needed |
| D-11 Sustainability | Define who pays, for what, and who funds releases/security/support | Staffing, cost/runway, offer validation and support ownership |
| D-12 HA/DR | Declare topology-specific RPO/RTO and availability objectives | Failover/restore under load; durability boundary; reconciliation after restore |

A useful decision record contains question, options, constraints, chosen option, evidence, consequences, owner, date and re-evaluation trigger. An OPEN entry without an experiment or owner is only a list of uncertainty.

### 11. PDF recommendations and every recorded difference

#### 11.1 N1–N16: disposition

| Item | Reassessment |
|---|---|
| N1 — broad title and market framing | Title is fine; evidence-based framing must not turn the market hypothesis into fact |
| N2 — five expert roles | Useful lenses; not five independent reviewers or certifications |
| N3 — zero-omission/method claim | Replace absolute completeness claim with a documented coverage ledger; heading order is cosmetic |
| N4 — seven BPMN gaps assigned Phase 1 | New scope commitment; reproduce gaps and test required semantics before assigning cost/phase |
| N5 — “Polyglot Enterprise” source label | Weak provenance; use a concrete implementation/reference or label it inference |
| N6 — market table additions | Pin product/edition and evidence; uncertainty about “Control” needs original source context |
| N7 — safe defaults column | Useful direction; retries, locks, retention and query limits require operation-specific contracts |
| N8 — added tool/security level names | Treat as planned work and applicability decisions; unchanged estimates do not validate the added assurance scope |
| N9 — exceptionally mature / 100% successful | Rejection is correct; no such success guarantee follows from the dossier |
| N10 — 6–9 months and four SDKs | Scope proposal, not validated schedule or sufficient-to-win evidence; resolve buyer languages and staffing |
| N11 — authorization spike first | Strong priority; compare contracts on candidate engines, then choose the port; test correctness as well as speed |
| N12 — importer Phase 1.5 | Sensible if migration is the entry market; namespace replacement alone does not preserve semantics |
| N13 — PostgreSQL-only early PR gate | Conditional on supported release scope; do not claim Oracle/SQL Server support without their required checks |
| N14 — early CMMN isolation/defer converter | Sensible if CMMN is not the first buyer's need; isolation itself can require integration work |
| N15 — step journal helper | Useful possible feature; duplicate-free arbitrary remote APIs is an invalid guarantee; estimate and test separately |
| N16 — public benchmark after Phase 1 | Useful once comparable builds/workloads exist; publish limits and raw data even if targets are missed |

#### 11.2 Part IV X1–X21: disposition

These IDs refer specifically to the PDF difference table, not Part V's factual X-1…X-20 register.

| Difference | Reassessment / resolution |
|---|---|
| X1 — four vs six SDKs | Explicit scope decision; old PHP/.NET exit condition cannot be silently dropped |
| X2 — auth/outbox/UI timing and DB scope | Move safety/usability into any real pilot; update one roadmap and support matrix |
| X3 — 6–9 months | Unvalidated; the counter-estimate is also an inference; estimate a defined MVP |
| X4 — 100% successful | Reject guarantee |
| X5 — importer Phase 1.5 vs 4 | Buyer-driven sequencing decision; separate model import from running-state migration |
| X6 — database CI | Reconcile change/release gates with declared support, not just convenience |
| X7 — CMMN isolation timing | Separate early architectural isolation from later feature/converter investment |
| X8 — journal duplicate guarantee | Reject; preserve unknown outcome and reconciliation |
| X9 — 22 modules | Correct against the actual build/module graph; diagram entries and build modules differ |
| X10 — 3,659 tests | Test files across a repository are neither test-case count nor proof of port coverage |
| X11 — unlimited Flowable query results | Unverified as stated; inspect the exact deployed API/config rather than asserting the opposite |
| X12 — Notice 157 causality | Unsupported causal attribution; require source/version/reproducer |
| X13 — changed stakeholder evidence | Preserve exact citation and distinguish inference; not inherently a design contradiction |
| X14 — “full” OCEL support | Remove unsupported breadth or show versioned conformance evidence |
| X15 — “clearly best” | Unsupported comparative superiority |
| X16 — SDK maturity caveat removed | Restore maturity/support qualification; counts alone are not parity |
| X17 — UI “if delivered” removed | Restore distinction between planned and shipped behavior |
| X18 — “far simpler” | Require installation/operations comparison before stronger claim |
| X19 — approval-fatigue residual omitted | Retain the risk and evaluate approval design |
| X20 — delayed inbound/events residual omitted | Retain business impact, finite backlog and repair/recovery behavior |
| X21 — test engine limited to Tier 1 | Coherent with R18 precedence; align old broad wording in the current baseline |

The factual correction register X-1…X-20 should remain: market uniqueness; neo preview; existing AI features; clients; DMN task semantics; licenses; sandbox; TCK ranking; standards/protocol versions; Camunda licensing/storage; n8n storage; three CVE interpretations; RPN; success probability; ISO edition; and non-comparable performance rows. This reassessment does not relabel all 20 as newly independently verified. Selected items were rechecked through S1–S8; the others retain the earlier review's cited evidence and stated limits.

### 12. Test evidence and release gates

The retained 32 tests are a good starting corpus. They have **not been executed in this review**.

| ID | Scenario / observable property |
|---|---|
| T01 | Concurrent/retried process starts produce one intended instance; conflicting payload rejected |
| T02 | Crash after remote effect before completion gives safe recovery or explicit unknown outcome |
| T03 | Stale worker cannot mutate protected state after lease expiry |
| T04 | Stream loss does not unjustifiably redispatch acknowledged running work |
| T05 | Actual required external-effect exclusivity, not only completion serialization |
| T06 | Lower-ID late outbox commit is eventually delivered |
| T07 | Publish-before-mark crash is safe for each destination/consumer |
| T08 | Poison event has explicit ordering/block/repair policy |
| T09 | Early and duplicate inbound events have documented durable behavior |
| T10 | Parent/child modification, timers and cancellation preserve invariants |
| T11 | Deletion during external work preserves audit, late results and reconciliation |
| T12 | Agent call/journal crash window produces safe replay or uncertainty |
| T13 | Tenant spoofing is rejected at every API/worker/broker/file entry point |
| T14 | Direct read, list, count and export apply equivalent authority |
| T15 | Permission revoked after form load is handled at execution |
| T16 | Material data changes invalidate/recheck approval as specified |
| T17 | Maker-checker separation holds despite overlapping assignments |
| T18 | Decimal/large integer/date/null meaning survives every supported SDK |
| T19 | Updating rule/form/model respects in-flight version binding |
| T20 | Unsupported executable constructs are clearly rejected or deliberately non-executable |
| T21 | Migration/modification preserve supported timer/MI/compensation invariants |
| T22 | DST/calendar/downtime scheduling matches the declared policy |
| T23 | Hostile inputs/scripts/destinations are bounded and cannot gain forbidden access |
| T24 | Large tenants cannot cause unbounded starvation/pool impact |
| T25 | Long broker outage has finite capacity, controlled degradation and measured recovery |
| T26 | Acknowledged DB writes survive the specified failover boundary; RPO/RTO measured |
| T27 | Restore does not blindly repeat completed external effects |
| T28 | Rolling N/N-1 upgrade works with active processes and a recovery plan |
| T29 | Partial tenant migration/provisioning failure is isolated and repairable |
| T30 | Retention/erasure respects holds, references and required evidence |
| T31 | Stable-load benchmark with auth/audit enabled measures completed work and tails |
| T32 | First-time users complete a real workflow, including errors and required accessibility/localization |

Add focused evidence for partition rebalance/epochs; complete deterministic replay inputs; supported and rejected CMMN conversions; continue-as-new obligations; lost wake-ups; worker principal/lease binding; connector callback replay; protocol/schema compatibility; and cache/telemetry failure behavior. These additions are driven by requirements, not a desire to increase the test count.

| Gate | Pass evidence | What can be claimed before it passes |
|---|---|---|
| G1 — buyer/problem | Real use case, accountable buyer, adoption reason | Product hypothesis |
| G2 — engine choice | Comparable corpus and maintenance burden | Provisional base |
| G3 — dependency/license route | Version-level inventory and supported isolation profile | Candidate stack |
| G4 — business correctness | Effect/commit, lease, inbox/outbox, types/version tests | Controlled technical experiment |
| G5 — authority/isolation | Tenant, policy, revocation and approval-binding evidence | No verified sensitive multi-tenant readiness |
| G6 — operability | Upgrade, failover, restore and backlog-recovery evidence | No verified production SLA |
| G7 — usable application | Complete human and exception workflows | Engine/UI prototype |
| G8 — sustainability | Staffing, support ownership, scope and funded maintenance | No established long-term delivery commitment |

Gate absence is an evidence limitation, not proof that the product will fail. The supplied documents do not establish that these gates have passed.

### 13. What the next approved baseline should contain

Keep the historical material; place the current decisions and contracts first. A workable structure is:

1. Product boundary: buyer, first workflow, measurable adoption reason and explicit non-goals for the first release.
2. Supported release matrix: runtime, DB/version, SDK/version, transport, standards, deployment and license profile.
3. Closed decisions with evidence and remaining experiments with owners.
4. Canonical R1–R49 requirements, each with rationale, normative behavior, failure behavior, dependencies, release scope and tests.
5. Data/state/API contracts: lease and business-effect lifecycles, authorization, idempotency, inbox/outbox, executable-package binding, migration and audit.
6. User and operator journeys: normal flow, exceptions, uncertainty, access changes and recovery.
7. Release evidence: conformance results, failure tests, raw benchmarks, build provenance, upgrade/restore runbooks and known limitations.
8. Historical sources and the current cross-verification register, linked by stable IDs.

A requirement should be independently implementable and reviewable. For example, R9 needs exact states/authority and reclaim rules, not only “follow E06.” A developer should not have to reconcile three historical paragraphs to discover whether a stream disconnect unlocks a job.

#### Recommended execution order

| Priority | Work | Accountable role | Exit artifact |
|---|---|---|---|
| 1 | Fix buyer/release constraints and compare existing bases | Product owner + lead architect | Buyer brief, representative corpus and base-selection evidence |
| 2 | Specify R3/R4/R9/R19/R23/R35 and business-effect/inbox contracts | Core, DB and security leads | Reviewable state/API/schema contracts and failure-test mapping |
| 3 | Choose stack/license/support profile; scope R36–R49 | Engineering/release owner | Version matrix, owners, estimates and explicit exclusions |
| 4 | Build one secured vertical pilot with actual required SDKs/DBs | Application + platform teams | Working user/operator flow and recorded failure recovery |
| 5 | Rehearse upgrade/restore, measure capacity and validate adoption | SRE/QA + product owner | G4–G8 evidence and a justified expansion decision |

No calendar guarantee is justified without actual staffing, scope and dependency evidence. Preserve the long-term vision, but make each release's promises small enough to prove and maintain.

### 14. Coverage and final judgment

| Source area | Treatment in this reassessment |
|---|---|
| Part I — full architecture and R1–R35 | Preserved-content check; architecture, runtime, stack and per-requirement closure map |
| Part II — stakeholders, 12 use cases, standards, market, trade-offs, FMEA, threats, scenarios, capacity, effort and risks | Prior complete review retained; arithmetic recomputed; product/application/operations and evidence limitations reconsidered |
| Part III — previous independent review | Ordered-content check; accepted corrections recognized rather than reported as entirely absent |
| Part IV — 16 new items and 21 differences | Every ID receives a disposition in §11 |
| Part V — correction register, R36–R49 and 12 decisions | Evidence/status/precedence audit; all requirements and decisions covered |
| Appendix A — PDF extraction | Considered as supplied text; original PDF fidelity not independently verified |

The product opportunity remains plausible. The strongest direction is a reliable, usable workflow application/platform for a particular buyer, with transparent deployment and maintenance. The major risk is committing to a broad fork, multiple languages/databases, standards, UI, migration and agents before proving the buyer value and the most difficult correctness contracts.

**My decision:** proceed with bounded validation and a complete secured pilot. Do not yet represent the consolidated master as a finished implementation baseline, market-winning plan, certified security posture or production-ready product. The next substantial improvement is to close decisions and produce observable engineering/customer evidence.

### Primary sources rechecked for this reassessment

| ID | Primary source | Used for |
|---|---|---|
| S1 | [Operaton FAQ](https://operaton.org/faq/) | License, migration positioning, support ecosystem, MCP |
| S2 | [Operaton 2.2 release notes](https://docs.operaton.org/docs/documentation/reference/release-notes/2_2/) | Neo preview/backend coupling; SKIP LOCKED preview and performance caveat |
| S3 | [Flowable external clients](https://documentation.flowable.com/latest/develop/external-clients) | Published language clients; enterprise-doc context |
| S4 | [Camunda AI agents](https://docs.camunda.io/docs/components/agentic-orchestration/ai-agents/) | Existing native/external agent orchestration |
| S5 | [Liquibase 5.0 release notes](https://docs.liquibase.com/community/release-notes/5-0) | FSL licensing change |
| S6 | [GraalVM JDK 25 security guide](https://www.graalvm.org/jdk25/security-guide/) | Edition-specific sandbox boundary |
| S7 | [AWS transactional outbox guidance](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html) | Duplicate delivery and consumer idempotency |
| S8 | [PostgreSQL replication documentation](https://www.postgresql.org/docs/current/warm-standby.html) | Asynchronous loss window and synchronous-replication configuration |

The broader source register and original section-by-section review remain in [Sangam_End_to_End_Independent_Review.md](https://chatgpt.com/library?file_id=libfile_823535e7c03c8191b72343432ed8a181&account_id=personal), also preserved as Part III of [SANGAM.md](https://chatgpt.com/library?file_id=libfile_20367b28b3f88191a986d6aecfedfd32&account_id=personal). This report is a new reassessment, not an edit to either input.



---

# Part VIII — Reconciliation of the Master Reassessment (Part VII)

> **Purpose:** to record, item by item, how each finding of the reassessment (Part VII) relates to this master. A finding is either already closed (mostly by Part VI) or adopted as an amendment.
>
> **Precedence:** amendments marked **ADOPTED** here override earlier text in Parts I–VI.
>
> **Status fields:** per M04, three separate fields are used from now on:
> - **Evidence:** VERIFIED / CITED / UNVERIFIED / CONTRADICTED
> - **Decision:** ADOPTED / DECIDED / OPEN / REJECTED
> - **Delivery:** SPECIFIED / IMPLEMENTED / TESTED

## VIII.1 Which version was reassessed

| Check | Result |
|---|---|
| Master version the reassessment reviewed | 5,742 lines. That matches the master **before Part VI was added**: 5,740 lines from the build, plus the two-line retirement note. Evidence: VERIFIED (build log) |
| Consequence | Findings about R3, R4, R9, R19, R23 and R35 not being specified (M02, §6) refer to that version. Part VI has since specified all six. They are cross-checked in VIII.3 |
| The reassessment's hashes for DESIGN.md, ANALYSIS.md and the previous review | Match the hashes in the master header and Part V CV-1. Evidence: VERIFIED |
| The original PDF was not attached to the reassessment | The PDF's hash is recorded in the master header. Its fidelity rests on Appendix A (raw text) and Part IV. Evidence: as stated |
| Arithmetic (FMEA 4,581 → 712; effort 143–220 PM) | Recomputed again by the reassessment. These are repetitions of the same calculation over the same inputs, not independent evidence (M08). Evidence: VERIFIED |

## VIII.2 Consolidation findings M01–M12

| ID | Finding | Decision | How it is closed |
|---|---|---|---|
| M01 | No single current baseline | **ADOPTED** | VIII.6 adds the *Current Implementation Baseline index*, with one authoritative pointer per topic. A full normative rewrite of R1–R49 into one table is scheduled as the next document task (VIII.7 #1) |
| M02 | R9/R19/R23/R35 not written in full | **Closed by Part VI** (VI.3, VI.4, VI.5, VI.6), with amendments in VIII.3 | Delivery: SPECIFIED |
| M03 | "Retain as is" vs "with amendments" | **ADOPTED** | In Part V.3, read "Retain as is" as **"Retain with amendments"** wherever the review adds a requirement. The R-map in Part VII §7 is the authoritative wording |
| M04 | Status words mix evidence, decision and delivery | **ADOPTED** | Three fields from this Part onwards (see the header) |
| M05 | Precedence ignored REJECTED/Superseded | **ADOPTED** | Every final disposition in Parts V–VIII is authoritative, including REJECTED and Superseded. The replacement text is the corrected statement in V.5 or V.4b |
| M06 | D-9 and D-10 still open although policy was adopted | **ADOPTED**: **D-9 CLOSED** (the market "open slot" is a hypothesis; validating it with buyers remains a separate open task) and **D-10 CLOSED** (release approval uses gates G1–G8; percentages are archived only as history) | Decision: DECIDED |
| M07 | "REJECTED" used where evidence is simply missing | **ADOPTED** | In V.4b, "Flowable query limit unlimited" is re-labelled **UNVERIFIED** and the Notice 157 causal link **UNSUPPORTED**. "22 modules" and "3,659 tests of the port" stay **CONTRADICTED** (the module tree and the file count show otherwise) |
| M08 | Repeated checks counted as independent | **ADOPTED** | From now on, evidence is counted by independent sources, implementations and experiments |
| M09 | Scope grew without re-estimation (R36–R49, `context.step`) | **ADOPTED** | The 143–220 PM estimate **does not include** R36–R49, Part VI or the journal helper. Re-estimate after the D-1 spike (VIII.7 #4) |
| M10 | The authorization spike assumes a Flowable port | **ADOPTED** | The authorization contract (VI.4) is tested on **both** candidate bases inside the D-1 spike, before any port is committed |
| M11 | The 6–9 month MVP and the 70–110 PM counter-estimate are both unvalidated | **ADOPTED** | Both are marked UNVERIFIED. The MVP is estimated directly once its scope is fixed |
| M12 | Reused IDs (X1 vs X-1, R1/R5 workflows, D principles vs D decisions) | **ADOPTED**: stable namespaces | `CLAIM-xx` = Part V X-1…X-20; `PDF-DIFF-xx` = Part IV X1–X21; `PDF-NEW-xx` = N1–N16; `REQ-xx` = R1–R49; `WF-R1`/`WF-R5` = reference workflows; `PRIN-Dx` = design principles D1–D12; `ADR-Dx` = decisions D-1…D-12; `TEST-Txx`; `GATE-Gx`. Older IDs stay valid as aliases |

## VIII.3 Engineering blockers (Part VII §6) checked against Part VI

| Blocker | Part VI coverage | Gap the reassessment raises | Amendment (**ADOPTED**) |
|---|---|---|---|
| §6.1 R9 lease | VI.3: OFFERED vs LOCKED; a closed stream releases only OFFERED; fencing; leases; heartbeat; cooperative cancel | A worker must not start executing before the ACK/lease handshake has succeeded. Lease time must come from an authoritative clock. Reconnect/resume behaviour | **A-1:** the SDK starts the handler **only after receiving the `LOCKED` confirmation** with its `lockVersion`. An OFFERED task that the worker starts without that confirmation is a protocol violation, and the SDKs cannot do it. All lease times use the **database clock** (`now()` of the engine DB), never worker clocks. **Reconnect:** a worker that reconnects while it still holds a valid lease resumes heartbeats with `(taskId, lockVersion)`, and no re-offer happens |
| §6.2 effects / journal (R20, R36, R37) | VI.3 operationId, OPERATION_LEDGER, UNKNOWN_OUTCOME, `effect=irreversible` | Separate operation and attempt identities. A **three-way integration capability rule**. `context.step` must not promise exactly-once | **A-2:** `operationId` (stable for one intended action) and `attemptId` (changes per lease) are separate fields. A new legitimate business action gets a new operationId. Each connector or topic declares one capability class:<br>(a) *idempotent + outcome lookup* → retry or query with the same operationId;<br>(b) *outcome lookup only* → query before any controlled retry;<br>(c) *neither* → UNKNOWN_OUTCOME and reconciliation, no automatic repeat.<br>`context.step(...)` replays durably recorded results and records call intents first. Its documentation states that it is **not exactly-once for remote APIs**. Journal retention, encryption of sensitive data and ownership follow R29/R42 |
| §6.3 R19 authorization | VI.4: one policy with two compiled forms, an effective-grant table, a single query path, a property test, revocation, sensitive-action re-check | Deny precedence; no post-filtering of pages; a wider benchmark | **A-3:** **explicit deny wins** over any grant at the same or broader scope. This is compiled into the SQL as a `NOT EXISTS` deny clause. Filtering a page after the query is **forbidden** as an enforcement method (ArchUnit and code review). The T38 benchmark adds skewed tenants, overlapping grants and denies, cold caches, more than 1,000 members per group, revocations during pagination, and policy changes. A result that is fast but wrong counts as a failed run |
| §6.4 R23 / R35 | VI.5: three operations, tombstones, late results, reconciliation. VI.6: three exclusivity modes, lease at acquisition | Who may cancel, and what may still finish. Supported compensations. No transaction held across network calls | **A-4:** cancellation authority is a separate permission (`CANCEL_INSTANCE`, `DELETE_INSTANCE`, `ERASE_DATA`). Each activity declares `onCancel = finish-allowed \| cancel-requested \| compensate`. Leases are held **as rows, never as open transactions** across network calls (already implied by VI.1 rule 5; now stated explicitly). T05 observes the **external action** through a stub that records overlapping execution |
| §6.5 outbox / inbox (R4, R38, R40) | VI.2: `root_seq` under L2, state-based claims, epoch fencing, poison policy, capacity thresholds, inbox, TTL | Drain formula; several independent databases | **A-5:** the operations guide uses `drain time ≈ B / (μ − λ)`, valid only when μ > λ, and sizes `outboxMaxBytes` from it. With DB-per-tenant, each tenant database has its own outbox and relay; no transaction across databases is implied |
| §6.6 locking / partitions (R3, R39) | VI.1: total order, root set resolved first, per-tenant L1, bounded transactions, retries, dialects | Partition epochs and stale-owner rejection (R39) are **not yet specified** | **A-6 (OPEN, specified next):** the R39 partition contract (owner epochs, stale-owner rejection, rebalance with in-flight recovery) is added to the next specification batch. Until then, partitioned executors are **out of scope** for any release (DESIGN §4.3 item 4 is deferred) |

## VIII.4 R1–R49 closure map and tests

- **ADOPTED:** Part VII §7 is the authoritative **closure map** for REQ-1…REQ-49, with its "relevant evidence" column. Part VI replaces the normative text of R3, R4, R9, R19, R23 and R35. Amendments A-1…A-6 apply on top.
- **ADOPTED:** the additional focused tests from Part VII §12 are named:

| ID | Test |
|---|---|
| T41 | Partition rebalance / epochs (R39) |
| T42 | Completeness of deterministic replay inputs (R42) |
| T43 | Supported and rejected CMMN conversion corpus (R45) |
| T44 | Continue-as-new obligations (R49) |
| T45 | Lost wake-up (R1) |
| T46 | Worker principal / lease binding (R26) |
| T47 | Connector callback replay (R27, UC12) |
| T48 | Protocol and schema compatibility (R16) |
| T49 | Cache and telemetry exporter failure (R13, R30) |

  Together with T33–T40 from Part VI, the test catalogue is now T01–T49. None of these tests has been executed (Delivery: SPECIFIED).

## VIII.5 Decisions (Part VII §10) against this master

| Decision | State in this master | Reassessment recommendation | Result |
|---|---|---|---|
| D-1 Base | PROVISIONAL; weighted spike on both (VI.7) | Same; deep fork only for demonstrated gaps | **Aligned.** Plus M10: authorization is tested on both bases |
| D-2 Delivery | RECOMMENDED: Stages A–E; awaiting the owner | Same | **DECIDED** 2026-11-04: Stages A–E, see VIII.9 |
| D-3 First market | **DECIDED: government / regulated integrator** (owner, 2026-11-04) | Its default was an ISV *or* integrator workflow; for regulated buyers, keep Oracle/SQL Server and the required languages | **Aligned.** It confirms keeping Oracle/SQL Server in Tier 1 |
| D-4 SDKs | OPEN | Java + the pilot's 1–2 languages; keep the explicit PHP/.NET acceptance if it is required | **DECIDED** 2026-11-04 (IX.6): Tier 1 = Java, .NET, PHP, Python, TypeScript/Node; Tier 2 = Go, Rust, Kotlin; any other language via the REST/OpenAPI contract |
| D-5 API | OPEN | REST lifecycle first | OPEN (recommendation recorded) |
| D-6 Scripts | Effectively covered by D-8 (external script worker) | External workers first | **CLOSED** by D-8 |
| D-7 Migration tool | DECIDED: pin 4.33.0, SQL-format changelogs, FSL legal review, revisit triggers | Avoid "permanent freezing"; have a maintenance route | **Aligned.** The revisit triggers already exist. The Operaton pom pin was checked in the local clone (`reference/operaton/pom.xml` line 30) during this session; the reassessment could not see that clone |
| D-8 Sandbox | DECIDED: out-of-process script worker; no GraalJS; WASM later | A documented runtime or process isolation | **Aligned** |
| D-9 | — | Close as policy | **CLOSED** (M06) |
| D-10 | — | Close as policy | **CLOSED** (M06) |
| D-11 Sustainability | OPEN | Define who pays, for what, and who funds maintenance | OPEN. Inputs: Part III §6.5 and Part VII §5.2 |
| D-12 HA/DR | OPEN | Topology-specific RPO/RTO | OPEN. Inputs: Part III §12.3 and Part VII §9.3 |

Every decision record now uses the template from Part VII §10: question, options, constraints, chosen option, evidence, consequences, owner, date, re-evaluation trigger.

## VIII.6 Current Implementation Baseline: index

Where to find the authoritative text for each topic. Read only these locations to know the current specification.

| Topic | Authoritative location |
|---|---|
| Product boundary / first market | VI.7 D-3; Part III §6.2 and §11.3; Part VII §5.1 |
| Supported release matrix (DB, SDK, transport, standards, licence) | R17 DB tiers (I §16, II §19), D-4/D-5 OPEN, R44 profile, D-7, D-8, X-6/X-7 |
| Locks and transactions | VI.1 + A-6 |
| Outbox, inbox, back-pressure | VI.2 + A-5 |
| Worker lease, operation identity, unknown outcome | VI.3 + A-1, A-2 |
| Authorization and tenancy | VI.4 + A-3 |
| Cancel / delete / erase | VI.5 + A-4 |
| Exclusivity | VI.6 + A-4 |
| Other requirements R1–R49 | Part VII §7 closure map, with normative text in II §19 and III §8–§10 |
| Tests and gates | T01–T32 (III §16), T33–T40 (Part VI), T41–T49 (VIII.4); G1–G8 (III §18 / VII §12) |
| Decisions | VI.7 + VIII.5 |
| Corrections to earlier claims | V.2, V.4b (relabelled per M07), V.5 |

## VIII.7 Next document tasks (from Part VII §13)

1. Rewrite R1–R49 as one normative requirement table: rationale, behaviour, failure behaviour, dependencies, release scope, tests (M01).
2. Specify R39 partitions (A-6), R41 API idempotency, R42 audit vs replay data, R43 package versioning, R47 forms.
3. Version-level licence and dependency inventory (Part VII §9.1).
4. Re-estimate effort once the D-1 spike and the pilot scope are known (M09, M11).
5. Define user and operator journeys for the government approval pilot (Part VII §8, Part III §11).

## VIII.8 Part VII integrity: exact comparison

A second, stricter check was run because an outside review pointed out that the source has 499 lines while the earlier check reported 373.

| Measure | Value |
|---|---|
| Source `SANGAM_Master_Reassessment_HI.md` | **499 total lines = 373 non-empty + 126 blank** |
| Earlier check (V.6 method) | Counted non-empty lines only: 373 of 373 present, in order |
| Exact check (2026-11-04) | After undoing the one-level heading demotion, Part VII equals the source **line for line, blank lines included: 497 of 497**. The other 2 lines are trailing blank lines at the end of the file |

Evidence: VERIFIED (script `check_part7.py`). There is no content discrepancy.

## VIII.9 ADR-D-2: Delivery sequence (DECIDED)

| Field | Content |
|---|---|
| Question | How is delivery sequenced? |
| Options | (a) Phases 0–4 (Part I §11); (b) Stages A–E (Part III §15.2, Part VII §13) |
| Chosen | **(b) Stages A–E.** Phases 0–4 stay as the long-term feature inventory, mapped onto the stages below |
| Owner / date | Project owner, 2026-11-04 |
| Evidence | Part III §15.2; Part VII §10 and §13; the PDF's wedge-MVP recommendation agrees in direction |
| Consequences | The three mandatory conditions below; any release scope is stated per stage |
| Re-evaluation trigger | A stage exit fails its gate, or the first design partner changes |
| Delivery status | **Not implemented.** Accepting D-2 does not mean any work is done or any test has passed |

### Stage definitions

| Stage | Work | Exit condition | Gates |
|---|---|---|---|
| **A — Need and scope** | Fix the government integrator's first workflow, users, roles, delegation rules and deployment constraints (on-premise, DB, languages) | Approved pilot scope and acceptance criteria | G1 |
| **B — Technical proof** | D-1 spike on both candidate engines. Prove the authorization (VI.4 + A-3), lease (VI.3 + A-1, A-2), idempotency (R41) and recovery contracts on both | Engine chosen from the experiment results; licence/dependency route accepted | G2, G3 |
| **C — Full pilot** | Real user and operator flows: approvals, maker-checker, delegation, audit, failure handling and reconciliation, on the required databases and SDKs | Successful end-to-end demonstration on Oracle, SQL Server and PostgreSQL with the required SDKs | G4, G5, G7 |
| **D — Production readiness** | Security, load, upgrade, failover and restore validation; broad tests of everything built in C | Evidence for gates G1–G8 as they apply | G6 (and G4/G5 at full breadth) |
| **E — Expansion** | More workflows, integrations and capabilities | Proven customer need and maintenance capacity | G8 + one decision per expansion |

### Mandatory conditions

1. **Phase → Stage mapping**, with every requirement, dependency, owner and test placed (table below).
2. **Oracle and SQL Server are Tier 1** (ADR-D-3). They are part of the pilot and the release checks from Stage C; PostgreSQL is not the only gate.
3. **Permissions, audit and safe failure handling are built in Stage C**, and validated broadly in Stage D. They are not deferred to a later phase.

### Phase → Stage mapping

Owners are roles until people are named. "Tests" refers to the T01–T49 catalogue.

| Old phase item | Stage | Requirements | Depends on | Owner (role) | Tests |
|---|---|---|---|---|---|
| Buyer and workflow definition (new) | A | UC1–UC5 scope, D-4 languages | — | Product owner | T32 (criteria defined) |
| Phase 0: fork / strip / rename / config rework | **B (minimal), rest deferred until after D-1** | R5, R7, PRIN-D1, D4 | D-1 result | Lead architect | build matrix |
| Phase 0: Liquibase consolidation, CI DB matrix, OTel | B (skeleton) → C (full) | R12, R13, R17, D-7 | D-1 | Platform lead | T28, T49 |
| Authorization contract spike (PDF N11, M10) | **B, on both engines** | R19 (VI.4), A-3, R26 | — | Security lead | T13–T17, T37, T38 |
| Lease / operation-ID / idempotency contracts | B (proof) → C (product) | R9 (VI.3), R20, R21, R36, R41, A-1, A-2 | — | Core lead | T01–T04, T36 |
| Phase 1: BPMN + DMN core, job executor | C | R2, R3 (VI.1), R8, R10, R33, R34 (pilot subset), R35 (VI.6) | D-1 | Core lead | T05, T10, T18–T20, T33, T34 |
| Phase 1: workers (REST first), SDKs | C | D-5, R18 (Java + pilot languages; PHP/.NET kept unless the owner changes it), R16 | D-4 | DX lead | T01–T04, T18, T46, T48 |
| Phase 2: outbox, inbox, history, cleanup | C | R1, R4 (VI.2), R29, R38, R40, A-5 | — | Core lead | T06–T09, T25, T35, T45 |
| Phase 2: multi-tenancy, OIDC, audit | C | R19, R26, R28, R42 (audit part) | Authorization spike | Security lead | T13, T24, T30 |
| Phase 2: incidents, reconciliation, cancel/delete | C | R23 (VI.5), A-4 | Lease contract | Core lead | T11, T39 |
| Phase 2: UI cockpit + tasklist + admin (pilot flows) | C | UC3, UC4, R43, R47 | Forms contract | UI lead | T16, T17, T19, T32 |
| Phase 2: migration / modification / restart / batch | D (pilot-required subset can move to C) | R48, R49, R21 | D-1 | Core lead | T21, T44 |
| Security programme, fuzzing, sandbox (script worker) | C (secure defaults, parser hardening) → D (full) | R6/D-8, R11, R24, R25, R27 | — | Security lead | T23, T47 |
| Upgrade, failover, restore, load, soak | D | R22, R30, R31, R32, D-12 | Stage C build | SRE / QA lead | T22, T26–T29, T31 |
| Phase 3: CMMN | E (architectural isolation in B/C) | R45, UC8 | — | Core lead | T43 |
| Phase 3: event gateway breadth, serverless connectors | E (only the pilot's channels in C) | UC7, UC12 | Inbox/outbox | Integration lead | T09, T47 |
| Phase 3: AI agentTask + MCP | E | R14, R37, UC9, UC10 | Lease + authorization | AI lead | T12 |
| Phase 3: XES/OCEL, web modeller, Rust SDK, Helm operator, Temporal interop, WASM | E | R15, R46, R18 Tier 2, L10 | — | Various | T20, T42 |
| Phase 4: importers, compatibility facades, data migration | E (assessment tool earlier if a buyer needs it) | R48, UC6 | D-1 | Migration lead | T21, T27 |
| Phase 4: partitioned executors | E (blocked until R39 is specified, A-6) | R39 | R39 spec | Core lead | T41 |


---

# Part IX — Stage A: Need and Scope Pack (started 2026-11-04)

> **Status:**
> - Evidence: owner inputs are recorded verbatim; Indian-government constraints are **UNVERIFIED** research items (IX.7).
> - Decision: as marked per item.
> - Delivery: SPECIFIED; nothing is implemented.
>
> **Stage A exit gate: G1.**

## IX.1 Owner inputs (2026-11-04, verbatim)

| Question | Answer |
|---|---|
| First pilot workflow | "All Option": bill/payment approval, file movement (e-Office), grant/scheme sanction, licence/permit service |
| Real buyer / department | "All Department of Central Govt of India and All State Govt of India" |
| Languages of connecting systems | "Also other language, Java, .NET, PHP, Python / Node" |
| Deployment and database | "All Options": on-prem / State Data Centre with Oracle, SQL Server or PostgreSQL, and government cloud (MeghRaj/NIC) |

## IX.2 Interpretation: product envelope vs first pilot

| Level | Scope | Status |
|---|---|---|
| **Product envelope** (long-term) | All four workflow families; central and all state government departments; all listed languages and any other language via the protocol; on-prem State DC + government cloud; Oracle, SQL Server and PostgreSQL | **DECIDED** by the owner. This is the target market and must not be silently narrowed |
| **Reference pilot** (Stage A → C) | One shared **government approval core** plus **four workflow packs** on that core, built with synthetic data and simulated external systems, but with real routing, permissions, persistence and recovery | **DECIDED** here: it is the only way to cover "all options" while keeping one engine contract |
| **G1 (buyer/problem)** | Needs at least one **named** department or integrator, with an accountable person and an adoption reason | **OPEN.** "All departments" is a market, not a buyer, so G1 cannot pass on it. Buyer outreach runs in parallel with Stage A/B; the reference pilot becomes the demo for that outreach |

Risk note:
- Covering four workflow families, five or more languages, three databases and two deployment models in one pilot is a large scope (see M09, PR1).
- The common core (IX.3) keeps the engine work shared; the packs mostly add models, rules, forms and integrations.
- If the team is small, the packs are built one after another on the core, in this order: payment → file movement → sanction → licence. The order is recorded, not the dropping of any pack.

## IX.3 The shared government approval core

All four families share one lifecycle, so they are built as configuration of one core and not as four engines.

```
DRAFT → SUBMITTED → SCRUTINY ⇄ CLARIFICATION_SOUGHT (return to applicant/initiator)
      → RECOMMENDATION (1..n levels, by post/seat) → SANCTION/APPROVAL (competent authority)
      → EXECUTION (external action: payment / dispatch / disbursement / certificate)
      → ACKNOWLEDGED → RECONCILED → CLOSED (output issued, record retained)
Side exits: REJECTED (with reasons) · WITHDRAWN (by initiator) · CANCELLED (authorised, VI.5) · UNKNOWN_OUTCOME (VI.3)
```

The core provides the following, each with its spec and tests:

| Capability | Spec | Tests |
|---|---|---|
| Seat/post-based assignment | IX.5 | — |
| Amount/competence routing through DMN (delegation of powers) | IX.5 | — |
| Maker-checker and segregation of duties | R19 / A-3 | T17 |
| Return and resubmit with version history | R43 | T16, T19 |
| Approval bound to payload hash | VI.4 rule 5 | T16 |
| SLA timers and escalation | R31 | T22 |
| External action with operationId and reconciliation | VI.3, A-2 | T02, T27 |
| Audit timeline | R42 | T30 |
| Hindi/English UI | — | T32 |

## IX.4 Four workflow packs

| Pack | Actors (seats) | Main steps | Rules (DMN) | External systems (simulated in pilot) | Output | Key exceptions to test |
|---|---|---|---|---|---|---|
| **P1 — Bill / payment approval** (IFMIS-style, Part III §11.3) | Initiator (DDO office), financial checker, final approver, treasury/payment system | Bill draft → validation → checker (selected from eligible) → approver (by amount band) → payment instruction → bank/treasury acknowledgement → reconciliation | Amount bands per post (delegation of financial powers), budget head availability, duplicate-bill check | Budget/treasury/payment gateway (PFMS-like), bank acknowledgement | Payment advice, voucher | Amount changed after approval; duplicate submit; payment success with lost response; approver transferred mid-flow; delegate expired |
| **P2 — File movement (e-Office style)** | Dealing assistant, section officer, under/deputy secretary, competent authority | Note/draft → forward up the hierarchy → return/seek comments → approve/dispose → dispatch | Routing by subject and level; "put up to" rules; who can dispose | Dak/dispatch, email/SMS gateway, DSC/eSign for approval (to verify, IX.7) | Approved note, dispatch letter | Out-of-hierarchy marking; file sent back several levels; officer on leave or additional charge; parallel comments from two sections |
| **P3 — Grant / scheme sanction** | Applicant (or field office), verifier, sanctioning authority, finance | Application → document verification → field verification (optional) → recommendation → sanction → disbursement (DBT-style) | Eligibility, entitlement amount, sanction limit per authority | Beneficiary registry, document store, DBT/payment system | Sanction order, disbursement record | Ineligible after verification; partial disbursement; beneficiary account failure; re-verification on appeal |
| **P4 — Licence / permit service** | Citizen/applicant, scrutiny clerk, inspector, approving officer | Application + fee → scrutiny → clarification loop → inspection → approval/rejection → certificate issue | Fee calculation, inspection required?, deemed-approval/SLA rules (time-bound service) | Payment gateway, certificate/document repository (DigiLocker-like, to verify) | Licence certificate (signed), rejection order with reasons | SLA breach and escalation; fee paid but callback lost; inspection rescheduled; appeal after rejection |

## IX.5 Organisation, roles and delegation model (government-specific)

1. **Seat (post) ≠ person.** Tasks are assigned to a **seat** (designation + office), and the person holding the seat on that date acts. Transfers, additional charge and vacancies are effective-dated. History records both the seat and the person.
2. **Hierarchy.** Ministry/Department → Directorate → Region/District → Office → Section. Routing rules refer to hierarchy levels, not to fixed people.
3. **Delegation of powers.** Financial and administrative powers per seat (amount limits, case types) are **versioned DMN tables**. A sanction checks the seat's power at the time of action.
4. **Leave and additional charge.** A substitute seat holder acts with delegated authority for a date range. The audit shows "acted for".
5. **Segregation of duties.** The initiator ≠ checker ≠ approver, **by person and by seat**. Controlled exceptions need a higher authority and a reason.
6. **Auto vs human steps** follow the same audited state machine with different authorised actors (Part III §11.3). There are no bypass paths.

## IX.6 Deployment and stack matrix for the product envelope

| Dimension | Decision | Status |
|---|---|---|
| Deployment targets | (1) On-prem / State Data Centre: VMs or Kubernetes, **air-gapped install supported** (offline images, offline docs, no outbound calls by default). (2) Government cloud (MeghRaj-empanelled providers / NIC cloud) | **DECIDED** (owner: all options). Air-gapped support is an inference from on-prem government use and is **to verify** in IX.7 |
| Databases | Tier 1: **Oracle, SQL Server, PostgreSQL**; all three in pilot and release checks from Stage C (VIII.9 condition 2) | **DECIDED** |
| **ADR-D-4 SDKs** | **Tier 1 (full worker lifecycle + test engine): Java, .NET, PHP, Python, TypeScript/Node.**<br>**Tier 2 (generated, community-maintained): Go, Rust, Kotlin.**<br>**Any other language:** the published REST/OpenAPI contract + generated client, with the same lifecycle semantics. Contract tests (T01–T04, T18) run on every Tier-1 SDK | **DECIDED** (owner: "Java, .NET, PHP, Python/Node + other languages"). Cost note: five Tier-1 SDKs is a standing maintenance obligation (PR6); it must be staffed |
| API at start (D-5) | REST lifecycle first; gRPC streaming later when measured need exists | Still **OPEN**; the recommendation stands |

## IX.7 Indian-government constraints: research items for Stage A

These are **UNVERIFIED** until checked against official sources during Stage A. They are listed so nothing is missed.

| # | Area | What to verify | Why it matters |
|---|---|---|---|
| GOV-1 | Languages | Hindi + English UI; which regional (Eighth Schedule) languages each state needs | i18n scope; text expansion; fonts |
| GOV-2 | Accessibility / web guidelines | Current GIGW version and its accessibility requirements (WCAG mapping) | UI acceptance (T32) |
| GOV-3 | Security audit | Whether a CERT-In-empanelled auditor's security audit is required before go-live, and its scope | Stage D gate evidence |
| GOV-4 | Data protection | DPDP Act 2023 and its rules: consent, purpose, erasure, breach reporting | R29, erasure (VI.5) |
| GOV-5 | Data localisation / hosting | Hosting rules for government data (MeghRaj empanelment, data within India) | Deployment matrix |
| GOV-6 | Identity / SSO | Central (e.g. Parichay) and state SSO options; OIDC/SAML support | OIDC integration |
| GOV-7 | Digital signature | DSC / eSign for approvals and certificates; legal validity of e-approvals | P2, P4 outputs |
| GOV-8 | Payments | PFMS / state treasury integration patterns; payment gateways for fees | P1, P3, P4 |
| GOV-9 | Documents | DigiLocker or state repositories for issued certificates | P4 |
| GOV-10 | Notifications | Government email/SMS gateways, DLT rules for SMS | Notifications |
| GOV-11 | Records retention | Record retention schedules and RTI obligations for files and approvals | R29 retention and legal holds |
| GOV-12 | Procurement | GeM listing, empanelment and support-contract expectations | G8 sustainability (D-11) |

## IX.7a GOV-1…GOV-12: verification results (2026-11-04)

**Scope of the evidence:** two research passes against official Indian government sources. The full reports, with every URL and with snippet-only or secondary items marked, are in **Appendix B** (GOV-1…6) and **Appendix C** (GOV-7…12). Some official sites blocked automated fetching, and some PDFs are scanned images, which is why most items are PARTIAL.

**Result:** 2 items VERIFIED, 10 PARTIAL, 0 contradicted the plan.

| Item | Status | Key requirement found (official source) | Design implication → new requirement |
|---|---|---|---|
| GOV-1 Languages | PARTIAL | Official Languages Rules 6 and 11: documents, forms and registers covered by s.3(3) must be bilingual (Hindi and English). GIGW: bilingual, Unicode, language switch. 22 scheduled languages | **R54:** Hindi + English UI, forms and output templates, plus a configurable state language; Unicode storage on all Tier-1 DBs; optional BHASHINI hook for translation |
| GOV-2 GIGW / accessibility | PARTIAL | GIGW 3.0 (2023) covers websites, **web applications and mobile apps**; WCAG 2.1 AA; STQC Website Quality Certification; RPwD rule 15 → IS 17802 | **R55:** WCAG 2.1 AA (GIGW) as the legal floor and WCAG 2.2 AA as the product target (DESIGN §14.6); IS 17802; a GIGW conformance matrix for STQC certification |
| GOV-3 Security audit | **VERIFIED** | CERT-In-empanelled audit **before hosting, every year and after major changes**; 6-hour incident reporting; **180-day logs kept in India**; NIC/NPL time sync | **R51:** India-resident log retention ≥180 days, SIEM export, incident workflow able to meet 6 h, NTP from NIC/NPL. **R52:** an audit-ready release process (audit evidence pack per release). Becomes a **Stage D gate item** |
| GOV-4 DPDP | **VERIFIED** | DPDP Act 2023 + Rules (G.S.R. 846(E), 13 Nov 2025). Core obligations apply from about **May 2027**. s.7(b) State legitimate use; Rule 6 safeguards incl. 1-year logs; breach notice + 72 h report; s.17(4) State erasure carve-out; child-data relaxation for State benefits/licences | **R53:** purpose tagging of personal data, notice templates, encryption and masking, breach and grievance workflows, configurable retention, plus a **data-processor agreement template** for departments. Must be compliant before May 2027 |
| GOV-5 Hosting | PARTIAL | Government DC, NIC or **MeitY-empanelled (STQC-audited) cloud** only; data residency in India | **R56:** deployable on State DC, NIC and empanelled cloud; **no foreign runtime dependencies or outbound telemetry by default** (supports the air-gapped install, AC-11/T50) |
| GOV-6 Identity / SSO | PARTIAL | **Parichay mandatory for government users** (CERT-In guidelines); Jan Parichay supports REST, SAML 2.0, OAuth 2.0; Aadhaar only via an approved AUA/KUA, with no biometric storage | **R50:** pluggable identity layer with SAML 2.0 + OAuth2/OIDC adapters, a Parichay connector first; Aadhaar only through a department's AUA/KUA (Sub-AUA model), with an Aadhaar data vault and no biometrics |
| GOV-7 Digital signatures | PARTIAL | eSign API v2.1 (Feb 2023) through 7 empanelled ESPs. CSMOP 2022: **DSC or eSign mandatory on e-files for Dealing Officer and above**. Class 3 certificates | **R57:** a signing service with a DSC agent + eSign ASP (multiple ESPs); hash signing; PAdES/CMS with LTV and timestamps; signature bound to the approval payload hash (VI.4 rule 5) |
| GOV-8 Payments | PARTIAL | PFMS mandatory for central releases (GFR 232(v)). SNA-SPARSH: IFMIS payment file → PFMS sanction → RBI e-Kuber. **External integration by SFTP files with a source system ID** | **R58:** file-based (SFTP) PFMS/IFMIS connectors (signed payment/sanction files out, status files in) running as external-task workers with operationId (VI.3); gateway adapters (NTRP, PayGov, GRAS) |
| GOV-9 Documents | PARTIAL | IT Rule 9A (8 Feb 2017, Gazette number to re-check): issued DigiLocker documents equal originals. Issuer Push v1.7.2 / Pull v1.3 APIs; API Setu | **R59:** DigiLocker issuer adapter for licences/certificates (P4) and requester adapter for applicant documents; publish Sangam APIs on API Setu where required |
| GOV-10 Notifications | PARTIAL | TRAI TCCCPR amendment (12 Feb 2025): Government Message category via DLT, "-G" header suffix, URL whitelist scrubbing, one template per header; traceability enforced from 11 Dec 2024 | **R60:** a notification service with a per-tenant DLT registry (entity, header, template), template validation, whitelisted URLs only; NIC SMS and gov.in e-mail adapters |
| GOV-11 Records / RTI | PARTIAL | CSMOP 2022: e-files are category A (permanent) or C (≤10 years); A/B files reviewed at year 25 and sent to the National Archives. Public Records Act 1993 forbids unauthorised destruction. RTI: 30 days, or 48 h for life/liberty | **R61:** retention classification at file closure, review queue, legal hold, NAI export as PDF/A, RTI request SLA module. **This overrides any generic TTL cleanup (R29) for government records** |
| GOV-12 Procurement | PARTIAL | GFR Rule 149: GeM mandatory where available. Make in India order revised 19 Jul 2024: Class-I ≥50% local content, Class-II ≥20%, 20% preference margin; startup relaxations. **No method found for calculating software local content** | **R62:** GeM-listable licence/support/service SKUs; documented ≥50% local content (Class-I); cloud offer only via a MeitY-empanelled CSP. Feeds D-11 (sustainability) |

**Consequences for the master:**
1. **New requirements R50–R62** are added to the requirement set (REQ namespace). They belong to Stage C (R50, R51, R54, R56, R57, R58, R60, R61 for the pilot packs) and Stage D (R52, R53, R55, R59, R62 as evidence).
2. The **DPDP timeline (about May 2027)** and the **yearly CERT-In audit** become fixed planning constraints for Stage D.
3. **Open evidence gaps** (to close with the department or by official request):
   - the IT Act official text (India Code was unreachable);
   - the PFMS integration specification (issued only to registering systems);
   - the scanned TRAI directions;
   - Parichay protocol/onboarding documents;
   - State Data Centre hosting norms;
   - the Rule 9A Gazette number;
   - a method for calculating software local content.
4. **New test T50:** air-gapped install from the offline bundle (AC-11).

## IX.7c Non-public documents: PARKED list (not a development dependency)

> **Superseded by ADR-D-13 (X.0b, 2026-11-04):** the RQ items below are **parked**. Nothing will be requested from government during development, and **no development work depends on them**. Each integration is built from openly available detail as a configurable adapter with a simulator (X.0b). The drafts are kept only for later go-to-market use.

Some documents are **not published** and are issued only to registered systems or applicants. They cannot be obtained by browsing, and no technique changes that. The legitimate route is a formal request through the department or the system owner.

Drafts are below. They are to be sent by the project owner or a sponsoring department; nothing has been sent.

| Request | Document needed | Addressee | Channel | Precondition |
|---|---|---|---|---|
| RQ-1 | PFMS external-system integration specification: SFTP file formats, source system ID (SSID) registration, status files | PFMS Division, Office of the Controller General of Accounts (CGA), Department of Expenditure | Through the sponsoring department's PFMS nodal officer; PFMS helpdesk | A sponsoring department (G1) that will register Sangam as its source system |
| RQ-2 | Parichay application-integration documents (SAML 2.0 / OAuth 2.0 metadata, onboarding steps, test environment) | National Informatics Centre (NIC), Parichay team | servicedesk.nic.in / 1800 111 555, through the department's NIC coordinator | An application owner in a government department |
| RQ-3 | State treasury / IFMS bill and payment interface specification | State Finance Department / Directorate of Treasuries of the pilot state | Through the pilot department | Pilot state named |
| RQ-4 | Aadhaar authentication as Sub-AUA (only if P3/P4 need it) | The department's AUA/KUA, and UIDAI | Department's AUA agreement | A department with AUA/KUA licence |
| RQ-5 | eSign ASP onboarding with an empanelled ESP | Any of the empanelled eSign Service Providers (CCA list) | Commercial agreement | Legal entity + department use case |
| RQ-6 | DLT entity, header and template registration (Government Message category) | Telecom operator DLT platforms | Department's own registration | Department's PE-ID |

### Draft letter (RQ-1 / RQ-2 / RQ-3 template, to be adapted)

> **Subject:** Request for integration specification for <PFMS / Parichay / State IFMS> for the departmental workflow application "<application name>"
>
> Sir/Madam,
>
> The <Department name>, <Government of India / Government of State>, is implementing a workflow and approval application for <bill/payment approval, file movement, scheme sanction, licence services>. It will be hosted at <State Data Centre / NIC / MeitY-empanelled cloud>. To integrate it with <PFMS / Parichay / IFMS>, we request:
> 1. the current integration specification and data/file formats;
> 2. the procedure and forms for registering the application as a source system / service provider;
> 3. access to the test (UAT) environment and test credentials;
> 4. the security and audit prerequisites (e.g. a CERT-In-empanelled audit certificate) required before production access.
>
> Nodal officer: <name, designation, email (gov.in/nic.in), phone>. Technical contact: <name, email>.
>
> Yours faithfully,
> <Authorised signatory, designation>

**Status:** Evidence: NOT-PUBLIC. Decision: **PARKED** (ADR-D-13). Delivery: **no dependency.** Integrations proceed from public detail plus adapters and simulators; the drafts are reused only after development, when a department adopts the product.

## IX.7d "Non-public" items: what was found in public sources (2026-11-04)

**Method:** only public, legitimate sources were used: official portals, official FAQs, government RFPs and circulars, and open-source government projects. No login was used, no protection was bypassed, and no leaked or confidential copies were used.

**Availability legend:**
- **PUBLIC-DETAIL:** enough detail found publicly to design against.
- **ON-REQUEST:** the full document exists but is issued only after registration; the request route is in IX.7c.

| Item | What was found (source) | Availability | Design consequence |
|---|---|---|---|
| **PFMS integration (GOV-8, RQ-1)** | Official PFMS DBT FAQ on the CGA GIFMIS site.<br>**PFMS provides:** a System Integration document, a sample SOP, an API document, and Annexure-I and II forms (IP details, **SSID**, payment mode, scheme details).<br>**The external system must:** write its own integration SOP, give IPs for **SFTP whitelisting and API access**, and **host its own UAT SFTP server**; PFMS hosts the production server.<br>**SSID** = unique ID of the external system; several schemes can share one SSID, each mapped to it; changing the integration model need not change the SSID.<br>**Four integration models:** (1) MIS only, (2) MIS + payment, (3) through the PFMS UI, (4) State IFMIS integration. Sources: https://gifmis.cga.gov.in/SitePages/doc/PFMS_DBT_FAQ.pdf, and a CGA/DBT workshop deck: https://dbtbharat.gov.in/data/documents/PFMS-%20National%20Workshop%20DBT_PFMS%2022.07.16%20Final.pdf | Process and roles **PUBLIC-DETAIL** (official FAQ). File/API formats **ON-REQUEST** (RQ-1) | R58 updated: support **both SFTP and API** modes. Provide a UAT SFTP endpoint in the pilot. SSID and scheme mapping in configuration. Choose model 2 (MIS + payment) or model 4 (via State IFMIS) per department |
| **PFMS direction of travel** | A public-sector bank's public RFP requires **API-based PFMS integration per the latest PFMS Office Memorandum, with API + legacy SFTP in parallel during transition, and an ISO 20022 architecture**. Source: https://www.jkgrameen.bank.in/adminpanel/files/10820266930103.pdf (secondary-official: a government-owned bank's tender) | PUBLIC-DETAIL (secondary) | R58: build the connector so file and API transports share one canonical payment model, ready for ISO 20022 messages |
| **State IFMS / treasury integration (RQ-3)** | **Open-source reference implementation:** eGov Foundation's DIGIT Works / MUKTASoft **IFMS Adapter** for **Odisha IFMS Just-in-Time (JIT) APIs**.<br>Endpoints: `webservices/jitfs/v1.0/authenticate` and `webservices/jitfs/v1.0/get-jit-service-data`.<br>Service-code jobs: VA (virtual allotment), PI (payment instruction posted from bill + payment), PD (polls payment results), FD (bulk failures of the last 72 h).<br>Client credentials are shared offline as master data. Sources: https://works.digit.org/digit-works/programmes/muktasoft-v1.0/deployment/muktasoft-services/ifms-adapter, https://docs.digit.org/works/reference-implementations/muktasoft-v2.2/deployment/muktasoft-services/ifms-adapter. Rajasthan IFMS overview (NIC informatics, Jul 2025): integration with RBI e-Kuber and PFMS — https://informatics.nic.in/files/websites/july-2025/ifms-rajasthan.php | **PUBLIC-DETAIL for Odisha** (open-source docs). Other states **ON-REQUEST** (RQ-3) | The P1 pack's treasury connector follows the same pattern: post PI → poll status → failure feed, with operationId reconciliation (VI.3). The DIGIT adapter is a design reference; its licence is to be checked before reusing any code |
| **Parichay / JanParichay onboarding (GOV-6, RQ-2)** | Parichay (G2G) official site: REST APIs, SAML 2.0 and OAuth 2.0 (read directly in Chrome on 2026-11-04); MFA (Parichay Authenticator); 4.1K+ integrated services; a **staging environment exists** (parichay.staging.nic.in).<br>JanParichay partner portal (G2C), official: onboarding = log in → register the organisation → approval (up to 48 h) → choose the integration method → "Requirements to Avail JanParichay" form → receive a **JanParichay Proxy** → implement login/logout/timeout/token-validation/handshake → support checklist → go live. **"Integration User Manual v1.3"** is offered from the portal's FAQ/Help.<br>The NIC MeriPehchaan PDF lists the integration approaches: OAuth 2.0 / SAML 2.0 / OpenAPI. Sources: https://parichay.nic.in, https://jppartners.meripehchaan.gov.in/, https://informatics.nic.in/uploads/pdfs/302101aa_34_36_egov_meripehchaan_compressed.pdf | **PUBLIC-DETAIL** for the process (G2C fully, G2G partly). Credentials and metadata **ON-REQUEST**: after registration (G2C) or via NIC (G2G, RQ-2). The v1.3 manual PDF was not fetched in this pass | R50: SAML 2.0 + OAuth2 adapters; implement token validation, session timeout and a logout handshake; plan for a proxy component in the JanParichay model |
| **eSign ASP onboarding (GOV-7, RQ-5)** | Official CCA documents: eSign API **v2.1** spec; ESP empanelment guidelines; the empanelled ESP list.<br>Rules: only Certifying Authorities may be ESPs; an external agency becomes an **ASP**; an ASP may use eSign only for applications it owns or operates; **the signer's consent and its logs stay with the ASP**; the XML request must be **digitally signed**, over a secured channel.<br>Onboarding: application form + documents + agreement with the chosen ESP → pre-production testing → public key certificate mapping → production. Sources: https://www.cca.gov.in/sites/files/pdf/ACT/eSign-APIv2.1.pdf, https://www.cca.gov.in/sites/files/pdf/esign/CCA-ESP.pdf, https://www.cca.gov.in/node/81, https://cca.gov.in/eSign_service_faq.html | **PUBLIC-DETAIL** (official CCA). Commercial agreement ON-REQUEST (RQ-5). The "integrate all ESPs within one month" rule is **secondary only, unverified** | R57: the ASP role is held by the department or operator. Store the consent and log evidence in the audit (R42); sign requests; support multiple ESPs |
| **Aadhaar Sub-AUA (GOV-6, RQ-4)** | Earlier pass: UIDAI rules allow Aadhaar authentication only through an approved AUA/KUA; no biometric storage. API spec version not confirmed in this pass | Rules PUBLIC-DETAIL (earlier pass). Agreement ON-REQUEST | Unchanged: Sub-AUA through the department only |
| **DLT registration (GOV-10, RQ-6)** | TRAI 12 Feb 2025 regulation (earlier pass): Government Message category, "-G" header, template and URL whitelisting | PUBLIC-DETAIL (rules). Registration ON-REQUEST (by the department's PE) | R60 unchanged |

**Net effect:**
- Of the six "non-public" items, **all six now have public process detail**. Odisha IFMS also has public API detail.
- Only the **exact PFMS file/API schemas, other states' IFMS specs, credentials and commercial agreements** remain ON-REQUEST, and they become available once a department sponsors the integration (G1).

Sources (search results consulted):
- PFMS FAQ (GIFMIS): https://gifmis.cga.gov.in/SitePages/doc/PFMS_DBT_FAQ.pdf
- DBT SOP: https://dbtbharat.gov.in/data/documents/Standard-Operating-Procedures.pdf
- J&K Grameen Bank RFP: https://www.jkgrameen.bank.in/adminpanel/files/10820266930103.pdf
- DIGIT IFMS adapter (v2.0): https://works.digit.org/programmes/muktasoft-v2.0/deployment/muktasoft-services/ifms-adapter
- Odisha IFMS 3.0 RFP: https://ct.odisha.gov.in/sites/default/files/2024-03/RFP-3395_15.03.2024_IFMS-3.0.pdf
- Parichay on NIC: https://www.nic.gov.in/project/parichay/
- CCA eAuth: https://cca.gov.in/sites/files/pdf/esign/CCA-EAUTH.pdf
- Protean eSign FAQ: https://cms.proteantech.in/sites/default/files/2023-09/FAQ_1.pdf

## IX.7e GOV-1…GOV-12: FINAL status (2026-11-04)

**Evidence passes behind this table:**
- (1) official-source research: Appendices B and C;
- (2) Chrome reading of official pages, incl. India Code (new domain indiacode.gov.in), PIB and guidelines.india.gov.in: Appendix D;
- (3) public PDFs downloaded and text-extracted / **OCR'd** (Tesseract eng+hin): SDC guidelines + annex, Government e-mail policy, four TRAI directions, MeitY cyber-security procurement order;
- (4) public-source search for "non-public" items: IX.7d.

No login, CAPTCHA or other protection was bypassed. When a search engine showed a CAPTCHA, the search stopped there.

| Item | FINAL status | What is now established (official source) | Design status |
|---|---|---|---|
| GOV-1 Languages | **VERIFIED** | Official Languages Rules 6/11 (bilingual Hindi + English); GIGW bilingual and Unicode; **Eighth Schedule = 22 languages** (MHA statement via PIB; 2025 Lok Sabha answer) | Complete (R54). The state language per deployment is configuration |
| GOV-2 GIGW / accessibility | **VERIFIED for GIGW; PARTIAL for IS 17802** | GIGW 3.0: *"ensures conformity with Level AA of WCAG 2.1"* (guidelines.india.gov.in). RPwD (Amendment) Rules on ICT accessibility dated **10 May 2023** (listed by DEPwD); the G.S.R. number and IS 17802 wording are not read (the PDF host timed out) | Complete (R55: WCAG 2.1 AA floor + IS 17802 target). The missing citation is parked (PK-01) |
| GOV-3 Security audit | **VERIFIED** | CERT-In: empanelled audit before hosting, yearly and after major change; 6-hour incident report; 180-day logs in India | Complete (R51, R52) |
| GOV-4 DPDP | **VERIFIED** | Act 2023; Rules G.S.R. 846(E), 13 Nov 2025 (core obligations from about May 2027); **Data Protection Board established by G.S.R. 844(E)**, four members fixed by **G.S.R. 845(E)** (both 13 Nov 2025). Member appointments not found | Complete (R53). Appointments are parked (PK-02); no design impact |
| GOV-5 Hosting | **VERIFIED for design** | MeitY **State Data Centre guidelines** (text extracted): SDC objectives cover reliability, availability, scalability, **backup, redundancy, survivability and disaster management**, security and confidentiality, and third-party monitoring/audit of the SDC. MeitY cloud empanelment with STQC audit; data residency in India. GIGW 3.0: security audit clearance before production + periodic off-site backup | Complete (R56; HA/DR per ADR-D-12). No published NIC rule on hosting location: parked (PK-03) |
| GOV-6 Identity / SSO | **VERIFIED for protocols; onboarding NOT-PUBLIC** | Parichay (official site, read): REST, SAML 2.0, OAuth 2.0, MFA, staging exists. Onboarding through servicedesk.nic.in (login). JanParichay partner onboarding steps are public (IX.7d). Aadhaar only via AUA/KUA | Complete (R50: SAML/OAuth adapters + local IdP for air-gapped). Onboarding metadata parked (PK-04) |
| GOV-7 Digital signatures | **VERIFIED** | **IT Act s.3, 3A, 5 and Second Schedule read on India Code.** The Second Schedule has two electronic-signature techniques: **Aadhaar/e-KYC e-authentication (G.S.R. 61(E), 27-1-2015)** and **signing through a trusted third party (S.O. 3472(E), 29-9-2020)**. eSign API v2.1 + CCA ESP/ASP rules (IX.7d); CSMOP 2022: DSC/eSign mandatory on e-files for Dealing Officer and above | Complete (R57) |
| GOV-8 Payments | **VERIFIED for process; schemas ON-REQUEST** | PFMS FAQ (official): integration documents, SSID, four models, SFTP + API, UAT server; SNA-SPARSH flow; Odisha IFMS JIT API (open-source reference); trend to API + ISO 20022 | Complete as an adapter + simulator (ADR-D-13). Exact schemas parked (PK-05) |
| GOV-9 Documents | **VERIFIED** | DigiLocker **Rule 9A inserted by G.S.R. 111(E), 8 Feb 2017** (the original rules were G.S.R. 711(E), 21 Jul 2016); Issuer Push v1.7.2 / Pull v1.3 APIs; API Setu | Complete (R59) |
| GOV-10 Notifications | **VERIFIED** | TRAI directions (OCR'd), dated 20 Aug 2024, 30 Aug, 28 Oct and 30 Nov 2024:<br>(a) only **whitelisted URLs / APKs / OTT links / call-back numbers** in templates; for URLs with fixed and variable parts, the fixed part is whitelisted; effective 1 Oct 2024 after extension;<br>(b) **PE→TM chain traceability**; messages whose chain is undefined or mismatched are **rejected from 11 Dec 2024**.<br>Plus the TCCCPR amendment of 12 Feb 2025 (Government Message, "-G" suffix). **Government e-mail policy** (text extracted): forwarding official mail to personal accounts is not allowed; OTP/mobile number mandatory for security; encryption mandatory for sending. The public SMS route for departments is **Mobile Seva (MeitY/C-DAC)** | Complete (R60: DLT registry, URL whitelist check, PE-TM chain declaration; Mobile Seva / NIC mail adapters). NIC SMS console (login) parked (PK-06) |
| GOV-11 Records / RTI | **VERIFIED** | **Public Records Rules 1997 (G.S.R. 43, 9 Jan 1997), Rule 9** (scanned text read): January review against the retention schedule; **no destruction of records older than 25 years without appraisal**; a destruction list (Form 6) kept permanently; destruction by burning or shredding. CSMOP 2022 A/C categories; RTI timelines | Complete (R61, X.2.3) |
| GOV-12 Procurement | **VERIFIED for rules; software method NOT FOUND** | GFR Rule 149 (GeM mandatory where available); PPP-MII revised 19 Jul 2024 (Class-I ≥50 %, Class-II ≥20 %). **MeitY cyber-security products order 2019** (text extracted): minimum local content **60 % of total cost**; licence/royalty paid to third parties **≤ 20 %**; **IP owned by the Indian company**, with rights to use, distribute and modify. No MeitY method for general software found | Complete (R62: document IP ownership, cost-based local content and royalty share). The general-software method is parked (PK-07) |

**Final count:**
- **VERIFIED:** 8 items (GOV-1, 3, 4, 5, 7, 9, 10, 11).
- **VERIFIED with a parked sub-item:** 4 (GOV-2, 6, 8, 12).
- **Unresolved items that block design or development:** **0** (ADR-D-13).

## IX.8 Pilot acceptance criteria (draft, for owner approval)

| ID | Criterion | Pack | Tests |
|---|---|---|---|
| AC-01 | A bill passes initiator → selected checker → approver chosen by amount band; payment acknowledgement reconciles; audit shows seat, person and rule version | P1 | T16, T17, T19, T27 |
| AC-02 | Amount or beneficiary changed after approval invalidates the approval and forces re-approval | P1 | T16 |
| AC-03 | Payment succeeds but the response is lost → `UNKNOWN_OUTCOME` → reconciliation resolves it without a duplicate payment | P1 | T02, T27 |
| AC-04 | A file moves up three levels, is returned two levels, is resubmitted and approved; officer on leave → additional-charge holder acts, shown as "acted for" | P2 | T15, T17 |
| AC-05 | Grant application fails eligibility after verification → rejection with reasons; partial disbursement and account failure handled | P3 | T11, T27 |
| AC-06 | Licence SLA breach triggers escalation; fee paid but callback lost is reconciled; a signed certificate is issued (simulated eSign) | P4 | T22, T47 |
| AC-07 | The same pilot runs on Oracle, SQL Server and PostgreSQL with identical results | All | T18, T31 |
| AC-08 | Workers in Java, .NET, PHP, Python and Node complete tasks with the same lease, idempotency and error semantics | All | T01–T04, T18 |
| AC-09 | A tenant/department cannot see another's data through UI, API, list, count or export | All | T13, T14 |
| AC-10 | Hindi and English UI; a first-time officer completes a task without help (usability session) | All | T32 |
| AC-11 | Air-gapped install from offline bundle on a State-DC-like VM | All | install test (new, T50) |

## IX.9 Stage A exit checklist

| Item | Status |
|---|---|
| Product envelope recorded (IX.1–IX.2) | Done |
| Shared core + 4 packs defined (IX.3–IX.4) | Draft, needs owner review |
| Organisation/role/delegation model (IX.5) | Draft, needs owner review |
| Deployment and SDK matrix; D-4 decided (IX.6) | Done |
| Government constraints researched (IX.7) | **Done** (IX.7a): 2 VERIFIED, 10 PARTIAL, gaps listed; R50–R62 added |
| Acceptance criteria approved (IX.8) | **Done**: approved by the owner on 2026-11-04 (X.0) |
| G1: named first department/integrator + accountable person | **Moved** by ADR-D-13: no longer a Stage A exit condition. It becomes a go-to-market gate after development. Stage A exits on the approved reference-pilot scope |

---

# Part X — Finalisation (2026-11-04 → 2026-11-06)

> **Purpose:** close the remaining decisions and publish the normative baseline. The target is an approved specification baseline by Friday 2026-11-06. **This is a specification baseline, not built software**; implementation starts with Stage B.

## X.0 Owner decisions, batch 1 (2026-11-04)

| ADR | Decision | Status | Consequences |
|---|---|---|---|
| **AC approval** | AC-01…AC-11 (IX.8) **approved as drafted**. Later changes go through change control | **DECIDED** by the owner | IX.9 item "Acceptance criteria approved" → Done. AC-11 maps to the new test T50 (air-gapped install) |
| **ADR-D-5 API** | **REST/OpenAPI lifecycle first.** gRPC streaming only after a measured need, with the R9 rules (VI.3, A-1) | **DECIDED** by the owner | VI.3 stream rules apply only when gRPC is added. The pilot uses REST long-poll. The SDK contract tests run over REST |
| **ADR-D-11 Sustainability** | All of: **(1) implementation/migration services; (2) annual support / AMC (supported distribution with SLA); (3) managed/hosted service on MeitY-empanelled cloud; (4) workflow packs + training/partner certification.** The owner adds: *"If anything else needed as per govt policy then that will also include"* | **DECIDED** by the owner | (a) GeM SKUs for services, support, hosted service and training (R62). (b) The hosted offer only via a MeitY-empanelled CSP (GOV-5, R56). (c) The commercial offers must not hold back the security, tenancy or audit features of the open core (Part III §6.5). (d) **Open action:** check government procurement policy for any other required offer or condition (e.g. ATS/AMC norms, Make in India class, startup relaxations), and add it when found (GOV-12) |
| **ADR-D-12 HA/DR** | **RPO ≤ 5 minutes, RTO ≤ 1 hour, 99.5 % availability**: asynchronous database replication to a DR site + backups; failure boundary = loss of the primary site/host. RPO 0 is **not** claimed | **DECIDED** by the owner | Replaces the 99.95 % / RPO 0 target in Part I §14.6 for the pilot and first releases. Tests T26 (failover) and T27 (restore + reconciliation) measure against these numbers. Restore runbooks must reconcile external effects (VI.3 operationId) |

**Decision register after batch 1:**

| ADR | Status |
|---|---|
| D-1 base engine | **PROVISIONAL** until the Stage B spike |
| D-2 delivery sequence | DECIDED (VIII.9) |
| D-3 first market | DECIDED (VI.7) |
| D-4 SDKs | DECIDED (IX.6) |
| D-5 API | DECIDED (X.0) |
| D-6 scripts | CLOSED (by D-8) |
| D-7 migration tool | DECIDED (VI.7) |
| D-8 sandbox | DECIDED (VI.7) |
| D-9 market claim | CLOSED (VIII.2, M06) |
| D-10 success metric | CLOSED (VIII.2, M06) |
| D-11 sustainability | DECIDED (X.0) |
| D-12 HA/DR | DECIDED (X.0) |

**11 of 12 are closed.** Only D-1 remains, and it is closed by experiment, not by opinion.

---

## X.0b ADR-D-13: Self-reliant development, with no government-side dependency (DECIDED 2026-11-04)

| Field | Content |
|---|---|
| Question | May development depend on documents, credentials or decisions from government bodies (RQ-1…RQ-6, a named buyer for G1)? |
| Owner's decision (paraphrased from Hinglish) | Nothing will come from government now; data from government arrives only when the product is ready end to end. Keep no request list that creates a development dependency on the government side. Do everything ourselves. Whatever cannot be found must be searched for in every possible way; if it is still not found, park it in a list, but do not make it a development dependency |
| Chosen | **Self-reliant development.** Every integration and requirement is built from **openly available sources**. Missing details are **parked**, never blocking |
| Owner / date | Project owner, 2026-11-04 |

### Rules that follow

1. **Adapter + simulator for every government integration**: PFMS, State IFMS/treasury, Parichay/JanParichay, eSign/DSC, DigiLocker, DLT SMS / NIC mail, payment gateways, Aadhaar (Sub-AUA). Each one is:
   - a pluggable adapter whose formats live in **configuration and mapping files**, not code;
   - built from the public detail in IX.7a/IX.7d;
   - delivered with a **simulator** that reproduces the public behaviour: SFTP file exchange and status files for PFMS; the JIT flow (authenticate → PI → PD → FD) for IFMS; SAML/OAuth IdP for Parichay; eSign API v2.1 request/response; Push/Pull for DigiLocker; DLT template checks.
   - When the real specifications arrive later, only the mapping or configuration changes. That is an **adoption task, not a development dependency**.
2. **Stage gates are re-based:**
   - Stage A exits on the **approved reference-pilot scope** (IX.2–IX.9, AC approved in X.0).
   - **G1 (named buyer) moves to a go-to-market gate after development.**
   - Stages B–D run entirely on synthetic data and simulators.
   - Stage D also validates against any real environment that is openly available (e.g. a public sandbox).
3. **Parked list.** IX.7c (RQ-1…RQ-6) plus every **ON-REQUEST** item in IX.7d and every gap still open after the Chrome/OCR pass are recorded in **X.6 Parked items**. Each record has: what is missing, the public substitute used, the adapter setting that will absorb it, and who could supply it later.
4. **Continuous search.** For parked items, open-source and official sources keep being searched (see the method in IX.7d). Anything found is moved from Parked to the adapter configuration.
5. **Unchanged:** the security, compliance and audit requirements (R50–R62) remain build requirements. They are met by design and are evidenced at go-to-market (e.g. a CERT-In audit of the finished product).

**Effect:** development has **zero external blocking dependencies**. The only open decision, D-1, is closed by our own Stage B spike.

## X.0c ADR-D-14 and ADR-D-15: universal open-source product; four Tier-1 databases (DECIDED 2026-11-05)

| Field | Content |
|---|---|
| Owner's decision (paraphrased from Hindi) | The government processes must be handled, but Sangam will also be our own product that **anyone** can use, and it will be **completely open source**. Everything Flowable and Operaton/Camunda offer up to the premium level of their licences will be **free** in Sangam: UI and backend. Databases: PostgreSQL, Oracle, MS SQL **and MySQL**, so it can be built on any of them. A **universal solution** combining both engines' capabilities. Their weaknesses are eliminated to zero, and their strengths are included and improved end to end. Nothing is left out. |
| **ADR-D-14 Product scope** | Sangam is a **universal, fully open-source workflow platform**. Indian government processes (IX.4, GOV-PATTERNS) are packs on top of it, not its limit. **Every capability sold in premium or commercial editions of Camunda 7/8, Flowable and Operaton** has a free Sangam equivalent: modeller, cockpit/operations, tasklist/work UI, admin/identity, analytics/optimisation/process mining, connectors, AI agents, multi-tenant administration. The premium-feature catalogue and the new requirements R63+ come from `spike/results/PREMIUM-AND-UNIVERSAL-SCOPE.md` (in progress). |
| **ADR-D-15 Databases** | **Tier 1 = PostgreSQL, Oracle, SQL Server, MySQL** (MySQL promoted from Tier 2; supersedes R17 and CF-11 for MySQL). MariaDB and DB2 stay Tier 2. Licence rule unchanged (LIC-4): MySQL Connector/J is not bundled; the MariaDB driver is the shipped driver for MySQL where compatible, else Connector/J is customer-supplied. MySQL is added to all spike DB runs (queue Q1–Q3). |
| Consequences | (1) The base-engine evaluation adds MySQL and a "premium-feature buildability" view: how much of the premium catalogue each base already offers free, e.g. Operaton webapps-neo vs Flowable with no OSS UI. (2) The universal patterns UP-01… extend the test catalogue beyond government. (3) The effort re-estimate (M09) must include the premium-equivalent UI and tooling. |
| Status | Decision: DECIDED (owner, 2026-11-05). Delivery: SPECIFIED. |

## X.0d ADR-D-1 FINAL and ADR-D-16…D-19: base engine, integration model, licence, repository, Stage C (DECIDED 2026-11-05)

| Field | Content |
|---|---|
| Owner's decision (paraphrased from Hindi) | Testing stops: the Stage B evidence is enough. **Operaton is final as the base engine.** All of Flowable's strengths are to be brought into Operaton/Sangam; the gap list is `spike/results/FLOWABLE-INTO-OPERATON.md`. Answers to the doubts raised: dependency + plugins; Apache 2.0; public GitHub now; run all Stage C work in parallel; publish after removing local paths. |
| **ADR-D-1 FINAL** | Base engine = **Operaton 2.2.x** (XI.10: 85.2 vs 72.1). The "owner may override" clause is closed. Flowable is no longer a candidate. Its adapter and results stay in `spike/` only as evidence. |
| **ADR-D-16 Integration model** | Sangam consumes Operaton as **Maven dependencies** and adds behaviour through **plugins and SPIs**: process-engine plugins, parse listeners, command interceptors, history handlers, the DMN SPI and the product layer. A change to the engine source is allowed only when no extension point exists. Each such change is recorded in the patch ledger (P-n), kept minimal, and **offered upstream** to Operaton. The first example is P-1 (MySQL datetime precision). Sangam does not maintain a hard fork. |
| **ADR-D-17 Licence** | **Apache License 2.0** for all Sangam code, matching Operaton and Flowable. Ported code keeps its original headers and is marked modified (NOTICE). The rules LIC-1…11 stay in force: no GraalJS; no bundled Oracle, Db2 or MySQL drivers; Liquibase pinned at 4.33.0. |
| **ADR-D-18 Repository** | Public at **https://github.com/sqamentor/sangam** from now. Third-party checkouts (`reference/`), build output and machine-specific run logs are excluded (`.gitignore`). Local paths were removed before publishing. |
| **ADR-D-19 Stage C** | Every Stage C workstream starts **in parallel** on the D-16 model: core/product layer, REST/OpenAPI, SDKs, UI, Flowable-strength ports, government packs and the four-database CI. Heavy runs still go through the shared machine lock. |
| Pending amendments carried into Stage C | (1) operationId derived from the business key (T35). (2) Adopt R63–R84 (PREMIUM-AND-UNIVERSAL-SCOPE). (3) MySQL conditions: P-1 precision patch and READ COMMITTED isolation. (4) GP-09 rule: one instance per item; no large multi-instance. (5) Jackson ≥ 2.22.3. (6) A-10…A-16 (XI.10.3). |
| Status | Decision: DECIDED (owner, 2026-11-05). Delivery: SPECIFIED. |

## X.1 Normative requirements R1–R62

> **Purpose (M01, VIII.7 #1):** one authoritative statement per requirement, so an implementer does not have to reconcile Parts I–IX.
>
> **Precedence applied:** Part VI (VI.1–VI.6) replaces R3, R4, R9, R19, R23 and R35; amendments A-1…A-6 (VIII.3) apply on top; Part VII §7 is the closure map for R1–R49; Part V accepted corrections override Parts I–IV; R50–R62 come from IX.7a. Decisions D-3, D-4, D-7 and D-8 (VI.7, VIII.5, IX.6) are applied where they fix a requirement's content.
>
> **Status:** Evidence and Decision as in the cited sources; Delivery for every row is **SPECIFIED** only. Nothing is implemented and no test has been executed.
>
> **Columns:** "Stage" follows the VIII.9 Phase → Stage mapping (R50–R62: IX.7a consequence 1). "Tests" uses the T01–T50 catalogue (III §16, VI, VIII.4, IX.7a). "add test" marks a test that Part VII §7 asks for and that has not been named yet. "Spec pending" marks requirements that VIII.7 #2 still lists for detailed specification.

| REQ | Title | Normative statement (current wording after all overrides) | Failure behaviour | Depends on | Stage | Tests | Source sections |
|---|---|---|---|---|---|---|---|
| R1 | Relay-driven wake-up | Wake-up notifications MUST come only from the outbox relay: one batched `NOTIFY` per claim cycle, no payload, never inside a command transaction. A 1 s poll MUST stay authoritative so that correctness survives lost wake-ups. Tenant-sensitive data MUST NOT go on broadly visible channels, broadcast storms SHOULD be avoided, and the benefit SHOULD be measured. | A lost notification, listener restart or notification outage is recovered by the next poll; no work is lost. | R4 | C | T06–T08, T25, T31, T45 | II §19 R1; III E11, §13; VI.2.3 step 6; VII §7; VIII.4 |
| R2 | Per-DB acquisition SQL | Acquisition MUST use dialect-specific SQL (Oracle two-step; SQL Server UPDLOCK+READPAST+ROWLOCK on an index seek; MySQL lock-footprint tests) in its own short transaction that never takes L2. DB and isolation versions MUST be pinned. Correctness, starvation, lock escalation and fairness MUST be tested, not just syntax. Autovacuum and xmin alerts MUST exist. | Contention errors are classified as retryable per VI.1.6 and retried. Vacuum or xmin lag raises an alert. | R3, R17 | C | T10, T24, T31 (on committed DBs) | II §19 R2; III §13; VI.1 rule 4, VI.1.6; VII §7 |
| R3 | Lock and transaction specification | **Replaced by VI.1.** Locks MUST be taken only in the order L1 (business-resource lease) → L2 (root rows, ascending id, `FOR UPDATE`) → L4 (job and external-task rows, version-checked). L3 is covered by L2 plus `REV_`; L5 is insert-only. The root set MUST be resolved first. The default is one root per transaction (`maxRootsPerCommand` = 20). Acquisition never takes L2. No remote call is allowed while L2 is held (`LockScope`). Limits: `lock_timeout` 2 s, `statement_timeout` 5 s, 10 s watchdog. Leases are rows, never open transactions (A-4). A per-definition `root_lock_wait_seconds` metric is kept, and one root is not designed for more than about 50 commands/s. | If an unlocked root is needed: abort and retry with the larger set. Retryable errors get up to 5 retries with jittered back-off (20 ms–1 s); after that, a job uses its retry/incident path and an API call returns `409 CONFLICT_RETRY_EXHAUSTED`. A remote call inside the lock throws `IllegalRemoteCallInLockScope`. | R2, R9, R17, R35 | C | T10, T33, T34 | VI.1.1–VI.1.6; VIII.3 A-4; III E05; VII §6.6, §7 |
| R4 | Outbox algorithm | **Replaced by VI.2.** Each command MUST insert one `OUTBOX` row plus one `PENDING` `OUTBOX_DELIVERY` row per destination, with `root_seq` allocated under L2 so that per-root order equals commit order. Relays MUST claim by state: head-of-line per (root, destination), SKIP LOCKED, `claim_epoch` fencing. They publish outside any transaction and mark `DONE` only with their own epoch. Consumers MUST deduplicate on `event_id`. A daily partition is dropped only when all its deliveries are `DONE` or `SKIPPED`. With DB-per-tenant, each tenant DB has its own outbox and relay and there is no cross-DB transaction (A-5). | Lost claim: the mark changes 0 rows and a duplicate publish is allowed. Expired claims are reset to `PENDING` by a sweeper. After 10 attempts the delivery is `PARKED`: `ordered=true` blocks later events (alert; audited requeue or skip), `ordered=false` becomes `SKIPPED` with a gap record. A partition with open rows is kept and an alert fires. | R3, R38, R40 | C | T06–T08, T25, T35 | VI.2.1–VI.2.5, VI.2.7; VIII.3 A-5; III E04; VII §6.5, §7 |
| R5 | JDK baseline | The baseline MUST be JDK 25 LTS. The supported framework, driver and deployment matrix and its patch support MUST be certified and published. | Not specified. Combinations outside the published matrix are unsupported. | R17, R32 | B (minimal) | T28, T31 + build/runtime matrix | II §19 R5; I §16; III §13; VII §7; VIII.9 |
| R6 | Script isolation | **Per D-8:** Release 1 MUST have no in-engine scripts; the engine evaluates FEEL only. Script tasks (JavaScript, Python) MUST run in an out-of-process script worker using the normal lease protocol: container, seccomp, no network unless allowed, cgroup CPU/memory/time limits. Rhino and GraalJS MUST NOT be used. In-process WASM (Chicory) is a later option, only after its fuel/interrupt limits are verified. | A hostile or runaway script is bounded by the container limits and handled as a worker task under VI.3. | R9, R23, R24 | C (secure defaults) → D (full) | T23; add test (escape/isolation) | II §19 R6; V.2 X-7; V.3; VI.7 D-8; VIII.5; VII §7 |
| R7 | Native-light profile | Native image MUST be limited to a light/worker-gateway profile. Broader native-parity promises are withdrawn, and the profile's supported APIs and features MUST be published. | Not specified. Features outside the published profile are unsupported. | R5 | B (Phase 0 item) | add test (profile compatibility/build evidence) | II §19 R7; III §13, §15.1 #7; VII §7; VIII.9 |
| R8 | DMN engine scope | The supported DMN engine scope MUST be chosen deliberately: measure the Drools footprint with jDMN as fallback, keep pinned TCK results (jDMN 3391/3391, Drools 3388/3391), and test modeler coverage and semantic edge cases. Three engines SHOULD NOT be fully supported without demand. | Not specified. | R44, R46, D-1 | C | T18–T20; pinned TCK results | II §19 R8; I §3.4; V.2 X-8; III §13; VII §7 |
| R9 | Worker task lease lifecycle | **Replaced by VI.3 + A-1.** States: AVAILABLE → OFFERED → (ack) → LOCKED. A closed stream MUST release only OFFERED tasks (10 s offer timeout); LOCKED work keeps its lease until expiry, release or completion. The SDK MUST start the handler only after the LOCKED confirmation with `lockVersion`. Every call carries `(taskId, lockVersion, attemptId)`. Leases are per topic (default 5 min, max 24 h), with heartbeats at lease/3 that carry `cancelRequested`. All lease times use the engine DB clock. A reconnecting worker that still holds a valid lease resumes heartbeats, with no re-offer. | Stale `lockVersion`: `409 STALE_LEASE`, nothing changes. Lease expiry: AVAILABLE (`lock_version`+1, attempt+1), or UNKNOWN_OUTCOME for `effect=irreversible`. Fail with retries=0: INCIDENT. A cancel not acknowledged before expiry: UNKNOWN_OUTCOME. | R3, R20, R21, R36, D-5 | B (proof) → C | T02, T03, T04, T36 | VI.3.1–VI.3.3; VIII.3 A-1; III E06; V.3; VII §6.1, §7 |
| R10 | FEEL engine and null semantics | One pinned FEEL engine MUST be bound per model version. Strict null (null → incident) MUST apply only where a Boolean routing result or a mandatory output is required; valid optional nulls stay valid. Security fixes MUST go through regression suites and compatibility modes, not by keeping old vulnerable evaluators. | Null in a Boolean-routing or mandatory-output context raises an incident. Other contexts follow their declared behaviour. | R33, R43 | C | T18, T19; add test (null and upgrade corpus) | II §19 R10; III E18, §13; V.3 (Revise); VII §7, §8.2 |
| R11 | JSON limits | JSON input MUST be limited in depth, size, total request/model/payload growth, numbers, references and decompression, and the limits MUST be enforced before expensive parsing. Jackson default typing MUST be disabled. | Over-limit input is rejected with bounded resource use. | R24, R28 | C → D | T18, T23 | II §19 R11; III §13; VII §7 |
| R12 | Schema migration | Schema changes MUST run as one controlled migration job with a session lock (a Kubernetes Job plus a plain VM/Docker command), with a per-DB online-safe change allowlist and safe recovery from a failed migration. **Per D-7:** Liquibase is pinned at 4.33.0 with per-dialect formatted-SQL changelogs, and 5.x (FSL) MUST NOT be adopted before legal review. | A concurrent run is blocked by the session lock. A failed migration follows the documented recovery procedure. | R17, R32, D-7 | B (skeleton) → C | T28, T29 | II §19 R12; III §13; V.2 X-6; VI.7 D-7; VII §7; VIII.9 |
| R13 | Telemetry | OpenTelemetry MUST use parent spans for synchronous continuations and links for async jobs, timers and workers, with explicit context in the core and per-operation semantic conventions. Cardinality MUST be bounded and sensitive data suppressed. Outbound telemetry is off by default (R56). | An exporter failure MUST NOT affect core engine behaviour. | R56 | B (skeleton) → C | T24, T31, T49 | II §19 R13; III §13; VII §7; VIII.4 |
| R14 | AI / MCP tools | MCP MUST be read-only by default, and write tools MUST create approval tasks. Reads, fields, searches and data sent to model providers MUST be scoped. Tool output is untrusted (taint labels are not enforcement). Approvals MUST be bound to tool identity, arguments, resource and expiry, and authority rechecked at execution. Per-step and per-workflow budgets, cancellation and an agent-card allowlist MUST exist. | A changed payload invalidates the approval. An uncertain tool effect becomes an unknown outcome (R36/R37). | R19, R36, R37 | E | T12–T17, T23; add test (AI-specific) | II §19 R14; III §10, §13; VII §7, §9.2 |
| R15 | Process-mining export | XES MUST be the primary export. OCEL is deferred; when offered it MUST be validated (pm4py in CI) with defined business-object identities, relationships, lifecycle timestamps and tenant-aware export. "Full OCEL" MUST NOT be claimed without versioned conformance evidence. | Not specified. | R42 | E | add test (export semantic fixtures) | II §19 R15; III §13; VII §7, §11.2 X14 |
| R16 | Contract-driven schemas | One canonical semantic schema MUST generate the event and API types (JSON Schema/proto), with AsyncAPI for documentation and contract tests and automated consistency across proto, OpenAPI and AsyncAPI. | An incompatible protocol or schema change fails the compatibility tests. | R18, D-5 | C | T48 | II §19 R16; III §13; VII §7; VIII.4 |
| R17 | Database tiers | Tier 1 MUST be PostgreSQL, Oracle and SQL Server (D-3; "PostgreSQL only" rejected), in the pilot and release checks from Stage C. Tier 2: MySQL, MariaDB, DB2 (nightly). Experimental: CockroachDB, YugabyteDB. H2 for tests only. Exact versions, licensed CI images and hardware MUST be declared. | Not specified. A DB outside the declared tiers is unsupported. | D-3, R2, R12 | B (skeleton) → C | T10, T26, T28, T29, T31 (per committed DB) | II §19 R17; III §13; V.4b; VI.7 D-3; VIII.9 cond. 2; IX.6; VII §7 |
| R18 | SDK tiers | **Per ADR-D-4 (IX.6):** Tier 1 (full worker lifecycle + test engine) MUST be Java, .NET, PHP, Python and TypeScript/Node. Tier 2 (generated, community-maintained): Go, Rust, Kotlin. Any other language uses the published REST/OpenAPI contract with the same lifecycle semantics. T01–T04 and T18 MUST run on every Tier-1 SDK, and promised PHP/.NET behaviour MUST NOT be silently dropped. | An SDK that diverges from the lifecycle semantics fails the contract tests and is not released at Tier 1. | R9, R16, R33, D-4 | C | T01–T04, T18, T28 | II §19 R18; III §13; V.4; IX.6 D-4; VIII.5; VII §7 |
| R19 | Authorization | **Replaced by VI.4 + A-3.** Each policy compiles to an object check and a SQL predicate; a list-visible policy that cannot compile to SQL is rejected. `AUTH_EFFECTIVE` is maintained in the same transaction as grants. Every read (list, count, search, export, dashboard, get) MUST use `AuthorizedQueryBuilder` (enforced by ArchUnit). Explicit deny wins (`NOT EXISTS`). Post-filtering a page is forbidden. Field masks apply to lists and objects alike. The tenant comes only from the authenticated identity. Revocation bumps `policy_version` (cache ≤ 5 s). Sensitive actions re-check inside the command transaction. Approvals are bound to the payload hash and version. History uses the same predicates. | A non-compilable list policy is rejected at deployment. A revoked permission blocks the sensitive action at execution. A request tenant id only filters inside the allowed set. A fast but wrong benchmark result fails. | R26, R28, R43 | B (spike on both engines) → C | T13–T17, T37, T38 | VI.4.1–VI.4.2; VIII.3 A-3; VIII.2 M10; III §10; VII §6.3, §7 |
| R20 | Fencing and heartbeats | Every lock MUST carry a fencing token (`lockVersion`), and stale completions are rejected. Lock time is set per job type, and heartbeats are required. The guarantee covers engine mutation only; remote effects rely on R36 operation identity and reconciliation. | A stale completion is rejected with `409 STALE_LEASE`. | R9, R36 | B → C | T02–T05, T27 | II §19 R20; III E01, §13; VI.3.2 rule 2; VII §7 |
| R21 | Expired-lease sweeper | A reset-expired sweeper MUST cover every job table, timers included. It MUST use DB-authoritative lease time, make only legal state transitions, fence concurrent sweepers and expose recovery metrics. | An expired lease goes back to AVAILABLE with `lock_version`+1, or to UNKNOWN_OUTCOME for irreversible effects or an unacknowledged cancel (VI.3). | R9, R20 | B → C (also D, with R48/R49) | T03, T04; add test (concurrent sweeper) | II §19 R21; III §13; VI.3.1; VIII.3 A-1; VII §7 |
| R22 | Benchmarks | Targets are per reference process and are hypotheses: R1 ≥ 2,000 PI/s; R5 ≥ 300 PI/s with p99 command latency < 50 ms; hand-off p99 < 100 ms, on a 16-vCPU app + 16-vCPU PG. Benchmarks MUST measure completed work with auth, audit and history on, and include backlog, realistic lifetime, durability, resource cost and every Tier-1 DB. Operaton and Flowable run on the same hardware. A public benchmark publishes raw data and limits. | Missed targets are published with the raw data, not hidden. | R17, R19, R42 | D | T24, T25, T31 | II §19 R22, §14.3; III §12.1, §13; V.4b; VII §7, §9.3 |
| R23 | Cancel / delete / erase | **Replaced by VI.5 + A-4.** Business cancel, technical delete and erasure are separate operations. Each needs its own permission (`CANCEL_INSTANCE`, `DELETE_INSTANCE`, `ERASE_DATA`) and a reason. Before rows are removed, OFFERED/LOCKED tasks MUST go to `CANCEL_REQUESTED` and a `TOMBSTONE` is written (kept until lease end + 30 days). Internal jobs are dropped silently. Each activity declares `onCancel` = finish-allowed / cancel-requested / compensate. Erasure scrubs payloads everywhere but keeps audit metadata, legal holds and open reconciliation items. An audit event lists the open operation IDs. | A late result that matches a tombstone gets `410 GONE_INSTANCE_DELETED` and is audited. If the result reports an effect, a `LATE_RESULT_AFTER_DELETE` reconciliation item opens. The instance is never revived. A lease that expires without a cancel ack becomes UNKNOWN_OUTCOME. | R9, R29, R36, R42, R53, R61 | C | T10, T11, T27, T39 | VI.5.1–VI.5.3; VIII.3 A-4; III E08; VII §6.4, §7 |
| R24 | XML hardening | One hardened XML factory MUST cover every parser, transformer, schema validator, diagram generator and resource resolver (DOM, SAX, StAX), with entity and size limits. Other factories are banned by Semgrep/ArchUnit. The CVE claim is verified separately from the code weakness. | Hostile XML is rejected without network or file access, with bounded resources. | R11 | C → D | T23; add test (parser inventory and fuzz corpus) | II §19 R24; III §10, §13; V.2 X-14; VII §7, §9.2 |
| R25 | No expressions in queries | Queries, filters and REST input MUST NOT evaluate arbitrary expressions. Saved filters use literal values plus fixed placeholders. Versioned, reviewed named-query handlers are a separate mechanism; both need bounded evaluation and permissions. | Expression content in query input is rejected. | R19 | C → D | T13, T14, T23; add test (named queries) | II §19 R25; III §13; VII §7 |
| R26 | Worker-bound operations | Job operations MUST be bound to the authenticated service principal, tenant, task lease and allowed topic; a worker name supplied by the client is not enough. Bulk operations need admin permission. | A call from a principal not bound to the lease or topic is rejected. | R9, R19 | B → C | T03, T13, T46 | II §19 R26; III §13; VI.4.1 rule 7; VII §7; VIII.4 |
| R27 | Connector egress | Connectors MUST enforce a tenant-scoped egress allow-list, block private and metadata ranges, limit redirects, pin DNS, authenticate callbacks and bound responses. Approved internal enterprise endpoints MUST be configured explicitly. | A disallowed destination, redirect, or forged or replayed callback is refused. | R26, R38 | C → D (breadth in E) | T23, T47; add test (SSRF) | II §19 R27; III §10, §13; VII §7; VIII.4 |
| R28 | Safe defaults | Defaults MUST be: authorization on, `queryMaxResultsLimit` 1,000, SKIP LOCKED on, retry `R3/PT1M` with jitter, and no SQL or stack traces in errors. Per-tenant quotas, bounded bytes, pagination and per-operation retry policy MUST also apply. Non-idempotent effects MUST NOT get automatic blind retries. | Exceeding a quota gives a bounded rejection. An irreversible effect goes to UNKNOWN_OUTCOME instead of retrying (VI.3 rule 5). | R9, R19, R36 | C | T01, T13, T23–T25 | II §19 R28; III §13; VII §7 |
| R29 | Retention and variable limits | Retention MUST be on by default (a TTL warning, not a refusal). Cleanup runs as a leader-elected service. Inline variables are at most 256 KB; larger values are stored as references. History is partitioned by removal time. Retention MUST also cover audit minima, active instances, legal holds, references, journals and non-SQL copies. **For government records R61 overrides generic TTL cleanup.** | Cleanup MUST NOT delete required evidence or held data merely because a TTL has passed. | R42, R61 | C | T25, T30 | II §19 R29; III §13; VII §7, §9.3; IX.7a GOV-11 |
| R30 | Bounded caches | Caches MUST be bounded, use weak classloader references, be keyed by tenant and model, and have invalidation and metrics. A 24 h soak under representative load is a gate, but it is not proof of leak freedom. | A cache or exporter failure must not change core behaviour. | R13 | D | T19, T24, T31, T49 | II §19 R30; III §13; VII §7; VIII.4 |
| R31 | Timer policy | Timers MUST use UTC due dates, explicit timezones on cycles, `lastFiredInterval` and a declared catch-up policy. They MUST separate elapsed cadence from local-calendar cadence and define DST gap/fold and business-calendar behaviour. SLA timers drive escalation in the approval core. | After downtime, missed firings follow the declared catch-up policy. | — | D (see finding 12) | T22 | II §19 R31; III §13; IX.3; VII §7 |
| R32 | Upgrade matrix | The upgrade matrix MUST cover module combinations and DB-per-tenant. Behaviour-changing defaults stay behind a flag for one LTS, and there MUST be an executable support window for old active models that outlive that window. | A rolling N/N-1 upgrade has a working recovery plan. | R12, R43 | D | T19, T28, T29 | II §19 R32; III §13, §15.4; VII §7 |
| R33 | FEEL type contract | A FEEL type contract for JSON variables MUST be checked at deployment and at runtime. It covers precise decimals/money, large integers, zoned vs unzoned time, missing vs null, and defined decision-error/result behaviour, and it is identical across SDKs. | A zoned/unzoned mismatch is rejected at deployment. A runtime type violation produces the defined decision error. | R10, R18 | C | T18, T19 | II §19 R33; III E18, §13; VII §7, §8.2 |
| R34 | BPMN gaps of the base | Each alleged BPMN gap MUST be reproduced on pinned upstream code before it is scheduled. Gaps of the base (II §3.1) are Must items and gaps in both engines (II §3.2) are Should/Could; only a pilot subset is delivered in Stage C. A release conformance profile and the behaviour for unsupported elements MUST be published. | An unsupported construct is rejected at deployment (R44). | D-1, R44 | C (pilot subset) | T20, T21; add test (pinned gap corpus) | II §19 R34, §3.1–§3.2; III §13; VII §7, §11.1 N4; VIII.9 |
| R35 | Exclusivity | **Replaced by VI.6 + A-4.** `sangam:exclusive` has three modes. `state` (default): root state is serialized by L2, and external effects may overlap. `execution`: at most one locked task per (root, exclusiveGroup), using an `EXCLUSIVE_LEASE` row inserted in the acquisition transaction. `resource:<FEEL>`: at most one locked task per business resource across the tenant, with `lock_version` passed as `resourceFence`. Acquisition never locks the instance row; order is FIFO within a scope; completion still takes L2; leases are rows, never open transactions. | A candidate with a live lease is skipped. The lease is released on complete, fail, release or expiry (expiry per VI.3). | R3, R9 | C | T05, T40 | VI.6.1–VI.6.2; VIII.3 A-4; III E07; VII §6.4, §7 |
| R36 | Business-operation identity (E01) | `operationId` = UUIDv5(tenant, rootId, activityInstanceId, loopCounter, retrySafeKey). It MUST stay stable across retries, redelivery, lease expiry, migration and restore, and the SDKs forward it as the idempotency key. `attemptId` is a separate field that changes per lease, and a new legitimate business action gets a new operationId (A-2). `OPERATION_LEDGER` keeps (operationId, requestFingerprint, outcome) at least as long as history. Each connector or topic MUST declare a capability class: (a) idempotent + outcome lookup, (b) outcome lookup only, (c) neither. | An unknown downstream result leads to `outcomeUnknown` and an UNKNOWN_OUTCOME reconciliation item (confirm success / retry with the same operationId / mark failed). Class (b): query before a controlled retry. Class (c): no automatic repeat. | R9, R20, R29 | B (proof) → C | T02, T03, T27, T36 | III E01; V.3; VI.3.2 rules 4–5; VIII.3 A-2; VII §6.2, §7 |
| R37 | Durable call intent / journal (E02) | A call intent with a stable call ID and any provider/request handles MUST be persisted before dispatch. Recovery uses provider idempotency or outcome lookup and records uncertainty instead of re-issuing. `context.step(...)` replays durably recorded results, and its documentation MUST state that it is **not exactly-once for remote APIs**. Prompt, tool and model versions, policy, cost and budget reservations are recorded. Journal retention, encryption and ownership follow R29/R42. | A crash between the remote call and the journal commit leads to an outcome lookup or explicit UNKNOWN_OUTCOME, never a blind re-issue. | R14, R29, R36, R42 | E | T12; add test (ordinary worker step-helper) | III E02; V.3, V.4b; VIII.3 A-2; VII §6.2, §7 |
| R38 | Inbound inbox (E03) | **Per VI.2.6.** A message MUST be inserted into `INBOX` (PK tenant, source, message_id) and committed **before** the broker is acked. Processing claims rows with SKIP LOCKED and epochs. With no subscription yet, the message is `BUFFERED` until a subscription appears or its TTL runs out (default 24 h); the policy per event definition is buffer / reject / start-new. `uniqueStart` enforces one start per (tenant, definition key, business key). The tenant comes from the channel binding and authenticated source, never from the payload alone. | Duplicate key: ack and drop. TTL expiry: `EXPIRED`, with audit and an optional dead-letter channel. A DB restore while the broker holds newer messages MUST NOT create duplicate business starts. | R4, R19, R36 | C | T09, T35; add test (TTL/multiple-match) | VI.2.6–VI.2.7; III E03; VIII.3 A-5; VII §6.5, §7 |
| R39 | Partition epochs (E09) | Partition ownership MUST use owner epochs, reject stale owners, and rebalance with in-flight recovery; changing the partition count MUST NOT strand jobs or give the same work to two owners. **The contract is OPEN (A-6):** until it is specified, partitioned executors are out of scope for every release. | A resuming stale owner must be rejected (contract not yet specified). | R3, R9, R21 | E (blocked by A-6) | T41 (T24 is only related) | III E09; VIII.3 A-6; VIII.7 #2; VII §6.6, §7 |
| R40 | Outbox capacity (E10) | **Per VI.2.4 + A-5.** `outboxMaxBytes` MUST be configured (default 20% of planned DB storage) and sized as event rate × average payload × allowed outage hours × 1.5, checked with drain time ≈ B/(μ−λ), which is valid only when μ > λ. The drain rate is measured and shown. | At 50%: alert. At 80%: new starts and inbound events get `503 BACKPRESSURE`, while continuations still commit. At 95%: only completions and cancellations are accepted. | R4, R38 | C | T25; add test (recovery-drain measurement) | VI.2.4; VIII.3 A-5; III E10; VII §6.5, §7 |
| R41 | API idempotency (E12) | Idempotency MUST be an API lifecycle: scope (tenant, command type, key), payload fingerprint, handling of concurrent duplicates, reuse of success and error responses, retention, retries after timeout and authorization revalidation. The result is stored atomically with engine state where possible. *Spec pending (VIII.7 #2).* | A reused key with a different payload is rejected. A duplicate returns the stored response. | R3, R19, R36 | B (proof) → C | T01; add test (expiry/key reuse) | III E12; V.3; VIII.7 #2; VIII.9; VII §7 |
| R42 | Audit vs replay (E13) | Operational history, mandatory security/business audit, optional diagnostic payloads and replay fixtures MUST be separate purposes, each with its own minimum event schema, ordering, schema evolution and retention. Model, rules, forms, expression profile, connector contract and external outcomes are recorded by immutable version/hash. Replay against a new model is scenario regression, not Temporal-style replay. Audit shows seat, person, rule version and "acted for". *Spec pending (VIII.7 #2).* | Not specified (spec pending). | R29, R43, R51 | C (audit part); replay E | T42; T19, T30 cover parts; add test (privacy) | III E13; V.3; VIII.7 #2; IX.5, IX.8 AC-01; VII §7 |
| R43 | Executable-package versioning (E14) | The complete executable package MUST be pinned: BPMN/CMMN, called subprocesses, DMN, FEEL profile, forms, scripts/worker contract, connector templates, policy versions and type definitions. Deployment-scoped vs latest binding MUST be explicit. Lifecycle: draft → review → approved → deployed → active → retired, with signed/hash-addressed artifacts, separate author and deployer authority, comparison and rollback/run-off. Return-and-resubmit keeps version history. *Spec pending (VIII.7 #2).* | A change to the bound payload or version invalidates existing approvals (VI.4 rule 5). | R10, R19, R47 | C | T19, T21, T28 | III E14; V.3; VI.4.1 rule 5; VIII.7 #2; IX.3; VII §7, §8.2 |
| R44 | Standards profile (E15) | Each release MUST publish a standards profile: a clause/element matrix giving standard version, conformance profile, parser/import/export/editor/runtime support, limitations, tests and release. Unsupported executable content MUST fail deployment explicitly. Syntactic interchange (MIWG) is kept separate from runtime equivalence. | An unsupported construct is rejected at deployment or documented as non-executable. | R8, R34, R46 | Not mapped in VIII.9 (finding 9) | T20 + coverage matrix | III E15; V.3; VIII.6; VII §7, §8.2 |
| R45 | Bounded CMMN conversion (E16) | CMMN → BPMN conversion MUST be limited to a supported subset, with diagnostics for unsupported constructs, manual review, an explicit state-migration mapping and a run-off option for existing cases. No universal automatic conversion is promised. CMMN is architecturally isolated in B/C and built in E. | An unsupported construct produces a diagnostic and goes to manual handling or run-off. | R44, R48 | E (isolation in B/C) | T43 (T21 alone is insufficient) | III E16; V.3, V.4b; VIII.4; VII §7, §11.1 N14 |
| R46 | Modeler/runtime parity (E17) | A modeler/runtime compatibility matrix with round-trip tests MUST exist for every supported construct. When external-authoring import is acceptable MUST be decided. | Not specified. | R8, R44 | E | T20, T32; add test (round-trip/authoring corpus) | III E17; V.3; VII §7 |
| R47 | Forms contract (E19) | Forms MUST be an independent versioned contract, with separate data-validation and layout/rendering schemas, extension points, server-side validation, conditional fields, attachments, draft persistence, field-level authority, accessibility and version binding for in-flight forms. A field hidden on the client is not an authorization boundary. *Spec pending (VIII.7 #2).* | Server-side validation rejects invalid or unauthorised data whatever the client shows. | R19, R43 | C | T16, T19, T32; add test (form version fixtures) | III E19; V.3; VIII.7 #2; VII §7, §8.2 |
| R48 | Migration contracts (E20) | Model import, API/REST compatibility, library migration and live execution-state migration MUST be separate contracts. Start with compatibility classification and new-instance cutover with run-off. Live migration MUST include inventory and dry-run, rejection of unsupported states, worker freeze/drain, mapping and reconciliation, cutover ownership, rollback conditions and duplicate-effect protection, all checked against business invariants. | An unsupported state is rejected. Rollback follows the stated conditions. | D-1, R36, R43 | D (pilot subset may move to C); importers E | T21, T27, T28; add test (real migration corpus) | III E20; V.3; VIII.9; VII §7 |
| R49 | Continue-as-new and modification limits (E21) | Continue-as-new MUST provide atomic handover, pending messages, business-key uniqueness, original deadlines and a navigable chain. Modification commands are restricted, need reasons and MUST re-evaluate the business obligations they affect (such as approvals). Rewinding a token never reverses a completed payment. | A command that would bypass a required approval is refused or forces re-evaluation. | R19, R36, R42, R43 | D | T44 | III E21; V.3; VIII.4; VII §7 |
| R50 | Identity / SSO (GOV-6) | A pluggable identity layer MUST provide SAML 2.0 and OAuth2/OIDC adapters, with a Parichay connector first (Parichay is mandatory for government users). Aadhaar is allowed only through a department's AUA/KUA (Sub-AUA model), with an Aadhaar data vault and no biometric storage. | Not specified. | R19, R26 | C | add test | IX.7 GOV-6; IX.7a GOV-6; App. B GOV-6 |
| R51 | Log retention and incident readiness (GOV-3) | Logs MUST be retained in India for at least 180 days and exportable to a SIEM. The incident workflow MUST be able to meet 6-hour CERT-In reporting. Time sync uses NTP from NIC/NPL. | Not specified. | R13, R29, R42 | C (Stage D gate item) | add test | IX.7a GOV-3; App. B GOV-3 |
| R52 | Audit-ready release process (GOV-3) | Each release MUST ship an audit evidence pack that supports a CERT-In-empanelled security audit before hosting, every year and after major changes. | Not specified. A release without the evidence pack fails the Stage D gate. | R24, R27, R51 | D | add test | IX.7a GOV-3, consequence 2; App. B GOV-3 |
| R53 | DPDP compliance (GOV-4) | Personal data MUST be purpose-tagged, with notice templates, encryption and masking, breach workflows (72 h report) and grievance workflows, configurable retention, and a data-processor agreement template for departments. Compliance MUST be in place before the core obligations apply (about May 2027). | Not specified. | R23, R29, R61 | D | add test | IX.7 GOV-4; IX.7a GOV-4, consequence 2; App. B GOV-4 |
| R54 | Languages (GOV-1) | The UI, forms and output templates MUST be in Hindi + English plus a configurable state language. Storage MUST be Unicode on every Tier-1 DB. A BHASHINI translation hook is optional. | Not specified. | R17, R47 | C | T32 (IX.3, AC-10); add test | IX.7a GOV-1; IX.3; IX.8 AC-10; App. B GOV-1 |
| R55 | Accessibility / GIGW (GOV-2) | WCAG 2.1 AA (GIGW 3.0) is the MUST floor and WCAG 2.2 AA the SHOULD target. IS 17802 MUST be met, and a GIGW conformance matrix maintained for STQC certification. | Not specified. | R47, R54 | D | T32 (IX.7 GOV-2); add test | IX.7 GOV-2; IX.7a GOV-2; I §14.6; III §10; App. B GOV-2 |
| R56 | Hosting and localisation (GOV-5) | Sangam MUST be deployable on a State DC, NIC and MeitY-empanelled cloud, with data resident in India. By default there are no foreign runtime dependencies and no outbound telemetry, and air-gapped install from an offline bundle is supported. | Not specified. | R13, R17 | C | T50 | IX.6; IX.7a GOV-5, consequence 4; IX.8 AC-11; App. B GOV-5 |
| R57 | Signing service (GOV-7) | A signing service MUST provide a DSC agent and an eSign ASP (multiple ESPs), hash signing and PAdES/CMS with LTV and timestamps. The signature MUST be bound to the approval payload hash (VI.4 rule 5). | Not specified. A change after signing invalidates the bound approval (VI.4 rule 5). | R19, R43 | C | add test | IX.7a GOV-7; App. C GOV-7 |
| R58 | PFMS / IFMIS and payment connectors (GOV-8) | PFMS/IFMIS connectors MUST be file-based over SFTP (signed payment/sanction files out, status files in) and run as external-task workers with operationId (VI.3). Gateway adapters: NTRP, PayGov, GRAS. | An ambiguous payment status follows VI.3: UNKNOWN_OUTCOME and reconciliation, with no duplicate payment. | R9, R27, R36 | C | add test | IX.7a GOV-8; IX.4 P1; App. C GOV-8 |
| R59 | DigiLocker / API Setu (GOV-9) | A DigiLocker issuer adapter MUST be provided for licences and certificates (P4), and a requester adapter for applicant documents. Sangam APIs are published on API Setu where required. | Not specified. | R27, R57 | D | add test | IX.7a GOV-9; IX.4 P4; App. C GOV-9 |
| R60 | Notifications / DLT (GOV-10) | A notification service MUST keep a per-tenant DLT registry (entity, header, template), validate templates and allow only whitelisted URLs. Adapters for NIC SMS and gov.in e-mail. | A message whose template or URL is not registered fails validation. | R4, R27 | C | add test | IX.7a GOV-10; App. C GOV-10 |
| R61 | Records retention and RTI (GOV-11) | Each file MUST be classified for retention at closure (category A permanent / C ≤ 10 years). Category A and B files are reviewed at year 25. The system provides legal hold, NAI export as PDF/A and an RTI request SLA module (30 days, or 48 h for life/liberty). **This overrides any generic TTL cleanup (R29) for government records.** | Unauthorised destruction of records MUST NOT happen (Public Records Act 1993). | R23, R29, R31 | C | add test | IX.7 GOV-11; IX.7a GOV-11; App. C GOV-11 |
| R62 | Procurement readiness (GOV-12) | The licence, support and service SKUs MUST be listable on GeM. Local content MUST be documented at ≥ 50% (Class-I). Any cloud offer goes only through a MeitY-empanelled CSP. This feeds D-11. | Not specified. | D-11, R56 | D | add test | IX.7 GOV-12; IX.7a GOV-12; App. C GOV-12 |

### Consistency findings

1. **Stage definitions disagree.** In III §15.2 and in VI.7 D-2 ("product/baseline validation → full vertical pilot → production hardening → expansion → advanced scope"), the pilot is Stage B, hardening is C, expansion is D and advanced scope is E. VIII.9 defines A need/scope, B technical proof, C full pilot, D production readiness, E expansion. VI.7 D-2 also labels the choice "Recommended" while its status column says DECIDED. VIII.9 should be stated as the only stage definition, and III §15.2 and VI.7 annotated.
2. **SDK tiers (R18) have three versions.** II §19 and I §16 give six Tier-1 languages including Go. VIII.9 (Phase 1 row) says "Java + pilot languages; PHP/.NET kept". IX.6 ADR-D-4 decides five Tier-1 languages, with Go moved to Tier 2. VIII.5 marks D-4 DECIDED, but the VIII.6 index still says "D-4/D-5 OPEN".
3. **The R9 transport is undecided but assumed.** II §19 R9 and I §16 require gRPC streaming with long-poll fallback, and II §14.3 assumes streaming for hand-off p99 < 100 ms. VI.3 describes stream offers. D-5 is still OPEN with a REST-first recommendation (VIII.5, IX.6). The VI.3 offer/ACK mechanics and the R22 latency target need a stated REST/long-poll variant.
4. **The L1 lock domain does not cover `execution` mode.** VI.1.1 defines L1 only for `exclusive="resource:…"`, but VI.6 `execution` mode also inserts `EXCLUSIVE_LEASE` rows in the acquisition transaction. VI.1 should say whether execution-group leases are L1 and how they relate to rule 4 ("acquisition never takes L2").
5. **The default retry behaviour for undeclared effects is ambiguous.** VI.3 rule 5 blocks retries only for topics marked `effect=irreversible`; otherwise lease expiry returns the task to AVAILABLE. A-2 requires every connector/topic to declare capability class (a)/(b)/(c), and R28 forbids blind retries of any non-idempotent effect. The mapping between `effect=irreversible` and the classes, and the default for an undeclared topic, are not specified.
6. **Erasure conflicts with records retention.** VI.5 `ERASE_DATA` scrubs payloads in runtime, history, outbox, inbox and object store. R61 (Public Records Act; category A permanent) forbids unauthorised destruction and overrides R29, and the DPDP s.17(4) State carve-out (IX.7a GOV-4) limits erasure. VI.5 does not state whether erasure is refused or limited for retention-classified government records, or how R53 and R61 take precedence over each other.
7. **The test catalogue has inconsistent counts.** V.3 says the R36–R49 acceptance tests are T01–T32, which VII §7 and VIII.4 contradict. VIII.4 and VIII.9 call the catalogue T01–T49, the VIII.6 index lists only T01–T49, and IX.7a adds T50. The VI header calls T33–T40 "new", while the VIII.6 index attributes them to Part VI. One numbered catalogue should be the only reference.
8. **The baseline index misses R50–R62.** VIII.6 ("Other requirements R1–R49"), VIII.2 M01 and VIII.7 #1 scope the baseline to R1–R49. IX.7a adds R50–R62 without updating the index, and R50–R62 appear nowhere in the VIII.9 Phase → Stage table.
9. **VIII.9 mapping gaps and duplicates.** VIII.9 mandatory condition 1 requires every requirement to be placed, but **R44 is not mapped** to any stage. Several requirements are placed in more than one stage without a rule for which one governs: R19 and R26 (B and C), R21 (B→C and D), R48 (D and E), R42 (C for audit, while T42 sits in an E row).
10. **R50–R62 have no tests.** IX.7a names only T50 (R56). The other twelve requirements have no T-mapping, against VIII.9 condition 1. IX.3 still lists "Hindi/English UI" with no spec reference, even though R54 now specifies it.
11. **R17 PR-gate policy.** II §19 R17 and I §16 require all Tier-1 DBs on **every PR**. III §16's closing paragraph recommends targeted affected-DB checks on PRs, with broader matrices on a schedule. V.4b keeps "PostgreSQL-only PR gating" OPEN. VIII.9 condition 2 only fixes release checks from Stage C. The PR-level gate is unresolved.
12. **R31 stage vs the pilot core.** VIII.9 places R31 (timer policy) in Stage D. IX.3 lists "SLA timers and escalation (R31, T22)" as a pilot core capability, and IX.8 AC-06 (licence SLA breach escalation, T22) is a pilot acceptance criterion, which needs at least a Stage C subset.
13. **R34 MoSCoW status.** II §19 R34 makes the base's BPMN gaps Must items. III §13 says "scope decision needed", VII §7 requires reproducing each gap first, and VIII.9 limits R34 to a "pilot subset". VII §11.1 N4 records the PDF assigning seven gaps to Phase 1. The Must classification has not been reconciled with these qualifications.
14. **The index points to specifications that do not exist yet.** VIII.6 says the normative text of the other requirements is in II §19 and III §8–§10, but VIII.7 #2 states that R39, R41, R42, R43 and R47 still need specification. VIII.9 Stage B is meant to *prove* the R41 idempotency contract on both engines before that contract is specified.
15. **A-6 status is contradictory.** VIII.3 heads its amendments column "Amendment (**ADOPTED**)" and the Part VIII header says ADOPTED amendments override Parts I–VI, yet A-6 is "OPEN, specified next". It is unclear whether "partitioned executors out of scope" is binding now (it is treated as binding here) while the R39 contract itself stays open.
16. **Stale summaries remain unannotated in Parts I–II.** I §16 and II §19 still state the superseded wording: R9 "un-lock on stream close", R10 global "null → incident", R23 "ends a job silently", R35 "checked per root instance at completion", R6 "GraalJS isolates". Part VI and V.3 override them by precedence, but no in-place pointer exists, which is the M01 risk. A one-line "superseded by VI.x / D-8" note on each would help.

### X.1a Resolution of the consistency findings in X.1 (2026-11-04)

| # | Finding | Resolution (authoritative from now on) |
|---|---|---|
| CF-1 | Stages A–E are defined differently in III §15.2, VI.7 D-2 and VIII.9 | **VIII.9 is authoritative.** III §15.2 and VI.7 are history |
| CF-2 | Three versions of the SDK tiers; the VIII.6 index still shows D-4 OPEN | **IX.6 (ADR-D-4) is authoritative**: Tier 1 Java, .NET, PHP, Python, TypeScript/Node; Tier 2 Go, Rust, Kotlin; any other language via REST/OpenAPI. VIII.6 is corrected below |
| CF-3 | R9 assumes gRPC streaming while D-5 was open | **Resolved by ADR-D-5 (X.0): REST first.** The REST long-poll lease protocol follows VI.3 with the same states, where OFFERED = returned in a poll response and not yet acknowledged. The stream rules apply when gRPC is added |
| CF-4 | Lock level L1 covers only `resource:` mode, but the `execution` mode also takes a lease | **Amendment A-7:** L1 = any row in `EXCLUSIVE_LEASE`, for both `execution` and `resource:` modes (VI.6). The lock order stays L1 → L2 → L4 |
| CF-5 | No default for topics declaring neither `effect=irreversible` nor a capability class | **Amendment A-8:** the default capability class is **(c) "neither"**: no automatic repeat after lease expiry; the task goes to UNKNOWN_OUTCOME with reconciliation. A topic must declare class (a) or (b) to get automatic retries. `effect=irreversible` is an alias of (c) |
| CF-6 | Erasure (VI.5) vs records retention (R61, Public Records Act) | **Amendment A-9:** for government records, **R61 legal retention takes precedence over erasure** (DPDP s.17(4) State carve-out, GOV-4; the Public Records Act bars unauthorised destruction, GOV-11). Erasure then means minimising personal data that law does not require to be retained, plus masking in views. The legal basis is recorded per request |
| CF-7 | Test catalogue counts differ (T01–T32, T01–T49, T50) | **The authoritative catalogue is T01–T50, plus the tests numbered from T51 onwards in X.2.** Earlier ranges are partial views |
| CF-8 | The VIII.6 index omits R50–R62; R44 has no stage | **Index corrected below.** **R44 (release conformance profile) → Stage C**: the pilot publishes its supported BPMN/DMN profile |
| CF-9 | R31 (timers/DST) is in Stage D, but the pilot core and AC-06 need it | **R31 moves to Stage C.** Stage D keeps the broad DST and downtime tests (T22 at scale) |
| CF-10 … CF-16 | R17 PR-gate policy; R34 Must status; specs the index points to but that do not exist yet; A-6 status; old superseded wording in Parts I–II; and others (see X.1 consistency findings) | **Scheduled for the Day-3 final consistency sweep (X.5).** Until then, the precedence rules apply: Part X > IX > VIII > VII > VI > V > I–IV |

**Correction to the VIII.6 index (additional rows):**

| Topic | Authoritative location |
|---|---|
| SDK tiers / API transport | IX.6 (ADR-D-4), X.0 (ADR-D-5) |
| Government requirements R50–R62 | IX.7a, X.1 |
| Normative requirement table (all R1–R62) | **X.1**: the single place to read current requirements |
| HA/DR targets | X.0 (ADR-D-12) |
| Amendments A-7, A-8, A-9 | X.1a |

## X.2 Specifications R39, R41, R42, R43, R47

> **Precedence:** X.2 **replaces** the normative text of R39, R41, R42, R43 and R47 everywhere in Parts I–IX (II §19 rows, III E09/E12/E13/E14/E19, VII §7 closure rows). It closes amendment **A-6** as SPECIFIED. It overrides DESIGN §4.3 item 4 (`hash(rootInstanceId) % partitions` with a Kubernetes lease) and the "replay / time-travel / complete event log" wording of DESIGN L5/L9, §4.6 and §14.4. For government records it applies GOV-11 (R61), which overrides generic TTL cleanup (R29).
>
> **Builds on:** VI.1 (lock order L1 → L2 → L4, bounded transactions, retries), VI.2 (outbox `root_seq`, epoch-fenced claims), VI.3 + A-1/A-2 (leases, database clock, `operationId`/`attemptId`), VI.4 + A-3 (one policy, sensitive-action re-check, approval bound to payload hash), VI.5 (erasure and tombstones), IX.3–IX.5 (government core, seats, delegation of powers), X.0 (ADR-D-5 REST first; ADR-D-12 RPO ≤ 5 min, RTO ≤ 1 h).
>
> **SQL:** written for PostgreSQL. Dialect notes follow each specification and use the same error classes as VI.1.6.
>
> **Status (M04 fields):** Evidence: design inference **[I]** unless a source is cited. Decision: **PROPOSED** (becomes ADOPTED on owner review). Delivery: **SPECIFIED**. Nothing here is implemented. Nothing is done until its acceptance tests pass. That covers the tests already in the catalogue (T01, T16, T19, T21, T28, T30, T32, T41, T42) and the new tests T51–T77 listed at the end of this section.

---

### X.2.1 R39 (E09): Partition ownership for job executors

#### X.2.1.1 Scope and default

| Item | Rule |
|---|---|
| **Default** | **Partitioned executors are optional and OFF by default** (`sangam.executor.partitioning = off`). With partitioning off, every node acquires jobs through the ordinary VI.1 rule 4 path (`FOR UPDATE SKIP LOCKED`, epoch and `lock_version` per VI.3). That path is complete and correct without anything in this section |
| **What partitioning is** | A **throughput and affinity optimisation** only. It lowers acquisition contention and keeps a root's async work on one node, which keeps caches warm. **It is never a correctness mechanism.** Engine-state correctness still comes from L2 plus the row CAS on `lock_version` (VI.1, VI.3). Partition fencing is a second, independent guard |
| **What is partitioned** | Internal jobs only: async continuations, timers and internal retries (`JOB`, `TIMER_JOB`). **External tasks are never partitioned.** They follow the VI.3 worker lease, whatever this setting is |
| **Enabling gate** | Partitioning may be enabled for a release only if **T54** shows a measured benefit over the default path on the same hardware and workload: either ≥ 25 % more completed jobs/s, or ≥ 30 % lower p99 acquisition latency, with equal correctness results. Without that evidence the feature stays off and undocumented for customers (E09: "measure whether partitioned acquisition adds value") |
| **Release placement** | Stage E (VIII.9 phase map, unchanged). This specification removes the A-6 block, but not the T54 gate |
| **Authoritative clock and store** | All lease times use the **engine database clock** (A-1). The **engine database is the only ownership authority**. A Kubernetes `Lease` object may be used only as a hint for faster takeover. It never grants the right to write |

#### X.2.1.2 Routing: fixed slots, versioned map

`hash % partitions` is not used, because changing the partition count would move almost every root. Instead:

1. **Slots.** A cluster has a fixed number of virtual slots `S`, set at cluster creation (default 4,096, maximum 65,536). `S` cannot be changed online. Changing it is an offline, audited migration that rewrites `JOB.slot`.
2. **Slot of a job.** `slot = murmur3_32(tenant_id || ':' || root_id) mod S`. The engine computes it when it creates the job and **stores it in the job row**. The slot of a job never changes.
3. **Partitions own sets of slots.** `EXEC_SLOT` maps each slot to exactly one partition. Changing the partition count changes that mapping. Jobs are never rehashed or rewritten.
4. **Invariant I-1 (no stranding):** every slot is in exactly one `EXEC_SLOT` row at every moment, with state `STABLE` or `MOVING`. A slot that stays `MOVING` longer than `drainTimeout + orphanTimeout` raises an alert and is completed by force (X.2.1.7 step 5).

#### X.2.1.3 Tables

```
EXEC_PARTITION(partition_id INT PK,
       owner_node VARCHAR NULL, owner_incarnation UUID NULL,
       owner_epoch BIGINT NOT NULL DEFAULT 0,       -- strictly increasing, never reused
       lease_expires_at TIMESTAMPTZ NULL,           -- engine DB clock
       state  -- UNOWNED | ACTIVE | DRAINING | RETIRED | SHARED (virtual partition 0 only)
       , drain_deadline TIMESTAMPTZ NULL, map_epoch BIGINT NOT NULL)

EXEC_SLOT(slot INT PK, partition_id INT NOT NULL, state -- STABLE | MOVING
       , from_partition INT NULL, move_started_at TIMESTAMPTZ NULL, map_epoch BIGINT NOT NULL)

EXEC_MAP(cluster_id PK, current_map_epoch BIGINT, target_map_epoch BIGINT NULL,
       state -- STABLE | TRANSITION
       , coordinator_node, coordinator_epoch BIGINT, coordinator_expires_at)

EXEC_NODE(node_id PK, incarnation UUID, started_at, heartbeat_at, draining BOOLEAN)

JOB(..., slot INT NOT NULL, owner_partition INT NULL, owner_epoch BIGINT NULL,
       lock_owner, lock_version BIGINT, lock_expires_at, recoveries INT DEFAULT 0)
-- index: (slot, due_at) WHERE lock_owner IS NULL
```

`incarnation` is a fresh UUID each time a process starts. A restarted node with the same `node_id` is a **new owner** and never inherits the epoch it held before.

#### X.2.1.4 Partition lease lifecycle

```
UNOWNED --claim--> ACTIVE(epoch e+1) --renew--> ACTIVE(e)
ACTIVE(e) --renew fails / lease expired--> (taken by another node) ACTIVE(e+1)     [forced takeover]
ACTIVE(e) --handover requested--> DRAINING(e) --drained or drain_deadline--> UNOWNED --claim--> ACTIVE(e+1)
ACTIVE/UNOWNED --map change removes partition--> RETIRED                          [after all its slots moved]
```

**Claim** (own short transaction, no other locks):

```sql
UPDATE EXEC_PARTITION
   SET owner_node=:node, owner_incarnation=:inc, owner_epoch=owner_epoch+1,
       lease_expires_at=now()+:leaseTtl, state='ACTIVE', drain_deadline=NULL
 WHERE partition_id=:p
   AND state IN ('UNOWNED','ACTIVE','DRAINING')
   AND (state='UNOWNED' OR lease_expires_at < now())
RETURNING owner_epoch;
```

**Renew** (every `leaseTtl/3`; default `leaseTtl` = 15 s):

```sql
UPDATE EXEC_PARTITION SET lease_expires_at=now()+:leaseTtl
 WHERE partition_id=:p AND owner_incarnation=:inc AND owner_epoch=:e AND state IN ('ACTIVE','DRAINING')
   AND lease_expires_at >= now();
-- 0 rows ⇒ the lease is lost: the node stops acquiring for :p at once and drops its in-memory queue for :p
```

**Self-fencing (liveness only).** A node stops acquiring for a partition when its monotonic clock says `leaseTtl − safetyMargin` (default 3 s) has passed since its last successful renewal, even if no renewal has failed yet. Correctness never depends on self-fencing. The database CAS in X.2.1.5 rejects a stale owner whatever its local clock says.

#### X.2.1.5 Fencing on every write

Rule **F-0:** *the row that is mutated carries the fence.* A partitioned executor writes nothing whose validity rests only on in-memory ownership.

| Write | Fence |
|---|---|
| Acquire jobs (marks rows owned) | `EXISTS` on the partition row with `(owner_incarnation, owner_epoch)` and an unexpired lease, **in the same statement** (below). Each acquired job row gets `owner_partition`, `owner_epoch` and `lock_version+1` |
| Execute a job (engine-state change) | VI.1: take **L2 first**, then re-read the job by id with `lock_owner=:node AND lock_version=:v AND owner_epoch=:e`. If no row matches, the stale owner aborts and discards the job. The final job update/delete is a CAS on `(id, lock_version)` |
| Create follow-on jobs | No fence needed: new jobs are created unowned (`lock_owner IS NULL`) with their computed slot. **Post-commit hand-off** (DESIGN §4.4) may run a new job on the creating node only if that node owns the slot's partition with a valid lease **and** wins a normal acquisition CAS for that job id. Otherwise the job waits for its owner |
| Partition-local state (cursors, timer look-ahead windows) | Stored in rows keyed by `partition_id` and updated `WHERE owner_epoch=:e`. 0 rows ⇒ stale owner, abort |
| Release a job (shutdown, drain) | `UPDATE JOB SET lock_owner=NULL, lock_version=lock_version+1 WHERE id=:id AND lock_version=:v AND owner_epoch=:e` |

Acquisition:

```sql
UPDATE JOB j
   SET lock_owner=:node, lock_version=j.lock_version+1, owner_partition=:p, owner_epoch=:e,
       lock_expires_at=now()+:jobLockTtl
 WHERE j.id IN (
         SELECT x.id FROM JOB x
          WHERE x.slot IN (SELECT s.slot FROM EXEC_SLOT s WHERE s.partition_id=:p AND s.state='STABLE')
            AND x.lock_owner IS NULL AND x.due_at <= now() AND x.suspended = false
          ORDER BY x.priority DESC, x.due_at
          LIMIT :batch FOR UPDATE SKIP LOCKED)
   AND EXISTS (SELECT 1 FROM EXEC_PARTITION p
                WHERE p.partition_id=:p AND p.owner_incarnation=:inc AND p.owner_epoch=:e
                  AND p.state='ACTIVE' AND p.lease_expires_at > now())
RETURNING j.id, j.lock_version;
```

Why this is enough **[I]**. A stale owner (paused by GC, SIGSTOP or a network partition) can hold three things: (a) acquired job ids in memory, (b) an open DB transaction, (c) an in-flight execution transaction. In each case:
- **(a)** Its execution re-read fails, because recovery (X.2.1.6) has bumped `lock_version` and `owner_epoch`.
- **(b)** The DB aborts the open transaction through `idle_in_transaction_session_timeout` (PG, default 10 s) and `statement_timeout` (VI.1 rule 6). That is a liveness measure only.
- **(c)** If the stale execution commits **before** recovery touches the job row, it was the only execution: recovery's later CAS skips or misses the row, and nothing is duplicated. If recovery commits first, the stale CAS fails.

Two executions can never both commit a change to the same job. Internal jobs do no remote work (VI.1 rule 5), so a rejected stale execution leaves no external effect behind.

#### X.2.1.6 Rebalance and in-flight recovery

A **coordinator** computes and applies rebalance plans. The coordinator is itself a fenced lease: `EXEC_MAP.coordinator_epoch`, claimed and renewed like a partition. Every plan write carries `coordinator_epoch`, so a stale coordinator's plan is rejected.

**Triggers:** a node joins, a node leaves or drains (pre-stop hook), a lease expires, or an administrator changes the partition count.

**Planned handover** (scale-down, rolling upgrade). Steps for partition `p`, moving from node A to node B:
1. The coordinator sets `state='DRAINING'` and `drain_deadline = now() + drainTimeout` (default 30 s, and always ≤ the pod's `terminationGracePeriodSeconds − 10 s`).
2. A stops acquiring for `p`. It finishes the executions it has in flight, then releases any acquired jobs it has not started (release CAS), then sets `state='UNOWNED'` (`WHERE owner_epoch=:e`).
3. B claims (epoch `e+1`). Recovery (step 4) runs anyway, as a safety net.

**Forced takeover** (lease expired, node lost). B claims `p` with epoch `e+1` and then runs **in-flight recovery** in batches of `recoveryBatch` (default 500). Each batch is its own short transaction:

```sql
UPDATE JOB j SET lock_owner=NULL, lock_version=j.lock_version+1, owner_partition=NULL,
                 owner_epoch=NULL, recoveries=j.recoveries+1
 WHERE j.id IN (SELECT x.id FROM JOB x
                 WHERE x.owner_partition=:p AND x.owner_epoch < :newEpoch AND x.lock_owner IS NOT NULL
                 ORDER BY x.id LIMIT :recoveryBatch FOR UPDATE SKIP LOCKED);
```

Recovery rules:
- `SKIP LOCKED` skips rows that a stale execution is committing right now. A later pass, or plain `lock_expires_at` expiry, catches them. That avoids lock cycles between recovery and stale executions, and recovery takes no L2.
- Recovery **does not decrement retries**: losing a node is not a job failure. When `recoveries > maxRecoveries` (default 3), the job goes to an incident `JOB_RECOVERY_LOOP`. This catches a poison job that kills its executor.
- The general expired-lock sweeper (R21) also covers partitioned jobs, so recovery is never the only path that frees a job.

**Orphan rule (liveness).** A partition that has been `UNOWNED`, or has had an expired lease, for longer than `orphanTimeout` (default 2 × `leaseTtl`) may be claimed by any partition-capable node. If no node is partition-capable, the **default acquisition path** acquires jobs whose slot belongs to an orphaned partition. A job therefore always has an acquirer.

#### X.2.1.7 Changing the partition count

A map change is an administrative command. It needs `ADMIN_EXECUTOR` permission, a reason, and an audit event (R42, class B). Procedure, moving map epoch M to M+1:

1. Validate: the new partition count is between 1 and `S`; each new partition gets a contiguous range of slots (a balanced split); a minimal-movement assignment is computed, so going from P to P' partitions moves about `|P' − P| / max(P, P')` of the slots.
2. `EXEC_MAP.state='TRANSITION'`, `target_map_epoch=M+1`. Insert the new `EXEC_PARTITION` rows as `UNOWNED`.
3. For each slot that changes partition, in batches: `UPDATE EXEC_SLOT SET state='MOVING', from_partition=partition_id, partition_id=:target, move_started_at=now() WHERE slot=:s AND map_epoch=:M`. Acquisition filters on `state='STABLE'`, so the source stops taking new jobs from that slot at its next cycle.
4. The source owner releases or finishes in-flight jobs of moving slots, as in a drain.
5. A moving slot becomes `STABLE` under the target when either of these holds: (a) no job in the slot has `owner_partition = from_partition` with a live lock, or (b) `drainTimeout` has passed, in which case recovery runs on those jobs (the X.2.1.6 SQL, filtered by slot).
6. When every slot is `STABLE` at `map_epoch=M+1`: `current_map_epoch=M+1`, `state='STABLE'`. Partitions that own no slots become `RETIRED`.

Turning partitioning **off** is a map change to the single virtual partition `0`, whose state is `SHARED`. `SHARED` means it is acquired through the default path by every node. Turning it **on** is the reverse. Neither change requires downtime.

Invariants checked continuously (metrics and T52):
- **I-1**: every slot is mapped (X.2.1.2).
- **I-2**: at most one live partition owner per epoch. This follows from the claim CAS.
- **I-3**: no job is executed to commit twice. This follows from the job CAS.
- **I-4**: no due job waits longer than `poll + drainTimeout + orphanTimeout` because of a rebalance.

#### X.2.1.8 Rules

1. Partition ownership never replaces L2 or `lock_version`. A partitioned executor that skips either of them is a defect (ArchUnit rule: the only job-execution entry point is `JobExecutionCommand`).
2. Partition leases are rows, never open transactions held for the length of the lease (the same principle as A-4).
3. An owner change never changes `operationId` (VI.3 rule 4). Partitioning has no effect on worker-visible identities.
4. Per tenant: with DB-per-tenant, each tenant database has its own `EXEC_*` tables and its own slot map (the same as A-5 for the outbox).
5. Metrics: `executor_partition_owner_changes_total{reason}`, `executor_stale_epoch_rejections_total`, `executor_jobs_recovered_total`, `executor_slot_moving_seconds`, `executor_orphaned_partitions`. Owner changes are also audit events (class B, `EXECUTOR_OWNERSHIP_CHANGED`).

#### X.2.1.9 Dialects

| DB | Claim / CAS | Skip-locked selection | Idle-transaction guard |
|---|---|---|---|
| PostgreSQL | `UPDATE … RETURNING` | `FOR UPDATE SKIP LOCKED` | `idle_in_transaction_session_timeout` |
| Oracle | `UPDATE … RETURNING … INTO` | `FOR UPDATE SKIP LOCKED` (two-step per R2: select ids, then update by id) | Profile `IDLE_TIME` plus JDBC query timeout; correctness rests on the CAS |
| SQL Server | `UPDATE … OUTPUT inserted.owner_epoch` | `WITH (UPDLOCK, READPAST, ROWLOCK)` on an index seek (R2) | No server-side idle timeout; the connection pool's `abandonedTimeout`; correctness rests on the CAS |
| MySQL 8 / MariaDB 10.6+ (Tier 2) | `UPDATE` + `ROW_COUNT()` | `FOR UPDATE SKIP LOCKED` | `innodb_lock_wait_timeout`, `wait_timeout` |

#### X.2.1.10 Acceptance

- **T41** (VIII.4): rebalance with epochs. Kill the owner of 4 of 8 partitions under load. Pass when the new owners hold `epoch+1`, every in-flight job runs to commit exactly once, and the stale-epoch rejection counter matches what the stub recorded.
- **T51**: a paused stale owner. Pause an owner process (SIGSTOP) for 3 × `leaseTtl`, then resume it. Pass when **zero** writes from the old epoch commit after takeover, the resumed node fences itself within one renewal interval, and the job CAS is the only accepted path.
- **T52**: partition-count changes under load. Go 8 → 12 → 4 → off → 8 with 100,000 jobs and timers in flight on every Tier-1 DB. Pass when invariants I-1…I-4 hold throughout, no job is stranded, and no job commits twice.
- **T53**: node loss in mid-execution, with in-flight jobs and a poison job. Pass when recovery finishes within `leaseTtl + orphanTimeout + 1 poll`, recovered jobs keep their retries, and the poison job reaches `JOB_RECOVERY_LOOP` after `maxRecoveries`.
- **T54**: the benefit gate. Run the default path against the partitioned path on the same hardware and the R5 reference workload. Partitioning may be enabled for a release only if the X.2.1.1 thresholds are met.

---

### X.2.2 R41 (E12): API idempotency lifecycle

#### X.2.2.1 Scope

| Item | Rule |
|---|---|
| **Applies to** | Every state-changing REST command (ADR-D-5: REST first): process start, message correlation, signal, user-task claim/complete/approve/reject/return, variable update, cancel, delete, modify, migrate/batch creation, deployment lifecycle commands (X.2.4), and administrative repair commands |
| **Does not replace** | Worker calls are fenced by `(taskId, lockVersion, attemptId)` (VI.3). Within that contract, a repeat of an identical `complete` with the same `(taskId, lockVersion, attemptId)` after success returns the stored success through this mechanism, with the implicit key `attemptId:complete`. External effects use `operationId` (VI.3, A-2), which is derived from engine identity, **never** from a client's idempotency key |
| **Header** | `Idempotency-Key`, following the IETF httpapi *Idempotency-Key* draft. 1–128 printable ASCII characters, opaque to the server; UUIDv4/v7 is recommended |
| **Required or optional** | Per command type and tenant profile. The **government profile** requires it for start, correlate, complete/approve/reject/return, cancel, delete and deployment commands. A missing key there gets `400 IDEMPOTENCY_KEY_REQUIRED`. Elsewhere it is optional, and a request without a key has no replay protection |
| **Scope of a key** | **`(tenant_id, command_type, idempotency_key)`**. `tenant_id` comes from the authenticated identity (VI.4 rule 6), never from the request body. `command_type` is a fixed name, e.g. `process.start` or `task.complete`. The target (definition key, task id) is part of the **fingerprint**, not the scope |
| **Principal** | The principal that first used a key is recorded. The same key from a **different principal** gets `422 IDEMPOTENCY_KEY_PRINCIPAL_MISMATCH`, with no stored data disclosed. A service account that runs on several pods counts as one principal |

#### X.2.2.2 Fingerprint

`fingerprint = SHA-256( JCS({ commandType, method, pathTemplate, pathParams, semanticQueryParams, body }) )`

- **JCS** is RFC 8785 canonical JSON. Sangam typed JSON (R33) carries decimals and large integers as strings, so canonicalisation never rounds a money value.
- Left out: transport and trace headers (`traceparent`, `User-Agent`, `Accept-Language`), the `Idempotency-Key` itself, and auth tokens.
- **Included:** `If-Match` (an optimistic version precondition is part of the command's meaning) and `X-Sangam-Acting-For-Seat` (IX.5, acting authority).
- The fingerprint function is versioned (`fp_version`). A stored record is always compared using its own `fp_version`.

#### X.2.2.3 Table

```
IDEMPOTENCY(tenant_id, command_type, idem_key,
       fp_version SMALLINT, fingerprint BYTEA(32), principal_id,
       state        -- IN_PROGRESS | COMPLETED | FAILED_FINAL | COMPACTED
       , owner_node, claim_epoch BIGINT, claim_expires_at,        -- DB clock
       http_status SMALLINT NULL, response_body BYTEA NULL,      -- encrypted (tenant key), ≤ 64 KiB, else ref
       response_headers JSONB NULL, resource_ref VARCHAR NULL,   -- e.g. root id, task id, package hash
       created_at, completed_at, response_expires_at, key_expires_at,
       PRIMARY KEY(tenant_id, command_type, idem_key))
-- partitioned or indexed by key_expires_at for cleanup
```

#### X.2.2.4 Algorithm

**Step 1: claim.** This is its own short transaction, with no other locks:

```sql
INSERT INTO IDEMPOTENCY(tenant_id, command_type, idem_key, fp_version, fingerprint, principal_id,
                        state, owner_node, claim_epoch, claim_expires_at, created_at)
VALUES (:t, :c, :k, :fpv, :fp, :pr, 'IN_PROGRESS', :node, 1, now()+:claimTtl, now())
ON CONFLICT (tenant_id, command_type, idem_key) DO NOTHING
RETURNING claim_epoch;
```

If a row was inserted, go to step 2. Otherwise read the existing row and apply the first matching line:

| Existing row | Response |
|---|---|
| `principal_id ≠ :pr` | `422 IDEMPOTENCY_KEY_PRINCIPAL_MISMATCH` |
| `fingerprint ≠ :fp` (compared with its `fp_version`) | `422 IDEMPOTENCY_KEY_REUSED`. No state change; the stored response is not disclosed |
| `COMPLETED` or `FAILED_FINAL` | **Replay** (step 4) |
| `COMPACTED` | **Compact replay** (step 4) |
| `IN_PROGRESS`, `claim_expires_at ≥ now()` | `409 IDEMPOTENCY_IN_PROGRESS`, `Retry-After: 1`. With `Prefer: wait=N` (N ≤ 10 s) the server polls the row and answers as soon as it settles |
| `IN_PROGRESS`, claim expired | **Takeover:** `UPDATE … SET claim_epoch=claim_epoch+1, owner_node=:node, claim_expires_at=now()+:claimTtl WHERE <pk> AND state='IN_PROGRESS' AND claim_expires_at<now() AND fingerprint=:fp RETURNING claim_epoch`. If a row comes back, go to step 2; if not, re-read and re-apply this table. An expired `IN_PROGRESS` row **proves** that the earlier attempt changed no engine state (step 2) |

**Step 2: execute atomically with engine state.** The command transaction follows VI.1: the root set is resolved first, then L1 → L2 → L4, then the L5 inserts, with no remote work while locks are held. **As its last write**, it updates the idempotency row:

```sql
UPDATE IDEMPOTENCY
   SET state='COMPLETED', http_status=:s, response_body=:enc, response_headers=:h, resource_ref=:ref,
       completed_at=now(), response_expires_at=now()+:responseRetention, key_expires_at=now()+:keyRetention
 WHERE tenant_id=:t AND command_type=:c AND idem_key=:k AND claim_epoch=:mine AND state='IN_PROGRESS';
-- 0 rows ⇒ IdempotencyClaimLost ⇒ roll back the whole command (no engine change survives)
```

- **Lock order.** The idempotency row is a new lock domain, **L6, always taken last**. The step 1 claim and takeover transactions hold no other lock. So no cycle can form with VI.1 L1–L5, and the VI.1 rule 1 proof still holds.
- **Atomicity property P-1:** the engine-state change and `COMPLETED` commit together or not at all. With DB-per-tenant, the table lives in the tenant's database. No command spans two databases.
- **Claim TTL.** `claimTtl` (default 30 s) must be greater than the VI.1 watchdog (10 s) plus the retry budget of VI.1 rule 7. Internal VI.1 retries happen inside the same claim. If `claim_expires_at − now() < 2 s`, a command re-checks its claim before it commits.

**Step 3: classify the outcome.**

| Outcome | Stored? | State | Rationale |
|---|---|---|---|
| 2xx success | Yes | `COMPLETED` | Core purpose |
| Deterministic business or validation result: 400 validation, 404 unknown definition/task, 409 version precondition failed (`If-Match`), 410 instance deleted, 422 business rule or form validation | Yes, in its own transaction (no engine state changed) | `FAILED_FINAL` | Repeating the same request gives the same answer; the client must use a new key after fixing the request |
| Transient: `409 CONFLICT_RETRY_EXHAUSTED`, `429`, `503 BACKPRESSURE` (VI.2.4), `500`, DB failover, watchdog timeout | **No**: the row is deleted `WHERE claim_epoch=:mine` | (none) | Engine state rolled back, so retrying is safe and should be allowed at once |
| `401` / `403` | **No**: row deleted | (none) | Authorization is evaluated on every call; storing a denial would freeze it, and storing a grant would leak it |

**Step 4: replay.** A replay **never re-executes** the command. Before returning anything, it:
1. **re-authenticates** the caller;
2. **re-checks authorization** (VI.4 object check, without cache): the caller must still hold the command permission on the target **and** `READ` on `resource_ref`. On failure it returns `403`, without the stored body. Revocation (VI.4 rule 5) therefore applies to replays too;
3. re-applies the **current field masks** (VI.4 rule 4) to the stored body. A replay never shows more than a fresh read would;
4. returns the stored status and body with `Idempotent-Replayed: true`, plus `Idempotency-Key-Expires` (the `response_expires_at` value).

A **compact replay** (after `response_expires_at` but before `key_expires_at`) returns the stored status, `Location`/`resource_ref` and `Idempotent-Replayed: compacted`, with no body. The client reads the resource if it needs more.

**Step 5: retention and cleanup.**

| Field | Default | Range | Notes |
|---|---|---|---|
| `responseRetention` (full body kept) | 24 h | 1 h – 7 d | Per command type |
| `keyRetention` (fingerprint, status and `resource_ref` kept as `COMPACTED`) | 30 d | ≥ `responseRetention`, ≤ 400 d | The government profile defaults to 90 d for `process.start` and payment-relevant commands |

- A cleanup service (leader-elected, R29) turns rows into `COMPACTED` and later deletes them, using daily partitions on `key_expires_at`.
- **After `key_expires_at`** the key is free, and a request that reuses it is a new command. The second defence against a business duplicate is the domain's unique business key: `uniqueStart` (VI.2.6 rule 4), or the duplicate-bill check of P1 (IX.4).
- **Erasure (VI.5):** it scrubs `response_body` and `response_headers` of the roots concerned. The fingerprint and `resource_ref` stay, because they contain no personal data.

#### X.2.2.5 Retries after timeout, failover and restore

1. **Client timeout.** The server may have committed. The client **must** retry with the same key and the same payload until it gets a definite answer (2xx, a stored 4xx or a compact replay), for as long as `keyRetention` lasts. SDKs (all Tier-1, IX.6) generate the key once per logical call, keep it across their own retries, and expose it in logs.
2. **Ambiguous network failure while committing.** Property P-1 covers it: either the row is `COMPLETED` (replay) or it is still `IN_PROGRESS`/absent with no engine change (re-execute after takeover).
3. **DB failover (ADR-D-12, RPO ≤ 5 min).** Idempotency rows and engine state live in the same database, so they are lost or kept **together**. A client that received a success whose commit was lost in failover gets a fresh execution on retry. That is consistent, because the earlier instance no longer exists. External effects of the lost commit are covered by `operationId` reconciliation (VI.3, T27). The operations guide states this explicitly.
4. **Restore from backup:** the same as failover. The idempotency table is never restored separately from engine state.

#### X.2.2.6 Errors

| Code | HTTP | When |
|---|---|---|
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | The key is mandatory for this command or profile |
| `IDEMPOTENCY_KEY_INVALID` | 400 | The key has the wrong length or characters |
| `IDEMPOTENCY_KEY_REUSED` | 422 | Same scope, different fingerprint |
| `IDEMPOTENCY_KEY_PRINCIPAL_MISMATCH` | 422 | Same scope, different principal |
| `IDEMPOTENCY_IN_PROGRESS` | 409 | A live claim is held by another attempt |
| (replay) | stored | `Idempotent-Replayed: true` or `compacted` |

#### X.2.2.7 Rules

1. Property P-1 is mandatory. An endpoint that cannot store its result atomically with its state change may not declare itself idempotent (an OpenAPI `x-sangam-idempotent` flag, checked by a contract test).
2. Audit (R42, class B) records the idempotency key and the replay flag for every command and every replay that returns a stored response.
3. Batch commands are idempotent at batch creation. Batch items get derived keys `key:itemIndex`.
4. Metrics: `api_idempotent_replays_total{command,kind}`, `api_idempotency_conflicts_total{reason}`, `api_idempotency_in_progress_total`.

#### X.2.2.8 Dialects

| DB | Claim insert | Duplicate detection |
|---|---|---|
| PostgreSQL | `INSERT … ON CONFLICT DO NOTHING RETURNING` | No row returned |
| Oracle | Plain `INSERT` | ORA-00001; then read the row (MERGE is not used, because of its race under concurrency) |
| SQL Server | Plain `INSERT` | Errors 2627/2601; then read the row (MERGE without `HOLDLOCK` races) |
| MySQL / MariaDB (Tier 2) | `INSERT IGNORE` | `ROW_COUNT() = 0` |

#### X.2.2.9 Acceptance

- **T01** (III §16): duplicate starts, both simultaneous and after a timeout.
- **T55**: 50 identical concurrent requests on every Tier-1 DB. Pass when exactly one command executes, and every other request gets `409 IDEMPOTENCY_IN_PROGRESS` or a replay with a byte-identical body.
- **T56**: conflicting reuse. Pass when a different payload, or a different principal, with the same key gets `422`, with no state change and no stored body disclosed.
- **T57**: a crash at commit boundaries, using fault injection (a) just before and (b) just after the command commit. Pass when (a) leads to exactly one execution after takeover and (b) leads to a replay of the original response.
- **T58**: retention. Pass when a replay inside `responseRetention` returns the full body, one inside `keyRetention` returns a compact replay, and after `keyRetention` the request is a new execution that `uniqueStart` or a business key still stops from becoming a duplicate business start.
- **T59**: authorization on replay. Revoke the permission, or switch tenant, between the original call and the replay. Pass when the replay gets `403` with no body, and newly masked fields are masked in replays.
- **T60**: outcome classification. Pass when a 422 business error is replayed; a 503 `BACKPRESSURE` or 409 retry-exhausted result is not stored, so an immediate retry executes; and a 401/403 is not stored.

---

### X.2.3 R42 (E13): Audit, diagnostics and replay data

#### X.2.3.1 Four data classes

| | **A: Operational history** | **B: Mandatory audit** | **C: Optional diagnostics** | **D: Replay fixtures** |
|---|---|---|---|---|
| Purpose | Cockpit, reporting, process mining (R15) | Security and business **evidence**; the government record trail | Debugging: payload snapshots, variable diffs, connector request/response bodies, stack traces, debug-agenda traces | **Scenario regression** and engine determinism checks (X.2.3.6) |
| Mandatory? | No: levels none…full and per-element overrides (DESIGN §4.6) | **Yes. It cannot be switched off** by history level, per-element suppression or `external` history mode | No: **off by default in production**; enabled per tenant/definition with an expiry | No: opt-in per definition (`capture=fixtures`), or captured in test environments |
| Written | Through the outbox (default) or synchronously (VI.2, §4.6) | **Synchronously, in the command transaction** as L5 inserts (VI.1), never through the outbox, so an outbox outage cannot lose or delay it | Asynchronously, best effort; it may be dropped under back-pressure (VI.2.4 ≥ 80 %) | Synchronously with the input it records (an L5 insert); a disabled capture records nothing |
| Ordering | Per root by `root_seq` (VI.2) | Per **chain** by a gapless `seq` assigned under L2 (X.2.3.3) | None guaranteed | Per root by `root_seq`, plus the logical time of each input |
| Tamper evidence | None (it is a projection) | **A hash chain per root, plus signed checkpoints** (X.2.3.4) | None | A hash of each fixture bundle |
| Is it evidence? | No | **Yes** | No | No |
| Retention | R29 TTL (X.2.3.5) | Legal minimum or records category (X.2.3.5) | ≤ 30 days | ≤ 30 days in production; unlimited in test |
| Read permission (VI.4) | `READ_HISTORY` | `READ_AUDIT` (separate; every read is itself audited) | `READ_DIAGNOSTICS` (operators) | `READ_FIXTURES` (model engineers) |

**Rule D-0:** no feature may claim to "replay", "time-travel" or "reconstruct" an execution from class A or class C data. Class A is a projection that may omit inputs (E13). Class D is the only class built for re-execution, and it is limited by X.2.3.6.

#### X.2.3.2 Minimum event schema

**Common envelope (all classes):**

| Field | Type | Notes |
|---|---|---|
| `event_id` | UUIDv7 | Globally unique, roughly time-ordered |
| `tenant_id` | id | From the authenticated binding (VI.4 rule 6) |
| `class` | A/B/C/D | |
| `type` | string | e.g. `TASK_APPROVED` |
| `schema_version` | int | Per type (X.2.3.7) |
| `chain_id`, `seq` | id, bigint | Class B: the chain; others: `root_id`, `root_seq` |
| `occurred_at` | timestamptz | **DB clock**, synchronised to the NIC/NPL NTP servers (R51, GOV-3) |
| `root_id`, `instance_id`, `element_id`, `activity_instance_id` | ids | |
| `package_hash`, `artifact_hash` | sha256 | R43: the executable package and the specific artifact (model, decision, form) |
| `actor` | object | `principal_id`, `seat_id`, `acted_for_seat_id` (IX.5 rule 4), `on_behalf_of` (VI.4 rule 7), `auth_method` (Parichay/SAML/OIDC/API key), `client_ip` (class B only) |
| `operation_id`, `attempt_id`, `idempotency_key`, `trace_id` | ids | VI.3, R41, R13 |

**Class B additions (mandatory):** `action`, `outcome` (`SUCCESS | DENIED | FAILED | UNKNOWN`), `reason` (free text in the user's language plus a reason code), `material_payload_hash` (the approval binding, VI.4 rule 5), `decision_ref` (DMN decision id + artifact hash + matched rule ids + output digest, for every rule that **routed** or **authorised** the action, including the delegation-of-powers row and its effective date, IX.5 rule 3), `policy_version` (VI.4), `payload_digest`, `payload_ref`, `purpose_tag` (DPDP, X.2.3.8), `records_category` (A/B/C, X.2.3.5), `prev_hash`, `hash`.

**Class B event types (minimum set):**

| Area | Events |
|---|---|
| Instance | `INSTANCE_STARTED`, `INSTANCE_CANCELLED`, `INSTANCE_DELETED`, `DATA_ERASED`, `INSTANCE_MIGRATED`, `INSTANCE_MODIFIED`, `INSTANCE_CLOSED` (with records classification) |
| Human task | `TASK_CREATED` (seat), `TASK_ASSIGNED`, `TASK_CLAIMED`, `TASK_DELEGATED`, `TASK_ACTED_FOR`, `TASK_APPROVED`, `TASK_REJECTED`, `TASK_RETURNED`, `TASK_COMPLETED`, each with `material_payload_hash` and the form hash (R47); `APPROVAL_INVALIDATED` (T16); `SOD_EXCEPTION_GRANTED` (IX.5 rule 5) |
| Decisions | `ROUTING_DECISION` and `AUTHORITY_DECISION`, for every DMN evaluation that selected an approver, an amount band or a power |
| External effects | `OPERATION_DISPATCHED`, `OPERATION_OUTCOME`, `OPERATION_UNKNOWN`, `RECONCILIATION_ACTION` (VI.3, A-2), `SIGNATURE_APPLIED` (R57: eSign consent and log evidence), `LATE_RESULT_AFTER_DELETE` (VI.5) |
| Security chain (per tenant) | `LOGIN`, `LOGIN_FAILED`, `GRANT_CHANGED`, `DENY_CHANGED`, `MEMBERSHIP_CHANGED`, `SEAT_HOLDER_CHANGED` (transfer, additional charge), `SENSITIVE_READ` (`READ_SENSITIVE`, aggregated per session and resource), `EXPORT`, `READ_AUDIT` |
| Administration | Package lifecycle transitions (X.2.4), `EXECUTOR_OWNERSHIP_CHANGED` and map changes (X.2.1), outbox `skip`/`requeue` repairs (VI.2.3), `DIAGNOSTICS_ENABLED`, `FIXTURE_CAPTURE_ENABLED`, retention and hold actions, `RTI_DISCLOSURE` |

**Class C minimum:** the envelope, plus `kind` (`SNAPSHOT | DIFF | CONNECTOR_IO | ERROR | TRACE`), `body_ref`, `redaction_profile` and `expires_at`.

**Class D minimum:** the envelope, plus `input_kind` (X.2.3.6 table), `logical_time`, `input_digest`, `input_ref` and `pseudonymised: bool`.

#### X.2.3.3 Ordering

1. **Per root.** Class B events of one root form one chain with `chain_id = root_id`. `seq` comes from `RT_ROOT.audit_seq++`, incremented **under L2**, so `seq` order equals commit order and there are no gaps (the same argument as VI.2.2).
2. **Per tenant security chain.** Events with no root (logins, grants, seat changes) use `chain_id = 'sec:' || tenant_id`. That pseudo-root is a row in `RT_ROOT` and is locked as L2 in ascending id order (VI.1 rule 1). Grant changes are infrequent, so this serialisation point is acceptable. Login events are batched per 1 s into one chained `LOGIN_BATCH` event, which keeps them off the hot path.
3. **Closed roots.** The `RT_ROOT` row is kept as a thin header (state `CLOSED`, with the chain head) until the record is disposed of. Post-closure events (RTI disclosure, retention review) lock it as L2.
4. **Across chains** there is no total order. `occurred_at` plus UUIDv7 give an approximate order only. A causal link is stated explicitly with `caused_by_event_id`.

#### X.2.3.4 Tamper evidence

```
AUDIT_EVENT(tenant_id, chain_id, seq BIGINT, event_id UUID UNIQUE, type, schema_version, occurred_at,
       envelope JSONB /* canonical, as stored */, payload_digest BYTEA(32), payload_ref NULL,
       prev_hash BYTEA(32), hash BYTEA(32), PRIMARY KEY(tenant_id, chain_id, seq))
AUDIT_PAYLOAD(event_id PK, salt BYTEA(16), ciphertext BYTEA, key_id, erased_at NULL, erase_reason NULL)
AUDIT_CHECKPOINT(tenant_id, checkpoint_no BIGINT, period_end, merkle_root BYTEA(32), chains_count,
       prev_checkpoint_hash, signature, key_id, exported_to JSONB, PRIMARY KEY(tenant_id, checkpoint_no))
```

1. **Chain.** `payload_digest = SHA-256(salt || JCS(payload))`. Then `hash_n = SHA-256(prev_hash_{n-1} || JCS(envelope_n incl. payload_digest, excl. hash))`. The genesis value is `prev_hash_0 = SHA-256("sangam-audit-v1:" || tenant_id || ":" || chain_id)`.
2. **The payload is outside the chain.** The chain covers the salted digest, not the payload. Erasure (X.2.3.8) deletes the ciphertext **and the salt**, and the chain still verifies. Without the salt, the digest cannot be used to guess the erased values.
3. **Database privileges.** The application role has `INSERT, SELECT` on `AUDIT_EVENT`, with no `UPDATE` or `DELETE`. Disposal runs under a separate `sangam_retention` role, and only through the disposal procedure (X.2.3.5). That procedure first writes a `RECORD_DISPOSED` event, carrying the disposed chain's final `seq` and `hash`, to the tenant security chain.
4. **Checkpoints.** Every 15 minutes (configurable), the engine computes a Merkle root over `(chain_id, last_seq, last_hash)` for every chain that changed. It chains the checkpoint to the previous one, signs it with the engine audit key (PKCS#11/HSM where available, otherwise a keystore with key-use audit), and **exports it outside the engine database**: to the SIEM (NIC PRATIMAAN, R51) and to a WORM or append-only store under a different administrative account. A DBA who rewrites a whole chain is detected at the next comparison with an exported checkpoint.
5. **Verification.** `sangam audit verify --tenant T [--chain C] [--since N]` recomputes the chains, checks that `seq` has no gaps, matches the latest exported checkpoint, and reports the first divergent event. It runs daily, and its result is itself a class B event.
6. Limits are stated plainly. The chain **detects** changes but does not prevent them. Events written after the last export can be truncated undetected until the next checkpoint, so the checkpoint interval is the detection window.

#### X.2.3.5 Retention, records categories and legal hold

| Class | Default | Minimum (hard floor) | Who may shorten it |
|---|---|---|---|
| A: operational history | R29 TTL, 90 days after root end | None, **but a CLOSED government record keeps its class A data while the record exists if the definition marks history `part-of-record`** | Model owner, per definition |
| B: audit, ordinary | 3 years | **≥ 1 year** (DPDP Rule 6 and Rule 8(3), GOV-4) and **≥ 180 days, stored in India** (CERT-In Directions 2022, GOV-3, R51) | No one below the floor |
| B: government record (GOV-11, R61) | By records category (below) | By category; never by TTL | No one; only the review workflow |
| C: diagnostics | 7 days | None; **maximum 30 days** | Operator |
| D: fixtures (production) | 30 days | None; maximum 90 days | Model engineer with DPO approval (X.2.3.8) |

**Government records (GOV-11, CSMOP 2022 §10.4–§10.9, Public Records Act 1993).** These rules **override any generic TTL cleanup**.
1. **Classification at closure.** When an instance of a government pack reaches `CLOSED` (IX.3), the closing officer, or a DMN default the officer can override, sets `records_category`:
   - **A**: permanent;
   - **C**: retained for `retention_years ≤ 10`, counted from the year the file is closed (RRS 2012);
   - **B**: physical files only, for imported or hybrid files.

   The cover marking is stored, e.g. `C/5 – Review 2031`.
2. **The record** consists of the class B chain, the final outputs (orders, certificates), the attachments (R47) and the class A history if it is marked `part-of-record`.
3. **No automatic deletion.** When `review_due` arrives, a **review task** is created for the record-room seat. Its actions are: *destroy* (category C only; needs a second authority), *upgrade to A*, or *extend*. A category A record is reviewed at **year 25** together with NAI. Records that survive the review are **exported to NAI** as PDF/A with SHA-256 checksums and CSV/XML metadata, following the NAI digitisation SOP. They are disposed of only after NAI confirms accession (`TRANSFER_PENDING → TRANSFERRED`).
4. **More than 25 years:** destruction is blocked without an **appraisal workflow** (NAI guidance).
5. **Legal hold.** `LEGAL_HOLD(hold_id, tenant_id, record_id, kind RTI|AUDIT|LITIGATION|VIGILANCE|OTHER, reference, placed_by, placed_at, released_by, released_at)`. While any hold is open, these are all blocked: disposal, class A/C/D cleanup of that record, and **erasure** (VI.5). A hold also stops the TTL of class C data that existed when the hold was placed. Placing and releasing a hold are class B events, and releasing one needs a different person from the one who placed it.
6. **RTI support.** A record linked to an RTI request receives an automatic hold until the reply and appeal windows close (30 days, or 48 h for life or liberty; §7(1)). Records older than 20 years carry the §8(3) flag.
7. **States** may configure their own Public Records Act schedules as versioned retention tables (R43 ruleset, `asOf` the closure date).

```
RECORD_RETENTION(tenant_id, record_id, category A|B|C, retention_years NULL, closed_on, review_due,
       state  -- OPEN | CLOSED | UNDER_REVIEW | TRANSFER_PENDING | TRANSFERRED | DISPOSAL_APPROVED | DISPOSED
       , open_holds INT, schedule_ref /* ruleset id + hash */, PRIMARY KEY(tenant_id, record_id))
```

#### X.2.3.6 Replay fixtures: scenario regression is not Temporal replay

**What Sangam does not claim.** Temporal-style replay re-runs workflow *code* against a recorded history to rebuild in-memory state, and it requires the code to be deterministic. Sangam state is **persisted** (runtime tables under L2), and models are interpreted. Recovery therefore never depends on replay, and Sangam does **not** offer Temporal replay semantics (E13).

**What fixtures support:**

| Mode | Input | Package | Expected result | What a pass proves |
|---|---|---|---|---|
| **Reproduce** | A recorded fixture | **The same `package_hash`** | Identical routing decisions, task assignments (seats), DMN outputs and emitted operations | The engine is deterministic for the observed path. Used for bug reproduction and the T42 check |
| **Scenario regression** | A recorded fixture | **A new package** | A diff report: same, diverged at element X, or input no longer applicable | Only that the **observed** paths behave as reported. **It says nothing about unobserved branches**, and a divergence is information, not automatically a failure |

**Inputs a fixture must capture** (completeness rule):

| Input kind | Captured as |
|---|---|
| Start variables, business key | Value (pseudonymised in production) |
| User-task submissions | Form hash + submitted data + actor seat |
| Message and signal arrivals | Payload + arrival order relative to `root_seq` |
| External-task results | Result, BPMN error or `outcomeUnknown`, plus the resolution |
| Timer firings | Logical fire time (not wall-clock execution time) |
| Clock reads in FEEL (`now()`, `today()`) | The value returned |
| Generated ids used in data | The value |
| **Live-evaluated policies** (X.2.4.3): seat holder, authorization, delegation-of-powers lookups, holiday calendars | The **outcome** at evaluation time, with ruleset version |
| DMN results | Recomputed in reproduce mode and compared with the recorded output digest |

A reproducer that meets an input event the fixture does not hold must **fail with `FIXTURE_INCOMPLETE`**. It must never fall back to a live value.

#### X.2.3.7 Schema evolution

1. Every event type has a JSON Schema in the platform schema registry, keyed `(type, schema_version)` and shipped with the engine. Within a major version only additive changes are allowed (new optional fields). A rename or a change of meaning is a **new type**.
2. **Stored class B events are never rewritten.** Readers apply **upcasters** (v_n → v_n+1) on read. The hash is computed over the stored canonical bytes, so upcasting never affects verification.
3. Readers ignore unknown fields. A schema version is never removed while events of that version exist; the registry keeps retired schemas for as long as the longest retention (category A: permanent).
4. The NAI export (X.2.3.5) writes the schema used for each event into the CSV/XML metadata.

#### X.2.3.8 Privacy (DPDP Act 2023 and Rules 2025, GOV-4, R53)

1. **Purpose tag.** Every definition declares a `purpose_tag`: an s.7(b) scheme, licence or permit id, a consent id, or s.7(c) plus the law. It is copied into every class B event, and an instance without one cannot start in the government profile.
2. **Personal-data marking** comes from the form data schema (R47: `x-sangam-personal`, `x-sangam-sensitive`) and from variable type definitions (R43). Marked fields are:
   - encrypted at field level in runtime, history and payload stores, with tenant keys;
   - masked in class A projections unless the reader has `READ_SENSITIVE`;
   - **excluded from class C** unless diagnostics for that tenant are explicitly enabled with `includePersonal=true` (a class B event, maximum 7 days);
   - **pseudonymised in production fixtures** (deterministic tokenisation per fixture bundle). Capturing non-pseudonymised production fixtures is rejected unless a DPO approval references the bundle.
3. **Child data** (s.9): a children's-records flag blocks analytics export (R15) and class D capture.
4. **Erasure** (VI.5) scrubs class A/C/D payloads, stored idempotency responses (R41) and `AUDIT_PAYLOAD` (ciphertext and salt). The class B envelope and digests stay. **Erasure is refused** while a legal hold is open or a category A record exists, because records law prevails and the State carve-out of s.17(4) applies. The refusal is itself an audited event with a reason. Erasure is configurable and can be disabled per tenant (GOV-4 design implication).
5. **Breach support.** The class B security chain and access events are the evidence source for the 6-hour CERT-In report and the 72-hour DPDP Board report (R51, R53).
6. **Residency.** Classes A–D and the checkpoint exports stay in India (R56). Exporting to a SIEM is configuration, never default outbound telemetry.

#### X.2.3.9 Acceptance

- **T42** (VIII.4): fixture completeness. For each input kind in X.2.3.6, remove it from a recorded fixture. Pass when the reproducer fails with `FIXTURE_INCOMPLETE` every time, and reproducing on the same package gives identical decisions on all three Tier-1 DBs.
- **T30** (III §16): retention and erasure with holds and referenced objects, extended by T63/T64.
- **T61**: tamper detection. Edit an event's envelope directly in the DB; then delete one, reorder two, insert one, and truncate a tail before and after a checkpoint. Pass when `audit verify` reports the first divergent `seq` in every case except a tail truncated after the last checkpoint, which is detected at the next checkpoint comparison.
- **T62**: audit independence. Use history level `none`, per-element suppression, `external` history mode and a 1-hour outbox outage. Pass when every class B event is still present, in commit order, with a gapless `seq`.
- **T63**: erasure and the chain. Erase a citizen's data on a category C record with no hold. Pass when the payloads disappear from runtime, history, diagnostics, fixtures, idempotency responses and the object store; the chain still verifies; and with a hold or category A in place, erasure is refused and the refusal is audited.
- **T64**: GOV-11 lifecycle. Pass when all of these hold:
   - a category C record at `review_due` creates a review task and nothing is deleted;
   - destroy needs two authorities;
   - a category A record older than 25 years cannot be destroyed without appraisal;
   - the NAI export validates as PDF/A (veraPDF), and the checksums and metadata match;
   - an RTI-linked record stays held.
- **T65**: schema evolution. Write events at v1, deploy an engine with v2 plus an upcaster, and read and verify. Pass when the chain verifies over the stored bytes, readers see v2, and a reader without the upcaster still parses v1.
- **T66**: privacy of classes C and D. Pass when production diagnostics without `includePersonal` contain no marked field; fixture capture without pseudonymisation or DPO approval is rejected; child-flagged records produce no fixtures; and TTLs (7 and 30 days) are enforced.

---

### X.2.4 R43 (E14): Executable package versioning

#### X.2.4.1 What is pinned

An **executable package** is an immutable, signed manifest listing hash-addressed artifacts. A running instance is bound to exactly one `package_hash` (`RT_ROOT.package_hash`). The engine resolves every reference through that package or through an explicit binding (X.2.4.2). Nothing is resolved from "whatever is newest" unless the binding says so.

| Artifact | Identity in the manifest | Default binding | Notes |
|---|---|---|---|
| BPMN / CMMN model(s) | `id`, `sha256` | **Frozen** | Unsupported constructs are rejected at deployment (R44, E15, T20) |
| Called processes and cases (call activity, case task) | target key | **`deployment`** (from the same package) | Also: `version`, `versionTag`, `latest`, see X.2.4.2 |
| DMN decisions, DRG, BKMs, imported DMN | `id`, `sha256` | **Frozen** for computation (eligibility, fees, entitlement) | Delegation of powers is a ruleset, see the delegation-of-powers row below |
| FEEL profile | `sangam-feel-<major.minor>` | **Frozen** (semantics) | Pins null handling per context, decimals, temporal types (E18, R33). It pins the **semantics, not the binary**: evaluator security fixes are applied, and the profile regression suite proves the semantics are unchanged |
| Forms | data-schema, layout and field-policy `sha256` (R47) | **Frozen** | Bound to each user task at creation (X.2.5.8) |
| Worker contracts | `topic`, `contractVersion` (semver), input/output schema `sha256`, capability class (A-2 a/b/c), `effect` | **Frozen** major version | Workers advertise the contract majors they support. Acquisition matches on topic **and** major version |
| Connector templates | template id, version, `sha256` | **Frozen** | Environment endpoints and credentials are **not** in the package (X.2.4.5) |
| Scripts (for the external script worker, D-8) | `sha256` | **Frozen** | |
| Type definitions (item definitions, variable JSON Schemas), message and signal definitions | `sha256` | **Frozen** | Personal-data markings (X.2.3.8) live here |
| Output templates (sanction order, licence, notification and DLT templates, R60), i18n bundles (en, hi, state language) | `sha256` | **Frozen** | Bilingual completeness is checked at deployment (R54) |
| **Policy references**: authorization policies, SoD rules, exclusivity scopes | `policyRef`, `minVersion` | **Live** (X.2.4.3) | Authority must stay revocable (VI.4 rule 5) |
| **Delegation-of-powers tables** (IX.5 rule 3) | ruleset id + **interface hash** | **Live, effective-dated** | The package pins the table's **interface** (inputs, outputs, decision id). The **content** is a separately governed ruleset with its own lifecycle and effective dates |
| Calendars (holidays, working hours) | ruleset id | **Live, effective-dated** | A due date already computed is not recalculated unless `recalculateOnCalendarChange=true` |
| Retention schedules (X.2.3.5) | ruleset id | **`asOf` the closure date** | |

#### X.2.4.2 Binding modes

| Mode | Meaning | Allowed for |
|---|---|---|
| `frozen` (`deployment`) | The artifact inside this package, by hash | Everything; the default |
| `version` / `versionTag` | A named version of another package, resolved **at deployment** and recorded in the manifest as a hash | Called processes, shared decisions |
| `asOf(<FEEL date>)` | The ruleset version effective on a business date from process data, e.g. `=application.submittedOn` | Rulesets (fees, schedules, entitlement) where law fixes the governing date |
| `live` | The version effective **at the moment of action**, by DB clock | Policies, seat holders, delegation of powers, calendars |
| `latest` | Newest ACTIVE version, resolved at call time and recorded on the child root | Only with `allowLatestBinding=true`. Produces a deployment warning. **Forbidden in the government profile** on any path that leads to an approval or an external effect |

Every resolution of `asOf`, `live` or `latest` records the resolved version and hash in the class B or class D event that used it (X.2.3), so the decision can be explained afterwards.

#### X.2.4.3 Live vs frozen: which and why

**Principle.** *Whatever computes a business outcome is frozen, so it can be reproduced. Whatever grants authority is live, so it can be revoked.*

| Live at time of action | Effect when it changes in mid-flow |
|---|---|
| Grants, denies, memberships (VI.4, A-3) | The next sensitive action is re-checked in the transaction (VI.4 rule 5). Lists follow within ≤ 5 s |
| Seat holder (transfer, additional charge, leave; IX.5 rules 1 and 4) | The task stays with the **seat**. The new holder sees it; the old holder loses it at the effective time |
| Delegation of powers | **At the time of action** the seat's power is re-evaluated. If the power is now insufficient, the action is refused (`403 POWER_INSUFFICIENT`) and the task follows the model's `onAuthorityChange` = `hold-and-escalate` (default), `reroute` (the routing DMN is evaluated again) or `deny`. **Approvals already given are not invalidated** by a later rule change, because they were valid when given, unless the package declares `revalidateOnRuleChange=true` |
| SoD rules | Checked at each action by person **and** seat (IX.5 rule 5, T17) |
| Calendars | New due dates use the new calendar; existing timers follow the X.2.4.1 calendars row |
| Suspension or kill switch on a package (X.2.4.4) | Applies at once |

#### X.2.4.4 Lifecycle

```
DRAFT --submit--> IN_REVIEW --approve--> APPROVED --deploy(env)--> DEPLOYED --activate(at)--> ACTIVE
IN_REVIEW --reject--> DRAFT(new revision)        DRAFT --withdraw--> WITHDRAWN
ACTIVE --activate(newer)--> RETIRED (no new starts; running instances run off)
ACTIVE --suspend--> SUSPENDED --resume--> ACTIVE   (emergency: no new starts; optional job hold)
RETIRED --last instance ended--> ARCHIVED (artifacts kept per retention X.2.3.5)
```

| Transition | Permission | Conditions | Audit (class B) |
|---|---|---|---|
| edit (DRAFT only) | `MODEL_AUTHOR` | `If-Match: <revision>`; a stale revision gets `409` with a three-way semantic diff. An optional soft edit lease shows "being edited by" | `PACKAGE_DRAFT_SAVED` (low level) |
| submit | `MODEL_AUTHOR` | Deployment validation passes (X.2.4.6). **The content hash is frozen from here on**; any change makes a new draft revision | `PACKAGE_SUBMITTED` |
| approve / reject | `MODEL_REVIEWER`, `MODEL_APPROVER` | Reviewer ≠ author; approver ≠ author; both by **person and seat**. The semantic diff against the current ACTIVE version has been viewed (recorded) | `PACKAGE_APPROVED` + signature over `package_hash` |
| deploy | `MODEL_DEPLOYER` | **Deployer ≠ author.** Only an APPROVED hash can be deployed. The target environment verifies the signatures and recomputes the hash | `PACKAGE_DEPLOYED{env}` |
| activate | `MODEL_DEPLOYER` (+ `MODEL_APPROVER` in production) | Optional `activate_at`, e.g. the effective date of a government order. One ACTIVE version per `(tenant, key)` for new starts | `PACKAGE_ACTIVATED` |
| suspend / resume | `MODEL_OPERATOR` | Reason required | `PACKAGE_SUSPENDED` |
| retire / archive | System | Automatic | `PACKAGE_RETIRED`, `PACKAGE_ARCHIVED` |

A setting `sod=relaxed`, where one person may author and deploy, is allowed only in environments marked `dev`. **Production refuses to start** if any tenant has it.

#### X.2.4.5 Hash addressing, signatures and environment promotion

```
ARTIFACT(sha256 BYTEA(32) PK, kind, media_type, size, bytes /* or object-store ref */, created_at)   -- immutable, shared
PACKAGE(tenant_id, key, version /* semver */, revision, package_hash BYTEA(32), state, manifest JSONB,
        created_by, submitted_at, approved_by, deployed_by, activated_at, retired_at,
        PRIMARY KEY(tenant_id, key, version), UNIQUE(tenant_id, package_hash))
PACKAGE_SIGNATURE(package_hash, role /* author|reviewer|approver|deployer */, principal_id, seat_id,
        alg, key_id, signature, signed_at)
ENV_BINDING(tenant_id, env, name, version, value_ref /* endpoint, secret reference */, state, approved_by)
```

Manifest sketch:

```yaml
package: { key: p1-bill-approval, version: 2.3.0, tenant: dept-x }
profile: { bpmn: sangam-bpmn-1, feel: sangam-feel-1.0, engine: ">=1.0 <2.0" }
artifacts:
  - { path: bill.bpmn, kind: bpmn, id: BillApproval, sha256: "…" }
  - { path: amount-band.dmn, kind: dmn, id: AmountBand, sha256: "…" }
  - { path: bill.form.json, kind: form-layout, sha256: "…" }
  - { path: bill.schema.json, kind: form-data, sha256: "…" }
bindings:
  - { from: "callActivity:Payment", to: p1-payment, mode: deployment }
rulesets:
  - { id: dfp-financial-powers, interfaceSha256: "…", mode: live }
  - { id: fee-schedule, interfaceSha256: "…", mode: asOf, asOf: "=bill.submittedOn" }
contracts:
  - { topic: treasury.post-payment, contractVersion: 1.4.0, capability: a, effect: irreversible, sha256: "…" }
policies: [ { ref: dept-x/sod-maker-checker, minVersion: 7, mode: live } ]
envBindings: [ treasury-endpoint, esign-asp ]          # names only; values live in ENV_BINDING
```

- `package_hash = SHA-256(JCS(manifest without signatures))`. Artifact hashes are inside the manifest, so the package hash commits to every byte.
- **Promotion** (dev → test → staging → prod) moves the **same bytes**. There is no rebuild per environment. The target verifies signatures and recomputes the hash; a mismatch gets `422 PACKAGE_HASH_MISMATCH`. Production may require attached evidence references, e.g. test-run ids for T19/T20 on that hash, as a configurable gate.
- Environment-specific values live only in `ENV_BINDING`, which has its own approval step (deployer ≠ binding approver in production). A package can reference bindings only by name. A missing binding blocks activation.
- **Air-gapped installs** (T50) import packages as signed bundles, using the same verification.

#### X.2.4.6 Deployment validation (closure check)

A package is rejected (`422 PACKAGE_INVALID`, with a list of findings) if any of these is true:
1. a reference (called element, decision, form, topic, connector, type, template, ruleset interface, policy) does not resolve inside the manifest or through a declared binding. **There is no fallback to latest**;
2. a construct is outside the release profile (R44);
3. the FEEL type check fails (R33), or an expression uses a function outside the pinned FEEL profile;
4. form keys do not match user-task variables (R47), or bilingual strings are incomplete in the government profile;
5. a list-visible policy cannot compile to SQL (VI.4 rule 1);
6. a contract with `effect=irreversible` lacks a declared capability class (A-2);
7. `latest` is used where X.2.4.2 forbids it.

#### X.2.4.7 Rollback and run-off

1. **Rollback** means activating an earlier version, or a new version whose content equals it. New starts go to that version. **Running instances are never silently rebound.**
2. Instances on the faulty version **run off** by default. The other options are each explicit and audited:
   - `suspend` the faulty package;
   - `migrate` with a migration plan (R48, validated against T21 invariants, with a form mapping per X.2.5.8);
   - `cancel` (VI.5).
3. **Run-off support.** A RETIRED package stays executable until it has no instances left. Its artifacts and FEEL profile must stay available. An **engine upgrade preflight** (R32, T28) blocks the upgrade if it would drop a FEEL profile, contract major or construct still used by a non-archived package. The preflight lists the affected instances.
4. **Worker contracts during run-off.** The worker fleet must keep serving every contract major that a non-archived package uses. The cockpit shows "contracts in use by version" so a worker cannot be retired too early.

#### X.2.4.8 Acceptance

- **T19** (III §16): a DMN, rule or form is updated while a process is active, and the explicit binding is honoured. That covers frozen, `asOf` and `live`.
- **T21** (III §16): migration preserves invariants; this extends it with form mapping and version-binding changes.
- **T28** (III §16): a rolling N/N-1 upgrade. Run-off packages keep executing, and the preflight blocks an upgrade that would drop a profile in use.
- **T67**: closure and no silent drift. Pass when deployments with an unresolved reference, with `latest` on an approval path (government profile), or with an unsupported construct are rejected; and when a change to a shared decision in the repository leaves running instances on their frozen hash unaffected.
- **T68**: live versus frozen in mid-flow. In one test run:
   - revoke a grant;
   - amend the delegation-of-powers table, effective tomorrow;
   - change the fee schedule.

   Pass when the revoked approver is denied; actions after the effective date use the new limit (`POWER_INSUFFICIENT` leads to hold-and-escalate) and approvals given earlier stay valid; the fee stays the one in force on the submission date; and every resolution is recorded in the audit.
- **T69**: separation of duties in the lifecycle. Pass when the author cannot review, approve or deploy their own package (checked by person **and** by seat, including acting-for), and production refuses to start with `sod=relaxed`.
- **T70**: promotion integrity. Pass when a changed artifact byte, a missing signature or an unknown key causes rejection in the target environment; a missing `ENV_BINDING` blocks activation; and an air-gapped bundle import verifies the same way.
- **T71**: rollback and run-off. Activate v3, then roll back to v2. Pass when new starts use v2, v3 instances run to completion, the RETIRED v3 package still executes after an engine minor upgrade, and v3 becomes ARCHIVED only at zero instances.

---

### X.2.5 R47 (E19): Forms contract

#### X.2.5.1 Three separate artifacts, one form version

| Artifact | Format | Role | Authority |
|---|---|---|---|
| **Data schema** | JSON Schema 2020-12, restricted to the *Sangam form-data profile* | **The contract** for submitted data: types, required fields, enums, patterns, ranges, cross-field rules | **Authoritative**, validated on the server |
| **Layout schema** | form-js JSON, with its `schemaVersion` pinned per engine release | Presentation: components, grouping, order, conditional visibility, labels as i18n keys | **None.** It is presentation only. form-js is its own JSON format, not JSON Schema (E19, [S28]) |
| **Field policy** | Sangam YAML/JSON | Per user task (or start form): for each field `editable | readonly | hidden | masked`, by role, seat or state | **Authoritative**, enforced on the server. **Default deny:** a field not listed is `hidden` |

Also in each form version: **i18n bundles** (en, hi, plus the tenant's state languages) and **extension declarations**. Custom components come only from the approved component registry, by id, version and hash. Arbitrary JavaScript in forms is not allowed.

The **form version** is the hash triple `(data_schema_sha256, layout_sha256, field_policy_sha256)` plus the bundle hashes. It is part of the executable package (X.2.4.1) and is frozen.

**Data-schema profile annotations:**

| Annotation | Meaning |
|---|---|
| `format: decimal` (with `x-sangam-scale`), `format: integer-string`, `format: date`, `format: local-date-time`, `format: date-time` | R33 typed JSON. Money is never a binary float, and large integers are strings (E18, T18) |
| `x-sangam-material: true` | The field is included in the **approval payload hash** (VI.4 rule 5). A change invalidates earlier approvals (T16, AC-02) |
| `x-sangam-personal`, `x-sangam-sensitive`, `x-sangam-purpose` | Privacy marking (X.2.3.8): encryption, masking, exclusion from diagnostics and fixtures |
| `x-sangam-rules` | Cross-field rules as FEEL expressions (pinned profile), each with an error code and an i18n key |
| `x-sangam-attachment` | Attachment field constraints (X.2.5.4) |
| `additionalProperties: false` | Required at every object level in the government profile |

**Deployment checks:**
- every layout key resolves to a data-schema path;
- every required data field is either in the layout or supplied by the system or a prefill;
- every field has a field-policy entry for each task that uses the form;
- every label, help text, enum label and error key exists in **en and hi** in the government profile, and in the configured state languages when `requireStateLanguage=true`.

#### X.2.5.2 Server-side validation (authoritative)

Client-side validation, generated from the same schema, is a UX aid only. **A client-side hidden or disabled field is not an authorization boundary** (E19).

When a user submits a form (complete, approve, return, start), the server runs the following pipeline inside the command (VI.1). Steps 2–7 run before any state change, and step 8 is atomic with the state change:
1. **Authenticate and authorize** the task action, re-checked in the transaction (VI.4 rule 5). Check the seat or acting-for authority (IX.5) and power (X.2.4.3).
2. **Resolve the form version** from `task.form_version`, never from the client.
3. **Structural limits:** size, depth and string lengths (R11). String limits are counted in Unicode code points after **NFC normalisation**.
4. **Unknown fields** get `422 UNKNOWN_FIELD`. They are rejected, not silently dropped.
5. **Field policy.** A value for a `readonly`, `hidden` or `masked` field that differs from the current server value gets `403 FIELD_NOT_WRITABLE`. Equal values are ignored.
6. **Conditions.** The server evaluates every visibility and requiredness condition (FEEL, pinned profile) over the submitted data plus the process context. Submitted values for **inactive** fields are rejected (`422 INACTIVE_FIELD`), or cleared when the field declares `onHidden: clear`. They are never written to state.
7. **Validation.** JSON Schema validation, then the `x-sangam-rules`, then the attachment checks (X.2.5.4). All errors are returned at once as a list of `{path, code, messageKey, params}`, localised to the user's language.
8. **Commit.** Write the variables, compute `material_payload_hash`, and emit a class B event carrying the form version and the data digest (X.2.3).

**Condition parity.** form-js evaluates conditions in the browser with a JavaScript FEEL implementation, while the server uses the pinned Java profile. Where they disagree, **the server wins**, and the response tells the client which fields are active. A parity corpus (part of T72) runs every pilot form's conditions on both evaluators, and a mismatch fails the build.

#### X.2.5.3 Conditional fields

1. Conditions may reference only fields of the same form version and declared, read-permitted process context variables. A deployment check enforces this.
2. **Hidden by condition** (not applicable now) is different from **hidden by policy** (not permitted for this actor). Only policy is about authorization. A field hidden by policy is never sent to the client, even when its condition is true.
3. A field that changes from active to inactive during editing is cleared or kept according to `onHidden` (default `clear`). The server's view is final.
4. Requiredness that depends on a condition is enforced only by the server's condition evaluation (step 6).

#### X.2.5.4 Attachments

```
ATTACHMENT(attachment_id UUID PK, tenant_id, root_id NULL, draft_id NULL, task_id NULL, field_path,
       uploader_principal_id, uploader_seat_id, object_key, sha256 BYTEA(32), size, declared_mime, sniffed_mime,
       state -- PENDING_UPLOAD | PENDING_SCAN | AVAILABLE | QUARANTINED | REJECTED | SUPERSEDED | ERASED
       , scan_engine, scan_result, created_at, available_at, records_category NULL)
```

1. **Upload slot.** The client calls `POST /attachments {taskId|draftId, fieldPath, name, size, mime}`. The server checks the field's `x-sangam-attachment` rules: allowed MIME types, maximum size, maximum count, maximum pages for PDF. It then creates the row, chooses the **object key itself** (`tenant/{tenant_id}/att/{attachment_id}`; never chosen by the client), and returns a pre-signed PUT URL. That URL is bound to that key, the declared length and the content type, and it expires after 15 minutes.
2. **Object store.** S3-compatible on-premise (e.g. MinIO) or a NIC/empanelled cloud store, inside India (R56). It is encrypted per tenant. Small installations may use a database blob store behind the same interface.
3. **Scan before use.** On upload completion: type sniffing (magic bytes must match the declared type and the allowlist), then an AV scan (ClamAV or the department's AV through ICAP), then an active-content policy (PDF JavaScript/launch actions and Office macros are rejected, or sanitised where the field allows it). The outcome is `AVAILABLE`, `QUARANTINED` (the user is told and an audit event is written) or `REJECTED`. A scan timeout leaves the file in `PENDING_SCAN`. **A form that references an attachment that is not `AVAILABLE` gets `422 ATTACHMENT_NOT_READY`.**
4. **Reference in data:** `{attachmentId, sha256, name, size, mime}`. On submit, the server checks that the attachment belongs to the **same tenant**, to the same root (or to the draft being submitted), to the same `field_path`, and to an uploader who is the actor or the actor's seat, and that its `sha256` matches. Otherwise it returns `403 ATTACHMENT_FOREIGN` or `422`. This covers file-reference spoofing (T13).
5. **Download** needs `READ` on the root plus read permission on the field (VI.4). It uses short-lived URLs (5 min), `Content-Disposition: attachment`, and `X-Content-Type-Options: nosniff`. Untrusted HTML or SVG is never rendered inline.
6. **Immutability.** Replacing a document creates a new attachment and marks the old one `SUPERSEDED`. Both are kept, because an approval is bound to a hash. WORM object locking is optional per tenant.
7. **Retention and erasure:** attachments are part of the record (X.2.3.5). Legal hold and erasure apply as in VI.5 and X.2.3.8. Orphan uploads (never referenced by a submission) are deleted after 24 h.

#### X.2.5.5 Field-level authorization

1. The field policy is evaluated on the server for every read and every write, by **role, seat (IX.5) and instance state**.
2. `masked` fields reach the client only in masked form, e.g. `XXXX-XXXX-1234` for an Aadhaar-like identifier (R50). The full value needs `READ_SENSITIVE`, which writes a `SENSITIVE_READ` audit event.
3. The same projection masks apply in task views, lists, search, export, history, diagnostics and idempotency replays (VI.4 rule 4, R41). There is no other path.
4. SoD constraints at field level: e.g. the checker may not edit `amount`, but may add `checkerRemarks`.

#### X.2.5.6 Accessibility (GIGW 3.0, WCAG 2.1 AA, IS 17802; R55)

The **legal floor** is WCAG 2.1 AA through GIGW 3.0, plus IS 17802 (RPwD rule 15). The **product target** is WCAG 2.2 AA (R55). These are rules for the renderer and the component library, **and** for each published form:

| # | Rule |
|---|---|
| A-1 | Every input has a programmatic label in the current language. Help text is linked with `aria-describedby` |
| A-2 | Errors: an error summary at the top with links to the fields; each field's error linked with `aria-describedby` and announced through `aria-live="polite"`; focus moves to the summary on a failed submit |
| A-3 | Keyboard: all controls can be operated; the tab order follows the visual order; there are no keyboard traps; focus is visible |
| A-4 | Conditional reveal: newly shown fields are announced, focus is never lost, and removed fields do not keep focus |
| A-5 | Time limits: a session-expiry warning with an extend option at least 2 minutes before expiry (WCAG 2.2.1). The draft is saved automatically before expiry (X.2.5.8) |
| A-6 | Contrast 4.5:1 for text and 3:1 for UI components; reflow at 320 CSS px; text resizable to 200 % without loss |
| A-7 | Date, amount and file inputs: typed date entry as an alternative to the picker; accessible upload with progress and scan status |
| A-8 | `lang` attribute on the page **and** on any element whose language differs from the page (GIGW Quality 17), e.g. an English beneficiary name inside a Hindi form |
| A-9 | Generated outputs (orders, certificates) are tagged PDF (PDF/UA target) in the same languages |

**Conformance evidence:**
- automated checks (axe-core) on every component in CI, and on **every published form at deployment** (lint: missing labels, i18n keys, contrast tokens);
- manual testing per release with NVDA and JAWS on Windows, plus TalkBack on mobile, in Hindi and English;
- a filled-in **GIGW 3.0 Annexure II conformance matrix** for STQC certification.

#### X.2.5.7 Languages (GOV-1, R54)

1. **Languages.** Hindi and English are mandatory for central-government tenants. The deployment check rejects missing strings. One or more **state languages** are configurable per tenant (Article 345). The fallback order is user choice → tenant default → English, and in the government profile a fallback never hides a missing Hindi or English string, because deployment would have failed.
2. **Data entry in any script.** Storage is Unicode (UTF-8 / NVARCHAR on all Tier-1 DBs); input is normalised to NFC on the server. Note fields accept Hindi or English with no forced translation (OL Rule 8(1)).
3. **Numerals and amounts.** Numeric and decimal fields accept Devanagari and other Indic digits and normalise them to ASCII before validation. Display uses Indian grouping (12,34,567.00) and the locale's digits by preference. The stored value is always canonical (R33).
4. **Outputs.** Templates support diglot (side-by-side) output (OL Rule 11). By default, replies use the language of the application (OL Rule 7).
5. **Translation help.** An optional BHASHINI connector suggests translations at authoring time only. Machine translation is never applied to submitted data.

#### X.2.5.8 Draft persistence and in-flight form versions

**Drafts:**

```
FORM_DRAFT(tenant_id, draft_id UUID PK, task_id NULL, start_package_hash NULL, form_version,
       owner_kind -- PERSON | SEAT
       , owner_principal_id, owner_seat_id, revision BIGINT, data_ciphertext, key_id,
       updated_at, expires_at, last_editor_principal_id)
```

1. Drafts are stored on the server, encrypted per tenant. They are saved automatically (debounced, at most every 10 s, and on blur or before session expiry). Every save sends `If-Match: <revision>`. A conflict, such as two tabs or two devices, gets `409 DRAFT_CONFLICT` with both versions, so the user can choose.
2. **A draft is not a submission.** It is checked only for size and type. It never becomes process variables and never counts as a decision. Saves are not class B events, but `DRAFT_DISCARDED_BY_HANDOVER` is.
3. **Ownership.** For file movement (P2) and other seat-owned work, the default is `SEAT`: when the seat holder changes, the new holder sees the draft labelled "drafted by …". For citizen and applicant forms, the default is `PERSON`. A draft is never visible to anyone else unless it is explicitly shared.
4. **Expiry.** A draft is deleted 30 days after its last update (configurable). It is also deleted when its task completes, is cancelled or is deleted. Erasure (X.2.3.8) and legal hold apply.

**In-flight form versions:**
1. **Bound at task creation.** `task.form_version` comes from the instance's frozen package (X.2.4). Activating a newer package does not change open tasks or their drafts.
2. **Old versions are served for as long as they are used.** The UI fetches the form by version hash. The server keeps serving RETIRED versions during run-off (X.2.4.7).
3. **Historic rendering.** A completed task is displayed with **the form version used to complete it**, so the record shows what the officer saw and signed (X.2.3).
4. **Start forms.** A start submission must carry the form version of the currently ACTIVE package, or one in `acceptStartFormVersions` (default: the immediately previous version, for 24 h after activation). Otherwise it gets `409 FORM_VERSION_OUTDATED`. The server offers a **draft upgrade**: values are copied by matching data-schema paths, and unmatched fields are reported to the user. Nothing is submitted silently.
5. **Migration (R48).** A migration plan that changes the form of open tasks must include a **form mapping**: path-to-path, or a FEEL transform per field. It is validated against the new data schema for every open task and draft. Without a mapping, open tasks keep their old form version. A mapping that fails for any task rejects the plan (T21).

#### X.2.5.9 Extension points

| Extension | Allowed form | Constraint |
|---|---|---|
| Custom component | A registered component (id, version, hash) from the approved library | Must pass the accessibility rules A-1…A-9 and axe CI. No network access from the component except through the Sangam API |
| Custom validator | Server-side only: a FEEL rule, or a **validator worker** (external task, VI.3, synchronous timeout ≤ 3 s, idempotent) | Client-only validators are not allowed |
| Prefill / lookup | A named-query handler (R25) or a connector through a worker | Must respect field policy and tenant binding. Results are typed by the data schema |

#### X.2.5.10 Acceptance

- **T16** (III §16): a material field (`x-sangam-material`) changed after approval invalidates the approval (AC-02).
- **T19** (III §16): a form is updated while the process is active. Open tasks keep their bound version.
- **T32** (III §16): a first-time user completes the pilot flows, including errors, in Hindi and English with keyboard and screen reader (AC-10).
- **T72**: server-side validation bypass. Send raw API submissions that bypass the client:
   - an unknown field;
   - an inactive conditional field;
   - a changed readonly field;
   - a missing field required by a condition;
   - a decimal sent as a binary float;
   - an oversized string or a deep nesting;
   - Devanagari digits in an amount.

  Pass when each is rejected or normalised exactly as X.2.5.2 says, and the browser/server FEEL condition parity corpus has no mismatches.
- **T73**: field authorization and masking. Pass when `masked` and `hidden` fields are absent, or masked, in task views, lists, search, export, history, diagnostics and idempotency replays; a `READ_SENSITIVE` read is audited; and the checker cannot change `amount` through any API.
- **T74**: attachments. Pass when an attachment id from another tenant, another root or another field is rejected; the EICAR test file is quarantined and its submission blocked; a MIME type spoofed by extension is rejected; an expired upload URL fails; a PDF with JavaScript is rejected; download needs permission; and a superseded document remains retrievable for the approval that referenced it.
- **T75**: in-flight form versions. Activate v2 while v1 tasks and drafts are open. Pass when v1 tasks complete on v1; history renders v1; an outdated start form gets `409` and an offered draft upgrade; a migration without a form mapping keeps v1; and a failing mapping rejects the plan.
- **T76**: accessibility and languages. Pass when axe reports zero serious or critical violations on every pilot form (P1–P4) in en, hi and one state language; the manual NVDA/JAWS script passes; the GIGW Annexure II matrix is complete; deployment fails on a missing Hindi string; and diglot output is produced.
- **T77**: drafts. Pass when a two-tab conflict gets `409` with both versions; a seat handover makes a SEAT draft visible to the new holder with attribution; a draft never appears as process variables before submit; expiry, erasure and hold behave as specified; and the draft is saved automatically before session expiry.

---

### New tests T51–T77

Together with T01–T50, the test catalogue is now **T01–T77**. None of these tests has been executed (Delivery: SPECIFIED).

| ID | Test | Spec |
|---|---|---|
| T51 | A paused stale partition owner resumes after takeover: zero stale-epoch writes commit, and the node fences itself | R39, X.2.1.5 |
| T52 | Partition count changes 8 → 12 → 4 → off → 8 under load on every Tier-1 DB: no stranded job, no double commit, invariants I-1…I-4 hold | R39, X.2.1.7 |
| T53 | Node loss with in-flight jobs and a poison job: bounded recovery, retries preserved, `JOB_RECOVERY_LOOP` incident | R39, X.2.1.6 |
| T54 | Benefit gate: partitioned against default acquisition on the same hardware; partitioning may be enabled only above the thresholds | R39, X.2.1.1 |
| T55 | 50 identical concurrent idempotent requests: one execution, others `409 IN_PROGRESS` or byte-identical replay | R41, X.2.2.4 |
| T56 | Key reuse with a different payload or a different principal: `422`, no state change, no disclosure | R41, X.2.2.4 |
| T57 | Crash just before or just after the command commit: exactly one execution, or replay of the original response | R41, X.2.2.4–5 |
| T58 | Retention: full replay, then compact replay, then new execution stopped by the business key | R41, X.2.2.4 |
| T59 | Replay after revocation or tenant switch: `403` with no body; current masks applied to replays | R41, X.2.2.4 |
| T60 | Outcome classification: business errors replayed; transient errors and 401/403 not stored | R41, X.2.2.4 |
| T61 | Audit hash chain: edits, deletes, reorders, inserts and truncation detected by verification or by the checkpoint | R42, X.2.3.4 |
| T62 | Audit present and ordered with history `none`, suppression, `external` mode and a 1-hour outbox outage | R42, X.2.3.1–3 |
| T63 | Erasure scrubs every copy while the chain still verifies; refused and audited under a hold or category A | R42, X.2.3.4, X.2.3.8 |
| T64 | GOV-11 lifecycle: C review instead of deletion, two-person destroy, 25-year appraisal block, NAI PDF/A export, RTI hold | R42, X.2.3.5 |
| T65 | Audit schema evolution: v1 events upcast to v2, chain verifies over stored bytes | R42, X.2.3.7 |
| T66 | Diagnostics and fixture privacy: no personal fields by default, pseudonymisation or DPO approval enforced, child records excluded, TTLs | R42, X.2.3.8 |
| T67 | Package closure: unresolved, forbidden-`latest` or unsupported references rejected; frozen instances unaffected by repository drift | R43, X.2.4.6 |
| T68 | Live versus frozen in mid-flow: grant revocation, effective-dated delegation change, `asOf` fee schedule | R43, X.2.4.3 |
| T69 | Lifecycle SoD by person and seat; production refuses `sod=relaxed` | R43, X.2.4.4 |
| T70 | Promotion integrity: hash or signature mismatch rejected, `ENV_BINDING` gate, air-gapped bundle verification | R43, X.2.4.5 |
| T71 | Rollback and run-off: new starts on v2, v3 runs off, survives an engine upgrade, archived at zero instances | R43, X.2.4.7 |
| T72 | Server-side form validation cannot be bypassed; FEEL condition parity between browser and server | R47, X.2.5.2–3 |
| T73 | Field-level authorization and masking across every read path | R47, X.2.5.5 |
| T74 | Attachments: tenant, root and field binding, AV quarantine, MIME spoofing, active content, URL expiry, supersession | R47, X.2.5.4 |
| T75 | In-flight form versions: bound task versions, historic rendering, outdated start forms, migration mapping | R47, X.2.5.8 |
| T76 | Accessibility (axe, NVDA/JAWS, GIGW Annexure II) and languages (en, hi, state language, diglot) on all pilot forms | R47, X.2.5.6–7 |
| T77 | Drafts: revision conflicts, seat handover, never treated as state, expiry, erasure, hold, save before expiry | R47, X.2.5.8 |

**Mapping of the existing tests used here:**
- T01 → R41;
- T16 → R47 (material fields);
- T19 → R43 and R47;
- T21 → R43 and R47 (form mapping);
- T28 → R43 (run-off preflight);
- T30 → R42;
- T32 → R47;
- T41 → R39;
- T42 → R42.

**Effect on Part VIII:**
- **A-6** changes to Decision PROPOSED, Delivery SPECIFIED. Partitioned executors stay **off by default** and in Stage E behind the T54 gate.
- The VIII.6 baseline index gains these rows: "Partition ownership → X.2.1", "API idempotency → X.2.2", "Audit / diagnostics / replay data → X.2.3", "Executable package → X.2.4", "Forms → X.2.5".

### X.2a Status updates from X.2

| Item | Update |
|---|---|
| R39, R41, R42, R43, R47 | Marked "Spec pending" in X.1; now **SPECIFIED** in X.2.1–X.2.5. X.2 is the normative text for these five |
| Amendment A-6 (partitions) | **Closed as SPECIFIED** by X.2.1. Partitioned executors stay **off by default**; they may be enabled in a release only if T54 shows a measured benefit |
| New lock domain | **L6** (idempotency record, X.2.2) is always taken **last**: L1 → L2 → L4 → L6 |
| Test catalogue | Authoritative range is now **T01–T77** (supersedes CF-7's "T01–T50 + T51 onwards") |
| Binding rule (X.2.4) | "**What computes an outcome is frozen; what grants authority is live**": delegation-of-powers tables are evaluated live by effective date. `latest` binding is banned on government approval paths |
| Audit (X.2.3) | Audit is written synchronously and **cannot be switched off by history-level settings**. This refines R42 and Part I §4.6 |

## X.3 Licence and redistribution inventory

> Status: research draft, prepared 2026-11-04 for SANGAM.md Part VII §9.1 (version-level licence inventory). Inputs: X-6 and X-7 (Part V), D-7 and D-8 (Part VI.7). **This is not legal advice.** Every "redistribution allowed" verdict below needs sign-off in the FSL/licence legal review that D-7 already requires.
>
> **Sources read in the local clones**
> - Operaton `reference/operaton`: root `pom.xml` (project 2.3.0-SNAPSHOT), `parent/pom.xml`, `bom/internal-dependencies/pom.xml`, `database/pom.xml`, `distro/**/pom.xml`, `NOTICE`, `webapps/frontend/package.json`, `webapps-neo/frontend/package.json`. There is no `webapps-neo/package.json`; the manifest is at `webapps-neo/frontend/package.json`.
> - Flowable `reference/flowable-engine`: `modules/flowable-dependencies/pom.xml` (8.1.0-SNAPSHOT), `modules/flowable-parent/pom.xml`, module poms. There is no `modules/flowable-root`. The parent pom is `modules/flowable-parent`.
>
> Both upstreams are at **SNAPSHOT** heads. The versions below are what those heads pin. When D-1 is closed, the inventory must be regenerated from the **release tag** that is chosen.
>
> **Notation**
> - "O:" means the version Operaton pins. "F:" means the version Flowable pins. "n/p" means pinned in neither pom.
> - Redistribution means "may Sangam ship this artifact inside its own Apache-2.0 binary distribution (jar, war, container image or installer)?"
> - Risk: none / low / medium / high.

### X.3.1 Base engines

| Component | Version | Licence (SPDX) | Redistribution in Apache-2.0 product | Obligations | Risk | Action |
|---|---|---|---|---|---|---|
| **Operaton** (fork of Camunda 7 CE) | 2.3.0-SNAPSHOT (root `pom.xml`) | Apache-2.0 ([LICENSE](https://github.com/operaton/operaton/blob/main/LICENSE), [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0)) | Yes | Apache §4: ship the LICENSE. **Carry the upstream NOTICE forward verbatim** (§4(d)). It lists Operaton (Andreas Klein, Andreas Zill, Tim Zöller), Camunda Services GmbH 2013-2024, the ASF, Odysseus Software GmbH (JUEL) and Quartz. It also points to a "License Book" (`distro/license-book`) for third-party jars. Keep the per-file copyright headers (§4(c)) and mark modified files (§4(b)). | Low | Copy NOTICE into the Sangam NOTICE. Rebuild a Sangam licence book (see X.3.6). |
| **Flowable** | 8.1.0-SNAPSHOT (`flowable-dependencies`) | Apache-2.0 ([LICENSE](https://github.com/flowable/flowable-engine/blob/main/LICENSE)) | Yes | Ship the LICENSE. The repo root has **no NOTICE file**, so §4(d) only applies to NOTICE files inside bundled third-party jars. Keep the Alfresco/Activiti/Flowable copyright headers (§4(c)) and mark modified files. | Low | Check each released Flowable jar's `META-INF/NOTICE` and aggregate them. |
| Trademarks (both) | n/a | Apache-2.0 §6 grants **no** trademark rights | n/a | "Flowable" is a trademark of Flowable AG. "Camunda" is a Camunda Services GmbH trademark; Operaton exists because the fork may not use it. "Operaton" is the project's name and brand. Only nominative use is allowed ("based on Operaton/Flowable"). The same applies to the bpmn.io and GraalVM/Oracle marks. | Medium | Do not use "Flowable", "Operaton" or "Camunda" in the Sangam product name, logo, package names or domain. Rename Maven groupIds and Java packages only where a fork diverges. Get Indian-government branding reviewed. |

### X.3.2 Java runtime dependencies

| Component | Version pinned upstream | Licence (SPDX) | Redistribution OK? | Obligations | Risk | Action |
|---|---|---|---|---|---|---|
| Spring Framework | O: 7.0.10; F: 7.0.7 | Apache-2.0 ([LICENSE](https://github.com/spring-projects/spring-framework/blob/main/LICENSE.txt)) | Yes | NOTICE inside the jars | None | Align on one version. |
| Spring Boot | O: 4.1.2; **F: 4.1.0-RC1** (pre-release; Spring Security, AMQP, Kafka and LDAP are also RC1) | Apache-2.0 ([LICENSE](https://github.com/spring-projects/spring-boot/blob/main/LICENSE.txt)) | Yes | NOTICE | Low (licence); Medium (maturity: an RC on Flowable `main`) | Pin a GA release. Take the managed versions of Micrometer, OTel and Jackson from the Boot BOM. |
| Quarkus | O: 3.40.1.1 (`quarkus-extension-maven-plugin`); F: n/a | Apache-2.0 ([LICENSE](https://github.com/quarkusio/quarkus/blob/main/LICENSE)) | Yes | NOTICE | None | Only needed if a Quarkus distro is shipped. |
| MyBatis | O and F: 3.5.19 | Apache-2.0 ([LICENSE](https://github.com/mybatis/mybatis-3/blob/master/LICENSE)) | Yes | MyBatis NOTICE (it embeds a shaded OGNL). Optional javassist is tri-licensed MPL-1.1 / LGPL-2.1+ / Apache-2.0; elect Apache-2.0. | None | — |
| Jackson 2 / 3 | O: 2.22.4 / 3.1.8; F: 2.21.3 / 3.1.3 | Apache-2.0 ([LICENSE](https://github.com/FasterXML/jackson-databind/blob/2.x/LICENSE)) | Yes | Jackson NOTICE | None | — |
| **Liquibase core** | **O: 4.33.0** (root `pom.xml` l.30, used by `distro/sql-script`); F: not a runtime dependency. Flowable uses only `liquibase-maven-plugin` **5.0.2** (FSL), in the build, to clean test databases. | 4.33.0: Apache-2.0. **5.0+: FSL-1.1-ALv2** (Functional Source License). Each FSL release converts to Apache-2.0 two years after its release date ([Liquibase FSL blog](https://www.liquibase.com/blog/liquibase-community-for-the-future-fsl), [5.0 release notes](https://docs.liquibase.com/community/release-notes/5-0), [FSL](https://fsl.software/)) | 4.33.0: yes. 5.x: FSL is **not OSI open source**. It forbids "Competing Use", so 5.x cannot ship in a permissive distribution without legal acceptance. | 4.33.0: NOTICE | **Medium**. Licence risk is nil for 4.33.0, but 4.33.0 will get no further Apache-2.0 security fixes. Flowable's 5.0.2 plugin is build-only and is never redistributed. | **D-7 decided: pin 4.33.0.** Add a dependency-convergence and enforcer rule that bans `org.liquibase:*:[5,)` from runtime scopes. Revisit triggers: a CVE in 4.33.x, or the Apache-2.0 conversion date of a 5.x release (5.0 converts about two years after its late-2025 release). The fallback is Flyway OSS (X.3.4). Keep the changelogs in SQL format so the tool can be swapped. |
| feel-scala (`org.camunda.feel:feel-engine`) | O: 1.22.1; F: not used (Flowable DMN has its own expression support) | Apache-2.0 ([LICENSE](https://github.com/camunda/feel-scala/blob/main/LICENSE.txt)) | Yes | NOTICE. The groupId contains the Camunda mark; that is fine as nominative use. | Low. Check that future 1.2x releases stay Apache-2.0, because Camunda 8 components have moved to other licences. | Re-check the licence on every bump. Freeze before any relicence. |
| Scala library and runtime deps (fastparse, geny, sourcecode, paranamer) | O: scala 2.13.18; fastparse 3.1.1; geny 1.1.1; sourcecode 0.4.4; paranamer 2.8.3 | scala-library: Apache-2.0 ([scala-lang.org/license](https://www.scala-lang.org/license/)). fastparse, geny, sourcecode: MIT. paranamer: BSD-3-Clause | Yes | Scala NOTICE. MIT and BSD copyright texts go in the licence book. | None | — |
| Drools / KIE (rules task; DMN option) | F: drools 9.44.0.Final (`provided`/test scope in `flowable-engine` and flowable5). O: not used. Current upstream: Apache KIE 10.x (ASF) | Apache-2.0 ([apache/incubator-kie-drools](https://github.com/apache/incubator-kie-drools/blob/main/LICENSE)) | Yes | ASF NOTICE. If Apache KIE is still incubating, its releases carry a DISCLAIMER; keep it. | Low | Ship only if the rules task is in scope. Prefer Apache KIE 10.x over the Red Hat-era 9.x line. KIE DMN pulls a large transitive tree (ANTLR, MVEL, etc.), so it needs its own SBOM pass. |
| jDMN (alternative DMN/FEEL) | n/p. Latest seen: 8.7.2 (`jdmn-parent`, Maven Central) | Apache-2.0 ([LICENSE](https://github.com/goldmansachs/jdmn/blob/master/LICENSE)) | Yes | NOTICE | Low | Only if chosen as the DMN engine. |
| Groovy (`org.apache.groovy`) | O: 5.1.3; F: 4.0.28 | Apache-2.0 ([groovy-lang.org](https://groovy-lang.org/)) | Yes | ASF NOTICE | Low (licence). Security: in-process Groovy conflicts with D-8. | Move script execution to the out-of-process worker (D-8), or exclude Groovy from the engine distro. |
| **GraalJS / GraalVM polyglot** | **O: `org.graalvm.polyglot:js` and `polyglot` 25.4.4.1.1** (`bom/internal-dependencies`). They are bundled in `distro/run/core` and in the WildFly modules and assembly. F: not used. | `org.graalvm.polyglot:js` (no suffix) is **Oracle GraalVM under GFTC**. GraalVM docs: "Language and tool dependencies use the GraalVM Free Terms and Conditions (GFTC) license"; the open variant needs the `-community` suffix ([embedding guide](https://www.graalvm.org/latest/reference-manual/embed-languages/), [GFTC](https://www.oracle.com/downloads/licenses/graal-free-license.html)). `js-community`: UPL-1.0 ([UPL](https://oss.oracle.com/licenses/upl/)). GraalVM CE JDK: GPL-2.0-with-classpath-exception. | **GFTC artifacts: no** for a permissive distribution. GFTC allows redistribution only of unmodified programs and only where "You do not charge Your licensees any fees associated with such distribution or use". That conflicts with paid government support and implementation contracts. It is also proprietary and forbids reverse engineering. `-community` (UPL): yes. | GFTC: include the licence and keep the notices. UPL: include the licence text. | **High** (if Operaton is the base and its distro is reused as-is) | **D-8 decided: no GraalJS.** Exclude `org.graalvm.polyglot:js`, `polyglot` and `org.graalvm.js:*` from every Sangam artifact, and add a Maven enforcer ban. If JS is ever needed, use `js-community`, or run it in the script worker. **Spec drift on X-7:** the current GraalVM security guide says "Sandboxing is available on Oracle GraalVM and, starting with GraalVM 25.1, on GraalVM Community Edition" through the `*-isolate-community` artifacts ([sandboxing guide](https://www.graalvm.org/latest/security-guide/sandboxing/)). X-7's "not in Community Edition" held for 25.0 but is no longer true. D-8 does not need to change, but the X-7 evidence row should be updated. |
| Nashorn (`org.openjdk.nashorn`) | O and F: 15.7 | GPL-2.0-only WITH Classpath-exception-2.0 ([openjdk/nashorn](https://github.com/openjdk/nashorn)) | Yes, as an unmodified separate jar (the Classpath Exception covers linking) | GPL text plus exception text. Offer source for the shipped binary (simplest: a link to the exact OpenJDK tag). | Medium | Do not bundle. Scripting goes out-of-process (D-8). |
| Rhino (`org.mozilla:rhino`) | F: 1.7.14.1 (hard-coded in `flowable-secure-javascript`). Current upstream line: 1.8.x | MPL-2.0 ([LICENSE](https://github.com/mozilla/rhino/blob/master/LICENSE.txt), [MPL-2.0](https://www.mozilla.org/en-US/MPL/2.0/)) | Yes. MPL is file-level copyleft; unmodified binaries are fine. | Tell users where to get the source of the MPL files. Any change to Rhino files must be published under MPL. | Low (licence); Medium (Rhino "secure" scripting is not a real sandbox) | Exclude `flowable-secure-javascript` from the default distro under D-8. If kept, move to 1.8.x. |
| Jython / JRuby (script engines) | O: jython 2.7.4; jruby-complete (version from the BOM) | Jython: PSF-2.0. JRuby: EPL-2.0 OR GPL-2.0 OR LGPL-2.1 | Yes (choose EPL-2.0 for JRuby) | Licence texts. EPL requires a source pointer. | Medium | Do not ship; out-of-process worker (D-8). |
| JUEL forks | O: `org.operaton.bpm.juel:operaton-juel` (fork of de.odysseus JUEL). F: inlined fork at `org.flowable.common.engine.impl.de.odysseus.el` in `flowable-engine-common` | Apache-2.0 (original JUEL by Odysseus Software GmbH) | Yes | **The Odysseus attribution must stay in NOTICE** (Operaton NOTICE already has it). Keep the file headers. | Low | Add the Odysseus line to the Sangam NOTICE if the Flowable base is chosen, since Flowable has no root NOTICE. |
| Apache HttpClient 5 / HttpCore 5 (+ HttpClient 4 in F) | O: httpclient5 5.6.5, httpcore5 5.4.4; F: httpclient5 5.6.1, httpclient 4.5.14, httpcore 4.4.16 | Apache-2.0 ([hc.apache.org](https://hc.apache.org/)) | Yes | ASF NOTICE | None | Drop HttpClient 4 (EOL line) where possible. |
| **PostgreSQL JDBC (pgjdbc)** | O: 42.7.14; F: 42.7.11 | BSD-2-Clause ([licence](https://jdbc.postgresql.org/license/)) | Yes | Copyright notice in the licence book | None | **The default bundled driver** (PostgreSQL is the reference database). |
| **Oracle JDBC (ojdbc)** | O: `ojdbc11` 23.26.4.0.0; F: `ojdbc17` 23.26.1.0.0 | **Oracle Free Use Terms and Conditions (FUTC)**. Proprietary; no SPDX ID (`LicenseRef-Oracle-FUTC`) ([FUTC](https://www.oracle.com/downloads/licenses/oracle-free-license.html); see the Maven Central [ojdbc17](https://central.sonatype.com/artifact/com.oracle.database.jdbc/ojdbc17) licence metadata) | **Technically permitted, but not as part of a permissive distribution.** FUTC allows redistributing *unmodified* programs with a copy of the licence, keeping Oracle's notices, following export law, and charging end users "no additional fees for the use of the Programs". It is not OSI-approved. The ASF itself classes this kind of licence as Category X ([ASF third-party policy](https://www.apache.org/legal/resolved.html)). The fee condition is ambiguous for paid government contracts. | Licence copy, notices, export compliance | **High** | **Customer-supplied.** Do not put ojdbc in Sangam images or installers. Provide a "bring your own driver" directory (`/opt/sangam/drivers`) plus a documented Maven Central coordinate. Test with it in CI, which is allowed under FUTC "developing, testing". |
| Microsoft SQL Server JDBC (`mssql-jdbc`) | O: 13.6.1.jre11; F: 13.4.0.jre11 | MIT ([LICENSE](https://github.com/microsoft/mssql-jdbc/blob/main/LICENSE)) | Yes | MIT notice | Low | The optional native `mssql-jdbc_auth` DLL (Windows integrated auth) is a separate download. Do not bundle it; verify its terms if ever needed. |
| **MySQL Connector/J** | O and F: 9.7.0 | **GPL-2.0-only WITH Universal-FOSS-exception-1.0** (plus Oracle's "additional permission" for its bundled components) ([Connector/J 9.7 licence](https://downloads.mysql.com/docs/licenses/connector-j-9.7-gpl-en.pdf), [Universal FOSS Exception](https://oss.oracle.com/licenses/universal-foss-exception/)) | **Not safe to bundle.** The UFE lets the driver be combined with software under an OSI-approved licence (Apache-2.0 qualifies). That protection fails as soon as Sangam, a state department or an integrator adds proprietary extensions or distributes a combined proprietary build. Then GPL-2.0 copyleft applies to the combined work. | GPL text, source offer, UFE text | **High** | **Customer-supplied, or replace.** For MySQL targets, use the MariaDB Connector/J (LGPL, see below), which talks to MySQL. Otherwise have the customer drop in Connector/J themselves. |
| MariaDB Connector/J | O: 3.5.11 (and 1.1.8 for migration QA); F: 3.5.8 | LGPL-2.1-or-later ([LICENSE](https://github.com/mariadb-corporation/mariadb-connector-j/blob/master/LICENSE), [LGPL-2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html)) | Yes, as an **unmodified, separately replaceable jar** (dynamic linking in Java) | LGPL text. Source offer or link to the exact tag. Users must be able to replace the jar: no shading, no fat-jar repackaging that hides it, no anti-relinking terms. | Medium | Ship as an optional profile, or in `/drivers` as a separate jar. Never shade it into an uber-jar. Add a written source offer to the licence book. |
| IBM Db2 JCC (`com.ibm.db2:jcc`) | O: 12.1.5.0 (11.5.9.0 profile); F: 12.1.4.0 | **IBM International Program License Agreement (IPLA)**. Proprietary; no SPDX ID ([Maven Central](https://central.sonatype.com/artifact/com.ibm.db2/jcc)) | **No.** IPLA grants no redistribution right that we have confirmed. | — | **High** | Customer-supplied only, same as Oracle. |
| H2 | O: 2.5.252 (also 1.4.200 for migration QA); F: 2.4.240. Bundled by Operaton `distro/run`, Tomcat and WildFly assemblies | MPL-2.0 OR EPL-1.0 ([licence](https://h2database.com/html/license.html)) | Yes (unmodified) | MPL source pointer | Low | Dev/demo profile only. It must not be in the production image, because it is not a supported database tier. |
| HSQLDB | F: 2.7.4 (test) | BSD-3-Clause-style (HSQLDB licence) | Yes | Notice | None | Test only. |
| Testcontainers | O and F: 2.0.5 | MIT ([LICENSE](https://github.com/testcontainers/testcontainers-java/blob/main/LICENSE)) | Not distributed (test scope) | — | None | Test images pulled at CI time (for example Oracle Free, Db2) have their own vendor terms. Never re-publish those images. |
| Chicory (pure-Java WASM) | n/p. 1.x line (1.4.0 promoted the compiler out of experimental; latest Maven release seen March 2026) | Apache-2.0 ([dylibso/chicory](https://github.com/dylibso/chicory/blob/main/LICENSE)) | Yes | NOTICE | Low | Candidate for the D-8 "WASM later" phase. The WASM *guest* toolchains (for example QuickJS compiled to WASM) need their own licence rows. |
| Endive (Bytecode Alliance fork of Chicory) | n/p. Early project; no stable release verified | Apache-2.0 ([bytecodealliance/endive](https://github.com/bytecodealliance/endive), [announcement](https://bytecodealliance.org/articles/endive-and-the-next-chapter-of-webassembly-on-the-jvm)) | Yes | NOTICE | Low (licence); Medium (maturity) | Watch it; choose between Chicory and Endive when D-8 phase 2 opens. |
| OpenTelemetry Java (API/SDK/exporters) | n/p in either pom. Take it from the Spring Boot 4.1 / Quarkus BOM | Apache-2.0 ([LICENSE](https://github.com/open-telemetry/opentelemetry-java/blob/main/LICENSE)) | Yes | NOTICE | None | Pin through the BOM. The OTel Java *agent* bundles many shaded libraries; if the agent is shipped, include its own third-party list. |
| Micrometer | n/p (Spring Boot BOM) | Apache-2.0 ([LICENSE](https://github.com/micrometer-metrics/micrometer/blob/main/LICENSE)) | Yes | NOTICE | None | — |
| gRPC-java | n/p | Apache-2.0 ([LICENSE](https://github.com/grpc/grpc-java/blob/master/LICENSE)) | Yes | NOTICE. `grpc-netty-shaded` embeds Netty, whose NOTICE lists further third parties. | None | Carry the Netty NOTICE. |
| Protobuf (protobuf-java) | n/p | BSD-3-Clause ([LICENSE](https://github.com/protocolbuffers/protobuf/blob/main/LICENSE)) | Yes | Copyright notice | None | — |
| Other bundled jars worth noting | O: hibernate **6.6.10.Final** (test/QA); F: hibernate 7.3.3.Final (test). Jakarta EE APIs; logback 1.6.6; slf4j; angus-mail; icu4j 71.1 | Hibernate 6.x: **LGPL-2.1**. Hibernate 7.x: Apache-2.0 ([hibernate.org/community/license](https://hibernate.org/community/license/)). Jakarta APIs: EPL-2.0 OR GPL-2.0-with-CPE. Logback: EPL-1.0 OR LGPL-2.1. Angus Mail: EPL-2.0 OR GPL-2.0-with-CPE OR BSD-3-Clause. ICU4J: ICU / Unicode licence | Yes (elect EPL/BSD where offered) | EPL source pointer; licence texts | Low | If Sangam ships JPA, use Hibernate ≥7. Elect EPL-2.0 for the Jakarta and Logback jars. |

### X.3.3 UI (webapps and webapps-neo)

Pinned versions come from Operaton `webapps/frontend/package.json` (legacy AngularJS cockpit). Caret ranges come from `webapps-neo/frontend/package.json`. Flowable ships no modeller UI in the engine repo.

| Component | Version | Licence (SPDX) | Redistribution OK? | Obligations | Risk | Action |
|---|---|---|---|---|---|---|
| **bpmn-js** | legacy: 18.31.1; neo: ^18.21.0 | **bpmn.io License**: MIT-style plus a watermark clause. Not OSI-approved; no SPDX ID (`LicenseRef-bpmn-io`) ([bpmn.io/license](https://bpmn.io/license/)) | Yes | Keep the copyright notice. **The code that shows the bpmn.io watermark "MUST NOT be removed or changed".** In an application "the watermark must stay fully visible and not visually overlapped by other elements". | Medium | Keep the watermark visible in every canvas: modeller, viewer, cockpit and the citizen-facing status view. No white-label build may hide it. Add a UI test that asserts the `.bjs-powered-by` element is visible. Tell procurement and branding stakeholders now (X-6). |
| **dmn-js** (and decision-table, drd, literal-expression, shared; dmn-js-properties-panel 3.13.0) | legacy: 17.13.0; neo: ^17.3.0 | bpmn.io License | Yes | Same watermark duty | Medium | As above. |
| **form-js** (`@bpmn-io/form-js`, `form-js-viewer`) | legacy: 1.26.1; neo: viewer ^1.21.3 | bpmn.io License | Yes | Same watermark duty, which also applies to rendered **forms**. That matters for citizen forms. | Medium | Decide whether citizen-facing forms use form-js (watermark visible to the public) or a Sangam renderer that reads the same schema. |
| cmmn-js | legacy: 0.20.0 | bpmn.io License | Yes | Watermark | Medium | Drop it unless CMMN is in scope. |
| bpmn-moddle, @bpmn-io/feelin, diagram-js (transitive) | bpmn-moddle ^10.0.0; feelin ^6.1.0 | MIT | Yes | MIT notice | None | — |
| react-bpmn | ^0.2.0 | MIT (verify the package licence field; it wraps bpmn-js, so the watermark still applies) | Yes | MIT notice + bpmn.io watermark | Low | — |
| Preact, @preact/signals, preact-iso | preact ^10.22.1; signals ^2.9.4; preact-iso ^2.6.3 | MIT ([LICENSE](https://github.com/preactjs/preact/blob/main/LICENSE)) | Yes | MIT notice | None | — |
| htm | ^3.1.1 | Apache-2.0 | Yes | NOTICE if present | None | — |
| i18next, i18next-browser-languagedetector, i18next-http-backend, react-i18next | ^26.2.0, ^8.2.0, ^4.0.4, ^17.0.10 | MIT ([i18next LICENSE](https://github.com/i18next/i18next/blob/master/LICENSE)) | Yes | MIT notice | None | — |
| react-hotkeys-hook | ^5.3.3 | MIT | Yes | MIT notice | None | — |
| DOMPurify | ^3.4.17 (legacy 3.4.17) | MPL-2.0 OR Apache-2.0 ([LICENSE](https://github.com/cure53/DOMPurify/blob/main/LICENSE)) | Yes | Elect Apache-2.0 | None | — |
| Vite (+ @preact/preset-vite, Vitest) | vite ^8.3.5; vitest ^4.1.12 | MIT ([Vite LICENSE](https://github.com/vitejs/vite/blob/main/LICENSE)) | Build tooling; only tiny runtime helpers end up in the bundle | MIT notice for the bundled helpers | None | Generate the bundle licence file with a plugin such as `rollup-plugin-license`. |
| Dev-only tooling (Playwright, ESLint, happy-dom, jsdom, lint-staged, simple-git-hooks, @axe-core/playwright, **pa11y**) | per `devDependencies` | Apache-2.0 / MIT, except **axe-core MPL-2.0** and **pa11y LGPL-3.0** | Not distributed | — | None (as long as they stay dev-only) | A CI check must confirm none reach the production bundle. |
| Legacy cockpit stack (AngularJS 1.8.3, jQuery 3.7.1, jQuery UI 1.14.2, Bootstrap 4.6.2, chart.js, lodash 4.18.1, angular-translate, angular-moment; `angular-data-depend` from a git URL `camunda-third-party/...`) | as pinned | MIT (AngularJS, jQuery, Bootstrap, chart.js, lodash). `angular-data-depend`: **licence not verified** (git dependency, no registry metadata) | Yes for MIT | MIT notices | Low (licence); **High (security: AngularJS has been EOL since Dec 2021)** | Do not ship the legacy webapps. Base the Sangam UI on webapps-neo. If anything legacy is kept, verify `angular-data-depend`'s licence first. |

### X.3.4 Tools

| Component | Version | Licence (SPDX) | Redistribution OK? | Obligations | Risk | Action |
|---|---|---|---|---|---|---|
| **Flyway OSS (Community)**, the D-7 fallback | n/p. Latest seen: 12.6.x on Maven Central; Redgate notes list 13.0.0 (July 2026). Verify before pinning | Apache-2.0 ([LICENSE](https://github.com/flyway/flyway/blob/main/LICENSE.txt)) | Yes | NOTICE | Low | This is a valid exit from Liquibase. Some features are Teams/Enterprise-only (undo migrations, some drift and reporting parameters; OSS ignores `publishResult` and `checkDriftOnMigrate`). From Flyway 10 the database support is split into `flyway-database-*` modules, so **check the edition and licence of each database module** (Oracle, Db2, SQL Server) at pin time. |
| liquibase-maven-plugin 5.0.2 (Flowable build) | 5.0.2 | FSL-1.1-ALv2 | Build-only; not redistributed | FSL permitted-purpose limits apply to *our* use | Low | Fine for internal CI under FSL. Do not ship it, and do not wrap it in a Sangam tool. |
| Node.js / npm (Operaton frontend build) | O: node 24.22.1, npm 11.19.1 | MIT plus bundled third-party licences | Build-only | — | None | — |

### X.3.5 Items that cannot ship in a permissive distribution, or need the customer to supply them

| # | Item | Why | Alternative |
|---|---|---|---|
| 1 | **Oracle JDBC (ojdbc11 / ojdbc17)** | FUTC is proprietary: no modification, licence copy required, and a fee condition that is ambiguous for paid contracts. It is not OSI. | Customer supplies it from Maven Central or Oracle into `/drivers`. Sangam documents the coordinate and tests it in CI. PostgreSQL is the default. |
| 2 | **IBM Db2 JCC** | IPLA; no confirmed redistribution right | Customer-supplied, same as Oracle. Or drop the Db2 tier. |
| 3 | **MySQL Connector/J** | GPL-2.0 + UFE. Copyleft applies once any non-FOSS component is in the combined work. | MariaDB Connector/J (LGPL, as a separate jar) for MySQL/MariaDB, or customer-supplied Connector/J. |
| 4 | **GraalJS `org.graalvm.polyglot:js` (Oracle GraalVM, GFTC)**, which the Operaton distros bundle | Proprietary GFTC with a "no fees" redistribution condition | D-8: out-of-process script worker. If JS is ever needed, use `js-community` / `js-isolate-community` (UPL, GraalVM ≥25.1). Strip `js` and `polyglot` from every Operaton-derived assembly. |
| 5 | **Liquibase ≥5.0** | FSL-1.1-ALv2 is not open source until each release's two-year conversion date | Pin 4.33.0 (D-7), or Flyway OSS. |
| 6 | Hibernate 6.x (if it ever moves out of test scope) | LGPL-2.1 | Hibernate ≥7 (Apache-2.0). |
| 7 | Nashorn, Jython, JRuby, Rhino in-process | Licences are shippable (GPL+CE, PSF, EPL, MPL), but they conflict with D-8's security posture | Out-of-process worker. |
| — | bpmn-js / dmn-js / form-js / cmmn-js | **Can ship**, but they are not OSI-approved and the watermark must stay visible. Not a blocker, but a branding constraint. | Accept the watermark. A non-bpmn.io modeller would be a large rewrite. |

### X.3.6 Process requirements (to close Part VII §9.1)

1. Generate a CycloneDX SBOM for every shipped artifact: `cyclonedx-maven-plugin` for Java and `@cyclonedx/cyclonedx-npm` for the UI bundle. Fail the build on any licence outside an allow-list: Apache-2.0, MIT, BSD-2/3-Clause, ISC, UPL-1.0, EPL-2.0 (binary), MPL-2.0 (binary), LGPL-2.1 (separate jar only), with documented exceptions.
2. Add a Maven enforcer `bannedDependencies` rule for `org.liquibase:*:[5,)` (runtime scope), `org.graalvm.polyglot:js`, `org.graalvm.polyglot:*-isolate` (non-community), `com.oracle.database.jdbc:*`, `com.ibm.db2:*` and `com.mysql:mysql-connector-j` in shipped modules. Allow them only in `test` scope or a `drivers-byo` profile.
3. Build an aggregated `NOTICE`: Operaton NOTICE (Camunda, Odysseus, Quartz, ASF), the NOTICE files from every ASF/Apache-2.0 jar, Scala, Netty (via gRPC), Jackson and MyBatis. Generate `THIRD-PARTY-LICENSES` (licence book) with the full text of every non-Apache licence. Generate a source-offer file for the LGPL, MPL, EPL and GPL+CE components.
4. Regenerate this table from the D-1 release tag, not from SNAPSHOT heads.
5. Re-check on every bump: feel-scala (Camunda relicensing risk), bpmn.io packages (watermark terms) and GraalVM (GFTC versus community split).

### Blocking issues

1. **GraalJS GFTC artifacts in the Operaton distributions.** `org.graalvm.polyglot:js` / `polyglot` 25.4.4.1.1 are Oracle GraalVM (GFTC) builds, bundled in `distro/run/core` and the WildFly modules and assembly. They must be removed or swapped for `-community` before any Operaton-based Sangam build ships. This enforces D-8.
2. **Oracle JDBC driver redistribution.** FUTC terms make it unsuitable for a permissive image. A "bring your own driver" mechanism and its documentation must exist before the Oracle database tier is advertised.
3. **IBM Db2 JCC.** No redistribution right was found. Customer-supplied, or drop the tier.
4. **MySQL Connector/J (GPL-2.0 + UFE).** Do not bundle. Decide between the MariaDB driver for MySQL and a customer-supplied Connector/J.
5. **Liquibase 5.x FSL guard.** D-7 pins 4.33.0, but nothing yet *prevents* a transitive or Dependabot upgrade to 5.x. The enforcer ban and the legal review D-7 names must be in place before Phase 0 exits.
6. **bpmn.io watermark acceptance.** The watermark must stay visible on modeller, viewer and form-js canvases, including citizen-facing forms. This needs explicit sign-off from product and procurement (X-6), or a decision to render citizen forms without form-js.
7. **Spec correction for X-7.** The GraalVM security guide now says CE sandboxing is available from 25.1 (`*-isolate-community`). Update the X-7 evidence row. D-8's out-of-process decision stands on threat-model grounds.
8. **Inventory provenance.** Both upstreams were inventoried at SNAPSHOT heads, and Flowable `main` pins Spring Boot / Security **4.1.0-RC1**. The inventory must be regenerated from the release tag chosen under D-1, with a CycloneDX SBOM, before §9.1 can be marked done.
9. **Unverified licence.** `angular-data-depend` is a git-URL dependency in Operaton's legacy webapps, and its licence is unknown. This only blocks if any legacy webapp code ships.

### X.3a Decisions arising from the licence inventory (2026-11-04)

| # | Finding | Decision |
|---|---|---|
| LIC-1 | **GraalJS in the Operaton distros** (`org.graalvm.polyglot:js` 25.4.4.1.1, GFTC licence: redistribution only unmodified and without charging fees) | **Strip it from every Sangam build.** This is consistent with D-8 (no in-engine scripts). A Maven enforcer rule bans `org.graalvm.polyglot:js` and any GFTC artifact |
| LIC-2 | **Oracle JDBC** (`ojdbc11`/`ojdbc17`, Oracle Free Use Terms) | **Not bundled.** The customer supplies it through a documented **bring-your-own-driver** directory. PostgreSQL is the default. Oracle stays Tier 1 (ADR-D-3), tested in CI with a driver fetched by the CI system, not shipped |
| LIC-3 | **IBM Db2 JCC** (IBM IPLA, no redistribution right found) | **Not bundled**; customer-supplied. Db2 stays Tier 2 (R17) |
| LIC-4 | **MySQL Connector/J** (GPL-2.0 + Universal FOSS Exception) | **Not bundled.** The MariaDB driver serves MySQL/MariaDB (Tier 2) |
| LIC-5 | **MariaDB driver** (LGPL-2.1) | May ship **only as a separate, replaceable jar with a source offer**, never shaded into a fat jar |
| LIC-6 | **Legacy AngularJS webapps** (EOL since Dec 2021) | **Not shipped.** The UI is based on webapps-neo only |
| LIC-7 | **Liquibase 5.x FSL** risk of an accidental upgrade | **Maven enforcer ban on Liquibase ≥ 5.0** until the D-7 legal review approves it. Flowable's 5.0.2 *build-time* plugin (test DB cleanup) is allowed because it is not shipped |
| LIC-8 | **bpmn.io licence** (bpmn-js, dmn-js, form-js, cmmn-js: not OSI; the watermark must stay visible) | The watermark is kept visible in modeller and viewer screens. **Open product question:** whether citizen-facing forms rendered by form-js are acceptable with the watermark, or whether a separate form renderer is needed for citizen screens. Parked for the Stage C UI design |
| LIC-9 | **Trademarks** | No "Flowable", "Operaton" or "Camunda" in product or package names; "based on …" wording only (already in Part I §12) |
| LIC-10 | **Rhino, Nashorn, Jython, JRuby** (shippable licences) | Kept **out of the default build** (D-8, out-of-process script worker) |
| LIC-11 | **Inventory regenerated at release tag** | Both clones are SNAPSHOT heads (Flowable pins Spring Boot 4.1.0-RC1). The inventory is **regenerated from the release tag chosen in D-1**, and an SBOM licence scan runs in CI (Part I §14.5) |

**Correction to Part V X-7 (GraalVM sandbox):** **VERIFIED** 2026-11-04 against the current GraalVM sandboxing guide: *"Sandboxing is available on Oracle GraalVM and, starting with GraalVM 25.1, on GraalVM Community Edition"*, using the `*-isolate-community` artifacts (e.g. `js-isolate-community`). The earlier X-7 statement was true for GraalVM 25.0 only. **D-8 is unchanged**: the out-of-process script worker remains the release-1 choice on threat-model grounds. GraalVM CE 25.1+ isolates (UPL licence) become a candidate for the later in-process option, alongside WASM.

## X.4 Pilot user and operator journeys

> **Status:**
> - Evidence: derived from Part IX (IX.3–IX.8, IX.7a), Part III §11, Part VII §8, Part VI (VI.3–VI.5) and amendments A-1…A-4 (VIII.3). Government facts come from Appendices B and C and keep their PARTIAL/VERIFIED labels. Error codes, audit event names and the proposed tests TP-xx are **new proposals** in this section; they are not yet in the requirement set.
> - Decision: **DRAFT**, for owner review together with IX.8 (acceptance criteria are themselves still draft).
> - Delivery: SPECIFIED; nothing is implemented, and no test listed here has been executed.
>
> **Closes:** VIII.7 task 5 (Part VII §13 item 6). **Gate:** these journeys are the scope of **G7 (usable application)** and the usability session of AC-10/T32.

### X.4.0 Conventions

#### X.4.0.1 Identifiers

| Prefix | Meaning | Example |
|---|---|---|
| `PER-n` | Persona (X.4.1) | PER-3 approving authority |
| `J-Pn-H` | Happy path of pack Pn | J-P1-H |
| `J-Pn-Exx` | Exception journey of pack Pn | J-P1-E03 |
| `CX-n` | Cross-cutting exception contract, specialised per pack (X.4.2.3) | CX-2 stale form |
| `OJ-n` / `AJ-n` / `AU-n` / `IJ-n` | Operator / admin / auditor / integration-engineer journey | OJ-02 |
| `S…` / `C…` / `O…` / `A…` / `U…` / `I…` | Screen: staff / citizen / operator / admin / auditor / integration (X.4.7) | S08 task detail |
| `Tnn` | Catalogue test T01–T50 (III §16, VI, VIII.4, IX.7a) | T16 |
| `TP-nn` | **Proposed** new test, needed where no catalogue test exists (X.4.12.3) | TP-04 |

#### X.4.0.2 Government integration tags

| Tag | Integration | Source | Requirement | Pilot form |
|---|---|---|---|---|
| G-SSO | Parichay (officials), Jan Parichay / MeriPehchaan (citizens, MFA), state SSO; SAML 2.0 / OAuth 2.0 | GOV-6, CERT-In 5.22/5.23 | R50 | Real adapter to a test IdP; Parichay onboarding docs are an open evidence gap |
| G-SIGN | DSC via local signer agent; eSign v2.1 ASP with several ESPs; hash signing, PAdES/CMS + LTV | GOV-7, CSMOP Box-e.7.1 | R57 | Simulated ESP + test DSC tokens |
| G-PFMS | PFMS / State IFMIS signed SFTP files (payment, sanction, DBT) out; status/response files in; SSID, scheme code, 3 PM cut-off | GOV-8 | R58 | Simulated SFTP server with failure injection |
| G-PG | Fee gateway adapters (NTRP/Bharatkosh, PayGov, GRAS-type e-Challan) with checksum and status query | GOV-8 | R58 | Simulated gateway |
| G-DL | DigiLocker Issuer (push URI / pull) and Requester (MeriPehchaan OAuth) | GOV-9 | R59 | Simulated DigiLocker |
| G-DLT | SMS via DLT: PE-ID, header with `-G`/`-T`/`-S` suffix, one template per header, typed variables, whitelisted URLs only | GOV-10 | R60 | Simulated NIC SMS gateway |
| G-MAIL | Official e-mail via gov.in / NIC SMTP relay | GOV-10 | R60 | Local SMTP sink |
| G-REC | CSMOP 2022 retention category A (permanent) / C (≤10 y), review queue, legal hold, NAI PDF/A export, RTI SLA | GOV-11 | R61 | Real module |
| G-LANG | Hindi + English UI, forms and outputs, plus one configurable state language; Unicode on all Tier-1 DBs | GOV-1 | R54 | Real; pilot state language chosen with G1 buyer (assumed Marathi in examples) |
| G-GIGW | GIGW 3.0 / WCAG 2.1 AA floor, WCAG 2.2 AA target, IS 17802 | GOV-2 | R55 | Real |
| G-CERT | CERT-In 6-hour incident report, 180-day India-resident logs, NIC/NPL NTP, SIEM (PRATIMAAN) export | GOV-3 | R51 | Real workflow, simulated submission |
| G-DPDP | Purpose tags, notices, masking, breach (72 h) and grievance workflows | GOV-4 | R53 | Real for P3/P4 personal data |

#### X.4.0.3 Audit event catalogue (proposed names)

Every event is written in the **same transaction** as the state change it records (L5 insert, VI.1) and carries: `tenant_id`, `case_id`/`root_id`, `actor_person_id`, `actor_seat_id`, `acted_for_seat_id` (if any), `authority_basis` (grant, delegation id, SoD exception id), `package_version` (R43), `payload_hash` where a material payload exists, `trace_id`, NTP-synced timestamp (R51). Background work records `onBehalfOf` (VI.4 rule 7).

| Group | Events |
|---|---|
| Identity | `AUTH_LOGIN`, `AUTH_LOGOUT`, `SESSION_EXPIRED`, `SEAT_CONTEXT_SELECTED`, `AUTH_DENIED` (action, reason, policy_version) |
| Case lifecycle (IX.3) | `CASE_CREATED`, `CASE_DRAFT_SAVED`, `CASE_SUBMITTED`, `CASE_RETURNED` (to level, reason), `CASE_RESUBMITTED` (version n→n+1, diff hash), `CLARIFICATION_SOUGHT`, `CLARIFICATION_RECEIVED`, `CASE_REJECTED` (reasons), `CASE_WITHDRAWN`, `CASE_APPROVED`, `CASE_ACKNOWLEDGED`, `CASE_RECONCILED`, `CASE_CLOSED`, `APPEAL_FILED` |
| Tasks | `TASK_CREATED`, `TASK_ASSIGNED` (assignmentId, seat, person, selection basis), `TASK_CLAIMED`, `TASK_UNCLAIMED`, `TASK_REASSIGNED`, `TASK_COMPLETED` (decision), `TASK_MARKED_OUT_OF_HIERARCHY` |
| Authority | `APPROVAL_RECORDED` (payload_hash, task version, rule version, power limit), `APPROVAL_INVALIDATED` (changed fields), `ACTED_FOR` (substitute, seat, delegation id), `SOD_EXCEPTION_GRANTED`, `SOD_VIOLATION_BLOCKED`, `POWER_LIMIT_EXCEEDED` |
| Rules / signing | `RULE_EVALUATED` (DMN key, version, inputs digest, output), `SIGNATURE_REQUESTED`, `SIGNATURE_APPLIED` (cert serial, hash, ESP/DSC, timestamp token), `SIGNATURE_FAILED` |
| External effects (VI.3) | `EXT_OP_REQUESTED` (operationId, attemptId, capability class), `EXT_OP_ACKNOWLEDGED`, `EXT_OP_FAILED`, `EXT_OP_UNKNOWN`, `RECON_ITEM_OPENED`, `RECON_ITEM_CLAIMED`, `RECON_ITEM_RESOLVED` (action, evidence), `LATE_RESULT_AFTER_DELETE`, `CANCEL_REQUESTED`, `CANCEL_ACKED` |
| Operations (VI.5) | `INCIDENT_OPENED`, `INCIDENT_RETRIED`, `INCIDENT_RESOLVED`, `INSTANCE_CANCELLED`, `INSTANCE_DELETED` (open operationIds), `INSTANCE_MODIFIED`, `OUTBOX_REQUEUED`, `OUTBOX_SKIPPED` |
| SLA (R31) | `SLA_STARTED`, `SLA_PAUSED`, `SLA_RESUMED`, `SLA_WARNING`, `SLA_BREACHED`, `ESCALATED`, `DEEMED_DECISION` |
| Notifications (R60) | `NOTIFICATION_QUEUED`, `NOTIFICATION_SENT`, `NOTIFICATION_DELIVERED`, `NOTIFICATION_FAILED` (DLT reason), `NOTIFICATION_SUPPRESSED_DUPLICATE` |
| Documents | `DOCUMENT_UPLOADED` (scan result), `DOCUMENT_FETCHED` (DigiLocker URI, issuer), `DOCUMENT_ISSUED`, `DOCUMENT_PUSHED` |
| Records (R61) | `RETENTION_CLASSIFIED`, `LEGAL_HOLD_SET`, `LEGAL_HOLD_RELEASED`, `RECORD_REVIEWED`, `RECORD_TRANSFERRED_NAI`, `RTI_REQUEST_LINKED`, `DATA_ERASED` (fields, copies scrubbed) |
| Admin | `TENANT_CREATED`, `TENANT_CONFIGURED`, `TENANT_ACTIVATED`, `SEAT_CREATED`, `SEAT_MOVED`, `POSTING_EFFECTIVE`, `ADDITIONAL_CHARGE_GRANTED`, `DELEGATION_GRANTED`/`_EXPIRED`, `GRANT_CHANGED` (policy_version), `IDP_CONFIGURED`, `PACKAGE_SUBMITTED_FOR_REVIEW`, `PACKAGE_APPROVED`, `PACKAGE_PUBLISHED`, `PACKAGE_RETIRED`, `DLT_TEMPLATE_REGISTERED` |
| Security | `SEC_INCIDENT_OPENED` (noticed_at), `SEC_INCIDENT_CLASSIFIED` (Annexure I type), `SEC_REPORT_SUBMITTED` (reference), `SEC_INCIDENT_CLOSED`, `DPDP_BREACH_REPORTED`, `AUDIT_EXPORTED`, `AUDIT_VIEWED` |

#### X.4.0.4 Error and status codes used by the screens

| Code | Defined in | Meaning in the UI |
|---|---|---|
| `409 STALE_LEASE` | VI.3 rule 2 | Worker only; never shown to an officer |
| `409 CONFLICT_RETRY_EXHAUSTED` | VI.1 rule 7 | "The system is busy with this case. Your input is kept; try again." |
| `410 GONE_INSTANCE_DELETED` | VI.5 rule 3 | Worker only; opens `LATE_RESULT_AFTER_DELETE` when an effect is reported |
| `503 BACKPRESSURE` | VI.2.4 | New submissions refused at ≥80% outbox budget; drafts still save; continuations still commit |
| `403 ACTION_NOT_PERMITTED` | proposed (VI.4 rule 5) | Permission re-check in the command transaction failed (revoked, transferred, delegation expired) |
| `409 FORM_VERSION_STALE` | proposed (R47) | Case/task version or form version moved since the form was loaded |
| `409 TASK_ALREADY_CLAIMED` | proposed | Another holder of the same seat group claimed first |
| `409 APPROVAL_INVALIDATED` | proposed (VI.4 rule 5) | Material payload hash differs from the approved hash |
| `409 IDEMPOTENCY_PAYLOAD_MISMATCH` | proposed (R41, T01) | Same idempotency key reused with a different payload |
| `422 SOD_VIOLATION` | proposed (IX.5 rule 5) | Same person or seat already acted earlier in the chain |
| `422 POWER_LIMIT_EXCEEDED` | proposed (IX.5 rule 3) | Seat's delegated power on the action date is below the case value |
| `401 SESSION_EXPIRED` | proposed | SSO session ended; unsent input kept in the browser (per-viewer only) |

### X.4.1 Personas

One **person** can hold several personas (e.g. dealing assistant who is also an additional-charge holder). Authority always comes from the **seat held on the action date** (IX.5 rule 1), never from the persona label.

| ID | Persona | Typical seat (examples) | Sign-in | Goals | Key permissions (server-enforced) | Must never | Main screens | Context and accessibility notes |
|---|---|---|---|---|---|---|---|---|
| PER-1 | **Initiator / dealing assistant** | DDO-office accounts clerk (P1); dealing assistant, section (P2); field-office data-entry (P3); scrutiny clerk (P4 intake) | Parichay (G-SSO) | Create and submit complete cases; respond to returns quickly | `CREATE_CASE`, `EDIT_DRAFT`, `SUBMIT`, `SELECT_ASSIGNEE` (from eligible list), `WITHDRAW` own case | Check or approve own case (SoD) | S01–S09, S11–S13 | High volume, keyboard-heavy; often Hindi-first; shared office PCs; low-bandwidth district links |
| PER-2 | **Checker / scrutiny / verifier** | Financial checker, accounts officer (P1); section officer (P2); document verifier (P3); scrutiny clerk / inspector (P4) | Parichay | Verify facts and rules; forward, return or seek clarification with reasons | `CLAIM`, `CHECK`, `RETURN`, `SEEK_CLARIFICATION`, `FORWARD` | Act on a case they initiated, by person or by seat | S03, S08, S09, S10, S11, S15 (inspector) | Needs side-by-side compare (version diff), attachment viewer with zoom; inspectors on mobile |
| PER-3 | **Approving / competent / sanctioning authority (seat)** | Final approver by amount band (P1); US/DS/competent authority (P2); sanctioning authority (P3); approving officer (P4) | Parichay + DSC or eSign (G-SIGN) | Decide fast with full context; sign legally | `APPROVE`/`SANCTION`/`DISPOSE`, `REJECT`, `RETURN`, `SIGN`; power limited by DMN delegation table on the action date | Approve above the seat's power; approve a payload that differs from the one shown | S03, S08, S10, S11, S12 | Senior, time-poor, may use tablet; screen magnification common; needs one-screen summary |
| PER-4 | **Additional-charge holder / substitute** | Officer holding a second seat (transfer gap, leave, vacancy) for an effective-dated range | Parichay | Clear the other seat's queue without confusing it with their own | Same as the substituted seat, **only within the date range**, shown "acted for" | Use substitute authority after valid-to; satisfy SoD by switching seats | S02 (seat context), S03, S08, S10 | Must always see which seat they are acting as; colour must not be the only cue |
| PER-5 | **Department (tenant) administrator** | Nodal officer / NIC-cell admin of the department | Parichay + MFA; break-glass local account only for IdP outage (audited) | Onboard the department, keep seats/postings/powers correct, publish packages | `MANAGE_ORG`, `MANAGE_POSTINGS`, `MANAGE_DELEGATION_TABLES` (draft only), `MANAGE_IDP`, `MANAGE_TEMPLATES`, `SUBMIT_PACKAGE`; **cannot approve own package** | Act on cases; approve own configuration change (R43 four-eyes) | A01–A11 | Bulk CSV import of hierarchy; long tables; needs strong validation messages |
| PER-6 | **Operator / reconciliation officer** | State-DC/NIC operations team member; department finance reconciliation officer for P1/P3 money items | Parichay + MFA | Keep work flowing; resolve incidents and uncertain outcomes **without duplicate effects** | `VIEW_OPS`, `RETRY_INCIDENT`, `RESOLVE_RECON` (needs evidence), `CANCEL_INSTANCE`, `DELETE_INSTANCE` (separate, A-4), `REPAIR_OUTBOX`, `MANAGE_SEC_INCIDENT` | See masked personal fields without `READ_SENSITIVE`; resolve a money item they also initiated | O01–O09 | Works under time pressure (6 h CERT-In window); dense data; must show known / pending / uncertain distinctly |
| PER-7 | **Auditor** | Internal audit, CAG/AG audit party (read-only), vigilance; RTI CPIO in read role | Parichay | Reconstruct who did what, under which authority and rule, against which data, with what downstream result (III §11.1) | `READ_HISTORY`, `READ_AUDIT`, `EXPORT_AUDIT` (bounded, logged); `SET_LEGAL_HOLD` if granted | Change anything; see other tenants (AC-09) | U01–U03, S11 (audit mode), A11 | Exports to PDF/A and CSV; long timelines need keyboard navigation and headings |
| PER-8 | **Citizen / applicant** (P3 individual applicant, P4) | No seat; external principal, tenant bound by portal channel | Jan Parichay / MeriPehchaan with MFA (CERT-In 5.23); non-Aadhaar alternative offered (GOV-6) | Apply once, pay once, know status, get a valid certificate | Own applications only; `PAY_FEE`, `RESPOND_CLARIFICATION`, `APPEAL`, `WITHDRAW` | See officer identities beyond seat designation; see other applicants | C01–C06 | Mobile-first, low bandwidth, assisted mode at CSC/kiosk; state language and Hindi; DPDP notice and consent; possibly a child applicant (guardian capture) |
| PER-9 | **Integration engineer** | Department/integrator developer of worker and connector code in Java, .NET, PHP, Python or Node (AC-08) | Service principal (client credentials / mTLS) for workers; Parichay for console | Connect department systems safely; prove lease, idempotency and error semantics | `REGISTER_WORKER` (tenant + topic bound, R26), `VIEW_TOPIC_METRICS`, sandbox access; **no production data access by default** | Use another tenant's topic; run a worker under a personal account | I01–I03, A10 | Developer console; API docs offline in air-gapped install (AC-11) |

### X.4.2 Shared core: lifecycle, screens and the cross-cutting exception contracts

#### X.4.2.1 Core lifecycle mapped to screens and events

| IX.3 state | Entered by | Screen(s) where it is visible / acted on | Audit event | Notes |
|---|---|---|---|---|
| DRAFT | PER-1 / PER-8 | S04, C02 | `CASE_CREATED`, `CASE_DRAFT_SAVED` | Package version bound at creation (R43, T19) |
| SUBMITTED | PER-1 / PER-8 | S06, C03 | `CASE_SUBMITTED` | Idempotency key + payload hash (R41, VI.4 rule 5) |
| SCRUTINY ⇄ CLARIFICATION_SOUGHT | PER-2 | S08, S09, C04 | `CLARIFICATION_SOUGHT` / `_RECEIVED` | SLA pause rule per package (R31) |
| RECOMMENDATION (1..n) | PER-2 / PER-3 | S07, S08 | `TASK_ASSIGNED`, `TASK_COMPLETED` | By seat and hierarchy level (IX.5 rule 2) |
| SANCTION / APPROVAL | PER-3 / PER-4 | S08, S10 | `APPROVAL_RECORDED`, `SIGNATURE_APPLIED`, `ACTED_FOR` | Power check against the DMN delegation table on the action date |
| EXECUTION | System worker | S11 (status), O04 | `EXT_OP_REQUESTED` | operationId (VI.3 rule 4); approval ≠ payment completed (III §11.1) |
| ACKNOWLEDGED | System | S11, S12 | `EXT_OP_ACKNOWLEDGED` | |
| RECONCILED | System / PER-6 | S11, O05 | `CASE_RECONCILED` / `RECON_ITEM_RESOLVED` | |
| CLOSED | System | S11, A11 | `CASE_CLOSED`, `RETENTION_CLASSIFIED` | CSMOP A/C category (R61) overrides generic TTL (R29) |
| REJECTED · WITHDRAWN · CANCELLED · UNKNOWN_OUTCOME | PER-3 · PER-1/8 · PER-6 · system | S08 · S11/C04 · O08 · O04 | `CASE_REJECTED` · `CASE_WITHDRAWN` · `INSTANCE_CANCELLED` · `EXT_OP_UNKNOWN` | Side exits; UNKNOWN_OUTCOME is never shown to a citizen as "failed" |

#### X.4.2.2 Server-side checks referenced by every journey

| Check ID | Check | Spec |
|---|---|---|
| K1 | Tenant from authenticated identity or channel binding; request tenant id is only a filter | VI.4 rule 6 |
| K2 | Every read (list, count, search, export, get) through `AuthorizedQueryBuilder`; deny wins; no page post-filtering | VI.4 rules 1–3, A-3 |
| K3 | Sensitive action re-checks object policy **inside the command transaction**, no cache | VI.4 rule 5 |
| K4 | Approval bound to payload hash and task version; material change invalidates | VI.4 rule 5, R43 |
| K5 | Segregation of duties by person **and** seat; exception only by a higher authority with reason | IX.5 rule 5, R19/A-3 |
| K6 | Delegated power (DMN table version effective on action date) ≥ case value / case type | IX.5 rule 3 |
| K7 | Seat holder / substitute valid on the action date (postings, additional charge, leave) | IX.5 rules 1, 4 |
| K8 | Idempotency key scope, payload hash and stored result (R41); duplicate returns the first result | R41, T01 |
| K9 | Optimistic case/task version (`REV_`) and form version check under the L2 root lock | VI.1, R47 |
| K10 | Package (model + DMN + form + worker contract + connector config) version bound to the case | R43, T19 |
| K11 | Field masks (`READ_VARIABLE`, `READ_SENSITIVE`) applied in projection for lists and objects | VI.4 rule 4 |
| K12 | Attachment limits: type allow-list, size, malware scan, no active content; references not inline above 256 KB | R29, T23 |
| K13 | External effect via outbox / external task only; no remote call while L2 is held | VI.1 rule 5 |
| K14 | Worker call fenced by `(taskId, lockVersion, attemptId)`; worker principal bound to tenant + topic | VI.3 rule 2, R26, T46 |
| K15 | Back-pressure state of the outbox budget | VI.2.4 |

#### X.4.2.3 Cross-cutting exception contracts (specialised per pack in X.4.3–X.4.6)

| ID | Situation | Detected by | What the user sees | System behaviour | Recovery | Audit | Tests |
|---|---|---|---|---|---|---|---|
| CX-1 | **Permission revoked mid-flow** (transfer, posting end, delegation expired, grant removed, seat suspended) while the form is open | K3 + K7 at submit | Inline blocking banner on S08: "You no longer hold authority for seat X for this action (since 14-11-2026 10:05). Your remarks are kept as a private draft." Action buttons disabled | Command rejected `403 ACTION_NOT_PERMITTED`; nothing changes; task stays with the **seat**, so the new holder sees it; read caches converge in ≤5 s (VI.4 rule 5) | User copies remarks or forwards to new holder via a "note to successor" (non-authoritative comment) | `AUTH_DENIED` (policy_version) | T15 |
| CX-2 | **Stale form**: case or task changed since load (return/resubmit, parallel comment, amount edit, newer form version deployed) | K9 at save/submit | Banner "This case changed since you opened it (version 3 → 4)" with a field-level diff (S09); unsaved input preserved; submit disabled until reviewed | `409 FORM_VERSION_STALE`; if a material field changed after an approval, `APPROVAL_INVALIDATED` path (K4). In-flight cases keep their bound form version (R47) | Reload with merge view; re-enter decision | `TASK_COMPLETED` only on success; rejected attempt logged at debug level, not as a business event | T16, T19 |
| CX-3 | **Conflicting claim**: two holders of a pooled seat claim the same task, or two officers act on the same case | K9 under L2 (single winner) | Loser sees "Already taken by Section Officer (Budget) — R. Sharma at 10:42" with "Back to inbox"; their task row disappears from the pool on refresh | `409 TASK_ALREADY_CLAIMED`; one commit only; other command retried ≤5 times then `409 CONFLICT_RETRY_EXHAUSTED` (VI.1 rule 7) | None needed; supervisor may reassign (`TASK_REASSIGNED`) | `TASK_CLAIMED` (winner) | T33, T10, TP-01 |
| CX-4 | **Network error** between browser/mobile and server (timeout, connection reset, offline) | Client | "We could not confirm that your action reached the server. Checking…" then either the stored result or "not received — safe to send again" | Client resends with the **same idempotency key** (K8). Server returns the first result; a different payload with the same key → `409 IDEMPOTENCY_PAYLOAD_MISMATCH` | Automatic status query by idempotency key; never a second business action | Single `CASE_SUBMITTED`/`TASK_COMPLETED` | T01, TP-02 |
| CX-5 | **Unknown outcome** of an external effect (payment, DBT, fee, signature, push, dispatch) | Worker reports `outcomeUnknown`; or lease expiry on an `effect=irreversible` topic; or `CANCEL_REQUESTED` lease expiry (VI.3) | Officer/citizen: "Payment status is being confirmed with the treasury. Do not re-initiate." (never "failed"); operator: item in O04 | Task → `UNKNOWN_OUTCOME`; no automatic retry (VI.3 rule 5); reconciliation item with three actions; capability class decides query/retry (A-2) | OJ-02 | `EXT_OP_UNKNOWN`, `RECON_ITEM_OPENED` / `_RESOLVED` | T02, T27, T36 |
| CX-6 | **Session expired** during long noting/scrutiny | Gateway / IdP | Modal "Your session ended. Sign in again to continue; your text is kept on this device." | `401 SESSION_EXPIRED`; re-auth by SSO returns to the same screen; local draft only in the browser, never treated as saved | Re-login; re-check K3 on submit | `SESSION_EXPIRED`, `AUTH_LOGIN` | T32 |
| CX-7 | **Back-pressure** (outbox ≥80%) | K15 | "New submissions are paused for a short time. Your draft is saved." Approvals and completions still work | `503 BACKPRESSURE` on starts/inbound; continuations commit (VI.2.4) | Retry later; operator OJ-07 | — (metric + ops alert) | T25 |
| CX-8 | **SoD violation attempt** (same person or seat in two roles, including via additional charge) | K5 | Candidate is not offered in S07; if forced via API: "You initiated this case and cannot check or approve it." | `422 SOD_VIOLATION`; controlled exception only through AJ-route with higher authority and reason | Request exception (higher authority) | `SOD_VIOLATION_BLOCKED` / `SOD_EXCEPTION_GRANTED` | T17 |

### X.4.3 P1 — Bill / payment approval (IFMIS-style)

Seats in the synthetic pilot tenant "Directorate of Public Works, District X": DDO-office accounts clerk (PER-1), three eligible financial checkers in the accounts section (PER-2), approvers by amount band — Executive Engineer ≤ ₹5,00,000, Superintending Engineer ≤ ₹25,00,000, Chief Engineer above (PER-3). External: simulated State IFMIS budget query and PFMS/IFMIS payment file exchange (G-PFMS).

#### J-P1-H Happy path

| # | Actor | Screen | Action | System response and server checks | Audit event | GOV | AC | Tests |
|---|---|---|---|---|---|---|---|---|
| 1 | PER-1 | S01 Sign-in | Signs in through Parichay | Assertion verified; active postings for today resolved; tenant from identity (K1); if >1 seat → S02 | `AUTH_LOGIN` | G-SSO | AC-09 | T13 |
| 2 | PER-1 | S02 Seat context | Chooses "Accounts Clerk, DDO office" | Only seats valid today (K7) offered; context shown in header on every screen | `SEAT_CONTEXT_SELECTED` | — | AC-04 | T17 |
| 3 | PER-1 | S03 Inbox → New | Starts "Bill for payment" | Only packages published and effective for the tenant (K10); package version bound to the case | `CASE_CREATED` (package v) | — | AC-01 | T19 |
| 4 | PER-1 | S04 Draft form | Enters payee, bank account (masked after entry), amount ₹4,75,000.00, budget head, sanction reference; attaches invoice PDF; saves | Form schema version recorded (R47); decimal amount contract (T18); attachment checks (K12); draft version 1 | `CASE_DRAFT_SAVED`, `DOCUMENT_UPLOADED` | G-LANG | AC-10 | T18, T23 |
| 5 | System | S04 inline result | Duplicate-bill check (DMN on payee + invoice no. + amount) and budget-head availability (read-only simulated IFMIS query) | DMN version and inputs digest recorded; result explained in plain words ("No earlier bill with this invoice number") | `RULE_EVALUATED` ×2 | G-PFMS (budget query) | AC-01 | T19 |
| 6 | PER-1 | S06 Review and submit | Reviews read-only summary in Hindi/English; submits | Idempotency key (K8); payload hash over material fields {payee, account, IFSC, amount, head, sanction ref} (K4); DRAFT → SUBMITTED; initiator person + seat recorded for SoD | `CASE_SUBMITTED` (payload_hash) | — | AC-01, AC-02 | T01, T16 |
| 7 | PER-1 | S07 Checker selection | Sees the three eligible checkers (seat, holder name, current queue size); selects one | Candidates computed server-side via K2 excluding initiator person and seat (K5); selection re-validated in the command; assignment ID stored | `TASK_ASSIGNED` (assignmentId, basis=explicit selection) | G-DLT / G-MAIL (new-task alert) | AC-01 | T17 |
| 8 | PER-2 | S03 Inbox | Opens and claims the task | Claim under L2 (K9); SLA clock for "check within 3 working days" starts (business calendar) | `TASK_CLAIMED`, `SLA_STARTED` | — | AC-01 | T22, T33 |
| 9 | PER-2 | S08 Task detail | Verifies invoice against bill; adds checker remarks; "Forward for approval" | K3 permission re-check; K5 SoD; K9 version; payload hash unchanged | `TASK_COMPLETED` (FORWARD) | — | AC-01 | T15, T17 |
| 10 | System | — | Amount-band DMN (delegation of financial powers, version effective today) chooses "Executive Engineer" seat; resolves the holder on the date | `RULE_EVALUATED` with table version; task assigned to the **seat** | `RULE_EVALUATED`, `TASK_ASSIGNED` | — | AC-01 | T19 |
| 11 | PER-3 | S08 → S10 | Reviews one-screen summary (amount in figures and words, payee, head, checker remarks); approves and signs | K3, K5, K6 (seat power ≥ ₹4,75,000 on today's date), K4 (approval bound to payload hash + task version); signature over the same hash (R57) | `APPROVAL_RECORDED`, `SIGNATURE_APPLIED`, `CASE_APPROVED` | G-SIGN | AC-01, AC-02 | T16 |
| 12 | System worker | — | Payment-file worker (any Tier-1 SDK) builds the signed IFMIS/PFMS payment file with SSID and scheme code | External task, topic `effect=irreversible`, `exclusive=resource:billId` (VI.6); operationId stable (VI.3 rule 4); file is written after commit (K13) | `EXT_OP_REQUESTED` (operationId, capability class) | G-PFMS (SFTP out) | AC-03, AC-08 | T02, T40, T46 |
| 13 | System | S11 status | Status/response file ingested through the inbox (dedup on file + record id) | EXECUTION → ACKNOWLEDGED | `EXT_OP_ACKNOWLEDGED`, `CASE_ACKNOWLEDGED` | G-PFMS (SFTP in) | AC-01 | T09 |
| 14 | System | S11, S12 | Bank debit confirmation matched to operationId; payment advice and voucher generated (bilingual, amount in words in Hindi and English) | ACKNOWLEDGED → RECONCILED | `CASE_RECONCILED`, `DOCUMENT_ISSUED` | G-LANG | AC-01 | T27 |
| 15 | System | S13 | Notifies initiator (in-app + e-mail) and payee (SMS on registered `-G` header with registered template) | Template validated against DLT registry before sending (R60) | `NOTIFICATION_SENT` | G-DLT, G-MAIL | — | TP-05 |
| 16 | System | S11 | Case closed; retention category C/10 assigned by package default (financial voucher schedule to confirm with the department) | RECONCILED → CLOSED | `CASE_CLOSED`, `RETENTION_CLASSIFIED` | G-REC | — | T30 |
| 17 | PER-7 | U01 → S11 | Opens the timeline: every step shows seat, person, acted-for, rule version, payload hash, operationId, downstream status | Same read policy as lists (K2); masks (K11) | `AUDIT_VIEWED` | — | AC-01, AC-09 | T14, T37 |

#### P1 exception journeys

| ID | Trigger (source) | Detected by | Actor / screen and what they see | System behaviour | Recovery path | Audit | AC | Tests |
|---|---|---|---|---|---|---|---|---|
| J-P1-E01 | **Amount or beneficiary changed after approval** (IX.4, AC-02) — e.g. clerk reopens via authorised correction, or approved bill is edited through API | K4 at execution and at any approval read | PER-1/PER-3 S08: banner "Approval no longer valid: amount changed ₹4,75,000 → ₹4,95,000"; status returns to RECOMMENDATION | Payment worker is **not** created (approval hash ≠ current hash); approval marked invalid; DMN re-routes (new band may pick a higher seat) | Re-check and re-approval on new hash; old approval kept in history | `APPROVAL_INVALIDATED` (changed fields), `TASK_ASSIGNED` | AC-02 | T16 |
| J-P1-E02 | **Duplicate submit** (double click, two tabs, retry after timeout) (IX.4) | K8 | PER-1 S06: second click shows the same reference number "Already submitted as BILL/2026/00412" | One case; repeated response identical; changed payload with same key → `409 IDEMPOTENCY_PAYLOAD_MISMATCH` | None | One `CASE_SUBMITTED` | AC-01 | T01 |
| J-P1-E03 | **Duplicate bill** (same invoice submitted as a new case) | Duplicate-bill DMN (step 5) | PER-1 S04: blocking validation with link to the earlier case (only if visible under K2; otherwise "a bill with these details already exists in your office") | Submission blocked; override only via checker with reason if the package allows it | Withdraw or justify | `RULE_EVALUATED` | AC-01 | T14 |
| J-P1-E04 | **Payment success with lost response** (IX.4, AC-03) | Worker reports `outcomeUnknown`, or lease expiry on irreversible topic | PER-1/PER-3 S11: "Payment status being confirmed with treasury — do not re-initiate"; PER-6 sees item in O04 | `UNKNOWN_OUTCOME`; no automatic retry; reconciliation item with evidence (file name, SFTP upload log, operationId) | OJ-02: query status file / bank statement by operationId → *confirm success with result* → ACKNOWLEDGED | `EXT_OP_UNKNOWN`, `RECON_ITEM_OPENED`, `RECON_ITEM_RESOLVED` | AC-03 | T02, T27, T36 |
| J-P1-E05 | **Approver transferred mid-flow** (IX.4) | K7 (posting end date) | Old holder: CX-1 banner if the form is open; new holder of the EE seat: task in S03 with "assigned to seat since 12-11-2026" | Task stays on the seat; no reassignment needed. If the task was person-claimed, the claim is released at relieving time | New holder acts; history shows both persons | `POSTING_EFFECTIVE`, `TASK_UNCLAIMED`, `AUTH_DENIED` (if old holder tries) | AC-01 | T15 |
| J-P1-E06 | **Delegate expired** (IX.4) — substitute's valid-to passed while task was in their queue | K7 at action and a timer at valid-to | PER-4 S03: task leaves the "acting for" section; open form gets CX-1 banner | Task returns to the substantive seat holder, or to the escalation seat if the seat is vacant (no-candidate fallback, III §11.1) | Seat holder or escalation authority acts | `DELEGATION_EXPIRED`, `TASK_REASSIGNED` | AC-04 | T15 |
| J-P1-E07 | **Multiple eligible checkers / no candidate** (III §11.3) | Candidate calculation | S07: list of eligible seats; if empty: "No eligible checker today (all on leave or SoD-excluded)" with "Send to section head" | No-candidate fallback seat from package config; never auto-assigns the initiator | Section head assigns | `TASK_ASSIGNED` (basis=fallback) | AC-01 | T17 |
| J-P1-E08 | **Same person holds two roles** (initiator also has checker posting, or approver via additional charge) (III §11.3) | K5 | CX-8 | `422 SOD_VIOLATION`; exception needs higher authority + reason | Different checker selected | `SOD_VIOLATION_BLOCKED` | AC-01 | T17 |
| J-P1-E09 | **Two mandatory parallel approvals / one-of-two conditional approval** (III §11.3) — package option for high-value bills | Model (parallel ALL / ANY) | PER-3 ×2 S08: "1 of 2 approvals recorded" progress | Each approval bound to the same payload hash; any material change invalidates both | — | `APPROVAL_RECORDED` ×n | AC-01 | T16, T17 |
| J-P1-E10 | **Return and resubmit** | Checker returns with reasons | PER-1 S09: return reasons, diff of what changed when resubmitting | Version n+1; earlier remarks kept; package version unchanged unless explicitly migrated | Correct and resubmit | `CASE_RETURNED`, `CASE_RESUBMITTED` | AC-01 | T19 |
| J-P1-E11 | **Budget head insufficient** at check time | Budget query (step 5) re-run before approval | S08: "Budget available ₹3,10,000 < bill ₹4,75,000" | Approval blocked by rule; case returned or held | Re-appropriation outside pilot scope; return | `RULE_EVALUATED` | AC-01 | T19 |
| J-P1-E12 | **Payment file rejected by IFMIS/PFMS** (status file with error code, e.g. invalid IFSC) | Inbox status file | PER-1 S11: "Rejected by treasury: invalid IFSC" (partial success: approval valid, payment not made) | Confirmed failure → `EXT_OP_FAILED`; since the beneficiary must change, a corrected bill needs re-approval (E01) and a **new operationId** (A-2) | Correct and re-approve | `EXT_OP_FAILED`, `APPROVAL_INVALIDATED` | AC-02 | T16, T27 |
| J-P1-E13 | **Cancel while payment worker is mid-call** (VI.5) | O08 cancel/delete | PER-6 O08 consequence preview: "1 external operation in progress — will go to CANCEL_REQUESTED" | Task → `CANCEL_REQUESTED`; worker ack → CANCELLED; no ack before lease end → `UNKNOWN_OUTCOME`; late success after delete → `410` + `LATE_RESULT_AFTER_DELETE` | OJ-03 | `INSTANCE_CANCELLED` / `INSTANCE_DELETED`, `LATE_RESULT_AFTER_DELETE` | AC-03 | T11, T39 |
| J-P1-E14 | **Restore from backup after payments were made** | OPERATION_LEDGER + inbox | PER-6 O04: restored cases with known downstream success appear as reconciliation items | Same operationId after restore; no blind repeat | OJ-02 | `RECON_ITEM_OPENED` | AC-03 | T27, T35 |
| J-P1-CX1 | Permission revoked: checker's posting ends while S08 open | CX-1 | CX-1 | CX-1 | CX-1 | `AUTH_DENIED` | AC-01 | T15 |
| J-P1-CX2 | Stale form: initiator withdraws/corrects while approver has S08 open | CX-2 | Approver: "Case changed (v2 → v3) — approval disabled" | `409 FORM_VERSION_STALE` | Reload, compare, re-decide | — | AC-02 | T16, T19 |
| J-P1-CX3 | Conflicting claim between two pooled accounts checkers | CX-3 | CX-3 | CX-3 | CX-3 | `TASK_CLAIMED` | — | T33, TP-01 |
| J-P1-CX4 | Network error on "Approve and sign" | CX-4 | "Checking whether your approval was recorded…" | Same idempotency key; signature not requested twice for the same hash (signature step keyed by approval idempotency key) | Status query | single `APPROVAL_RECORDED` | AC-01 | T01, TP-02 |
| J-P1-CX5 | Unknown outcome | = J-P1-E04 | | | | | AC-03 | T02, T27 |

### X.4.4 P2 — File movement (e-Office style)

Seats: dealing assistant (PER-1), section officer (PER-2), under secretary, deputy secretary, joint secretary / competent authority (PER-3), additional-charge holder (PER-4). CSMOP 2022 Box-e.7.1: DSC or eSign is **mandatory for dealing officers and above** before forwarding an e-file (G-SIGN). Output: approved note and dispatch letter.

#### J-P2-H Happy path

| # | Actor | Screen | Action | System response and server checks | Audit event | GOV | AC | Tests |
|---|---|---|---|---|---|---|---|---|
| 1 | PER-1 | S01, S02 | Parichay sign-in; seat "Dealing Assistant, Section B-2" | K1, K7 | `AUTH_LOGIN`, `SEAT_CONTEXT_SELECTED` | G-SSO | AC-09 | T13 |
| 2 | PER-1 | S03 → S05 File and noting | Creates e-file from a received dak (receipt diarised and linked); subject head and file number generated | Package P2 vX bound (K10); subject → routing DMN key | `CASE_CREATED`, `DOCUMENT_UPLOADED` | G-LANG | AC-04 | T19 |
| 3 | PER-1 | S05 | Writes note in Hindi or English (bilingual noting allowed), drafts reply letter (DFA) | Note paragraphs versioned; each paragraph locked after forwarding | `CASE_DRAFT_SAVED` | G-LANG | AC-10 | T32 |
| 4 | PER-1 | S07 "Put up to" | System proposes next level from "put up to" DMN (subject × level); user confirms | Only hierarchy-valid seats offered (IX.5 rule 2); deviation = out-of-hierarchy (E01) | `RULE_EVALUATED` | — | AC-04 | T19 |
| 5 | PER-1 | S10 Signing | Signs note hash with DSC (signer agent) or eSign OTP before forwarding | CSMOP mandatory signature for dealing officer and above (package flag); hash of note version signed; PAdES/CMS with timestamp | `SIGNATURE_APPLIED`, `TASK_COMPLETED` (FORWARD) | G-SIGN | AC-04 | TP-03 |
| 6 | PER-2 | S03 → S05/S08 | Section officer reads, adds noting, forwards to under secretary, signs | K3, K7, K9; note version n+1 | `SIGNATURE_APPLIED`, `TASK_COMPLETED` | G-SIGN | AC-04 | T15 |
| 7 | PER-3 (US) | S08 | Under secretary forwards to deputy secretary (level 2 → 3) | Routing by level | `TASK_COMPLETED` | G-SIGN | AC-04 | — |
| 8 | PER-3 (DS) | S08 | Deputy secretary forwards to competent authority (level 3) | "Who can dispose" DMN identifies the competent seat for this subject | `RULE_EVALUATED`, `TASK_ASSIGNED` | — | AC-04 | T19 |
| 9 | PER-3 (CA) | S08 → S10 | Approves the proposal and the draft letter; signs | K3, K4 (approval bound to the hash of note + draft letter version), K6 (disposal power) | `APPROVAL_RECORDED`, `SIGNATURE_APPLIED`, `CASE_APPROVED` | G-SIGN | AC-04 | T16 |
| 10 | PER-1 | S08 Dispatch | Issues the approved letter: number, date, recipients | Letter rendered from the approved version only; any edit after approval invalidates (K4) | `DOCUMENT_ISSUED` | G-LANG | AC-04 | T16 |
| 11 | System worker | — | Dispatch worker: gov.in e-mail, SMS intimation, dak register entry | External tasks with operationId per recipient; e-mail via NIC relay | `EXT_OP_REQUESTED` ×n, `NOTIFICATION_SENT` | G-MAIL, G-DLT | — | T02, TP-05 |
| 12 | System | S11 | Delivery reports ingested; dispatch ACKNOWLEDGED | Inbox dedup | `EXT_OP_ACKNOWLEDGED`, `NOTIFICATION_DELIVERED` | G-MAIL, G-DLT | — | T09 |
| 13 | PER-1 / PER-2 | S11 → A11 | Closes the file; chooses retention category A or C with review year ("C/5 – Review 2031") | Category mandatory at closure (R61); legal hold blocks closure-time weeding | `CASE_CLOSED`, `RETENTION_CLASSIFIED` | G-REC | — | T30 |
| 14 | PER-7 | S11 | Reviews movement: levels, signatures, acted-for marks | K2, K11 | `AUDIT_VIEWED` | — | AC-04 | T14 |

#### P2 exception journeys

| ID | Trigger (source) | Detected by | Actor / screen and what they see | System behaviour | Recovery path | Audit | AC | Tests |
|---|---|---|---|---|---|---|---|---|
| J-P2-E01 | **Out-of-hierarchy marking** (IX.4) — officer marks the file directly to a seat outside the "put up to" path | Routing DMN at S07 | S07: warning "Joint Secretary is outside the normal path (skips 2 levels). Give a reason." Some subjects: blocked | If the package allows it: reason mandatory, skipped seats get an FYI copy; if not allowed: `422` with the allowed seats | Mark to an allowed seat, or record reason | `TASK_MARKED_OUT_OF_HIERARCHY` (reason) | AC-04 | T17, TP-06 |
| J-P2-E02 | **File sent back several levels** (IX.4, AC-04: up three levels, returned two levels, resubmitted, approved) | Return action with target level | PER-3 (CA) S08 "Return to" list shows each earlier level; PER-2 receives with return note; S09 shows what was added since | Earlier signatures stay valid for their versions; new noting is a new version; on resubmission the file goes **up again through every level returned past** (default) or straight to the returning officer (package option) | Resubmit up the chain | `CASE_RETURNED` (to level 1), `CASE_RESUBMITTED` | AC-04 | T15, T19 |
| J-P2-E03 | **Officer on leave → additional charge** (IX.4, AC-04) | K7 | PER-4 S02 chooses "acting as Deputy Secretary (additional charge, 10–24 Nov)"; header shows "Acting for DS"; S08 action label "Approve (acted for DS)" | Authority = substituted seat's powers within dates; signature is the **substitute's own DSC** (CSMOP: delegated user signs with own account and token) | — | `ACTED_FOR`, `SIGNATURE_APPLIED` | AC-04 | T17 |
| J-P2-E04 | Additional-charge holder is the **same person** who noted at a lower level | K5 | CX-8: "You already noted on this file as Under Secretary; you cannot also decide as DS" | Route to next higher seat or controlled exception with reason | Escalate | `SOD_VIOLATION_BLOCKED` | AC-04 | T17 |
| J-P2-E05 | **Parallel comments from two sections** (IX.4) | Parallel "seek comments" (ALL) | Each section sees its own comment task; originating officer sees "1 of 2 comments received" | Each comment is a separate note thread version; neither overwrites the main note (no shared mutable field); file cannot move up until ALL answered or a deadline rule fires | Chase via SLA reminder | `TASK_CREATED` ×2, `TASK_COMPLETED` ×2 | AC-04 | T10, T33 |
| J-P2-E06 | **DSC token absent / eSign OTP fails / ESP down** | Signer agent / ESP response | S10: "Signature not applied — the file has not moved." Choices: retry with another ESP, use DSC | Forward command only commits after `SIGNATURE_APPLIED`; signature attempt is idempotent on the note hash | Switch ESP (per-request configurable) | `SIGNATURE_FAILED` | AC-04 | TP-03 |
| J-P2-E07 | **eSign response lost** after OTP accepted | ESP timeout | S10: "Checking signature status with the ESP…" | Query ESP by transaction id (capability class (b), **to verify** in GOV-7 evidence); if unconfirmed, ask user to sign again: a duplicate signature on the same hash is harmless (no external business effect) | Re-sign | `SIGNATURE_REQUESTED` ×2, one `SIGNATURE_APPLIED` used | — | TP-03 |
| J-P2-E08 | **Dispatch e-mail bounce / SMS DLT rejection** | Delivery report | PER-1 S11 partial success: "Letter issued; 1 of 3 recipients not delivered" | Notification failure does not undo the approval; item in O06 | OJ-05 | `NOTIFICATION_FAILED` | — | TP-05 |
| J-P2-E09 | **File needs to be referred to another department** (multi-department rule, III §11.1) | Routing | S07 shows only seats in the other tenant **exposed for inter-department referral**; no data from the other tenant is visible | Cross-tenant referral is a message to the other tenant's inbox, not shared rows (AC-09) | — | `EXT_OP_REQUESTED` | AC-09 | T13 |
| J-P2-E10 | **RTI request on a closed file** | CPIO links RTI | PER-7 A11: RTI clock (30 days / 48 h) on the linked file | Legal hold prevents weeding; export bounded and logged | — | `RTI_REQUEST_LINKED`, `LEGAL_HOLD_SET` | — | T30, TP-08 |
| J-P2-CX1 | Permission revoked: officer transferred while noting | CX-1 | CX-1; note text kept as private draft | Task stays on seat | Successor continues | `AUTH_DENIED` | AC-04 | T15 |
| J-P2-CX2 | Stale form: a parallel comment arrives while the officer writes noting | CX-2 | "A new comment from Section C was added" (non-material: append allowed after review); edit to the draft letter (material) → stale | Non-material additions allow submit after acknowledgement; material → `409 FORM_VERSION_STALE` | Review and continue | — | AC-04 | T19 |
| J-P2-CX3 | Conflicting claim on a section pool file | CX-3 | CX-3 | CX-3 | CX-3 | — | — | T33, TP-01 |
| J-P2-CX4 | Network error on forward | CX-4 | CX-4 | Same idempotency key; no double movement | — | — | — | T01, TP-02 |
| J-P2-CX5 | Unknown outcome of dispatch | CX-5 for e-mail/SMS: delivery reports used as the lookup; e-mail resend allowed only by officer decision (low-harm effect, class (b)) | O04 | — | OJ-02 | `EXT_OP_UNKNOWN` | — | T02 |

### X.4.5 P3 — Grant / scheme sanction (DBT-style)

Seats: applicant (PER-8) or field-office data-entry operator (PER-1, assisted mode), document verifier and field verifier (PER-2), sanctioning authority by sanction limit (PER-3), finance/DDO for release (PER-2/PER-3). Personal data processed under DPDP s.7(b) State legitimate use, purpose-tagged with the scheme ID (G-DPDP). External: simulated beneficiary registry, DigiLocker requester, PFMS DBT file exchange.

#### J-P3-H Happy path

| # | Actor | Screen | Action | System response and server checks | Audit event | GOV | AC | Tests |
|---|---|---|---|---|---|---|---|---|
| 1 | PER-8 | C01 | Signs in with Jan Parichay/MeriPehchaan + MFA; or PER-1 signs in with Parichay and opens assisted mode | Citizen principal bound to portal channel tenant (K1) | `AUTH_LOGIN` | G-SSO | AC-09 | T13 |
| 2 | PER-8 | C02 | Reads the DPDP notice (purpose, data, contact, rights link) in chosen language; proceeds | Notice version stored with the case; purpose tag = scheme ID | `CASE_CREATED` (purpose, notice v) | G-DPDP, G-LANG | AC-10 | TP-09 |
| 3 | PER-8 | C02 | Fills application; pulls caste/income certificate from DigiLocker instead of uploading scans | Requester OAuth consent; document stored with URI and issuer as provenance; Aadhaar only as vault token (no number stored in case data) | `DOCUMENT_FETCHED` | G-DL | AC-05 | TP-07 |
| 4 | PER-8 | C02 | Submits | K8, payload hash over {beneficiary id, bank account, entitlement inputs} | `CASE_SUBMITTED` | — | AC-05 | T01 |
| 5 | System | — | Eligibility pre-check DMN (scheme rule version) and duplicate-beneficiary check against the registry | Explainable result stored; failing pre-check does not auto-reject, it flags for verifier | `RULE_EVALUATED` | — | AC-05 | T19 |
| 6 | PER-2 (verifier) | S03 → S08 | Verifies documents; marks each as verified / doubtful | K3, K5 (verifier ≠ data-entry operator who keyed it) | `TASK_COMPLETED` (VERIFIED) | — | AC-05 | T17 |
| 7 | System | — | "Field verification required?" DMN (e.g. amount or risk score) → yes | Task to field-verifier seat for the beneficiary's block | `RULE_EVALUATED`, `TASK_ASSIGNED` | — | AC-05 | — |
| 8 | PER-2 (field) | S15 (mobile) | Records visit, geotag and photo; submits | Offline capture allowed; upload with idempotency key; photo through K12 | `TASK_COMPLETED`, `DOCUMENT_UPLOADED` | — | AC-05 | T01, T23 |
| 9 | PER-2 | S08 | Recommends with entitlement amount (DMN computed, override needs reason) | K9 | `TASK_COMPLETED` (RECOMMEND) | — | AC-05 | T18 |
| 10 | PER-3 | S08 → S10 | Sanctions; signs sanction order | K6: sanction limit of the seat on the date; K4 binding; batch sanction allowed (each case its own hash) | `APPROVAL_RECORDED`, `SIGNATURE_APPLIED`, `DOCUMENT_ISSUED` (sanction order) | G-SIGN, G-LANG | AC-05 | T16 |
| 11 | System worker | — | DBT file to PFMS: one record per beneficiary instalment, operationId per record | `effect=irreversible`; `exclusive=resource:beneficiaryAccount` | `EXT_OP_REQUESTED` | G-PFMS | AC-05, AC-08 | T02, T40 |
| 12 | System | S11, C04 | PFMS response file: credited | ACKNOWLEDGED → RECONCILED | `EXT_OP_ACKNOWLEDGED`, `CASE_RECONCILED` | G-PFMS | AC-05 | T27 |
| 13 | System | C04, C05 | SMS (`-G` header) and portal status "Amount credited on 18-11-2026" | DLT template validated; no free URL | `NOTIFICATION_SENT` | G-DLT | — | TP-05 |
| 14 | System | — | Closed; retention per scheme schedule; personal-data retention per tenant policy | Erasure disabled for State records unless policy says otherwise (GOV-4) | `CASE_CLOSED`, `RETENTION_CLASSIFIED` | G-REC, G-DPDP | — | T30 |

#### P3 exception journeys

| ID | Trigger (source) | Detected by | Actor / screen and what they see | System behaviour | Recovery path | Audit | AC | Tests |
|---|---|---|---|---|---|---|---|---|
| J-P3-E01 | **Ineligible after verification** (IX.4, AC-05) | Verifier finding + eligibility DMN | PER-3 S08 reject form requires reasons from a coded list plus free text; PER-8 C04: rejection order (bilingual) with reasons and appeal route and deadline | REJECTED; signed rejection order; appeal timer starts | Appeal (E04) | `CASE_REJECTED` (reason codes), `DOCUMENT_ISSUED` | AC-05 | T11, T32 |
| J-P3-E02 | **Partial disbursement** (IX.4, AC-05) — instalment plan, or batch where some records fail | Response file | PER-3/PER-1 S11 **partial success**: "2 of 3 instalments credited; 1 pending (scheduled 01-04-2027)"; for batch: "98 of 100 credited, 2 failed" | Each record has its own operationId and state; case state stays EXECUTION until all instalments are terminal | Failed records → E03 | `EXT_OP_ACKNOWLEDGED` ×n, `EXT_OP_FAILED` ×m | AC-05 | T27 |
| J-P3-E03 | **Beneficiary account failure** (IX.4, AC-05) — account closed / name mismatch | Response file reason code | PER-8 C04: "Payment returned by bank: account closed. Update bank details." Officer S11 sees the same | Confirmed failure → `FAILED` for that operationId; new account = **material change** → re-verification of the account and re-sanction rule per package (K4); new payment = **new operationId** (A-2) | Applicant updates account → verifier → sanction → new DBT record | `EXT_OP_FAILED`, `APPROVAL_INVALIDATED`, `EXT_OP_REQUESTED` | AC-05, AC-02 | T16, T27 |
| J-P3-E04 | **Re-verification on appeal** (IX.4) | Appeal filed (C05) | PER-8 C05 files appeal with grounds; appellate seat S08 orders re-verification | Linked appeal case; re-verification **by a different verifier** (K5 extended to "not the original verifier"); original decision kept | Re-verify → sanction or confirm rejection | `APPEAL_FILED`, `TASK_ASSIGNED` | AC-05 | T17 |
| J-P3-E05 | **Sanction limit exceeded** | K6 | PER-3 S08: "Your sanction limit is ₹2,00,000; this case is ₹3,50,000. Forward to Joint Director." | `422 POWER_LIMIT_EXCEEDED`; routed by DMN to the next seat | Forward | `POWER_LIMIT_EXCEEDED`, `TASK_ASSIGNED` | AC-05 | T19 |
| J-P3-E06 | **Duplicate beneficiary** (same person in this or another scheme) | Registry check | Verifier S08: match shown with masked identifiers (K11) | Hold for decision; never auto-merge | Reject or justify | `RULE_EVALUATED` | AC-05 | T14 |
| J-P3-E07 | **DigiLocker fetch fails / applicant refuses consent** | Requester adapter | C02: "Could not fetch from DigiLocker — upload a scanned copy instead"; non-Aadhaar path available | Upload path with K12; document marked "self-uploaded, to verify" | Verifier checks original | `DOCUMENT_UPLOADED` | AC-05 | T23, TP-07 |
| J-P3-E08 | **Scheme rule (DMN) changed while cases are in flight** | K10 | Verifier sees rule version on S08 | In-flight cases keep the bound rule version unless the package defines re-evaluation | — | `RULE_EVALUATED` (version) | AC-05 | T19 |
| J-P3-E09 | **Child applicant** (guardian required) | Age at intake | C02 asks guardian details and consent evidence | Record flagged to block analytics (GOV-4) | — | `CASE_CREATED` (child flag) | — | TP-09 |
| J-P3-CX1 | Permission revoked: sanctioning authority transferred during batch sanction | CX-1 | Batch summary: "0 of 40 sanctioned — your authority ended at 10:05" | Each case re-checked inside its own transaction (one root per transaction, VI.1 rule 3); cases already committed stay sanctioned, rest denied | New holder continues the remaining cases | `AUTH_DENIED` | AC-05 | T15 |
| J-P3-CX2 | Stale form: applicant updates bank details while verifier has S08 open | CX-2 | Verifier: "Bank details changed by applicant" | `409 FORM_VERSION_STALE` | Re-verify | — | AC-05 | T16 |
| J-P3-CX3 | Conflicting claim between verifiers of the same block pool | CX-3 | CX-3 | CX-3 | — | — | — | T33, TP-01 |
| J-P3-CX4 | Network error from field mobile (no coverage) | CX-4 | S15: "Saved on device, waiting to upload (3)" | Upload later with the original idempotency key; server keeps the first copy | Auto-sync | — | — | T01, TP-02 |
| J-P3-CX5 | **Unknown outcome of DBT record** (file uploaded, no response file within window) | CX-5 | Applicant C04: "Payment being processed"; officer S11: "Outcome being confirmed" | `UNKNOWN_OUTCOME` per record; no new DBT file for that record until resolved | OJ-02 with PFMS response re-request / bank statement | `EXT_OP_UNKNOWN` | AC-05 | T02, T36 |

### X.4.6 P4 — Licence / permit service (time-bound)

Seats: citizen/applicant (PER-8), scrutiny clerk (PER-1/PER-2), inspector (PER-2), approving officer (PER-3). Rules: fee calculation, "inspection required?", deemed-approval/SLA rules under the state's time-bound service law (to confirm with the G1 buyer). External: simulated payment gateway (G-PG), simulated eSign, simulated DigiLocker issuer.

#### J-P4-H Happy path

| # | Actor | Screen | Action | System response and server checks | Audit event | GOV | AC | Tests |
|---|---|---|---|---|---|---|---|---|
| 1 | PER-8 | C01 | Signs in (Jan Parichay + MFA) or creates a portal profile with OTP; language chosen (Hindi / English / state language) | K1; MFA mandatory for citizens (CERT-In 5.23) | `AUTH_LOGIN` | G-SSO, G-LANG | AC-10 | T13, T32 |
| 2 | PER-8 | C02 | Fills licence application (trade, premises), attaches documents or pulls from DigiLocker; reads DPDP notice | Form v bound (R47); K12 on uploads | `CASE_CREATED`, `CASE_DRAFT_SAVED`, `DOCUMENT_FETCHED` | G-DL, G-DPDP | AC-06 | T23 |
| 3 | System | C03 | Fee computed by DMN (category, area, duration); shown with breakup | `RULE_EVALUATED` (fee table version) | `RULE_EVALUATED` | — | AC-06 | T18, T19 |
| 4 | PER-8 | C03 → gateway | Pays fee | Payment request with operationId as merchant txn reference and checksum; case SUBMITTED only after payment confirmation | `EXT_OP_REQUESTED` | G-PG | AC-06 | T01 |
| 5 | System | C03 | Gateway callback (signed, checksum verified) → fee confirmed; receipt generated | Inbound callback through the inbox (dedup on gateway txn id, T09/T47); SLA clock starts on fee confirmation | `EXT_OP_ACKNOWLEDGED`, `CASE_SUBMITTED`, `SLA_STARTED` | G-PG | AC-06 | T09, T47, T22 |
| 6 | PER-1 (scrutiny) | S03 → S08 | Scrutinises; seeks clarification on a missing NOC | CLARIFICATION_SOUGHT; SLA paused if the package rule says so | `CLARIFICATION_SOUGHT`, `SLA_PAUSED` | G-DLT (SMS to applicant) | AC-06 | T22 |
| 7 | PER-8 | C04 | Responds with NOC | Version n+1; SLA resumed | `CLARIFICATION_RECEIVED`, `SLA_RESUMED` | — | AC-06 | T19 |
| 8 | System | — | "Inspection required?" DMN → yes; inspector seat for the ward | Task with inspection SLA | `RULE_EVALUATED`, `TASK_ASSIGNED` | — | AC-06 | — |
| 9 | PER-2 (inspector) | S15 | Schedules visit (applicant notified), inspects, records findings and photos | Offline-capable; idempotent upload | `TASK_COMPLETED`, `DOCUMENT_UPLOADED`, `NOTIFICATION_SENT` | G-DLT | AC-06 | T01 |
| 10 | PER-3 | S08 → S10 | Approves; certificate generated from approved data; signs (simulated eSign) | K3, K4 (certificate content hash = approved hash), K5, K6 | `APPROVAL_RECORDED`, `SIGNATURE_APPLIED`, `DOCUMENT_ISSUED` | G-SIGN, G-LANG | AC-06 | T16, TP-03 |
| 11 | System worker | — | Push certificate to DigiLocker (issuer, doc-type, URI) | External task, operationId; push failure does not block certificate download | `EXT_OP_REQUESTED`, `DOCUMENT_PUSHED` | G-DL | AC-06 | TP-07 |
| 12 | PER-8 | C05 | Downloads signed PDF (bilingual); receives SMS with whitelisted portal URL | DLT template + URL whitelist check | `NOTIFICATION_SENT` | G-DLT | AC-06 | TP-05 |
| 13 | System | S11 | Closed within SLA; retention per licence schedule | | `CASE_CLOSED`, `RETENTION_CLASSIFIED` | G-REC | — | T30 |

#### P4 exception journeys

| ID | Trigger (source) | Detected by | Actor / screen and what they see | System behaviour | Recovery path | Audit | AC | Tests |
|---|---|---|---|---|---|---|---|---|
| J-P4-E01 | **SLA breach and escalation** (IX.4, AC-06) | SLA timer on business calendar (holidays, working hours, paused periods) | PER-2 S03: amber at 75% ("due in 1 working day"), red at breach; escalation seat (e.g. Deputy Commissioner) gets the case in its "Escalated" section; applicant C04 sees "Delayed — escalated to senior officer" | `SLA_WARNING` → `SLA_BREACHED` → `ESCALATED`; schedule exactly as declared across DST-free IST, holidays and downtime catch-up (R31) | Escalation authority directs or reassigns | `SLA_WARNING`, `SLA_BREACHED`, `ESCALATED` | AC-06 | T22 |
| J-P4-E02 | **Fee paid but callback lost** (IX.4, AC-06) | No callback within window; applicant returns to C03 | C03: "Your payment is being confirmed. Do not pay again." Pay button disabled for this case | `UNKNOWN_OUTCOME` on the fee operation; reconciliation job queries gateway status by merchant txn id (capability class (b)); if paid → ACKNOWLEDGED and SLA starts from the **payment time** recorded by the gateway (package rule) | Auto-resolved by status query; else OJ-02 | `EXT_OP_UNKNOWN`, `RECON_ITEM_RESOLVED` | AC-06 | T02, T47 |
| J-P4-E03 | **Late duplicate callback** after manual resolution | Inbox dedup | None | Duplicate dropped (same gateway txn id) | — | — | AC-06 | T09, T47 |
| J-P4-E04 | **Fee paid twice** (two tabs / different txn ids) | Reconciliation | C03: "Second payment detected — refund initiated" | Second txn linked as excess; refund as a separate operation (simulated) | Refund reconciliation | `EXT_OP_ACKNOWLEDGED` ×2, `RECON_ITEM_OPENED` | AC-06 | T01, T27 |
| J-P4-E05 | **Inspection rescheduled** (IX.4) | Inspector or applicant | S15 new slot with reason; C04 shows new date | SLA policy: pause only when the applicant requested the change (package rule), otherwise clock runs | — | `TASK_COMPLETED` (RESCHEDULE), `SLA_PAUSED` (conditional) | AC-06 | T22 |
| J-P4-E06 | **Appeal after rejection** (IX.4) | C05 appeal within the deadline | C05: grounds + documents; appellate authority S08 | Linked appeal case with its own SLA; original officer excluded (K5) | Appellate decision → certificate or confirmed rejection | `APPEAL_FILED`, `CASE_REJECTED`/`CASE_APPROVED` | AC-06 | T17, T22 |
| J-P4-E07 | **Deemed approval** when the law provides it and SLA lapses | SLA timer | Applicant C05: "Deemed approved on 20-11-2026 under <rule>" | `DEEMED_DECISION` by system principal, `onBehalfOf` the competent seat; same state machine, no bypass path (IX.5 rule 6) | Officer may still record post-facto inspection where the law allows | `DEEMED_DECISION` | AC-06 | T22 |
| J-P4-E08 | **DigiLocker push fails** | Push worker | C05 **partial success**: "Certificate issued (download below). DigiLocker copy pending." | Push retried with the same URI/operationId (idempotent push to verify, GOV-9) | O04/O02 if retries exhausted | `EXT_OP_FAILED` / `INCIDENT_OPENED` | AC-06 | TP-07 |
| J-P4-E09 | **eSign of certificate fails / unknown** | Signing service | PER-3 S10: "Certificate not signed — not issued" | No unsigned certificate is ever issued; retry with another ESP | Re-sign | `SIGNATURE_FAILED` | AC-06 | TP-03 |
| J-P4-E10 | **Applicant withdraws** | C04 | C04 confirm dialog stating fee refund policy | WITHDRAWN; open tasks cancelled (`onCancel=finish-allowed` for inspection report upload) | — | `CASE_WITHDRAWN` | — | T10 |
| J-P4-CX1 | Permission revoked: approving officer suspended mid-approval | CX-1 | CX-1 | Task stays with seat | Next holder / escalation | `AUTH_DENIED` | AC-06 | T15 |
| J-P4-CX2 | Stale form: applicant uploads a clarification while scrutiny clerk is reviewing | CX-2 | "New documents from applicant" | `409 FORM_VERSION_STALE` | Review again | — | AC-06 | T19 |
| J-P4-CX3 | Conflicting claim: two inspectors in the ward pool | CX-3 | CX-3 | CX-3 | — | — | — | T33, TP-01 |
| J-P4-CX4 | Network error on citizen's mobile at "Pay" | CX-4 | "Checking your payment…" | Payment initiation keyed by operationId; gateway sees one merchant txn reference | Status query | — | AC-06 | T01, TP-02 |
| J-P4-CX5 | Unknown outcome | = J-P4-E02 (fee) and J-P4-E08/E09 (push, signature) | | | | | AC-06 | T02, T47 |

### X.4.7 Screen inventory

Role-aware menus are only a convenience; **the server-side checks are the security boundary** (III §11.2). Every screen that lists records uses K2, so counts, badges, exports and "nothing here" messages come from the same predicate as the rows (T14, T37). A screen never reveals that a hidden record exists: "not found" and "not permitted" produce the same message for objects outside the principal's visibility.

#### X.4.7.1 Screens, data and server-side checks

| ID | Screen | Personas | Data shown | Primary actions | Server-side checks | Used in |
|---|---|---|---|---|---|---|
| S01 | Sign-in (Parichay / state SSO) | PER-1…7, PER-9 console | IdP choice, language switch, GIGW footer | Sign in | IdP assertion signature, audience, time skew (NTP); account ↔ person link; MFA policy; break-glass only for PER-5 with reason | All |
| S02 | Seat context ("acting as") | PER-1…5 | Seats held today: substantive, additional charge, leave substitute, with date ranges | Select seat | K7; context token carries seat; every command re-validates it | J-P2-E03, AJ-04 |
| S03 | Work inbox | PER-1…4 | Sections: My seat · Acting for · Pool (claimable) · Escalated · Returned to me; columns: ref, subject, pack, amount, from seat, received, SLA due (business days) | Open, claim, bulk claim (≤50), filter, sort | K2 list predicate (stable sort, keyset pagination); K11 masks; counts from same predicate | All packs |
| S04 | Case draft form (P1 bill, P3 application by field office, P4 intake) | PER-1 | Package form vN, prefilled master data, inline rule results, attachments | Save draft, validate, attach, discard | K10 form version; K12; field-level authority; server-side validation is authoritative (client validation is a hint) | J-P1-H, J-P3-H |
| S05 | File and noting (P2) | PER-1…4 | Note sheet (paragraph versions, signatures), correspondence (dak), draft for approval, linked files | Add noting, attach, draft letter, link | K3, K9 per paragraph; signed paragraphs immutable | J-P2-H |
| S06 | Review and submit | PER-1 | Read-only summary of material fields, rule results, attachments, declaration | Submit, back | K8; K4 hash computed server-side; K15 | J-P1-H, J-P3-H |
| S07 | Assignee / "put up to" selection | PER-1…4 | Eligible seats with holder, workload, leave status; system-proposed default; out-of-hierarchy warning | Select, give reason | Candidate calculation server-side (K2, K5, K7); selection re-validated at commit | J-P1-H st.7, J-P2-E01 |
| S08 | Task detail and decision | PER-2…4 | Case summary, material values with "changed since last seen" markers, rule evidence, history excerpt, attachments, remarks, SLA | Approve / forward / return (to level) / seek clarification / reject (coded reasons) / reassign | K3, K4, K5, K6, K7, K9, K11 | All packs |
| S09 | Return, resubmit and version compare | PER-1…3 | Return reasons, field diff v(n) → v(n+1), who changed what | Resubmit, respond | K9; diff limited by K11 | J-P1-E10, J-P2-E02 |
| S10 | Signing dialog (DSC / eSign) | PER-1…4 | Exact document/hash to be signed, signer certificate, ESP choice | Sign, cancel, switch ESP | Hash recomputed server-side from the approved version; certificate validity, CRL/OCSP; consent text (eSign) | J-P1-H st.11, J-P2-H st.5 |
| S11 | Case timeline and audit trail | PER-1…7 | Lifecycle states, every event with seat, person, acted-for, rule/package version, payload hash, operationId, downstream status; known / pending / uncertain marked | Filter, export (PER-7), link to O05 | K2 (`READ_HISTORY`); K11; export bounded and logged | All |
| S12 | Output document viewer | PER-1…8 | Payment advice, voucher, sanction order, dispatch letter, certificate, rejection order — bilingual PDF with signature panel | Download, verify signature | Document visible only if the case is visible (K2); signature verification server-side | J-P1-H st.14 |
| S13 | Notification centre | PER-1…6 | In-app notices, delivery status of messages sent about my cases | Mark read, preferences (non-mandatory channels only) | K2; Government Message category cannot be suppressed (GOV-10) | All |
| S14 | My delegations and leave | PER-1…4 | Own substantive seats, charges given/received, validity | Request leave substitute (goes to PER-5 or controlling officer) | K7; requester cannot approve own delegation | AJ-04 |
| S15 | Field verification / inspection (mobile, offline-capable) | PER-2 | Assigned visits, map/address, checklist, photos | Schedule, record, sync | Upload with idempotency key; K12; geotag optional per DPDP minimisation | J-P3-H st.8, J-P4-H st.9 |
| C01 | Citizen home and sign-in | PER-8 | Services list, language, sign-in (Jan Parichay / MeriPehchaan + MFA), assisted-mode notice | Sign in, choose service | Channel → tenant binding (K1); MFA | J-P3-H, J-P4-H |
| C02 | Application form and documents | PER-8 | DPDP notice, form, DigiLocker pull, upload | Save, pull, upload, submit | K8, K10, K12; consent record; Aadhaar never stored in clear | J-P3-H, J-P4-H |
| C03 | Fee payment and payment status | PER-8 | Fee breakup (DMN), payment status, receipts | Pay, check status | Pay disabled while an operation is `UNKNOWN_OUTCOME`; checksum verification; operationId as merchant ref | J-P4-H st.4–5, J-P4-E02 |
| C04 | Application status and clarification | PER-8 | Stage in plain words, SLA date, clarification requests, messages | Respond, withdraw | Own cases only (K2); officer names hidden (seat designation only) | J-P4-H st.7 |
| C05 | Outcome, certificate and appeal | PER-8 | Certificate / rejection order with reasons, appeal deadline, DigiLocker status | Download, file appeal | K2; appeal deadline rule | J-P3-E04, J-P4-E06 |
| C06 | Grievance and data-rights request | PER-8 | Open grievances, 90-day DPDP SLA, RTI guidance link | File, track | K2; routed to the grievance officer seat | G-DPDP |
| O01 | Operations dashboard | PER-6 | Health per node, backlog per topic, oldest task age, outbox use vs budget (50/80/95%), drain time `B/(μ−λ)` (A-5), open incidents, open reconciliation items by age, SLA breaches, DLT failure rate, 6 h incident clocks | Drill down | `VIEW_OPS`; aggregates only, per tenant unless platform operator | OJ-01, OJ-07 |
| O02 | Incident workbench | PER-6 | Incidents (retries exhausted, parked deliveries, poison events, failed jobs) with tenant, pack, case, error class, first/last seen, owner | Claim, assign, bulk retry (guarded) | K2 on incidents; bulk ops need admin permission (R26) | OJ-01, OJ-04 |
| O03 | Incident detail and action | PER-6 | Error (no SQL/stack trace to non-platform users, R28), attempts, lock version, external effect status, consequence preview for each action | Retry, modify (skip/move token), cancel, add note | K3 in command; consequence preview computed server-side; reason mandatory | OJ-04 |
| O04 | Reconciliation queue | PER-6 | Items: `UNKNOWN_OUTCOME`, `LATE_RESULT_AFTER_DELETE`, cancel-ack missing; amount, operationId, capability class, age, owner, evidence count | Claim, filter, sort by value/age | K2; money items only to `RESOLVE_RECON` holders of that tenant; SoD (resolver ≠ initiator/approver of that case) | OJ-02, OJ-03 |
| O05 | Reconciliation item detail | PER-6 | Request fingerprint, attempts, downstream evidence (status files, gateway query, bank statement lines), OPERATION_LEDGER entry, linked case | Confirm success with result · Retry with same operationId · Mark failed; attach evidence | Evidence mandatory for "confirm" and "failed"; retry allowed only for capability class (a)/(b) after lookup (A-2); four-eyes above a configured value | OJ-02 |
| O06 | Notification and DLT failure view | PER-6, PER-5 | Failed/undelivered messages by reason (template mismatch, header, URL not whitelisted, DND n/a for -G, gateway down), affected cases, template id | Resend (same template), fix template (link to A09), suppress | Resend only within duplicate-control window; personal numbers masked (K11) | OJ-05 |
| O07 | Security incident register and CERT-In report | PER-6 (CISO/PoC) | Incident, noticed-at time, 6 h countdown, Annexure I type, affected tenants/systems, evidence (logs ≥180 days India), DPDP 72 h flag | Classify, collect evidence, generate report, record submission, close | `MANAGE_SEC_INCIDENT`; logs read-only; legal hold set automatically | OJ-06 |
| O08 | Cancel / delete consequence preview | PER-6 | Open tasks by state (OFFERED/LOCKED/UNKNOWN), open operationIds, `onCancel` policy per activity, tombstones to be written | Business cancel (reason), technical delete (reason) | `CANCEL_INSTANCE` vs `DELETE_INSTANCE` (A-4); K3; never deletes an open reconciliation item | OJ-03, OJ-04 |
| O09 | Outbox / inbox repair | PER-6 (platform) | Parked deliveries per destination, blocked roots, buffered/expired inbox messages | Requeue, skip with reason | `REPAIR_OUTBOX`; ordered destinations block until repaired (VI.2.3) | OJ-07 |
| A01 | Tenant onboarding wizard | PER-5 (platform admin creates; department admin completes) | Tenant profile, hosting profile, DB, languages, calendar, quotas, admins | Create, import, validate, activate | Platform permission to create; two department admins; quotas (T24) | AJ-01 |
| A02 | Organisation hierarchy and seats | PER-5 | Tree Ministry/Department → Directorate → Region/District → Office → Section; seats (designation + office) | Add, move (effective-dated), import CSV | Effective dating; no deletion of seats with history (deactivate only) | AJ-02 |
| A03 | Postings, transfers, additional charge, leave | PER-5 | Person ↔ seat timeline; open tasks affected by a change | Post, relieve, grant additional charge, record leave substitute | K5 preview (conflicts created by the change); impact preview of open tasks | AJ-04 |
| A04 | Delegation-of-powers tables (DMN) | PER-5 (author), competent authority (approver) | Versioned DMN: seat × case type × amount band, effective dates, test cases | Draft, test, submit for approval | Change approval (R43 four-eyes); hit-policy and gap/overlap check; effective-from ≥ approval time | AJ-03 |
| A05 | Identity provider setup | PER-5 | Parichay / state SSO metadata, protocol (SAML 2.0 / OAuth 2.0), attribute mapping, MFA, session timeout | Configure, test login, activate | Metadata signature; test login must succeed before activation; break-glass accounts limited | AJ-05 |
| A06 | Package workspace | PER-5, PER-9 | BPMN, DMN, forms, worker contracts, connector config, policy refs as one package; validation report; diff vs current | Edit, validate, simulate, submit | Unsupported constructs rejected (T20); hostile input bounded (T23) | AJ-06 |
| A07 | Package review, approval and publication | Reviewer (not the author), PER-5 | Diff, validation and simulation results, impact on in-flight cases, effective date | Approve, reject, publish, retire | Reviewer ≠ author (K5); in-flight cases keep bound version (T19) | AJ-06 |
| A08 | Roles, grants and SoD exceptions | PER-5, higher authority | Roles → permissions (incl. `CANCEL_INSTANCE`, `DELETE_INSTANCE`, `ERASE_DATA`), deny rules, SoD exceptions with reason/expiry | Grant, revoke, approve exception | `policy_version` increments; deny wins (A-3); grant changes are sensitive (K3) | AJ-04, CX-8 |
| A09 | Notification templates and DLT registry | PER-5 | PE-ID, headers (-G/-T/-S), template ids with typed variables, whitelisted URLs, e-mail templates, per language | Register, test-send, retire | Template ↔ header 1:1; variables typed; URLs from whitelist only | AJ-07 |
| A10 | Integration endpoints and worker topics | PER-9, PER-5 | Topics, capability class, `effect`, exclusivity, lease length, endpoints (PFMS SFTP, gateway, DigiLocker, ESP), credentials references | Register endpoint, bind principal, rotate secret | Egress allow-list (R27); principal ↔ tenant + topic (R26) | IJ-01 |
| A11 | Records retention, legal hold and RTI queue | PER-7, records officer | Closed cases by category (A/C) and review year, holds, RTI requests with 30-day / 48 h clocks, NAI transfer batches | Classify, review, hold, export PDF/A | R61 overrides TTL; destruction needs authorised review | J-P2-E10, AU-03 |
| U01 | Audit search | PER-7 | Events across cases by actor, seat, rule version, operationId, date | Search, open timeline | K2 on history; K11; tenant-bound | AU-01 |
| U02 | Audit export | PER-7 | Export job status, scope, row count (same predicate as list) | Request export, download | Bounded size; export job logged; same predicate as the list (T14) | AU-01, AU-02 |
| U03 | Evidence pack | PER-7, PER-6 | Per case or per release: timeline PDF/A, signatures with LTV, rule versions, reconciliation evidence; release audit pack (R52) | Generate, download | K2; checksums | AU-03 |
| I01 | Worker and topic console | PER-9, PER-6 | Registered workers (SDK, version, language), active leases, heartbeat age, error rates, `STALE_LEASE` counts | Pause topic (guarded), view errors | Own tenant topics only (K1, R26) | IJ-01 |
| I02 | Sandbox and failure injection | PER-9 | Simulated PFMS/gateway/ESP/DigiLocker/SMS with injectable faults (lost response, duplicate callback, delay, reject) | Run scenario, view trace | Sandbox tenant only; no production data | IJ-01, IJ-02 |
| I03 | Service principals and API credentials | PER-9, PER-5 | Principals, scopes (tenant + topics), expiry, last use | Create (needs PER-5 approval), rotate, revoke | Secrets shown once; revocation effective ≤5 s (VI.4 rule 5) | IJ-01 |

**Screen count: 47** (staff 15, citizen 6, operator 9, admin 11, auditor 3, integration 3).

#### X.4.7.2 States per screen

Global rules, which every cell below specialises:
- **Loading:** skeleton with `aria-busy`; actions disabled until the seat context and permissions are resolved; after 10 s "Still loading" with retry; no spinner-only states.
- **Empty:** says *why* (nothing assigned / filters exclude all / not yet started) and the next action; never "no permission" for a hidden object.
- **Unauthorised:** server 403/404 → "Not available for your current seat"; offers seat switch (S02) when another held seat could act; never leaks existence.
- **Stale:** banner with old → new version and a diff; user input kept; submit disabled until acknowledged (CX-2).
- **Conflict:** who won and when; back-to-list action (CX-3); retry-exhausted message for `409 CONFLICT_RETRY_EXHAUSTED`.
- **Error:** plain-language message + reference (trace id) + safe retry (same idempotency key, CX-4); no stack traces (R28).
- **Success:** reference number, new state, next seat, SLA due; announced via `aria-live="polite"`.
- **Partial success:** lists done / pending / uncertain separately (Part VII §8.1) with what the user should and should not do next.

| Screen | Loading | Empty | Unauthorised | Stale | Conflict | Error | Success | Partial success |
|---|---|---|---|---|---|---|---|---|
| S01 | Redirect to IdP with progress text | n/a | "Your account is not linked to a seat in this department" + nodal officer contact | IdP metadata rotated → admin alert, user sees retry | n/a | IdP unreachable: retry; break-glass only for PER-5 | Lands on S02/S03 | Signed in but no seat valid today → read-only S11 of own past cases |
| S02 | Seats list skeleton | "No seat valid today" with posting dates | n/a | Charge ended while page open → seat removed with notice | n/a | Retry | Header shows "Acting as …" | Some charges pending approval shown greyed with reason |
| S03 | Row skeletons, counts after rows | Per section: "No tasks for Accounts Clerk today" / "Filters hide all tasks — clear filters" | Section hidden if no permission | "12 new tasks — refresh" (no auto reorder under the cursor) | Claimed-by-other rows marked and removed on refresh | Partial load → retry; counts never shown without rows | Claim → row moves to "My seat" | Bulk claim: "38 of 40 claimed; 2 taken by others" |
| S04 | Form skeleton; master data lazy | New draft prefilled | Package not available to seat | Form version retired while drafting → draft stays on bound version, notice | Two tabs editing one draft → second gets stale on save | Field errors linked (error summary at top) | "Draft saved 10:42" | Saved but rule service slow: "Saved; checks pending" |
| S05 | Note sheet loads newest first | New file: empty note with guidance | File not marked to seat | New noting/comment arrived (CX-2) | Two officers noting at once → second stale | Retry; text kept | "Forwarded to Under Secretary" | Forwarded, FYI copies to skipped seats queued |
| S06 | Summary computing | n/a | Draft not owned by seat | Draft edited elsewhere | Duplicate submit → same reference (J-P1-E02) | `503 BACKPRESSURE` → "Draft saved; submit later" | Reference number + next step | Submitted; notification to checker failed (O06) |
| S07 | Candidate list computing | "No eligible seat" + fallback action (J-P1-E07) | n/a | Candidate went on leave since load → re-validated at commit, notice | Selected seat filled meanwhile → re-select | Retry | "Assigned to Accounts Officer-2 (A. Khan)" | n/a |
| S08 | Summary first, attachments lazy | n/a | CX-1 banner; actions disabled | CX-2 diff; approval disabled | CX-3 | Retry with same key | "Approved and signed; sent to treasury" | "Approved; payment instruction queued"; "Approved; 1 of 2 approvals pending" |
| S09 | Diff computing | "No changes since return" warning before resubmit | n/a | Another resubmission landed | n/a | Retry | "Resubmitted as version 3" | n/a |
| S10 | Waiting for signer agent / ESP | "No DSC token detected — insert token or use eSign" | Seat may not sign this document type | Document changed since dialog opened → signing blocked | Second signing attempt on same hash → reuses the first | ESP timeout → status check, then retry or switch ESP | "Signed — certificate valid, timestamped" | Signed but LTV data (OCSP) pending → retried in background |
| S11 | Timeline paginated by time | "No events yet" (new draft) | Case outside visibility → same as not found | Live events appended with "new events" marker | n/a | Retry | n/a (read) | Uncertain items flagged "Outcome being confirmed" |
| S12 | PDF render progress | "Document will be available after approval" | Same as S11 | Superseded version → banner linking the current one | n/a | Render failure → retry; original stays in store | Downloaded | Issued, DigiLocker push pending |
| S13 | List skeleton | "No notifications" | n/a | n/a | n/a | Retry | Marked read | "SMS to payee failed; e-mail delivered" |
| S14 | Skeleton | "No delegations" | n/a | Delegation approved/expired meanwhile | n/a | Retry | Request sent | n/a |
| S15 | Offline cache first | "No visits scheduled" | Visit reassigned → removed from device on sync | Case changed while offline → sync conflict review | Same visit done by two inspectors → second upload kept as annex, flagged | "Saved on device; will upload" | "Report uploaded" | "3 of 5 photos uploaded" |
| C01 | Page skeleton, low-weight | n/a | n/a | n/a | n/a | IdP down: "Try again later"; assisted-mode desk info | Dashboard | n/a |
| C02 | Progressive form, section by section | New application | n/a | Form version changed: draft keeps its version | Two devices → last draft save stale | Inline errors, error summary | "Application saved" | "Saved; DigiLocker fetch failed — upload instead" |
| C03 | "Contacting payment gateway" | n/a | Not own application → not found | Fee table changed before payment → fee recomputed with notice | Second payment attempt while one is unknown → blocked | Gateway error → "No money deducted? Check status" | Receipt shown and sent | "Payment being confirmed — do not pay again" |
| C04 | Status skeleton | "No applications yet" | Not own → not found | Status changed while viewing → refreshed | n/a | Retry | Clarification sent | "Clarification received; officer review pending" |
| C05 | Document render | "Decision not yet made" | Not own → not found | Certificate reissued → link to latest | Appeal already filed → shows it | Retry | Download / appeal reference | Issued; DigiLocker copy pending |
| C06 | Skeleton | "No grievances" | n/a | n/a | n/a | Retry | Grievance reference + SLA date | n/a |
| O01 | Tiles load independently | "No incidents" (explicitly green, with last-checked time) | Tenant tiles outside scope hidden | Auto-refresh with timestamp | n/a | Telemetry exporter down → dashboard says "metrics stale since 10:40" (T49) | n/a | Some nodes not reporting → marked unknown, not healthy |
| O02 | Rows skeleton | "No open incidents" | n/a | Incident resolved by another operator | Claimed by another operator | Retry | Claimed / retried | Bulk retry: "18 retried; 2 now UNKNOWN_OUTCOME (irreversible topic) — moved to reconciliation" |
| O03 | Detail + preview computing | n/a | Action needs permission not held → disabled with reason | Incident state changed → preview recomputed | Concurrent action → one wins | Retry | "Retried; job attempt 4 started" | "Modified; 1 external task cancel requested, ack pending" |
| O04 | Rows skeleton | "No open reconciliation items" | Money items hidden without `RESOLVE_RECON` | Item resolved elsewhere | Claimed by another officer | Retry | Resolved item leaves queue | n/a |
| O05 | Evidence loading per source | "No evidence yet — run status lookup" | SoD: "You approved this bill; another officer must resolve it" | Late worker result arrived → evidence added, decision rechecked | Two officers resolving → one wins | Lookup failed → retry lookup, no resolution | "Confirmed success; case moves to ACKNOWLEDGED" | "Lookup inconclusive — item kept open, escalated to finance" |
| O06 | Rows skeleton | "No failed notifications in 7 days" | Personal numbers masked without `READ_SENSITIVE` | Delivery report arrived | n/a | Retry | Resent | "Resent 40 of 42; 2 template mismatches" |
| O07 | Register skeleton | "No open security incidents" | Only CISO/PoC role | Evidence updated by another responder | Two responders editing report → section locks | Report generation failure → manual template download | "Report recorded as submitted at 14:52 (ref …)" | "Report submitted; follow-up evidence pending" |
| O08 | Preview computing | n/a | `DELETE_INSTANCE` absent → delete hidden; cancel shown if held | Instance changed → preview recomputed before confirm | Another cancel in progress | Retry | "Cancelled; 1 cancel request sent to worker" | "Deleted; 1 operation in CANCEL_REQUESTED, tombstone kept 30 days" |
| O09 | Rows skeleton | "No parked deliveries" | Platform role only | Delivery state changed | Concurrent requeue → one wins | Retry | Requeued | "Requeued 10; 1 still failing (re-parked)" |
| A01 | Wizard steps | n/a | Platform-only steps hidden | Draft tenant edited by co-admin | Two admins editing a step → second stale | Import errors listed per row | "Tenant active" | "Hierarchy imported 1,180 of 1,200 rows; 20 rejected (download report)" (T29) |
| A02 | Tree lazy-loads | "No offices yet — import or add" | Outside admin scope | Tree changed by another admin | Move conflict | Retry | Saved, effective date shown | Import partial (as A01) |
| A03 | Timeline skeleton | "No postings" | n/a | Posting changed meanwhile | Overlapping posting → blocked with explanation | Retry | "Transfer effective 12-11-2026; 7 open tasks stay with seat" | "Charge granted; 2 SoD conflicts need higher approval" |
| A04 | Table loading | "No table for this case type" | Approve hidden for author | Newer draft exists | Two authors → second stale | Gap/overlap errors listed by row | "Submitted for approval" / "Effective from 01-12-2026" | n/a |
| A05 | Metadata fetch | "No IdP configured" | n/a | Metadata changed at IdP | n/a | Test login failed with reason (signature, clock skew, attribute missing) | "Active" | "Active for officials; citizen IdP pending" |
| A06 | Editor loading | New package from template | n/a | Another author saved | Edit conflict | Validation report with element ids | "Validated" | "Valid with warnings (2)" |
| A07 | Diff computing | "Nothing to review" | Reviewer = author → approve disabled | Package changed after review started → review reset | Two reviewers → first decision wins | Publish failed → nothing published (atomic) | "Published v7, effective 01-12-2026; 143 in-flight cases stay on v6" | n/a |
| A08 | List loading | "No custom roles" | n/a | policy_version moved | Concurrent grant edits | Retry | "Grant changed; effective now (≤5 s caches)" | n/a |
| A09 | List loading | "No DLT templates — SMS disabled" | n/a | Template changed | n/a | Registration mismatch (header/template) explained | "Template active" | "Hindi template active; state-language template pending DLT approval" |
| A10 | List loading | "No integrations" | n/a | Endpoint changed | n/a | Connectivity test failed (egress blocked / TLS) | "Endpoint active" | "Active; status-file ingestion not yet verified" |
| A11 | List loading | "No files due for review" | n/a | Hold set meanwhile | Two reviewers on one batch | Retry | "Classified C/5" / "Transferred" | "Batch: 48 reviewed, 2 on hold" |
| U01 | Results skeleton | "No events match" | Outside tenant → nothing (no count leak) | n/a | n/a | Query too broad → narrow it | Results | n/a |
| U02 | Job progress | n/a | Export permission missing | n/a | n/a | Export failed → retry; partial file never delivered | "Export ready (12,400 rows, checksum)" | n/a (all-or-nothing) |
| U03 | Generation progress | n/a | Case outside scope | Case changed after pack generation → pack marked as-of time | n/a | Retry | Pack with checksum | "Pack generated; 1 signature LTV refresh pending" |
| I01 | Skeleton | "No workers connected" | Other tenants' topics never shown | n/a | n/a | Retry | n/a | Some SDK versions unreported |
| I02 | Scenario setup | "No scenarios" | Production tenant blocked | n/a | n/a | Simulator error | "Scenario passed" | "Passed 9 of 10 checks" |
| I03 | List loading | "No principals" | Needs PER-5 approval | n/a | n/a | Retry | Secret shown once | n/a |

### X.4.8 Operator journeys

Operator principle (Part VII §8.1): every operator screen shows **what is known, what is pending and what is uncertain**. "Job failed" is never the only information when money may have moved. Every operator action needs a reason, is audited, and shows its consequences before it is confirmed.

#### OJ-01 Incident workbench triage

| # | Actor | Screen | Action | System response / guard | Audit | AC | Tests |
|---|---|---|---|---|---|---|---|
| 1 | System | O01 | A job's retries run out (VI.1 rule 7) or a delivery is parked (VI.2.3) | Incident created with error class (retryable / business / config / unknown), tenant, case, pack | `INCIDENT_OPENED` | — | T08 |
| 2 | PER-6 | O02 | Filters by tenant and pack, sorts by age and value; claims | Claim under the incident's version; K2 per tenant | — | AC-09 | T14 |
| 3 | PER-6 | O03 | Reads error (sanitised, R28), attempt history, lock version, related external-effect state | If the related task is on an `effect=irreversible` topic, "Retry" is replaced by "Open reconciliation" | — | AC-03 | T02 |
| 4 | PER-6 | O03 | Chooses an action (OJ-04) or links to reconciliation (OJ-02) | Consequence preview | `INCIDENT_RETRIED` / `INCIDENT_RESOLVED` | — | — |
| 5 | PER-6 | O02 | Closes with a note; repeated error classes are tagged for a problem record | — | `INCIDENT_RESOLVED` | — | — |

#### OJ-02 Reconciliation queue: `UNKNOWN_OUTCOME` (example: P1 payment, J-P1-E04)

| # | Actor | Screen | Action | System response / guard | Audit | GOV | AC | Tests |
|---|---|---|---|---|---|---|---|---|
| 1 | System | O04 | Worker reports `outcomeUnknown` (or irreversible-topic lease expires) | Item opened with operationId, attemptId, request fingerprint, amount, capability class (A-2), SLA for resolution (e.g. 1 working day for money) | `EXT_OP_UNKNOWN`, `RECON_ITEM_OPENED` | G-PFMS | AC-03 | T02, T36 |
| 2 | System | O05 | Automatic lookup for class (a)/(b): next PFMS/IFMIS status file or gateway status query by operationId | Evidence attached; if conclusive, item auto-resolved by the system principal with evidence | `RECON_ITEM_RESOLVED` (auto) | G-PFMS, G-PG | AC-03, AC-06 | T27, T47 |
| 3 | PER-6 | O04 | Claims an item that stayed open | SoD: resolver ≠ initiator/checker/approver of that case (K5) | `RECON_ITEM_CLAIMED` | — | AC-03 | T17 |
| 4 | PER-6 | O05 | Gathers evidence: status file line, bank statement entry, treasury reply; attaches | Evidence stored with checksum | `DOCUMENT_UPLOADED` | — | AC-03 | — |
| 5a | PER-6 | O05 | **Confirm success with result** (reference, UTR, date) | Task completes with the supplied result; case → ACKNOWLEDGED → RECONCILED; four-eyes above a configured amount (second officer approves) | `RECON_ITEM_RESOLVED` (confirm) | — | AC-03 | T02, T27 |
| 5b | PER-6 | O05 | **Retry with the same operationId** (only class (a), or class (b) after a negative lookup) | Task back to AVAILABLE with the same operationId (VI.3); downstream dedup protects | `RECON_ITEM_RESOLVED` (retry) | — | AC-03 | T36 |
| 5c | PER-6 | O05 | **Mark failed** (evidence of non-payment required) | Task → FAILED; model routes (e.g. back to initiator); any new payment needs a new business action and new operationId (A-2) | `RECON_ITEM_RESOLVED` (failed) | — | AC-03 | T27 |
| 6 | System | S11 | Timeline shows the uncertainty period and how it was resolved | — | — | — | AC-01 | T30 |

#### OJ-03 Reconciliation queue: `LATE_RESULT_AFTER_DELETE` (VI.5)

| # | Actor | Screen | Action | System response / guard | Audit | AC | Tests |
|---|---|---|---|---|---|---|---|
| 1 | PER-6 | O08 | Deletes an instance (reason) while a payment task is LOCKED | Task → `CANCEL_REQUESTED`, tombstone (operationId, lock_version, expires_at = lease end + 30 days); internal jobs dropped quietly | `INSTANCE_DELETED` (open operationIds) | AC-03 | T11 |
| 2 | Worker | — | Completes later, reporting a successful payment | `410 GONE_INSTANCE_DELETED`; result audited; reconciliation item `LATE_RESULT_AFTER_DELETE` because an effect happened; the instance is **not** revived | `LATE_RESULT_AFTER_DELETE`, `RECON_ITEM_OPENED` | AC-03 | T39 |
| 2′ | System | — | Lease expires with no cancel ack | `UNKNOWN_OUTCOME` item | `EXT_OP_UNKNOWN` | AC-03 | T11 |
| 3 | PER-6 | O05 | Sees tombstone, deletion reason and actor, worker result, and payment evidence | Item cannot be erased while open (VI.5.1) | — | — | T30 |
| 4 | PER-6 | O05 | Resolves: "Effect confirmed — recovery/adjustment case raised" (links a new P1 case for recovery or accounting adjustment) or "No effect" with evidence | Resolution recorded; audit stays visible after the instance left the UI | `RECON_ITEM_RESOLVED` | AC-03 | T39 |

#### OJ-04 Retry, cancel and modify with guarded permissions

| Action | Permission | Pre-conditions shown in the preview (O03/O08) | Blocked when | Effect | Audit | Tests |
|---|---|---|---|---|---|---|
| Retry job | `RETRY_INCIDENT` | Attempts, last error, whether the job has external effects | Topic `effect=irreversible` with possible effect → reconciliation instead | New attempt; same operationId | `INCIDENT_RETRIED` | T02, T03 |
| Bulk retry | `RETRY_INCIDENT` + admin (R26) | Count by error class; irreversible items excluded automatically | > configured batch size without second approval | One root per transaction (VI.1 rule 3); partial-success summary | `INCIDENT_RETRIED` ×n | T10, T24 |
| Modify (move token / skip activity) | `MODIFY_INSTANCE` | Tokens to be cancelled, open external tasks and their `onCancel` policy (A-4), approvals that would be bypassed | It would skip an approval or SoD step (IX.5 rule 6: no bypass paths) | `INSTANCE_MODIFIED` with before/after | `INSTANCE_MODIFIED` | T10, T21 |
| Business cancel | `CANCEL_INSTANCE` | Compensation path, open external tasks → `CANCEL_REQUESTED` | Case already in EXECUTION with `finish-allowed` activity → waits | Model cancel path | `INSTANCE_CANCELLED` | T10, T11 |
| Technical delete | `DELETE_INSTANCE` (separate from cancel) | Same as cancel + tombstones + 30-day reconciliation retention | Open reconciliation item on the instance | Runtime rows removed after cancel requests | `INSTANCE_DELETED` | T11, T39 |
| Erase (privacy) | `ERASE_DATA` | Copies to scrub (runtime, history, outbox, inbox, object store); holds | Legal hold, CSMOP category A record, open reconciliation item | Payload scrubbed, audit metadata kept | `DATA_ERASED` | T30 |
| Release lease on behalf of a dead worker | `RETRY_INCIDENT` | Last heartbeat, lease end (DB clock, A-1) | Lease still valid | Wait or release → lock_version+1 | `INCIDENT_RESOLVED` | T03, T04 |

#### OJ-05 DLT / notification failure view

| # | Actor | Screen | Action | System response / guard | Audit | GOV | Tests |
|---|---|---|---|---|---|---|---|
| 1 | System | — | SMS rejected (template mismatch, header suffix wrong, URL not whitelisted, chain/traceability failure) or e-mail bounce | Delivery report ingested via inbox; failure classified | `NOTIFICATION_FAILED` (reason) | G-DLT, G-MAIL | TP-05 |
| 2 | PER-6 | O06 | Groups failures by reason and template | Personal numbers masked (K11) | — | — | — |
| 3 | PER-5 | A09 | Fixes the template registration (outside Sangam on the DLT portal) and records the new template id | Template ↔ header 1:1 validated; variables typed | `DLT_TEMPLATE_REGISTERED` | G-DLT | TP-05 |
| 4 | PER-6 | O06 | Resends affected messages | Same message key → duplicate control; messages older than the relevance window are not resent (e.g. OTPs) | `NOTIFICATION_SENT` | G-DLT | TP-05 |
| 5 | PER-6 | O06 | Where SMS cannot be fixed quickly, switches channel (e-mail / in-app) for mandatory notices | Government Message category notices must reach the user through some channel; the case timeline records the channel used | `NOTIFICATION_SENT` | G-MAIL | TP-05 |

Business rule: a notification failure **never** rolls back or blocks the business step it reports on (partial success), but an SLA-relevant notice (e.g. clarification sought, P4) records whether the applicant was actually informed, because the SLA pause rule may depend on it.

#### OJ-06 CERT-In incident report within 6 hours (R51)

| # | Time | Actor | Screen | Action | System response / guard | Audit | GOV | Tests |
|---|---|---|---|---|---|---|---|---|
| 1 | T0 | System / PER-6 | O01 → O07 | Security signal (SIEM rule, burst of cross-tenant `AUTH_DENIED`, audit-chain integrity failure, abnormal export volume) or a human report | Incident opened with **noticed_at** fixed from the NTP-synced clock (NIC/NPL); 6 h clock starts; edits to noticed_at only by correction with reason | `SEC_INCIDENT_OPENED` | G-CERT | TP-04 |
| 2 | T0+15 min | PER-6 (PoC) | O07 | Classifies against the 20 Annexure I types (e.g. "Attacks on Application such as E-Governance", data breach, data leak); marks personal-data involvement | If personal data → DPDP flag starts the 72 h Board-report workflow (R53) | `SEC_INCIDENT_CLASSIFIED` | G-CERT, G-DPDP | TP-04 |
| 3 | T0+30 min | PER-6 | A08, I03, I01 | Containment: revoke principals/grants, pause topics, block exports for the tenant | Revocation effective ≤5 s (VI.4 rule 5); each action audited and listed in the incident | `GRANT_CHANGED`, `AUTH_DENIED` | — | T15, T13 |
| 4 | T0+1 h | System | O07 | Evidence bundle: logs (≥180 days, stored in India), audit extracts, affected tenants and cases, hashes; legal hold on all of it | Bundle checksum recorded; export bounded and logged | `LEGAL_HOLD_SET`, `AUDIT_EXPORTED` | G-CERT | T30 |
| 5 | T0+2/4/5 h | System | S13, SMS, e-mail | Reminders to PoC, backup PoC and CISO; at T0+5 h escalation to the department head | Government Message SMS + e-mail | `NOTIFICATION_SENT` | G-DLT, G-MAIL | TP-04 |
| 6 | ≤ T0+6 h | PER-6 (PoC) | O07 | Generates the report from incident fields (CERT-In format **to verify**), reviews, submits through the official CERT-In channel outside Sangam, records submission time and reference | Overdue state if not recorded by T0+6 h (stays red; never auto-closed) | `SEC_REPORT_SUBMITTED` | G-CERT | TP-04 |
| 7 | ≤ T0+72 h | PER-6 + DPO | O07 | If personal data: notice to affected data principals and Board report | Template notices in user language | `DPDP_BREACH_REPORTED` | G-DPDP | TP-09 |
| 8 | Later | PER-6 | O07 | Follow-up updates to CERT-In, root cause, closure; links to problem records and release changes (R52 audit pack) | Closure requires RCA + lessons; record retained | `SEC_INCIDENT_CLOSED` | G-CERT | TP-04 |

#### OJ-07 Back-pressure and parked outbox repair

| # | Actor | Screen | Action | System response / guard | Audit | Tests |
|---|---|---|---|---|---|---|
| 1 | System | O01 | Outbox at 50% → alert; drain time shown (A-5) | — | — | T25 |
| 2 | System | S06, C02 | At 80% → new starts and inbound events `503 BACKPRESSURE`; drafts still save; approvals and completions still commit (CX-7) | — | — | T25 |
| 3 | PER-6 | O09 | Finds the parked ordered delivery blocking a root (poison event) | Shows root, destination, attempts, last error | — | T08 |
| 4 | PER-6 | O09 | Requeues after fixing the destination, or skips with reason | Audited repair; skip creates a gap record | `OUTBOX_REQUEUED` / `OUTBOX_SKIPPED` | T08 |
| 5 | System | O01 | Backlog drains; thresholds clear; submissions reopen | Recovery time measured | — | T25 |

#### OJ-08 Air-gapped installation on a State-DC-like VM (AC-11)

| # | Actor | Action | System response / guard | Tests |
|---|---|---|---|---|
| 1 | PER-6 | Verifies the offline bundle checksum and signature, SBOM | Bundle contains images, offline docs (incl. API docs for PER-9), Hindi/English UI packs, fonts | T50 |
| 2 | PER-6 | Installs on VM/Kubernetes with chosen DB (Oracle / SQL Server / PostgreSQL) | No outbound calls by default (R56); start-up checks (e.g. SQL Server RCSI, VI.1.6) | T50, AC-07 |
| 3 | PER-6 | Configures NTP to NIC/NPL (samay1/samay2.nic.in or internal relay), SIEM export, log retention ≥180 days | Start-up warning if NTP not configured | TP-04 |
| 4 | PER-6 | Configures IdP reachability: Parichay requires an allowed route or an in-network state SSO | Documented exception to "no outbound calls" | TP-10 |
| 5 | PER-6 | Runs the bundled smoke suite (synthetic tenant, one case per pack with simulated externals) | All four happy paths complete | T50, T32 |

#### OJ-09 Restore after external effects (drill)

| # | Actor | Action | System response / guard | Tests |
|---|---|---|---|---|
| 1 | PER-6 | Restores DB to an earlier backup while PFMS/gateway already processed later operations | OPERATION_LEDGER and inbox dedup (VI.2.6) prevent duplicate business starts and effects | T27, T35 |
| 2 | System | Re-sent or re-ingested status files match existing operationIds | Cases move forward without new payments | T27 |
| 3 | PER-6 | Reviews O04 for items where restored state and downstream evidence disagree | Reconciled through OJ-02 | T27 |

### X.4.9 Admin journeys

#### AJ-01 Onboarding a department (tenant)

| # | Actor | Screen | Action | System response / guard | Audit | GOV | AC | Tests |
|---|---|---|---|---|---|---|---|---|
| 1 | Platform admin | A01 | Creates tenant: department name (Hindi/English/state language), hosting profile (State DC / NIC / MeitY-empanelled cloud), DB, quotas | Data residency India (R56); quotas enforced (T24) | `TENANT_CREATED` | G-LANG | AC-07, AC-09 | T24, T29 |
| 2 | Platform admin | A01 | Appoints **two** department admins (PER-5) | Two-person rule for later configuration approval | `GRANT_CHANGED` | — | — | — |
| 3 | PER-5 | A01 | Sets languages (Hindi, English + state language), business calendar (gazetted/restricted holidays, working hours), time zone IST | Calendar versioned; used by SLA timers (R31) | `TENANT_CONFIGURED` | G-LANG | AC-06, AC-10 | T22 |
| 4 | PER-5 | A02, A03 | Imports hierarchy, seats, persons and postings (CSV / HRMS extract) | Row-level validation; partial import keeps valid rows only if "commit partial" chosen; failure isolated to this tenant | `SEAT_CREATED` ×n, `POSTING_EFFECTIVE` ×n | — | AC-04 | T29 |
| 5 | PER-5 | A05 | Configures Parichay / state SSO (AJ-05) | — | `IDP_CONFIGURED` | G-SSO | — | TP-10 |
| 6 | PER-5 | A09 | Registers DLT entity, headers, templates (AJ-07) | — | `DLT_TEMPLATE_REGISTERED` | G-DLT | — | TP-05 |
| 7 | PER-9 / PER-5 | A10, I03 | Registers integrations and worker principals (IJ-01) | — | — | G-PFMS, G-PG, G-DL, G-SIGN | AC-08 | T46 |
| 8 | PER-5 | A11 | Sets retention defaults per pack (CSMOP A/C; financial schedules), DPDP purpose tags | R61 overrides generic TTL | — | G-REC, G-DPDP | — | T30 |
| 9 | PER-5 + reviewer | A06, A07 | Publishes the four packs (AJ-06) and delegation tables (AJ-03) | — | `PACKAGE_PUBLISHED` | — | AC-01…AC-06 | T19, T20 |
| 10 | PER-5 | A01 | Go-live checklist: CERT-In-empanelled audit clearance recorded (CISO 5.19, R52), GIGW conformance matrix (R55), data-processor agreement (R53), smoke test per pack, isolation check (AU-02) | Activation blocked until every mandatory item has evidence | `TENANT_ACTIVATED` | G-CERT, G-GIGW, G-DPDP | AC-09 | T13, T14 |

#### AJ-02 Mapping seats and hierarchy

| # | Actor | Screen | Action | System response / guard | Audit | AC | Tests |
|---|---|---|---|---|---|---|---|
| 1 | PER-5 | A02 | Builds levels Ministry/Department → Directorate → Region/District → Office → Section | Level types fixed per tenant so routing rules can refer to levels (IX.5 rule 2) | `SEAT_CREATED` | AC-04 | — |
| 2 | PER-5 | A02 | Creates seats (designation + office), marks pool seats (e.g. "Accounts checkers, DDO-12") | Seat ids stable; never reused | `SEAT_CREATED` | AC-01 | — |
| 3 | PER-5 | A02 | Re-organisation: moves a section to another directorate effective 01-12-2026 | Open tasks stay with their seats; routing for **new** tasks uses the new tree from the effective date; preview lists affected open cases | `SEAT_MOVED` | AC-04 | T19 |
| 4 | PER-5 | A02 | Deactivates an abolished seat | Blocked while it has open tasks or is referenced by a published rule; history kept | — | — | T30 |

#### AJ-03 Delegation-of-powers DMN tables

| # | Actor | Screen | Action | System response / guard | Audit | AC | Tests |
|---|---|---|---|---|---|---|---|
| 1 | PER-5 | A04 | Drafts a new version (e.g. Government order raises Executive Engineer limit to ₹10,00,000 from 01-12-2026); attaches the order as authority | Draft version; hit policy, gap and overlap checks across bands and case types | — | AC-01 | T19 |
| 2 | PER-5 | A04 | Adds test cases (₹4,99,999.99 / ₹5,00,000.00 / ₹10,00,000.01) and runs them | Decimal boundaries exact (T18) | — | AC-01 | T18 |
| 3 | PER-5 | A04 | Views impact: pending tasks whose routing would change at action time | Because power is checked at the time of action (IX.5 rule 3), pending tasks re-evaluate when acted on after the effective date | — | AC-01 | T19 |
| 4 | Competent authority (finance) | A07 | Reviews diff and authority document; approves | Approver ≠ author (K5) | `PACKAGE_APPROVED` | AC-01 | T17 |
| 5 | System | — | Table becomes effective at the effective date; earlier decisions keep their recorded version | `RULE_EVALUATED` events cite the version | `PACKAGE_PUBLISHED` | AC-01 | T19 |

#### AJ-04 Transfers, additional charge, leave and vacancy

| # | Actor | Screen | Action | System response / guard | Audit | AC | Tests |
|---|---|---|---|---|---|---|---|
| 1 | PER-5 | A03 | Records transfer order: person X relieved from seat A on 12-11-2026 (afternoon), joins seat B | Preview: X's open claims on seat A (released at relieving time), drafts X initiated (stay with seat A), cases where X acted (SoD by person still applies at seat B) | `POSTING_EFFECTIVE` | AC-01, AC-04 | T15, T17 |
| 2 | PER-5 | A03 | Grants additional charge of seat A to person Y, 12–30 Nov | SoD preview: cases on seat A where Y already acted are listed and will be routed past Y | `ADDITIONAL_CHARGE_GRANTED` | AC-04 | T17 |
| 3 | PER-4 (Y) | S02, S03 | Sees "Acting for seat A" section; acts | Actions labelled "acted for" in audit and outputs | `ACTED_FOR` | AC-04 | T17 |
| 4 | System | — | Charge ends 30 Nov 23:59 IST | Open forms → CX-1; tasks back to seat A's new holder or escalation | `DELEGATION_EXPIRED` | AC-04 | T15 |
| 5 | PER-1 → controlling officer | S14 → A03 | Leave substitute request and approval | Requester cannot approve; date range enforced | `DELEGATION_GRANTED` | AC-04 | T17 |
| 6 | System | — | Seat vacant (no holder, no charge) | No-candidate fallback to the escalation seat; vacancy shown on O01 SLA tiles | `TASK_REASSIGNED` | AC-04 | T22 |

#### AJ-05 Parichay SSO setup

| # | Actor | Screen | Action | System response / guard | Audit | GOV | Tests |
|---|---|---|---|---|---|---|---|
| 1 | PER-5 | — | Completes NIC Parichay onboarding for the application (outside Sangam; documents are an open evidence gap, IX.7a) | — | — | G-SSO | — |
| 2 | PER-5 | A05 | Chooses protocol (SAML 2.0 or OAuth 2.0), uploads IdP metadata / client config | Metadata signature verified; certificate expiry tracked | `IDP_CONFIGURED` | G-SSO | TP-10 |
| 3 | PER-5 | A05 | Maps attributes (Parichay id / gov.in e-mail → person); seats come from Sangam postings, **not** from IdP groups | Unmapped login → "not linked" (S01 unauthorised) | — | — | TP-10 |
| 4 | PER-5 | A05 | Sets session timeout, MFA expectation, single logout | — | — | G-CERT | T32 |
| 5 | PER-5 | A05 | Test login with a test official; then activate | Activation only after a successful test | `IDP_CONFIGURED` (active) | — | TP-10 |
| 6 | PER-5 | A05 | Configures citizen IdP (Jan Parichay / MeriPehchaan) with MFA for P3/P4, and non-Aadhaar alternative | — | — | G-SSO | TP-10 |
| 7 | PER-5 | A05 | Creates two break-glass accounts (time-limited, MFA, every use alerts the CISO) | — | `GRANT_CHANGED` | G-CERT | TP-10 |

#### AJ-06 Model, form and rule publication with review and approval (R43)

| # | Actor | Screen | Action | System response / guard | Audit | AC | Tests |
|---|---|---|---|---|---|---|---|
| 1 | PER-5 / PER-9 (author) | A06 | Edits a package: BPMN model, DMN tables, form versions (Hindi/English/state labels), worker contracts, connector config, policy references | Package is the unit of versioning (R43) | — | AC-01…06 | T19 |
| 2 | Author | A06 | Validates | Unsupported executable constructs rejected (T20); hostile XML bounded (T23); every form label present in all tenant languages (blocking for Hindi/English) | — | AC-10 | T20, T23 |
| 3 | Author | A06 | Simulates with synthetic cases in the sandbox (I02) | Simulation report attached | — | — | — |
| 4 | Author | A06 | Submits for review with change note and effective date | — | `PACKAGE_SUBMITTED_FOR_REVIEW` | — | — |
| 5 | Reviewer (≠ author) | A07 | Reviews diff, validation, simulation, in-flight impact ("143 cases on v6 stay on v6") | Approve disabled for the author (K5) | `PACKAGE_APPROVED` / rejected | — | T17 |
| 6 | PER-5 | A07 | Publishes | Atomic publish; new cases bind v7 from effective date; in-flight keep v6 unless an explicit migration (out of pilot scope except the T21 subset) | `PACKAGE_PUBLISHED` | — | T19, T21 |
| 7 | PER-5 | A07 | Retires v5 after its last case closes | Retire blocked while cases are bound | `PACKAGE_RETIRED` | — | T19 |

#### AJ-07 DLT registry and notification templates

| # | Actor | Screen | Action | System response / guard | Audit | GOV | Tests |
|---|---|---|---|---|---|---|---|
| 1 | PER-5 | A09 | Records PE-ID, headers with suffix (`-G` for government messages, `-T` for OTP) | Header format validated | `DLT_TEMPLATE_REGISTERED` | G-DLT | TP-05 |
| 2 | PER-5 | A09 | Registers templates per language with typed variables and whitelisted URLs | One template per header; no free-text URL variables | `DLT_TEMPLATE_REGISTERED` | G-DLT | TP-05 |
| 3 | PER-5 | A09 | Test-sends to a test number | Delivery report shown | `NOTIFICATION_SENT` | G-DLT | TP-05 |
| 4 | PER-5 | A09 | Configures gov.in SMTP relay and e-mail templates | — | — | G-MAIL | TP-05 |

#### AJ-08 Controlled SoD exception

| # | Actor | Screen | Action | System response / guard | Audit | Tests |
|---|---|---|---|---|---|---|
| 1 | PER-2 | S08 | Blocked by `422 SOD_VIOLATION` in a small office with one eligible officer | Offers "Request exception" | `SOD_VIOLATION_BLOCKED` | T17 |
| 2 | Higher authority | A08 | Approves exception for that case only, with reason and expiry | Approver must be senior to both roles and not in the case chain | `SOD_EXCEPTION_GRANTED` | T17 |
| 3 | PER-2 | S08 | Acts; output and audit show the exception reference | — | `TASK_COMPLETED` | T17 |

### X.4.10 Auditor and integration-engineer journeys

| ID | Journey | Steps (actor → screen → action → result) | Audit | AC | Tests |
|---|---|---|---|---|---|
| AU-01 | **Reconstruct a decision** | PER-7 → U01: search by bill number → S11 timeline → each step shows seat, person, acted-for, authority basis, DMN version and inputs digest, payload hash, signature validity (LTV), operationId and downstream evidence → U03 evidence pack (PDF/A + checksum) | `AUDIT_VIEWED`, `AUDIT_EXPORTED` | AC-01, AC-04 | T30, T42 |
| AU-02 | **Isolation check** | PER-7 of tenant A → U01/U02: searches, counts and exports with filters naming tenant B ids → zero rows, counts identical to the list, no existence leak; API, worker, broker and file-reference spoofing attempts by PER-9 in sandbox are denied | `AUTH_DENIED` | AC-09 | T13, T14, T37 |
| AU-03 | **Records review, RTI and NAI transfer** | Records officer → A11: C files due for review → retain/upgrade/weed (authorised); A files at year 25 → NAI export PDF/A; RTI request linked to a file → 30-day or 48 h clock, legal hold during the request | `RECORD_REVIEWED`, `RECORD_TRANSFERRED_NAI`, `RTI_REQUEST_LINKED` | — | T30, TP-08 |
| IJ-01 | **Build and certify a worker** (e.g. PFMS payment-file worker in PHP) | PER-9 → I03: requests a principal bound to tenant + topic (PER-5 approves) → A10: declares capability class, `effect=irreversible`, exclusivity, lease → writes handler using the SDK, forwarding `operationId` downstream → I02: runs fault scenarios (lost response, duplicate callback, lease expiry, stream cut, stale lockVersion) → I01: watches leases and `STALE_LEASE` counts → promotes | `GRANT_CHANGED` | AC-03, AC-08 | T01–T04, T36, T46, T47 |
| IJ-02 | **Cross-SDK and cross-DB conformance run** | PER-9/QA → I02: runs the four pack scenarios with workers in Java, .NET, PHP, Python and Node against Oracle, SQL Server and PostgreSQL → compares decisions, amounts (decimal), dates and audit outputs | — | AC-07, AC-08 | T18, T31, T33 |

### X.4.11 Accessibility and language notes

#### X.4.11.1 Rules for every screen (G-GIGW, G-LANG)

| Area | Rule |
|---|---|
| Standard | GIGW 3.0 covers web applications and mobile apps: **WCAG 2.1 AA is the legal floor, WCAG 2.2 AA the product target** (R55); IS 17802 (RPwD rule 15); GIGW conformance matrix maintained per release for STQC |
| Keyboard | Every action reachable by keyboard; visible focus; logical order; skip links; no keyboard traps in PDF viewer, signing dialog or modeler; shortcut keys documented and remappable |
| Screen readers | Landmarks and headings per screen; tables with header associations; status changes via `aria-live`; error summary at the top linked to fields; tested with NVDA and JAWS (desktop) and TalkBack (mobile), in Hindi and English |
| Colour and status | SLA, stale and uncertain states use text + icon + colour; contrast ≥4.5:1; no information by colour alone |
| Timing | Session timeouts warn and allow extension (WCAG 2.2.1); SLA timers are business deadlines, not UI time limits |
| Language | Hindi + English everywhere, plus one state language per tenant; switch on every page; language of each output document follows OL Rules (bilingual for s.3(3) documents); `lang` attribute per block; Unicode (no legacy font encodings); Noto Sans Devanagari / state-script fonts bundled for air-gapped installs; allow 30–40% text expansion; no text in images; user-entered noting may be in Hindi or English and is stored as typed (optional BHASHINI translation hook, R54) |
| Numbers, money, dates | Indian digit grouping (₹4,75,000.00); amount in words in Hindi and English on financial outputs; dates DD-MM-YYYY with IST; working-day due dates shown as date + "working days left" |
| Errors | Plain language, says what happened, what is kept, what to do next; never codes alone; reference id for support |
| Mobile / bandwidth | Citizen and inspector screens usable at 360 px width, on 2G/3G-class links; page weight budget; offline capture for S15 |
| CAPTCHA | If used on citizen screens: accessible alternative required (GIGW); prefer MFA/OTP and rate limits |
| Documents | Output PDFs tagged (PDF/UA) with reading order and language tags; signature panel readable by screen readers |

#### X.4.11.2 Per-screen notes

| Screen | Accessibility (beyond X.4.11.1) | Language notes |
|---|---|---|
| S01 | IdP redirect announced; language switch before sign-in | Sign-in text in all three languages on one page |
| S02 | Current seat in a persistent, announced header region ("Acting as …") | Designations in Hindi and English from the seat master |
| S03 | Data grid with row/column headers; sort state announced; bulk select via keyboard; SLA icon + text | Subject lines may mix scripts; sorting uses locale collation per language |
| S04 | Field-level instructions linked by `aria-describedby`; inline rule result announced | Labels from package in all languages; field values entered in any script |
| S05 | Note paragraphs as an ordered list with author/seat/date; signed paragraphs marked as read-only to AT | Bilingual noting; Hindi typing support (Inscript / phonetic) without plugins |
| S06 | Summary as a definition list; declaration checkbox labelled | Summary rendered in the user's language with amount in words |
| S07 | Candidate list as radio group with workload described in text | Seat designations bilingual |
| S08 | Diff markers ("changed since you last saw") as text, not only highlight; decision buttons with full labels ("Return to Section Officer") | Reasons from coded list in all languages, free text as typed |
| S09 | Diff view also available as a linear "before → after" list | Field names in user's language |
| S10 | Signer-agent and OTP steps announced; OTP field with `autocomplete="one-time-code"`; time allowed shown and extendable where ESP allows | eSign consent text in user's language (as provided by ESP) |
| S11 | Timeline as list with headings per day; filter controls labelled | Event descriptions localised; data values as recorded |
| S12 | Tagged PDF; HTML alternative view for screen readers | Bilingual outputs; state-language output where the template exists |
| S13 | Unread count announced politely | Templates per language |
| S14 | Date-range pickers keyboard-accessible with typed input | Bilingual |
| S15 | Large touch targets (≥44 px), works one-handed, camera capture labelled; offline status as text | State language first for field staff |
| C01 | Simple language (reading level), big targets | All three languages; assisted-mode notice |
| C02 | Step indicator announced; save-and-resume; error summary | Help text in all languages; transliteration help for names |
| C03 | Payment status announced; no auto-redirect without notice | Fee breakup labels localised |
| C04 | Status as text ("Stage 3 of 5: Inspection") | Plain-language stage names per language |
| C05 | Appeal deadline as text with date | Certificate bilingual (+ state language) |
| C06 | Form fields labelled; SLA date as text | All languages |
| O01–O09 | Dense tables still need headers, keyboard filters and text for every status; charts have a table alternative; 6 h countdown announced at thresholds, not every second | English + Hindi UI; reasons and evidence stored as typed |
| A01–A11 | Wizards with step headings; CSV import errors as a navigable table with row numbers; DMN table editor fully keyboard-operable with cell coordinates announced | Every package/template field requires Hindi + English values before publish; state language required where the tenant enables it |
| U01–U03 | Search results and long timelines navigable by headings; exports in accessible PDF/A and CSV | Exported labels in the auditor's chosen language; data as recorded |
| I01–I03 | Standard developer-console accessibility; code samples as text | English primary, Hindi UI available |

### X.4.12 Traceability

#### X.4.12.1 Acceptance criteria → journeys → tests

| AC | Criterion (IX.8, short) | Journey steps that demonstrate it | Catalogue tests | Proposed tests |
|---|---|---|---|---|
| AC-01 | Bill: initiator → selected checker → approver by amount band; payment ack reconciles; audit shows seat, person, rule version | J-P1-H 1–17; J-P1-E05…E11; AJ-03; AU-01 | T16, T17, T19, T27 (+T14, T15, T18, T22, T33) | TP-01 |
| AC-02 | Amount/beneficiary changed after approval → re-approval | J-P1-E01, J-P1-E12, J-P1-CX2, J-P3-E03 | T16 | — |
| AC-03 | Payment success with lost response → UNKNOWN_OUTCOME → reconciled, no duplicate | J-P1-E04, J-P1-E13, J-P1-E14, OJ-02, OJ-03, OJ-09, IJ-01 | T02, T27 (+T11, T36, T39, T35) | — |
| AC-04 | File up three levels, returned two, resubmitted, approved; leave → additional charge "acted for" | J-P2-H, J-P2-E02, J-P2-E03, J-P2-E04, AJ-04 | T15, T17 (+T19) | TP-03, TP-06 |
| AC-05 | Grant ineligible after verification → rejection with reasons; partial disbursement; account failure | J-P3-H, J-P3-E01…E05, J-P3-CX5 | T11, T27 (+T16, T17, T19) | TP-07, TP-09 |
| AC-06 | Licence SLA breach escalation; fee paid but callback lost reconciled; signed certificate issued | J-P4-H, J-P4-E01, J-P4-E02, J-P4-E03, J-P4-E07, J-P4-E09 | T22, T47 (+T02, T09) | TP-03, TP-07 |
| AC-07 | Same pilot on Oracle, SQL Server, PostgreSQL, identical results | IJ-02, OJ-08 step 2, AJ-01 step 1 | T18, T31 (+T33) | — |
| AC-08 | Workers in Java, .NET, PHP, Python, Node: same lease, idempotency and error semantics | IJ-01, IJ-02, J-P1-H 12, J-P3-H 11 | T01–T04, T18 (+T36, T46) | — |
| AC-09 | No cross-tenant visibility through UI, API, list, count or export | AU-02, all S03/U01/U02 list states, J-P2-E09, AJ-01 step 10 | T13, T14 (+T37) | — |
| AC-10 | Hindi and English UI; first-time officer completes a task without help | Usability session script: J-P1-H 3–11 as PER-1/2/3 in Hindi, J-P4-H 1–7 as PER-8 in state language; X.4.11 | T32 | TP-11 |
| AC-11 | Air-gapped install from offline bundle | OJ-08 | T50 | TP-10 (IdP route) |

#### X.4.12.2 Tests → journeys (reverse map, catalogue tests used here)

| Test | Journeys |
|---|---|
| T01 | J-P1-E02, CX-4 (all packs), J-P4-E04, J-P3-H 4, IJ-01 |
| T02 | J-P1-E04, J-P2-CX5, J-P3-CX5, J-P4-E02, OJ-02, OJ-04, IJ-01 |
| T03 / T04 | OJ-04 (lease release), IJ-01 |
| T05 / T40 | J-P1-H 12, J-P3-H 11 (resource exclusivity) |
| T08 | OJ-01, OJ-07 |
| T09 | J-P1-H 13, J-P2-H 12, J-P4-H 5, J-P4-E03 |
| T10 | J-P2-E05, J-P4-E10, OJ-04 |
| T11 | J-P1-E13, J-P3-E01, OJ-03, OJ-04 |
| T13 | J-*-H step 1, J-P2-E09, AJ-01, AU-02, OJ-06 |
| T14 / T37 | J-P1-H 17, J-P1-E03, J-P3-E06, AU-02, screen inventory rule |
| T15 | CX-1 (all packs), J-P1-E05, J-P1-E06, AJ-04, OJ-06 |
| T16 | J-P1-E01, J-P1-E09, J-P1-E12, J-P2-H 9, J-P3-E03, J-P4-H 10, CX-2 |
| T17 | CX-8, J-P1-H 7/9, J-P2-E03/E04, J-P3-E04, J-P4-E06, AJ-03…AJ-08, OJ-02 |
| T18 | J-P1-H 4, J-P3-H 9, J-P4-H 3, AJ-03, IJ-02 |
| T19 | J-P1-H 3/5, J-P1-E10, J-P2-E02, J-P3-E08, CX-2, AJ-02, AJ-03, AJ-06 |
| T20 / T23 | AJ-06, J-P1-H 4 (attachments), J-P3-E07 |
| T21 | AJ-06 step 6, OJ-04 (modify) |
| T22 | J-P1-H 8, J-P4-H 5–7, J-P4-E01/E05/E06/E07, AJ-01 step 3, AJ-04 step 6 |
| T24 / T29 | AJ-01 |
| T25 | CX-7, OJ-07 |
| T27 / T35 | J-P1-E04, J-P1-E14, J-P3-E02/E03, OJ-02, OJ-09 |
| T30 | J-*-H closure steps, J-P2-E10, OJ-03, OJ-04 (erase), OJ-06, AU-01, AU-03 |
| T31 / T33 | CX-3, IJ-02 |
| T32 | AC-10 session, CX-6, AJ-05 |
| T36 | CX-5, OJ-02 5b, IJ-01 |
| T39 | J-P1-E13, OJ-03 |
| T42 | AU-01 |
| T46 / T47 | J-P1-H 12, J-P4-H 5, J-P4-E02/E03, IJ-01 |
| T49 | O01 states (metrics stale) |
| T50 | OJ-08 |

#### X.4.12.3 Proposed tests where no catalogue test exists

| ID | Test | Pass condition | Journeys |
|---|---|---|---|
| TP-01 | Concurrent claim of one pooled user task by two holders, on each Tier-1 DB | Exactly one `TASK_CLAIMED`; loser gets `409 TASK_ALREADY_CLAIMED`; no partial state | CX-3 |
| TP-02 | Browser/mobile resend after network loss at submit, approve, pay, upload | One business action per idempotency key; second response identical; mismatched payload rejected | CX-4 |
| TP-03 | Signature bound to approval hash; ESP timeout; switch ESP; DSC absent | No forward/issue without a valid signature over the approved hash; duplicate signature attempts harmless | J-P2-H 5, J-P2-E06/E07, J-P4-E09 |
| TP-04 | CERT-In 6-hour drill from a seeded security signal | Report recorded as submitted ≤6 h after noticed_at; reminders at 2/4/5 h; evidence bundle with checksums; NTP source verified | OJ-06, OJ-08 |
| TP-05 | DLT conformance: template mismatch, wrong header suffix, non-whitelisted URL, gateway down | Message blocked before send when invalid; failures visible in O06; business step not rolled back; resend deduplicated | OJ-05, AJ-07 |
| TP-06 | Out-of-hierarchy marking policy | Allowed only where the package permits; reason mandatory; skipped seats informed | J-P2-E01 |
| TP-07 | DigiLocker requester fetch and issuer push with failures | Provenance stored; push retried idempotently; certificate download unaffected | J-P3-H 3, J-P3-E07, J-P4-E08 |
| TP-08 | Retention classification and RTI clock | Category mandatory at closure; hold blocks weeding; 30-day / 48 h clocks correct on business calendar | J-P2-E10, AU-03 |
| TP-09 | DPDP notice, purpose tags, child flag, breach notices | Notice version linked to case; child records excluded from analytics; 72 h workflow triggered | J-P3-H 2, J-P3-E09, OJ-06 |
| TP-10 | Identity: Parichay SAML/OAuth login, unmapped account, break-glass, IdP route in air-gapped install | Seats only from postings; break-glass alerts; activation blocked until a test login passes | AJ-05, OJ-08 |
| TP-11 | Usability session protocol for AC-10 (≥5 first-time officers per language, ≥5 citizens incl. AT users) | Task completion without help; errors recovered; accessibility defects logged against X.4.11 | AC-10 |

### X.4.13 Assumptions and open items

| # | Item | Why it matters | Owner (role) |
|---|---|---|---|
| 1 | Error codes, audit event names and TP-01…TP-11 are proposals; they need adoption into R41/R42/R47 specs and the test catalogue (VIII.7 task 2) | Journeys depend on stable names | Lead architect |
| 2 | Capability class (A-2) per real connector is unverified: PFMS/IFMIS file dedup, gateway status query, ESP transaction lookup, DigiLocker push idempotency | Decides whether OJ-02 can auto-resolve or must be manual | Integration lead, with the department (PFMS spec is issued only to registering systems) |
| 3 | P1 approver signature (DSC/eSign) is shown as package-configurable; P2 signing is mandatory by CSMOP for dealing officers and above | Legal validity of approvals | Product owner + department |
| 4 | Deemed-approval and SLA pause rules (P4) depend on the state's time-bound service law | J-P4-E01/E05/E07 behaviour | Product owner with the G1 buyer |
| 5 | Re-sanction rule after beneficiary bank change (P3) is assumed "re-verify account, re-sanction only if the package says so" | AC-05 path | Department finance |
| 6 | State language for the pilot is not chosen (examples assume Marathi) | Fonts, templates, usability panel | Product owner (G1) |
| 7 | CERT-In report format and submission channel are not specified in the verified sources | OJ-06 step 6 | Security lead |
| 8 | Parichay reachability conflicts with the default "no outbound calls" air-gapped mode | OJ-08 step 4 | Platform lead |
| 9 | Retention category defaults for financial vouchers and licence files need the department's record retention schedule | G-REC steps | Records officer |

### X.4.14 Counts

| Category | Count | IDs |
|---|---|---|
| Pack happy paths | 4 | J-P1-H, J-P2-H, J-P3-H, J-P4-H |
| Pack exception journeys (IX.4 exceptions + related) | 43 | J-P1-E01…E14 (14), J-P2-E01…E10 (10), J-P3-E01…E09 (9), J-P4-E01…E10 (10) |
| Pack cross-cutting journeys (permission revoked, stale form, conflicting claim, network error, unknown outcome) | 20 | J-Pn-CX1…CX5 for n = 1…4 |
| Cross-cutting exception contracts | 8 | CX-1…CX-8 |
| Operator journeys | 9 | OJ-01…OJ-09 |
| Admin journeys | 8 | AJ-01…AJ-08 |
| Auditor journeys | 3 | AU-01…AU-03 |
| Integration-engineer journeys | 2 | IJ-01, IJ-02 |
| **Journeys total** (happy + exception + pack CX + operator + admin + auditor + integration) | **89** | (CX-1…CX-8 contracts counted separately) |
| **Screens** | **47** | S01–S15, C01–C06, O01–O09, A01–A11, U01–U03, I01–I03 |
| Personas | 9 | PER-1…PER-9 |
| Proposed tests | 11 | TP-01…TP-11 |

### X.4a Adoption of the X.4 proposals and its open items

| Item | Decision |
|---|---|
| Proposed tests **TP-01…TP-11** | **ADOPTED** as **T78–T88**, in the same order (TP-01 → T78 … TP-11 → T88). The authoritative test catalogue is now **T01–T88** |
| Proposed error codes (e.g. `409 FORM_VERSION_STALE`, `409 TASK_ALREADY_CLAIMED`, `422 SOD_VIOLATION`) and audit event names | **ADOPTED as the initial catalogue.** They are to be folded into the R41 (X.2.2), R42 (X.2.3) and R47 (X.2.5) specs during the Day-3 sweep. Until then, X.4 is the source |
| Real-connector support for duplicate rejection / status lookup (PFMS, gateway, eSign, DigiLocker) | **Covered by A-8.** Each adapter declares its capability class; until it is verified, the class defaults to **(c)**: the reconciliation item needs an officer. Listed in **X.6 Parked** |
| P4 deemed-approval and SLA-pause rules (state time-bound service laws) | **Per-state DMN configuration** in the P4 pack. The pilot uses a documented default and the state-specific rules are **parked** (X.6) |
| Pilot state language | **Configurable** (R54). The examples use Marathi only as an illustration. No dependency (ADR-D-13) |
| CERT-In report format and submission channel | The incident workflow exports a structured report. The exact CERT-In format is **parked** (X.6) |
| Parichay login vs the air-gapped default (no outbound calls) | **Resolved:** the air-gapped default stays. An online deployment may open **one explicit egress allow-list entry** for its IdP (R27, R56). Air-gapped deployments use a local OIDC/SAML IdP (e.g. Keycloak, Apache-2.0) federated with the department directory |

## X.5 Final consistency sweep (2026-11-05)

### X.5.1 Resolution of findings CF-10…CF-16 (from X.1)

| # | Finding | Resolution (authoritative) |
|---|---|---|
| CF-10 | R50–R62 have no tests; IX.3 "Hindi/English UI" has no spec reference | Test mapping:<br>• R50 → identity-provider test (**T87**)<br>• R51, R52 → CERT-In 6-hour drill (**T81**) + T49<br>• R53 → DPDP test (**T86**)<br>• R54 → T32, **T88**<br>• R55 → T32, **T88**<br>• R56 → T50<br>• R57 → signing-bound-to-approval (**T80**)<br>• R58 → T02, T27, T47, **T89**<br>• R59 → DigiLocker (**T84**)<br>• R60 → DLT conformance (**T82**)<br>• R61 → retention/RTI clocks (**T85**), T30, X.2.3 tests<br>• R62 → release-evidence artefact (local-content dossier), not an automated test<br>T78–T88 are the X.4 proposals TP-01…TP-11 (X.4a), identified here by their scenario names. **New T89:** conformance of every government adapter against its simulator (ADR-D-13), for PFMS, IFMS, Parichay, eSign, DigiLocker and DLT. **Test catalogue = T01–T89.** IX.3 "Hindi/English UI" → **R54** |
| CF-11 | R17 PR-gate policy unresolved | **Every PR:** PostgreSQL, plus each affected Tier-1 DB when the change touches dialect-specific SQL, mappings or migrations.<br>**Nightly:** the full Tier-1 matrix (PostgreSQL, Oracle, SQL Server).<br>**Weekly:** Tier 2.<br>**Every release (from Stage C):** the full Tier-1 matrix is mandatory (VIII.9 condition 2).<br>This supersedes "all Tier-1 DBs on every PR" in I §16 / II §19 and settles V.4b's open "PostgreSQL-only PR gating" |
| CF-12 | R31 stage vs the pilot core | Already resolved by **CF-9: R31 → Stage C** |
| CF-13 | R34 MoSCoW status | **Must** = the BPMN gaps (§3.1 / II §3.1) needed by packs P1–P4 or by common Camunda 7 / Flowable model import. Each such gap is **reproduced on the D-1 base in Stage B** before it is implemented in Stage C. The remaining gaps keep II §3.2 priorities (Should/Could, Stage E). The PDF's "seven gaps in Phase 1" (N4) maps to this rule |
| CF-14 | The index points to specs that did not exist | **Resolved:** X.2 now specifies R39, R41, R42, R43 and R47. Stage B proves the X.2.2 idempotency contract on both engines |
| CF-15 | A-6 status contradictory | **Resolved:** A-6 is closed as SPECIFIED by X.2.1 (X.2a). Partitioned executors are off by default |
| CF-16 | Stale summaries in Parts I–II | Parts I–IV stay verbatim (archive), so the stale statements are listed in the **superseded-statements register (X.5.3)** instead of being edited in place. The Baseline front section points readers to X.1 / X.2 / Part VI first |
| CF-9a | Requirements placed in more than one stage in VIII.9 | **Rule:** the **first** stage listed is where the requirement is **built**; later stages **broaden validation** (scale, failure, all DBs). This applies to R19, R21, R26, R42 and R48 |

### X.5.2 Error-code and audit-event catalogue: normative

The error codes and audit event names first proposed in X.4 (and adopted in X.4a) are **normative** from this point:
- the API error codes belong to **R41 (X.2.2)** and the API contract;
- the form errors (e.g. `409 FORM_VERSION_STALE`) belong to **R47 (X.2.5)**;
- the claim and segregation errors (`409 TASK_ALREADY_CLAIMED`, `422 SOD_VIOLATION`) belong to **R19 / VI.4 and IX.5**;
- the audit event names belong to **R42 (X.2.3)**'s minimum audit event list.

**X.4 is the single source of these names until they are published in the OpenAPI/AsyncAPI contracts (R16).** A name change after this point needs change control.

### X.5.3 Superseded-statements register (Parts I–IV archive)

| Archive location | Superseded statement | Authoritative replacement |
|---|---|---|
| I §2, I §14.6, II §14 | "≥1,000 PI/s"; "99.95 % availability, RPO 0" | II §14.3 per-workflow targets (R22); **ADR-D-12**: RPO ≤ 5 min, RTO ≤ 1 h, 99.5 % |
| I §5.1, I §5.3, II §19 R18 | 8 SDKs; "Tier 1 includes Go" | **ADR-D-4 (IX.6)** |
| I §5.2, II §19 R9 | gRPC streaming first; "un-lock on stream close" | **ADR-D-5** (REST first) + **VI.3** + A-1 + CF-3 |
| I §5.4, II §19 R6 | "WASM + GraalJS isolates" in-engine | **ADR-D-8** (out-of-process script worker); LIC-1; corrected X-7 |
| I §5.6, II §19 R17 | All DBs on every PR; 8-database list | **R17 tiers** + **CF-11**; LIC-2…LIC-5 (driver bundling) |
| I §3.4, V X-8 | Drools "highest conformance" | jDMN 3391/3391 highest; Drools default for features/support (X-8) |
| I §4.3 item 4 | Partition routing | **X.2.1 (R39)**: off by default |
| I §4.5 / II §19 R4 | "commit-visibility guard" | **VI.2** |
| I §4.6 | History levels may suppress everything | **X.2.3**: audit cannot be switched off |
| I §7 (ABAC everywhere) / II R19 | ABAC in lists via OPA/Cedar | **VI.4** + A-3 |
| II §19 R10 | Global "null → incident" | **Part III §9 E18 + Part VII §7 R10 ("Revise") + X.1 R10:** strict null handling only for Boolean routing decisions and mandatory outputs; valid optional nulls remain valid |
| II §19 R23 | "ends a job silently" | **VI.5** + A-4 |
| II §19 R35 | Exclusivity checked at completion | **VI.6** + A-7 |
| I §11 Phases 0–4 | Delivery timeline | **ADR-D-2 (VIII.9)** Stages A–E |
| I §3.3 import, Phase 4 | Migration as one item | **R48 / E20**: import, API compatibility and live-state migration are distinct |
| I §3.3b | Automatic CMMN → BPMN conversion | **R45 / E16**: supported subset + run-off |
| II §4, II summary | "Empty market slot" as fact; success percentages | **X-1** (hypothesis), **X-18 / D-10** (gates G1–G8) |
| II §4 n8n row | n8n on MySQL | **X-13**: PostgreSQL/SQLite since n8n 2.0 |
| II §11, §12, §20 | CVE-2026-91197 as fact; CVE-2025-0982 / CVE-2025-6514 descriptions | **X-14 … X-16** |
| VI.7 D-2 "RECOMMENDED"; III §15.2 stage labels | Stage definitions | **VIII.9** (CF-1) |
| IX.9 G1 as a Stage A exit | Named buyer required before development | **ADR-D-13**: G1 is a go-to-market gate |
| IX.7c request route | RQ-1…RQ-6 as a dependency | **ADR-D-13**: parked (X.6 PK-14) |

## X.6 Parked items (ADR-D-13: none of these blocks development)

Each item records: what is missing, the public substitute in use, the adapter or configuration that will absorb it, and who could supply it later. The search continues for every item; anything found moves into configuration.

| ID | Missing detail | Public substitute in use | Absorbed by | Possible later source |
|---|---|---|---|---|
| PK-01 | G.S.R. number and IS 17802 wording of the RPwD (Amendment) Rules, 10 May 2023 | GIGW 3.0 WCAG 2.1 AA (verified) + IS 17802 as target | R55 conformance matrix (citation only) | DEPwD site / e-Gazette (retry download) |
| PK-02 | Data Protection Board member appointments | Board established (G.S.R. 844/845(E)) | None needed | dpb.gov.in / e-Gazette |
| PK-03 | NIC rule on hosting location / DR for applications | MeitY SDC guidelines + MeitY cloud empanelment + GIGW backups | R56, ADR-D-12 | NIC hosting division |
| PK-04 | Parichay application onboarding metadata (SAML metadata / OAuth client registration) | Published protocols (REST, SAML 2.0, OAuth 2.0); JanParichay public steps | R50 identity adapter configuration | NIC service desk, at adoption |
| PK-05 | Exact PFMS file/API schemas; other states' IFMS specs | PFMS FAQ process + Odisha JIT open-source reference + ISO 20022 direction | R58 adapter mapping files + simulator | PFMS / state treasury, at adoption |
| PK-06 | NIC SMS console onboarding (behind login) | Mobile Seva (MeitY/C-DAC) public route + TRAI DLT rules | R60 notification adapter | NIC / Mobile Seva, at adoption |
| PK-07 | MeitY method for computing local content of general software | Cyber-security order 2019 method (cost-based, ≤20 % royalty, Indian IP) + PPP-MII 2024 thresholds | R62 local-content dossier | MeitY / DPIIT |
| PK-08 | Real connector capability (duplicate rejection / status lookup) for PFMS, gateway, eSign, DigiLocker | Default capability class (c) (A-8) | Adapter capability declaration | Provider documentation at adoption |
| PK-09 | State-specific deemed-approval / SLA-pause rules (time-bound service laws) for P4 | Documented default rules | P4 per-state DMN tables | State laws (public; to be collected per state) |
| PK-10 | CERT-In incident report format and submission channel | Structured incident export (X.4 operator journey) | Incident-report template | CERT-In site / empanelled auditor |
| PK-11 | Whether citizen-facing forms may carry the bpmn.io watermark | Watermark kept visible | Stage C UI decision (form renderer choice) | Product / procurement review |
| PK-12 | FSL legal review for Liquibase 5.x | Liquibase 4.33.0 pinned (Apache 2.0); Flyway OSS as fallback | D-7, enforcer ban | Legal counsel |
| PK-13 | Licence of `angular-data-depend` (legacy webapps); latest versions of Flyway, Chicory and jDMN | Legacy webapps not shipped; versions pinned at release tag | X.3 regeneration at the D-1 release tag | Package registries |
| PK-14 | RQ-1…RQ-6 official requests (IX.7c) | All of the above | — | Used only at go-to-market |

# Part XI — Stage B: Technical Proof (D-1 Spike) Plan (approved 2026-11-05)

> **Purpose:** close **ADR-D-1 (base engine)** with measured evidence, and produce the **G2** (engine choice) and **G3** (dependency/licence route) gate evidence.
>
> **Status:** PLAN APPROVED by the owner ("Go Ahead I Aproved", 2026-11-05). Delivery: not started.
>
> **Governing rules:**
> - VI.7 D-1 (weighted procedure);
> - M10 (authorization is tested on both bases);
> - CF-13 (BPMN gaps are reproduced before they are implemented);
> - ADR-D-13 (no government dependency: simulators only).

## XI.1 Candidates (pinned stable releases)

| Candidate | Release used | Why this tag | Note |
|---|---|---|---|
| **Flowable** | **8.0.0** (27 Feb 2026, "Latest" on GitHub releases) | Latest stable. The local clone is 8.1.0-SNAPSHOT and serves as reading reference only | Check its Spring Boot / JDK 25 compatibility in E10 |
| **Operaton** | **2.2.0** (23 Oct 2026, "Latest" on GitHub releases) | Latest stable. The local clone is 2.3.0-SNAPSHOT and serves as reading reference only | README states JDK 17–25 tested (Part I §1) |

Each candidate is used **with as few engine patches as possible**. Every patch needed is counted and recorded in the patch ledger (VI.7 D-1 rule).

## XI.2 Spike architecture: one product layer, two engine adapters

```
                ┌──────────── Sangam Spike Product API (REST, ADR-D-5) ─────────────┐
                │ start/correlate · task list/claim/complete · worker fetch/lock/   │
                │ heartbeat/complete/fail/unknown · cancel/delete · audit timeline   │
                └────────────────────────────┬───────────────────────────────────────┘
   Product-layer components (built ONCE, engine-neutral, tested on BOTH engines):
   • AuthorizationFilter + AUTH_EFFECTIVE (VI.4, A-3)   • OperationLedger + lease fencing (VI.3, A-1/A-2/A-8)
   • IdempotencyStore, L6 in the same tx (X.2.2)         • Outbox/Inbox + relay (VI.2)
   • Tombstones / reconciliation queue (VI.5)            • Audit hash chain (X.2.3, minimal)
                ┌────────────────────────────┴───────────────────────────────┐
         EngineAdapter: Flowable 8.0.0                   EngineAdapter: Operaton 2.2.0
                └──── shared DB transaction (Spring tx) per command ────────┘
   Simulators (ADR-D-13): PFMS SFTP+status files · Odisha-style IFMS JIT · eSign v2.1 ESP ·
                          DigiLocker Push · Keycloak IdP (Parichay stand-in) · DLT/SMS stub
```

**Key feasibility questions the spike must answer for each engine:**
- (Q1) Can product tables (idempotency, outbox, ledger, audit) commit **in the same transaction** as engine state?
- (Q2) Can list/count queries be filtered **in SQL** by the VI.4 predicate without patching the engine?
- (Q3) Can the external-task / external-worker API support the VI.3 lease states (OFFERED/LOCKED, fencing, extend, release, unknown outcome)?
- (Q4) How many BPMN gaps (CF-13) need engine patches?

## XI.3 Corpus (the same models on both engines)

| ID | Workflow | Content | Exercises |
|---|---|---|---|
| **WF-1** | P1 bill/payment (IFMS-style) | Initiator → checker selected from eligible seats → approver by amount band (**DMN delegation-of-powers table**) → payment external task → PFMS/IFMS simulator, including a lost response | VI.3, VI.4, X.2.2, X.2.4 (approval bound to payload hash), A-8; AC-01…AC-03 |
| **WF-2** | P2 file movement | Forward up 3 levels, return 2, resubmit; additional-charge holder acts; two parallel comment branches join | VI.1 (join concurrency), seat model IX.5; AC-04 |
| **WF-3** | P4 licence + case | Application → scrutiny loop → inspection; **SLA timer escalation** (DST boundary); approval → eSign + DigiLocker simulators. Case variant in **CMMN** on both engines (Flowable CMMN vs Operaton CMMN) | R31/T22, R57, R59, CMMN parity; AC-06 |
| **GAP set** | BPMN gap reproductions | Link events; signal/message/compensation end; message intermediate throw; process-level conditional start; compensation event sub-process; ad-hoc sub-process; I/O mapping on any activity | CF-13 / R34 |

## XI.4 Experiments and pass criteria

| ID | Experiment | Pass criterion | Tests | Score bucket |
|---|---|---|---|---|
| **E1** | Corpus coverage without engine patches | WF-1…WF-3 run end to end. Count the elements and features that need patches | AC-01…AC-06 (subset) | Coverage 30 % |
| **E2** | Authorization equivalence | `list == {r : allow(r)}` and the count matches, for random policies; deny wins; no post-filtering; 10k tasks × 300 groups p95 list < 200 ms on PostgreSQL | T13–T17, T37, T38 (reduced scale) | Failure tests 20 % + patches 25 % |
| **E3** | Worker lease + operationId + unknown outcome (REST) | A stale worker is rejected; no redispatch of acknowledged work; one completion after a 2× lease network cut; the same operationId on retry; class-(c) default goes to reconciliation | T02, T03, T04, T36 | Failure tests |
| **E4** | Idempotency atomic with engine state | A duplicate start returns the same instance; conflicting reuse gives 422; a crash between engine commit and response is safe | T01, T55–T60 (subset) | Failure tests |
| **E5** | Outbox / inbox | A late low-sequence commit is still delivered; a crash after publish is safe at the consumer; poison policy works; early message buffered; restore scenario | T06–T09, T35 | Failure tests |
| **E6** | Concurrency on PostgreSQL / Oracle / SQL Server | 1,000 concurrent correlations and completions on one root → 0 deadlocks, 0 lost updates; record OLE/retry counts | T10, T33, T34 | Failure tests |
| **E7** | Cancel/delete with external work in flight | Tombstone, late-result audit, reconciliation item, the instance stays deleted | T11, T39 | Failure tests |
| **E8** | Timers / DST | `R/PT1H` across a DST fold and gap fires exactly once per interval | T22 | Failure tests |
| **E9** | Benchmark | WF-R1 and WF-R5 on PostgreSQL with audit and authorization on; completed instances/s, p99, queue age. **Functional-scale on the dev machine (8 vCPU/32 GB); reference scale (16+16 vCPU) only if hardware is available** | T31 | Benchmark 10 % |
| **E10** | Platform fit | Runs on JDK 25; minor-version upgrade with active instances; licence inventory regenerated at the tag (LIC-11); GraalJS/ojdbc excluded (LIC-1/2) | T28 (reduced), T50 smoke | Patches + market fit |
| **E11** | BPMN gap reproduction | For each GAP-set element: works / needs extension / needs engine patch | T20 | Coverage + patches |

### Scoring (VI.7 D-1 weights)

| Bucket | Weight | Measured by |
|---|---|---|
| Coverage of corpus without engine patches | 30 % | E1, E11 |
| Number and size of required engine patches | 25 % | Patch ledger: lines changed, files touched, upgrade risk |
| Failure-test pass rate | 20 % | E2–E8 |
| Benchmark (WF-R5 on PostgreSQL) | 10 % | E9 |
| Fit with the first market (government integrator) | 15 % | Authorization model, audit, Oracle/SQL Server behaviour, delegation-of-powers handling, CMMN, licences (E2, E6, E10) |

**Decision rule:**
- The higher score wins.
- If the scores are within 5 points, the candidate with **fewer engine patches** wins (lower long-term maintenance).
- A deep fork is allowed only for a Must gap that cannot be built through extension points, recorded in the patch ledger.

## XI.5 Environment

| Item | Choice |
|---|---|
| Host | The dev machine: Windows 11, 8 logical CPUs, 32 GB RAM, Docker 29.7.2 (Linux engine) — VERIFIED 2026-11-05 |
| JDK | JDK 21.0.10 present; **JDK 25 LTS** to be installed for E10 (R5) |
| Build | Maven wrapper with a current Maven (the system Maven 3.6.2 is too old) |
| Databases | Testcontainers: PostgreSQL 17; Oracle Database Free (container image); SQL Server 2022 Developer (container image, dev/test use). Oracle/SQL Server JDBC drivers fetched at build time, not shipped (LIC-2) |
| Simulators | WireMock/Java stubs for IFMS JIT, eSign, DigiLocker; an SFTP container for PFMS files; Keycloak container as the IdP stand-in for Parichay (SAML/OIDC) |
| Repository | A new `spike/` folder in this project: `spike/product-api`, `spike/adapter-flowable`, `spike/adapter-operaton`, `spike/corpus` (BPMN/DMN/CMMN/forms), `spike/simulators`, `spike/tests`, `spike/results` |

## XI.6 Workstreams (run in parallel)

| WS | Work | Depends on |
|---|---|---|
| WS-1 | Product API + EngineAdapter interface + two adapters | — |
| WS-2 | Corpus models (WF-1…WF-3, GAP set), DMN delegation tables, forms | — |
| WS-3 | Product-layer components (authorization, ledger, idempotency, outbox/inbox, tombstones, audit chain) | WS-1 interface |
| WS-4 | Simulators + Testcontainers harness | — |
| WS-5 | Experiment suites E1–E11 + chaos/fault injection | WS-1…WS-4 |
| WS-6 | Benchmark + scoring + ADR-D-1 report | WS-5 |

## XI.7 Timeline (honest estimate)

| Week | Output |
|---|---|
| W1 | Environment, adapters skeleton, corpus models, simulators; E1/E11 first pass |
| W2 | E2 (authorization), E3 (lease), E4 (idempotency) on both engines |
| W3 | E5 (outbox/inbox), E6 (concurrency on 3 DBs), E7, E8 |
| W4 | E9 benchmark, E10 platform fit, scoring, **ADR-D-1 decision**, effort re-estimate (M09) |
| W5–W6 | Buffer for failed experiments or hardware access |

AI-assisted parallel work can compress this, but no date is promised before W1 shows real progress on this machine.

## XI.8 Deliverables and exit

- `spike/results/*.json`: raw results per experiment and engine.
- `spike/results/patch-ledger.md`
- `spike/results/scorecard.md`
- **ADR-D-1 final record**, added to this master.
- Licence inventory regenerated at the chosen tag (LIC-11).
- Re-estimated effort for Stages C–E.

**Stage B exit:** G2 (engine chosen on evidence) + G3 (licence/dependency route accepted) → Stage C starts.

## XI.9 Spike risks

| Risk | Mitigation |
|---|---|
| Oracle / SQL Server containers heavy on 32 GB | Run one DB at a time; PostgreSQL by default, Oracle/SQL Server for E6 and the AC-07 subset |
| Flowable 8.0.0 or Operaton 2.2.0 not working on JDK 25 | Run on JDK 21 first; record JDK 25 status as an E10 finding |
| Benchmark hardware too small | Report functional-scale numbers clearly labelled; reference-scale run later (R22 stays a hypothesis) |
| An engine needs patches for Q1/Q2 | That is exactly what the spike measures: it is recorded and scored, not hidden |

## XI.10 Stage B results and ADR-D-1 decision (2026-11-05)

**Evidence:** `spike/results/`:
- E1, E2, E3, E4, E5, E6, E7, E8, E9, E10, E11 (JSON + three reports);
- both patch ledgers;
- `scorecard.md`.

All experiments ran on JDK 21.0.10 with PostgreSQL 17. E6 also ran on Oracle Free 23 and SQL Server 2022 (RCSI). Nothing was implemented beyond the spike.

### XI.10.1 Summary of measurements

| Experiment | Flowable 8.0.0 | Operaton 2.2.0 |
|---|---|---|
| Adapters | 26/26 tests, 0 engine patches | 28/28 tests, 0 engine patches |
| **Q1** product tables in the engine transaction | **Yes**, with an explicit rollback fix (same MyBatis defect as Operaton) | **Yes**, with the same fix |
| **Q2** VI.4 authorization predicate on engine task tables | **Yes**, no patch (plain SQL + native query) | **Yes**, no patch. The built-in authorization does *not* honour deny-wins → keep it off |
| **Q3** VI.3 lease model | Partial: no extendLock (internal workaround), no fencing → product `SPIKE_LEASE` | Partial: native extendLock and activityInstanceId; no fencing → product `SPIKE_LEASE` |
| **Q4 / E11** BPMN GAP set (8) | 3 works / 4 need extension / 1 needs patch | 7 works / 1 needs patch (ad-hoc sub-process) |
| E1 corpus (17 scenarios) | 17/17 | 16/17 (CMMN history labels Inspection "terminated") |
| E2–E5, E7 integration | 26/27 (adapter correlation bug) | 27/27 |
| E2 at 10k tasks × 300 groups | list p95 86 ms ✓; count 550 ms ✗ | list p95 34 ms ✓; count 386 ms ✗ |
| E6 concurrency, hot root without L2 (PG / Oracle / MSSQL) | 0 lost updates, 0 deadlocks; 511 / 577 / 704 unrecovered after 5 retries | 0 lost updates, 0 deadlocks; 559 / 563 / 758 unrecovered |
| E8 DST `R/PT1H` | Asia/Kolkata 24/24 ✓; Berlin spring 6/6 ✓; **autumn fold runaway (≥21)** ✗ | Same: **runaway at the autumn fold** ✗ |
| E9 functional benchmark (low confidence) | R1 ≈ 23/s; R5-lite ≈ 11/s | R1 ≈ 35/s; R5-lite ≈ 11.5/s, lower tail latency |
| E10 | JDK 21 ✓; JDK 25 not tested; no forbidden licences | Same |

### XI.10.2 Scorecard and decision

| Bucket | Weight | Flowable | Operaton |
|---|---|---|---|
| Coverage | 30 | 24.4 | 27.2 |
| Engine patches | 25 | 10.0 | 17.0 |
| Failure tests | 20 | 17.3 | 18.0 |
| Benchmark | 10 | 8.4 | 10.0 |
| Market fit | 15 | 12.0 | 13.0 |
| **Total** | 100 | **72.1** | **85.2** |

Methods are in `spike/results/scorecard.md`. The patch and market-fit methods are judgements **[I]**; the result holds without them (robustness check in the scorecard).

**ADR-D-1: DECIDED by the approved VI.7 procedure.** Base engine = **Operaton 2.2.x**; the margin of 13.1 exceeds the 5-point tie rule.
- **Consequence:** the Part I plan, which used Flowable as the base and ported Operaton features into it, is **inverted**. Sangam builds on **Operaton** and carries Flowable's strengths over as product-layer features or ports:
  - ad-hoc sub-process: an engine patch, a Should item (CF-13) unless a pack needs it;
  - event-registry ideas into the event gateway (R38 / VI.2);
  - richer CMMN listeners: only as needed (R45);
  - Flowable's multi-language worker clients as a design reference for the Tier-1 SDKs.
- **The owner may override this decision.** Recorded as DECIDED because the owner approved the procedure (XI, 2026-11-05).

### XI.10.3 Spec amendments forced by measured evidence (apply on any engine)

| ID | Amendment | Evidence |
|---|---|---|
| **A-10** | **Lease-expiry hook.** Engine-native lease expiry (inside fetch, job execution or the engine's reset thread) must **not** make work available again before the product layer applies A-8. The adapter calls `ExpiryPolicy.onExpired(work)` in the same transaction; class (c) work goes to `UNKNOWN_OUTCOME`/held and is not re-offered | E3: a **measured double payment** when engine expiry ran before the product sweep |
| **A-11** | **Root lock L2 is mandatory, not optional.** Both engines rely on optimistic locking only; without L2, 30–75 % of hot-root commands fail after 5 retries on PG/Oracle/SQL Server (no data loss) | E6 |
| **A-12** | **DST timer guard (R31) is mandatory on the base engine:** UTC due dates, `lastFiredInterval`, catch-up policy; `executeDueJobs`/job execution must be bounded | E8: runaway on both engines |
| **A-13** | **A-3 wording:** *any matching deny wins, at any scope* (as compiled into `NOT EXISTS`) | E2 / product-layer ambiguity #2 |
| **A-14** | `OPERATION_LEDGER` carries the engine work id (task / external-task id) for expiry and delete handling | E3, E7 |
| **A-15** | The lease-table SQL is per dialect (PostgreSQL/Oracle/SQL Server); adapter constructors must not assume PostgreSQL | E6 blocker 1 |
| **A-16** | **Count queries under authorization need optimisation** (count p95 386–550 ms at 10k × 300 groups): an index plan on `AUTH_EFFECTIVE` + task tenant/group columns, or an approximate count with a "more than N" UI. R19 performance acceptance applies to list **and** count | E2 |

### XI.10.4 Defects to fix at Stage C start (adapter level, not engine)

- **Operaton adapter:** concurrent fetch by 4+ workers caused duplicate-lease-key / stale errors (17 % of R1) and ~30 stuck instances. The `SPIKE_LEASE` write must be idempotent per (work_id, lock_version) and serialized with the engine lock (E9 finding 2).
- **Operaton:** CMMN history state for exited human tasks ("terminated" vs "completed"). Decide the mapping in the audit projection (R42).
- **Operaton static clock** (`ClockUtil`, JVM-wide): use one engine per JVM (W-7).
- **Q1 explicit rollback** must stay in the adapter (both engines).
- **Flowable adapter** (reference only now): the correlation business-key bug, async continuation after external completion, slow fetch scan. **Not fixed**, because Flowable is no longer the base.
- **Not yet tested:** JDK 25 (R5), reference-scale benchmark (R22), Oracle/SQL Server for E1–E5/E7, poison-message / restore scenarios (T08, T35), membership join above 200 groups.

### XI.10.5 Stage B exit

| Gate | Status |
|---|---|
| **G2** engine choice | **PASSED**: measured, comparable corpus; patch burden recorded |
| **G3** dependency / licence route | **PASSED for the chosen engine**: no GraalJS, ojdbc, MySQL or Db2 drivers on the runtime classpath (E10); Jackson 2.22.1 required; Spring jars transitive (no context). Regenerate the licence inventory at the Operaton 2.2.x release tag (LIC-11) |
| Effort re-estimate (M09) | **Open**: to be done at Stage C planning, using the spike's measured adapter and product-layer sizes |

**Stage B is COMPLETE.** Next: Stage C, the full pilot on Operaton 2.2.x.

# Appendix A — PDF Report, Full Extracted Text (raw)

> Extracted with PyMuPDF. Some Devanagari conjuncts are broken by the extraction; the meaning of every item is captured cleanly in Part IV.

~~~~text
PAGES: 27

=== PAGE 1 ===
END-TO-END ARCHITECTURE, MARKET INTELLIGENCE & PRE-DEVELOPMENT AUDIT
Project Sangam (संगम) — संपूण विश्लेषण रिपोट
Comprehensive Evaluation of DESIGN.md & ANALYSIS.md Across Architecture, Polyglot
Standards, Security, Market Positioning, and Delivery Feasibility
आधार (Baseline Sources): Operaton 2.3.0-SNAPSHOT ( ccea971 ) & Flowable 8.1.0-SNAPSHOT
( f6017e3 ) | दिनांक (Date): 2026-11-04 | भूमिकाएँ (Evaluation Roles): Chief Distributed Systems Architect,
Standards & AI Specialist, CISO, Market Strategist, Engineering Delivery Director
कायकारी सारांश (Executive Summary): Project Sangam (संगम) केवल दो ओपन-सोस इंजनों (Operaton
और Flowable) को जोड़ने का प्रयास नहीं है, बि
 एंटरप्राइज़ वकफ़््लो बाज़ार के एक बड़े खाली स्थान (Open Market
Slot) को भरने का एक साक्ष्य-आधारित इंजीनियरिंंग ब्लूप्रिंंट है। आज बाज़ार में कोई भी ऐसा उत्पाद नहीं है जो BPMN 2.0.2 +
CMMN 1.1 + DMN 1.6 (Full FEEL) + Fine-grained Authorization + Multi-tenancy + संपूण
आधुनिक UI (Cockpit, Tasklist, Admin, Web Modeler) एक साथ Apache 2.0 लाइसेंस के अंतगत
उपलब्ध कराता हो [F]।
कोड-स्तरीय अध्ययन से यह प्रमाणित है कि दोनों इंजनों का सीधा "Copy-Paste" मजर असंभव है क्योंकि Operaton PVM
graph + atomic operations + tree compaction पर चलता है जबकि Flowable flat FIFO agenda
+ ACT_RU_ACTINST पर। इसलिए Flowable के एजेंडा कोर और शेयड-सर्विसेज को बेस बनाकर उसमें Operaton
की सर्वोत्तम खूबियाँ (Authorization, Migration/Modification/Restart, Batch, History Pipeline,
Cleanup, SKIP LOCKED , Liquibase, webapps-neo ) पोट करने और 35 अनिवाय सुधार (R1–R35) लागू करने
से अपस्ट्रीम के 24 प्रमुख विफलता मोड्स का कुल जोखिम (RPN) 4,581 से घटकर 712 (≈ 84% कम) रह जाता है [I]।
0. विश्लेषण की कायप्रणाली, साक्ष्य नियम और 5 विशेषज्ञ भूमिकाएँ (Methodology & Roles)
इस रिपोट में DESIGN.md और ANALYSIS.md के प्रत्येक बिंंदु का बिना कुछ छोड़े (Zero-Omission) मूल्यांकन किया गया है।
ANALYSIS.md में स्थापित सख्त साक्ष्य नियमों (Evidence Rules) का पालन किया गया है:
टैग (Tag)
अथ (Meaning)
अनुमत उपयोग (Allowed Use in Decisions)
[F]
Fact (तथ्य): लोकल सोस कोड, आधिकारिक स्टैंडड्स, CVE
या GitHub/JIRA इश्यू से सत्यापित प्रमाण।
सीधे आर्किटेक्चरल और डिज़ाइन निणय लेने के लिए अनुमत।
[V]
Vendor Claim (वेंडर दावा): किसी उत्पाद की अपनी कंपनी
द्वाारा प्रकाशित बेंचमाक या दावा।
केवल संदभ के लिए; कभी भी अकेले किसी निणय का आधार
नहीं।
[I]
Inference (तार्किक निष्कष): तथ्यों के आधार पर निकाला
गया तकनीकी निष्कष जिसका आधार स्पष्ट लिखा गया हो।
तक लिखित होने पर ही निणय का आधार बन सकता है।
[U]
Unverified (असत्यापित): जिसका सावजनिक प्रमाण
उपलब्ध नहीं मिला।
निणय का आधार नहीं बन सकता; यह सीधे टेस्ट या अध्ययन
आइटम बनता है।
दोनों दस्तावेज़ों के संपूण अध्ययन के लिए 19 प्री-डेवलपमेंट विश्लेषण आयामों को 5 विशेषज्ञ भूमिकाओं (Expert Roles) के अंतगत
परखा गया है: (1) चीफ डिस्ट्रीब्यूटेड सिस्टम्स आर्किटेक्ट, (2) स्टैंडड्स, पॉलीग्लॉट एवं AI प्लेटफ़ॉम विशेषज्ञ, (3) मुख्य सूचना सुरक्षा
अधिकारी (CISO), (4) मार्केट एवं प्रोडक्ट स्ट्रैैटेजिस्ट, और (5) इंजीनियरिंंग डिलीवरी डायरेक्टर।
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 1 of 27

=== PAGE 2 ===
1. स्टेकहोल्डस (S1–S10) और प्रााथमिकता-आधारित यूज़-केस (UC1–UC12)
संगम के डिज़ाइन को किसी काल्पनिक आवश्यकता के बजाय 10 वास्तविक उपयोगकर्ता समूहों ( S1 – S10 ) और उनके प्रमाणित
बाज़ार दद (Pain Points) से जोड़ा गया है:
ID
स्टेकहोल्डर (Stakeholder)
मुख्य आवश्यकता (Primary Need)
वास्तविक प्रमाण (Evidence [F])
S1
किसी भी भाषा का ऐप डेवलपर
(PHP, .NET, Go, Node,
Python, Java)
सरल SDK, लोकल टेस्ट इंजन, स्पष्ट एरर
संदेश
Flowable #4134 में आधिकारिक .NET
SDK की मांग; Camunda ने 8.6 में Go क्लाइंट
बंद किया।
S2
प्रोसेस मॉडलर / बिज़नेस
एनालिस्ट
विज़ुअल BPMN/DMN/CMMN मॉडलर
और सिमुलेशन
Flowable में कोई OSS UI नहीं है;
Camunda 8 वेब मॉडलर के लिए प्रोडक्शन
लाइसेंस आवश्यक है।
S3
बिज़नेस यूज़र (Business
User)
Tasklist, फ़ॉर्म्सस, मोबाइल-फ्रेंेंडली UI
Flowable OSS में Tasklist अनुपि
त;
Operaton का पुराना AngularJS UI End-
of-Life है।
S4
Operations / SRE टीम
आसान डिप्लॉयमेंट, मेट्रिक्स, सुरित अपग्रेेड,
इंसिडेंट हैंडलिंंग
Flowable अपग्रेेड विफलताएं ( #4008 ,
#2814 ) और हिस्ट्री विस्फोट ( #3422 : 2
TB bytearray टेबल)।
S5
सुरक्षा एवं अनुपालन अधिकारी
(Security / Compliance)
Authorization, ऑडिट लॉग, सुरित
डिफ़ॉल्ट, CVE प्रबंधन
Camunda Notice 157 (RCE); Flowable
CVE-2026-91197 (XXE)।
S6
Camunda 7 से माइग्रेेट करने
वाली टीमें
मॉडल/API कम्पैटिबिलिटी, मुफ़््त ओपन-सोस
लाइसेंस
Camunda 7 CE अक्टूबर 2025 में EOL हुआ;
Camunda 8 ड्रॉप-इन रिप्लेसमेंट नहीं है।
S7
Flowable OSS से माइग्रेेट
करने वाली टीमें
आधुनिक UI, फाइन-ग्रेेन्ड
Authorization, मौजूदा मॉडल्स का
चलना
Flowable OSS में न UI है और न ही
Authorization सर्विस।
S8
AI-Agent बिल्डस
ड्यूूरेबल एजेंट स्टेप्स, ह्यूूमन अप्रूूवल, MCP
सपोट
Temporal का $300M फंडिंंग राउंड एजेंट्स से
प्रेेरित; MCP टूल-पॉइज़निंंग सुरक्षा शोध।
S9
ISVs / SaaS बिल्डस (इंजन
एम्बेड करने वाले)
Multi-tenancy, परमिसिव Apache
2.0 लाइसेंस, व्हाइट-लेबलिंंग
Kestra में Multi-tenancy पेड है; n8n Fair-
code है; Camunda 8 में प्रोडक्शन लाइसेंस
लगता है।
S10
प्रोसेस-माइनिंंग एनालिस्ट
साफ़ इवेंट लॉग्स (XES / OCEL 2.0
एक्सपोट)
pm4py में OCEL 2.0 का पूण समथन उपलब्ध
है।
1.1 MoSCoW प्राथमिकता के साथ 12 यूज़-केस (UC1–UC12)
ID
यूज़-केस विवरण (Use Case Description)
स्टेकहोल्डस
प्राथमिकता
(MoSCoW)
UC1
किसी भी भाषा से प्रोसेस शुरू करना और उसी भाषा के वकर से सर्विस स्टेप्स पूरे
करना
S1
Must
UC2
वेब मॉडलर में BPMN + DMN मॉडल बनाना, डिप्लॉय करना और वजनिंंग
करना
S2
Must
UC3
फ़ॉर्म्सस, क्लेम/डेलिगेट और डेडलाइन के साथ ह्यूूमन टास्क (Human Tasks)
चलाना
S3
Must
UC4
रनिंंग इंस्टेंसेज की निगरानी (Monitor), रिट्राई, मॉडिफाई और माइग्रेेट करना
S4
Must
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 2 of 27

=== PAGE 3 ===
ID
यूज़-केस विवरण (Use Case Description)
स्टेकहोल्डस
प्राथमिकता
(MoSCoW)
UC5
फाइन-ग्रेेन्ड परमिशन (Authorization) और टेनेंट आइसोलेशन (Multi-
tenancy)
S5, S9
Must
UC6
मौजूदा Camunda 7 / Operaton / Flowable मॉडल्स को इम्पोट करके
चलाना
S6, S7
Should (Core
import early)
UC7
Kafka / RabbitMQ / Webhooks से प्रोसेस शुरू या जारी करना और
इवेंट्स बाहर भेजना
S1, S4
Should
UC8
CMMN 1.1 के साथ केस मैनेजमेंट (Case Management)
S2, S3
Should
UC9
बजट, अप्रूूवल गेट और क्रैश-सेफ रिज़््यूम के साथ AI एजेंट स्टेप
( agentTask ) चलाना
S8
Should
UC10
MCP (Model Context Protocol) के माध्यम से AI असिस्टेंट्स को प्रोसेस
उपलब्ध कराना
S8
Could (Read-
only first)
UC11
प्रोसेस माइनिंंग के लिए XES / OCEL 2.0 इवेंट लॉग्स एक्सपोट करना
S10
Could
UC12
Serverless फ़ंक्शन्स (AWS Lambda, Azure Functions, Cloud
Run) पर स्टेप्स चलाना
S1
Should
2. भूमिका 1: चीफ डिस्ट्रीब्यूटेड सिस्टम्स आर्किटेक्ट — कोर आर्किटेक्चर तुलना
2.1 Operaton बनाम Flowable 8 बनाम Sangam (22-सूत्रीय तुलनात्मक तालिका)
दोनों इंजनों के सोस कोड के अध्ययन से यह स्पष्ट है कि दोनों की अपनी-अपनी ताकत और कमज़ोरियाँ हैं। संगम ने प्रत्येक क्षेत्र से
विजेता तत्व को चुना है और जहाँ दोनों कमज़ोर थे, वहाँ नया डिज़ाइन लागू किया है:
क्षेत्र (Area)
Operaton (Camunda 7
Fork)
Flowable 8
विजेता → Sangam का निणय
1. Execution
Core
PVM graph
( ActivityImpl ) + ~35
एटॉमिक ऑपरेशन्स + ट्राम्पोलिन;
एक्जीक्यूशन ट्री कम्पैक्शन और प्री-7.3
के लिए LegacyBehavior ।
सीधे BpmnModel पर फ्लैट एजेंडा
( DefaultFlowableEngin
eAgenda , FIFO Runnable
ops); async टास्क के लिए future
ops।
Flowable कोर: सरल, कोई लेगेसी
ट्री-कम्पैक्शन जटिलता नहीं।
2. Activity
Instances
क्वेरी के समय एक्जीक्यूशन ट्री से
डिराइव किया जाता है।
फर्स्टट-क्लास रनटाइम टेबल
ACT_RU_ACTINST ।
Flowable: बिना ट्री रिकंस्ट्रक्शन के
मॉडिफिकेशन आसान बनाता है।
3. BPMN
Model
दो अलग मॉडल: रनटाइम के लिए SAX
BpmnParse , टूलिंंग के लिए
DOM model-api ।
एक ही BpmnModel (StAX
कन्वटर) जो पासर, वैलिडेटर और
रनटाइम तीनों में चलता है।
Flowable मॉडल + Operaton
का Fluent Builder API विचार।
4. CMMN
Engine
CMMN 1.1 चालू है, पर इवेंट
लिसनस कमेंट-आउट हैं और कई
TODOs हैं।
अपना एजेंडा, परसिस्टेंट सेंट्री मेमोरी
( SentryPartInstanceEn
tity ), समृद्ध लाइफसाइकिल और
केस माइग्रेेशन।
Flowable CMMN: अलग प्लग-
इन मॉड्यूूल में आइसोलेटेड।
5. DMN
Engine
feel-scala 1.22.1 के
साथ पूण FEEL, DRD और हिट
पॉलिसीज़ (TCK में DMN-Scala
1.9 का स्कोर 2850/3391)।
JUEL के साथ FEEL-जैसी प्री-पार्सिंंग;
असली FEEL बिल्कुल नहीं है ( grep
-i feel में 0 मैच)।
नया SPI: डिफ़ॉल्ट Apache KIE
Drools DMN (TCK
3388/3391, DMN 1.6); jDMN
व feel-scala वैकि
क।
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 3 of 27

=== PAGE 4 ===
क्षेत्र (Area)
Operaton (Camunda 7
Fork)
Flowable 8
विजेता → Sangam का निणय
6. Engine
Composition
एक मोनोलिथिक इंजन जिसके अंदर
BPMN+CMMN+DMN बंधे हैं।
Engines + Configurators +
Shared Services जो
SCOPE_TYPE_ से अलग पहचाने
जाते हैं।
Flowable पैटन: साफ़ SPI और
इम्यूटेबल कॉि
ग के साथ।
7. Job
Executor
DB पोलिंंग, एक्सपोनेंशियल बैकऑफ,
प्रायोरिटीज़, प्रायोरिटी-रेंज पूल्स,
डिप्लॉयमेंट-अवेयर, नया ऑप्ट-इन
SKIP LOCKED ।
Async और Timer के लिए अलग
एक्वायर थ्रेेड्स, ग्लोबल एक्वायर लॉक,
पोस्ट-कमिट डायरेक्ट हैंड-ऑफ, अलग
जॉब टेबल्स।
दोनों का संगम: Flowable का हैंड-
ऑफ + टेबल ि
ट; Operaton की
प्रायोरिटीज़ + डिफ़ॉल्ट SKIP
LOCKED ।
8. External
Workers
Fetch-and-lock, टॉपिक्स,
वेरिएबल फ़िल्टर; लॉन्ग-पोल केवल एक
JVM तक सीमित; केवल Java
क्लाइंट।
BPMN और CMMN दोनों के लिए
External Worker जॉब्स; केवल
REST; क्लाइंट्स अलग रिपोज़ में।
नया डिज़ाइन: क्लस्टर-अवेयर लॉन्ग-
पोल + gRPC स्ट्रीमिंंग + 8 भाषाओं के
SDKs।
9. Events /
Messaging
कुछ नहीं: कोई Kafka/AMQP नहीं;
केवल API कोरिलेशन और इन-JVM
Spring इवेंट्स।
Event Registry:
Kafka/JMS/Rabbit चैनल्स, की/
टेनेंट डिटेक्टस, कोरिलेशन, स्टाट/
बाउंड्री/कैच इवेंट्स।
Flowable Event Registry +
CloudEvents 1.0 +
Transactional Outbox।
10. History
प्लगेबल Producer →
Handler पाइपलाइन, रिमूवल-
टाइम रणनीतियाँ, TTL, क्लीनअप
विंंडो, पैरेलल क्लीनअप।
पर-एलिमेंट हिस्ट्री लेवल; async
हिस्ट्री अब केवल हुक्स तक सीमित;
टाइमर-आधारित बल्क क्लीनअप।
Operaton पाइपलाइन +
Flowable के पर-एलिमेंट लेवल्स,
Outbox के माध्यम से।
11. Migration
/ Dynamic
माइग्रेेशन प्लान व वैलिडेटस,
मॉडिफिकेशन (start
before/after, cancel), हिस्ट्री से
रीस्टाट, बैच वजन्स।
JSON माइग्रेेशन प्लान, 1→N व
N→1 मैपिंंग, प्री/पोस्ट ि
प्ट्स,
change-state, टास्क/सबप्रोसेस
का डायनामिक इंजेक्शन, केस
माइग्रेेशन।
दोनों का यूनियन (Union): दोनों के
सभी डायनामिक फीचस।
12. Batch
Framework
Seed / Job / Monitor
बैच फ्रेेमवक और कई बिल्ट-इन बैच
प्रकार।
BatchService + बैच पाट्स।
Operaton बैच फ्रेेमवक।
13.
Authorization
फाइन-ग्रेेन्ड: 22 रिसोस प्रकार,
grant/revoke, परमिशन
बिटमास्क, सीधे SQL में लागू।
OSS में शून्य: केवल मोटे प्रिविलेज और
REST इंटरसेप्टर हुक्स।
Operaton मॉडल (डिनॉमलाइज़््ड
टेबल के साथ) + ABAC (OPA /
Cedar)।
14. Multi-
tenancy
रो-लेवल TENANT_ID_ + टेनेंट
चेक्स।
रो-लेवल तथा DB-per-tenant
राउटिंंग
( TenantAwareDataSourc
e ) + चेंज-टेनेंट टूलिंंग।
Flowable (दोनों मोड्स) +
Operaton के टेनेंट चेक्स।
15. Scripting
JSR-223 (GraalJS, Groovy),
Spin JSON/XML।
Rhino सैंडबॉक्स (क्लास व्हाइटलिस्ट,
CPU/मेमोरी सीमा), स्टैटिक
Groovy।
WASM (Chicory/Endive) +
GraalJS Isolates; Rhino पूरी
तरह प्रतिबंधित ( CVE-2025-
0982 )।
16. Schema
Migration
पर-DB SQL ि
प्ट्स तथा
Liquibase।
केवल पर-DB SQL ि
प्ट्स।
Liquibase एकमात्र स्रोोत (DBAs
के लिए जनरेटेड SQL)।
17. Databases
H2, Postgres, MySQL,
MariaDB, Oracle, MSSQL,
DB2।
उपरोक्त सभी + CockroachDB,
HSQL।
टियड सपोट (Tier 1, Tier 2,
Experimental)।
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 4 of 27

=== PAGE 5 ===
क्षेत्र (Area)
Operaton (Camunda 7
Fork)
Flowable 8
विजेता → Sangam का निणय
18. Runtimes
Embedded, Spring Boot 4,
Quarkus, Jakarta EE, Run
distro।
Embedded, Spring Boot 4 (+
GraalVM native sample)।
JDK 25 LTS स्टैंडअलोन सवर +
Spring Boot 4 / Quarkus
एम्बेडेड + लाइट नेटिव इमेज।
19. User
Interface (UI)
पुराना AngularJS (EOL) + नया
webapps-neo (Preact,
bpmn-js/dmn-js/form-js)।
कोई OSS UI नहीं है।
Operaton webapps-neo +
नया वेब मॉडलर।
20.
Observability
DB मीटर टेबल्स, हेल्थ सर्विस; कोई
Micrometer/OTel नहीं।
जॉब एक्जीक्यूटर में Micrometer-
शैली का ऑब्ज़र्वेशन हुक।
OpenTelemetry ट्रेेसिंंग (W3C
traceparent ) +
Micrometer/Prometheus हर
जगह।
21. Testing
JUnit 5 + AssertJ प्रोसेस असशन्स
+ पर-DB Testcontainers +
ArchUnit।
JUnit 5 एक्सटेंशन्स,
@Deployment , मॉक बिहेवियर
फ़ैक्ट्री।
दोनों + Mutation (PIT),
Property (jqwik), Fuzzing
(Jazzer), Jepsen व Replay
टेस्ट।
22. Legacy
Weight
LegacyBehavior , ~171
डिप्रिकेशन्स, AngularJS।
flowable5-* (748 फ़ाइलें,
83,316 लाइन्स), ~367
डिप्रिकेशन्स।
दोनों हटाए गए: पुराने मॉडल्स इम्पोट के
समय कन्वट होंगे।
2.2 दोनों इंजनों की 18 साझा कमज़ोरियाँ (W1–W18) और संगम का समाधान
ID
कमज़ोरी और उसका स्रोोत (Weakness &
Where)
संगम का स्थायी समाधान (Sangam's Architectural Fix)
W1
Java-केंद्रित गहरा इंटीग्रेेशन (दोनों):
JavaDelegate फर्स्टट-क्लास है, अन्य भाषाओं को
केवल REST मिलता है।
Protocol-first (gRPC + REST + CloudEvents); 8 भाषाओं के
जनरेटेड SDKs; Java Beans के बजाय FEEL एक्सप्रेेशन्स; टाइप्ड JSON
वेरिएबल्स।
W2
डेटाबेस ही एकमात्र कोऑर्डिनेशन माध्यम है; पोलिंंग
और एि
जिशन पर OLE तूफ़ान (Operaton)।
डिफ़ॉल्ट रूप से दो-चरणीय SKIP LOCKED + पोस्ट-कमिट डायरेक्ट हैंड-
ऑफ + Outbox रिले से बैच्ड वेक-अप बस (Postgres NOTIFY /
NATS / Redis)।
W3
पैरेलल जॉइन्स और एक ही इंस्टेंस पर समवर्ती
कोरिलेशन में Optimistic-Lock Exceptions
(दोनों)।
ग्लोबल लॉक क्रम ( root instance → executions →
jobs ) के साथ रूट इंस्टेंस पर SELECT … FOR UPDATE रो-लॉक
+ जिटर वाला रिट्राई इंटरसेप्टर।
W4
लॉन्ग-पोलिंंग क्लस्टर-अवेयर नहीं है (Operaton)।
सभी API नोड्स द्वाारा साझा वेक-अप बस और फ्लो-कंट्रोल (credits) के साथ
gRPC सवर स्ट्रीमिंंग।
W5
सिंंक्रोनस इनलाइन हिस्ट्री (Operaton); async
हिस्ट्री केवल हुक्स तक सीमित (Flowable)।
ट्रांांजैक्शनल Outbox → हिस्ट्री प्रोजेक्टर → SQL / Elasticsearch /
OpenSearch / Kafka; सख्त ऑडिट के लिए वैकि
क sync मोड।
W6
कोई मेट्रिक्स या ट्रेेसिंंग नहीं (Operaton),
Flowable में कमज़ोर।
हर कमांड, जॉब और वकर पर OpenTelemetry स्पैन्स (सिंंक्रोनस के लिए
parent, async के लिए span links — R13 ) और Micrometer
मेट्रिक्स।
W7
5,000+ लाइनों वाली म्यूटेबल गॉड-कॉि
ग क्लास
(दोनों)।
इम्यूटेबल टाइप्ड कॉि
ग ( EngineConfig records + builders),
प्रति फीचर अलग SPI मॉड्यूूल, स्टाटअप पर वैलिडेशन और 800-लाइन क्लास
सीमा।
W8
62 डेटाबेस टेबल्स, डुप्लीकेट डिप्लॉयमेंट और प्रॉपर्टी
टेबल्स (Flowable)।
सभी मॉडल प्रकारों के लिए एक साझा डिप्लॉयमेंट/रिसोस स्टोर; सर्विस के
अनुसार समूहित टेबल्स; लक्ष्य ~35 टेबल्स।
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 5 of 27

=== PAGE 6 ===
ID
कमज़ोरी और उसका स्रोोत (Weakness &
Where)
संगम का स्थायी समाधान (Sangam's Architectural Fix)
W9
कोई फाइन-ग्रेेन्ड Authorization नहीं
(Flowable)।
Operaton का रिसोस × परमिशन मॉडल (लिस्ट क्वेरीज़ के लिए
डिनॉमलाइज़््ड परमिशन टेबल — R19 ) + सिंंगल चेक के लिए ABAC (OPA
/ Cedar) + OIDC।
W10
DMN में असली FEEL का अभाव (Flowable);
feel-scala में अधूरा TCK कवरेज
(Operaton)।
DecisionEngine SPI के पीछे डिफ़ॉल्ट Apache KIE Drools
DMN (DMN 1.6, लेवल 3); BPMN/CMMN कंडीशन्स के लिए भी पिन
किया गया FEEL इंजन।
W11
कोई इवेंट ब्रोोकर इंटीग्रेेशन नहीं (Operaton)।
इवेंट गेटवे: Kafka, NATS, RabbitMQ, JMS, SQS/SNS, Azure
Service Bus, Google Pub/Sub, MQTT और Webhooks
(CloudEvents 1.0 प्रारूप)।
W12
कोई OSS UI नहीं (Flowable); AngularJS
EOL (Operaton)।
webapps-neo (Preact) पर आधारित एकीकृत आधुनिक UI:
Cockpit, Tasklist, Admin और नया Web Modeler + एम्बेडेबल वेब-
कम्पोनेंट्स।
W13
भारी लेगेसी कम्पैटिबिलिटी कोड
( LegacyBehavior , flowable5-* )।
पूरी तरह हटाया गया; पुराने मॉडल्स रनटाइम लेगेसी पाथ के बजाय इम्पोट के
समय कन्वट किए जाते हैं।
W14
वेरिएबल्स का Java Serialization (दोनों में मौजूद,
भले डिफ़ॉल्ट ऑफ हो)।
पूरी तरह समाप्त; वेरिएबल्स केवल टाइप्ड JSON (string, number,
boolean, date, json, file, reference) हैं जिनमें 256 KB इनलाइन
सीमा है।
W15
थ्रेेड-बाउंड ThreadLocal डिज़ाइन, वचुअल
थ्रेेड्स का अभाव (दोनों)।
JDK 25 LTS वचुअल थ्रेेड्स (JEP 491 से synchronized पिनिंंग
समाप्त), सेमाफोर द्वाारा नियंित; कोर में स्पष्ट कॉन्टेक्स्ट पासिंंग ( D11 )।
W16
7–8 डेटाबेस के लिए हाथ से लिखी गई SQL अपग्रेेड
ि
प्ट्स (दोनों)।
एकमात्र स्रोोत के रूप में Liquibase चेंजलॉग (रोलिंंग अपग्रेेड के लिए सिंंगल
K8s Job के रूप में — R12 ) और DBAs के लिए जनरेटेड SQL।
W17
कमज़ोर सैंडबॉक्स के साथ एम्बेडेड ि
प्टिंंग
(Operaton) और Rhino एस्केप जोखिम।
WASM (Chicory/Endive) और GraalJS Isolates जिनमें CPU/
मेमोरी/समय/फ़््यूल सीमा है और डिफ़ॉल्ट रूप से कोई होस्ट एक्सेस नहीं है
( R6 )।
W18
केवल Java में एक्सटनल-टास्क क्लाइंट
(Operaton)।
टियड आधिकारिक SDKs: Tier 1 (Java, .NET, PHP,
TypeScript/Node, Python, Go) और Tier 2 (Rust, Kotlin)।
2.3 प्रत्येक डिज़ाइन तत्व का 5W1H विश्लेषण (ANALYSIS.md §2)
तत्व
(Element)
क्या (What)
क्यों — साक्ष्य (Why)
कौन
(Who)
कब
(Phas
e)
कहाँ
(Where)
कैसे (How)
Flowable
Core Base
Agenda इंजन, शेयड
सर्विसेज, CMMN,
इवेंट रजिस्ट्री
ट्री-कम्पैक्शन रहित सरल कोर
[F]; CMMN इवेंट लिसनस
केवल Flowable में हैं [F]
Core
Team
0
sangam-
core ,
runtimes
Fork,
flowable5
-* (83k
LOC) हटाना,
रीनेम
Operaton
Ports
Authz,
migration,
modification,
restart, batch,
history, cleanup,
SKIP LOCKED
Operaton इन सभी में मज़बूत
है [F]; ~27k लाइन्स + 3,659
टेस्ट [F]
Core
Team
1–2
sangam-
service
s ,
sangam-
securit
y
Operaton
कोड व टेस्ट को
स्पेसिफिकेशन
मानकर
Flowable
मॉडल पर री-
इम्प्लीमेंट
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 6 of 27

=== PAGE 7 ===
तत्व
(Element)
क्या (What)
क्यों — साक्ष्य (Why)
कौन
(Who)
कब
(Phas
e)
कहाँ
(Where)
कैसे (How)
DMN
Engine SPI
Drools DMN
डिफ़ॉल्ट, jDMN और
feel-scala वैकि
क
TCK: Drools 3388/3391,
DMN-Scala 2850/3391
[F]; Flowable में FEEL नहीं
[F]
Modelle
rs
1
sangam-
dmn
प्रति इंजन
एडॉप्टर; CI में
TCK रन
Job
Executor
SKIP LOCKED,
पोस्ट-कमिट हैंड-ऑफ,
प्रायोरिटीज़, फेंसिंंग
सभी DBs पर Flowable
डेडलॉक रिपोट्स [F]; River,
pg-boss, Hatchet में
प्रमाणित [F/V]
Ops
1
sangam-
executo
r
दो-चरणीय
एि
जिशन,
फिक्स्ड लॉक
क्रम ( C2 )
Outbox +
History
Projector
स्टेट और साइड-
इफेक्ट्स के लिए एक
ट्रांांजैक्शन; असिंंक्रोनस
हिस्ट्री
सिंंक्रोनस हिस्ट्री कमिट को
5.7× धीमा करती है [V];
प्रमाणित पैटन [F]
Ops,
Auditor
s
2
sangam-
services
/histor
y
कमिट-
विज़िबिलिटी गाड
के साथ पोलिंंग
रिले ( C3 )
Worker
Gateway
REST लॉन्ग-पोल,
gRPC स्ट्रीमिंंग, पुश,
ब्रोोकर
Zeebe ने gRPC स्ट्रीमिंंग से
एि
वेशन डिले घटाया [F]; नॉन-
Java उपेित [F]
S1
1
sangam-
gateway-
workers
लॉन्ग-पोल
फ़ॉलबैक के साथ
स्ट्रीमिंंग ( R9 )
SDKs
क्लाइंट्स + वकस +
टेस्ट इंजन
Temporal को 7 SDKs के
लिए Rust कोर बनाना पड़ा;
Camunda ने Go छोड़ा [F]
S1
1–3
sdks/
टियड SDKs
( R18 ):
जनरेटेड कोर +
एर्गोनॉमिक लेयर
Event
Gateway
Kafka, NATS,
RabbitMQ,
SQS…;
CloudEvents
Operaton में ब्रोोकर इंटीग्रेेशन
नहीं [F]; Flowable में इवेंट
रजिस्ट्री है [F]
S1, S4
3
sangam-
gateway-
events
Flowable इवेंट
रजिस्ट्री पोट;
आउटबाउंड के
लिए Outbox
Authorizati
on
Resources ×
Permissions +
ABAC
Flowable में शून्य [F]; C7 में
अधिक ग्रुुप्स पर SQL धीमा
( CAM-7441 , CAM-
5364 ) [F]
S5
2
sangam-
securit
y
डिनॉमलाइज़््ड
परमिशन टेबल के
साथ SQL
फ़िल्टरिंंग; सिंंगल
चेक के लिए
OPA/Cedar
Multi-
tenancy
Row-level + DB-
per-tenant
Flowable में दोनों हैं [F]; टेनेंट
रजिस्ट्रेेशन पर स्कीमा लॉक
डेडलॉक ( #4215 ) [F]
S9
2
sangam-
securit
y ,
storage
स्कीमा लॉक के
बाहर टेनेंट
रजिस्ट्रेेशन
Sandbox
WASM + JS
Isolates
Rhino सैंडबॉक्स एस्केप:
CVE-2025-0982 ,
CVSS 10 [F]
S5
3
sangam-
sandbox
Chicory/End
ive WASM;
GraalJS
isolate; कोई
Rhino नहीं
UI
Cockpit, Tasklist,
Admin, Modeler
Flowable में कोई UI नहीं
[F]; Operaton में
webapps-neo (Preact)
है [F]
S2–S4
2–3
ui/
webapps-
neo का फोक;
bpmn-js /
dmn-js /
form-js
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 7 of 27

=== PAGE 8 ===
तत्व
(Element)
क्या (What)
क्यों — साक्ष्य (Why)
कौन
(Who)
कब
(Phas
e)
कहाँ
(Where)
कैसे (How)
AI
agentTas
k + MCP
ड्यूूरेबल जनल के साथ
A2A स्टेप; MCP
सवर
ड्यूूरेबल जनल प्रमाणित
(Temporal, Restate) [V];
MCP `CVE-2025-6514`
[F]
S8
3
runtime
extension
प्रति स्टेप जनल;
डिफ़ॉल्ट रूप से
Read-only
MCP
XES / OCEL
Export
प्रोसेस-माइनिंंग लॉग्स
pm4py दोनों को सपोट करता
है [F]; कमर्शियल OCEL सपोट
अस्पष्ट [U]
S10
3
history
projector
XES प्राइमरी;
OCEL का
pm4py से CI
में सत्यापन
CMMN
Module
संपूण CMMN,
आइसोलेटेड
कोई नया स्पेक नहीं [F];
Flowable CMMN अधिक
समृद्ध [F]
S2
3
sangam-
runtime-
cmmn
प्लग-इन मॉड्यूूल;
ArchUnit
आइसोलेशन
Observabili
ty
OTel ट्रेेसेज,
Micrometer
Operaton में
OTel/Micrometer नहीं
[F]; OTel ओवरहेड ~5%
p95 (Elastic) [V]
S4
0
sangam-
observab
ility
Async काय के
लिए Span
Links ( R13 )
Schema
Mgmt
Liquibase, टियड
DBs
Oracle/DB2 पर
Liquibase लॉक रेस
( #1584 ) [F]; Camunda
ने Galera छोड़ा [F]
S4
0
sangam-
storage-
jdbc
एकल माइग्रेेशन
जॉब; DB टियस
( R12 ,
R17 )
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 8 of 27

=== PAGE 9 ===
2.4 प्रस्तावित 22-मॉड्यूूल लेआउट और प्रमुख अपस्ट्रीम स्रोोत फ़ाइलें (DESIGN.md §4.1, §13)
sangam/
├── sangam-api/                 पि
क Java API (services, queries) — कोई भी impl क्लास 
बाहर लीक नहीं होगी
├── sangam-model/               BpmnModel, CmmnModel, DmnModel, fluent builders
├── sangam-parsers/             bpmn, cmmn, dmn, legacy-namespace importers 
(camunda:, operaton:, flowable:)
├── sangam-core/                command pipeline, CommandContext, agenda base, 
immutable EngineConfig SPI
├── sangam-runtime-bpmn/        BPMN agenda + activity behaviors
├── sangam-runtime-cmmn/        CMMN agenda + behaviors (वैकि
क प्लग-इन, शून्य कोर निभरता)
├── sangam-dmn/                 DecisionEngine SPI + adapters: Drools DMN 
(default), jDMN, feel-scala
├── sangam-feel/                BPMN/CMMN एक्सप्रेेशन्स के लिए पिन किया गया FEEL इंजन + custom-
function SPI
├── sangam-services/            job, task, variable, identitylink, entitylink, 
eventsub, batch, history
├── sangam-security/            authorization (resources/permissions + ABAC), 
identity SPI, OIDC/LDAP
├── sangam-storage-jdbc/        MyBatis mappings, per-DB dialects, Liquibase 
changelog
├── sangam-executor/            job acquisition (SKIP LOCKED), worker dispatch, 
timers, retries, incidents, fencing
├── sangam-gateway-workers/     pull (REST long-poll / gRPC stream), push 
(HTTP/serverless), broker
├── sangam-gateway-events/      Kafka, NATS, RabbitMQ, JMS, SQS/SNS, Azure SB, 
Pub/Sub, MQTT, webhooks
├── sangam-sandbox/             सुरित WASM (Chicory/Endive) + GraalJS isolate ि
प्ट 
रनटाइम (No Rhino)
├── sangam-observability/       OpenTelemetry, Micrometer, HealthService
├── sangam-server/              स्टैंडअलोन सवर (REST + gRPC + UI), Docker इमेज, लाइट नेटिव 
इमेज प्रोफ़ाइल
├── sangam-spring-boot-starter/ Spring Boot 4 ऐप्स के लिए एम्बेडेड मोड
├── sangam-quarkus-extension/   Quarkus ऐप्स के लिए एम्बेडेड मोड
├── sangam-test/                JUnit 5 एक्सटेंशन, AssertJ असशन्स, Testcontainers प्रोफ़ाइल्स
├── proto/ + openapi/ + asyncapi/   भाषा-तटस्थ कॉन्ट्रैैक्ट्स (Source of Truth)
├── sdks/                       Tier 1: java, dotnet, php, node-ts, python, go | 
Tier 2: rust, kotlin
├── ui/                         cockpit, tasklist, admin, modeler (Preact, webapps-
neo आधारित)
└── deploy/                     helm chart, k8s operator, docker-compose, terraform 
examples
प्रमुख अपस्ट्रीम फ़ाइलें जहाँ से विकास प्रारंभ होगा:
Flowable (Base): DefaultFlowableEngineAgenda.java व एजेंडा ऑपरेशन्स, CommandInvoker.java ,
AbstractEngineConfiguration.java (साफ़ SPI से बदलने हेतु), flowable-job-
service/.../asyncexecutor/* , flowable-cmmn-engine/.../agenda/operation/* , flowable-
event-registry/.../pipeline/* और flowable-external-job-rest/* ।
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 9 of 27

=== PAGE 10 ===
Operaton (Ports): impl/cfg/auth/* , AuthorizationCheck* , Authorization.xml
(Authorization); DefaultDmnEngineConfiguration.java , feel-api/* , feel-scala/* (DMN
पैटन); impl/migration/* , ProcessInstanceModification* , RestartProcessInstances*
(Dynamic changes); impl/batch/* ; impl/history/* , historycleanup/* ; Job.xml ( SKIP
LOCKED डायलेक्ट वेरिएंट्स); db/liquibase/* ; webapps-neo/* ; और test-
utils/{assert,testcontainers,archunit}/* ।
3. भूमिका 2: स्टैंडड्स, पॉलीग्लॉट और AI प्लेटफ़ॉम विशेषज्ञ का मूल्यांकन
3.1 एलिमेंट-लेवल स्टैंडड्स गैप एनालिसिस (BPMN 2.0.2, DMN 1.6/1.7, CMMN 1.1)
दोनों कोडबेस के पास हैंडलस, बिहेवियस और वैलिडेटस की जाँच से पता चला कि Flowable बेस में Operaton की तुलना में 11
प्रमुख BPMN गैप्स हैं जिन्हें R34 के तहत अनिवाय रूप से पोट किया जाएगा, और 6 ऐसे गैप्स हैं जो दोनों इंजनों में अधूरे हैं:
श्रेेणी
(Category)
गैप / फीचर (Missing Element or Capability)
Operaton साक्ष्य (Evidence
[F])
निणय
(Decision)
Flowable
Base Gap
#1
Link intermediate catch / throw events
IntermediateCatchLi
nkEventActivityBehav
ior
Must: Port in
Phase 1
Flowable
Base Gap
#2
Signal end event, message end event,
message intermediate throw
BpmnParse service-task-
like events
Must: Port in
Phase 1
Flowable
Base Gap
#3
Compensation end event; compensation
event sub-process
parseCompensationEv
entSubprocess
Must: Port in
Phase 1
Flowable
Base Gap
#4
Process-level conditional start; वेरिएबल बदलने पर
ऑटोमैटिक कंडीशन मूल्यांकन
ConditionalEventBeh
avior ,
createConditionEval
uation()
Must: Port in
Phase 1
Flowable
Base Gap
#5
किसी भी एि
विटी पर I/O mapping
( inputOutput ); कनेक्टर फ्रेेमवक
parseActivityInputO
utput , connect plugin
Must: पॉलीग्लॉट
वकस व C7 इम्पोट
हेतु
Flowable
Base Gap
#6
डेलिगेट्स के साथ Send task; messageRef द्वाारा
कोरिलेटेड Receive task
BpmnParse
Must: Port in
Phase 1
Flowable
Base Gap
#7
decisionRef और रिज़ल्ट मैपस के साथ DMN
Business-rule task
DmnBusinessRuleTask
ActivityBehavior
(Flowable का टास्क Drools
रूल्स चलाता है, DMN नहीं)
Must: Port in
Phase 1
Flowable
Base Gap
#8
Multi-instance बॉडी और इनर एि
विटी पर अलग
asyncBefore/asyncAfter ; retry cycles;
job priorities; incidents
Operaton job / MI
parsing
Must: Port in
Phase 1
Flowable
Base Gap
#9
External-task API सेमैंटिक्स (topics, वेरिएबल्स के साथ
fetch-and-lock, extend lock, BPMN error)
ExternalTaskActivit
yBehavior
Must: पॉलीग्लॉट
वकर API का
आधार
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 10 of 27

=== PAGE 11 ===
श्रेेणी
(Category)
गैप / फीचर (Missing Element or Capability)
Operaton साक्ष्य (Evidence
[F])
निणय
(Decision)
Flowable
Base Gap
#10
Process instance modification, restart, async
correlation, condition-evaluation API
Operaton runtime/history
services
Must: Phase 2
Flowable
Base Gap
#11
पासर में camunda: / operaton: एक्सटेंशन
एलिमेंट्स का समथन
Flowable केवल सादे
camunda: एट्रिब्यूट्स पढ़ता है
( BpmnXMLUtil l.338)
Should: UC6
माइग्रेेशन के लिए
Missing in
Both #1
Complex gateway (दोनों में पास होता है, पर रनटाइम पर
अनदेखा किया जाता है)
Both engines
Should: Phase
3
Missing in
Both #2
instantiate / parallel instantiate के साथ
Event-based gateway
Both engines
Could
Missing in
Both #3
Multiple / parallel-multiple start, intermediate
और boundary events
Both engines
Should: Phase
3
Missing in
Both #4
Standard loop characteristics
Both engines
Should: जोड़ने में
सरल
Missing in
Both #5
रनटाइम पर Data stores और data input/output
associations
Both engines
Could: JSON
वेरिएबल्स पर्याप्त हैं
Missing in
Both #6
पूल्स के बीच एक्जीक्यूटेबल मैसेज फ़््लो (डायग्रााम के माध्यम से
कोरिलेशन)
Both engines
Could: API
कोरिलेशन डिफ़ॉल्ट
रहेगा
3.2 DMN 1.6 / 1.7 Beta नीति और CMMN "Retire-Safe" रणनीति
DMN गैप्स की पूर्ति: दोनों अपस्ट्रीम इंजनों में Boxed expressions (context, invocation, list, relation,
function definition), BKM, item-definition टाइप चेकिंंग और DMN 1.6 का अभाव है [F]। Apache KIE
Drools DMN 10.2.0 (TCK स्कोर 3388/3391 , कन्फॉर्मेंंस लेवल 3, DMN 1.1–1.6 सपोट) को डिफ़ॉल्ट चुनने से ये
सभी कमियाँ अपने-आप दूर हो जाती हैं। एडॉप्टर में Operaton की इि
सिट रिक्वायड-डिसीजन चेनिंंग और Flowable की
PRIORITY / OUTPUT ORDER हिट पॉलिसीज़ व डिसीजन सर्विसेज सुनिि
त की जाएँगी।
DMN 1.7 Beta 1 नीति (DESIGN.md §3.3c): आधिकारिक OMG पेज पर DMN 1.6 ( formal/25-12-02 ,
सितंबर 2026) फॉमल मानक है, जबकि DMN 1.7 Beta 1 ( dtc/26-06-01 , XSD dtc/26-06-03 ) सूचनात्मक रूप
से प्रकाशित है। सावजनिक ट्रैैकर में 1.6 से 1.7 में टाले गए आइटम हैं: ठोस integer और real प्रकार जहाँ number
एब्स्ट्रैैक्ट बन जाता है ( DMN16-15 ), फर्स्टट-क्लास FEEL वैल्यू के रूप में unary tests ( DMN16-7 ), डिसीजन मॉडल के अंदर
टेस्ट केसेस ( DMN16-6 ), और ग्रेे-बॉक्स डिसीजन-सर्विस निभरताएं ( DMN16-77 )। संगम अभी अनुपालन के लिए DMN 1.6
formal को लक्ष्य बनाएगा, dmn.preview-1.7 फ़््लैग के पीछे 1.7 नेमस्पेस को प्रीव्यू देगा, और DMN16-6 (मॉडल के अंदर
एम्बेडेड टेस्ट केसेस) को डिप्लॉयमेंट वैलिडेशन एक्सटेंशन के रूप में जल्दी अपनाएगा।
CMMN 1.1 की ि
ति: Flowable में टाइमर, यूज़र और जेनेरिक इवेंट लिसनस हैं (जो Operaton के
DefaultCmmnElementHandlerRegistry में कमेंट-आउट हैं [F])। दोनों इंजनों में Discretionary items /
planning tables और Case file items गायब हैं जिन्हें Could श्रेेणी में रखा गया है। CMMN को sangam-
runtime-cmmn प्लग-इन में अलग टेबल ग्रुुप और CMMN → BPMN कन्वटर के साथ रखा गया है ताकि भविष्य में इसे बिना
सिस्टम तोड़े रिटायर किया जा सके।
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 11 of 27

=== PAGE 12 ===
3.3 YAML DSLs और Durable Execution (Temporal) से अपनाए गए 10 सबक (L1–L10)
प्रोजेक्ट ओनर के निणय (2026-11-04) के अनुसार CNCF Serverless Workflow (YAML) और कोड-फर्स्टट SDK DSL को
स्कोप से हटा दिया गया है ताकि दो अलग-अलग इंजन न बनाने पड़ें। इसके बजाय उनके 10 सर्वोत्तम फीचस सीधे BPMN इंजन में
शामिल किए गए हैं:
ID
सीखा गया सबक (Lesson)
प्रेेरणा स्रोोत
संगम में कार्यान्वयन (Sangam Feature)
L1
कोड की तरह वकफ़््लो का PR रिव्यू
Git में YAML DSLs
BPMN Semantic Diff & Review Tool: CLI + UI
जो कच्चे XML डिफ़ के बजाय जोड़े/हटाए/बदले गए एलिमेंट्स, फ़््लो
और एक्सप्रेेशन्स दिखाता है।
L2
रेफरेंस द्वाारा सीधे API कॉल
Serverless
Workflow call
सर्विस टास्क सीधे OpenAPI operationId ,
AsyncAPI ऑपरेशन या gRPC मेथड का नाम दे सकते हैं और
इनपुट/आउटपुट कॉन्ट्रैैक्ट से वैलिडेट होते हैं।
L3
पढ़ने योग्य व्यवहारिक कन्फॉर्मेंंस
टेस्ट
Serverless
Workflow CTK
कोड टेस्ट्स के साथ-साथ कोर इंजन सेमैंटिक्स के लिए Gherkin
परिदृश्य।
L4
डेवलपस के लिए आसान यूनिट
टेस्टिंंग
Temporal SDK Test
Server
हर Tier-1 SDK में टाइम-ि
पिंंग (टाइमस के लिए) और स्टेट
असशन्स के साथ इन-मेमोरी / Testcontainers इंजन।
L5
रिप्ले द्वाारा सुरित मॉडल अपग्रेेड
Temporal Replay
Tests
Replay Testing: नए मॉडल वजन को डिप्लॉय या माइग्रेेट
करने से पहले पुराने रिकॉर्डेड इवेंट लॉग्स को उस पर चलाकर अंतर
रिपोट करना।
L6
रनिंंग इंस्टेंस से सीधे संवाद
Temporal Queries /
Updates
Query & Update Handlers: मॉडल में नामित रीड-
ओनली क्वेरीज़ (FEEL आधारित) और वैलिडेटेड अपडेट्स घोषित
करने की सुविधा।
L7
बहुत लंबे चलने वाले इंस्टेंसेज
Temporal
Continue-as-New
Continue-as-New: एक इंस्टेंस अपनी आवश्यक स्टेट आगे
बढ़ाकर और लिंंक रखकर खुद को बंद कर नया इंस्टेंस शुरू कर
सकता है।
L8
ड्यूूरेबल AI एजेंट लूप्स
Temporal Agent
Use Case
agentTask के अंदर Durable Agent Journal: हर
LLM और टूल कॉल अपने परिणाम के साथ रिकॉड होता है; क्रैश
के बाद बिना दोबारा कॉल किए वहीं से रिज़््यूम होता है।
L9
प्रति-इंस्टेंस संपूण इवेंट हिस्ट्री
Temporal Event
History
Outbox से पोषित Per-instance Append-only
Event Log जो ऑडिट, टाइम-ट्रैैवल डीबगिंंग, रिप्ले (L5) और
XES/OCEL एक्सपोट में काम आता है।
L10
मौजूदा सिस्टम्स के साथ सह-
अि
त्व
Polyglot Enterprise
Temporal Interop Connector: संगम से Temporal
वकफ़््लो शुरू करने व परिणाम का इंतज़ार करने और Temporal
से संगम प्रोसेस शुरू/कोरिलेट करने का एडॉप्टर।
4. भूमिका 4: मार्केट एवं प्रोोडक्ट स्ट्रैैटेजिस्ट — 14 उत्पादों की तुलना और 4-स्तरीय SWOT
4.1 बाज़ार के 14 प्रमुख उत्पादों के साथ तुलना (ANALYSIS.md §4)
उत्पाद
(Product)
मॉडल
(Model)
स्टोरेज (DB /
Storage)
भाषा पहुँच
(Languages)
लाइसेंस (Licence)
प्रकाशित प्रदशन
(Published Perf)
Camunda 8
BPMN, DMN
(No CMMN)
Raft log +
RocksDB; सेकेंडरी
ES/OS या 8.9 से
RDBMS
Java + Spring
आधिकारिक; Go
डिप्रिकेटेड
Camunda
License: प्रोडक्शन
के लिए पेड लाइसेंस
अनिवाय [F]
3 ब्रोोकस पर ~240 PI/s, 6
पर 480 (32 KiB पेलोड)
[V]
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 12 of 27

=== PAGE 13 ===
उत्पाद
(Product)
मॉडल
(Model)
स्टोरेज (DB /
Storage)
भाषा पहुँच
(Languages)
लाइसेंस (Licence)
प्रकाशित प्रदशन
(Published Perf)
Temporal
Code
(Replay)
Cassandra / PG /
MySQL
7 आधिकारिक
SDKs
MIT
Cloud टॉप टियर पर
>150k actions/s [V];
सेल्फ-होस्टेड अप्रकाशित
AWS Step
Functions
JSON (ASL)
Managed
कोई भी AWS
SDK
Proprietary
प्रति अकाउंट थ्रॉॉटल्ड; 256
KiB पेलोड व 25k-इवेंट
हिस्ट्री सीमा [F]
Conductor /
Orkes
JSON DSL
Redis / PG /
MySQL /
Cassandra + ES
8 SDKs
Apache 2.0 +
कमर्शियल
लैपटॉप पर 210
workflows/s, 1,450
tasks/s [V]
Kestra
YAML
PG / MySQL
(OSS)
ि
प्ट के रूप में
कोई भी भाषा
Apache 2.0;
RBAC और
Multi-tenancy
पेड हैं [F]
सावजनिक बेंचमाक नहीं मिला
[U]
n8n
Visual Low-
code
PG / MySQL /
SQLite + Redis
JS / Python
नोड्स
Fair-code (Non-
OSI) [F]
प्रति इंस्टेंस 220 exec/s
[V]; समय के साथ धीमा
पड़ता है [F/3P]
Apache
Airflow
Python DAG
PG / MySQL
Python
Apache 2.0
बैच-केंद्रित; मिनटों में स्टाट
होता है [F]
Apache KIE
BPMN,
DMN, SW
RDBMS
Java /
Quarkus
Apache 2.0
(Incubating)
सावजनिक बेंचमाक नहीं मिला
[U]
Flowable
OSS
BPMN,
CMMN,
DMN (JUEL)
8+ RDBMS
Java; REST
Apache 2.0; UI
और कंट्रोल कमर्शियल
हैं [F]
16 vCPU पर हिस्ट्री सहित
सरल प्रोसेस 6,605 PI/s
(2018) [V]
Operaton
BPMN,
CMMN,
DMN (FEEL)
7 RDBMS
Java; REST
Apache 2.0 (कोई
पेड संस्करण नहीं)
C7 गाइड: लैपटॉप पर 100–
500 PI/s [V]
Restate
Code
(Journal)
Own Log
7 SDKs
BSL 1.1 [F]
अप्रकाशित
Inngest
Code
Managed / Self
TS, Python,
Go
SSPL → बाद में
Apache
तुलनीय नहीं
Windmill
Scripts +
Flows
केवल PostgreSQL
10+ ि
प्ट
भाषाएं
AGPL + प्रोप्राइटरी
EE
अपने बेंचमाक में अग्रणी [V]
Hatchet /
DBOS
Code
PostgreSQL
कई भाषाएं
MIT
Hatchet: सिंंगल नोड 10k
tasks/s; 8-vCPU RDS
पर 2,000 runs/s [V]
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 13 of 27

=== PAGE 14 ===
4.2 चारों स्तरों पर SWOT विश्लेषण (Architecture, Functional, Technical, Overall)
स्तर (Level)
Strengths (ताकत)
Weaknesses
(कमज़ोरियाँ)
Opportunities
(अवसर)
Threats (खतरे)
1.
Architectur
e
ट्री-कम्पैक्शन रहित एजेंडा
कोर [F]; फर्स्टट-क्लास
ACT_RU_ACTINST
[F]; Hexagonal Ports
(D1); Outbox से हिस्ट्री/
इवेंट्स अलग; CMMN प्लग-
इन आइसोलेशन।
विशाल विरासत कोडबेस
(~520k लाइन्स) [F]; सिंंगल
RDBMS थ्रूूपुट सीमा [F];
रिले/प्रोजेक्टर/वेक-अप बस
जैसे अधिक मूविंंग पाट्स [I];
अलग मॉडल पर री-
इम्प्लीमेंटेशन [I]।
Storage SPI भविष्य में
पार्टीशन्ड बैकएंड का रास्ता
खुला रखता है; SaaS के
लिए DB-per-tenant
हॉरिजॉन्टल स्केल देता है
[I]; gRPC स्ट्रीमिंंग
Zeebe में प्रमाणित है [F]।
Camunda 8.9 के
RDBMS स्टोरेज से
आर्किटेक्चरल अंतर घटा है
[F]; अपस्ट्रीम Flowable
से मज लागत समय के साथ
बढ़ेगी [I]; पूरे इंजन के लिए
Native Image कठिन है
[F/I]।
2.
Functional
दोनों इंजनों के फीचस का
यूनियन [F]; Drools से
लेवल-3 DMN [F];
Migration +
Modification +
Dynamic Injection +
Replay Testing; ड्यूूरेबल
जनल वाला agentTask
[I]।
पैरिटी से पहले 11 BPMN
गैप्स पोट करने होंगे (§3.1);
CMMN की मांग घट रही है
[F]; दोनों अपस्ट्रीम में
Complex Gateway
गायब है [F]; कोड-फर्स्टट DX
में Temporal आगे है [F]।
Camunda 7 टीमों को
मुफ़््त और कम्पैटिबल घर
चाहिए [F]; ऑडिट-प्रधान
उद्योगों के लिए
XES/OCEL प्रोसेस-
माइनिंंग एक्सपोट एक बड़ा
अंतर है [I]।
A2A और MCP नए हैं
और तेज़ी से बदलते हैं
जिससे रीवक का जोखिम है
[F]; उपयोगकर्ता n8n जैसे
सैकड़ों लो-कोड कनेक्टस
की अपेक्षा कर सकते हैं
[F]।
3. Technical
प्रमाणित बिल्डिंंग ब्लॉक्स
(SKIP LOCKED,
Outbox, Liquibase,
OTel, gRPC) [F]; JSON-
only वेरिएबल्स पूरे
Deserialization CVE
वग को मिटाते हैं [F]; एकल
हाडन्ड XML फ़ैक्ट्री [F]।
Oracle/MySQL पर
SKIP LOCKED और
Liquibase लॉक्स के लिए
पर-DB काम ज़रूरी है [F];
8 डेटाबेस टेस्ट लागत बढ़ाते
हैं [F/I]; Drools से
SBOM डिपेंडेंसी ट्री बड़ा
होता है [I]।
JDK 25 वचुअल थ्रेेड्स में
पिनिंंग समाप्त (JEP 491)
[F]; JVM पर WASM
(Chicory →
Bytecode Alliance के
तहत Endive) परिपक्व हो
रहा है [F]।
गलत उपयोग होने पर
NOTIFY का ग्लोबल
कमिट लॉक [F]; सैंडबॉक्स
एस्केप ( CVE-2025-
0982 ) और MCP हमले
( CVE-2025-6514 )
[F]।
4. Overall
बाज़ार में स्पष्ट खाली स्थान
[F]; 100% Apache 2.0;
स्टैंडड्स-कम्प्लीट; मुफ़््त
आधुनिक UI; अधिकांश
जोखिमों के निवारक उपाय
पहले से तय हैं।
एक टीम के लिए स्कोप बहुत
बड़ा है (143–220 पसन-
मंथ्स) [I]; अभी कोई फंडेड
कंपनी या बड़ी कम्युनिटी पीछे
नहीं है [F]।
Camunda 7 EOL; बिना
UI वाला Flowable
OSS; और AI एजेंट
वकफ़््लो की बढ़ती मांग।
"सब कुछ एक साथ बनाने"
के प्रयास में कुछ भी पूरा न
होने का जोखिम; फंडेड
प्रतिस्पर्धी तेज़ी से आगे बढ़
सकते हैं [I]।
4.3 सात आयामों में तुलनात्मक निणय ("Will Sangam Be Better?" — ANALYSIS.md §18.1)
आयाम
(Dimension)
बनाम Flowable OSS
बनाम Operaton
बनाम Market Leaders एवं अंतिम निणय
1. Performance
समान कोर; बेहतर एि
जिशन
(SKIP LOCKED, हैंड-ऑफ)
और Outbox हिस्ट्री [I]।
बेहतर: एजेंडा कोर, कोई ट्री-
कम्पैक्शन नहीं, डिफ़ॉल्ट SKIP
LOCKED [I]।
अति-विशाल स्केल पर Zeebe/Temporal से
आगे नहीं [F/I]; एक DB पर छोटे/मध्यम स्केल
में दोनों बेस से बेहतर।
2. Functionality
& Standards
बेहतर: Operaton इवेंट्स,
I/O मैपिंंग, Authorization
और FEEL DMN जुड़ते हैं
[F]।
बेहतर: Ad-hoc सब-प्रोसेस,
CMMN लिसनस, इवेंट
रजिस्ट्री और DMN 1.6 जुड़ते
हैं [F]।
BPMN+CMMN+DMN+AI का अितीय
संयोजन [F] → स्पष्ट रूप से सवश्रेेष्ठ
(Better)।
3. Database
Support
समान विस्तार, गुणवत्ता के लिए
टियड।
Operaton से अधिक
व्यापक।
Camunda 8, Temporal और
Windmill से अधिक व्यापक [F] → बेहतर
(Better)।
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 14 of 27

=== PAGE 15 ===
आयाम
(Dimension)
बनाम Flowable OSS
बनाम Operaton
बनाम Market Leaders एवं अंतिम निणय
4. Tech-Stack
Reach
कहीं बेहतर: केवल REST के
मुकाबले Tier-1 SDKs [F]।
कहीं बेहतर: Operaton में
केवल Java क्लाइंट है [F]।
SDK संख्या में Temporal/Conductor के
समकक्ष; दोनों बेस इंजनों से बहुत आगे।
5. Ease of Use
बेहतर: संगम में पूरा UI शामिल
है [F]।
बेहतर: AngularJS के बजाय
आधुनिक Preact UI [F]।
सरल ऑटोमेशन में n8n/Kestra जितना
आसान नहीं [F], पर दोनों बेस इंजनों से बेहतर।
6. Modern UI
बेहतर (Flowable OSS में
शून्य UI है)।
बेहतर ( webapps-neo +
Web Modeler)।
Camunda 8 के समकक्ष (पर Camunda
का UI पेड लाइसेंस में है) [F] → बेहतर।
7. Plug & Play
बेहतर: एक कंटेनर +
Postgres और Helm चाट।
Operaton Run के समान
सरल।
Kestra/Conductor के समान;
Camunda 8 सेल्फ-मैनेज्ड से कहीं सरल
[F/I] → अच्छा (Good)।
5. तकनीकी साक्ष्य (18 Choices), इंटरफ़ेस टकराव (C1–C17) और ATAM ट्रेेड-ऑफ
5.1 18 तकनीकी चुनावों की साक्ष्य समीक्षा (ANALYSIS.md §6)
#
तकनीकी चुनाव (Technical
Choice)
निणय (Verdict)
शीष जोखिम और साक्ष्य (Top Risk &
Evidence)
सुधार
(Revision)
1
SKIP LOCKED क्यू;
NOTIFY वेक-अप
शर्तोंं के साथ प्रमाणित;
ट्रांांजैक्शन के अंदर NOTIFY
Risky है
कमिट पर NOTIFY क्लस्टर-वाइड लॉक लेता है
(~2.9k → बैच करने पर 60k writes/s,
DBOS 2026) [F]; पुराने xmin से PG ब्लोट
[F]; MySQL गैप लॉक्स (bug 91209) [F];
Oracle FETCH FIRST + FOR UPDATE
समस्या [F]।
R1 , R2
2
रूट-इंस्टेंस रो लॉक
शर्तोंं के साथ प्रमाणित
MSSQL, Oracle, PG और MySQL पर
Flowable इंस्टेंस-लॉक डेडलॉक (फोरम
थ्रेेड्स) [F]।
R3
3
Outbox + रिले
शर्तोंं के साथ प्रमाणित
सादा id > last पोलिंंग देर से कमिट हुए
ट्रांांजैक्शन छोड़ देता है; डुप्लीकेट्स [F]।
R4
4
बड़े स्केल पर Flowable
एक्जीक्यूटर
शर्तोंं के साथ प्रमाणित
केवल वेंडर के अपने नंबर उपलब्ध हैं (4–6k
jobs/s) [V]।
R22
5
एम्बेडेड Drools DMN
JVM पर प्रमाणित; Native
Image में अप्रमाणित
Native के लिए बिल्ड-टाइम कोडजेन आवश्यक
[F/I]; भारी डिपेंडेंसी ट्री।
R8
6
gRPC स्ट्रीमिंंग
शर्तोंं के साथ प्रमाणित
(Zeebe)
L7 प्रॉक्सी, कनेक्शन पिनिंंग, अधिकतम मैसेज
आकार, अनाथ लॉक्स [F]।
R9
7
WASM / JS सैंडबॉक्स
Chicory/GraalJS
प्रमाणित; Rhino Risky
CVE-2025-0982 (CVSS 10 सैंडबॉक्स
एस्केप) [F]।
R6
8
हर जगह FEEL
शर्तोंं के साथ प्रमाणित
चुपचाप null लौटाना; feel-scala
वजन्स के बीच व्यवहार बदलना ( #582 ) [F]।
R10
9
केवल JSON वेरिएबल्स
पूणतः प्रमाणित (Proven)
JSON DoS (गहराई और आकार) [I]।
R11
10
Liquibase स्कीमा प्रबंधन
शर्तोंं के साथ प्रमाणित
Oracle/DB2 पर लॉक-टेबल रेस
( #1584 ); अटके हुए लॉक्स [F]।
R12
11
Async काय में
OpenTelemetry
शर्तोंं के साथ प्रमाणित
गलत parent/link मॉडल से विशालकाय
ट्रेेसेज बनना [I]।
R13
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 15 of 27

=== PAGE 16 ===
#
तकनीकी चुनाव (Technical
Choice)
निणय (Verdict)
शीष जोखिम और साक्ष्य (Top Risk &
Evidence)
सुधार
(Revision)
12
वचुअल थ्रेेड्स (Virtual
Threads)
JDK 24+ पर प्रमाणित; JDK
21 पर Risky
JEP 491 से पहले synchronized
ब्लॉक पर पिनिंंग [F]।
R5 (JDK 25)
13
GraalVM Native Image
Risky
MyBatis / OGNL / रनटाइम डिप्लॉयमेंट
बनाम क्लोज़््ड-वर्ल्डड धारणा [F/I]।
R7
14
A2A / MCP / स्टेप जनल
A2A/MCP Risky; जनल
प्रमाणित
टूल पॉइज़निंंग, प्रॉम्प्ट इंजेक्शन, CVE-
2025-6514 [F]।
R14
15
XES / OCEL 2.0 एक्सपोट
XES प्रमाणित; OCEL शर्तोंं
के साथ
कमर्शियल टूल्स में OCEL कंज्यूमर सपोट अभी
सीमित है [F/U]।
R15
16
CloudEvents / AsyncAPI
3
डॉक्स के लिए प्रमाणित;
कोडजेन Risky
AsyncAPI कोड जनरेटर में कमियाँ (जैसे
allOf ) [F]।
R16
17
आठ डेटाबेस का समथन
Risky
मैट्रिक्स लागत; CockroachDB में 40001
पर रिट्राई अनिवाय [F]।
R17
18
SQL के अंदर
Authorization
शर्तोंं के साथ प्रमाणित
क्वेरी 1s से 30s तक धीमी ( CAM-7441 );
>280 ग्रुुप्स पर धीमापन ( CAM-5364 )
[F]।
R19
5.2 17 इंटरफ़ेस टकराव (Pairwise Conflicts C1–C17 — ANALYSIS.md §7)
जब अलग-अलग सही दिखने वाले कम्पोनेंट्स को एक साथ जोड़ा जाता है, तो उनके बीच N2 टकराव पैदा होते हैं। ANALYSIS.md ने
ऐसे 17 टकराव पकड़कर उनके लिए बाध्यकारी नियम तय किए हैं:
ID
टकराने वाले घटक (Conflicting
Components)
टकराव का प्रभाव (Conflict)
समाधान नियम (Architectural
Resolution Rule)
C1
Wake-up bus × Command
transaction
कमिट पर NOTIFY सभी जॉब-बनाने
वाले कमिट्स को सीरियलाइज़ कर देता
है [F]।
वेक-अप केवल Outbox रिले से बैच में भेजे जाएँगे;
पोलिंंग फ़ॉलबैक रहेगा ( R1 )।
C2
Root-instance lock × Job
acquisition
विपरीत लॉक क्रम होने पर तुरंत
डेडलॉक होता है [F/I]।
ग्लोबल लॉक क्रम: root instance →
executions → jobs । एि
जिशन पहले
कमिट करेगा, फिर एक्जीक्यूशन नए ट्रांांजैक्शन में
इंस्टेंस लॉक लेगा + NOWAIT /timeout
( R3 )।
C3
Outbox relay × History/UI
ताज़गी × Vacuum
धीमा रिले हिस्ट्री व UI को पीछे रखता
है; Outbox रोज़ से टेबल ब्लोट होता
है [I]।
रिले-लैग SLO और अलट; पार्टीशन्ड Outbox
की प्रूूनिंंग; लाइव स्टेट के लिए UI सीधे रनटाइम
टेबल्स पढ़ेगा ( R4 )।
C4
gRPC streaming × Executor
partitions × K8s LB
स्ट्रीम ऐसे नोड पर जुड़ता है जो उस
पार्टीशन का मालिक नहीं है; स्ट्रीम्स एक
पॉड से चिपक जाते हैं [F/I]।
कोई भी नोड कोई भी जॉब डिस्पैच कर सकता है
(पार्टीशन केवल टाइमस और एि
जिशन को गाइड
करते हैं); MAX_CONNECTION_AGE ; स्ट्रीम
बंद होने पर अन-एक्नॉलेज्ड जॉब्स अनलॉक होंगे
( R9 )।
C5
Native image × MyBatis ×
Drools × Runtime deploy
GraalVM की क्लोज़््ड-वर्ल्डड सीमाएं
डायनामिक क्लासलोडिंंग रोकती हैं
[F/I]।
Native Image केवल "Worker Gateway
/ Light" प्रोफ़ाइल के लिए; पूरा इंजन JVM पर
चलेगा ( R7 )।
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 16 of 27

=== PAGE 17 ===
ID
टकराने वाले घटक (Conflicting
Components)
टकराव का प्रभाव (Conflict)
समाधान नियम (Architectural
Resolution Rule)
C6
कई FEEL इंजन × अपग्रेेड के बाद
व्यवहार
Drools, jDMN और feel-scala
एक ही कंडीशन का अलग मूल्यांकन कर
सकते हैं [I]।
BPMN/CMMN एक्सप्रेेशन्स के लिए एक ही
FEEL इंजन, प्रति मॉडल वजन पिन किया गया;
Strict-null मोड ( R10 )।
C7
Virtual threads × JDBC
connection pool
असीमित वचुअल थ्रेेड्स छोटे DB पूल
के आगे कतार लगा देते हैं [I]।
प्रति जॉब प्रकार सेमाफोर (Semaphore); पूल
साइज़िंंग नियम; JFR पिनिंंग जांच।
C8
SQL Authorization × ABAC ×
Tenancy × 8 Dialects
जटिल जॉइन्स से क्वेरी-प्लान विस्फोट
होता है [F/I]।
डिनॉमलाइज़््ड परमिशन टेबल; ABAC केवल
सिंंगल-रिसोस चेक के लिए; हर Tier-1 DB पर
क्वेरी-प्लान टेस्ट ( R19 )।
C9
MCP server × Authorization
प्रॉम्प्ट-इंजेक्टेड LLM यूज़र के
अधिकारों से घातक बदलाव कर सकता
है [F]।
MCP डिफ़ॉल्ट रूप से Read-only; राइट टूल्स
अप्रूूवल टास्क बनाएँगे; टूल आउटपुट को Tainted
माना जाएगा ( R14 )।
C10
OpenTelemetry × "No
ThreadLocal in core" (D11)
OTel का डिफ़ॉल्ट कॉन्टेक्स्ट
ThreadLocal पर आधारित है
[I]।
कोर के अंदर स्पष्ट (Explicit) कॉन्टेक्स्ट
ऑब्जेक्ट; OTel ब्रिज केवल बाहरी एडॉप्टस में रहेगा
( R13 )।
C11
Liquibase × Rolling upgrade
× DB-per-tenant
टेनेंट रजिस्ट्रेेशन के साथ स्कीमा लॉक
का डेडलॉक (Flowable #4215 )
[F]।
रोलआउट से पहले एक Kubernetes Job के
रूप में माइग्रेेशन; टेनेंट रजिस्ट्रेेशन स्कीमा लॉक के
बाहर ( R12 )।
C12
Lock expiry × Long jobs ×
Heartbeats
लॉक एक्सपायर होने पर जॉब दूसरी बार
चल जाता है [F]।
फेंसिंंग टोकन ( lockVersion ); हाटबीट्स;
प्रति जॉब प्रकार लॉक टाइम ( R20 )।
C13
History cleanup × Engine
start-up
क्लीनअप जॉब रीकॉि
गरेशन से इंजन
स्टाटअप विफल हो जाता है ( CAM-
8866 ) [F]।
क्लीनअप एक लीडर-इलेक्टेड सर्विस के रूप में
चलेगा, कभी भी इंजन स्टाटअप का हिस्सा नहीं होगा
( R29 )।
C14
CMMN plug-in × Shared
services
छुपी हुई निभरता भविष्य में CMMN
को हटाने से रोकेगी [I]।
ArchUnit नियम; साझा सेवाएं केवल
scopeType के माध्यम से जुड़ेंगी।
C15
AsyncAPI codegen × Multi-
language SDKs
AsyncAPI जनरेटस में सभी भाषाओं
के लिए पूण समथन नहीं है [F]।
टाइप्स JSON Schema / Proto से जनरेट
होंगे; AsyncAPI केवल डॉक्स और कॉन्ट्रैैक्ट
टेस्ट्स के लिए ( R16 )।
C16
Partition routing × Exclusive
jobs × External workers
एक्सक्लूसिव एक्सटनल जॉब्स एक-दूसरे
को ब्लॉक कर देते हैं (Flowable
#3509 ) [F]।
एक्सटनल वकस के लिए एक्सक्लूसिविटी की जाँच
एि
जिशन पर नहीं, कम्प्लीशन पर रूट इंस्टेंस के
अनुसार होगी ( R35 )।
C17
JSON variables × FEEL dates
Zoned बनाम Unzoned तारीखों
की तुलना में रनटाइम एरर
( FEEL/SCALA-01008 ) [F]।
लिखित टाइप कॉन्ट्रैैक्ट (JSON → FEEL);
मिसमैच को डिप्लॉयमेंट वैलिडेशन पर ही रिजेक्ट
किया जाएगा ( R33 )।
5.3 ATAM गुणवत्ता ट्रेेड-ऑफ (Quality Trade-offs — ANALYSIS.md §8)
Consistency बनाम Throughput: इंस्टेंस लॉक्स और सिंंक्रोनस राइट्स गति थोड़ी कम करते हैं पर शुद्धता देते हैं →
निणय: Correctness first (इंस्टेंस लॉक + Outbox)।
Audit Strictness बनाम Latency: सिंंक्रोनस हिस्ट्री तत्काल ऑडिट देती है पर कमिट धीमा करती है → निणय: outbox
डिफ़ॉल्ट, प्रति डिप्लॉयमेंट sync का विकल्प।
Security बनाम Ease of Use: डिफ़ॉल्ट रूप से सुरित (Auth चालू, ि
प्ट सैंडबॉक्स्ड, क्वेरी में कोई एक्सप्रेेशन नहीं);
लोकल विकास के लिए स्पष्ट dev mode प्रोफ़ाइल।
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 17 of 27

=== PAGE 18 ===
Compatibility बनाम Clean Design: Camunda/Flowable एक्सटेंशन्स के लिए रनटाइम में लेगेसी पाथ रखने के
बजाय इम्पोट-टाइम कन्वजन।
Breadth बनाम Quality: 8 DBs, 8 SDKs, 3 स्टैंडड्स और AI का बोझ संभालने के लिए हर चीज़ को Tiered (DB
टियस, SDK टियस, MoSCoW) किया गया है।
Portability बनाम Performance: जेनेरिक SQL के बजाय Tier-1 डेटाबेस के लिए प्रति-डायलेक्ट ट्यूून्ड एि
जिशन
SQL ( R2 )।
Native Start-up बनाम Features: पूरे इंजन के लिए JVM; केवल लाइट/गेटवे प्रोफ़ाइल के लिए GraalVM Native
Image ( R7 )।
6. भूमिका 3: मुख्य सूचना सुरक्षा अधिकारी (CISO) एवं विश्वसनीयता विश्लेषण (FMEA, Fault
Trees, STRIDE, Simulations)
6.1 FMEA: 24 विफलता मोड्स और RPN में 84% की कमी (ANALYSIS.md §9)
नीचे दी गई तालिका में Severity (S), Occurrence (O) और Detection Difficulty (D) (प्रत्येक 1–10) के आधार पर
अपस्ट्रीम का प्रारंभिक RPN (कुल 4,581) और संगम के सुरक्षा व विश्वसनीयता उपायों के बाद का नया RPN (कुल 712, अर्थात्
84% की कमी) दर्शाया गया है:
#
विफलता मोड और प्रभाव
(Failure Mode &
Effect)
साक्ष्य (Evidence)
S×O×D
= RPN
रोकथाम (Prevention)
पहचान
(Detection
)
S×O′×D′
= After
F1
लॉक एक्सपायर होने पर जॉब
का दो बार चलना → डबल
पेमेंट/ईमेल
Flowable फोरम
(P3)
9×6×6 =
324
Fencing token
( lockVersion ),
हाटबीट्स, आइडम्पोटेंसी कीज़
( R20 )
Jepsen टेस्ट;
डुप्लीकेट
कम्प्लीशन
मेट्रिक
9×2×3 =
54
F2
जॉब का खो जाना (कभी न
चलना) → इंस्टेंस अटकना
Flowable
#3913 ,
#3782
9×4×7 =
252
सभी जॉब टेबल्स (टाइमर सहित)
के लिए reset-expired स्वीपर
( R21 ); Outbox
"Oldest
due job
age" अलट
9×2×2 =
36
F3
इंस्टेंस और जॉब लॉक में
डेडलॉक → विफल कमांड्स
Flowable फोरम
(सभी DBs)
6×7×4 =
168
ग्लोबल लॉक क्रम; दो-चरणीय
एि
जिशन ( R3 )
पर-DB
डेडलॉक
चाओस टेस्ट;
क्लासिफायर
मेट्रिक
6×3×2 =
36
F4
पैरेलल जॉइन्स पर OLE
तूफ़ान → रुके हुए फ़््लो
Flowable
#2124 ; C7
फोरम
7×7×4 =
196
रूट इंस्टेंस लॉक + रिट्राई
इंटरसेप्टर
मॉडल-
आधारित
(jqwik) टेस्ट
7×2×3 =
42
F5
NOTIFY से कमिट थ्रूूपुट
का गिरना → सिस्टम धीमा
Recall.ai, DBOS
8×5×6 =
240
NOTIFY केवल Outbox रिले
से, बैच में ( R1 )
कमिट-लेटेंसी
SLO
8×1×3 =
24
F6
क्यू टेबल ब्लोट → समय के
साथ एि
जिशन धीमा
PgQue PR #80
6×6×5 =
180
Autovacuum ट्यूूनिंंग;
idle-in-tx टाइमआउट ( R2 )
xmin -age
अलट
6×3×2 =
36
F7
हिस्ट्री टेबल्स का असीमित
बढ़ना → डिस्क फुल
Flowable
#3422 (2 TB)
7×8×3 =
168
डिफ़ॉल्ट रिटेंशन चालू; 256 KB
पेलोड सीमा; पार्टीशन्स ( R29 )
टेबल-साइज़
मेट्रिक्स
7×2×2 =
28
F8
हिस्ट्री क्लीनअप से स्टाटअप
ब्लॉक → डिप्लॉय आउटेज
CAM-8866
8×3×5 =
120
लीडर-इलेक्टेड सर्विस के रूप में
क्लीनअप ( R29 )
क्लीनअप
टकराव के साथ
स्टाटअप टेस्ट
8×1×2 =
16
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 18 of 27

=== PAGE 19 ===
#
विफलता मोड और प्रभाव
(Failure Mode &
Effect)
साक्ष्य (Evidence)
S×O×D
= RPN
रोकथाम (Prevention)
पहचान
(Detection
)
S×O′×D′
= After
F9
मेमोरी लीक (कैश, ि
प्ट
इंजन) → OOM रीस्टाट
CAM-10553 ;
Operaton
#2761
7×5×6 =
210
बाउंडेड कैश, वीक क्लासलोडर
रेफरेंस ( R30 )
24 घंटे का
सोक (Soak)
गेट
7×2×2 =
28
F1
0
DST बदलने पर टाइमर
विस्फोट → हज़ारों
अतिरिक्त इंस्टेंस
Operaton
#3973 (Open)
8×4×7 =
224
UTC स्टोरेज,
lastFiredInterval ,
स्पष्ट टाइमज़ोन ( R31 )
DST प्रॉपर्टी
टेस्ट्स
8×1×2 =
16
F1
1
सेकेंडरी पासर में XXE →
डेटा लीक
CVE-2026-
91197 ; लोकल
कोड [F]
8×4×6 =
192
एकल हाडन्ड XML फ़ैक्ट्री; अन्य
पर प्रतिबंध ( R24 )
Semgrep /
ArchUnit;
Jazzer फ़ज़िंंग
8×1×2 =
16
F1
2
क्वेरी/फ़िल्टर में एक्सप्रेेशन
इंजेक्शन → RCE
C7 Notice 157;
Operaton
डिफ़ॉल्ट true
[F]
10×4×5
= 200
क्वेरी में एक्सप्रेेशन मूल्यांकन पूरी
तरह प्रतिबंधित ( R25 )
सुरक्षा डिफ़ॉल्ट
टेस्ट्स
10×1×2
= 20
F1
3
ि
प्ट सैंडबॉक्स एस्केप →
RCE
CVE-2025-
0982 (CVSS 10)
10×3×6
= 180
WASM / GraalJS
isolates; कोई Rhino नहीं;
शून्य होस्ट एक्सेस ( R6 )
एस्केप टेस्ट
सुइट
10×1×4
= 40
F1
4
एक वकर द्वाारा दूसरे के
जॉब्स छूना → डेटा लीक/
तोड़फोड़
Flowable
#4282 (Open)
8×5×5 =
200
वकर-बाउंड जॉब ऑपरेशन्स
( R26 )
Authorizati
on मैट्रिक्स
टेस्ट्स
8×1×2 =
16
F1
5
पुश कनेक्टस से SSRF →
आंतरिक नेटवक में सेंध
§5.1 से निष्कष
8×4×6 =
192
इग्रेेस अलाउ-लिस्ट; प्राइवेट/
मेटाडेटा रेंज ब्लॉक; DNS
पिनिंंग ( R27 )
SSRF टेस्ट
सुइट
8×1×3 =
24
F1
6
प्रॉम्प्ट-इंजेक्टेड MCP/एजेंट
एक्शन → अनधिकृत बदलाव
MCP शोध; CVE-
2025-6514
9×5×7 =
315
डिफ़ॉल्ट Read-only MCP;
अप्रूूवल टास्क; Taint मार्किंंग;
बजट ( R14 )
रेड-टीम
टेस्ट्स; ऑडिट
अलट्स
9×2×4 =
72
F1
7
FEEL null को चुपचाप
false मानना → गलत
शाखा
Camunda फोरम
7×6×7 =
294
Strict-null मोड ( null →
incident) ( R10 )
डिप्लॉयमेंट पर
लिंंटिंंग
7×2×3 =
42
F1
8
अपग्रेेड के बाद FEEL
व्यवहार बदलना → गलत
निणय
feel-scala
#582
8×4×7 =
224
प्रति मॉडल वजन पिन किया गया
FEEL इंजन; TCK ( R10 )
रिप्ले टेस्टिंंग
( L5 )
8×1×2 =
16
F1
9
कुछ मॉड्यूूल संयोजनों पर
अपग्रेेड ि
प्ट फेल होना
Flowable
#4008 ,
#2814
8×5×4 =
160
Liquibase; मॉड्यूूल-संयोजन
अपग्रेेड मैट्रिक्स ( R32 )
अपग्रेेड CI गेट
8×2×2 =
32
F2
0
Liquibase लॉक अटकना
या रेस होना → डिप्लॉय
ब्लॉक
Liquibase
#1584
6×4×3 =
72
एकल माइग्रेेशन जॉब; सेशन लॉक
( R12 )
डिप्लॉय चेक
6×2×2 =
24
F2
1
Authorization क्वेरीज़
का धीमा पड़ना → UI
टाइमआउट
CAM-7441 ,
CAM-5364
6×6×4 =
144
डिनॉमलाइज़््ड परमिशन्स; 300
ग्रुुप्स के साथ बेंचमाक ( R19 )
क्वेरी-लेटेंसी
SLO
6×2×2 =
24
F2
2
प्रॉक्सी के पीछे gRPC
स्ट्रीम्स टूटना → वकस
आइडल
Camunda फोरम
5×6×4 =
120
REST लॉन्ग-पोल फ़ॉलबैक;
प्रॉक्सी सेटिंंग्स गाइड ( R9 )
Worker-
idle अलट
5×2×2 =
20
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 19 of 27

=== PAGE 20 ===
#
विफलता मोड और प्रभाव
(Failure Mode &
Effect)
साक्ष्य (Evidence)
S×O×D
= RPN
रोकथाम (Prevention)
पहचान
(Detection
)
S×O′×D′
= After
F2
3
जॉब के दौरान इंस्टेंस
डिलीट होने पर झूठा
इंसिडेंट बनना
Operaton
#4017
4×5×4 =
80
समवर्ती डिलीट पर जॉब को
चुपचाप समाप्त करना ( R23 )
यूनिट टेस्ट
4×1×2 =
8
F2
4
प्रदशन लक्ष्य चूकना →
उत्पाद की विश्वसनीयता पर
असर
C7 गाइड: DB सीमा
है
7×6×3 =
126
बेंचमाक-फर्स्टट; प्रति रेफरेंस
प्रोसेस लक्ष्य (§14, R22 )
नाइटली
बेंचमाक
7×3×2 =
42
6.2 चारों शीष खतरों के लिए Fault Trees (T1–T4) और STRIDE + CVE नियंत्रण
Fault Tree T1 (साइड इफेक्ट का दो बार चलना): (a) जॉब चलते समय लॉक एक्सपायर होना + हाटबीट न होना + फेंसिंंग चेक
न होना → R20 (फेंसिंंग टोकन + हाटबीट्स) से पूरी तरह ब्लॉक। (b) नेटवक टाइमआउट के बाद वकर रिट्राई करे और बाहरी
सिस्टम आइडम्पोटेंट न हो → SDK द्वाारा डिफ़ॉल्ट आइडम्पोटेंसी कीज़ से कम किया गया (अवशिष्ट जोखिम: वे बाहरी सिस्टम जो
कीज़ को अनदेखा करते हैं)। (c) पि
श के बाद और माक से पहले Outbox रिले का क्रैश होना → eventId पर कंज्यूमर
डीडुप ( R4 ) से हल।
Fault Tree T2 (जॉब का कभी न चलना): (a) लॉक ओनर की मृत्यु और उस टेबल के लिए स्वीपर न होना → सभी जॉब टेबल्स
के लिए reset-expired स्वीपर ( R21 )। (b) कमिट के बाद नोड क्रैश होने से इन-मेमोरी हैंड-ऑफ खोना → जॉब रो कमिटेड
होने के कारण दूसरा नोड SKIP LOCKED से उसे उठा लेता है। (c) वेक-अप खो जाना → पोलिंंग फ़ॉलबैक।
Fault Tree T3 (इंस्टेंस का अटकना या करप्ट होना): (a) बिना रिट्राई के जॉइन पर OLE → रूट इंस्टेंस लॉक + रिट्राई
( R3 )। (b) रनिंंग जॉब के साथ डिलीट की रेस → चुपचाप जॉब समाि ( R23 )। (c) मॉडल अपग्रेेड से सेमैंटिक्स बदलना →
पिन किया गया FEEL ( R10 ) + रिप्ले टेस्टिंंग ( L5 )।
Fault Tree T4 (सुरक्षा ब्रीच): अनहाडन्ड XML पासर ( R24 ), क्वेरी में एक्सप्रेेशन ( R25 ), ि
प्ट एस्केप ( R6 ), क्रॉस-वकर
एक्सेस ( R26 ), SSRF ( R27 ) और प्रॉम्प्ट-इंजेक्टेड टूल कॉल ( R14 ) — सभी के लिए डेडिकेटेड सुरक्षा गेट्स लागू हैं।
6.3 अपस्ट्रीम प्रोडक्शन विफलताएं (P1–P18), लोकल कोड सत्यापन और खतरनाक डिफ़ॉल्ट्स
लोकल सोस कोड में प्रत्यक्ष रूप से सत्यापित दो गंभीर कमज़ोरियाँ [F]:
1. Flowable की ProcessDiagramLayoutFactory.parseXml (लाइन 102) में बिना किसी XXE सुरक्षा के
DocumentBuilderFactory.newInstance() बनाया गया है ( CVE-2026-91197 )।
2. Operaton के ProcessEngineConfigurationImpl (लाइन 784) में
enableExpressionsInStoredQueries = true डिफ़ॉल्ट रूप से चालू है, जो Camunda Security Notice
157 (RCE) का कारण रहा है।
सेटिंंग (Configuration
Setting)
Operaton का डिफ़ॉल्ट [F]
Flowable का डिफ़ॉल्ट [F]
Sangam का सुरित डिफ़ॉल्ट
(R28)
Authorization
Off (बंद)
अनुपि
त (कोई सर्विस नहीं)
On (चालू, Deny-by-
default)
Query Max Results
Limit
Integer.MAX_VALUE
(DoS जोखिम)
असीमित
1,000 (कॉि
गरेबल)
Job Retries
तीन तत्काल रिट्राई (बिना अंतराल)
तीन रिट्राई
R3/PT1M जिटर (Jitter) के
साथ
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 20 of 27

=== PAGE 21 ===
सेटिंंग (Configuration
Setting)
Operaton का डिफ़ॉल्ट [F]
Flowable का डिफ़ॉल्ट [F]
Sangam का सुरित डिफ़ॉल्ट
(R28)
Job Lock Time
5 मिनट
1 घंटा
प्रति जॉब टाइप p99 + हाटबीट +
फेंसिंंग
SKIP LOCKED
Acquisition
Off (बंद)
अनुपि
त
On (डिफ़ॉल्ट रूप से चालू)
History Cleanup /
Retention
शेड्यूूल नहीं किया गया
Off (चालू करने पर 365 दिन)
On (लीडर-इलेक्टेड सर्विस, TTL
चेतावनी)
Safe XML Flags
हाडन्ड
इंजन कॉि
ग में false
(केवल Spring Boot में चालू)
एकल हाडन्ड XML फ़ैक्ट्री (अन्य
प्रतिबंधित)
6.4 दस एंड-टू-एंड सिनेरियो सिमुलेशन (Scenario Simulations S1–S10 — ANALYSIS.md §13)
1. S1 (पॉलीग्लॉट ऑडर प्रोसेस — हैप्पी पाथ): PHP शॉप आइडम्पोटेंसी की के साथ POST /v1/process-instances कॉल
करती है → OIDC व CREATE_INSTANCE परमिशन जाँच → T1 में इंस्टेंस, JSON वेरिएबल्स, Drools DMN
"discount" निणय और Outbox रोज़ एक साथ कमिट होते हैं → रिले बैच्ड वेक-अप भेजता है → .NET वकर को gRPC
स्ट्रीम पर टास्क मिलता है → फेंसिंंग टोकन के साथ कम्प्लीशन (T2) → पैरेलल फोक (Go वकर + AWS Lambda पुश) →
दोनों शाखाएं एक साथ जॉइन पर पहुँचती हैं (T3, T4) पर रूट-इंस्टेंस लॉक क्रम के कारण बिना OLE या डेडलॉक के क्रमबद्ध होती हैं
→ डिनॉमलाइज़््ड टेबल से सही ग्रुुप/टेनेंट को यूज़र टास्क दिखता है → समाि पर Kafka में order.completed
CloudEvent और रात में XES एक्सपोट। (परिणाम: ✔ सफल)
2. S2 (टास्क के बीच वकर क्रैश): काड चाज करने के बाद .NET वकर क्रैश होता है → लॉक एक्सपायर होने पर नए फेंसिंंग टोकन के
साथ टास्क दोबारा डिलीवर होता है → SDK वही आइडम्पोटेंसी की पेमेंट API को भेजता है और पुराने वकर का बासी टोकन रिजेक्ट
हो जाता है। (परिणाम: ✔ सफल; ⚠ अवशिष्ट जोखिम: आइडम्पोटेंसी न मानने वाली बाहरी APIs)
3. S3 (कमिट के तुरंत बाद इंजन नोड क्रैश): इन-मेमोरी हैंड-ऑफ खो जाता है, पर जॉब और Outbox रोज़ डेटाबेस में कमिट हो चुके
हैं → दूसरा नोड SKIP LOCKED से जॉब उठा लेता है और लीज-आधारित रिले इवेंट्स पि
श कर देता है। (परिणाम: ✔ सफल)
4. S4 (एक ही मिलीसेकंड में एक ही इंस्टेंस पर दो मैसेज कोरिलेशन): दोनों कमांड्स क्रम से रूट-इंस्टेंस लॉक लेते हैं; टाइमआउट होने
पर रिट्राई इंटरसेप्टर जिटर के साथ रिट्राई करता है और MSSQL एरर 1205 को क्लासिफायर रिट्राई में बदल देता है। (परिणाम:
✔ सफल)
5. S5 ( agentTask का लूप के बीच क्रैश और प्रॉम्प्ट इंजेक्शन): 3 LLM कॉल्स और 2 टूल कॉल्स के बाद क्रैश होने पर जनल से
रिकॉर्डेड परिणाम रिप्ले होते हैं (कोई कॉल दोहराया नहीं जाता) और स्टेप 6 से काम शुरू होता है। टूल आउटपुट में छिपा "migrate
all instances" निर्देश Tainted माक होता है और बिना ह्यूूमन अप्रूूवल टास्क के आगे नहीं बढ़ता। (परिणाम: ✔ सफल)
6. S6 (स्कीमा बदलाव और नए मॉडल वजन के साथ रोलिंंग अपग्रेेड): K8s Job से Liquibase (केवल expand स्टेप) चलता है
→ पुराने और नए नोड्स साथ चलते हैं (N-1 टेस्ट) → मॉडल v2 डिप्लॉय होने पर v1 के लॉग्स रिप्ले (L5) होते हैं → पिन किए गए
FEEL से v1 इंस्टेंसेज का व्यवहार सुरित रहता है। (परिणाम: ✔ सफल)
7. S7 (घंटेवार टाइमर पर DST बदलाव): स्पष्ट टाइमज़ोन, UTC ड्यूू-डेट्स और lastFiredInterval चेक के कारण दोहराए
गए लोकल घंटे में हज़ारों इंस्टेंस बनने के बजाय प्रति घंटा केवल एक इंस्टेंस बनता है। (परिणाम: ✔ सफल)
8. S8 (Kafka का 2 घंटे के लिए डाउन होना): आउटबाउंड इवेंट्स पार्टीशन्ड Outbox में सुरित जमा होते हैं और कमांड्स बिना
रुके कमिट होते रहते हैं। (परिणाम: ✔ सफल)
9. S9 (टेनेंट आइसोलेशन): टेनेंट A के यूज़र की क्वेरी में SQL स्तर पर टेनेंट और Authorization दोनों फ़िल्टर जुड़ते हैं; DB-
per-tenant में टेनेंट रजिस्ट्रेेशन स्कीमा लॉक नहीं लेता ( C11 )। (परिणाम: ✔ सफल)
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 21 of 27

=== PAGE 22 ===
0. S10 (दुर्भावनापूण BPMN अपलोड): बाहरी एंटिटीज़ (XXE) वाला DTD एकल हाडन्ड पासर द्वाारा तुरंत रिजेक्ट होता है और
java.lang.Runtime छूने की कोशिश करने वाला ि
प्ट टास्क WASM/isolate सैंडबॉक्स द्वाारा ब्लॉक व लॉग किया जाता
है। (परिणाम: ✔ सफल)
7. भूमिका 5: इंजीनियरिंंग डिलीवरी डायरेक्टर — कैपेसिटी मॉडल, कोडबेस माप, प्रयास और जोखिम
7.1 कैपेसिटी एवं परफॉरमेंस मॉडल (Capacity & Write Model — ANALYSIS.md §14)
एक यथाथवादी एंटरप्राइज़ रेफरेंस प्रोसेस "R5" (Start → 1 DMN निणय → 5 एक्सटनल सर्विस टास्क → 1 पैरेलल फोक/जॉइन
→ 1 यूज़र टास्क → 20 JSON वेरिएबल्स → Outbox के माध्यम से ऑडिट हिस्ट्री) का डेटाबेस राइट मॉडल नीचे दिया गया है:
प्रोसेस चरण (Phase in Reference
Process R5)
ट्रांांजैक्शन्स (Transactions)
अनुमानित रो राइट्स (Row Writes)
Start + DMN Evaluation
1
~30
5 External Tasks (Lock + Complete)
10
~60
Parallel Join
2
~10
User Task (Create + Complete)
2
~15
Process End
1
~10
History Projection (Outbox Relay)
Batched
~100
कुल प्रति R5 इंस्टेंस (Total per
Instance)
~16 Transactions
~225 Row Writes
गणितीय निष्कष: यदि R5 को 1,000 PI/s पर चलाया जाए, तो एक प्राइमरी डेटाबेस पर प्रति सेकंड 16,000 ट्रांांजैक्शन्स और
2,25,000 रो राइट्स का भार पड़ेगा जो एक बड़े PostgreSQL सवर की ऊपरी भौतिक सीमा है। इसलिए संगम ने झूठे दावों के बजाय
16-vCPU ऐप + 16-vCPU PostgreSQL पर निम्नलिखित यथाथवादी लक्ष्य ( R22 ) तय किए हैं: R1 (सरल 1-टास्क प्रोसेस) ≥
2,000 PI/s; R5 (यथाथवादी प्रोसेस) ≥ 300 PI/s (p99 कमांड लेटेंसी < 50 ms); और जॉब हैंड-ऑफ लेटेंसी p99 < 100
ms। इसके साथ ही Little's Law ( Concurrent tasks = arrival rate × duration , जैसे 300 PI/s × 5
टास्क × 0.2s = 300 समवर्ती टास्क) के आधार पर वकर पूल साइज़िंंग फॉमूला SDK डॉक्स में शामिल किया गया है।
7.2 लोकल कोडबेस का वास्तविक माप और 15 वक पैकेजों का प्रयास (ANALYSIS.md §15)
2026-11-04 को दोनों लोकल क्लोन्स के src/main Java कोड की गिनती से प्राप्त तथ्य [F]:
Operaton कुल मुख्य कोड: 5,672 फ़ाइलें, 5,20,924 लाइन्स + 3,659 टेस्ट फ़ाइलें (जिसमें Authorization: 6 फ़ाइलें
/ 1,867 लाइन्स + SQL मैपिंंग्स; Migration: 99 फ़ाइलें / 8,702 लाइन्स; Batch: 62 फ़ाइलें / 5,202 लाइन्स;
History: 73 फ़ाइलें / 9,736 लाइन्स; History Cleanup: 13 फ़ाइलें / 1,338 लाइन्स; CMMN: 151 फ़ाइलें /
16,307 लाइन्स; DMN: 192 फ़ाइलें / 13,850 लाइन्स; और REST: 608 फ़ाइलें / 56,233 लाइन्स शामिल हैं)।
Flowable कुल मुख्य कोड: 5,615 फ़ाइलें, 6,03,390 लाइन्स + 2,417 टेस्ट फ़ाइलें (जिसमें हटाया जाने वाला
flowable5-engine : 748 फ़ाइलें / 83,316 लाइन्स; BPMN इंजन: 1,115 फ़ाइलें / 1,24,676 लाइन्स; engine-
common : 383 फ़ाइलें / 40,424 लाइन्स; CMMN इंजन: 557 फ़ाइलें / 69,575 लाइन्स; DMN इंजन: 148 फ़ाइलें /
13,469 लाइन्स; Job Service: 172 फ़ाइलें / 19,505 लाइन्स; और Event Registry: 138 फ़ाइलें / 14,331 लाइन्स
शामिल हैं)।
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 22 of 27

=== PAGE 23 ===
वक पैकेज (Work Package — Phase 0 to Phase 3)
अनुमानित प्रयास (Person-Months
[I])
1. Phase 0: Fork, strip flowable5-* , rename, immutable config,
Liquibase consolidation, CI DB matrix, OTel
12 – 18
2. BPMN Gaps (§3.1 के 11 गैप्स) + Camunda / Operaton एक्सटेंशन इम्पोट
10 – 16
3. Executor Hardening (पर-DB SKIP LOCKED, फेंसिंंग, लॉक क्रम, क्लासिफायर,
टाइमस/DST)
8 – 12
4. DMN SPI + Drools / jDMN / feel-scala एडॉप्टस + TCK
4 – 6
5. Authorization (डिनॉमलाइज़््ड परमिशन टेबल सहित) + Multi-tenancy + OIDC
12 – 18
6. History Pipeline + Outbox + Cleanup + Event Log + Replay Testing
10 – 14
7. Migration / Modification / Restart / Batch Framework
10 – 14
8. Worker Gateway (REST + gRPC स्ट्रीमिंंग) + Push / Serverless कनेक्टस
8 – 12
9. SDKs: Tier 1 (Java, .NET, PHP, TypeScript, Python, Go) टेस्ट इंजनों सहित
18 – 30
10. UI ( webapps-neo से Cockpit, Tasklist, Admin और Web Modeler)
18 – 28
11. Event Gateway (Event Registry पोट + चैनल्स + CloudEvents)
6 – 10
12. AI agentTask + Durable Journal + MCP Server (डिफ़ॉल्ट Read-only)
5 – 8
13. CMMN Hardening + प्लग-इन आइसोलेशन + CMMN → BPMN कन्वटर
6 – 10
14. Security Programme (STRIDE, Jazzer फ़ज़िंंग, WASM सैंडबॉक्स, ASVS L3, पेन-
टेस्ट तैयारी)
8 – 12
15. Documentation, Examples, Benchmarks, Release Engineering (SLSA
L3)
8 – 12
Phase 3 के अंत तक कुल अनुमानित प्रयास (Total Effort)
≈ 143 – 220 Person-Months
7.3 प्रोजेक्ट रिस्क रजिस्टर (PR1–PR12), Pre-Mortem और सफलता की संभावना
ID
प्रोजेक्ट जोखिम (Project Risk)
P × I = Score
निवारण उपाय (Mitigation)
स्वामी
(Owner)
PR1
स्कोप की तुलना में टीम का छोटा होना
4 × 5 = 20
§15.1 के अनुसार टीम का आकार या सख्त
स्कोप टियरिंंग
Project Lead
PR2
Authorization पोट की जटिलता (हर
क्वेरी में बदलाव)
4 × 4 = 16
Phase 0 में अलग डिज़ाइन स्पाइक
Architect
PR12
MVP पूरा होने से पहले समय या फंडिंंग
समाप्त होना
3 × 5 = 15
MVP सबसे पहले; हर माइलस्टोन पर कायशील
डेमो
Sponsor
PR3
प्रदशन का लक्ष्यों से कम रहना
3 × 4 = 12
बेंचमाक-फर्स्टट नीति और संशोधित R1/R5 लक्ष्य
( R22 )
Perf Lead
PR4
अपस्ट्रीम Flowable से फोक का बहुत
दूर चला जाना (Drift)
4 × 3 = 12
नियमित सिंंक शेड्यूूल; शुरुआती चरण में हॉट
फ़ाइलों में न्यूनतम बदलाव
Core Lead
PR5
मल्टी-DB रखरखाव की भारी लागत
4 × 3 = 12
डेटाबेस टियस ( R17 )
QA Lead
PR6
8 भाषाओं के SDK रखरखाव की लागत
4 × 3 = 12
SDK टियस और प्रोटोकॉल से ऑटो-जनरेशन
( R18 )
DX Lead
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 23 of 27

=== PAGE 24 ===
ID
प्रोजेक्ट जोखिम (Project Risk)
P × I = Score
निवारण उपाय (Mitigation)
स्वामी
(Owner)
PR7
AI / MCP सुरक्षा दुघटना
3 × 4 = 12
डिफ़ॉल्ट Read-only, ह्यूूमन अप्रूूवल और रेड-
टीम टेस्टिंंग ( R14 )
Security
Lead
PR8
UI प्रयास का कम आकलन
3 × 4 = 12
webapps-neo का पुनरुपयोग और
सीमित MVP UI स्कोप
UI Lead
PR9
प्रतिस्पर्धियों द्वाारा लाइसेंस या फीचस में
बदलाव
3 × 3 = 9
मुख्य विभेदकों (Free UI + Standards +
Authz) को तेज़ी से शिप करना
Product
Lead
PR11
प्रमुख व्यि
यों पर अत्यधिक निभरता
(Key-person risk)
3 × 3 = 9
पेयर प्रोग्राामिंंग और Docs-as-Code
Project Lead
PR10
ट्रेेडमाक या लाइसेंस संबंधी चूक
2 × 4 = 8
§12 की Apache 2.0 व bpmn.io
वॉटरमाक चेकलिस्ट और CI स्कैन
Project Lead
सफलता की संभावना (Success Probability — ANALYSIS.md §18.2 [I]):
Technical MVP (Phase 1: BPMN + DMN + Workers + Tier-1 SDKs + Basic UI): 75–85% (सभी
बिल्डिंंग ब्लॉक्स प्रमाणित हैं)।
संपूण स्कोप (Phase 3 तक), 8–10 इंजीनियस की टीम के साथ (15–28 महीने): 45–55%।
संपूण स्कोप (Phase 3 तक), 3–4 लोगों की छोटी टीम के साथ (3–6 साल): 10–20%।
अनुशंसित टियड स्कोप (§19 सुधारों के साथ), 6–10 लोगों की टीम के साथ: 60–70%।
8. इंजीनियरिंंग सिद्धांत (A1–A7, D1–D12, P1–P10), रोडमैप और 35 अनिवाय सुधार (R1–R35)
8.1 इंजीनियरिंंग गुणवत्ता प्रणाली और 10+ वष का लाइफसाइकिल (DESIGN.md §11, §12, §14)
System Analysis (A1–A7): हर आवश्यकता की ट्रेेसिबिलिटी ( REQ-KEEP-* , REQ-FIX-W* , REQ-NEW-* ),
ISO/IEC 25010 गुणवत्ता लक्ष्य, DDD शब्दावली, ATAM ट्रेेड-ऑफ, MADR प्रारूप में ADRs, प्रति कम्पोनेंट STRIDE थ्रेेट
मॉडल और अपस्ट्रीम बग्स से रिग्रेेशन टेस्ट।
System Design (D1–D12): Hexagonal Architecture (कोर में Spring/MyBatis/Kafka की कोई निभरता
नहीं), मॉड्यूूलर मोनोलिथ फर्स्टट, Contract-first APIs ( buf breaking , oasdiff ), SOLID (कोई क्लास ~800
लाइन्स से बड़ी नहीं), स्पष्ट स्टेट मशीनें, हर जगह Idempotency, सर्किट ब्रेेकस, Secure by Default, Expand →
Migrate → Contract बैकवड कम्पैटिबिलिटी, डिज़ाइन से Observability, कोर में पूण Determinism (कोई
System.currentTimeMillis , new Date() या ThreadLocal नहीं), और स्टैंडड्स को प्राथमिकता।
System Development (P1–P10): Trunk-based विकास, कोर के लिए TDD, टेस्ट पिरामिड, कोर पर PIT
Mutation Testing (≥ 70% स्कोर, ≥ 85% लाइन कवरेज), jqwik प्रॉपर्टी-आधारित टेस्ट, Jazzer पासर फ़ज़िंंग, कोर व
सुरक्षा मॉड्यूूल्स के लिए 2 रिव्यूअस, Docs-as-Code, सख्त Definition of Done और पिन किए गए डिपेंडेंसीज़ के साथ
Reproducible Builds।
10+ वष का लाइफसाइकिल और कानूनी नियम (§12, §14.7): हर 3 महीने में माइनर रिलीज़ और हर 2 साल में 5-वर्षीय सुरक्षा
सपोट वाला LTS रिलीज़; N-1/N-2 SDK कम्पैटिबिलिटी; OpenRewrite अपग्रेेड रेसिपीज़; Apache 2.0 LICENSE व
दोनों प्रोजेक्ट्स के NOTICE का संरक्षण; प्रोडक्ट नाम में "Camunda", "Flowable" या "Operaton" का उपयोग न करना;
और bpmn-js/dmn-js/form-js के वॉटरमाक को बनाए रखना।
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 24 of 27

=== PAGE 25 ===
8.2 सभी 35 अनिवाय डिज़ाइन सुधारों की संपूण तालिका (R1–R35 — ANALYSIS.md §19)
ID
अनिवाय डिज़ाइन परिवतन (Required Design Change Applied to DESIGN.md)
स्रोोत (Source)
R1
NOTIFY केवल Outbox रिले से बैच में चलेगा; कमांड ट्रांांजैक्शन के अंदर कभी नहीं; पोलिंंग फ़ॉलबैक
रहेगा।
§6 #1, C1, F5
R2
पर-DB एि
जिशन SQL (Oracle दो-चरणीय, इंडेक्स सीक पर MSSQL
UPDLOCK+READPAST+ROWLOCK , MySQL लॉक-फुटप्रिंंट टेस्ट); autovacuum व
xmin अलट।
§6 #1, F6
R3
ग्लोबल लॉक क्रम: root instance → executions → jobs ; दो-चरणीय एि
जिशन;
NOWAIT /timeout + रिट्राई।
C2, F3
R4
Outbox: कमिट-विज़िबिलिटी गाड, eventId से कंज्यूमर डीडुप, लैग SLO और पार्टीशन्ड टेबल।
§6 #3, C3
R5
JDK बेसलाइन को 21+ से बढ़ाकर JDK 25 LTS किया गया (JEP 491 वचुअल थ्रेेड्स के लिए)।
§6 #12
R6
कोई Rhino नहीं; ि
प्ट्स केवल WASM (Chicory/Endive) या GraalJS isolates पर; शून्य
होस्ट एक्सेस।
§6 #7, F13
R7
GraalVM Native Image केवल लाइट / वकर-गेटवे प्रोफ़ाइल के लिए; पूण इंजन JVM पर।
§6 #13, C5
R8
Drools DMN के फुटप्रिंंट का मापन; jDMN फ़ॉलबैक; तीनों इंजनों पर DMN TCK रन।
§6 #5
R9
REST लॉन्ग-पोल फ़ॉलबैक के साथ gRPC स्ट्रीमिंंग, MAX_CONNECTION_AGE , स्ट्रीम बंद होने पर
अनलॉक, प्रॉक्सी गाइड।
§6 #6, C4, F22
R10
प्रति मॉडल वजन BPMN/CMMN के लिए एक पिन किया गया FEEL इंजन; Strict-null मोड
( null → इंसिडेंट)।
§6 #8, C6, F17, F18
R11
JSON गहराई और आकार की सीमाएं; Jackson default typing पूरी तरह बंद।
§6 #9
R12
सेशन लॉक के साथ एकल Kubernetes Job के रूप में Liquibase; प्रति DB ऑनलाइन-सेफ चेंज
अलाउ-लिस्ट।
§6 #10, C11, F20
R13
OTel नियम: सिंंक्रोनस कंटीन्यूएशन के लिए parent spans, async जॉब्स/टाइमस/वकस के लिए
span links; कोर में स्पष्ट कॉन्टेक्स्ट।
§6 #11, C10
R14
MCP सवर डिफ़ॉल्ट रूप से Read-only; राइट टूल्स अप्रूूवल टास्क बनाएँगे; Tainted टूल आउटपुट;
एजेंट-काड अलाउ-लिस्ट।
§6 #14, C9, F16
R15
XES प्राथमिक प्रोसेस-माइनिंंग एक्सपोट; OCEL 2.0 का CI में pm4py के विरुद्ध सत्यापन।
§6 #15
R16
AsyncAPI केवल डॉक्स और कॉन्ट्रैैक्ट टेस्ट्स के लिए; इवेंट टाइप्स JSON Schema / Proto से
जनरेट होंगे।
§6 #16, C15
R17
DB Tiers: Tier 1: PostgreSQL, Oracle, SQL Server (हर PR पर)। Tier 2: MySQL,
MariaDB, DB2 (Nightly)। Experimental: CockroachDB, YugabyteDB। केवल टेस्ट:
H2।
§6 #17
R18
SDK Tiers: Tier 1: Java, .NET, PHP, TypeScript, Python, Go (जनरेटेड कोर +
एर्गोनॉमिक लेयर + टेस्ट इंजन)। Tier 2: Rust, Kotlin (जनरेटेड)।
§4, PR6
R19
Authorization: लिस्ट क्वेरीज़ के लिए डिनॉमलाइज़््ड परमिशन टेबल; सिंंगल-रिसोस चेक के लिए
ABAC/OPA/Cedar; हर Tier-1 DB पर 10k टास्क × 300 ग्रुुप्स बेंचमाक।
§6 #18, C8, F21
R20
हर लॉक पर फेंसिंंग टोकन ( lockVersion ); बासी कम्प्लीशन रिजेक्ट; प्रति जॉब प्रकार लॉक टाइम;
हाटबीट्स।
P3, C12, F1
R21
टाइमस सहित सभी जॉब टेबल्स के लिए reset-expired स्वीपर।
P4, F2
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 25 of 27

=== PAGE 26 ===
ID
अनिवाय डिज़ाइन परिवतन (Required Design Change Applied to DESIGN.md)
स्रोोत (Source)
R22
प्रति रेफरेंस प्रोसेस संशोधित प्रदशन लक्ष्य (§14.3: R1 ≥ 2,000 PI/s, R5 ≥ 300 PI/s); समान
हाडवेयर पर Operaton/Flowable के साथ तुलना।
§14, F24
R23
समवर्ती इंस्टेंस डिलीट होने पर जॉब चुपचाप समाप्त होगा (कोई रिट्राई या इंसिडेंट नहीं); प्रति-डायलेक्ट
कन्करेंसी क्लासिफायर; MSSQL READ_COMMITTED_SNAPSHOT स्टाटअप चेक।
P5, P18, F23
R24
एकल हाडन्ड XML फ़ैक्ट्री; Semgrep/ArchUnit द्वाारा अन्य XML फ़ैक्ट्रीज़ पर प्रतिबंध; एंटिटी और
आकार सीमाएं।
CVE-2026-91197,
F11
R25
क्वेरीज़, फ़िल्टस या REST इनपुट में कोई एक्सप्रेेशन मूल्यांकन नहीं; सेव्ड फ़िल्टस केवल लिटरल वैल्यूज़ और
फिक्स्ड प्लेसहोल्डस लेंगे।
C7 Notice 157, F12
R26
वकर-बाउंड जॉब ऑपरेशन्स; बल्क ऑपरेशन्स के लिए एडमिन परमिशन अनिवाय।
Flowable #4282,
F14
R27
कनेक्टर इग्रेेस कंट्रोल: अलाउ-लिस्ट, प्राइवेट/मेटाडेटा IP रेंज ब्लॉक, रीडायरेक्ट सीमा और DNS पिनिंंग।
F15
R28
सुरित डिफ़ॉल्ट्स: Authorization चालू, queryMaxResultsLimit 1,000, SKIP
LOCKED चालू, जिटर के साथ रिट्राई R3/PT1M , एरर में कोई SQL या स्टैक ट्रेेस नहीं।
§12
R29
डिफ़ॉल्ट रूप से रिटेंशन चालू (TTL चेतावनी); लीडर-इलेक्टेड सर्विस के रूप में क्लीनअप; अधिकतम
इनलाइन वेरिएबल 256 KB (बड़े रेफरेंस के रूप में); रिमूवल टाइम से पार्टीशन्ड हिस्ट्री।
P6, P7, F7, F8
R30
वीक क्लासलोडर रेफरेंस के साथ बाउंडेड कैश; कैश मेट्रिक्स; 24 घंटे का सोक (Soak) गेट।
P9, F9
R31
टाइमर नीति: UTC ड्यूू-डेट्स, साइकिल्स पर स्पष्ट टाइमज़ोन, lastFiredInterval , कैच-अप
नीति और DST प्रॉपर्टी टेस्ट्स।
P10, F10
R32
मॉड्यूूल संयोजनों और DB-per-tenant पर अपग्रेेड मैट्रिक्स; व्यवहार बदलने वाले डिफ़ॉल्ट्स एक LTS
तक फ़््लैग के पीछे रहेंगे।
P13, F19
R33
JSON वेरिएबल्स के लिए FEEL टाइप कॉन्ट्रैैक्ट; डिप्लॉयमेंट वैलिडेशन पर zoned/unzoned मिसमैच
रिजेक्ट; डिसीजन एरस के लिए स्पष्ट व्यवहार।
P16, C17
R34
Flowable बेस के 11 BPMN गैप्स (§3.1) को Must आइटम के रूप में और दोनों इंजनों के गैप्स
(§3.2) को Should/Could के रूप में पूरा करना।
§3
R35
एक्सटनल वकस के लिए एक्सक्लूसिविटी की जाँच एि
जिशन पर प्रति जॉब नहीं, बि
 कम्प्लीशन पर प्रति
रूट इंस्टेंस होगी।
P2, C16
9. विशेषज्ञ निष्कष और 7 निर्णायक रणनीतिक सुझाव (My Verdict & 7 Strategic
Imperatives)
पाँचों विशेषज्ञ भूमिकाओं से DESIGN.md और ANALYSIS.md का संपूण पोस्टमाटम करने के बाद मेरा स्पष्ट निष्कष यह है कि
तकनीकी और आर्किटेक्चरल दृि से यह डिज़ाइन असाधारण रूप से परिपक्व और ठोस है। फिर भी, इस प्रोजेक्ट को वास्तविक धरातल
पर 100% सफल बनाने के लिए मेरे 7 निर्णायक रणनीतिक सुझाव नीचे दिए गए हैं:
1. सुझाव 1 — "Scope बनाम Team Size" के जाल से बचें (Focus on the Wedge MVP First): ANALYSIS.md
§15.1 का 143–220 पसन-मंथ्स का अनुमान सबसे बड़ा सत्य है। यदि आपकी कोर टीम 8–10 इंजीनियस से छोटी है, तो पहले
6–9 महीनों में केवल PostgreSQL + BPMN + Drools DMN + Outbox + SKIP LOCKED +
Authorization + webapps-neo UI + 4 प्रमुख SDKs (Java, Python, Node/TS, .NET) पर 100% ऊर्जा
लगाएँ। यही "Camunda 7 EOL + Flowable OSS" के बाज़ार को जीतने के लिए पर्याप्त है।
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 26 of 27

=== PAGE 27 ===
2. सुझाव 2 — Phase 0 में सबसे पहले "Authorization Query Spike (R19)" चलाएँ: Operaton का
Authorization मॉडल Flowable की हर लिस्ट क्वेरी को छूएगा। इससे पहले कि टीम सैकड़ों MyBatis XML फ़ाइलों को
बदले, Phase 0 के पहले महीने में ही Denormalised Permission Table का स्कीमा बनाकर PostgreSQL,
Oracle और MSSQL पर 10,000 टास्क × 300 ग्रुुप्स का बेंचमाक पास कर लें।
3. सुझाव 3 — Camunda 7 / Flowable XML Model Importer (UC6) को Phase 4 से खींचकर Phase 1.5
में लाएँ: रोडमैप ( §11 ) में मॉडल इम्पोटस को Phase 4 में रखा गया है, जबकि शुरुआती एडॉप्शन का सबसे बड़ा स्रोोत
Camunda 7 छोड़ने वाली टीमें ( S6 ) हैं। XML नेमस्पेस मैपर ( camunda: और flowable: को संगम में बदलना) को
Phase 1 के तुरंत बाद शिप करें ताकि उपयोगकर्ता बिना मॉडल बदले संगम का परीक्षण कर सकें (केवल रनिंंग DB माइग्रेेशन टूल
को Phase 4 में रखें)।
4. सुझाव 4 — Phase 1 में CI Database Matrix का व्यावहारिक अनुशासन रखें: यद्यपि Tier 1 में PostgreSQL,
Oracle और SQL Server तीनों हैं ( R17 ), फिर भी शुरुआती कोडिंंग गति बनाए रखने के लिए हर PR पर PostgreSQL को
अनिवाय गेट रखें और Oracle व MSSQL के भारी Testcontainers सूट को दैनिक/माइलस्टोन बिल्ड में चलाएँ, फिर Phase
2 से उन्हें हर PR पर लागू करें।
5. सुझाव 5 — CMMN को Phase 3 तक "Zero-Touch Isolation" में रखें: Flowable का CMMN इंजन पहले से ही
काम करता है और बाज़ार में CMMN की नई मांग कम है। इसलिए Phase 0 में केवल उसे sangam-runtime-cmmn
मॉड्यूूल में अलग कर दें और शुरुआती 12 महीनों में CMMN → BPMN कन्वटर या CMMN वेब मॉडलर बनाने में कोर टीम का
समय बिल्कुल खच न करें।
6. सुझाव 6 — बाहरी नॉन-आइडम्पोटेंट सिस्टम्स (Sim S2 Residual Risk) के लिए SDK में "Step Journal Helper"
दें: जिस प्रकार agentTask ( L8 ) में क्रैश के बाद दोबारा कॉल रोकने के लिए Durable Step Journal बनाया गया है, उसी
जनल मैकेनिज़््म को सामान्य सर्विस टास्क वकस के लिए भी SDK में context.step("chargeCard", ...) हेल्पर के
रूप में उपलब्ध कराएँ। इससे वे बाहरी पेमेंट/लेगेसी APIs जो Idempotency Key सपोट नहीं करतीं, वे भी डुप्लीकेट निष्पादन
से बच जाएँगी।
7. सुझाव 7 — Phase 1 के अंत में तुलनात्मक "Open Benchmark Report (R22)" प्रकाशित करें: जैसे ही एजेंडा कोर,
Outbox और SKIP LOCKED तैयार हों, एक ही 16-vCPU हाडवेयर पर Operaton, Flowable और Sangam के बीच
R1 और R5 वकफ़््लो का पारदर्शी बेंचमाक पि
श करें। यह प्रमाणित डेटा ही ओपन-सोस समुदाय और एंटरप्राइज़ आर्किटेक्ट्स के
बीच संगम की विश्वसनीयता स्थापित करेगा।
Project Sangam (संगम) — Comprehensive End-to-End Analysis Report
Page 27 of 27

~~~~


---

# Appendix B — GOV-1…GOV-6 Verification Report (verbatim, 2026-11-04)

> Research report against official Indian government sources. It is summarised in Part IX.7a.

## Sangam: Indian government compliance research, items GOV-1 to GOV-6

Research date: 2026-11-04. "Read" means I extracted and read the official text (often a gazette or PDF). "Snippet" means I saw only a search-result excerpt of an official page. Anything labelled SECONDARY comes from a non-government source.

---

### GOV-1 Languages

**(a) Instruments**
- Official Languages Act, 1963, s.3(3), and the Official Languages (Use for Official Purposes of the Union) Rules, 1976, as amended. I read the full rules text from a CAG-hosted copy.
- Eighth Schedule to the Constitution, as listed in an MHA Lok Sabha answer of 11 Feb 2025 (snippet).
- Department of Official Language (DoOL), Annual Programme 2023-24 and 2021-22 (snippets). The 2026-27 programme was not read because rajbhasha.gov.in failed TLS from this environment.
- GIGW 3.0, Lifecycle Management guideline 10 (read).
- Digital India BHASHINI (snippets and PIB).

**(b) What they require**
- **Rule 1:** the Rules extend to the whole of India except Tamil Nadu.
- **Rule 6:** documents under s.3(3) of the Act (resolutions, general orders, notifications, contracts, licences, permits, tender notices and forms) must be issued in both Hindi and English. The signer is responsible for this.
- **Rule 7:** an employee may submit an application in Hindi or English. One made in Hindi must be replied to in Hindi.
- **Rule 8(1):** an employee may write a note or minute on a file in Hindi or English without supplying a translation.
- **Rule 11:** manuals, codes and procedural literature must be bilingual in diglot form. "The forms and headings of registers ... shall be in Hindi and in English". Name-plates, letterheads and similar items must also be bilingual.
- **DoOL Annual Programme:**
  - 2023-24: all computers must have Unicode installed so that Hindi work can be done on them.
  - 2021-22: targets are 100% bilingual electronic equipment purchases and 100% bilingual websites.
  - Hindi content on websites must be "developed and updated in Hindi regularly".
- **Eighth Schedule:** 22 languages. Assamese, Bengali, Bodo, Dogri, Gujarati, Hindi, Kannada, Kashmiri, Konkani, Maithili, Malayalam, Manipuri, Marathi, Nepali, Odia, Punjabi, Sanskrit, Santhali, Sindhi, Tamil, Telugu, Urdu.
- **GIGW 3.0:**
  - "Website/app is bilingual with a prominent language selection link and uses Unicode characters" (Lifecycle 10).
  - Pages in multiple languages must be updated at the same time (Lifecycle 6).
  - Hindi and regional-language fonts must be tested across browsers (Quality 13).
  - Pages need a language attribute (Quality 17).
- **BHASHINI:** MeitY's language platform. It offers translation APIs and a website-translation plugin covering 22 scheduled languages. Example: e-Shram went live in all 22 languages in January 2025.
- **States:** Article 345 lets each state legislature adopt its own official language or languages. States then pass their own Official Language Acts. I did not read any state Act in this pass. In practice a state expects English plus the state official language (for example Hindi in Rajasthan or Marathi in Maharashtra). That is UNVERIFIED.

**(c) URLs**
- https://cag.gov.in/uploads/media/Official-Language-Rules-20200728115111.pdf
- https://rajbhasha.gov.in/sites/default/files/AP2023_24.pdf
- https://rajbhasha.gov.in/sites/default/files/AnnualProg2021-22Eng_0.pdf
- https://www.mha.gov.in/MHA1/Par2017/pdfs/par2025-pdfs/LS11022025/118.pdf
- https://guidelines.india.gov.in/annexure-ii-matrix-to-check-conformity/
- https://bhashini.gov.in/about-bhashini
- https://www.pib.gov.in/PressReleasePage.aspx?PRID=2090895

**(d) Status in law**
- The Act and Rules are **mandatory** for Central Government offices, except in Tamil Nadu.
- GIGW is mandatory for government websites and apps (see GOV-2).
- BHASHINI is advisory, an enabling platform.
- State language law applies to state departments.

**(e) Design implication for Sangam**
- Every UI string, form label, register heading, notification template and generated document (sanction orders, licences, permits) must render bilingually. Default to Hindi and English, with a per-tenant third or replacement language.
- Store data in Unicode (NVARCHAR or UTF-8 collations on Oracle, SQL Server and PostgreSQL).
- Note and comment fields in e-Office-style file movement must accept Hindi or English with no forced translation.
- Replies should default to the language of the incoming application.
- Provide an optional BHASHINI translation connector.
- Templates should support side-by-side (diglot) output.

**(f) Status: PARTIAL**
- The OL Rules and GIGW text were verified.
- The Eighth Schedule list and the DoOL programmes were seen only as official snippets.
- The current 2026-27 programme was not read, and no state Act was read.

---

### GOV-2 Accessibility and GIGW

**(a) Instruments**
- **GIGW 3.0 (2023).** "Guidelines for Indian Government Websites and Apps", released in March 2023 according to the STQC transition plan dated 23 May 2023. NIC under MeitY formulated it jointly with the STQC Directorate, and CERT-In wrote the cybersecurity chapter.
- **RPwD Act 2016, rule 15(1)(c) of the 2017 Rules,** amended on 10 or 11 May 2023 (reported as G.S.R. 359(E), SECONDARY).
- **BIS IS 17802** (Part 1):2021 and (Part 2):2022.

**(b) What they require**
- **Scope:** GIGW covers "websites, web portals, web applications and mobile apps". The guidelines say "all the websites/apps owned by government organisations must comply". Browser-based intranet applications should also follow them.
- **Structure:** 88 guidelines in four groups: Quality (25), Accessibility (50), Cybersecurity (3) and Lifecycle (10), per the STQC transition plan.
- **WCAG:** the accessibility criteria are adopted from WCAG 2.1. The "Level AA" mapping appears in an official-site snippet and in the STQC material. The pages I read do not state the level explicitly.
- **Certification:** GIGW "form[s] the basis for obtaining the Website Quality Certification from the STQC Directorate".
  - STQC began accepting GIGW 3.0 applications from 1 Oct 2023.
  - The transition window ran until about October 2024.
  - A new website is treated as a fresh GIGW 3.0 case.
- **GIGW security clause:** the site must be audited before production hosting by "NIC/STQC/STQC empanelled laboratory/CERT-In empanelled laboratory". Infrastructure must be hosted "within geographical boundaries of India".
- **RPwD rule 15:** ICT products and services must comply with IS 17802 Parts 1 and 2. That standard is India's adaptation of EN 301 549.

**(c) URLs**
- https://guidelines.india.gov.in/scope-and-objective/
- https://guidelines.india.gov.in/introduction/
- https://guidelines.india.gov.in/guidelines/
- https://www.stqc.gov.in/sites/default/files/tenders/Transition%20Plan.pdf
- https://www.scconline.com/blog/post/2023/05/12/ministry-of-social-justice-and-empowerment-notified-rights-of-persons-with-disabilities-amendment-rules-2023-legal-news/ (SECONDARY)
- https://www.services.bis.gov.in/php/BIS_2.0/bisconnect/knowyourstandards/Indian_standards/isdetails_mnd/27423

**(d) Status in law**
- GIGW is **mandatory** for government websites and apps. DARPG has made it part of the Central Secretariat Manual of Office Procedure.
- STQC certification is a voluntary scheme, but departments and tenders commonly require it.
- RPwD rule 15 is a **statutory mandate** on "every establishment", which includes government.

**(e) Design implication for Sangam**
- Build to WCAG 2.1 AA and IS 17802 from the start, for both the web application and any mobile app: keyboard operation, ARIA, contrast, text spacing, captions and accessible PDFs for generated orders.
- Ship a GIGW 3.0 conformance matrix (Annexure II) and a Website Quality Manual template so departments can apply for STQC certification.

**(f) Status: PARTIAL**
- GIGW scope and the STQC process were verified.
- The WCAG "Level AA" mapping is only an official-site snippet.
- The RPwD amendment GSR number and the date are SECONDARY. The gazette was not read.

---

### GOV-3 Security audit, CERT-In directions and logs

**(a) Instruments**
- **CERT-In Directions under s.70B(6) of the IT Act,** No. 20(3)/2022-CERT-In, dated 28 April 2022, effective 60 days after issue. Read. There is also an FAQ from May 2022 and a notice of 27 June 2022 extending deadlines for MSMEs.
- **CERT-In "Guidelines on Information Security Practices for Government Entities"** (read). The PDF text carries no date. Secondary sources date it June 2023.
- **CERT-In Comprehensive Cyber Security Audit Policy Guidelines, July 2025.** SECONDARY only, not read.

**(b) What they require**
- **2022 Directions.** These bind "service providers, intermediaries, data centres, body corporate and Government organisations":
  - (i) Synchronise clocks to the NIC or NPL NTP servers, or to sources traceable to them.
  - (ii) Report the 20 incident types in Annexure I within 6 hours of noticing them. Annexure I includes "Attacks on Application such as E-Governance", data breach and data leak.
  - (iii) Designate a Point of Contact with CERT-In.
  - (iv) "Enable logs of all their ICT systems and maintain them securely for a rolling period of 180 days ... within the Indian jurisdiction".
- **2023 Guidelines, scope:** they apply to central Ministries and Departments, their attached and subordinate offices, PSUs and agencies.
- **2023 Guidelines, Part 1:**
  - Internal audit at least every 6 months.
  - Third-party audit at least yearly.
  - "Security audit should be performed prior to and after implementation ... or major enhancements" (15.3.4).
- **2023 Guidelines, CISO checklist:**
  - 5.1: host only in government data centres or MeitY-empanelled CSPs, never in a departmental LAN.
  - 5.2: audit by a CERT-In-empanelled auditor "prior to hosting, at-least once annually and also after any major changes".
  - 5.19: deploy only after audit clearance.
  - 5.21: HTTPS.
  - 5.22: "For government users, ensure that Websites and Applications are integrated with NIC SSO (parichay.nic.in)".
  - 5.23: MFA or 2FA for public users.
  - 5.24: follow GIGW.
  - 6.2: keep logs at least 180 days.
  - Onboard to NIC PRATIMAAN (SIEM). State departments do this through their state NIC centre.

**(c) URLs**
- https://www.cert-in.org.in/Directions70B.jsp
- https://www.cert-in.org.in/PDF/CERT-In_Directions_70B_28.04.2022.pdf
- https://www.cert-in.org.in/PDF/guidelinesgovtentities.pdf
- https://www.cert-in.org.in/PDF/Auditor_Guidelines.pdf

**(d) Status in law**
- The 2022 Directions are **mandatory** under s.70B. Non-compliance is punishable under s.70B(7).
- The 2023 Guidelines are expected of all central government entities. The CISO compliance part is stated as mandatory for central Ministries.
- State departments are not named in the scope, but they meet the same requirement through GIGW, state IT policies and tender clauses (inference).

**(e) Design implication for Sangam**
- Budget a CERT-In-empanelled audit before every go-live, every year, and after each major release. Keep a release cadence and change log that supports this.
- Use NTP configured to samay1.nic.in and samay2.nic.in.
- Keep tamper-evident application, audit and access logs for at least 180 days, stored in India, and export them to a SIEM such as PRATIMAAN.
- Add a security-incident workflow that produces a CERT-In report within the 6-hour window.
- Make Parichay the default login for officials and require MFA for citizens.

**(f) Status: VERIFIED**
- Both documents were read.
- The June 2023 date and the 2025 audit policy are SECONDARY.

---

### GOV-4 Data protection (DPDP)

**(a) Instruments**
- **DPDP Act, 2023** (No. 22 of 2023), Gazette 11 Aug 2023. Read.
- **DPDP Rules, 2025,** G.S.R. 846(E). The notification is dated 13 Nov 2025 and appeared in the Gazette on 14 Nov 2025; PIB gives 14 Nov. Read.
- **Rules commencement:**
  - Rules 1, 2 and 17-21 (the Board) came into force on publication.
  - Rule 4 (Consent Managers) comes into force after 1 year.
  - Rules 3, 5-16, 22 and 23 come into force after 18 months, around mid-May 2027.
- **Act commencement:** the separate notification bringing the Act's sections into force (reported as G.S.R. 843(E)) was not read and is UNVERIFIED.

**(b) Obligations that matter for a platform serving a government Data Fiduciary**
- **Legitimate use by the State, s.7(b) and Rule 5 with the Second Schedule.** The State may process data without fresh consent to "provide or issue ... subsidy, benefit, service, certificate, licence or permit". This applies where the person consented before, or where the data sits in a notified State database. The Second Schedule standards are:
  - lawful processing;
  - limitation to that purpose and to the data necessary;
  - accuracy;
  - retention only while needed;
  - security safeguards;
  - intimation to the person, with a contact and a link for exercising rights;
  - accountability.
- **Other legitimate use, s.7(c):** processing for State functions under any law.
- **Processor role, s.8(1)-(2).** The fiduciary (the department) remains responsible. A processor (Sangam's operator) may act only under a valid contract.
- **Security, Rule 6:**
  - encryption, masking or tokenisation;
  - access control;
  - access logs and monitoring;
  - backups;
  - logs kept for 1 year;
  - security clauses in the processor contract.
- **Breach, s.8(6) and Rule 7:**
  - Notify each affected person without delay.
  - Notify the Board without delay.
  - Send a detailed report within 72 hours.
- **Erasure and retention:**
  - s.8(7) requires erasure once the purpose is served.
  - s.17(4) says s.8(7), s.12(3) (the right to erasure) and, where no decision affects the person, s.12(2) do not apply to the State.
  - Rule 8(3) requires keeping personal data and processing logs for at least 1 year.
  - The Third Schedule time limits apply only to large e-commerce, online-gaming and social-media fiduciaries.
- **Children, s.9 and Rule 10:**
  - Verifiable parental consent is required.
  - There must be no tracking or behavioural monitoring.
  - Fourth Schedule Part B exempts processing for a subsidy, benefit, licence or permit "in the interests of a child" under s.7(b), limited to what is necessary.
- **Rights, Rule 14:** publish how people can exercise their rights, and answer grievances within 90 days.
- **Rule 9:** publish a contact for questions about processing.
- **Exemptions, s.17(2)(a):** the Act does not apply to State instrumentalities notified for sovereignty, security, public order and similar purposes.
- **Cross-border, Rule 15:** transfer abroad is allowed, subject to Central Government orders.
- **Data Protection Board:**
  - It is a "digital office" (Rule 20) with four members according to PIB.
  - Appeals go to TDSAT.
  - Penalties go up to Rs 250 crore for failed security safeguards and Rs 200 crore for breach-notification and child-data failures.
  - Member appointments were still pending as of August 2026 (SECONDARY).

**(c) URLs**
- https://egazette.gov.in/WriteReadData/2023/248045.pdf (Act)
- https://egazette.gov.in/WriteReadData/2025/267650.pdf (Rules)
- https://static.pib.gov.in/WriteReadData/specificdocs/documents/2025/nov/doc20251117695301.pdf
- https://www.livelaw.in/articles/india-data-protection-board-established-law-543751 (SECONDARY)

**(d) Status in law:** **mandatory** for all Data Fiduciaries, including State departments, subject to s.17. The key operational rules apply from about May 2027.

**(e) Design implication for Sangam**
- **Purpose tagging:** tag each workflow with its legal basis and purpose (s.7(b) scheme or licence ID, consent, or s.7(c)). Enforce data minimisation per form.
- **Notices:** send automatic intimation and notice messages that include the contact person and the rights link.
- **Encryption and masking:** encrypt at field level and mask Aadhaar and other identifiers.
- **Logs:** keep access logs for at least 1 year, with retention policies set per tenant.
- **Breach handling:** a breach-response workflow for the Board's 72-hour report, aligned with CERT-In's 6-hour report.
- **Children:** age and guardian capture with parental-consent evidence. Flag children's records to block analytics on them.
- **Grievances:** a rights and grievance queue with a 90-day SLA.
- **Processor contract:** a template with security clauses.
- **Erasure:** configurable and disable-able, since the State is exempt from s.8(7) but archival and other laws still apply.

**(f) Status: VERIFIED**
- The Act and Rules text were read.
- The Act-commencement notification and the Board's appointment status are UNVERIFIED or SECONDARY.

---

### GOV-5 Hosting and data localisation

**(a) Instruments**
- **MeitY GI Cloud (MeghRaj) empanelment of Cloud Service Offerings.** MeitY's "Guidelines for Procurement of Cloud Services" v1.0 (read, MCRHRDI-hosted copy).
- **MeitY Cloud Management Office, "Guidelines/Best Practices for User Departments on Cloud Security"** (read, egovstandards.gov.in copy).
- **CERT-In 2023 Guidelines, checklist 5.1** (read).
- **CERT-In 2022 Directions (iv)** on logs in India (read).
- **GIGW 3.0** on India hosting (read).

**(b) What they require**
- **Deployment models:** MeitY empanels CSPs for Public, Virtual Private and Government Community Cloud. Offerings are listed through GeM in a GI Cloud Services Directory open to Centre and States. CSPs must meet MeitY empanelment and "security guidelines as defined by STQC" (STQC audits them).
- **Data residency:** "all empaneled Cloud Service Provider would be offering cloud services out of Indian Data Centre facilities and ensure data residency within the country. Data ownership resides with the Government Department."
- **Exit terms:** the CSP may not delete data until 45 days after contract expiry. NOC and SOC facilities must be in India.
- **CERT-In 5.1:** host only in government data centres or MeitY-empanelled CSPs.
- **GIGW:** web infrastructure must be within India.
- **CERT-In Directions:** logs must be kept within Indian jurisdiction.
- **Security best-practices document:** describes itself as "advisory in nature".
- **Not found in this pass:**
  - a consolidated binding "government data must stay in India" rule beyond the clauses above;
  - NIC National Data Centre or State Data Centre hosting norms as a published document;
  - the current CSP empanelment list.
- An older MeitY policy document says sensitive government information must be stored in a Government Cloud. That was a snippet and its currency is uncertain.

**(c) URLs**
- https://www.mcrhrdi.gov.in/cio2022/presentations/law/Guidelines_Procurement_Cloud%20Services_v2.2.pdf
- https://www.meity.gov.in/static/uploads/2026/03/fea54ec9e544c77abbdb48d0f1da6264.pdf (current MeitY copy, returned 403 to my fetch)
- https://egovstandards.gov.in/sites/default/files/2026-03/Cloud%20Security%20Best%20Practices.pdf
- https://www.cert-in.org.in/PDF/guidelinesgovtentities.pdf

**(d) Status in law**
- Empanelment and residency are **mandatory** conditions for government use of cloud.
- The CERT-In hosting clause applies to central entities.
- The cloud security best practices are advisory.

**(e) Design implication for Sangam**
- Certify Sangam on all three databases (Oracle, SQL Server, PostgreSQL) for State Data Centre deployment, NIC deployment and MeitY-empanelled CSP deployment. A SaaS offering would itself need MeitY SaaS empanelment.
- Keep no foreign telemetry, CDN, SaaS dependency, e-mail or SMS gateway in the data path. Primary, DR, backups and logs must all stay in India.
- Provide data-export and exit tooling.

**(f) Status: PARTIAL**
- Residency and empanelment were verified from MeitY documents.
- SDC and NIC hosting norms and a binding localisation rule were not found.

---

### GOV-6 Identity and SSO

**(a) Instruments**
- **NIC Parichay:** single sign-on for government users (G2G).
- **Jan Parichay and MeriPehchaan:** the National Single Sign-On for citizens, launched 4 July 2022 (NIC Informatics, January 2023, read).
- **Rajasthan SSO** (sso.rajasthan.gov.in).
- **Aadhaar Act 2016, the Aadhaar (Authentication and Offline Verification) Regulations 2021,** and the Aadhaar Authentication for Good Governance (SWIK) Rules 2020 (G.S.R. 490(E)), amended by G.S.R. 88(E) of 31 Jan 2025 (snippets).

**(b) What they require or offer**
- **Parichay:**
  - It is the platform behind single sign-on into eOffice, eMail, eHRMS and similar NIC services.
  - It authorises users by department and by government e-mail address (@nic.in, @gov.in).
  - It uses MFA through Parichay Authenticator and Kavach.
  - CERT-In checklist 5.22 requires government applications to integrate with it.
- **Jan Parichay and MeriPehchaan:**
  - Integration options are REST API, SAML 2.0 and OAuth 2.0. **OIDC is not stated.**
  - MFA options are OTP, backup codes, tap and token.
  - Users can complete eKYC with Aadhaar, PAN or driving licence.
  - The NSSO federates JanParichay, e-Pramaan and DigiLocker.
  - Support: support-parichay@nic.in and servicedesk.nic.in.
  - Public step-by-step onboarding and SDK documentation: not found.
- **Rajasthan SSO:**
  - One SSO ID per person, covering citizens, industry and government employees.
  - Registration is via Jan Aadhaar, Google or Bhamashah.
  - Employees register through SIPF.
  - Integration protocol: not found.
- **Aadhaar requesting entities:**
  - Only entities that meet UIDAI Schedule A may become requesting entities (AUA or KUA). A KUA additionally gets e-KYC.
  - Sub-AUAs use an existing AUA.
  - Biometric and OTP data must never be stored.
  - The encrypted PID block (the identity data in an authentication request) may be buffered for at most 24 hours.
  - The Aadhaar number must not be used as a domain identifier.
  - Transactions must be logged for audit.
  - Under SWIK Rules r.3, authentication is "on a voluntary basis" for good governance, ease of living (added in 2025) and prevention of benefit leakage. Approval runs through the department and MeitY or UIDAI.
  - The Aadhaar Data Vault and consent rules sit in the regulations, which were not read in full.

**(c) URLs**
- https://parichay.nic.in/pnv1/assets/index.html
- https://govintranet.gov.in/
- https://janparichay.meripehchaan.gov.in/v1/pehchaan/about-us.html
- https://informatics.nic.in/uploads/pdfs/302101aa_34_36_egov_meripehchaan_compressed.pdf
- https://sso.rajasthan.gov.in/
- https://doitc.rajasthan.gov.in/Files/DOITCWEB/WriteReadData/Downloads/202506200733567644817correctionformforGOVTEMP.pdf
- https://uidai.gov.in/en/authentication-requesting-agency
- https://uidai.gov.in/images/The_Aadhaar_Authentication_and_Offline_Verifications_Regulations_2021-_Clean_copy-30122025.pdf
- https://uidai.gov.in/images/The_Aadhaar_Authentication_for_Good_Governance_Social_Welfare_Innovation_Knowledge_Rules_2020-updated_as_on_142025.pdf

**(d) Status in law**
- Parichay is **mandatory** for central government applications under CERT-In 5.22.
- Jan Parichay is advisory.
- State SSO is per state policy.
- Aadhaar rules are **mandatory** for anyone performing authentication.

**(e) Design implication for Sangam**
- Use a pluggable identity layer with SAML 2.0 and OAuth 2.0 connectors. Treat OIDC as a bonus, not a requirement.
- Ship adapters for Parichay (officials), Jan Parichay or MeriPehchaan (citizens) and state SSOs (Rajasthan and others).
- Never call UIDAI directly. Integrate as a Sub-AUA through the department's or state's AUA.
- Store only an Aadhaar reference key or token in a vault.
- Record consent and log each authentication.
- Offer non-Aadhaar alternatives, because authentication is voluntary.

**(f) Status: PARTIAL**
- Jan Parichay protocols and UIDAI obligations were verified.
- Parichay's own protocol and onboarding documentation and Rajasthan SSO's integration method were not found.
- The Aadhaar regulations were not read in full.

---

### Summary table

| Item | Status | Key requirement | Design implication | URL |
|---|---|---|---|---|
| GOV-1 Languages | PARTIAL | OL Rules 6 and 11: s.3(3) documents, forms and registers bilingual in Hindi and English. GIGW: bilingual, Unicode, language switch. 22 scheduled languages. | Bilingual UI and templates plus a state language; Unicode database; Hindi or English notings; BHASHINI hook | https://cag.gov.in/uploads/media/Official-Language-Rules-20200728115111.pdf |
| GOV-2 GIGW and accessibility | PARTIAL | GIGW 3.0 (2023) covers websites, web apps and mobile apps; WCAG 2.1 (AA); STQC Website Quality Certification; RPwD r.15 requires IS 17802 | WCAG 2.1 AA and IS 17802; GIGW conformance matrix for STQC | https://guidelines.india.gov.in/scope-and-objective/ |
| GOV-3 Security audit | VERIFIED | CERT-In-empanelled audit before hosting, yearly and after major changes; 6-hour incident reporting; 180-day logs in India; NIC NTP | Audit-ready release cycle, India-resident logs, SIEM export, incident workflow | https://www.cert-in.org.in/PDF/guidelinesgovtentities.pdf |
| GOV-4 DPDP | VERIFIED | s.7(b) State legitimate use; Rule 6 safeguards with 1-year logs; breach notice without delay plus 72-hour report; child consent; s.17(4) State erasure carve-out; Rules effective about May 2027 | Purpose tagging, notices, encryption and masking, breach and grievance workflows, configurable retention | https://egazette.gov.in/WriteReadData/2025/267650.pdf |
| GOV-5 Hosting | PARTIAL | Government DC or MeitY-empanelled (STQC-audited) CSP only; data residency in India | Deployable on SDC, NIC and empanelled cloud; no foreign dependencies | https://egovstandards.gov.in/sites/default/files/2026-03/Cloud%20Security%20Best%20Practices.pdf |
| GOV-6 Identity and SSO | PARTIAL | Parichay mandatory for government users; Jan Parichay supports REST, SAML 2.0 and OAuth 2.0; Aadhaar only via an approved AUA or KUA, no biometric storage | Pluggable SAML and OAuth identity layer; Sub-AUA model; Aadhaar vault | https://uidai.gov.in/en/authentication-requesting-agency |


---

# Appendix C — GOV-7…GOV-12 Verification Report (verbatim, 2026-11-04)

> Research report against official Indian government sources. It is summarised in Part IX.7a.

## Sangam: Indian Government Compliance Research, GOV-7 to GOV-12

Research date: 2026-11-04. Status key: **VERIFIED** means the official source was read. **PARTIAL** means some claims rest on search snippets, secondary sources, or official pages that could not be read in full. **UNVERIFIED** means the point was not found. "(secondary)" marks a non-official source. Several official PDFs (TRAI press releases and directions, the Public Records Rules) are scanned images with no text layer, so I could not read them. Claims that depend on them are labelled.

---

### GOV-7: Digital signatures

**(a) Rules, systems and versions**
- Information Technology Act, 2000 (amended 2008). Section 3 covers digital signatures, Section 3A covers electronic signatures, Section 5 gives legal recognition, and the Second Schedule lists the e-authentication techniques. India Code returned HTTP 403, so I did not read the official text. Secondary sources say the Second Schedule lists "e-authentication technique using Aadhaar or other e-KYC services". The word "other" was added on 5 March 2019 (secondary: https://melento.ai/en-in/blog/?p=3368). The eSign API spec v2.1 (§1, read) confirms the definitions: electronic signatures are techniques specified in the Second Schedule and include digital signatures, and digital signatures follow the procedure in Section 3. It also cites the "Electronic Signature or Electronic Authentication Technique and Procedure Rules, 2015" (https://cca.gov.in/sites/files/pdf/ACT/eSign-APIv2.1.pdf).
- Digital Signature (End Entity) Rules 2015, GSR 660(E), dated 25 Aug 2015. These set the signature formats (https://cca.gov.in/faq.html).
- **eSign API Specifications v2.1**: released 07 Feb 2023, ESP readiness by 07 Apr 2023, current. CA-eKYC-account eSign API is at v3.3 (09 Dec 2020). eSign Remote API (remote key storage) is v1.0 (27 Jan 2021), with v1.1 listed as 09 Sep 2026 (https://cca.gov.in/eSignAPI.html).
- **Electronic Signature – Application Integration Guidelines v1.0** (CCA, PDF metadata dated Nov 2022) (https://cca.gov.in/sites/files/pdf/guidelines/CCA-ESAIG.pdf).
- **DSC classes.** The CCA FAQ (read) describes Class 1, 2 and 3. Class 2 and 3 keys must be on FIPS 140-2 Level 2 hardware. A Class 3 certificate may be used wherever Class 2 is required. CAs "can opt out" of any class (https://cca.gov.in/faq.html). According to search snippets of the CCA Identity Verification Guidelines (v2.6), CAs may no longer issue Class 2 individual signing certificates on their own. They issue Class 3 certificates that also carry the Class 2 OID (https://cca.gov.in/sites/files/pdf/guidelines/CCA-IVG.pdf, PARTIAL). I did not find a CCA notice dated 1 Jan 2021 for the "2021 change". The IDRBT CA's CPS records "Removed section for Class 2" with a 2021 approval date (https://cca.gov.in/sites/files/pdf/Licensed-CA/IDRBT_4.0.2.pdf, snippet).
- **Empanelled ESPs** (read): eMudhra (25 Jun 2015), C-DAC (21 Jul 2015), Protean eGov (9 Nov 2016), Capricorn Identity Services (4 Oct 2017), Verasys (10 May 2019), CSC e-Governance Services (15 May 2019), CDSL Ventures (6 Mar 2020) (https://cca.gov.in/service-providers.html). Only licensed CAs may act as ESPs. An Application Service Provider (ASP) cannot sublet the service to others (https://cca.gov.in/faq.html).

**(b) What it offers a software product**
- **eSign v2.1 flow (read):**
  1. The ASP computes the document hash on the client side and takes the user's consent.
  2. The ASP builds a signed eSign request XML and redirects to the ESP URL, or uses the ESP SDK.
  3. The ESP verifies the ASP's signature, authenticates the user by e-KYC (OTP or biometric), and returns the signature in the response XML.
  - Each ESP has its own service URL and ASP ID, so these should be configurable per request (https://cca.gov.in/sites/files/pdf/ACT/eSign-APIv2.1.pdf).
- **Integration guidelines (read):**
  - Revocation is checked by CRL or OCSP.
  - If verification stays inside the application, standard signature formats are acceptable. If external relying parties must verify, use extended formats.
  - Use timestamps from licensed CAs.
  - The guidelines have a dedicated Long-Term Validation and Long-Term Archival (LTV/LTA) section.
  - Applications must not take custody of users' tokens.
  - Archive proof of verification at the time of DSC registration (https://cca.gov.in/sites/files/pdf/guidelines/CCA-ESAIG.pdf).
- **Legal validity (read):**
  - Digital signatures are "at par with hand written signatures".
  - A signature remains valid if the certificate was valid when it was signed, even if the certificate later expires or is revoked.
  - Aadhaar authentication by itself is not a signature (https://cca.gov.in/faq.html).
- **e-Office (read, CSMOP 2022 Box-e.7.1):** It is "mandatory for all officers of the rank of Dealing officers and above or equivalent" to use DSC or e-sign to sign eFiles before forwarding them. Ministries procure the DSCs. e-sign requires the officer's mobile number to be linked to Aadhaar. A delegated user must still sign in with their own account and DSC token (https://moef.gov.in/uploads/2017/09/CSMOP-2022-Sixteenth-Edition.pdf). Organisation SOPs describe e-Office's "Register for signing" step, which needs DSC (the Digital Signer Service) or e-sign with OTP (secondary: https://icssr.org/sites/default/files/2024-05/SOP-v1.pdf).

**(c) URLs:** https://cca.gov.in/eSignAPI.html, https://cca.gov.in/service-providers.html, https://cca.gov.in/faq.html, https://cca.gov.in/sites/files/pdf/guidelines/CCA-ESAIG.pdf

**(d) Mandatory or advisory:** Use of the IT Act and CCA framework is mandatory for any legally valid electronic signature. DSC or e-sign on eFiles is mandatory for Dealing Officers and above in central e-Office use (CSMOP 2022). The eSign API spec is mandatory for ESPs and ASPs.

**(e) Design implication for Sangam:**
- Build a pluggable signing service with two paths:
  - DSC token signing on the client, through a local signer agent, since tokens must stay with the user.
  - eSign v2.1 as an ASP, with more than one ESP configured and switchable per request.
- Sign the hash of each note or approval. Store the signatures as PAdES or CMS with LTV data (OCSP or CRL response plus a CA timestamp).
- Keep a per-user record of DSC registration and verification proof.
- Treat a plain login or OTP approval as authentication, not signature.

**(f) Status: PARTIAL.** eSign, ESP, CCA FAQ and CSMOP were read. The text of IT Act Sections 3, 3A and 5 and the Second Schedule was not read from India Code (HTTP 403). The Class 2 discontinuation rests on snippets of the CCA IVG.

---

### GOV-8: Payments

**(a) Systems, rules and dates**
- **PFMS** (CGA, Ministry of Finance) has moved to **https://pfms.gov.in** (https://pfms.nic.in). The site describes its scope as payments and exchequer control, DBT, fund-flow tracking, receipts and accounting, and an "established interface with state financial management systems, banks and other external systems" (https://pfms.gov.in, read).
- **GFR 232(v)** prescribes releasing funds to States and monitoring them through PFMS. **GFR 230(7)** sets the principle of just-in-time release. Both are cited in the DoE letter below (read).
- **SNA model**: DoE OM No. 1(13)PFMS/FCD/2020, dated 23 Mar 2021, effective 1 Jul 2021. **SNA-SPARSH**: DoE letter F.No.1(27)/PFMS/2020, dated 13 Jul 2023 (read) (https://doe.gov.in/files/public-finance-state-cna-sna-document/Letter_Dated_13_07_202_To_ALL_Chief_Secretaries_Principal_Secretaries.pdf). Search snippets say all CSS were to move to SPARSH by 31 Oct 2025, and a CGA SOP on SLS-wise mother sanction applies from 1 Apr 2026 (PARTIAL: https://cga.gov.in/Circular/Published/9198.aspx, https://cga.nic.in//writereaddata/file/SOPrevisedworkflowSNASPARSH95dt14082025.pdf).
- **EAT module** for Central Sector schemes: DoE OM of 15 Jul 2016 (universal roll-out of PFMS for CS schemes). A 12 Sep 2017 OM requires releases from 1 Oct 2017 to be based on PFMS expenditure and balance data (secondary: https://taxguru.in/corporate-law/universal-rollout-pfms-central-sector-schemes.html, https://www.icar.org.in/sites/default/files/Circulars/updated_updated_ImplementationofPFMSinICAR%281%29.pdf). A later DoE OM No. 3/(06)/PFMS/2023 dated 16 Jun 2023 covers CS-scheme release procedure (title only: https://doe.gov.in/files/public-finance-state-cna-sna-document/OM_dt_16_06_2023_to_all_secys.pdf).

**(b) Concrete integration facts**
- **SNA-SPARSH flow (read):**
  1. The SNA and implementing agencies register in **State IFMIS** and generate payment files there.
  2. The State treasury consolidates them into a "State Cyber Treasury" with SLS tags and flags, then shares the consolidated payment file with PFMS.
  3. The Ministry generates a central-share sanction against a "mother sanction". Files received by 3 PM are sanctioned the same working day.
  4. State IFMIS auto-pushes the file to **RBI e-Kuber**, which pays beneficiaries and sends debit notifications to PFMS and IFMIS.
  5. Where a State's IT is not ready, agencies may submit manual claims.
- **PFMS DBT FAQ (read, CGA, undated):**
  - External systems integrate through **SFTP**. The external system hosts its own SFTP server for UAT, and PFMS provides the production server.
  - Each system gets a **Source System ID (SSID)**, and schemes are mapped to it.
  - PFMS supplies a System Integration document, a sample SOP, an "API document" and Annexure i/ii (IPs, SSID, payment mode).
  - Payments route through NPCI (APBS/NACH). Aadhaar is "not mandatory for DBT" (https://gifmis.cga.gov.in/SitePages/doc/PFMS_DBT_FAQ.pdf).
  - A 2025 bank RFP mentions migrating from SFTP to API and ISO 20022 for PFMS. This is secondary and indicative only (https://www.jkgrameen.bank.in/adminpanel/files/10820266930103.pdf).
  - PFMS has also told integrated external systems to stop using non-compliant DSCs and weak ciphers (https://pfms.gov.in).
- **Citizen fee collection:**
  - **Bharatkosh / NTRP (CGA)** is the central non-tax receipt portal. Banks and payment aggregators onboard under NTRP integration documents (https://cga.gov.in/Page/Bharatkosh.aspx, PARTIAL).
  - **PayGov India** is run by NSDL Database Management (NDML) for MeitY (then DIT). Its integration document covers registration, payment request and response, checksum and reconciliation (https://mgov.gov.in/Resources/PayGov.pdf, read; the document is dated DIT-era).
  - **Maharashtra GRAS** is the Finance Department's e-Challan system, built by NIC Pune and maintained by the Directorate of Accounts & Treasuries. It accepts net banking, cards and bank-counter payments. The site does not describe a public department API (https://gras.mahakosh.gov.in, read).
  - **SBI ePay**: not found on an official source.

**(c) URLs:** https://pfms.gov.in, https://gifmis.cga.gov.in/SitePages/doc/PFMS_DBT_FAQ.pdf, DoE SNA-SPARSH letter (above), https://gras.mahakosh.gov.in, https://cga.gov.in/Page/Bharatkosh.aspx

**(d) Mandatory or advisory:** PFMS is mandatory for releasing Central funds and for CS/CSS fund tracking (GFR 232(v)). SNA-SPARSH is mandatory for notified CSS. Using PFMS for DBT payments of central schemes is required in practice. Payment gateways are chosen by each department or State.

**(e) Design implication for Sangam:**
- Do not move money directly. Sangam should produce sanction orders and approved payment instructions as signed files for State IFMIS or PFMS: SFTP batch first, with an API adapter later.
- Ingest status and response files to close bill and DBT workflows.
- Model the SSID, scheme code, SLS tag, mother-sanction limit, and the 3 PM cut-off.
- Build a payment-gateway abstraction with adapters for NTRP, PayGov and State systems (GRAS-type e-Challan), including checksum verification and a reconciliation job.

**(f) Status: PARTIAL.** SNA-SPARSH, the PFMS FAQ, the PFMS homepage and GRAS were read. The EAT OMs are secondary. The current PFMS API spec is not public. SBI ePay is UNVERIFIED.

---

### GOV-9: Documents (DigiLocker, API Setu)

**(a) Rules and versions**
- **Information Technology (Preservation and Retention of Information by Intermediaries Providing Digital Locker Facilities) Rules, 2016.** **Rule 9A** was inserted by an amendment notified on **8 Feb 2017**. Under it, documents issued or pushed by departments into DigiLocker are deemed at par with original physical documents. The J&K IT Department circular hosted on digilocker.gov.in (read) cites G.S.R. 711(E) and tells departments to treat DigiLocker documents as originals (https://digilocker.gov.in/assets/img/Circulars/DL%20Circular.pdf). The GSR number is cited inconsistently across sources, so it should be checked against the eGazette.
- **Issuer specs** (referenced in the official issuer workflow, read):
  - "Digital Locker dedicated repository (push) API specification **v1.7.2**", for Aadhaar-seeded documents.
  - "Pull API specification **v1.3**", for documents that are not Aadhaar-seeded (https://cdn.apisetu.gov.in/portal/assets/workflow_issuer.pdf).
  - The API Setu "DigiLocker Issuer APIs" OpenAPI spec is v3.0.0 (Feb 2021) (secondary listing: https://apis.guru/apis/apisetu.gov.in/issuer).
- **Requester spec v1.12** (Sep 2023) and MeriPehchaan requester spec v2.3 (https://cf-media.api-setu.in/resources/Requester-APISpecification-V1_12.pdf, snippet only).
- **API Setu**: launched by MeitY in Mar 2020 to implement the Open API policy of July 2015. It is run by Digital India Corporation. The site reports 5.2k+ APIs and 1.6k+ publishers (https://dic.gov.in/api-setu, read).

**(b) Integration process (read, issuer workflow)**
1. Register on the partner portal (demo: devpartners.digitallocker.gov.in) and wait for approval to get an Issuer/Org ID.
2. **Push model:** issue URIs against Aadhaar by CSV upload, by single-URI push API, or by CSV-upload API. Register a repository endpoint (REST, **POST/XML**, with an optional API key and keyhash) so DigiLocker can fetch documents.
3. **Pull model:** register Search and Pull services so citizens can pull documents by entering identifiers.
4. Doc types come from DigiLocker's catalogue (for example INCER for an income certificate). The URI format must follow the spec.

**(c) URLs:** https://cdn.apisetu.gov.in/portal/assets/workflow_issuer.pdf, https://digilocker.gov.in/assets/img/Circulars/DL%20Circular.pdf, https://dic.gov.in/api-setu, https://apisetu.gov.in

**(d) Mandatory or advisory:** Under Rule 9A, issued documents are legally equivalent to originals. Integration itself is advisory for central and state issuers, though some States mandate it (for example, the J&K circular). Requesters may accept these documents but are not required to (secondary).

**(e) Design implication for Sangam:**
- Licence and permit outputs: implement a DigiLocker **Issuer** adapter covering Push URI and the Pull Doc/Pull URI endpoints, returning signed PDFs with XML metadata, plus a URI and doc-type registry.
- DBT and licence intake: implement a **Requester** adapter (OAuth through MeriPehchaan) so applicants share issued documents instead of uploading scans.
- Store each fetched document with its URI and issuer as provenance.
- Publish Sangam's own APIs on API Setu in OpenAPI format.

**(f) Status: PARTIAL.** The issuer workflow and DIC page were read. The Rule 9A text and the gazette number were not read from the eGazette. The spec PDFs themselves (v1.7.2 and v1.3) were not opened.

---

### GOV-10: Notifications (SMS, email)

**(a) Rules and versions**
- **TCCCPR 2018** (6 of 2018), as amended by the **TCCCPR (Second Amendment) Regulations, 2025 (1 of 2025)**, dated **12 Feb 2025** (read). Most provisions took effect 30 days after publication, and some after 60 days (https://www.trai.gov.in/sites/default/files/2025-02/Regulation_12022025.pdf).
- **URL/APK/OTT whitelisting**: TRAI direction of 20 Aug 2024. Messages with non-whitelisted URLs are blocked from 1 Oct 2024 (PARTIAL; the official PDFs are scanned: https://trai.gov.in/sites/default/files/2024-10/PR_No.58of2024.pdf, https://www.trai.gov.in/sites/default/files/2024-11/Direction_28102024_0.pdf).
- **Traceability (PE–TM chain binding)**: messages on undeclared or mismatched chains are rejected from **11 Dec 2024** (PARTIAL, snippet of TRAI PR 92/2024: https://trai.gov.in/sites/default/files/2024-12/PR_No.92of2024.pdf).
- A 2025 direction requires pre-tagging of template variables (for example URL and numeric types), with 60 days to modify existing templates (PARTIAL, PIB snippet: https://www.pib.gov.in/PressReleasePage.aspx?PRID=2191273).

**(b) Concrete requirements (read, 2025 amendment)**
- New category **"Government Message"**: a message sent on the direction of Central or State Government or a constitutional body, "through the DLT platform". It needs no consent and cannot be blocked by preference settings.
- **Transactional** messages are limited to responses to a customer-initiated transaction within 30 minutes (OTP, alerts). Other informational messages are "Service".
- Headers must carry the suffix **-P, -S, -T or -G** (promotional, service, transactional, government).
- One content template may not be linked to more than one header.
- Access providers scrub URLs, APKs, OTT links and call-back numbers against sender-uploaded whitelists, and the sender must give an undertaking that they are not malicious.
- Sender registration requires physical verification, biometric authentication of the authorised person, and a linked unique mobile number.
- **NIC SMS Gateway** (sms.gov.in, read via search):
  - Offers PUSH, PULL, OTP, OBD voice, missed-call and Quick SMS services.
  - Each application needs its own ID, an HTTPS application, whitelisted server IPs, and a security-audit certificate.
  - The request is countersigned by the NIC coordinator and sent to smssupport@gov.in (https://sms.gov.in/jsp/services.jsp, which refused connection at fetch time; form: https://nfsa.gov.in/handlers/hndlViewImage.ashx?type=reportdocument&random=d5c03038-8378-4633-9fac-5517a8947ccc&id=13923b1b-9f39-4e79-85db-c583b1e8457c).
  - I did not find whether NIC registers DLT entities and templates on departments' behalf.
- **E-mail Policy of Government of India** (F.No. 2(22)/2013-EG-II, released Feb 2015):
  - Only NIC e-mail may be used for official communication.
  - The default address format is userid@gov.in.
  - DSC or encryption is required for sensitive mail (https://www.meity.gov.in/static/uploads/2024/02/E-mail_policy_of_Government_of_India_3-2.pdf, via search snippet).

**(c) URLs:** above.

**(d) Mandatory or advisory:** TCCCPR is mandatory for all senders of commercial and government SMS (DLT entity, header and template registration). The NIC e-mail policy is mandatory for officials of central and state bodies that use it.

**(e) Design implication for Sangam:**
- Build a notification service with a per-tenant DLT registry: PE-ID, header with the -G/-T/-S suffix, template ID with typed variables, whitelisted URLs, and TM chain.
- Validate messages against the registered template before sending.
- Never put non-whitelisted or free-form URLs in SMS.
- Support NIC SMS and private aggregators as interchangeable providers.
- Send official e-mail as SMTP relay through gov.in/NIC, not a third-party provider.
- Log delivery reports for audit.

**(f) Status: PARTIAL.** The 2025 amendment was read. The whitelisting and traceability dates come from snippets of scanned TRAI and PIB documents. The NIC SMS and e-mail policy details come from search snippets.

---

### GOV-11: Records retention and RTI

**(a) Rules and versions**
- **Public Records Act, 1993** (No. 69 of 1993, 22 Dec 1993) (read on the NAI page):
  - Section 2(e): "public records" include any material "produced by a computer or by any other device".
  - The Act prohibits unauthorised destruction, with punishment of up to 5 years and/or a fine of ₹10,000. The NAI web page's section numbering appears jumbled, so check the PDF.
  - Source: https://nationalarchives.nic.in/en/public-records-act-1993
- **Public Records Rules, 1997**: listed on the NAI site (https://nationalarchives.nic.in/en/public-record-acts-rules). The PDF is scanned and was not read. NAI guidelines (secondary snippets) say retention schedules must be vetted by NAI and revised at least every 5 years, and records older than 25 years may not be destroyed without appraisal (https://nationalarchives.nic.in/sites/default/files/2023-06/Guidelines_for_preparation.pdf).
- **CSMOP, 16th edition (2022)**, DARPG, launched Aug 2022, with a later DARPG amendment (https://moef.gov.in/uploads/2017/09/CSMOP-2022-Sixteenth-Edition.pdf, read; amendment: https://dipam.gov.in/ddaservice/dipam_docs/Circulars/Amendment%20of%20the%2016th%20Edition%20of%20the%20Central%20Secretariat%20Manual%20of%20Officer%20Procedure%2C%202022.pdf). No 17th edition was found. The e-Office procedures are now built into CSMOP 2022 as "Box-e" sections. A separate current "Central Secretariat Manual of e-Office Procedure" was not found as an official document.
- **Record Retention Schedule common to all Ministries/Departments, 2012** (DARPG, read) (https://www.darpg.gov.in/static/uploads/2026/07/5551a9839eeaa0e7c52c815331af8271.pdf).
- **RTI Act 2005**, as amended up to 18 Nov 2025 (amendments: RTI Amendment 2019, J&K Reorganisation 2019, DPDP Act 2023) (read) (https://rti.dopt.gov.in/Writereaddata/RTI%20Act,%202005%20(Amended)-English%20Version.PDF).
- **NAI guidance on electronic records**: no dedicated e-records management guideline found. The NAI Digitisation SOP specifies TIFF 6.0, JPEG and PDF/A, checksums, JHOVE validation, and CSV/XML metadata (https://nationalarchives.nic.in/sites/default/files/2025-03/Digitization%20SOP%20v2%2019%20sept%202024-1.pdf, snippet).

**(b) Concrete requirements (read)**
- **CSMOP 2022 §10.4 (physical files):**
  - A = keep and microfilm.
  - B = keep, do not microfilm.
  - C = keep for a specified period of at most 10 years; upgrade to B if needed.
- **CSMOP 2022 §10.4 (eFiles):** only **A** (permanent) and **C** (at most 10 years, upgraded to A if needed).
- **Recording (§10.5):** the file cover is marked with category and review year, for example "C/5 – Destroy (or Review) 2/2023".
- **Review (§10.9):** C files are reviewed when their period expires. A and B files are reviewed at year 25 with NAI, and survivors are transferred to NAI. Closed e-files are reviewed against the retention schedule and weeded or transferred under NAI accession procedure. e-files should also be kept on external media that is checked weekly.
- **RRS 2012:** retention is counted from the year the file is closed. The schedule's e-records annex uses Category-I (10 years on the department server, then transfer to the NAI server) and Category-II (10 years). This differs from CSMOP 2022's A/C labels; follow CSMOP 2022 as the later source.
- **RTI Act:**
  - §4(1)(a): keep records catalogued and indexed, and computerised and networked where appropriate.
  - §4(1)(b): suo motu publication of the listed particulars, including the decision-making procedure.
  - §7(1): reply within **30 days**, or **48 hours** where life or liberty is involved.
  - §8(3): information about events **20 years** old must be provided, subject to clauses (a), (c) and (i).
  - §8(1)(j) now exempts "information which relates to personal information" (DPDP amendment).

**(c) URLs:** above.

**(d) Mandatory or advisory:** The PRA, the Rules and the RTI Act are mandatory for public authorities (the PRA applies to central bodies; States have their own Acts, for example the UP Public Records Act 2025, per secondary sources). CSMOP is mandatory for the Central Secretariat and is commonly adopted by States as a model.

**(e) Design implication for Sangam:**
- Classify every file at closure (A/C for eFiles, A/B/C for physical files) and capture the retention years.
- Run a review queue by due year, and put a legal hold on files linked to RTI, audit or litigation.
- Block deletion of any record older than 25 years without an appraisal workflow.
- Export to NAI in PDF/A with checksums and CSV/XML metadata.
- Keep immutable audit trails.
- Add an RTI module with 30-day and 48-hour SLA timers, a §8(3) 20-year flag, a personal-data redaction step, and a §4(1)(b) disclosure publisher.

**(f) Status: PARTIAL.** CSMOP 2022, RRS 2012, the RTI Act and the NAI PRA page were read. The Public Records Rules PDF is scanned and was not read. A dedicated NAI e-records guideline was not found.

---

### GOV-12: Procurement

**(a) Rules and versions**
- **GFR 2017, Rule 149** (as amended; read from a consolidated copy amended up to 31 Jul 2024 hosted by IIT Kanpur, not doe.gov.in):
  - "Procurement of Goods and Services by Ministries or Departments will be mandatory for Goods or Services available on GeM."
  - Direct purchase up to ₹50,000.
  - L1 among at least 3 manufacturers for ₹50,000 to ₹10 lakh.
  - Bidding or reverse auction above that.
  - Source: https://iitk.ac.in/centralstores/data/GFR_2017_Amended_upto_31-07-2024.pdf
- **GFR startup relaxations** (same copy):
  - Prior turnover and prior experience may be relaxed for DPIIT startups if they meet quality and technical specifications (DoE OM F.1/1/2022-PPD, 02 Feb 2022).
  - DPIIT-recognised startups are exempt from bid security (EMD).
  - Earlier DoE OM of 25 Jul 2016 (forwarded copy: https://coal.gov.in/sites/default/files/2020-01/02-08-16-a.pdf).
  - DoE clarifies that procuring entities may still require prior experience for safety- or security-critical items (https://doe.gov.in/files/circulars_document/Request_clarification_provision_startups_MSMEs.pdf, snippet).
- **Public Procurement (Preference to Make in India) Order 2017, revised 19 Jul 2024**, DPIIT No. P-45021/2/2017-PP (BE-II) (read):
  - Class-I local supplier: at least 50% local content. Class-II: at least 20%.
  - Margin of purchase preference: 20%.
  - Purchases under ₹5 lakh are exempt.
  - Services include System Integrator contracts.
  - Global tenders under ₹200 crore need approval.
  - A statutory or cost auditor certificate is required above ₹10 crore.
  - Source: https://www.dpiit.gov.in/static/uploads/2025/07/458478309ccf301ceb58f63d02646f42.pdf
- **Software local-content method:** **not found.** MeitY has issued preference orders for cyber-security products (02 Jul 2018, revised 06 Dec 2019) and electronics, but secondary analysis (2022) says no software local-content computation method has been prescribed (https://dot.gov.in/sites/default/files/2021%2003%2016%20PMISec%20Security.pdf?download=1; secondary: https://www.nishithdesai.com/default.aspx?id=6230).
- **MeitY cloud empanelment (MeghRaj):**
  - Run through the AMBUD portal, with an STQC audit and annual surveillance.
  - Offered in deployment models including Government Community Cloud, from Indian data centres.
  - Empanelled offerings are bought through GeM (https://ambud.meity.gov.in, https://www.meity.gov.in/static/uploads/2024/02/f228f03f173a0e320758a504295f3a1f.pdf, snippets).
  - A PIB release reports 26 empanelled CSPs (https://www.pib.gov.in/PressReleasePage.aspx?PRID=2202897, snippet).
- **GeM software and SaaS listings:**
  - Software is listed under catalogue categories, with fields such as "Deployment option: Cloud" and OEM support period (example: https://mkp.gem.gov.in/enterprise-management-system-software/manageengine-servicedesk-plus-enterprise-edition-subscription-model-2/p-5116877-12118143652-cat.html).
  - IaaS on GeM is restricted to MeitY-empanelled, STQC-audited providers (snippet: https://gem.gov.in/searchresult/getServices).
  - No official step-by-step GeM SaaS onboarding guide or "Startup Runway" document was found.

**(b) What it means for a vendor:** Sangam must be listed on GeM as a product (perpetual or subscription licence) and/or a service (implementation, AMC, SI). Local-content self-certification is required in bids. Any cloud-hosted SaaS variant needs a MeitY-empanelled CSP underneath.

**(c) URLs:** above.

**(d) Mandatory or advisory:** GeM is mandatory for central Ministries and Departments for items available on GeM. Many States have adopted the same rule (for example AP G.O.Ms. 21: https://assets-bg.gem.gov.in/resources/pdf/G.O.Ms.No-21.pdf). PPP-MII is mandatory for central procuring entities. Startup relaxations are discretionary ("may be relaxed").

**(e) Design implication for Sangam:**
- Package Sangam as a GeM-listable on-premise licence (per department or per user) with fixed SKUs, plus implementation services.
- Keep IP, development and support in India and document local content of at least 50% so Sangam qualifies as Class-I. Avoid foreign proprietary runtime dependencies that dilute local content.
- Offer the cloud option only on a MeitY-empanelled CSP, preferably Government Community Cloud.
- Get DPIIT startup recognition if eligible.

**(f) Status: PARTIAL.** PPP-MII 2024 was read. GFR text was read from an institutional copy, not doe.gov.in. Cloud empanelment and GeM listing details come from snippets. The software local-content method is not found.

---

### Summary table

| Item | Status | Key requirement | Design implication | URL |
|---|---|---|---|---|
| GOV-7 Digital signatures | PARTIAL | IT Act / CCA. eSign API v2.1 (Feb 2023) through 7 empanelled ESPs. DSC or e-sign mandatory on eFiles for Dealing Officer and above (CSMOP 2022). Class 3 is used in place of Class 2. | Signing service with DSC agent plus eSign ASP (multi-ESP). Hash signing, PAdES/CMS with LTV and CA timestamp. | https://cca.gov.in/eSignAPI.html |
| GOV-8 Payments | PARTIAL | PFMS mandatory for central releases (GFR 232(v)). SNA-SPARSH (13 Jul 2023): IFMIS payment file to PFMS sanction to RBI e-Kuber. PFMS external integration by SFTP with SSID. | Produce signed payment and sanction files for IFMIS or PFMS, and ingest status files. Gateway adapters for NTRP, PayGov and GRAS. | https://doe.gov.in/files/public-finance-state-cna-sna-document/Letter_Dated_13_07_202_To_ALL_Chief_Secretaries_Principal_Secretaries.pdf |
| GOV-9 Documents | PARTIAL | Rule 9A (8 Feb 2017): issued DigiLocker documents equal originals. Issuer Push (v1.7.2) and Pull (v1.3) APIs, POST/XML endpoints. API Setu for Open APIs. | DigiLocker issuer adapter for licences and requester adapter for intake. Publish APIs on API Setu. | https://cdn.apisetu.gov.in/portal/assets/workflow_issuer.pdf |
| GOV-10 Notifications | PARTIAL | TCCCPR 2nd Amendment (12 Feb 2025): "-G" header suffix, Government Message via DLT, URL whitelist scrubbing, one template per header. Chain traceability enforced from 11 Dec 2024. | DLT registry per tenant, template validation, whitelisted URLs only. NIC SMS and gov.in e-mail adapters. | https://www.trai.gov.in/sites/default/files/2025-02/Regulation_12022025.pdf |
| GOV-11 Records / RTI | PARTIAL | CSMOP 2022: eFiles in category A (permanent) or C (at most 10 years), A/B reviewed at year 25 and transferred to NAI. PRA 1993 bars unauthorised destruction. RTI: 30 days or 48 hours, 20-year rule, §4 disclosure. | Retention classification at closure, review queue, legal hold, NAI export in PDF/A, RTI SLA module. | https://moef.gov.in/uploads/2017/09/CSMOP-2022-Sixteenth-Edition.pdf |
| GOV-12 Procurement | PARTIAL | GFR Rule 149: GeM mandatory where available. PPP-MII revised 19 Jul 2024: Class-I at least 50%, Class-II at least 20%, 20% preference margin. Startup turnover and experience relaxation, EMD exemption. Software local-content method not found. | GeM-listable licence and service SKUs. Document at least 50% local content. Cloud only via MeitY-empanelled CSP. | https://www.dpiit.gov.in/static/uploads/2025/07/458478309ccf301ceb58f63d02646f42.pdf |

---

# Appendix D — GOV gap closure via Chrome reading of official pages (verbatim)

## Government-source gap closure (Chrome pass, 2026-11-04)

Method: the user's Chrome (Claude in Chrome), one tab, public pages only. No files were downloaded and no CAPTCHA was solved.

**Notes on how the sites behaved:**
- **India Code** has moved to **indiacode.gov.in**. The old indiacode.nic.in deep links bounce or fail. Its built-in "PDF Ebook Viewer" (`/pdf-viewer/...`) shows PDF text in the page, so gazette PDFs hosted there could be read.
- PDFs hosted elsewhere (meity.gov.in, trai.gov.in, legislative.gov.in, depwd.gov.in) open in Chrome's own PDF viewer. `get_page_text` returns no text from that viewer, even for PDFs that have a text layer. Those items are marked NEEDS-DOWNLOAD (text extraction, not necessarily OCR).
- meity.gov.in and nic.gov.in now redirect to Hindi IDN domains (`इलेक्ट्रॉनिकी...` / `xn--m1bet4hqd2b...`). meity.gov.in site search returned nothing.
- **Search engines:** DuckDuckGo (html) worked for about 20 queries and then showed a CAPTCHA ("Select all squares containing a duck"). Bing showed a CAPTCHA on the first query. Neither was solved, so later look-ups were limited.

---

### 1. IT Act 2000: Sections 3, 3A, 5 and the Second Schedule — **CLOSED**

Source: India Code, The Information Technology Act, 2000 (Act 21 of 2000), administered by MeitY.
- Act page: https://indiacode.gov.in/act/d41fe391-b9c0-40b1-afdc-ad034c7ac62f/sections
- Second Schedule item: https://indiacode.gov.in/items/1e1abbf4-d485-4f96-ab72-c44bbcebd247 (file `sch_ii_a200021.pdf`, read in the India Code viewer)

**Section 3 (Authentication of electronic records).** "(1) ... any subscriber may authenticate an electronic record by affixing his digital signature." (2) Authentication uses an "asymmetric crypto system and hash function". (3) Anyone with the subscriber's public key can verify the record. (4) The private key and the public key form a unique functioning key pair. The heading was substituted with effect from 27-10-2009.

**Section 3A (Electronic signature).** Inserted with effect from 27-10-2009.
- (1) Notwithstanding s.3, a subscriber may authenticate any electronic record "by such electronic signature or electronic authentication technique which (a) is considered reliable; and (b) may be specified in the Second Schedule".
- (2) Reliability tests:
  - (a) the signature creation data or authentication data are linked to the signatory or authenticator "and to no other person";
  - (b) they were under the signatory's sole control at the time of signing;
  - (c) any alteration to the signature after affixing is detectable;
  - (d) any alteration to the information after authentication is detectable;
  - (e) any other prescribed conditions.
- (4) The Central Government may add techniques to, or omit them from, the Second Schedule by notification. (5) Each such notification is laid before Parliament.
- Linked rules on India Code: Electronic Signature or Electronic Authentication Technique and Procedure Rules, 2016 (27-04-2016) and the Amendment Rules, 2015 (30-06-2015).

**Section 5 (Legal recognition of electronic signatures).** Where any law requires information to be authenticated by signature, or a document to be signed, "such requirement shall be deemed to have been satisfied, if such information or matter is authenticated by means of electronic signature affixed in such manner as may be prescribed by the Central Government."

**Second Schedule ("Electronic Signature or Electronic Authentication Technique and Procedure", see s.3A(1)).** It lists two techniques:
1. **"e-authentication technique using Aadhaar or other e-KYC services"**
   - Inserted by G.S.R. 61(E) dated 27-1-2015. The words "or other" were added by S.O. 1119(E) dated 1-3-2019.
   - A Certifying Authority issues the DSC on the basis of e-authentication, the Form C particulars (Schedule IV, IT (CA) Rules 2000), digitally signed e-KYC information, and the applicant's electronic consent.
   - A trusted third party, offered by the CA, may handle key-pair generation, key storage and signature creation. The words "on hardware security module" were omitted by G.S.R. 539(E) dated 30-6-2015.
   - The Controller's e-authentication guidelines and Rules 3–12 of the Digital Signature (End entity) Rules, 2015 apply. Clause (g) was substituted by G.S.R. 446(E) dated 27-4-2016.
2. **"e-authentication technique and procedure for creating and accessing subscriber's signature key facilitated by trusted third party"**
   - Inserted by S.O. 3472(E) dated 29-9-2020.
   - The CA must ensure identity verification (per the Controller's Identity Verification Guidelines), secure key storage by the trusted third party, and "subscriber's sole authentication control to the signature key".
   - The trusted third party facilitates key generation, signing, submission of the DSC application, revocation, and destruction of the key.

### 2. DigiLocker Rule 9A — **CLOSED**

**Instrument:**
- Information Technology (Preservation and Retention of Information by Intermediaries Providing Digital Locker Facilities) Amendment Rules, 2017
- **G.S.R. 111(E), dated 8 February 2017**
- Gazette of India, Extraordinary, Part II, Section 3(i), No. 88, New Delhi, Wednesday, February 8, 2017
- F.No. 3(29)/2016-EG-II, signed by Ajay Kumar, Addl. Secy.
- In force from the date of publication.
- Principal rules: G.S.R. 711(E), dated 21 July 2016.

**Rule 9A** ("Issuing certificates or documents in Digital Locker System and accepting certificates or documents shared from Digital Locker Account at par with Physical Documents"):
- (1) "Issuers may start issuing and Requesters may start accepting digitally (or electronically) signed certificates or documents shared from subscribers' Digital Locker accounts at par with the physical documents in accordance with the provisions of the Act and rules made thereunder."
- (2) A document pushed by an issuer and accessed by a requester through the URI "shall be deemed to have been shared by the issuer directly in electronic form".
- Explanation: links to the "single source of truth" (issuer repositories) give automatic verification.

**Other changes made by the same amendment:**
- Rule 12 was substituted. Every Digital Locker service provider must publish a grievance officer; complaints are redressed within one month; appeal lies to the Digital Locker Authority within 15 days.
- Rule 17(2) now requires a "yearly audit". The half-yearly and quarterly audits were removed.

URLs:
- India Code item: https://indiacode.gov.in/items/a4e38f0a-5909-429c-8b66-cbb2556860fb (file `ru_cen_45_0_00025_1519710661826.pdf`, read in the viewer)
- List of rules under the IT Act: https://indiacode.gov.in/act/d41fe391-b9c0-40b1-afdc-ad034c7ac62f/sections?f.identifier_collection=RULE,equals&f.act_id=AC_CEN_45_76_00001_200021_1517807324077,equals

### 3. State Data Centre / NIC hosting norms — **PARTIAL** (the SDC guideline PDFs could not be read: NEEDS-DOWNLOAD)

**What was found:**
- **MeitY SDC guidelines.** "Guidelines for Technical and Financial Support for Establishment of State Data Centre (SDC)". The search-engine snippet of the official PDF reads: "State Data Centre (SDC) has been identified as one of the important element of the core infrastructure for supporting e-Governance initiatives of NeGP ... to consolidate services, applications and infrastructure to provide efficient electronic delivery of G2G, G2C and G2B services."
  - Annexure-1 covers "Best practices and Guidelines to Physical Security of the State Data Centre".
  - The full text was not readable.
  - PDF URLs (NEEDS-DOWNLOAD; they probably have a text layer, so OCR may not be needed):
    - https://www.meity.gov.in/static/uploads/2024/02/guidelines.pdf
    - https://www.meity.gov.in/static/uploads/2024/02/Annexure-1_sdc-1.pdf
- **GIGW 3.0 hosting and security norms** (official HTML, https://guidelines.india.gov.in/guidelines/, Cybersecurity chapter written by CERT-In):
  - Security audit before go-live: "Website, web application, web portal or mobile app have been Security Audited and an Audit Clearance certificate has been issued by NIC/ STQC/ STQC empanelled laboratory/CERT-In empanelled laboratory before hosting in production environment."
  - Audits are against OWASP ASVS, OWASP Top 10 and CIS benchmarks.
  - Backup: "Implement a backup solution to store data and system configurations ... backed up periodically." Keep backups off-site ("Do not store the backups on the same server as of the website"). Backup media must be in safe custody, with access restricted and logged.
  - The GIGW 3.0 introduction (https://guidelines.india.gov.in/introduction/) says STQC certifies cybersecurity aspects "on the basis of the 'safe to host' certificate issued by the cybersecurity auditors empanelled by CERT-In/STQC or the auditors of STQC or NIC", and that CERT-In advisories issued later count as updates to the chapter.
- **NIC National Cloud (MeghRaj)** (https://www.nic.gov.in/service/national-cloud/, which redirects to the IDN domain):
  - Service models offered: PaaS, IaaS, SaaS, containers, WAF, Vulnerability Assessment Service, "Backup Service – ... backup the data and application code ... based on various parameters like frequency, retention period", load balancer, S3WaaS (GIGW-compliant websites on NIC Cloud).
  - The page refers readers to https://cloud.gov.in/.
- **NIC data centres** listed at https://www.nic.gov.in/data-centres/: Delhi, Pune, Bhubaneswar, Hyderabad, Chandigarh, Guwahati.

**Not found:** a public NIC "hosting policy" page that states a hosting-location (India-only) rule or DR norms. No explicit "must be hosted in India / in an SDC" wording was read in this pass.

### 4. TRAI DLT directions — **CLOSED** (via official PIB releases; the direction texts themselves are NEEDS-DOWNLOAD)

TRAI direction list: https://trai.gov.in/release-publication/directions. All are titled "Direction on regarding measures to curb misuse of Headers and Content Templates under TCCCPR, 2018".

| Direction date | PDF URL (not readable in-browser) |
|---|---|
| 20/08/2024 | https://trai.gov.in/sites/default/files/2024-09/Direction_20082024.pdf |
| 30/08/2024 | https://trai.gov.in/sites/default/files/2024-09/Direction_30082024_0.pdf |
| 28/10/2024 | https://trai.gov.in/sites/default/files/2024-11/Direction_28102024_0.pdf |
| 30/11/2024 | https://trai.gov.in/sites/default/files/2024-12/Direction_30112024.pdf |
| 18/11/2025 (later revision) | listed on the same page |

**URL / APK / OTT whitelisting.** The direction was issued on **20 August 2024**. Its original effective date was 1 September 2024 (PIB 2043334, 8 Aug 2024: "With effect from 1st September 2024, no message, containing URLs/APKs that are not whitelisted, shall be allowed to be delivered"). TRAI granted a one-month extension on **30 August 2024**:
- PIB 2050109 (https://pib.gov.in/PressReleasePage.aspx?PRID=2050109): "all Access Providers ensure that traffic containing URLs/APKs/OTT links, which are not whitelisted, is not permitted with effect from October 1, 2024".
- The call-back-number timeline was "to be fixed separately".
- Senders must upload their whitelisted URLs, APKs and OTT links to their access provider's DLT portal (PIB 2058943).
- PIB 2068980 confirms whitelisting was implemented with effect from 1 October 2024.

**Traceability (PE–TM chain binding).** The same **20 August 2024** direction required traceability from 1 November 2024. It was extended to 30 November 2024 by the direction of **28 October 2024**, and then to 10 December 2024 by the direction of **30 November 2024**.
- PIB 2079435, 30 Nov 2024 (https://pib.gov.in/PressReleaseIframePage.aspx?PRID=2079435): "With effect from 11th December 2024, any traffic (messages) where the chain of Telemarketers is not defined or does not match with pre-defined chain shall be rejected."
- PIB 2086103, 19 Dec 2024 (https://pib.gov.in/PressReleasePage.aspx?PRID=2086103): Principal Entities "along with their Telemarketers (TMs), were required to declare and register their message transmission paths through blockchain-based Distributed Ledger Technology (DLT)". "Effective 11th December 2024, SMS traffic sent through unregistered paths is being rejected."
- TRAI press releases (PDF): PR No. 60/2024 https://trai.gov.in/sites/default/files/2024-09/PR_No.60of2024.pdf, PR No. 90/2024 https://trai.gov.in/sites/default/files/2024-12/PR_No.90of2024.pdf, PR No. 92/2024 https://trai.gov.in/sites/default/files/2024-12/PR_No.92of2024.pdf

### 5. Government SMS / e-mail access — **PARTIAL**

**SMS:**
- smsgw.sms.gov.in returned a browser error page (unreachable).
- NIC's messaging page (https://www.nic.gov.in/service/messaging/) says: "NIC also provides SMS gateway services that enable government departments to send bulk and transactional messages to citizens and officials ... used for alerts, public notifications, OTPs, and appointment confirmations."
- The NIC SMS web console https://easynicsms.gov.in/ is behind a login and verification code (BLOCKED for anything beyond the landing page).
- The public onboarding process found is **MeitY's Mobile Seva / MSDG (run by C-DAC)**, https://mgov.gov.in/RegisterPushService:
  - The department asks for an account by e-mailing msdp(at)cdac(dot)in with: user name, mobile number, alternate mobile, e-mail, Sender ID (up to 6 characters), department name, address and project details. Mobile Seva's form also asks for a password; it is not repeated here.
  - C-DAC reviews and approves the account and gives test credits.
  - SMS can then be sent from the portal services.mgov.gov.in (quick, group or bulk via Excel, with delivery reports) or through the HTTP POST API `https://msdgweb.mgov.gov.in/esms/sendsmsrequestDLT`.
  - API parameters include `senderid`, `templateid` ("must be obtained from the telecom operator", 12–19 digits, i.e. DLT), `key` and `smsservicetype`.
  - Messages are delivered with an operator prefix such as AD-/TA- in front of the Sender ID.

**E-mail:**
- NIC messaging page: NIC "offers secure and reliable email services to the Central and State Government officials ... maintained for official communication in Government through a Managed Service Provider". It also says: "'Gov.in' domain accounts are also maintained by NIC for use by Government departments", and the NICNET domain is "nic.in".
- The **E-mail Policy of Government of India** itself is a PDF and was not readable. Search snippet: "The Implementing Agency (IA) for the GoI e-mail service shall be National Informatics Centre (NIC)".
  - https://www.meity.gov.in/static/uploads/2024/02/E-mail_policy_of_Government_of_India_3-2.pdf (NEEDS-DOWNLOAD)
  - Related consolidated policy document: https://www.meity.gov.in/static/uploads/2024/03/Policy-Document.pdf
- A secondary government circular (MoSPI OM, 11 Dec 2017, search snippet only) mentions accounts "governed by the 'E-mail Policy of Government of India'" and restrictions on private e-mail such as Gmail and Yahoo.
- The exact policy wording that official e-mail must be on gov.in/nic.in was **not read** in this pass.

### 6. Public Records Rules, 1997 — **CLOSED** (scanned PDF, read visually page by page in the India Code viewer; no text layer)

**Instrument:** G.S.R. 43, Department of Culture, New Delhi, 9 January 1997, made under s.17(1) of the Public Records Act, 1993 (69 of 1993). Published in the Gazette of India, Part II, Section 3(i), 18 January 1997.
- India Code item: https://indiacode.gov.in/items/a51f12d3-7581-480f-93ea-7794915a4a6b (`PublicRecordRules1997.pdf`, 6.8 MB, 15 pages, scanned; English text on pp. 9–15)
- Viewer: https://indiacode.gov.in/pdf-viewer/a51f12d3-7581-480f-93ea-7794915a4a6b/f9c8d7e0-c049-4e9b-b45a-ad5aa8fc8385
- If machine-readable text is needed, this file **needs download + OCR**.

**Rule 2 definitions:**
- (l) "retention Schedule of records" means "a Schedule which provides the period of retention".
- (m) "review" means "periodical evaluation of recorded files on the expiry of the period of retention for determining their further retention or destruction".
- (k) "records of permanent nature" are records kept after recording for the period specified under rule 5(1).

**Rule 3.** The records creating agency nominates a Records Officer, not below Section Officer rank.

**Rule 5 (Acceptance of public records of permanent nature):**
- (1) The Director General or head of the Archives accepts permanent records "retained after recording by the records creating agency in its records room for the last twenty five years or more".
- (2) Each year, before 31 January, the Records Officer reports permanent records due for appraisal in Form-1.
- (3) After appraisal, a transfer list in Form-2 is prepared and the records are deposited.
- (4) Records may be kept beyond 25 years "for reasons to be recorded in writing".

**Rule 6.** Withdrawal of deposited records uses Form-3. Records must be returned within 6 months.

**Rule 7.** Classified records are reviewed for down-grading every fifth year.

**Rule 8.** Annual report in Form-5, every March.

**Rule 9 (Destruction of Public Records):**
- (1) "No public record shall be destroyed without being recorded and reviewed. In the month of January every year, each records creating agency shall record after consulting the records retention Schedule all those files on which action has been completed."
- (2) "No public records which is more than twenty five years old shall be destroyed by any records creating agency unless it is appraised."
- (3) A list of records proposed for destruction is prepared by the agency in Form-6 "and retained permanently for future reference".
- (4) Half-yearly report in Form-7 on recording, indexing, reviewing and weeding.
- (5) "Records shall be destroyed either by burning or shredding in the presence of records officer."

Rules 10 and 11 cover access to private and public records.

**Note:** the 1997 text names the Ministry of Human Resource Development (rule 8(2)). Any later amendments were not checked.

### 7. Software local-content method under PPP-MII — **PARTIAL**

**DPIIT PPP-MII Order, 2017** (official HTML, https://www.dpiit.gov.in/ministry/about-us/details/Title=Public-Procurement-(Preference-to-Make-in-India)-Order,-2017-ITMwETMtQWa). Last amended 19.07.2024.
- Class-I local supplier: at least 50% local content. Class-II: at least 20% and below 50%. Nodal ministries may notify higher thresholds.
- Excluded from local content: "Imported items sourced locally from resellers/distributors, license fees/royalties paid/ technical charges paid out of India, procurement/Supply of repackaged/refurbished/rebranded".
- Multi-item contracts use a weighted average.
- Purchase-preference margin is 20%.
- A penalty of up to 10% of contract value applies for mis-declared local content.

**MeitY notifications seen (via search snippets; documents not read):**
- **Electronic products:** MeitY notification F.No. 43(4)/2019-IPHW (W-43/4/2019-IPHW) dated **07.09.2020**, "a mechanism for calculating local content for 13 Electronic Products viz., (i) Desktop PCs, (ii) Thin Clients, (iii) Computer Monitors ...". Cited in MeitY's PLI 2.0 guidelines (https://www.meity.gov.in/static/uploads/2024/03/Guidelines_PLI-Scheme-ITHW-min.pdf) and in GeM bid documents.
- **Cyber security products:** "MeitY hereby issues revised Public Procurement (Preference to Make in India) Order 2019 for cyber security products". Hosted by DPIIT: https://www.dpiit.gov.in/static/uploads/2025/07/8484862f9274e58898f1aaaecdadc88a.pdf (NEEDS-DOWNLOAD; date and local-content definition not read).
- **Software products:** the National Policy on Software Products 2019 promises "an enabling framework ... for preferential inclusion of Indian software product in Government procurement in line with the PPP-MII Order" (https://www.meity.gov.in/static/uploads/2024/02/national_policy_on_software_products-2019.pdf).
- No standalone MeitY software-product local-content calculation notification was found.

**Not done:** a further search was blocked by the DuckDuckGo CAPTCHA. Next place to look: the MeitY orders page https://www.meity.gov.in/documents/orders-and-notices (redirects to the IDN domain) and the DPIIT FAQ PDF https://www.dpiit.gov.in/static/uploads/2025/07/8583e876591c96cacf45805f5f0cf705.pdf.

### 8. IS 17802 / RPwD (Amendment) Rules 2023 — **PARTIAL** (G.S.R. number not read; NEEDS-DOWNLOAD)

- The DEPwD lists "**RPwD (Amendment) Rules 2023 dated 10th May 2023 on ICT products and services**" (published on the site 28 Nov 2023): https://depwd.gov.in/en/rpwd-amendment-rules-2023-dated-10th-may-2023-on-ict-products-and-services/
- Gazette PDF (Chrome viewer, no text extracted; NEEDS-DOWNLOAD): https://divyangjan.depwd.gov.in/upload/uploadfiles/files/RPwD%20(Amendment)%20Rules%2C%202023%20-%20Accessibility%20standards%20on%20ICT%20products%20and%20Services_compressed.pdf
- The DEPwD site search for "17802" returned no matching page. The **G.S.R. number** and the wording that names IS 17802 (Part 1 & 2) were **not read**.
- India Code lists RPwD rules only up to the 2020 amendment.
- Related 2023 sectoral amendments on DEPwD: 13 July 2023 (Ministry of Culture), 17 July 2023 (Department of Sports), and the MoHUA Harmonised Guidelines 2021.

### 9. Eighth Schedule (22 languages) — **CLOSED** (official MHA statement via PIB; the Constitution PDF itself is NEEDS-DOWNLOAD)

- MHA statement in the Lok Sabha, 22 Dec 2004 (PIB Release ID 5928, https://pib.gov.in/newsite/erelcontent.aspx?relid=5928): "At present, the Eighth Schedule consists of 22 languages."
- The list: (1) Assamese, (2) Bengali, (3) Gujarati, (4) Hindi, (5) Kannada, (6) Kashmiri, (7) Konkani, (8) Malayalam, (9) Manipuri, (10) Marathi, (11) Nepali, (12) Oriya, (13) Punjabi, (14) Sanskrit, (15) Sindhi, (16) Tamil, (17) Telugu, (18) Urdu, (19) Bodo, (20) Santhali, (21) Maithili, (22) Dogri.
- History: 14 languages were in the original Constitution. Sindhi was added in 1967; Konkani, Manipuri and Nepali in 1992; Bodo, Dogri, Maithili and Santhali by the 92nd Amendment Act, 2003 (gazetted 8.1.2004).
- An MHA Lok Sabha answer (Starred Q. 118, 11 Feb 2025, search snippet) repeats "At present, 22 languages": https://www.mha.gov.in/MHA1/Par2017/pdfs/par2025-pdfs/LS11022025/118.pdf
- MHA note PDF: https://www.mha.gov.in/web/sites/default/files/2022-10/LanguagesintheEighthSchedule[1].pdf
- The current spelling is "Odia" (renamed by the Constitution (Ninety-sixth Amendment) Act, 2011). That comes from background knowledge and was not verified in this pass.
- Constitution of India 2026 English version (legislative.gov.in, opened in Chrome's PDF viewer, no text extracted): https://www.legislative.gov.in/static/uploads/2025/07/95ef471035ee0b5e4e5b1cac3d3584c1.pdf (NEEDS-DOWNLOAD)
- The rajbhasha.gov.in "Languages Included in the Eighth Schedule" pages return 404 on current URLs.

### 10. GIGW 3.0 → WCAG 2.1 Level AA — **CLOSED**

Source: https://guidelines.india.gov.in/new-features-of-gigw-3-0/ (Accessibility section). Quote: "GIGW 2.0 was compliant with version 2.0 of WCAG, however in the recent past WCAG has been upgraded to version 2.1 ... GIGW 3.0 has been upgraded to include these additional requirements ... **The current version ensures conformity with Level AA of WCAG 2.1.** In all 17 new success criteria have been added to the new version."

GIGW 3.0 was drafted jointly by NIC, the STQC Directorate and CERT-In, and conformity is certified through STQC's Certified Quality Website (CQW) scheme (https://guidelines.india.gov.in/introduction/).

### 11. Data Protection Board of India — **PARTIAL** (established and size fixed; appointments not verified)

- **Established:** G.S.R. 844(E), MeitY, 13 November 2025, under s.18(1) and (3) of the DPDP Act, 2023. "the Central Government hereby establishes, the Data Protection Board of India ... with effect from the date of publication of this notification in the Official Gazette." Head office is in the National Capital Region. Reference F. No. AA-11038/1/2025-CL&ES, signed by Ajit Kumar, Jt. Secy.
  - https://indiacode.gov.in/items/6e642814-ffd4-4c5b-b400-a540d0dc69fb
- **Size:** G.S.R. 845(E), 13 November 2025, under s.19(1): "the Data Protection Board of India shall consist of four members."
  - https://indiacode.gov.in/items/3cf1707c-d8ad-432b-92c1-64bcc45ce711
- India Code also lists "Enforcement Timeline for the DPDP Act" (13-11-2025) and 2 sets of rules under the Act. These were not opened.
  - DPDP Act notifications list: https://indiacode.gov.in/act/c058fa9f-eaf0-4ca3-98f1-3443b087bca9/sections?f.identifier_collection=NOTIFICATION,equals&f.act_id=AC_CEN_45_0_00003_2023-22_1763464807080,equals
- **Chairperson and member appointments:** no appointment notification was found on India Code.
  - dpb.gov.in returned a browser error page.
  - A PIB search through Bing hit a CAPTCHA (BLOCKED).
  - Whether a chairperson or members have been appointed is unverified.

### 12. Parichay onboarding for applications — **NOT-PUBLIC**

https://parichay.nic.in/ (landing page, FAQ and contact):
- "for application owners, this intuitive platform saves time, effort, and cost to build the authentication environments for every service independently."
- "Application owners can opt for various integration methods to integrate their application with Parichay i.e. Rest APIs, SAML 2.0, and OAuth 2.0."
- The page shows "4.1K+ Integrated Services" and 76.6 lakh+ enrolled users.
- FAQ "How can I join Parichay?" answers only for users: "Parichay is a Single Sign-on platform for Government of India and a government employee/user may register on Parichay using their Government Ids."
- There is no integration, developer or documentation link on the page. Its only links are Login, the Authenticator app stores, nic.in, meity.gov.in and **https://servicedesk.nic.in/** (toll-free 1800 111 555). The NIC Service Desk lists a "JanParichay – Authentication / authorization" category, and raising a ticket requires login (OTP or "Login with Parichay").
- Conclusion: integration documentation and onboarding are not public. Access appears to go through NIC (Service Desk ticket or NIC contact).

---

### Summary

| # | Gap | Status | Key official finding |
|---|---|---|---|
| 1 | IT Act s.3, 3A, 5, Second Schedule | CLOSED | India Code text read; Second Schedule = Aadhaar/e-KYC e-authentication (G.S.R. 61(E) 27-1-2015) + trusted-third-party remote signing (S.O. 3472(E) 29-9-2020) |
| 2 | DigiLocker Rule 9A | CLOSED | G.S.R. 111(E), 8 Feb 2017 (Gazette Extra. No. 88); principal rules G.S.R. 711(E), 21 Jul 2016 |
| 3 | SDC / NIC hosting norms | PARTIAL / NEEDS-DOWNLOAD | GIGW 3.0: audit clearance before production hosting, periodic off-site backups; SDC guideline PDFs unreadable; no public NIC hosting-location policy found |
| 4 | TRAI DLT directions | CLOSED (via PIB) | URL whitelisting: direction 20 Aug 2024, effective 1 Oct 2024; traceability: direction 20 Aug 2024, extended 28 Oct and 30 Nov 2024, rejection from 11 Dec 2024 |
| 5 | NIC SMS / e-mail policy | PARTIAL | SMS onboarding via MeitY Mobile Seva (C-DAC) is public; NIC SMS console behind login; e-mail policy PDF unreadable; NIC confirms gov.in/nic.in accounts |
| 6 | Public Records Rules 1997 | CLOSED (scanned; read visually) | G.S.R. 43, 9 Jan 1997; r.9 destruction rules (Jan review against retention schedule, no destruction of records over 25 years old without appraisal, Form-6 list kept permanently, burning/shredding) |
| 7 | Software local content (PPP-MII) | PARTIAL | DPIIT 50%/20% rule and exclusions; MeitY electronics notification 07.09.2020 and cyber-security PPP-MII 2019 seen only as snippets; no software-specific method found |
| 8 | IS 17802 / RPwD Amendment 2023 | PARTIAL / NEEDS-DOWNLOAD | RPwD (Amendment) Rules 2023 dated 10 May 2023 on ICT confirmed on DEPwD; G.S.R. number and IS 17802 wording not read |
| 9 | Eighth Schedule | CLOSED | 22 languages per MHA (PIB 5928; Lok Sabha answer 2025) |
| 10 | GIGW 3.0 to WCAG 2.1 AA | CLOSED | "The current version ensures conformity with Level AA of WCAG 2.1." |
| 11 | Data Protection Board | PARTIAL | Established by G.S.R. 844(E), 13 Nov 2025; four members by G.S.R. 845(E); appointments unverified (dpb.gov.in down, search CAPTCHA) |
| 12 | Parichay onboarding | NOT-PUBLIC | REST/SAML 2.0/OAuth 2.0 stated; no public developer docs; route is the NIC Service Desk |

#### PDFs that need download (text extraction or OCR)

**Scanned, needs OCR:**
- Public Records Rules 1997 (read visually here): India Code item https://indiacode.gov.in/items/a51f12d3-7581-480f-93ea-7794915a4a6b

**Text PDFs that Chrome's viewer would not expose (download + text extraction; OCR probably not needed):**
- MeitY SDC guidelines: https://www.meity.gov.in/static/uploads/2024/02/guidelines.pdf
- MeitY SDC Annexure-1: https://www.meity.gov.in/static/uploads/2024/02/Annexure-1_sdc-1.pdf
- E-mail Policy of GoI: https://www.meity.gov.in/static/uploads/2024/02/E-mail_policy_of_Government_of_India_3-2.pdf
- TRAI directions:
  - https://trai.gov.in/sites/default/files/2024-09/Direction_20082024.pdf
  - https://trai.gov.in/sites/default/files/2024-09/Direction_30082024_0.pdf
  - https://trai.gov.in/sites/default/files/2024-11/Direction_28102024_0.pdf
  - https://trai.gov.in/sites/default/files/2024-12/Direction_30112024.pdf
- RPwD (Amendment) Rules 2023 (ICT): https://divyangjan.depwd.gov.in/upload/uploadfiles/files/RPwD%20(Amendment)%20Rules%2C%202023%20-%20Accessibility%20standards%20on%20ICT%20products%20and%20Services_compressed.pdf
- MeitY cyber-security PPP-MII Order 2019 (hosted by DPIIT): https://www.dpiit.gov.in/static/uploads/2025/07/8484862f9274e58898f1aaaecdadc88a.pdf
- Constitution of India 2026 (English): https://www.legislative.gov.in/static/uploads/2025/07/95ef471035ee0b5e4e5b1cac3d3584c1.pdf

#### Blocks met (not bypassed)
- DuckDuckGo image CAPTCHA (after about 20 queries).
- Bing "One last step" challenge.
- easynicsms.gov.in login and verification code.
- servicedesk.nic.in ticketing login.
- smsgw.sms.gov.in and dpb.gov.in returned browser error pages.

---

# Appendix E — In-Place Edit Log (verbatim originals)

> These are the original lines that were later edited in place, either because the owner decided something or because a correction was accepted. They are kept here word for word so that no text from any source is lost. **They are history only. The current text is in the body, and the Baseline v1.0 precedence rules apply.**

| # | Where it was | Original text (verbatim) |
|---|---|---|
| E-1 | part_viii.md | \| D-2 Delivery \| RECOMMENDED: Stages A–E; awaiting the owner \| Same \| **Aligned**; still waiting for the owner's confirmation \| |
| E-2 | part_viii.md | \| D-4 SDKs \| OPEN \| Java + the pilot's 1–2 languages; keep the explicit PHP/.NET acceptance if it is required \| **OPEN.** The project owner's original requirement named PHP and .NET, so the pilot keeps them unless the owner changes that \| |
| E-3 | gov_requests.md | ## IX.7c Non-public documents: official request route |
| E-4 | gov_requests.md | **Status:** Evidence: NOT-PUBLIC. Decision: ADOPTED (request route). Delivery: pending G1 (a named department is required to send RQ-1…RQ-6). |
| E-5 | x5_sweep.md | \| II §19 R10 \| Global "null → incident" \| **E18 / A-…**: strict null only for Boolean routing and mandatory outputs (Part III §9 E18; VIII R10 revise) \| |
| E-6 | Part VI, VI.7 (D-3 row) | \| **D-3** First market \| **Your choice is needed.** The options with evidence are listed in Part III §6.2. Recommendation: **ISV embedding** or a **government/regulated approval integrator**, whichever you can reach. **Camunda 7 migration** only if a safe migration offer can ship early \| **OPEN**: needs your input (buyer access) \| Review §6.2; X-1 (Operaton already serves Camunda 7 users) \| — \| |
| E-7 | Part VI, VI.7 (D-2 status cells) | \| **RECOMMENDED**, awaiting your confirmation \| Review §15.2; PDF recommendation 1 (wedge MVP) agrees in direction \| — \| |
| E-8 | Part IX, IX.9 | \| Government constraints researched (IX.7) \| **Open**: GOV-1…GOV-12 to verify with official sources \| |
| E-9 | Part IX, IX.9 | \| Acceptance criteria approved (IX.8) \| **Open**: owner approval \| |
| E-10 | Part IX, IX.9 | \| G1: named first department/integrator + accountable person \| **Open**: buyer outreach \| |

Each original line also appears below as a plain line, unescaped, so it can be matched exactly:

~~~~text
| D-2 Delivery | RECOMMENDED: Stages A–E; awaiting the owner | Same | **Aligned**; still waiting for the owner's confirmation |
| D-4 SDKs | OPEN | Java + the pilot's 1–2 languages; keep the explicit PHP/.NET acceptance if it is required | **OPEN.** The project owner's original requirement named PHP and .NET, so the pilot keeps them unless the owner changes that |
## IX.7c Non-public documents: official request route
**Status:** Evidence: NOT-PUBLIC. Decision: ADOPTED (request route). Delivery: pending G1 (a named department is required to send RQ-1…RQ-6).
| II §19 R10 | Global "null → incident" | **E18 / A-…**: strict null only for Boolean routing and mandatory outputs (Part III §9 E18; VIII R10 revise) |
| **D-3** First market | **Your choice is needed.** The options with evidence are listed in Part III §6.2. Recommendation: **ISV embedding** or a **government/regulated approval integrator**, whichever you can reach. **Camunda 7 migration** only if a safe migration offer can ship early | **OPEN**: needs your input (buyer access) | Review §6.2; X-1 (Operaton already serves Camunda 7 users) | — |
| **RECOMMENDED**, awaiting your confirmation | Review §15.2; PDF recommendation 1 (wedge MVP) agrees in direction | — |
| Government constraints researched (IX.7) | **Open**: GOV-1…GOV-12 to verify with official sources |
| Acceptance criteria approved (IX.8) | **Open**: owner approval |
| G1: named first department/integrator + accountable person | **Open**: buyer outreach |
~~~~
