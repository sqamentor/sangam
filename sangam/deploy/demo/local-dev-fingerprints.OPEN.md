# `local-dev` key fingerprints for SANGAM_KEYS_FOREIGN_FINGERPRINTS: OPEN

Owner choice on 2026-11-19: "Local dev keys (Recommended)". This file records why no `local-dev <sha256>` lines can be
handed over yet, and the exact command the owner can run. No key was read, printed or copied to produce it.

## Why there are no lines yet (from the code)

- **Which keys get fingerprints.** Only *configured* keys are fingerprinted:
  - the KEK, the registry signer and pepper, the compliance principal key and the audit checkpoint pair, among others;
  - SECRET keys use HMAC-SHA256 with the key `sangam:key-fingerprint:v1`; PUBLIC keys use SHA-256 (`KeyPurposeSeparation.java:99-127`, `:340-356`).
- **A stock local dev server configures none of them.**
  - The `h2` profile has no KEK, signer or pepper defaults.
  - With no KEK set, the dev server uses a per-JVM ephemeral master key (`SecurityKeysWiring.java:52-80`), which is never fingerprinted.
  - The checkpoint and principal-key references default to empty (`application.yaml:165`).
  - So a stock local dev export contains only its header line, with zero fingerprints.
- **The export runs inside the server.**
  - It is written by `sangam.keys.fingerprint-export` at Spring context start-up (`KeyPurposeSeparationWiring.java:39-51`, `KeyEnvironmentSeparation.java:212-225`).
  - There is no offline tool for it.
- **The owner's servers are not touched.** Ports 18080, 18081 and 5173 belong to the owner and were not started, stopped or queried.
- **No dummy line is acceptable.** Production needs at least one foreign line (`KeyEnvironmentSeparation.java:111-113`). A made-up fingerprint would satisfy that check while proving nothing, so none is supplied.

## What the owner can run (only if the local dev server is started with explicit keys)

Run a second JVM on random ports, using the same key settings as the local dev server. It does not touch the running
servers. In PowerShell:

```
$env:SANGAM_KEYS_ENVIRONMENT = 'local-dev'
$env:SANGAM_KEYS_FINGERPRINT_EXPORT = '<handover>\local-dev-fingerprints.txt'
# plus the SAME key variables the local dev server uses (SANGAM_SECURITY_KEK_KEYS_*, SANGAM_REGISTRY_SIGNER_*, ...)
java -jar sangam-server.jar --spring.profiles.active=h2 --server.port=0 --management.server.port=0
# stop it (Ctrl+C) once the log shows the export written; the file holds hashes only: '<env> <fp> <setting>' lines
```

## Open choices for the owner

If the local dev server runs with the stock ephemeral keys, it has nothing to fingerprint. The choices are then:

- (i) give local dev fixed keys first and export them as above;
- (ii) use another environment's list (for example a staging key set generated now and kept apart);
- (iii) use the one-time first-environment bootstrap (`SANGAM_KEYS_BOOTSTRAP=true`, decisions 387(4) and 390; it is recorded as KEY_ENVIRONMENT_BOOTSTRAPPED and refused on any later start).
