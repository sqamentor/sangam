# Non-Maven compliance and supply-chain findings (lane W3-X2, 2026-11-12)

Read-only review under owner decision 112 (epic E16). Scope: everything that is not a Maven artifact, meaning npm (UI and SDKs), the PyPI, NuGet, Composer, Cargo and Go SDK manifests, container images and bundled runtimes, fonts, and CI tooling. Tree reviewed: `D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524` (live, other lanes editing; nothing in it was changed). Nothing was installed, downloaded, pulled or run, except local read-only hashing and parsing of files already on disk.

Companion inventory: `NON-MAVEN-INVENTORY.csv` (487 rows, one decision each).

| Decision | Rows |
|---|---|
| ACCEPTED FOR DEFINED USE | 349 |
| ACCEPTED WITH CONDITIONS | 79 |
| REVIEW REQUIRED | 58 |
| REJECTED FOR DEFINED USE | 1 |

Policy basis: `sangam/deploy/licence/licence-policy.json`, `check_licences.py`, and owner decisions 56, 70, 71, 77, 78, 79, 94, 95, 96, 102, 105, 106, 111 and 112.

Evidence tiers used for decisions. Only E1 or E2 can support an ACCEPTED decision. E3 or E0 leads to REVIEW REQUIRED (decision 112: "unknown stays unresolved").
- E1: the artifact is on disk (installed `package.json` plus its LICENSE file, cargo registry `Cargo.toml`, local interpreter metadata, a file hash).
- E2: an owner decision or a `licence-policy.json` entry names the component and its licence.
- E3: only a repository comment or general knowledge.
- E0: nothing.

Handoff snapshots under `handoff/worktree-snapshots/**` hold older copies of the same manifests. They are not built or shipped, and decision 111 schedules their removal, so they are out of scope and not inventoried.

---

## 1. VERIFIED (each item checked on 2026-11-12)

### npm / UI (`sangam/sangam-ui`)
- **V-01. Lockfile complete and integrity-pinned.** `sangam/sangam-ui/package-lock.json` (lockfileVersion 3) has 369 package entries. All 369 carry an `integrity` sha512 and resolve from `registry.npmjs.org`. 149 are in the production (shipped) closure (12 direct) and 220 are dev-only. Every direct dependency in `package.json` is an exact version, no ranges. Evidence: parsed lockfile.
- **V-02. Installed tree matches the lockfile.** Every installed `node_modules/**/package.json` version equals its lock version. No installed package lacks a lockfile entry. 50 platform-specific optional dev binaries (`@esbuild/*`, `@rollup/rollup-*`) are not installed on this Windows host, so their licence comes from lockfile metadata only (all MIT, dev only). Evidence: `sangam/sangam-ui/node_modules`.
- **V-03. Licences of the shipped closure** (149, from installed `package.json` plus a LICENSE file):

  | Licence | Packages |
  |---|---|
  | MIT | 128 |
  | bpmn.io (LIC-8) | 11 |
  | Apache-2.0 | 5 |
  | ISC | 2 |
  | OFL-1.1 | 2 |
  | `(MPL-2.0 OR Apache-2.0)` (dompurify 3.4.17; elect Apache-2.0) | 1 |

  No GPL, AGPL, LGPL or SSPL appears in the shipped npm closure. Dev-only licences include MPL-2.0 (axe-core 4.14.0) and CC-BY-4.0 (caniuse-lite, build data). Five shipped MIT packages have no LICENSE file in the package (`@bpmn-io/semver-compat`, `component-props`, `component-xor`, `dom-iterator`, `domify`), so the notices file must reproduce the MIT text.
- **V-04. bpmn.io (LIC-8).** 11 shipped packages are covered by the `LicenseRef-bpmn.io` conditional purls: bpmn-js 17.11.1, dmn-js 16.8.2 and its 5 sub-packages, diagram-js 14.11.3, `@bpmn-io/form-js-editor`/`-viewer` 1.12.0, and `@bpmn-io/properties-panel` 3.58.1. Seven of them declare `"SEE LICENSE IN LICENSE"`. diagram-js and properties-panel declare `MIT`. `node_modules/bpmn-js/LICENSE` lines 13-16 require the watermark to stay "fully visible and not visually overlapped". The copyright holder is Camunda Services GmbH. Existing controls: the `ui.yml` step "bpmn.io watermark kept (LIC-8)" and `src/components/BpmnDiagram.watermark.test.tsx`. **Limit of the control:** the CI step only greps `dist/` for the string `bpmn.io`. It does not prove the watermark is visible or not overlapped.
- **V-05. OFL web fonts are outside the policy.** `@fontsource/noto-sans` 5.1.0 and `@fontsource/noto-sans-devanagari` 5.1.0 (OFL-1.1, LICENSE present) are direct production dependencies. The OFL-1.1 conditional in `licence-policy.json` lists only `pkg:generic`/`pkg:github` Noto fonts for PDF embedding. `check_licences.py:128-132` treats a conditional licence on a purl that is not listed as **forbidden**. So the UI SBOM step in `release.yml` (`--strict`) and in `ui.yml`/`licence-check.yml` will report FORBIDDEN LICENCE for both packages. This is predicted from the code and was **not run**.
- **V-06. A second OFL font is bundled outside the policy and has a conflicting licence statement.** `sangam-ui/public/fonts/gov/central/sakal-bharati/SakalBharati.ttf` (SHA-256 F27CF39C…0805, re-hashed and equal to FONTS.md) ships in the UI image. Its name ID 13 and the C-DAC page say OFL-1.1, but name ID 10 says "FOR NON-COMMERCIAL FREE USAGE" (`sangam-ui/FONTS.md` §2.1). Decision 70 says any other OFL font needs a new owner decision, and none exists for this one.
- **V-07. IBM install-time telemetry.** `@carbon/grid` 11.66.0 and `@carbon/layout` 11.63.0 (Apache-2.0, shipped closure via `@bpmn-io/form-js-viewer`) run the postinstall `ibmtelemetry --config=telemetry.yml` (`@ibm/telemetry-js` 1.12.0). The lockfile marks `hasInstallScript` for `@carbon/grid`, `@carbon/layout`, `esbuild`, `fsevents` and `inferno`. `npm ci` runs without `--ignore-scripts` in `ui.yml:46`, `release.yml:120`, `sdks.yml` and `ui.Dockerfile:28`. Only `licence-check.yml:53` uses `--ignore-scripts`.
- **V-08. The repo's own UI licence script diverges from policy.** `sangam-ui/scripts/list-licences.mjs` accepts OFL-1.1 and Artistic-2.0 globally and uses its own bpmn.io name regex. `licence-policy.json` allows neither generally. Two sources of truth exist for the same question.
- **V-09. TypeScript SDK.** `sangam/sdks/typescript/package-lock.json` has 3 entries (`@types/node` 20.19.43 MIT, typescript 5.9.3 Apache-2.0, undici-types 6.21.0 MIT), all dev and all with sha512. The published package has zero runtime dependencies (`files: dist, README.md`). Licences come from lockfile metadata, because node_modules is not installed.

### Other SDK ecosystems
- **V-10. NestJS adapter.** `sangam/sdks/typescript-nestjs` has **no lockfile**. Its peers `@nestjs/common`/`@nestjs/core` are open ranges (`^10 || ^11`) and are not installed. README.md:64 confirms it was never run against Nest. Decision 96 defers verification to the test phase.
- **V-11. Python SDK and FastAPI adapter.**
  - The Python SDK has zero runtime dependencies (`sdks/python/pyproject.toml`).
  - The FastAPI adapter (`sdks/python-frameworks/fastapi/pyproject.toml`) needs `fastapi>=0.135` and `starlette>=1.0`, with no lock.
  - The local interpreter has fastapi 0.135.3 (MIT) and starlette 1.0.0 (BSD-3-Clause), from importlib.metadata.
  - Decision 96 names Django, Laravel and Symfony (deferred) and NestJS (kept). **FastAPI is not mentioned**, and no CI job builds or tests the adapter.
- **V-12. .NET.**
  - Sangam.Sdk has no PackageReference.
  - Sangam.Sdk.Hosting has only the in-box `Microsoft.AspNetCore.App` FrameworkReference.
  - The test project pins Microsoft.NET.Test.Sdk 17.11.1, xunit 2.9.2 and xunit.runner.visualstudio 2.8.2. It has no `packages.lock.json`, the project was never restored (no `obj/project.assets.json`), and the packages are absent from the local NuGet cache. Their licences are therefore unverified.
  - `sdks.yml` packs only Sangam.Sdk.
- **V-13. PHP.** `sdks/php/composer.json` has no runtime packages (php>=8.2, ext-curl, ext-json) and dev `phpunit/phpunit ^11.5`. There is no `composer.lock`. CI forces 11.5.0 with `composer update --with`, but the ac08 job runs `composer install` without a lock.
- **V-14. Rust.** `sdks/rust/sangam-worker/Cargo.lock` exists locally but is **gitignored** (`.gitignore:2`). It holds 9 crates with crates.io checksums. Licences come from `~/.cargo/registry/src/*/<crate>/Cargo.toml`: all `MIT OR Apache-2.0`, except ryu `Apache-2.0 OR BSL-1.0` (elect Apache-2.0) and unicode-ident `(MIT OR Apache-2.0) AND Unicode-3.0`. Go (`sdks/go/go.mod`) has no third-party modules.

### Fonts for PDF (`sangam/deploy/fonts`)
- **V-15. Noto fonts match their records.** Re-hashed on 2026-11-12, equal to `fonts/README.md` and the DOWNLOADS-WAVE3 line of 14:04Z:
  - NotoSansDevanagari-Regular.ttf: 573E525D…2FF9
  - NotoSans-Regular.ttf: F5F552C8…ECE5
  - OFL-NotoSansDevanagari.txt: A216F6F8…F331
  - OFL-NotoSans.txt: CEE9892F…061A

  The OFL texts are present. This conforms to the conditional entry (decisions 70 and 95).

### Containers, images and runtimes
- **V-16. No image is pinned by digest.**
  - All Dockerfile `ARG *_IMAGE` defaults are tag-only.
  - All compose `image:` lines are tag-only.
  - Helm `values.yaml` has `digest: ""` for the server, UI, script-worker, ClamAV, control-plane, gateway and rollup images.
  - Mutable tags in use: `mcr.microsoft.com/mssql/server:2022-latest` (compose, Helm backup default, Testcontainers, db-matrix), `gcr.io/distroless/*:nonroot`, and floating-minor `postgres:17`, `mysql:8.4` and `mariadb:11.4`.
  - `deploy/airgap/save-images.sh` records digests into `images.lock` only after a pull.
- **V-17. Shipped runtime bases** (all tag-only):
  - `eclipse-temurin:25.0.1_8-jre-noble`: server, control-plane, gateway, rollup, script-worker.
  - `gcr.io/distroless/java25-debian13:nonroot`: server distroless variant.
  - `gcr.io/distroless/base-debian12:nonroot`: sdk-worker.
  - `nginxinc/nginx-unprivileged:1.28.0-alpine3.21`: UI.
  - `ghcr.io/graalvm/native-image-community:25.0.1`: build only, but its runtime is compiled into the shipped sdk-worker binary.
- **V-18. Air-gap bundle contents.** `deploy/airgap/images.txt` makes Sangam redistribute these third-party images:
  - `runtime` group (always saved): keycloak 26.3.3, otel-collector-contrib 0.131.1, postgres:17.
  - `observability` group (opt-in): jaeger 2.9.0, prometheus v3.5.0, **grafana-oss 12.1.1, which the file itself labels AGPL-3.0**. AGPL-3.0 is in `forbidden_licenses`, and no owner decision makes an exception. → **REJECTED FOR DEFINED USE** (distribution in the bundle).
  - `devdb` (customer-only flag): Oracle Free, SQL Server, MySQL.
- **V-19. ClamAV.** `clamav/clamav:1.4.3` appears in compose (profile `malware`) and Helm (`malware.clamav.enabled: false`). Tag pinned, digest empty, separate service, never linked. This conforms to decision 71 apart from the missing digest. It is not in `images.txt`, so Sangam does not redistribute it today.
- **V-20. Script-worker inputs.**
  - `script-worker.Dockerfile` takes `node:22.18.0-bookworm-slim` (copies only `/usr/local/bin/node`).
  - It builds nsjail 3.4 by `git clone --branch 3.4 --recurse-submodules` (kafel as a submodule) and checks `NSJAIL_COMMIT` (default empty, so the build fails without it).
  - Build apt packages (unpinned): autoconf, bison, flex, gcc, g++, git, ca-certificates, libnl-route-3-dev, libprotobuf-dev, make, pkg-config, protobuf-compiler.
  - Runtime apt packages: python3, libprotobuf32t64, libnl-route-3-200.
  - **No approved nsjail commit value is recorded** in the tree or in the handoff notes (searched). Decision 79 says "commit hash recorded".
  - The image is **not** built by `release.yml` and is **not** in `images.txt`, but Helm references `sqamentor/sangam-script-worker`.
  - Copying the bare node binary drops Node's LICENSE and its bundled third-party notices.
- **V-21. Proprietary terms accepted in code.** `AcceptanceDatabase.java:163` calls `.acceptLicense()` (Microsoft SQL Server EULA), and compose sets `ACCEPT_EULA: "Y"` (`docker-compose.yml:177`). Oracle Free (`gvenzl/oracle-free:23-slim-faststart`) and Db2 Community (`icr.io/db2_community/db2:12.1.2.0`, db-matrix) are pulled in test lanes. No owner record of accepting these terms exists. Decision 112 forbids contract acceptance without authorisation.
- **V-22. Helm backup defaults point at third-party DB images.** Helm `backup.sqlserver.image` defaults to `mcr.microsoft.com/mssql/server:2022-latest` and `backup.mysql.image` to `mysql:8.4`, so customer clusters pull a proprietary image and a GPL image. `backup.oracle.image` correctly requires a customer image (LIC-2).

### CI tooling (`.github/workflows`)
- **V-23. Actions are tag-pinned only.** 123 `uses:` references across 28 distinct actions/reusable workflows. **0 are SHA-pinned.** Tag references with write tokens or credentials:
  - `docker/login-action@v3` and `docker/build-push-action@v6` (packages write)
  - `softprops/action-gh-release@v2`
  - `actions/attest-build-provenance@v2`
  - `sigstore/cosign-installer@v3` (cosign version itself unpinned)
  - `aws-actions/configure-aws-credentials@v4`
  - `graalvm/setup-graalvm@v1` (major tag; receives `GITHUB_TOKEN`)

  `slsa-github-generator@v2.1.0` must be referenced by tag (accepted).
- **V-24. Downloaded binaries.**
  - Not verified at all: grype v0.121.3 and **syft v1.29.0 (release job)** install via `curl … install.sh | sh` with no checksum. kubeconform v0.7.0 has no checksum.
  - Checked only against a checksum file from the same release origin, with no signature: gitleaks 8.30.1 and kubescape 4.0.20.
  - pip and npx tools have pinned versions but no hashes: semgrep 1.185.0, pip-licenses 5.5.5, pyyaml 6.0.3 vs 6.0.2 (two pins), and `@cyclonedx/cyclonedx-npm@2.1.0` via `npx --yes`. The last one generates the SBOM that the release licence gate consumes.
- **V-25. Scanners never gate anything.** Trivy (`exit-code: "0"`, continue-on-error), Grype, Kubescape, Gitleaks (`--exit-code 0`), Semgrep (`|| true`, job continue-on-error) and Scorecard (`publish_results: false`) are all report-only. Only `check_licences.py` (strict on release), `build_tools_not_shipped.py` and kubeconform can fail a job.
- **V-26. Image SBOMs are not licence-checked.** `release.yml` produces image SBOMs (syft, plus BuildKit `sbom: true`) but runs only `build_tools_not_shipped.py` on them, **not** `check_licences.py`. `licence-policy.json` has no rule for OS packages inside images. So GPL, LGPL and AGPL inside shipped image layers (busybox, glibc, bash, libnl-route, Grafana) are neither gated nor explicitly allowed.
- **V-27. Dependabot opens no PRs.** `dependabot.yml` sets `open-pull-requests-limit: 0` for every ecosystem, so it raises no update PRs (security alerts are separate). There is no `docker` ecosystem entry, and no entry for `sdks/typescript-nestjs` or `sdks/python-frameworks`.
- **V-28. The repository NOTICE is incomplete.** It covers Operaton, Flowable, the FEEL closure and a one-line bpmn.io mention. There is no third-party notices file for:
  - the 149 shipped npm packages (vite emits none);
  - the fonts;
  - Node.js inside the script-worker;
  - base-image OS packages.

  Only MariaDB has a source offer (`deploy/docker/server/SOURCE-OFFER.txt`).

---

## 2. ASSUMPTIONS (not proven here)
- **A-01. Vite output.** `vite build` bundles only modules imported from the production graph, so dev-only packages (including MPL axe-core and CC-BY caniuse-lite) do not reach `dist/`. Not verified by inspecting `dist/`.
- **A-02. SBOM vs lockfile.** `cyclonedx-npm --omit dev` follows the lockfile `dev` flags, so the UI SBOM equals the 149-package shipped closure.
- **A-03. Expected licences of unpulled images and sources.** These are written into the CSV as "expected …, unverified" and are **not** accepted on that basis:
  - Keycloak: Apache-2.0 with a UBI base.
  - otel, jaeger, prometheus: Apache-2.0.
  - nsjail and kafel: Apache-2.0.
  - libnl: LGPL-2.1.
  - Node.js: MIT with bundled OpenSSL Apache-2.0, ICU, V8 BSD-3.
  - GraalVM CE: GPL-2.0 with Classpath Exception.
  - Debian, Ubuntu and Alpine bases: GPL/LGPL OS packages.
- **A-04. GitHub Actions licences** (MIT/Apache-2.0) were not inspected. CI-only use creates no distribution obligation.
- **A-05. Customer-installed SDK dependencies.** Packages that SDK consumers install themselves (FastAPI, Starlette, NestJS, the .NET shared framework, PHP extensions) are not redistributed by Sangam, so their licences create no Sangam distribution obligation. This holds as long as SDK packages do not vendor them.
- **A-06. Snapshot copies.** `handoff/worktree-snapshots/**` manifests are stale copies and not part of 0.1.0.
- **A-07. The air-gap bundle is a Sangam distribution.** Whatever `save-images.sh` saves without the customer-only flags is distributed by Sangam (product, runtime, and observability when the flag is used).

---

## 3. UNRESOLVED (needs an owner decision or evidence; accountable owner: product owner, with the integrator executing; review by: before the test-phase image pulls, and in any case before the 0.1.0 release gate)

| # | Item | Why unresolved | Practical options |
|---|---|---|---|
| U-01 | `@fontsource/noto-sans(-devanagari)` 5.1.0 web fonts | OFL-1.1 conditional covers only the PDF Noto fonts; the strict gate will fail (V-05) | (a) owner widens decision 70/95 to UI web fonts and adds the `pkg:npm/%40fontsource/...` purls; (b) serve the already-approved deploy/fonts TTFs from `public/` instead |
| U-02 | Sakal Bharati font in the UI | not covered by any decision; conflicting non-commercial clause (V-06) | owner/legal decision, ideally written C-DAC confirmation; fallback: drop it and rely on Noto Sans Devanagari |
| U-03 | Grafana OSS 12.1.1 in the air-gap `observability` group | AGPL-3.0 is forbidden for shipping (V-18) | remove it from images.txt (the customer pulls it), or an explicit owner exception with an AGPL source offer |
| U-04 | Base-image OS packages (GPL/LGPL) in shipped images and the bundle | policy silent; image SBOMs not licence-gated (V-26) | owner rule: "OS packages in base layers allowed as separate programs, with a source offer and copyright files kept"; add `check_licences.py --sbom image-sbom/*` with an OS-package section |
| U-05 | Script-worker distribution | decision 79 covers the test phase only; libnl-route LGPL-2.1 at runtime; no release build, no images.txt entry; node notices missing (V-20) | owner decision for distribution; record the nsjail and kafel commits; copy Node's LICENSE; add a source offer for libnl |
| U-06 | Approved nsjail commit hash | decision 79 says recorded, but no value exists in the tree or handoff | integrator records the 40-hex commit for tag 3.4 and the kafel submodule commit in the Dockerfile header and compose `.env.example` |
| U-07 | EULA and terms acceptance in test code (SQL Server `.acceptLicense()`, `ACCEPT_EULA=Y`, Oracle Free, Db2 Community) | decision 112: no contract acceptance without explicit authorisation (V-21) | owner records acceptance for internal CI/test use, or the lanes are opt-in with the operator accepting |
| U-08 | Helm backup default images (mssql `2022-latest`, `mysql:8.4`) | make customers pull proprietary/GPL images by default; mutable tag (V-22) | default `""` + `required` (as for Oracle), or pinned digest plus a documented customer acceptance |
| U-09 | FastAPI adapter scope | not in decision 96; no CI; open ranges (V-11) | owner adds FastAPI to decision 96 (defer, or keep with a test-phase check) |
| U-10 | IBM telemetry postinstall in `npm ci` | install-time data leaves the build host; air-gap/data-protection question (V-07) | `--ignore-scripts` (esbuild then needs `npm rebuild esbuild`) or `IBM_TELEMETRY_DISABLED=true` in every npm ci (CI and ui.Dockerfile) |
| U-11 | How the strict gate treats `"SEE LICENSE IN LICENSE"` (7 bpmn.io packages) | `check_licences.normalise` maps it to `LicenseRef-see-license-file`, which is neither allowed nor conditional, so `--strict` may FAIL depending on what cyclonedx-npm 2.1.0 emits. The gate was not run, so this cannot be reported as PASS | run the gate in the test phase; if it fails, map bpmn.io purls to `LicenseRef-bpmn.io` explicitly |
| U-12 | Consumer ban on `org.camunda*` (SG-27) vs Camunda-copyrighted bpmn.io npm packages | the ban is Maven-namespace scoped; bpmn-js is © Camunda Services GmbH (V-04) | owner confirms with the consumer whether the ban is namespace-only (no vendor contact without authorisation) |
| U-13 | ClamAV signature databases (freshclam) | the database terms are separate from the GPL-2.0 engine and were not verified | verify at test-phase pull; air-gap mirror policy |
| U-14 | Keycloak image redistribution (UBI EULA) | image licence ≠ base OS licence | verify the UBI redistribution terms at pull; keep notices |
| U-15 | GraalVM CE native-image runtime in the sdk-worker binary | confirm Community (GPL-2.0+CPE), no GFTC component linked | verify at pull; record in the image SBOM |
| U-16 | .NET test packages, phpunit, pip-licenses, cosign, the actions themselves | licence not inspectable offline | resolve in the test phase when restored, then record |
| U-17 | Sangam.Sdk.Hosting packaging | decision 105 lists only NuGet `Sangam.Sdk`; sdks.yml packs only that | owner: publish Hosting or keep it source-only |

---

## 4. Obligations (when Sangam distributes)
- **UI image / dist:**
  - third-party notices for the 149 npm packages: MIT/ISC/Apache-2.0 texts, Apache NOTICE files where present (`path-intersection` ships a NOTICE-type file);
  - the bpmn.io licence text, with the watermark visible and unobscured, unmodified (LIC-8);
  - OFL.txt next to each font, fonts never sold alone and never renamed;
  - an election record for dompurify (Apache-2.0).
- **PDF fonts:** OFL-NotoSans*.txt shipped with the TTFs (present).
- **GPL tools inside images:**
  - ClamAV (if ever bundled): GPL-2.0 text plus corresponding source or a written offer.
  - busybox (nginx-alpine, prometheus) and Ubuntu/Debian GPL packages: source offer, keep `/usr/share/doc/*/copyright`.
  - libnl-route-3 (LGPL-2.1, script-worker): source offer; dynamic linking keeps it replaceable.
  - MariaDB driver: existing SOURCE-OFFER.txt.
- **Node binary in the script-worker:** Node's LICENSE (which includes its bundled-dependency notices) must be copied with the binary.
- **AGPL (Grafana):** source of the exact version to every recipient. This is why it must not be in the Sangam bundle without an exception.
- **Rust and Go SDKs:** no vendoring, so no extra notice in source distributions. A binary distribution would carry the crate licences.

## 5. Gaps in current controls (prioritised remediation)
1. **P1:** UI licence gate will fail or be inconclusive (U-01, U-11). Fix the policy purls before the test-phase gate run.
2. **P1:** AGPL Grafana in the bundle (U-03). Change images.txt or get an owner exception.
3. **P1:** The release licence gate ignores image SBOMs, and the policy has no OS-package rule (V-26, U-04). Add a `check_licences.py` run over `image-sbom/*.cdx.json` with an owner-approved OS-package rule.
4. **P1:** Pin every third-party action to a commit SHA, starting with the release and credential jobs (V-23). Re-enable Dependabot `github-actions` PRs (limit above 0) so SHA pins stay current.
5. **P1:** Release-job installers without verification: syft `curl|sh`, `npx --yes cyclonedx-npm`, unpinned cosign (V-24). Pin by checksum or signature, or by an npm lockfile for the SBOM tool.
6. **P2:** Pin every base, sidecar and test image by digest. Replace `2022-latest` and `nonroot` with versioned tags plus digests. Fill Helm `digest` for production and air-gap installs (V-16).
7. **P2:** Record EULA/terms acceptance or make those lanes opt-in (U-07). Change the Helm backup defaults (U-08).
8. **P2:** Script-worker: record the nsjail/kafel commits, add the Node LICENSE, decide distribution, pin apt versions or use snapshot.ubuntu.com (U-05, U-06).
9. **P2:** Lockfiles: nestjs (`package-lock.json`), PHP dev (`composer.lock` for CI), .NET (`RestorePackagesWithLockFile`), Python (constraints file with hashes for CI), and commit Cargo.lock or a CI `--locked` check (V-10..V-14).
10. **P2:** Telemetry postinstall (U-10). Add `--ignore-scripts` or the opt-out environment variable in ui.yml, release.yml, sdks.yml and ui.Dockerfile.
11. **P3:**
    - Keep one source of truth: make `list-licences.mjs` read `licence-policy.json` (V-08).
    - Add a generated `THIRD-PARTY-NOTICES` for the UI dist and the images (V-28).
    - Make at least the Trivy/Grype critical-severity and Gitleaks results gating at release (V-25).
    - Unify the pyyaml pins.

## 6. What was not done (never reported as PASS)
- No `check_licences.py`, `build_tools_not_shipped.py`, `npm run licences`, test or build was executed. Gate outcomes in V-05 and U-11 are predictions from the code.
- No image was pulled, so image licences, OS packages and digests are unverified (all images are REVIEW REQUIRED unless an owner decision covers them).
- `dist/` contents were not inspected (A-01).
