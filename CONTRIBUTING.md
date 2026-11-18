# Contributing to Sangam

Thank you for helping. This file says how to propose a change, what a change must contain to be accepted, and how to
report bugs and security problems. Sangam is licensed under Apache 2.0 ([LICENSE](LICENSE)); by contributing you agree
that your contribution is licensed under the same terms.

## Reporting bugs and asking for features

- **Bugs and feature requests:** open a GitHub issue at <https://github.com/sqamentor/sangam/issues>. For a bug, give
  the version or commit, the module (for example `sangam-server`, `sangam-ui`, an SDK, a deployment asset), the
  database, the steps to reproduce, what you expected and what happened. Logs and a minimal BPMN/DMN file help.
- **Security vulnerabilities:** do **not** open a public issue. Report privately as described in
  [SECURITY.md](SECURITY.md) (GitHub private vulnerability reporting).

## Issue and pull-request flow

1. For anything beyond a small fix, open or find an issue first so the approach can be agreed before you write code.
2. Fork the repository and create a branch from `main`.
3. Make the change with its tests (see below). Keep one topic per pull request.
4. Sign off every commit (`git commit -s`, see [Developer Certificate of Origin](#developer-certificate-of-origin-dco)).
5. Open a pull request against `main` and fill in every section of the template
   ([.github/pull_request_template.md](.github/pull_request_template.md)): what and why, the decision (ADR) it
   follows, the requirement ids it covers and the tests that prove them, and the checklist.
6. CI runs on the pull request (`.github/workflows/`: build and tests, database matrix, security analysis,
   governance). A maintainer reviews the change; code owners per area are recorded in
   [.github/CODEOWNERS](.github/CODEOWNERS).

## Requirements for an acceptable contribution

A pull request is accepted when:

- it fixes the **root cause** (no workaround) and keeps domain knowledge in data, not in engine code;
- it adds or updates **tests** that prove the change (see [Test policy](#test-policy)), and no assertion is weakened
  or removed;
- the build and the test suite of the affected modules pass;
- generated files are changed only through their generators, and a second generator run gives no diff (the pull
  request template lists them: packgen `--check`, profilegen, `generate_changelogs`, `gen_openapi`);
- new dependencies are pinned and pass the licence policy in
  [sangam/deploy/licence/licence-policy.json](sangam/deploy/licence/licence-policy.json);
- requirement ids it covers are recorded with their proving tests in
  [sangam/docs/records/TRACEABILITY-REGISTER.csv](sangam/docs/records/TRACEABILITY-REGISTER.csv);
- a change that contradicts a recorded decision (ADR in [SANGAM.md](SANGAM.md)) proposes a new ADR first;
- every commit is signed off (DCO).

The internal definition of done the maintainers use is in [sangam/COORDINATION.md](sangam/COORDINATION.md).

## Coding standard

- **Java:** the code targets Java 21 (`maven.compiler.release` 21 in [sangam/pom.xml](sangam/pom.xml)); CI builds on
  JDK 21, 25 and 27 (`.github/workflows/ci-build.yml`). Follow the style of the surrounding code; new source files
  start with an `SPDX-License-Identifier: Apache-2.0` header.
- **Static analysis:** the Maven profile `sangam-static-analysis` in [sangam/pom.xml](sangam/pom.xml) compiles with
  Error Prone and NullAway (`-Xplugin:ErrorProne ... -Xep:NullAway:WARN -XepOpt:NullAway:AnnotatedPackages=org.sangam`),
  and the profile `sangam-spotbugs` runs SpotBugs with FindSecBugs. The `security` workflow
  (`.github/workflows/security.yml`) runs both on every change under `sangam/`, together with CodeQL and Semgrep
  (local rules in [semgrep/](semgrep/)). These checks are currently **report-only**: Error Prone runs with
  `-XepAllErrorsAsWarnings` and NullAway at `WARN`, and SpotBugs with `failOnError=false`; making them blocking is a
  pending owner decision. A contribution must not add new findings. Run them locally with:

  ```
  cd sangam
  ./mvnw -Psangam-static-analysis,sangam-spotbugs -DskipTests verify
  ```

- **Other languages:** the UI (`sangam/sangam-ui`) and the SDKs (`sangam/sdks`) follow the lint and test setup in
  their own directories; lockfiles are required for every package manager.

## Test policy

- **Every bug fix and every feature adds tests.** A fix adds a **regression test that fails without the fix and passes
  with it** (write it first and see it fail). A feature adds tests for its behaviour, including the error and
  refusal paths.
- Do not weaken, skip or delete an existing assertion to make a build pass.
- A claim about database behaviour is proven on that database: the integration tests run against PostgreSQL, Oracle,
  SQL Server and MySQL through Testcontainers (`.github/workflows/db-matrix.yml`), so Docker is needed to run them
  locally.
- The Java tests run with `./mvnw verify` in `sangam/` (CI's quick job uses
  `-Dsangam.db=h2 -DskipITs -Dsangam.it.skip=true`); the governance scripts have their own tests
  (`python -I -m unittest discover -s .github/scripts`).

## Developer Certificate of Origin (DCO)

Every commit must carry a `Signed-off-by: Your Name <you@example.com>` trailer whose e-mail matches the commit
author, as `git commit -s` writes it. By signing off you certify the
[Developer Certificate of Origin 1.1](https://developercertificate.org/). The `governance` workflow checks this on
every pull request with [.github/scripts/dco_check.py](.github/scripts/dco_check.py); the check is report-only today.

## Licence

Contributions are accepted under the [Apache License 2.0](LICENSE). Do not submit code you do not have the right to
license under it, and keep upstream licence and notice text when you port code (see [NOTICE](NOTICE)).
