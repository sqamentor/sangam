<!-- SPDX-License-Identifier: Apache-2.0 -->
# M-016: database per tenant (owner decision 51, Q-02 option A)

Status: **PARTIAL**. The instance-side binding, its fail-closed checks, the dedicated cell registration and inbox admission, and the control-plane placement exclusivity are coded; their tests are written but not run. The control-plane API write path (cell `dedicatedTenant`, tenant `isolation`) is coded on the W3-M contract round 2. With sangam-security on, the recorded isolation is now carried and read as the system principal (W3-D, section 5). Lane W3-H (B49), 2026-11-12.

## 1. The decision and what it means in code

**Decision 51 (Q-02 = A).** One engine per JVM. Database per tenant is a server instance bound to one tenant database, with its own outbox, relay and inbox, placed and routed by the ADR-D-21 cell control plane. `TenantEngineRegistry` stays a library for real-clock embedded hosts.

**What already exists, and is reused unchanged.** A Sangam server instance (a cell) owns exactly one DataSource. Its outbox (`OUTBOX`, `OUTBOX_DELIVERY`), relay, inbox (`INBOX`), idempotency store (`IDEMPOTENCY_RECORD`), job partitions (`EXEC_*`) and Liquibase run all live in that one database. A database per tenant is therefore **a cell whose database holds one tenant**. Nothing in `OutboxRelayService`, `sangam-persistence` or `TenantEngineRegistry` changes, and Operaton's JVM-wide `ClockUtil` (W-7) is never shared between tenants.

**What was missing**, all strictly opt-in:

1. A **binding** of an instance to its tenant, enforced fail-closed: the instance never serves another tenant, and it refuses to start when its configuration or its database contradict the binding.
2. **Placement exclusivity** in the control plane:
   - a cell dedicated to tenant T hosts only T;
   - a tenant recorded with `isolation = DATABASE` is placed only on the cell dedicated to it.
3. **Registration** of the dedication: the cell tells the control plane which tenant it is dedicated to.
4. The **onboarding path**: create the dedicated cell, register it, create the tenant with `isolation = DATABASE`, and let the control plane place it.

## 2. Coded in this lane (sangam-server, new files)

| File | What |
|---|---|
| `bridge/DedicatedTenant.java` | Plain-Java binding: `of`, `admits`, `requireAttribution`, `requireDirectory`, `requireIsolation` |
| `bridge/DedicatedTenantFilter.java` | 421 `TENANT_NOT_ON_INSTANCE` for any `/api/` or `/engine-rest/` request naming another tenant (principal, `X-Sangam-Tenant`, `tenant` parameter, or the cell tenant-admin path). The answer never names the bound tenant. |
| `config/DedicatedTenantWiring.java` | `@ConditionalOnProperty(sangam.tenancy.dedicated-tenant)`. The binding bean checks the attribution tenant; the filter runs at order 39, just before the move-state filter; the start-up directory check runs too. |
| `config/ScheduleTenants.java` | Additive: `directory(port, sys, includeSuspended)`, the one directory reader. The old overload is unchanged. |
| `config/CellWiring.java` (integrator grant) | The registration descriptor carries the binding. The broker inbox admission **parks** a message of another tenant (tenant state `TENANT_NOT_ON_INSTANCE`: dead letter plus reconciliation item, then ack, SL-142); it is never stored in this database and never redelivered forever. The bound tenant keeps the IR-4 move-state decision. |
| `sangam-cell` `CellDescriptor` / `ControlPlaneClient` (grant) | `CellDescriptor` gains `dedicatedTenant` (null = shared). `register` sends it only for a dedicated cell and then requires the control plane to echo it in the PUT answer; otherwise the registration fails and is retried, so a dedicated cell never registers as a shared one. Until the control-plane field lands (W3-M), a dedicated cell therefore stays unregistered: fail-closed by design. |
| `sangam-control-plane` `PlacementService` (grant) | `reject` applies the dedication rule first: a cell with `CP_CELL.dedicated_tenant` hosts only that tenant, and a tenant with `CP_TENANT.isolation = DATABASE` goes only to the cell dedicated to it. `place` then fails with `NoCapacity` naming the missing dedicated cell. Moves obey it (`TenantMoveService` uses `reject`). SHARED and SCHEMA tenants are placed as before (Q-W3H-5 pending). `CellDirectory.dedicatedTenant` and `TenantRegistry.isolation` are read-only lookups. |

**Configuration.** `sangam.tenancy.dedicated-tenant` (`SANGAM_TENANCY_DEDICATED_TENANT`), together with `sangam.tenant.default` (`SANGAM_TENANT_DEFAULT`) set to the same tenant.

- Absent means today's shared behaviour.
- **Do not** add an empty default line for this key to `application.yaml` or Helm values. An empty value counts as set, and the start-up then refuses, the same rule as `sangam.cell.id`.

**Start-up refusals** (fail-closed, each with a message naming the fix):

- the key is blank or `*`;
- the attribution tenant is not the bound tenant, so untenanted work would be charged to a tenant that is not in this database;
- the tenant directory holds any other tenant (suspended tenants included);
- the bound tenant is recorded with an isolation other than `DATABASE`;
- the security module's `AdminPort` is missing, so the directory cannot be proven;
- `sangam.cell.id` is not set: a dedicated instance is a cell placed by the control plane (decision 51), and the cell wiring is what gates the broker inbox per tenant.

**Tests (written, not run):**

- `DedicatedTenantTest`;
- `DedicatedTenantFilterTest` (principal, header, parameter, admin path, no tenant, non-API path);
- `DedicatedTenantWiringTest` (cell required, attribution, empty directory, suspended other tenant, isolation, missing directory, filter order);
- `DedicatedCellAdmissionTest` (inbox park of another tenant, bound tenant keeps the move-state decision, shared cell unchanged, registration echo required, shared registration sends nothing new);
- `PlacementDedicationH2Test` in sangam-control-plane (the rule, shared default, dedicated cell exclusivity, DATABASE tenant only on its dedicated cell, refusal without one, moves).

## 3. Not changed, and why

- **`TenantEngineRegistry`.** It stays a library for embedded hosts with a real clock (decision 51). The server never builds several engines.
- **`OutboxRelayService` and the inbox.** They already run per instance, on its database.
- **Liquibase.** It already runs once per instance against its own database (`sangam.liquibase.mode`, or a one-shot migration job per tenant database).
- **The single-database default.** Without the key no dedicated bean, filter or check exists. Decision 97 adds one filter to every shared instance (section 5); both the server and the starter host pick up its configuration class by the same package scan, so `ServerBeanSetEqualityTest` compares equal sets.

## 4. Waiting on other lanes (posted on WAVE3-SYNC-BOARD, 2026-11-12)

| What | Owner | Shape |
|---|---|---|
| Problem code `TENANT_NOT_ON_INSTANCE` (421) in the contract | W3-M | Like `TENANT_MOVED`, with `details.tenant` only |
| Control-plane contract (M-017 `controlplane.py`) | W3-M | Cell registration `dedicatedTenant` (optional); tenant `isolation` with **the existing values** SHARED, SCHEMA, DATABASE |
| Control-plane schema | W3-K | **Done** (W3-K 12:23:57Z): `cp-0001-05` `CP_CELL.dedicated_tenant` str64 NULL; `cp-0001-06` `CP_TENANT.isolation` str16 NOT NULL DEFAULT 'SHARED' |
| Placement exclusivity, moves obeying it | **Done** (integrator grant) | See section 2. |
| `CellDescriptor.dedicatedTenant`, `CellWiring` registration and inbox admission | **Done** (integrator grant) | See section 2. |
| Control-plane API write path: `CellDirectory.Registration` and `Cell` gain `dedicatedTenant` (stored in `CP_CELL.dedicated_tenant`, echoed in the PUT answer); `TenantRegistry.NewTenant` and `Tenant` gain `isolation` (`CP_TENANT.isolation`); registration refused when a cell becomes dedicated while hosting another tenant | **Done** (W3-H on W3-M contract round 2, 14:01:57Z) | `dedicatedTenant` is trimmed and upper-cased like tenant codes (blank = shared, `*` or more than 64 characters = 400); a dedication change is refused (409) while a hosted tenant would break the placement rule, and audited as `CELL_DEDICATION`. `isolation` is set at create only: absent = SHARED, otherwise SHARED, SCHEMA or DATABASE (case-insensitive), anything else 400. `cp-0001-06` comment corrected. Test `PlacementDedicationH2Test` (written, not run). |
| Create-tenant refusal on a dedicated instance (a body field the filter cannot see), and record isolation in the security module (section 5) | W3-D (security admin), schema owner | 421 `TENANT_NOT_ON_INSTANCE` per decision 97; the start-up check already catches a dedicated-instance violation at the next restart |

## 5. Owner decision 97 (Q-W3H-5 = A): a shared instance refuses DATABASE and SCHEMA tenants

**Coded.** On a shared instance (`sangam.tenancy.dedicated-tenant` absent), `config/SharedInstanceIsolationWiring` registers `sangamSharedIsolationFilter`: the same filter class and order as the dedicated one, answering 421 `TENANT_NOT_ON_INSTANCE` for a request whose tenant is recorded with isolation `DATABASE` or `SCHEMA`.

- `bridge/SharedInstanceIsolation` reads the recorded isolation through `AdminPort.tenant` as the system principal, cached per tenant for 5 s.
- A tenant without a record, a request without a tenant and the wildcard are served as before. A failing lookup is not served.
- `DedicatedTenantWiring` and `SharedInstanceIsolationWiring` have complementary conditions (the property present, whatever its value, versus absent), so exactly one of them applies.
- Tests (written, not run): `SharedInstanceIsolationTest`, `SharedInstanceIsolationWiringTest`, and `DedicatedTenantFilterTest.aSharedInstanceRefusesDatabaseAndSchemaTenants`.

**Finding: the isolation is not recorded when sangam-security is on (REQUEST to W3-D, integrator and the schema owner).**

- `SecurityAdminBridge.java:501` reports every tenant as `SHARED`.
- `createTenant` and `updateTenant` (`:372-408`) drop the requested isolation.
- `TenantAdminService.Tenant` has no isolation field.

Consequences until this is fixed:

- a tenant created with `DATABASE` or `SCHEMA` is silently stored as `SHARED`;
- the decision 97 refusal fires only where the isolation is recorded (security off, `JdbcAdminAdapter`);
- a dedicated instance with security on refuses to start, because its bound tenant is never recorded as `DATABASE`. That is fail-closed, as intended.

The fix: isolation in the security tenant record, create and update (with the decision 97 refusal there too), a system read of one tenant's isolation, the bridge mapping, and the column.

**Status 2026-11-12T14:50Z: closed.** The column (`SEC_TENANT.isolation`, 0043-11, W3-G) has landed, and W3-D (DONE 14:34:41Z) carries it through `TenantAdminService` and `SecurityAdminBridge`: create stores it, update with a different value answers 409 `ISOLATION_IMMUTABLE`, a dedicated instance records its own tenant as `DATABASE` and refuses others, and `SecurityAdminBridge.tenant` answers the system principal with a read-only system view (id, status, isolation). Both wirings therefore see the recorded value with security on. Tests (written, not run): W3-D `SharedInstanceIsolationSecurityOnH2Test` and `SecurityAdminBridgeTenantIsolationH2Test`; W3-H `IsolationWiringSecurityOnH2Test` (through the wiring bean methods: the registered shared filter refuses DATABASE and SCHEMA 421, and a dedicated instance passes its start-up check only when its tenant is recorded as DATABASE and alone in the database).

## 6. Onboarding path (operations)

1. Provision the tenant's database, using the database profile and the same vendor matrix as a cell.
2. Start the instance with:
   - `sangam.cell.id=<cell>`;
   - `sangam.tenancy.dedicated-tenant=<tenant>`;
   - `sangam.tenant.default=<tenant>`;
   - the database URL;
   - `sangam.cell.capacity.tenants=1`.

   Liquibase creates the schema, and the directory check passes on an empty database.
3. The cell registers with the control plane. After the REQUESTs land, it also sends `dedicatedTenant`.
4. Create the tenant in the control plane with `isolation = DATABASE`. Placement puts it on its dedicated cell only.
5. Onboard the tenant on the instance (tenant admin, isolation `DATABASE`). From then on the instance serves that tenant only.
6. To move a shared tenant into its own database, use the existing IR-4 tenant move (freeze, export, import, activate) into the dedicated cell. The filter admits the bound tenant's admin path.
