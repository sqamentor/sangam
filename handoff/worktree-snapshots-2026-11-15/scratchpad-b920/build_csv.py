import csv, json, sys, collections

S = sys.argv[1]
OUT = sys.argv[2]
npm = json.load(open(S + "/npm_rows.json", encoding="utf-8"))
for r in npm:
    if r["name"] in ("@carbon/grid", "@carbon/layout", "@ibm/telemetry-js"):
        r["decision"] = "REVIEW REQUIRED"
        r["conditions_or_reason"] = ("licence Apache-2.0 is fine, but @carbon/grid and @carbon/layout run 'ibmtelemetry --config=telemetry.yml' as postinstall "
            "(IBM install-time usage telemetry via @ibm/telemetry-js, pulled in by @bpmn-io/form-js-viewer). npm ci in ui.yml, release.yml and ui.Dockerfile "
            "does not pass --ignore-scripts (only licence-check.yml does): data-protection/air-gap question; set IBM_TELEMETRY_DISABLED=true or --ignore-scripts and confirm with owner")
        r["evidence"] += " | lockfile hasInstallScript; installed package.json scripts.postinstall"
    if r["name"] == "inferno" or r["name"] == "esbuild":
        r["evidence"] += " | lockfile hasInstallScript=true"
    if "NOT INSTALLED" in r["evidence"] and r["decision"] == "ACCEPTED FOR DEFINED USE":
        r["conditions_or_reason"] = "platform-specific optional dev binary not installed on this Windows host; licence from lockfile registry metadata only (dev/build, never shipped)"

AR = "ACCEPTED FOR DEFINED USE"
AC = "ACCEPTED WITH CONDITIONS"
RR = "REVIEW REQUIRED"
RJ = "REJECTED FOR DEFINED USE"
D = "2026-11-12"
NOPIN = "NOT PINNED BY DIGEST (tag only)"
UNV = "not pulled: licence not verified from the artifact (evidence tier E3 = repository claim/general knowledge only)"

rows = []
def add(eco, name, ver, integ, src, usage, loc, spdx, obl, dec, cond, ev="see location"):
    rows.append(dict(ecosystem=eco, name=name, version=ver, integrity=integ, source=src, usage_scope=usage, location=loc,
                     spdx=spdx, obligations=obl, decision=dec, conditions_or_reason=cond, evidence=ev))

# ---------------- npm: packages without a lockfile (NestJS adapter) and npx-fetched CI tools
add("npm", "@nestjs/common", "^10.0.0 || ^11.0.0 (range)", "NONE (no lockfile)", "npm registry (not fetched)", "peerDependency of @sangam/nestjs; runtime in the consumer's app (not bundled)",
    "sangam/sdks/typescript-nestjs/package.json", "NOASSERTION (expected MIT, unverified)", "consumer-supplied", RR,
    "no lockfile in sdks/typescript-nestjs; not installed; decision 96 keeps NestJS as coded and verified in the test phase with no downloads now", "package.json peerDependencies; README.md:64 says @nestjs/* not installed")
add("npm", "@nestjs/core", "^10.0.0 || ^11.0.0 (range)", "NONE (no lockfile)", "npm registry (not fetched)", "peerDependency of @sangam/nestjs; runtime in the consumer's app",
    "sangam/sdks/typescript-nestjs/package.json", "NOASSERTION (expected MIT, unverified)", "consumer-supplied", RR,
    "no lockfile; not installed; decision 96", "package.json peerDependencies")
add("npm", "@types/node", "20.19.43", "NONE (no lockfile in this package)", "npm registry", "devDependency (build/test only)", "sangam/sdks/typescript-nestjs/package.json",
    "MIT (same version locked with integrity in sdks/typescript/package-lock.json)", "none when not shipped", RR,
    "package has no lockfile: npm install would resolve transitive undici-types freely", "package.json devDependencies")
add("npm", "typescript", "^5.6.3 (range)", "NONE (no lockfile in this package)", "npm registry", "devDependency (build only)", "sangam/sdks/typescript-nestjs/package.json",
    "Apache-2.0 (5.9.3 locked in sdks/typescript)", "none when not shipped", RR, "range + no lockfile: version not reproducible", "package.json devDependencies")
add("npm", "@cyclonedx/cyclonedx-npm", "2.1.0", "NONE (npx --yes, no lockfile, no integrity)", "npm registry at CI run time", "CI tool (SBOM generation for UI); build only",
    ".github/workflows/ui.yml:71, licence-check.yml:54, release.yml:123", "NOASSERTION (expected Apache-2.0, unverified)", "none (not shipped)", RR,
    "fetched by npx at run time without integrity pin; its output feeds the release licence gate, so a tampered tool would falsify the gate", "workflow lines cited")

# ---------------- PyPI
add("pypi", "setuptools", ">=68 (range)", "NONE", "PyPI", "build backend (sdist/wheel build of sangam-sdk, sangam-sdk-fastapi)", "sangam/sdks/python/pyproject.toml; sangam/sdks/python-frameworks/fastapi/pyproject.toml",
    "NOASSERTION (expected MIT, unverified)", "none (not shipped)", RR, "unpinned build requirement, no hash-locked build environment", "pyproject [build-system]")
add("pypi", "wheel", "unpinned", "NONE", "PyPI", "build backend", "same pyproject files", "NOASSERTION (expected MIT, unverified)", "none", RR, "unpinned", "pyproject [build-system]")
add("pypi", "pytest", ">=8 (range); local env 9.0.3", "NONE", "PyPI", "test extra only", "sangam/sdks/python/pyproject.toml [test]; fastapi adapter [test]",
    "MIT (local interpreter metadata of 9.0.3)", "none (not shipped)", AC, "dev/test only; pin exact version (CI installs whatever >=8 resolves)", "importlib.metadata in local Python 3.13.2: pytest 9.0.3 License-Expression MIT")
add("pypi", "fastapi", ">=0.135 (range); local env 0.135.3", "NONE (no lockfile)", "PyPI", "runtime dependency of sangam-sdk-fastapi (installed by the consumer; not bundled)",
    "sangam/sdks/python-frameworks/fastapi/pyproject.toml", "MIT (local interpreter metadata of 0.135.3)", "consumer-installed; none for Sangam unless redistributed", RR,
    "framework adapter not covered by decision 96 (which names Django, Laravel, Symfony deferred and NestJS kept): owner must confirm FastAPI scope for 0.1.0; open range, no lock; no CI job builds/tests the adapter", "pyproject dependencies; importlib.metadata 0.135.3 MIT")
add("pypi", "starlette", ">=1.0 (range); local env 1.0.0", "NONE (no lockfile)", "PyPI", "runtime dependency of sangam-sdk-fastapi (consumer)", "sangam/sdks/python-frameworks/fastapi/pyproject.toml",
    "BSD-3-Clause (local interpreter metadata of 1.0.0)", "consumer-installed", RR, "same as fastapi", "importlib.metadata 1.0.0 BSD-3-Clause")
add("pypi", "semgrep", "1.185.0", "NONE (pip without --require-hashes)", "PyPI at CI run time", "CI tool (SAST), own venv, build-time only", ".github/workflows/security.yml:233",
    "LGPL-2.1 (licence-policy.json build_time_tools)", "LGPL: never ship; if ever redistributed, source + relinking rights", AC,
    "owner 2026-11-12 build-time tool exception; never shipped (build_tools_not_shipped.py); add --require-hashes", "licence-policy.json build_time_tools; security.yml")
add("pypi", "pip-licenses", "5.5.5", "NONE", "PyPI at CI run time", "CI tool (report-only)", ".github/workflows/security.yml:237", "NOASSERTION (workflow comment says MIT)", "none", RR, UNV.replace("not pulled: ", "") + "; no hash pin", "security.yml comment")
add("pypi", "pyyaml", "6.0.3 (ci-build.yml) / 6.0.2 (sdks.yml)", "NONE", "PyPI at CI run time", "CI tool", ".github/workflows/ci-build.yml:61; sdks.yml:187",
    "MIT (local interpreter metadata of 6.0.3)", "none", AC, "two different pins in CI; unify and add hashes", "importlib.metadata PyYAML 6.0.3 MIT")

# ---------------- NuGet
for n, v in (("Microsoft.NET.Test.Sdk", "17.11.1"), ("xunit", "2.9.2"), ("xunit.runner.visualstudio", "2.8.2")):
    add("nuget", n, v, "NONE (no packages.lock.json; package not in local NuGet cache)", "nuget.org", "test only (IsPackable=false)",
        "sangam/sdks/dotnet/tests/Sangam.Sdk.Tests/Sangam.Sdk.Tests.csproj", "NOASSERTION (expected MIT/Apache-2.0, unverified)", "none (not shipped)", RR,
        "licence not verifiable offline (test project never restored here: no obj/project.assets.json); no NuGet lock file (RestorePackagesWithLockFile not set)", "csproj PackageReference; %USERPROFILE%\\.nuget\\packages lacks these versions")
add("nuget", "Microsoft.AspNetCore.App (FrameworkReference)", "net8.0 shared framework", "n/a (in-box)", ".NET 8 runtime on the consumer host", "runtime of Sangam.Sdk.Hosting (consumer-provided, not packed)",
    "sangam/sdks/dotnet/src/Sangam.Sdk.Hosting/Sangam.Sdk.Hosting.csproj", "NOASSERTION (expected MIT, unverified)", "none for Sangam (not redistributed)", RR,
    "Sangam.Sdk.Hosting is not packed by sdks.yml (only Sangam.Sdk is) although decision 105 lists NuGet Sangam.Sdk only: confirm whether Hosting ships", "csproj; sdks.yml dotnet pack line")
add("toolchain", ".NET SDK", "8.0.404", "NONE (actions/setup-dotnet download)", "Microsoft", "build/test (CI)", ".github/workflows/sdks.yml:97,269", "NOASSERTION (expected MIT)", "none", AC, "owner decision 106 approved .NET 8 SDK; log version in the downloads register", "decision 106")

# ---------------- Composer
add("composer", "phpunit/phpunit", "^11.5 (composer.json); CI forces 11.5.0", "NONE (no composer.lock)", "Packagist", "dev only (require-dev)", "sangam/sdks/php/composer.json; .github/workflows/sdks.yml:177",
    "NOASSERTION (expected BSD-3-Clause, unverified)", "none (not shipped)", RR, "no lockfile; transitive dev tree resolved freshly in CI; ac08 job runs composer install without a lock", "composer.json require-dev")
add("composer", "ext-curl / ext-json / php", "php>=8.2", "n/a", "consumer PHP runtime", "runtime (consumer-provided)", "sangam/sdks/php/composer.json", "PHP-3.01 (runtime, consumer-provided; unverified)", "none for Sangam", AC,
    "platform requirements only, not redistributed", "composer.json require")
add("toolchain", "PHP + Composer", "php 8.3 / composer 2.8.3", "NONE", "shivammathur/setup-php", "build/test (CI)", ".github/workflows/sdks.yml:167,275", "NOASSERTION", "none", AC, "decision 106 approved PHP; pin setup-php by SHA", "decision 106")

# ---------------- Go / Rust / Kotlin
add("go", "github.com/sqamentor/sangam/sdks/go", "go 1.22 (no third-party modules)", "n/a (no go.sum needed)", "repo", "SDK source", "sangam/sdks/go/go.mod", "Apache-2.0 (own)", "own licence", AR, "zero third-party Go modules", "go.mod has no require block")
add("toolchain", "Go toolchain", "1.22.10", "NONE", "actions/setup-go", "build/test (CI)", ".github/workflows/sdks.yml:199", "NOASSERTION (expected BSD-3-Clause)", "none", AC, "decision 106 approved Go toolchain", "decision 106")
crates = [("itoa", "1.0.11", "49f1f14873335454500d59611f1cf4a4b0f786f9ac11f4312a78e4cf2566695b", "MIT OR Apache-2.0"),
          ("proc-macro2", "1.0.106", "8fd00f0bb2e90d81d1044c2b32617f68fcb9fa3bb7640c23e9c748e53fb30934", "MIT OR Apache-2.0"),
          ("quote", "1.0.45", "41f2619966050689382d2b44f664f4bc593e129785a36d6ee376ddf37259b924", "MIT OR Apache-2.0"),
          ("ryu", "1.0.17", "e86697c916019a8588c99b5fac3cead74ec0b4b819707a682fd4d23fa0ce1ba1", "Apache-2.0 OR BSL-1.0"),
          ("serde", "1.0.203", "7253ab4de971e72fb7be983802300c30b5a7f0c2e56fab8abfc6a214307c0094", "MIT OR Apache-2.0"),
          ("serde_derive", "1.0.203", "500cbc0ebeb6f46627f50f3f5811ccf6bf00643be300b4c3eabc0ef55dc5b5ba", "MIT OR Apache-2.0"),
          ("serde_json", "1.0.116", "3e17db7126d17feb94eb3fad46bf1a96b034e8aacbc2e775fe81505f8b0b2813", "MIT OR Apache-2.0"),
          ("syn", "2.0.117", "e665b8803e7b1d2a727f4023456bbbbe74da67099c585258af0ad9c5013b9b99", "MIT OR Apache-2.0"),
          ("unicode-ident", "1.0.24", "e6e4313cd5fcd3dad5cafa179702e2b244f760991f45397d14d4ebf38247da75", "(MIT OR Apache-2.0) AND Unicode-3.0")]
for n, v, ck, lic in crates:
    direct = n in ("serde", "serde_json")
    add("cargo", n, v, "sha256:" + ck, "crates.io", ("dependency (direct, Cargo.toml range '1')" if direct else "dependency (transitive)") + "; build of sangam-worker crate (consumers resolve their own versions; not vendored)",
        "sangam/sdks/rust/sangam-worker/Cargo.lock (GITIGNORED, local only)", lic, "keep licence notice if binaries are distributed", AC,
        "dual licence: elect " + ("Apache-2.0 (BSL-1.0 not in allowed list)" if n == "ryu" else "MIT or Apache-2.0") + "; Cargo.lock is gitignored, so CI and reviewers cannot reproduce these versions",
        f"~/.cargo/registry/src/*/{n}-{v}/Cargo.toml license=\"{lic}\"; Cargo.lock checksum")
add("toolchain", "Gradle", "8.10.2", "NONE", "gradle/actions/setup-gradle@v4", "build (tier-2 Kotlin client compile check)", ".github/workflows/sdks.yml:248", "NOASSERTION (expected Apache-2.0)", "none", AC, "decision 106 approved Kotlin toolchain", "decision 106")
add("container", "openapitools/openapi-generator-cli", "v7.10.0", NOPIN, "Docker Hub", "build (tier-2 Go/Rust/Kotlin client generation)", "sangam/sdks/tier2/generate_tier2.py:24-25", "NOASSERTION (expected Apache-2.0)", "generated code licence is the project's; keep generator notices if templates require", AC,
    "decision 106 approved openapi-generator; pin by digest and record it", "generate_tier2.py IMAGE constant")

# ---------------- container images
def img(name, tag, usage, loc, spdx, dec, cond, obl="", ev=""):
    add("container", name, tag, NOPIN, name.split("/")[0] if "." in name.split("/")[0] else "Docker Hub", usage, loc, spdx, obl, dec, cond, ev)

img("maven", "3.9.11-eclipse-temurin-25", "build stage (server, script-worker, sdk-worker-native); script-worker 'test' target copies its Maven + JDK into the test image",
    "sangam/deploy/docker/server.Dockerfile:36; script-worker.Dockerfile:23; sdk-worker-native.Dockerfile:13", "NOASSERTION (expected Apache-2.0 Maven + GPL-2.0-with-classpath-exception Temurin + Ubuntu OS packages)", RR,
    UNV + "; build only, nothing but /out copied to runtime; pin by digest", "none while build-only")
img("maven", "3.9.11-eclipse-temurin-21", "build stage (scale, simulators)", "sangam/deploy/docker/scale.Dockerfile:9; simulators.Dockerfile:19", "NOASSERTION (as above)", RR, UNV + "; pin by digest")
img("eclipse-temurin", "25.0.1_8-jre-noble", "RUNTIME BASE of shipped images: sangam-server, control-plane, gateway, rollup (release.yml), script-worker",
    "sangam/deploy/docker/server.Dockerfile:37; script-worker.Dockerfile:24", "NOASSERTION (Temurin JRE GPL-2.0-with-classpath-exception; Ubuntu 24.04 OS packages incl. GPL/LGPL e.g. bash, coreutils, glibc)", RR,
    UNV + "; shipped image layers redistribute Ubuntu GPL/LGPL packages: licence-policy.json forbids GPL in shipped scope but has no rule for OS packages; image SBOMs are not licence-gated; pin by digest",
    "GPL/LGPL OS packages: provide corresponding source or written offer when Sangam distributes the image; keep /usr/share/doc/*/copyright")
img("eclipse-temurin", "21.0.8_9-jre-noble", "runtime base of sangam-simulators (product group in airgap images.txt) and scale.Dockerfile images", "sangam/deploy/docker/scale.Dockerfile:10; simulators.Dockerfile:20",
    "NOASSERTION (as above)", RR, UNV + "; pin by digest", "as above")
img("gcr.io/distroless/java25-debian13", "nonroot", "RUNTIME BASE of shipped sangam-server -distroless image (release.yml)", "sangam/deploy/docker/server.Dockerfile:38",
    "NOASSERTION (Debian 13 packages + Java runtime)", RR, UNV + "; mutable tag 'nonroot'; Debian GPL/LGPL packages redistributed; pin by digest",
    "Debian package copyright files are present in distroless; source offer for GPL/LGPL packages")
img("ghcr.io/graalvm/native-image-community", "25.0.1", "build stage; its SubstrateVM runtime is compiled into the shipped sangam-sdk-worker binary", "sangam/deploy/docker/sdk-worker-native.Dockerfile:14",
    "NOASSERTION (GraalVM Community: expected GPL-2.0-with-classpath-exception; NOT Oracle GraalVM/GFTC which is forbidden)", RR,
    UNV + "; confirm Community licence of the exact image and that no GFTC component is linked; pin by digest", "classpath-exception notice in distributed binary")
img("gcr.io/distroless/base-debian12", "nonroot", "RUNTIME BASE of shipped sangam-sdk-worker image", "sangam/deploy/docker/sdk-worker-native.Dockerfile:15", "NOASSERTION (Debian 12 packages incl. glibc LGPL)", RR, UNV + "; mutable tag; pin by digest", "LGPL glibc: source offer")
img("node", "22.18.0-alpine3.22", "build stage of sangam-ui (npm ci + vite build); not in runtime", "sangam/deploy/docker/ui.Dockerfile:17", "NOASSERTION (Node.js MIT + bundled deps; Alpine OS packages)", RR,
    UNV + "; npm ci in this stage runs install scripts (incl. @carbon/* IBM telemetry postinstall) because --ignore-scripts is not set; pin by digest")
img("nginxinc/nginx-unprivileged", "1.28.0-alpine3.21", "RUNTIME BASE of shipped sangam-ui image", "sangam/deploy/docker/ui.Dockerfile:18",
    "NOASSERTION (nginx BSD-2-Clause; Alpine musl MIT, busybox GPL-2.0, others)", RR, UNV + "; busybox GPL-2.0 redistributed in shipped image; pin by digest", "GPL-2.0 busybox: source offer when distributed")
img("node", "22.18.0-bookworm-slim", "build input of script-worker: only /usr/local/bin/node copied into the RUNTIME image", "sangam/deploy/docker/script-worker.Dockerfile:25,68",
    "NOASSERTION (Node.js MIT; binary statically includes OpenSSL Apache-2.0, ICU Unicode, V8 BSD-3, libuv MIT, etc.)", AC,
    "owner decision 79: approved for the TEST PHASE ONLY, nothing pulled before it; copying the bare binary drops Node's LICENSE (third-party notices) -> must copy it too; distribution of the script-worker image is not yet approved; pin by digest",
    "MIT + bundled third-party notices must accompany the node binary")
add("source", "google/nsjail", "3.4 (git tag) at NSJAIL_COMMIT build-arg", "commit check in Dockerfile, but NO commit value recorded in the repo (default empty)", "https://github.com/google/nsjail.git",
    "build from source; binary shipped in script-worker runtime image", "sangam/deploy/docker/script-worker.Dockerfile:42-53; compose NSJAIL_COMMIT=${NSJAIL_COMMIT:-}",
    "NOASSERTION (expected Apache-2.0, unverified)", "Apache-2.0: licence text + NOTICE in image if distributed", AC,
    "owner decisions 77/79: nsjail 3.4 from source at an approved, recorded commit, test phase only; the approved 40-hex commit is not recorded in the tree or handoff (decision 79 says recorded): record it before any build", "Dockerfile; grep of tree/handoff found no commit value")
add("source", "google/kafel", "git submodule of nsjail 3.4 (shallow)", "pinned only transitively via the nsjail commit", "GitHub (submodule)", "build from source; linked into nsjail", "sangam/deploy/docker/script-worker.Dockerfile:49",
    "NOASSERTION (expected Apache-2.0, unverified)", "licence text if distributed; its bison/flex-generated parser carries the Bison exception", AC,
    "decision 79 (test phase only); record the kafel submodule commit with the nsjail commit", "Dockerfile git clone --recurse-submodules")
add("apt", "build packages: autoconf bison flex gcc g++ git ca-certificates libnl-route-3-dev libprotobuf-dev make pkg-config protobuf-compiler", "unpinned (Ubuntu noble archive at build time)", "apt signature only; no version pins",
    "Ubuntu noble archive", "build stage only (nsjail compile)", "sangam/deploy/docker/script-worker.Dockerfile:45-48", "NOASSERTION (mixed GPL-2.0/GPL-3.0/LGPL/BSD; build tools)", "none while build-only", AC,
    "decision 79: 'Ubuntu noble apt packages listed by W3-K', test phase only; versions unpinned, so the build is not reproducible")
add("apt", "python3", "unpinned (Ubuntu noble)", "apt signature only", "Ubuntu noble archive", "RUNTIME in script-worker image (CPython runs user scripts)", "sangam/deploy/docker/script-worker.Dockerfile:63-65",
    "NOASSERTION (expected Python-2.0 / PSF; unverified)", "keep copyright file", AC, "decision 79 test phase only; distribution not approved; pin version")
add("apt", "libprotobuf32t64", "unpinned (Ubuntu noble)", "apt signature only", "Ubuntu noble archive", "RUNTIME in script-worker image (nsjail dependency)", "sangam/deploy/docker/script-worker.Dockerfile:65",
    "NOASSERTION (expected BSD-3-Clause)", "keep copyright file", AC, "decision 79 test phase only; pin version")
add("apt", "libnl-route-3-200", "unpinned (Ubuntu noble)", "apt signature only", "Ubuntu noble archive", "RUNTIME in script-worker image (dynamically linked by nsjail)", "sangam/deploy/docker/script-worker.Dockerfile:65",
    "NOASSERTION (libnl is expected LGPL-2.1, unverified)", "LGPL-2.1: source offer + allow replacement (dynamic linking satisfies relinking)", RR,
    "LGPL-2.1 in a SHIPPED image is outside licence-policy.json (LGPL-2.1 conditional covers only mariadb-java-client); decision 79 covers test phase only; needs owner decision before the image is distributed")

img("postgres", "17", "dev compose DB; Testcontainers/IT (BulkIngestPostgresIT, AcceptanceDatabase, db-matrix); Helm backup CronJob image in the customer cluster; airgap images.txt 'runtime' group (REDISTRIBUTED in bundle)",
    "sangam/deploy/compose/docker-compose.yml:119; docker-compose.cells.yml:44; helm/sangam/values.yaml:626; airgap/images.txt; db-matrix.yml:157", "NOASSERTION (PostgreSQL licence + Debian OS packages incl. GPL)", RR,
    UNV + "; floating tag '17' (minor version moves); redistributed in the air-gap bundle with GPL OS packages and no source offer; pin by digest", "PostgreSQL licence notice; GPL OS packages source offer when bundled")
img("gvenzl/oracle-free", "23-slim-faststart", "test only (Testcontainers, db-matrix, dev compose); airgap 'devdb' group only with --with-dev-databases",
    "sangam/deploy/compose/docker-compose.yml:152; AcceptanceDatabase.java:160; db-matrix.yml:158; airgap/images.txt", "LicenseRef-Oracle-Free-Use-Terms (per airgap/images.txt comment; forbidden for shipping)", RR,
    "test use requires someone to accept the Oracle Free Use Terms: no owner record of that acceptance; community repackaging (gvenzl) not an Oracle-published image; never distribute (save-images.sh --with-dev-databases can bundle it)")
img("mcr.microsoft.com/mssql/server", "2022-latest", "test (Testcontainers, db-matrix), dev compose (ACCEPT_EULA=Y), Helm backup CronJob default image in the customer cluster; airgap devdb group",
    "docker-compose.yml:173,196; helm/sangam/values.yaml:641; AcceptanceDatabase.java:163 (.acceptLicense()); db-matrix.yml:159; airgap/images.txt", "LicenseRef-Microsoft-SQL-Server-EULA (proprietary)", RR,
    "MUTABLE 'latest' tag; code auto-accepts the Microsoft EULA (Testcontainers .acceptLicense(), compose ACCEPT_EULA=Y) without a recorded owner authorisation; Helm default makes customers pull a proprietary image for backups; never distribute")
img("mcr.microsoft.com/mssql/server", "2025-CU10-ubuntu-24.04", "test (sqlserver2025 lane)", "AcceptanceDatabase.java:165; db-matrix.yml:161", "LicenseRef-Microsoft-SQL-Server-EULA (proprietary)", RR,
    "EULA auto-accepted in test code; test-only; pin by digest")
img("mysql", "8.4", "dev compose; test; Helm backup CronJob default image (customer cluster); airgap devdb group", "docker-compose.yml:214; helm/sangam/values.yaml:650; AcceptanceDatabase.java:236; db-matrix.yml:162",
    "NOASSERTION (MySQL Community GPL-2.0 per images.txt comment)", RR, UNV + "; LIC-4 keeps MySQL out of what Sangam ships but the Helm chart defaults a GPL image for backups (customer pulls it; acceptable only if never bundled by Sangam); floating minor tag")
img("mariadb", "11.4", "test only (MariaDB lane)", "AcceptanceDatabase.java:187; db-matrix.yml:163", "NOASSERTION (MariaDB server expected GPL-2.0)", RR, UNV + "; test use only; floating tag")
img("icr.io/db2_community/db2", "12.1.2.0", "test only (db2 lane, on request)", ".github/workflows/db-matrix.yml:164", "NOASSERTION (IBM Db2 Community Edition licence; proprietary)", RR,
    "proprietary IBM terms, accepted by whoever pulls; no owner record; never distribute (LIC-3)")
img("clamav/clamav", "1.4.3", "optional separate service (compose profile 'malware', Helm malware.clamav.enabled=false); test phase pull only",
    "docker-compose.yml:327; helm/sangam/values.yaml:294-299", "GPL-2.0 (owner decision 71)", AC,
    "decision 71: separate service, never linked, pinned tag, pull only in test phase. Gaps: no digest (values digest \"\"); if Sangam ever bundles/redistributes the image (it is NOT in airgap images.txt today) GPL-2.0 source offer + notice needed; freshclam signature databases have their own Cisco-Talos terms (unverified) and air-gapped sites need a private mirror",
    "GPL-2.0: when distributed by Sangam, provide corresponding source (or written offer) and licence text; no linking into Sangam")
img("quay.io/keycloak/keycloak", "26.3.3", "identity sidecar: dev compose (start-dev) and airgap images.txt 'runtime' group (REDISTRIBUTED in bundle)", "docker-compose.yml:366; airgap/images.txt",
    "NOASSERTION (Keycloak Apache-2.0 per images.txt; base OS packages from Red Hat UBI differ and carry the UBI EULA)", RR,
    UNV + "; image licence != bundled OS package licences (UBI EULA permits redistribution only under its terms: verify); pin by digest", "Apache-2.0 NOTICE; UBI EULA terms; GPL OS packages source offer")
img("otel/opentelemetry-collector-contrib", "0.131.1", "observability: dev compose and airgap 'runtime' group (REDISTRIBUTED)", "docker-compose.yml:384; airgap/images.txt", "NOASSERTION (Apache-2.0 per images.txt)", RR, UNV + "; pin by digest")
img("jaegertracing/jaeger", "2.9.0", "observability: dev compose; airgap 'observability' group (opt-in, REDISTRIBUTED)", "docker-compose.yml:395; airgap/images.txt", "NOASSERTION (Apache-2.0 per images.txt)", RR, UNV + "; pin by digest")
img("prom/prometheus", "v3.5.0", "observability: dev compose; airgap 'observability' group (opt-in, REDISTRIBUTED)", "docker-compose.yml:402; airgap/images.txt", "NOASSERTION (Apache-2.0 per images.txt; busybox-based image carries GPL-2.0 busybox)", RR, UNV + "; GPL busybox redistributed; pin by digest")
add("container", "grafana/grafana-oss", "12.1.1", NOPIN, "Docker Hub", "DISTRIBUTION: airgap images.txt 'observability' group (saved into the Sangam air-gap bundle with --with-observability)",
    "sangam/deploy/airgap/images.txt; save-images.sh", "AGPL-3.0 (per airgap/images.txt comment; unverified from artifact)", "AGPL-3.0: source of the exact version must be offered to every recipient; network-use clause for modified versions", RJ,
    "AGPL-3.0 is in licence-policy.json forbidden_licenses for what Sangam ships, and no owner decision makes an exception; images.txt claims 'separate unmodified program' as justification without an owner record. Remove from the Sangam-produced bundle (customer pulls it) or obtain an owner decision", "images.txt lines 9-10, 28")
img("grafana/grafana-oss", "12.1.1", "dev compose only (local observability stack)", "sangam/deploy/compose/docker-compose.yml:417", "AGPL-3.0 (per images.txt; unverified)", AC,
    "dev/local use only, unmodified, not distributed by Sangam; pin by digest")
img("curlimages/curl", "8.15.0", "dev/test compose helper (cells)", "sangam/deploy/compose/docker-compose.cells.yml:158", "NOASSERTION (curl licence + Alpine packages)", RR, UNV + "; pin by digest")
add("container", "registry.local/dba/oracle-client-rman", "23 (example)", "n/a (customer image)", "customer registry", "customer-supplied backup image (Oracle client + RMAN)", "sangam/deploy/helm/sangam/examples/values-oracle.yaml:19; values.yaml:635",
    "LicenseRef-Oracle (customer-supplied; LIC-2)", "customer's own Oracle licence", AC, "Helm requires it to be supplied; Sangam never ships it (template 'required' guard)", "backup-cronjobs.yaml:82")
for prod, loc, note in (
    ("sangam-server", "server.Dockerfile; release.yml:188-220", "built and pushed by release.yml with BuildKit SBOM + provenance; also -distroless variant"),
    ("sangam-ui", "ui.Dockerfile; release.yml:240", "bundles the npm production closure incl. bpmn.io (LIC-8) and OFL fonts"),
    ("sangam-control-plane / sangam-gateway / sangam-rollup", "server.Dockerfile APP_MODULE; release.yml:221-229", "built by release.yml"),
    ("sangam-sdk-worker", "sdk-worker-native.Dockerfile; release.yml:231", "native image (GraalVM CE runtime compiled in); no BuildKit sbom/provenance flags on this build"),
    ("sangam-simulators", "simulators.Dockerfile; release.yml:252", "test tooling image, listed in airgap 'product' group"),
    ("sangam-script-worker", "script-worker.Dockerfile; compose; helm values.yaml:368-372", "NOT built by release.yml and NOT in airgap images.txt although the Helm chart references sqamentor/sangam-script-worker"),
    ("sangam-server-test-mode", "server-test-mode.Dockerfile", "test-mode overlay on sangam-server:dev")):
    dec = RR if prod in ("sangam-script-worker",) else AC
    cond = ("inputs approved for the test phase only (decision 79); distribution path (release build, image SBOM, notices, LGPL libnl) undefined" if prod == "sangam-script-worker" else
            "own Apache-2.0 code; Helm digest defaults to \"\" (pin by digest for production/air-gap); image SBOMs (syft) are only checked by build_tools_not_shipped.py, NOT by check_licences.py; no third-party notices file for base-image OS packages")
    add("container", prod, "${SANGAM_VERSION} / Chart.AppVersion", "digest produced at push (release.yml outputs) but Helm values digest=\"\" by default", "ghcr.io/sqamentor (built here)",
        "distribution (product image)", "sangam/deploy/docker/" + loc, "Apache-2.0 (own code) + base-image and bundled third-party licences", "NOTICE + third-party notices for bundled components", dec, cond + "; " + note,
        "Dockerfile LABEL org.opencontainers.image.licenses=Apache-2.0")

# ---------------- fonts
add("font", "NotoSansDevanagari-Regular.ttf", "notofonts/devanagari NotoSansDevanagari-v2.007", "sha256:573E525D9F4923D34645FE752475419F45F8DEC5A8E43F3E83C5C5F43D1F2FF9 (re-hashed 2026-11-12, matches README)",
    "github.com/notofonts/devanagari release zip sha256 820C7DA4...B469B1", "distribution (embedded in generated PDF/A by the M-081 renderer)", "sangam/deploy/fonts/", "OFL-1.1",
    "ship OFL text (OFL-NotoSansDevanagari.txt present), keep copyright, never sell alone, never rename (Reserved Font Name rules)", AC,
    "licence-policy conditional (decisions 70/95): unmodified, with OFL.txt, PDF embedding only", "fonts/README.md; OFL-NotoSansDevanagari.txt header; Get-FileHash")
add("font", "NotoSans-Regular.ttf", "notofonts/latin-greek-cyrillic NotoSans-v2.015", "sha256:F5F552C8C5EDB61FE6EFB824BAF4D4DE47B1A8689AB4925FF43F7BD6A4EBECE5 (re-hashed, matches README)",
    "github.com/notofonts/latin-greek-cyrillic release zip sha256 0C34DF07...B958F5", "distribution (PDF embedding)", "sangam/deploy/fonts/", "OFL-1.1", "as above (OFL-NotoSans.txt present)", AC,
    "decision 95 widened decision 70 to Noto Sans", "fonts/README.md; OFL-NotoSans.txt first line 'Copyright 2022 The Noto Project Authors'")
add("font", "SakalBharati.ttf", "13.02 (name ID 5)", "sha256:F27CF39CFAE18B05929792FC2610F5BE55D27E31AFEECA2A7D6D6040733B0805 (re-hashed, matches FONTS.md)", "cdac.in SakalBharati_Ship.zip (sha256 9cfc6b88...147154)",
    "DISTRIBUTION: bundled in the UI (public/fonts) and served to browsers", "sangam/sangam-ui/public/fonts/gov/central/sakal-bharati/", "OFL-1.1 (declared: name ID 13 + C-DAC page) - CONFLICTING name ID 10 'FOR NON-COMMERCIAL FREE USAGE'",
    "OFL text shipped (OFL.txt present); never sell alone", RR,
    "OFL-1.1 conditional in licence-policy.json covers only the Noto PDF fonts (decision 70 says any other OFL font needs a new owner decision); the font's own metadata contradicts OFL with a non-commercial clause (FONTS.md 2.1): needs owner/legal decision, possibly written C-DAC confirmation",
    "sangam-ui/FONTS.md §2, §2.1; public/fonts/.../OFL.txt")

# ---------------- CI actions + downloaded tools
actions = collections.OrderedDict([
    ("actions/checkout@v4", "first-party GitHub"), ("actions/setup-python@v5", "first-party"), ("actions/setup-java@v4", "first-party"), ("actions/setup-node@v4", "first-party"),
    ("actions/setup-dotnet@v4", "first-party"), ("actions/setup-go@v5", "first-party"), ("actions/upload-artifact@v4", "first-party"), ("actions/download-artifact@v4", "first-party"),
    ("actions/cache@v4", "first-party"), ("actions/attest-build-provenance@v2", "first-party (release, id-token/attestations write)"),
    ("github/codeql-action/init@v3", "first-party"), ("github/codeql-action/analyze@v3", "first-party"), ("github/codeql-action/upload-sarif@v3", "first-party (security-events write)"),
    ("docker/setup-buildx-action@v3", "third-party"), ("docker/setup-qemu-action@v3", "third-party (release)"), ("docker/login-action@v3", "third-party (release; receives GITHUB_TOKEN with packages write)"),
    ("docker/build-push-action@v6", "third-party (release; pushes product images)"), ("aquasecurity/trivy-action@v0.36.0", "third-party scanner (report-only)"),
    ("azure/setup-helm@v4", "third-party"), ("dorny/paths-filter@v3", "third-party (db-matrix)"), ("graalvm/setup-graalvm@v1", "third-party (major tag only; receives GITHUB_TOKEN)"),
    ("shivammathur/setup-php@v2", "third-party"), ("gradle/actions/setup-gradle@v4", "third-party"), ("aws-actions/configure-aws-credentials@v4", "third-party (security.yml; handles cloud credentials)"),
    ("ossf/scorecard-action@v2.5.0", "third-party (report-only)"), ("sigstore/cosign-installer@v3", "third-party (release signing; cosign version not pinned)"),
    ("softprops/action-gh-release@v2", "third-party (release; contents write)"),
    ("slsa-framework/slsa-github-generator/.github/workflows/generator_generic_slsa3.yml@v2.1.0", "reusable workflow; must be referenced by tag (verifies its own ref)"),
])
for a, kind in actions.items():
    nm, ref = a.rsplit("@", 1)
    slsa = "slsa-framework" in nm
    high = any(k in kind for k in ("release", "credentials", "GITHUB_TOKEN"))
    dec = AC if slsa else RR if high else AC
    cond = ("tag reference is required by the generator design; accepted" if slsa else
            ("mutable tag on a job holding write tokens/credentials: pin to the full 40-hex commit SHA (with a version comment) before release; licence unverified (expected MIT/Apache-2.0)" if high else
             "mutable tag: pin to full commit SHA; Dependabot github-actions has open-pull-requests-limit 0, so no update PRs arrive; licence unverified (expected MIT/Apache-2.0)"))
    add("github-action", nm, ref, "NONE (tag, not SHA)", "github.com/" + nm.split("/.github")[0], "CI (" + kind + ")", ".github/workflows/*.yml", "NOASSERTION (not inspected)", "none (not shipped)", dec, cond, "grep 'uses:' in .github/workflows")
tools = [
    ("trivy", "v0.76.2", "installed by trivy-action (no checksum in workflow)", "image scan, report-only", "ci-build.yml:182-191", RR, "report-only (exit-code 0, continue-on-error): never a gate"),
    ("grype", "v0.121.3", "NONE: curl | sh of install.sh from raw.githubusercontent.com, no checksum", "image scan, report-only", "ci-build.yml:195", RR, "unverified script piped to shell; add checksum/signature verification"),
    ("syft", "v1.29.0", "NONE: curl | sh install.sh, no checksum", "RELEASE: produces image SBOMs that are attested", "release.yml:263", RR, "unverified installer in the release job feeding attested SBOMs; verify checksum/cosign signature"),
    ("kubeconform", "v0.7.0", "NONE (no checksum)", "Helm manifest validation (gate)", "ci-build.yml:269", RR, "binary downloaded without checksum"),
    ("kubescape", "4.0.20", "sha256 checked against same-release checksums.sha256", "Helm posture, report-only", "ci-build.yml:276-283", AC, "same-origin checksum only (no signature); report-only"),
    ("gitleaks", "8.30.1", "sha256 checked against same-release checksums.txt", "secret scan, report-only", "security.yml:278-283", AC, "report-only (exit-code 0, continue-on-error); same-origin checksum; licence per comment MIT (unverified)"),
    ("helm", "v3.18.4", "via azure/setup-helm", "chart lint/package", "ci-build.yml:235; release.yml:392", AC, "pinned version; action tag-pinned"),
    ("cosign", "unpinned (cosign-installer@v3 default)", "installer verifies its own download", "RELEASE: keyless signing of SHA256SUMS", "release.yml:360", RR, "cosign version not pinned"),
    ("Node.js runtime (CI)", "\"22\" (floating major)", "NONE", "UI/SDK build", "ui.yml:41; release.yml:115; licence-check.yml:48", RR, "floating major differs from the image's node 22.18.0: SBOM/build not reproducible"),
    ("Temurin JDK (CI)", "\"25\" / \"21\" (floating)", "NONE", "build", "setup-java calls", AC, "floating patch level; Maven artifacts out of scope of this review"),
    ("Python (CI)", "\"3.13\" (floating)", "NONE", "CI scripts", "setup-python calls", AC, "floating patch level"),
]
for n, v, integ, use, loc, dec, cond in tools:
    add("ci-tool", n, v, integ, "GitHub releases / vendor", "CI: " + use, ".github/workflows/" + loc, "NOASSERTION (not inspected)", "none (not shipped)", dec, cond, "workflow lines cited")

allrows = npm + rows
cols = ["ecosystem", "name", "version", "integrity", "source", "usage_scope", "location", "spdx", "obligations", "decision", "conditions_or_reason", "evidence"]
with open(OUT, "w", encoding="utf-8", newline="") as f:
    w = csv.DictWriter(f, fieldnames=cols, quoting=csv.QUOTE_MINIMAL)
    w.writeheader()
    for r in allrows:
        w.writerow({c: r.get(c, "") for c in cols})
c = collections.Counter(r["decision"] for r in allrows)
print(len(allrows), dict(c))
print(collections.Counter((r["ecosystem"], r["decision"]) for r in allrows))
