# Sangam

[![OpenSSF Best Practices](https://www.bestpractices.dev/projects/16866/badge)](https://www.bestpractices.dev/projects/16866)

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
- **[sangam/](sangam/):** the product: the Maven multi-module build ([sangam/pom.xml](sangam/pom.xml)), the server, the UI (`sangam/sangam-ui`), the SDKs (`sangam/sdks`) and the deployment assets ([sangam/deploy/](sangam/deploy/)). Release notes: [sangam/RELEASE-NOTES-0.1.0.md](sangam/RELEASE-NOTES-0.1.0.md).
- **[spike/](spike/):** the Stage B engine-selection code and results, **archived in place** and not maintained; do not build or deploy from it. See [ARCHIVE.md](ARCHIVE.md) and [sangam/docs/records/SPIKE-ARCHIVE.md](sangam/docs/records/SPIKE-ARCHIVE.md). The decision scorecard is in [spike/results/scorecard.md](spike/results/scorecard.md).
- **[sangam/docs/](sangam/docs/):** the integration documentation:
  - [INTEGRATION-GUIDE.md](sangam/docs/INTEGRATION-GUIDE.md): how to integrate Sangam into an application (M-018).
  - [PLUG-AND-PLAY.md](sangam/docs/PLUG-AND-PLAY.md): how far Sangam is plug and play today, mode by mode, with evidence.
  - [IFMS-INTEGRATION-GUIDE.md](sangam/docs/IFMS-INTEGRATION-GUIDE.md): the end-to-end guide to plugging Sangam into IFMS.

## Install

Requirements: JDK 21 or later (the build targets Java 21; CI builds on JDK 21, 25 and 27) and Docker (for the
compose stack and the Testcontainers database tests).

Build from source:

```
cd sangam
./mvnw -DskipTests install
```

On Windows use `mvnw.cmd`. Container images, the Helm chart, the air-gapped bundle and the supported deployment
targets are described in [sangam/deploy/README.md](sangam/deploy/README.md); the supported JDKs, frameworks,
drivers and browsers are in [sangam/deploy/SUPPORT-MATRIX.md](sangam/deploy/SUPPORT-MATRIX.md).

## Quick start

Run the server, the UI and Keycloak on a laptop with docker compose and PostgreSQL
([sangam/deploy/README.md, section 1.1](sangam/deploy/README.md#11-developer--pilot-laptop-docker-compose)):

```
cd sangam/deploy/compose
cp .env.example .env             # change every password; on a laptop also set SANGAM_SECURITY_PRODUCTION=false
docker compose --profile postgres up -d --build
```

Then open the UI at <http://localhost:3000>; the API is at <http://localhost:8080/api/v1> and Keycloak at
<http://localhost:8180> (realm `sangam`). The other database profiles (`oracle`, `sqlserver`, `mysql`), the
production switch and the add-on profiles are explained in the same section.

## Usage

[sangam/docs/INTEGRATION-GUIDE.md](sangam/docs/INTEGRATION-GUIDE.md) explains how to use Sangam from an
application: choosing a mode, embedding through the Spring Boot starter (`sangam/sangam-spring-boot-starter`),
calling the standalone server over its REST contract, tenants, security tokens and scopes, SDK workers
(`sangam/sdks`: Java, Kotlin, .NET, Go, Python, TypeScript, PHP, Rust and others), connectors and packs.
[sangam/docs/PLUG-AND-PLAY.md](sangam/docs/PLUG-AND-PLAY.md) states what works out of the box in each mode.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the issue and pull-request flow, the requirements for an acceptable
contribution, the coding standard and the test policy. Report bugs as
[GitHub issues](https://github.com/sqamentor/sangam/issues).

## Security

To report a vulnerability, use GitHub private vulnerability reporting; see [SECURITY.md](SECURITY.md)
for the supported versions, response targets, scope and how fixes are released. Dependency alert
triage: [sangam/deploy/licence/VULNERABILITY-TRIAGE.md](sangam/deploy/licence/VULNERABILITY-TRIAGE.md).
Cryptography used by the product, with code evidence: [sangam/docs/security/CRYPTO.md](sangam/docs/security/CRYPTO.md).

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
