# Security Policy (SG-12)

## Reporting a vulnerability

Please **do not** open a public issue, pull request or discussion for a suspected vulnerability.

Report it privately through GitHub's private vulnerability reporting for `sqamentor/sangam`:

1. Open <https://github.com/sqamentor/sangam/security/advisories/new>
   (repository **Security** tab, then **Report a vulnerability**).
2. Include the affected component (server module, UI, SDK, deployment asset), version or commit,
   reproduction steps or proof of concept, impact as you understand it, and any suggested fix.

The report and all follow-up stay inside the draft security advisory until a fix is published.
We do not use a personal e-mail address as a reporting channel.

## Supported versions

| Version | Supported |
|---|---|
| 0.1.x (`main` branch) | Yes |
| Anything older / forks / other branches | No |

Sangam is pre-1.0. Only the current `main` line receives security fixes for now; this table will
grow when release branches exist.

## Response targets

| Step | Target |
|---|---|
| Acknowledge the report | 3 business days |
| Initial triage and severity (CVSS) | 7 days |
| Fix or mitigation, critical / high | 30 days |
| Fix or mitigation, moderate / low | 90 days or next minor release |

Targets are goals, not contractual SLAs. We keep the reporter informed in the advisory thread and
credit the reporter in the published advisory unless they ask not to be named.

## Scope

In scope: code in this repository that ships or deploys Sangam: the `sangam/` Maven reactor
(server, engine, REST, security, persistence, connectors and all other modules), `sangam/sangam-ui`,
the SDKs under `sangam/sdks/`, the CLI and tools under `sangam/tools/`, and deployment assets under
`sangam/deploy/` (Dockerfiles, Helm/compose, licence policy).

Out of scope: `spike/` (Stage B evaluation code, never shipped), example and test fixtures,
vulnerabilities that require an already-compromised host or administrator, social engineering,
denial of service by volume, and issues in third-party dependencies that are already tracked by
Dependabot (see `sangam/deploy/licence/VULNERABILITY-TRIAGE.md`). Third-party issues are
still welcome when Sangam's usage makes them exploitable.

Please test only against your own deployment. Do not access data that is not yours.

## How fixes are released

1. Fixes are developed in a private fork attached to the GitHub security advisory.
2. The fix is merged to `main` together with a regression test; dependency fixes go through the
   root `dependencyManagement` / `sangam-bom` (Maven) or `package.json` + lockfile (UI), keeping
   the licence guards (Liquibase 4.x, SG-27 FEEL pins, LIC-1 no GraalJS) intact.
3. The advisory is published with a CVE where applicable, affected and fixed versions, and
   upgrade or mitigation steps. Release notes reference the advisory.
4. Dependency alerts that cannot be fixed are triaged and justified in
   `sangam/deploy/licence/VULNERABILITY-TRIAGE.md`, never silently dismissed.
