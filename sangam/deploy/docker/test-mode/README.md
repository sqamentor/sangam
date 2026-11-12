# Sangam server test mode (R18 layer 2, M-021)

R18 gives SDK users two test layers:

1. **In-process fake** (layer 1, owned by the SDKs): `SangamTestEngine` in every Tier-1 SDK (Java
   `org.sangam.sdk.testing`, TypeScript `src/testing.ts`, Python `sangam_sdk.testing`, .NET `Sangam.Sdk.Testing`, PHP
   `Sangam\Sdk\Testing`). It fakes the worker API with the VI.3 lease, fencing, STALE_LEASE, UNKNOWN_OUTCOME and R41
   replay semantics and a steerable clock. Use it for unit tests of a handler.
2. **Test-mode server** (layer 2, this image): the real server, so the same tests run against the real state
   machine, the real BPMN engine and the real problem documents. Use it for process-level tests and for the M-023
   contract tests, which must pass against both layers.

## Build and run (nothing is pulled: the image is a layer on the server image you built)

```sh
# from sangam/
docker build -f deploy/docker/server.Dockerfile -t sangam-server:dev .
docker build -f deploy/docker/server-test-mode.Dockerfile -t sangam-server:dev-test-mode .
docker run --rm -p 8080:8080 -p 8081:8081 sangam-server:dev-test-mode
# ready when this answers {"status":"UP"}
curl -s http://localhost:8081/actuator/health/readiness
```

`--build-arg SERVER_IMAGE=<your registry>/sangam-server:<tag>` layers it on a server image from your registry
instead. With the distroless server target the same layer works (it adds only labels and environment).

## What test mode sets

| Setting | Value | Source key |
|---|---|---|
| Profile | `h2` | `application-h2.yaml`: in-memory H2 (PostgreSQL mode), schema on start, dev token, dev bootstrap tenant `dev`, simulators on |
| Job acquisition wait | 100 ms (max 500 ms) | `SANGAM_JOB_WAIT_TIME_IN_MILLIS`, `SANGAM_JOB_MAX_WAIT` (`operaton.bpm.job-execution.*`) |
| Token | `SANGAM_DEV_TOKEN` (the profile's development default unless you pass `-e SANGAM_DEV_TOKEN=...`) | `sangam.security.dev-token.*` |
| Tenant | `SANGAM_TENANT_DEFAULT` (default `dev`) | `application-h2.yaml` |

The database lives in memory: every container start is a clean server. Point the SDK at `http://localhost:8080`
with the dev token as bearer token (Java: `SangamClient.builder().baseUrl(...).tokenProvider(TokenProvider.of(token))`).

**Never use this image in production.** The development token is a platform identity on this profile
(`sangam.security.platform-issuer`), and the data is gone on restart. The image carries the label
`org.sangam.test-mode=true` so a registry policy can keep it out of production namespaces.

## Pending (needs server work, requested on the Wave 3 board)

Layer 1 fakes steer time with `advance(Duration)` (real clock plus an offset). The server has the pieces (the engine
clock wrapper `OperatonClock.set`, `BusinessClock.install`) but no endpoint and no switch, so this image cannot yet:

- **steer the clock**: move the engine clock and the business clock together, and read them back;
- **skip time**: advance by a duration so due timers and SLA escalations fire on the next job acquisition.

Requested shape (contract W3-M, engine W3-F, server wiring integrator): a `sangam.test-mode.enabled` switch (default
false, refused at start unless the datasource is H2), and `POST /api/v1/test-mode/clock` with `{"advanceBy":"PT2H"}`,
`{"set":"2026-12-01T00:00:00Z"}` or `{"reset":true}`, plus `GET` returning `{"now", "offsetMs"}`, admin only. When it
lands, this Dockerfile adds `SANGAM_TEST_MODE_ENABLED=true` and nothing else.
