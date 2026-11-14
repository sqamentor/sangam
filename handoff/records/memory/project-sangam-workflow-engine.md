---
name: project-sangam-workflow-engine
description: "Goal, decisions and current state of the user's universal workflow engine \"Sangam\" (SANGAM.md + spike/)"
metadata:
  node_type: memory
  type: project
  originSessionId: 898f1ff7-0dcc-4d95-b425-830a9a5ead44
  modified: 2026-11-08T06:19:56.490Z
---

## Goal

The user is building **Sangam**, a universal workflow platform.
- It combines the strengths of Operaton (a Camunda 7 fork) and Flowable, and drives the weaknesses of both to zero.
- **ADR-D-14:** fully open source, for anyone in any industry. Every premium or commercial feature of Camunda 7/8, Flowable and Operaton is free: UI, backend, analytics, modeller, operations.
- Government of India packs (IFMS, works, tender, recruitment, RTI and so on, for all central and state departments) are the first market (D-3), not the limit.
- It must plug into any tech stack.
- **ADR-D-15:** Tier-1 databases are PostgreSQL, Oracle, SQL Server and MySQL.

**SANGAM.md** (project root, about 10.4k lines) is the single master; the APPROVED BASELINE v1.0 front section comes first. Spike code is in `spike/` and its evidence in `spike/results/`.

## Decisions

- **Closed:** D-2 Stages A–E; D-3 government integrator first; D-4 SDK tiers (Java, .NET, PHP, Python, TS); D-5 REST first; D-7 Liquibase 4.33.0; D-8 out-of-process scripts; D-11 revenue model; D-12 RPO ≤ 5 min / RTO ≤ 1 h; D-13 no government dependency (adapters + simulators; parked list X.6).
- **D-1 FINAL: Operaton 2.2.x** (85.2 vs Flowable 72.1). The owner stopped testing and finalized it (SANGAM.md X.0d).
- **D-16:** Operaton is a dependency, extended with plugins; engine patches only where unavoidable, offered upstream.
- **D-17:** Apache 2.0.
- **D-18:** public repo https://github.com/sqamentor/sangam.
- **D-19:** all Stage C workstreams run in parallel.
- Flowable strengths to port are listed in `spike/results/FLOWABLE-INTO-OPERATON.md`.
- `git push`: since 2026-11-08 the Sangam repo uses its own repository-local hook (`core.hooksPath=.githooks`); CVR's governance hook no longer applies (see [[project-boundaries]]). Still get the user's own-words push approval in the conversation.

## Owner priorities and sync rule (2026-11-08, restated)
- **Priority order (owner, refined 2026-11-08):** 1 IFMS systems; 2 every central and state government system, department-wise end to end; 3 every sector inside India (BFSI, banking, finance, e-commerce, manufacturing, healthcare, services); 4 worldwide — every government and every sector.
- **Plug-in-play like the top CRMs (Salesforce and peers):** Sangam must attach to any system the way those platforms do (API-first, metadata-driven, extension points, packages, events), have a long lifecycle (versioned, backward-compatible APIs and upgrades), and cover future failure and issue cases now, at the maximum level. Sangam must fit every one end to end — nothing missed — and stay completely generic, adopting each sector's changes by configuration without breaking, failing or creating new issues.
- **Plug-and-play for IFMS:** Sangam manages IFMS's workflow needs end to end as a plug-in.
- **Flowable + Operaton:** every strength of both in Sangam; every weakness of both eliminated; research first; ≥99.99% confidence.
- **Sync (owner, 2026-11-08, overrides IFMS's SD-317 note):** Sangam syncs with WorkFlow (IFMS workflow fork, local_ff9f2caf…), Hosting, DOIT परियोजना निगरानी प्रणाली and PHED. Among them there is no master except IFMS's master; the others are peers. IFMS reports the sponsor answered it directly "WorkFlow master" (IFMS SD-317, re-confirmed SD-433): the WorkFlow session is IFMS's master. Send every result to WorkFlow and copy IFMS main (and Hosting/DOIT/PHED when it concerns them). Sangam pushes go only through Sangam's own gate (`.githooks/pre-push` → `tools/governance/push-approval.mjs`, record with `--source user-message --words "<owner's verbatim message>" --conversation <id>` or `--tty`).
- **Owner's founding intent (restated 2026-11-08):** Sangam is the alternative to Flowable and Operaton and must be far more advanced and far better than both. Sangam is self-governing: it may take needs from IFMS, but everything else about its workflow platform (process, governance, design) Sangam decides itself; IFMS decisions (e.g. its SD-xxx) are input, not orders.
- **Sangam is independent:** it takes only feedback from IFMS, to fulfil IFMS's value. It stays generic and plug-and-play and never deviates from its own core principles and the architecture standards decided in SANGAM.md (ADR-D-*). An IFMS demand is implemented only within those principles — as a generic capability or as configuration/pack data, never as IFMS-specific code; if a demand conflicts with a principle, explain and propose the generic alternative instead of deviating.

## Fair evaluation in progress (2026-11-05)

- **Done:** E12–E15 favour Operaton (security 8.5 vs 3.5; Flowable CVE-2026-91197 XXE verified and unfixed).
- **Done:** the premium catalogue has 52 features; R63–R84 and UP-01…22 are proposed in `spike/results/PREMIUM-AND-UNIVERSAL-SCOPE.md`.
- **Done:** Operaton adapter fixes, 72/72 on H2, PG, Oracle and SQL Server.
- **Running:**
  - Flowable adapter parity
  - MySQL dialect: Operaton MySQL uses second-precision `datetime` and needs READ COMMITTED; being measured
  - gov patterns GP-01…55 and the domains (`experiments-gov-a/b/c`)
- **Queued:** `spike/run-queue.ps1` (Q1–Q7: four databases, T08/T35/T28, JDK 25, E9). Start it after the parity and MySQL agents finish.
- **Pending spec amendments:**
  - A-10…A-16 (XI.10.3)
  - operationId from the business key (T35 finding)
  - R63–R84 adoption
  - Jackson 2.22.3

## Stage C (started 2026-11-05)

- **Rule:** code everything end to end first; test last, re-running the Stage B benchmark cases (E1–E17, T-series) one by one, then end-to-end tests.
- **Where it lives:** product build in `sangam/` (ARCHITECTURE.md is binding), plus `sangam-ui` and `sdks/` (Java, .NET, Python, TypeScript, PHP).
- **Owner's added scope:**
  - **Fonts:** the central government's official Hindi TTFs; a state's own font if it publishes one, else the central one. Hindi and English now, other languages later.
  - **IFMS:** every central ministry and department, and all 28 states and 8 UTs, as configuration profiles.
  - **Compliance:** DPDP Act 2023 and Rules 2025, CERT-In 2022 directions, IT Act and SPDI rules, and state IT acts, all end to end.
  - **Sector packs:** banking, insurance, health, e-commerce and others.
  - **Robustness:** every scenario covered, especially the Flowable-derived features, and no issue in the Operaton core.

## Generic platform decisions (SANGAM.md X.0e, ADR-D-20…24)

- **D-20 Generic platform:** Sangam is a plug-and-play platform for any country, government department or private sector. Government clients come first, but nothing is built for one specific department. Domain knowledge lives only as data (profiles, seeds, DMN, packs). The `sangam-ifms` module was replaced by the generic `sangam-config` and `sangam-ledger`.
- **D-21 Deployment:** cell-based, with a control plane, a gateway and a national roll-up.
- **D-22 Boundary:** workflow + rules + generic ledgers/master data; integrate with existing IFMS/EHR/core banking.
- **D-23 First jurisdiction packs:** India, USA health (HIPAA/FHIR/X12), EU GDPR, international PFM.
- **D-24 Load target:** about 3,000 starts/s and 30,000 ops/s, ~1M users; at least 500 starts/s per cell.

**Why:** the owner corrected an IFMS-specific module design.
**How to apply:** never name a country, ministry or regulation in Java; propose generic capabilities first.

## How to apply

- Work in parallel with agents. Heavy runs go through the shared lock (see [[feedback-shared-heavy-lock]]).
- Batch questions to the owner.
- Verify facts and mark anything unverified.
- Merge every result into SANGAM.md with an integrity check.
- Reply in Hindi with English technical terms.
- See [[feedback-fast-parallel-work]].
