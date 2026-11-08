# sangam-cli (NG-23)

`sangam` command-line tool over the REST API only, with no database access. Build: `mvnw.cmd -q -B -DskipTests -f tools/sangam-cli/pom.xml install`, then run `java -jar tools/sangam-cli/target/sangam-cli.jar`.

**Evidence level:** compiled, with `MainTest` run against a fake HTTP layer. Never run against a real server or identity provider. Device-flow polling has no test.

| Command | REST call | Note |
|---|---|---|
| `login` | OIDC discovery, then device flow or client credentials | https required except on loopback. The secret comes from an environment variable only |
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
- **Gap:** the access token is stored in `~/.sangam/cli.json` without refresh; on non-POSIX file systems it relies on the user profile's permissions. There is no OS keychain support.

## Third-party dependencies

Jackson (Apache-2.0), shaded into the jar. `maven-shade-plugin` at build time only.
