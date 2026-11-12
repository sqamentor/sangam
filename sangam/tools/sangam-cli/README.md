# sangam-cli (NG-23)

`sangam` command-line tool over the REST API only, with no database access. Build: `mvnw.cmd -q -B -DskipTests -f tools/sangam-cli/pom.xml install`, then run `java -jar tools/sangam-cli/target/sangam-cli.jar`.

**Evidence level:** compiled. `MainTest`, `TokenStoreTest` and `KeyProtectorTest` (M-093) are written against a fake HTTP layer, a fake keychain and a fake process runner; the M-093 tests have not been run yet (test phase). Never run against a real server, identity provider or OS keychain. Device-flow polling has no test.

| Command | REST call | Note |
|---|---|---|
| `login` | OIDC discovery, then device flow or client credentials | https required except on loopback. The secret comes from an environment variable only; only the variable's name is saved |
| `logout` | none | Deletes the saved tokens and the keychain key; keeps the server setting |
| `deploy FILE` | `POST /deployments` | Multipart |
| `list TYPE`, `get PATH` | GET | JSON on stdout |
| `start KEY` | `POST /process-instances` | `--idempotency-key` for CI retries |
| `test run PACKAGE` | `POST /packages/{id}/tests/run` | The server answers with an array of `TestRun`. Exit 1 when any has `passed: false` (an object with `failed > 0` is also understood) |
| `batch status ID` | `GET /ops/jobs/{id}` | |

Exit codes: 0 ok, 1 refused or test failures, 2 usage, 3 authentication, 4 network.

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

Jackson (Apache-2.0), shaded into the jar. `maven-shade-plugin` at build time only. The keychain needs no new dependency: it uses JDK `javax.crypto` and the platform's own tools.
