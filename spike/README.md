# Sangam Stage B spike

Implements the plan in `../SANGAM.md` Part XI. It answers ADR-D-1: which base engine to choose, Flowable 8.0.0 or Operaton 2.2.0.

## Modules

| Module | Purpose |
|---|---|
| `product-api` | Engine-neutral contract (`EngineAdapter`) |
| `adapter-flowable` | `EngineAdapter` on Flowable 8.0.0 (embedded, no Spring) |
| `adapter-operaton` | `EngineAdapter` on Operaton 2.2.0 (embedded, no Spring) |
| `product-layer` | Components built once: authorization (VI.4), operation ledger and leases (VI.3), idempotency (X.2.2), outbox/inbox (VI.2), tombstones (VI.5), audit chain (X.2.3) |
| `corpus` | WF-1, WF-2, WF-3 and the GAP set. Each model is shared, plus engine-specific variants where an engine needs one, and every variant is recorded |
| `simulators` | PFMS SFTP files, IFMS JIT, eSign v2.1, DigiLocker Push, plus the Testcontainers harness (PostgreSQL / Oracle Free / SQL Server, Keycloak) |
| `experiments` | E1–E11 suites; results go to `results/` |

## Build

```
.\mvnw.cmd -q verify
```

This needs JDK 21+ and Docker (for Testcontainers).

## Rules

- Count and record every engine patch or workaround in `results/patch-ledger.md`.
- Use synthetic data and simulators only (ADR-D-13).
- Never bundle Oracle, Db2 or MySQL JDBC drivers, or GraalJS (LIC-1…LIC-4). Test scope only.
