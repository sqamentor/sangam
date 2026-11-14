# sangam-cli (NG-23)

`sangam` command-line tool over the REST API only, with no database access. Build: `mvnw.cmd -q -B -DskipTests -f tools/sangam-cli/pom.xml install`, then run `java -jar tools/sangam-cli/target/sangam-cli.jar`.

**Evidence level:** compiled. `ModelDiffCommandTest` (M-125, written, not run), `MainTest`, `TokenStoreTest` and `KeyProtectorTest` (M-093) are written against a fake HTTP layer, a fake keychain and a fake process runner; the M-093 tests have not been run yet (test phase). Never run against a real server, identity provider or OS keychain. Device-flow polling has no test.

| Command | REST call | Note |
|---|---|---|
| `login` | OIDC discovery, then device flow or client credentials | https required except on loopback. The secret comes from an environment variable only; only the variable's name is saved |
| `logout` | none | Deletes the saved tokens and the keychain key; keeps the server setting |
| `deploy FILE` | `POST /deployments` | Multipart |
| `list TYPE`, `get PATH` | GET | JSON on stdout |
| `start KEY` | `POST /process-instances` | `--idempotency-key` for CI retries |
| `test run PACKAGE` | `POST /packages/{id}/tests/run` | The server answers with an array of `TestRun`. Exit 1 when any has `passed: false` (an object with `failed > 0` is also understood) |
| `batch status ID` | `GET /ops/jobs/{id}` | |
| `upgrade check` | none (offline); with `--schema` `GET /ops/schema-status` | M-092; see below |
| `diff BEFORE AFTER` | none (offline) | M-125; see below |

Exit codes: 0 ok, 1 refused or test failures (for `upgrade check`: any blocking finding; for `diff`: a change at a `--fail-on` level), 2 usage, 3 authentication, 4 network.

## Upgrade check (M-092)

Run the CLI of the **target** release before an upgrade:
`sangam upgrade check --models DIR [--models DIR]... --config application.yaml [--config prod.env]... [--as-of yyyy-MM-dd] [--schema postgresql|oracle|mssql|mysql|h2]`.
Without `--schema` it needs no server. It prints one JSON report (`blocking`, `warnings`, `info`, `findings`, `notChecked`, and `schema` when compared).

- **Models:** every `.bpmn`, `.bpmn20.xml`, `.dmn` and `.cmmn` file under each directory is parsed (no DTD, no external entity) and checked against the target's removed or deprecated constructs.
- **Config:** `.yaml`/`.yml`/`.properties` keys in Spring relaxed form (case, `-` and `_` ignored; a rule key also covers its sub-keys), and `.env` variable names, against the target's removed and renamed settings.
- **API:** the target's `sangam-api-deprecations.yaml` (bundled from `sangam-rest/api` at build time; the file `ApiDeprecationRegistry` loads). An operation past its sunset on the as-of date (default today, UTC) is blocking, because it answers 410 `API_SUNSET`; a deprecated one is a warning with its successor.
- **Schema** (`--schema DATABASE`, the database type of the installation): the CLI reads `GET /ops/schema-status` from the logged-in server (`--server` or `sangam login`; permission `ops.read`) and compares the applied product changesets with the target's changelog, bundled from `sangam-persistence` at build time (`db/changelog/sangam-master.xml` and the formatted-SQL files it includes; engine patches and history partitioning are not product changesets). Blocking: applied changesets the target does not know (a newer release migrated the database; downgrades are not supported), or a held Liquibase lock. Info: the changesets the upgrade's migration Job will apply. Without `--schema` the report lists the schema under `notChecked`. A 401 or 403 from the server exits 3, another refusal exits 1.
- **Rules:** `src/main/resources/upgrade-rules.json` holds what the release removes or deprecates (`--rules FILE` and `--deprecations FILE` override the bundled files). 0.1.0 is the first release, so its lists are empty.

Scaffolding for later releases:
- `upgrade/openrewrite/rewrite.yml`: declarative OpenRewrite recipes for Java SDK users (`org.sangam.upgrade.UpgradeToLatest`, empty for 0.1.0). OpenRewrite is not part of the Sangam build; users run the file with their own setup.
- SDK codemods for the other languages follow the same rule: a release that renames a public SDK symbol adds the rename to `upgrade-rules.json` (so `upgrade check` reports it) and to the SDK's changelog. No codemod tool is shipped, because none is installed in the build.

## Model diff (M-125)

`sangam diff BEFORE AFTER [--json] [--fail-on ELEMENT,FLOW,EXPRESSION,PROPERTY] [--resource NAME]` compares two versions of a BPMN, DMN, CMMN or form file with `org.sangam.modeller.diff.ModelDiff`, the same code as `POST /models/diff`. It needs no server and no login.

- **Output:** one line per change (`+` added, `-` removed, `~` changed, then level, element type, id, name, path and old -> new value). `--json` prints the `ModelDiffReport` of `POST /models/diff` instead.
- **Matching:** elements are matched by id (form components by `id`, else `key`). Diagram layout (BPMNDI, DMNDI, CMMNDI) and modeller `exporter` attributes are ignored, so moving a shape is not a change.
- **Absent version:** an empty file (for example `/dev/null`) stands for a new or deleted model; both empty is refused.
- **Format:** from `--resource`, else the AFTER file name (BEFORE when AFTER is empty), else the content.
- **PR check:** with `--fail-on`, exit 1 when any change is at a listed level; without it, exit 0 whatever changed. A file that cannot be read, an unreadable model, two different formats or bad options exit 2.

## Integration notes

- **Tables:** none.
- **Wiring:** none in `sangam-server`. `POST /packages/{id}/tests/run` exists in sangam-rest. For `login` with device flow, the OIDC client must allow the device grant (`urn:ietf:params:oauth:grant-type:device_code`) at the identity provider; the server cannot turn this on.
- **Screens:** none.
- **Tokens (M-093):**
  - `~/.sangam/cli.json` holds settings only: server, issuer, token endpoint, client id, account id, and the name of the secret variable.
  - The tokens are kept in `~/.sangam/credentials/<account>.json`, which is always owner-only: mode 0600, set at creation, on POSIX; a single owner access entry on Windows NTFS. The file is written to a temporary file and then moved into place.
  - `SANGAM_TOKEN_STORE` chooses where the tokens go:
    - `auto` (the default): the tokens are encrypted with AES-256-GCM under a random 256-bit key, with the account as associated data. The key is held by the OS secret store, through the tool the platform already ships: macOS `/usr/bin/security` (login keychain), Linux `secret-tool` (Secret Service), or Windows `powershell.exe` with the Data Protection API for the current user. The key is passed on stdin, never on a command line, and every store is read back to verify it. If no store works, the tokens go to the owner-only file in clear text, with a note on stderr.
    - `keychain`: the keychain is required; login fails without one.
    - `file`: always the owner-only file, for CI runners and servers.
  - The access token is renewed 60 s before `expires_in` runs out, and once after a 401; the same request (same `Idempotency-Key`) is then sent again.
    - With a refresh token, the CLI uses the `refresh_token` grant. A rotated refresh token replaces the old one.
    - A service principal repeats `client_credentials` with the secret read from the same environment variable.
    - If renewal fails, the CLI exits 3 and prints the OAuth error code only.
  - `SANGAM_TOKEN` overrides everything; it is never stored or renewed.
  - A `cli.json` from before M-093 that still holds `accessToken` is migrated once into the store.
  - No command prints a token.
- **Limits:** none of the three platform tools has been run in a test (fakes only). On Linux without `secret-tool` or a running Secret Service (headless hosts), `auto` uses the file. The Windows access list replaces the file's DACL but is not marked protected, so a later change to the parent's inheritable entries can apply to it again.

## Third-party dependencies

Jackson (Apache-2.0), shaded into the jar, including `jackson-dataformat-yaml` and SnakeYAML (Apache-2.0) for `upgrade check`. `sangam-modeller` (this project, Apache-2.0) for `diff`, with every transitive dependency excluded: its diff package uses only the JDK and Jackson, so no Operaton or other Sangam module jar enters the shaded jar. `maven-shade-plugin` at build time only. The keychain needs no new dependency: it uses JDK `javax.crypto` and the platform's own tools.
