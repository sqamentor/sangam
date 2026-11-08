# sangam-control-plane (SCALE)

Spring Boot app (port 8090, management 8091) with its own database and its own Operaton engine (one engine per JVM,
D-10). Design: `../SCALE-ARCHITECTURE.md` sections 5 and 7.

## Files and requirements covered
| File | Covers |
|---|---|
| `registry/TenantRegistry.java`, `Tenant.java`, `ResidencyRules.java` | n-level tenant hierarchy, jurisdiction, residency (narrow-only), assigned cell, CAS route epoch (ADR-D-20/21, SCALE 5.1, 9) |
| `cells/CellDirectory.java`, `Cell.java` | Registration (idempotent, residency immutable, ids never reused), heartbeat, DOWN after timeout (SCALE 5.2) |
| `placement/PlacementService.java` | Residency filter, capacity/outbox/pool filters, hierarchy affinity, headroom (SCALE 5.3; R40) |
| `routing/RoutingService.java`, `Route.java` | Routing API + incremental snapshot (SCALE 5.4) |
| `move/*`, `resources/bpmn/tenant-move.bpmn` | Tenant move drain → copy → verify → cut-over → retain → purge, rollback path (SCALE 7) |
| `audit/ControlPlaneAudit.java` | Append-only control-plane audit with content hash (R42 for control-plane decisions) |
| `web/ControlPlaneController.java` | REST API, RFC 9457 errors |
| `config/SecurityConfig.java` | Role tokens (ADMIN, CELL, GATEWAY, ROLLUP) |
| `config/SchemaConfig.java`, `resources/db/changelog/controlplane/**` | Self-contained Liquibase 4.33.0 changelog: postgresql, oracle, mssql, mysql, h2 (D-7, R12) |
| `tools/generate_scale_changelogs.py` | Generator of the control-plane and roll-up changelogs from one catalogue |

## API
`GET /api/v1/routes/tenants/{code}`, `GET /api/v1/routes?sinceMs=`; `PUT /api/v1/cells/{id}`,
`POST /api/v1/cells/{id}/heartbeat`, `GET /api/v1/cells[/{id}]`; admin: `POST /api/v1/admin/tenants`
(`{tenantCode, parentCode, levelKind, name, jurisdiction, residency, sector, expectedStartsPerSec, place}`),
`GET /api/v1/admin/tenants/{code}[/children|/placement-candidates|/moves]`, `POST /api/v1/admin/tenants/{code}/place`,
`POST /api/v1/admin/tenants/{code}/moves` (`{targetCell?, reason}`), `GET /api/v1/admin/moves/{id}`,
`POST /api/v1/admin/cells/{id}/placement?open=`, `POST /api/v1/admin/cells/{id}/retire`, `GET /api/v1/admin/audit?subject=`.

## Integration notes
The move needs the cell tenant-admin API (IR-4 in `../sangam-cell/README.md`). Operaton's own tables are created by
the engine (`schema-update: true`) in the control-plane database.

## Third-party dependencies
Spring Boot 4.1.2 starters (webmvc, jdbc, actuator, security, validation), operaton-bpm-spring-boot-starter 2.2.0,
Liquibase 4.33.0, HikariCP, Micrometer Prometheus (all Apache-2.0); JDBC drivers as sangam-server (H2 EPL/MPL,
PostgreSQL BSD-2, mssql-jdbc MIT, MariaDB LGPL-2.1 outside the fat jar per LIC-4/5).
