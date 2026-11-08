# Sangam

**Sangam** (संगम, "confluence") is an open-source, universal workflow and decision platform. It is being built on the **Operaton** engine (a Camunda 7 fork), and brings in the strengths of **Flowable**.

- **Standards:** BPMN 2.0.2, DMN 1.6, CMMN 1.1.
- **Free and open source (Apache 2.0):** every capability that commercial workflow suites sell as premium will be part of Sangam: modeller, operations cockpit, tasklist/work UI, admin, analytics and process mining, connectors and AI-agent steps.
- **Any technology stack:** Java, .NET, PHP, Python, TypeScript/Node and others through a REST/OpenAPI contract and worker SDKs.
- **Databases:** PostgreSQL, Oracle, SQL Server and MySQL as Tier 1.
- **Government packs:** processes of Indian central and state departments, including IFMS bills and payments, works, tenders, recruitment, RTI and grievances. These packs are one use of the platform, not its limit.

## Status

| Stage | State |
|---|---|
| A: need and scope | Complete |
| B: technical proof (engine selection) | Complete: base engine **Operaton 2.2.x** chosen on measured evidence |
| C: full pilot | Starting |

This repository currently contains:
- **[SANGAM.md](SANGAM.md):** the master specification. It holds the Approved Baseline, decisions (ADRs), requirements R1–R84, specifications, government research and the evidence register. Start with the "APPROVED BASELINE" section at the top.
- **[spike/](spike/):** the Stage B evaluation code and results. It contains adapters for both engines, a product layer, government-system simulators, a process corpus and experiments E1–E17. Results are in [spike/results/](spike/results/), and the decision scorecard is in [spike/results/scorecard.md](spike/results/scorecard.md).

## Build the spike

Requirements: JDK 21+ and Docker (for Testcontainers).

```
cd spike
./mvnw -q -DskipTests install
```

See [spike/README.md](spike/README.md) and [spike/RUN-QUEUE.md](spike/RUN-QUEUE.md).

## Security

To report a vulnerability, use GitHub private vulnerability reporting; see [SECURITY.md](SECURITY.md)
for the supported versions, response targets, scope and how fixes are released. Dependency alert
triage: [sangam/deploy/licence/VULNERABILITY-TRIAGE.md](sangam/deploy/licence/VULNERABILITY-TRIAGE.md).

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
