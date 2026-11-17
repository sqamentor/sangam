# sangam-security (WS-SECURITY)

Authorization (VI.4, deny wins), multi-tenancy, OIDC, the organisation model (seats ≠ persons), seat-based task
assignment, delegation of powers as versioned DMN, maker-checker / SoD, approval bound to the payload hash, admin
services (policies, roles, groups, users, privileges, tokens, tenants) and security audit.

Status: **all code in scope is written and compiles** (`mvnw.cmd -q -B -DskipTests -pl sangam-security -am install`).
Tests were not written or run, per the owner's phase rule (ARCHITECTURE.md §5: testing comes last).

## Entry point

`org.sangam.security.SangamSecurity` wires every service for one engine database:

```java
SangamSecurity sec = new SangamSecurity(SecurityDialect.POSTGRESQL,
        new SangamSecurity.Options(Duration.ofSeconds(5), null, 100, Transactions.of(dataSource), null));
engineConfiguration.getProcessEnginePlugins().add(new SangamSecurityEnginePlugin(sec));
```

Every service method takes the caller's `java.sql.Connection` and never commits it, the same as the core layer.

## Requirements covered, file by file

Paths are relative to `src/main/java/org/sangam/security/` unless they start with `sangam-core`.

### Engine-neutral ports (added to sangam-core; no existing core file changed)

| File | Covers |
|---|---|
| `sangam-core/.../core/security/SecurityIdentity.java` | VI.4 rules 6 and 7 (the tenant comes from the identity; system principal with `onBehalfOf`); IX.5 rules 1 and 4 (seats held now, acting-for seats); prefixed principal ids; bridge to the spike `AuthorizationPolicy.Principal` |
| `sangam-core/.../core/security/SangamSecurityException.java` | X.4.0.4 / X.5.2 normative codes: `ACTION_NOT_PERMITTED` 403, `SOD_VIOLATION` 422, `POWER_LIMIT_EXCEEDED` 422, `APPROVAL_INVALIDATED` 409, `TENANT_MISMATCH`, `NOT_LINKED`, `MFA_REQUIRED`, `POLICY_REJECTED`; R28 (no SQL or stack trace in the error) |
| `sangam-core/.../core/security/SecurityPorts.java` | Ports for other workstreams: `Authorization` (VI.4), `SeatDirectory` (IX.5, K7), `DelegationOfPowers` (K6), `SegregationOfDuties` (K5), `ApprovalBinding` (K4), `SecurityAudit` (R42) |

### Policy compiler and the single read path (R19 / VI.4, A-3, A-13, A-16, D-14, D-15, D-36, D-55)

| File | Covers |
|---|---|
| `dialect/SecurityDialect.java` | A-15: SQL per dialect for PostgreSQL, Oracle, SQL Server, MySQL 8.4 and H2 (dev). Covers `BITAND` vs `&`, paging, the capped count (A-16), row locks and duplicate-key classification. Maps to the core `Dialect` |
| `policy/Permission.java` | The permission bit layout. Bits 0–10 are identical to the spike's `Action` (shared rows). It adds `READ_SENSITIVE`, `START`, `DELETE_INSTANCE`, `ERASE_DATA`, `MIGRATE`, `BULK`, `EXPORT`, `DEPLOY`, `ADMIN`, `APPROVE_SOD_EXCEPTION`, `WORK`, `ASSIGN`, `REVIEW_PACKAGE`. It also defines the list-visible set (VI.4 rule 1) and the sensitive set (rule 5) |
| `policy/ResourceDescriptor.java`, `policy/ResourceRegistry.java` | VI.4 rule 1 (only attributes stored as columns), rule 8 (history types `HISTORIC_*` use the same predicates) and A-13 scopes (`procinst:`, `def:`, `bk:`, `taskdef:`, `topic:`). Engine tables are Operaton 2.2.0 `ACT_RU_*`/`ACT_HI_*`. Admin types are virtual (B-11). FC-31 `INSTANCE_LINK` is a protected type |
| `policy/Condition.java`, `policy/ConditionCompiler.java` | VI.4 rule 1: one condition yields both a SQL form and an equivalent Java form. A list-visible policy that does not compile to SQL is rejected (`requireCompilable`). Principal references (`personId`, `seats`, `groups`, `roles`) are supported |
| `policy/PolicyCompiler.java` | The compiler. **Deny wins at any scope** (A-3, A-13): the predicate is compiled as `NOT EXISTS` branches over id, `*`, every scope and the resource-type wildcard. A conditional grant matches only when its condition holds. A conditional deny with an unknown condition always applies (fail closed). The A-16/D-55 index plan uses one `EXISTS` per branch and a bound tenant. Above 200 principal ids it joins `AUTH_MEMBERSHIP` (rule 2). The **object check runs the same predicate** against the single row (T37 equivalence). Condition cache keyed by `policy_version` with ≤ 5 s staleness, re-read for sensitive actions (rule 5) |
| `policy/AuthorizedQueryBuilder.java` | VI.4 rule 3 / K2 / D-15 / D-36: the single path for list, count, search, export, dashboard and get-by-id, with no post-filtering (A-3). Capped count `CappedCount(value, capped)` gives "more than N" (A-16, R65: counts never reveal hidden rows). Page size cap 1,000 (R28). A request tenant is only a filter (rule 6) |
| `policy/ModuleResourceTypes.java` | Resource types of the generic modules, each with table, id, tenant column, condition attributes, broader scopes (A-13) and a **permission set** (a policy naming another permission on the type is rejected at deployment). Table-backed: `RES_*` (all 17 `ResourcesContext.RT_*` types), `CASE_FILE`, `CASE_REFERENCE`, `COMMITTEE`, `PROGRAMME`, `PROGRAMME_RETURN`, `registry-object`, `registry-party`. Virtual (object checks on `*` or an id): `registry-asset`, `registry-work-order`, `registry-instrument-public`, `COMPLIANCE`, `CONFIG`, `LEDGER`, `BILLING`, `FIELD`, `DISPATCH`, `GEO` |
| `policy/AuthEffectiveMaintainer.java` | VI.4 rule 2: `AUTH_EFFECTIVE` materialisation with **incremental maintenance** per grant source in the same transaction. Every change increments `policy_version` (rule 5). Also provides full `rebuild` and drift `verify` |
| `policy/MembershipIndex.java` | VI.4 rule 2 membership table (effective-dated). IdP group/role mirroring for the > 200 path (D-55). `policy_version` increments on change |
| `policy/AuthorizationService.java` | `SecurityPorts.Authorization`. Object re-check inside the command transaction (K3). `AUTH_DENIED` with `policy_version` (CX-1) survives the rollback |

### Multi-tenancy

| File | Covers |
|---|---|
| `tenant/TenantGuard.java` | VI.4 rule 6, K1, S9: a request tenant only filters, writes always use the identity's tenant, and a row's tenant must match |
| `tenant/TenantContext.java` | Per-request/job identity binding, used by engine listeners and the tenant id provider |
| `tenant/SangamTenantIdProvider.java` | Operaton `TenantIdProvider`: every instance carries the identity's tenant. Sub-instances inherit it. A shared definition falls back to the caller's tenant (FC-33). With no identity it fails closed |
| every query in every service | tenant column bound in every statement |

### OIDC, tokens and identity (R50, R67, A-17, FC-42)

| File | Covers |
|---|---|
| `oidc/JtiReplayGuard.java`, `oidc/JdbcReplayGuard.java` | Plain-Java client-token replay contract (one use per issuer and `jti`) and its cluster-wide store (SL-138) |
| (moved, M-141a) `org.sangam.security.spring.SangamResourceServerConfiguration` in sangam-server | Spring Security resource server: JWKS/issuer validation, clock skew, audience, required `tenant` claim. Stateless, no CSRF. URL rules only require authentication; authorization lives in the services |
| `oidc/KeycloakClaimsMapper.java` | Keycloak mapping: realm roles, client roles, groups (path prefix stripped), tenant claim. **MFA pass-through** per tenant (`acr`/`amr`, R67). Seats never come from the IdP (AJ-05 step 3) |
| `oidc/SecurityProperties.java` | `sangam.security.*` settings: issuer, JWKS, audiences, JIT tenants, MFA tenants and dev tokens. **Dev static-token mode** is refused on a production profile |
| (moved, M-141a) `org.sangam.security.spring.TokenAuthenticationFilters` in sangam-server | API-token bearer (`sgm_…`) and the dev static-token filter |
| (moved, M-141a) `org.sangam.security.spring.IdentityResolutionFilter` in sangam-server | Resolves person, groups, roles and seats, enforces MFA, binds `TenantContext`, and logs logins once per session in batches |
| (moved, M-141a) `org.sangam.security.spring.SangamAuthenticationToken`, `SecurityWebSupport` and `ClientJwtVerifier` in sangam-server | Authentication token; RFC 9457 problem responses (R28); a JWT-only bearer resolver; the client JWT verifier |
| `identity/PrincipalResolver.java` | Tenant active check (AJ-01). Subject → person mapping, else `NOT_LINKED`. **JIT provisioning** (R67). Groups and roles from the IdP plus Sangam memberships, role bindings of the user, its groups and its seats. Seats from postings at the database time |
| `identity/ResolvedIdentityCache.java` | SG-15: per-token cache of the resolved identity. Every request does one validation read (tenant `policy_version`, tenant status, the person the subject maps to and its status, database time). Every product write path that changes the result bumps `policy_version` or is caught by that read, so revocation through the product is seen on the next request (`ResolvedIdentityCacheH2Test`). An entry also ends at the next effective-date boundary of the memberships, postings and leave it depends on. **`MAX_AGE` = 5 minutes is the upper bound for changes made directly in the database outside the services**; `clear()` drops all entries. There is no operator endpoint for it (restart the node, or bump `AUTH_POLICY_VERSION` for the tenant) |
| `identity/LdapDirectorySync.java` | R67 / A-17: **LDAP/AD read and scheduled sync** (JDK JNDI, no new dependency). Users and groups with source `LDAP`; missing users are disabled; TLS is required |
| `org/PersonService.java` | Users (FC-42 IDM users): create, update, link subject, disable, JIT |
| `admin/GroupAdminService.java` | Groups and memberships (FC-42 IDM groups), mirrored into `AUTH_MEMBERSHIP` |
| `admin/PrivilegeService.java` | **FC-42 privileges and privilege mappings**, evaluated as policy objects, so a deny mapping wins |
| `admin/ApiTokenService.java` | **FC-42 tokens**: hashed secrets, scopes, expiry ≤ 1 year, revoke, revoke-all, last-used, constant-time compare |

### Organisation model (IX.5, AJ-02, AJ-04)

| File | Covers |
|---|---|
| `org/OrgModel.java` | Value types: units, seats, postings (REGULAR / ADDITIONAL_CHARGE / LEAVE_SUBSTITUTE), leave, transfer preview |
| `org/OrgUnitService.java` | IX.5 rule 2: level types fixed per tenant. **Effective-dated** parent, so a re-organisation from a date applies to new tasks only (AJ-02 step 3). **Effective-dated, audited rename** (`renameUnit`, `ORG_UNIT_RENAMED`, name history; earlier dates keep the old name). Provides ancestors, ancestor at a level and children. Cycle guard |
| `org/SeatService.java` | IX.5 rule 1: seat = designation + office; ids are never reused. Seat moves are effective-dated. Abolition is blocked while the seat has open work or holders (AJ-02 step 4). Routing by level, and seniority for the SoD exception approver |
| `org/PostingService.java` | Effective-dated postings, **transfers** with preview (AJ-04 step 1), **additional charge** (step 2), **leave with substitute** where the requester cannot approve (step 5), **vacancies** and the escalation seat (step 6), and the holder at time t (K7). Expiry sweeper `DELEGATION_EXPIRED` (step 4). Each posting is mirrored as a seat membership, so grants follow posting dates |

### Seat-based task assignment (IX.5 rules 1, 2, 4, 5; CX-1; CX-8; R82 base)

| File | Covers |
|---|---|
| `assignment/SeatAssignmentResolver.java` | Engine-neutral resolver. Takes a fixed seat or a designation at a level. Routes past seats that acted (SoD by seat) and excludes persons who acted (SoD by person; the excluded person gets an explicit deny on that task, so the candidate is not offered). Escalates on vacancy. Task grants are materialised for the **seat** principal, so the person holding the seat at the time of action acts |
| `assignment/SeatTaskListener.java` | Operaton `TaskListener` for create, assignment, complete and delete. Resolves on create. Checks K7 and K5 again at claim and at completion, at the DB time. Records the actor for SoD. `TASK_ACTED_FOR`. Releases grants. A failure rolls the command back |
| `assignment/SeatAssignmentParseListener.java` | `BpmnParseListener`: attaches the listener to every user task from `operaton:properties` (`sangam.seat`, `sangam.seatVariable`, `sangam.level`, `sangam.designation`, `sangam.unitVariable`, `sangam.duty`, `sangam.caseVariable`). Records the process starter as INITIATOR |
| `assignment/SangamSecurityEnginePlugin.java` | `ProcessEnginePlugin` (ADR-D-16): registers the parse listener and the tenant id provider. Built-in authorization **off** (D-14) |
| `assignment/EngineConnections.java` | The engine command's JDBC connection (same transaction) |

### Delegation of powers (IX.5 rule 3, K6, AJ-03, T18)

| File | Covers |
|---|---|
| `delegation/DmnPowerRows.java` | Operaton standalone DMN engine, as a reader only: the matched rows of a table (`maxAmount` / `allowed` / `ruleRef`). It decides nothing (RM-08). Parsed decisions are cached by content hash |
| `delegation/DelegationOfPowersService.java` | **Versioned** tables `DRAFT → IN_REVIEW → APPROVED → PUBLISHED → RETIRED`. Draft test cases cover boundaries, overlaps and gaps. The reviewer must differ from the author (K5). Publishing sets the effective date and closes the previous version. The impact preview supports AJ-03 step 3. **Checked at action time** with the version in force on that date, by the one power-limit evaluator of sangam-core (`PowerLimitEvaluator`, contract `sangam.power-decision/1`; owner decision 142, RM-08): the service supplies the request, the trusted case-type metadata the host binds with `useOperationMetadata` (the deployed pack manifests, decision 193; none bound: every check is denied) and the table rows as delegated limits (`LegacyPowerContracts.serviceRows`), so every row must pass, the lowest ceiling binds and `UNLIMITED` never lifts another ceiling or the posting power cap (a mandatory cap). The port carries no action, case-type version or currency: the caller states them in `extraInputs` (`action`, `caseTypeVersion`, `currency`, optional `stateVersion`); a missing one denies. No table, an inactive seat, no row, a row without a limit, a malformed value and a failed evaluation are denials with a reason. Writes `AUTHORITY_DECISION` with `decision_ref` (X.2.3.2) and `POWER_LIMIT_EXCEEDED`, both with the evaluator's payload under `power_decision` |

### Maker-checker / SoD (IX.5 rule 5, K5, CX-8, AJ-08, T17)

| File | Covers |
|---|---|
| `sod/SegregationOfDutiesService.java` | Initiator ≠ checker ≠ approver **by person and by seat**, including the seat acted for. Duty conflicts are configurable. Exclusion sets feed candidate selection. The **controlled exception** needs a reason. It is approved by a higher authority with `APPROVE_SOD_EXCEPTION` who holds a senior seat, is not the requester and is not in the case chain. It is limited to one case, person and duty, has an expiry and is used once. Writes `SOD_VIOLATION_BLOCKED`, `SOD_EXCEPTION_*` |

### Approval bound to payload hash (VI.4 rule 5, K4, CX-2, T16)

| File | Covers |
|---|---|
| `approval/ApprovalBindingService.java` | `SHA-256(JCS(normalised material payload))` via core `CanonicalJson`/`Hashes`; decimals are normalised. Provides the submitted-hash check, approval recording (seat, acted-for, rule, power limit, task version), invalidation on material change with the changed fields (`APPROVAL_INVALIDATED`) and `requireValidApproval` before execution |

### Admin services (A08, AJ-01, B-11, EC-17, EC-20, R26, R64)

| File | Covers |
|---|---|
| `admin/PolicyAdminService.java` | Create, update and delete of ALLOW/DENY policies with conditions. Rejects a non-compilable list policy at deployment. `CHANGE_GRANT` is re-checked in the transaction (K3). **Four-eyes** with pending changes (AJ-01 step 2). Incremental `AUTH_EFFECTIVE` update. `GRANT_CHANGED`/`DENY_CHANGED` |
| `admin/RoleAdminService.java` | Roles as permission sets (deny rules allowed). Effective-dated bindings to users, groups or seats. **Tenant bootstrap with two administrators** (AJ-01 step 2) |
| `admin/TenantAdminService.java` | Tenant lifecycle (EC-17, AJ-01): create (platform admin), configure (languages, calendar, quotas R28, four-eyes), go-live checklist, activation blocked without evidence, suspend and resume, `requireActive` |
| `admin/WorkerBindingService.java` | R26 / K14: the worker principal is bound to tenant and topic (`WORK` on `WORKER_TOPIC`) |
| `admin/BulkOperationGuard.java` | R64: `BULK` permission, pre-count from the same predicate (capped), four-eyes above a threshold, one start per plan. R63 / EC-20: a guarded operator action needs a reason and is audited |
| `admin/SecurityDirectoryService.java` | Paged, tenant-scoped list/query services for the admin screens and the server bridge: org units (name and parent at a date), seats, postings and person delegations (acting postings), delegation-of-powers tables, policies and pending policy changes, roles and role bindings, groups and members, users, SoD exceptions. `READ` object check on the admin type (deny wins), tenant bound in SQL, filtering and paging in SQL per dialect, page ≤ 1,000 (R28), capped total (A-16) |
| `admin/ModuleDefaults.java` | Admin defaults: role templates per module (`resources-admin`/`-reader`, `booking-officer`, `queue-operator`, `roster-manager`, `case-*`, `programme-*`, `registry-*`, `compliance-officer`, `config-admin`, `ledger-*`, `billing-admin`, `field-agent`/`-reviewer`/`-admin`, `dispatch-operator`, `geo-*`). **Person-conditioned self-service grants** for the implicit role `authenticated`: own bookings (`requester_id`/`beneficiary_id`), allotments (`holder_id`/`beneficiary_id`), waitlist entries, duties (`RES_ROSTER_ASSIGNMENT.person_id`), roster membership and swap requests. The conditions compile to SQL, so lists show exactly the caller's rows. `install(...)` is explicit and idempotent |
| `crypto/AesGcmFieldEncryptor.java` (port `sangam-core/.../core/security/FieldEncryptor.java`) | Tenant-key encryption of sensitive values and JSON at rest (DPDP, X.2.3.8): AES-256-GCM, envelope `sgmenc:v1:<tenant>/k<n>:<base64url>`, AAD = tenant + key id + caller context (cross-tenant and cross-purpose copies fail), canonical JSON, lazy `reencrypt` after rotation; `403 DECRYPTION_DENIED`, `410 KEY_DESTROYED` |
| `crypto/TenantKeyStore.java`, `crypto/JdbcTenantKeyStore.java` | One data key per tenant, versioned key ids, `ACTIVE` / `DECRYPT_ONLY` / `DESTROYED` (crypto-shredding), rotation, keys stored only wrapped by a KEK (AES-GCM, AAD = key id), KEK rotation by `rewrapAll`, concurrent first use is safe |
| `crypto/MasterKeyProvider.java` | KEKs from base64 secrets, a PKCS#12 key store, or an ephemeral development key (refused on production by `SangamSecurity.newFieldEncryptor`); HSM-pluggable |
| `mask/FieldMaskService.java` | VI.4 rule 4 / K11: `READ_VARIABLE`/`READ_SENSITIVE` field masks, the same for lists and objects; `SENSITIVE_READ` audit. M-086: `maskAll` is set-based (one `PolicyCompiler.allowedIds` query per permission and 32 ids, same predicate as the object check); the jqwik property `FieldMaskSetEquivalencePropertiesTest` proves it equals the per-row result, audit included |
| `crypto/FieldReencryptionJob.java`, `crypto/ReencryptionSource.java` | M-085: resumable, tenant-scoped re-encryption to the active key after a rotation. One transaction per batch with the cursor (`SEC_REENCRYPT_PROGRESS`), compare-and-set writes, a dry run, metric callback, `FIELD_REENCRYPTION_COMPLETED`. Scheduled by `sangam-server` `FieldReencryptionWiring` (`sangam.security.reencryption.*`, off by default) |
| `breakglass/BreakGlassService.java`, `breakglass/BreakGlassAlarm.java` | M-084 (AJ-05 step 7): break-glass token classes from tenant config (`tokenClasses`, `breakGlass: true`), dual approval (requester and holder excluded), a time-boxed API token shown once, every use audited `BREAK_GLASS_USED` with `alarm=true` plus the alarm callback, and the review task (`review_status` PENDING). Off (404) without a configured class. REST: `sangam-server` `BreakGlassController` |
| `oidc/ClaimsProfile.java`, `oidc/ClaimsProfileMapper.java` | M-077 (R50, AJ-05 step 3): configurable person link (sub, verified e-mail with allowed domains, or a named person-id claim), IdP roles and groups on or off, multi-factor required or not. Presets `default` and `verified-email`; no country or IdP default in code |
| `saml/SamlProperties.java`, `saml/SamlAttributeMapper.java` | M-076: settings of the optional SAML SP adapter (off by default) and the assertion-to-identity mapping through the same claims profile (tenant from the registration, AuthnContextClassRef to `acr`) |

### Security audit (R42 audit part, X.2.3.2, X.2.3.3, X.4.0.3)

| File | Covers |
|---|---|
| `audit/SecurityEventType.java` | Normative event names (X.5.2) |
| `audit/SecurityAuditService.java` | Class-B events through core `AuditChain` in the caller's transaction. The root chain holds case events; the tenant security chain `sec:<tenant>` holds the rest. The envelope actor carries `principal_id`, `seat_id`, `acted_for_seat_id` ("acted for"), `on_behalf_of`, `auth_method`, `client_ip` and `policy_version`. Denial events (SL-132) go to the `sec:d:` denial chain of their chain in a separate leaf-pool transaction (`Options.denialTx`), so they survive the rollback; see integration note 6 |
| `audit/LoginEventBatcher.java` | Logins and login failures batched per 1 s into `LOGIN_BATCH` (X.2.3.3 rule 2) |

### Other files

| File | Covers |
|---|---|
| `SangamSecurity.java` | Wiring facade |
| `support/Sql.java` | JDBC/JSON helpers, DB clock (A-1), insert-if-absent per dialect |
| `src/main/resources/db/sangam-security-reference.sql` | The exact tables and columns this module uses (the integration contract, below) |

### External deny-only policy check (owner decision 166, M-083)

An optional remote policy decision point (OPA, a Cedar agent, or any service that reads the request below) can refuse a
command; it can never grant one. Off by default; nothing is called unless `sangam.security.external-policy.enabled=true`.

| File | Covers |
|---|---|
| `policy/external/ExternalPolicyCheck.java` | The port: one call per command, ALLOW_CONTINUE or DENY, or `ExternalPolicyUnavailable` |
| `policy/external/ExternalPolicyRequest.java` | Request `sangam.external-policy-request/1`: tenant, actor id, auth method, groups, command type, method, path template, path parameters (never the command body) |
| `policy/external/ExternalPolicyDecision.java` | ALLOW_CONTINUE or DENY; reason code and policy id kept only as safe tokens |
| `policy/external/ExternalPolicyCommands.java`, `ExternalPolicyRequirement.java` | Per command REQUIRED (unavailable fails closed), BEST_EFFORT (unavailable continues) or NONE (no call); unknown or ambiguous names stop the start-up |
| `policy/external/ExternalPolicyGate.java` | The verdict table; asserts no L2 lock is held before the remote call (decision 86) |
| `policy/external/HttpExternalPolicyCheck.java` | JDK HTTP client, no new library: POST `{"input": {...}}` (OPA envelope), reads `decision` ALLOW/DENY, `allow` true/false or an OPA boolean `result`; https only (http only outside production on request), no redirects, connect timeout plus an overall deadline, 64 KiB answer limit, optional bearer token from a file |

The server calls the gate in `org.sangam.server.bridge.ExternalPolicyFilter` (wiring `ExternalPolicyWiring`, keys
`sangam.security.external-policy.*`) after authentication and before the idempotency claim, the command transaction and
any lock. A DENY answers `403 POLICY_DENIED`, an unavailable service for a required command `503 POLICY_UNAVAILABLE`;
both are recorded as `AUTH_DENIED` on the tenant's security denial chain. Sangam's own endpoint and object checks run
afterwards as before, so an ALLOW never overrides them. The check is made once at command entry: a policy change
between that call and the command's own transaction is not seen by the running command (Sangam's own object checks are
still re-checked in the transaction, VI.4 rule 5).

Owner decision 200 widens the scope: besides the Sangam REST commands, the same filter checks every external-worker
call (the contract's `x-sangam-fenced` operations, command types `work.<action>`: `work.fetch-and-lock`,
`work.complete`, `work.fail`, `work.step`, ...) and every state-changing `/engine-rest` call (POST, PUT, PATCH,
DELETE; command type `engine-rest`, the request path sent as `pathTemplate`). All are checked at entry, outside any
transaction and lock scope (`org.sangam.server.bridge.ExternalPolicyTargets`); these names are valid in
`required-commands` and `best-effort-commands`, and `*` covers them.

### Unverified payments: explicit-only permissions (owner decision 141, RM-03)

`Permission.ACCEPT_UNVERIFIED` and `Permission.RESOLVE_UNVERIFIED` (ordinals 24 and 25, appended; both sensitive, so
re-checked without cache) are **explicit-only** (`Permission.explicitOnly()`):

- they are granted on the virtual type `BILLING` with the resource id `payment-channel:<channel>@<office>`
  (`org.sangam.security.billing.UnverifiedPaymentPermissions.resourceId`); the tenant is the grant row's tenant, and a
  counter is a condition attribute (`counterId`, with `tenantId`, `officeId`, `channel`);
- a grant on `'*'` (type or id) never confers them, in the object check and in its set form; denies on wildcards still
  apply. Policies and role rules granting them on `'*'`, or on a type that does not define them, are rejected at
  validation (`PolicyCompiler.validate(type, id, perms, condition, deny)`); the `billing-admin` template and every
  "full type" template leave them out;
- the system identity never holds them (packs, jobs and connectors); `UnverifiedPaymentPermissions.permits` also refuses
  API keys and an identity of another tenant, and audits a refusal as `AUTH_DENIED`.

**No `PAYMENT_CHANNEL` resource type.** The existing model expresses tenant (grant row), channel and office (concrete
resource id), counter (condition), deny-wins and the in-transaction re-check. The only missing rule was "a generic
billing or channel permission never implies it", which the explicit-only rule adds for every type; a separate type
would duplicate the channel scope and still need that rule. The server binds sangam-billing's
`UnverifiedPaymentAuthority` to this (`org.sangam.server.bridge.UnverifiedPaymentAuthorityBridge`), evaluating only the
request's resolved identity (`TenantContext`).

### Flowable gap-list items (`sangam/docs/records/FLOWABLE-INTO-OPERATON.md`) in this area

| Id | Status | Where |
|---|---|---|
| FC-42 IDM with privileges, privilege mappings and tokens | Done | `PersonService`, `GroupAdminService`, `PrivilegeService`, `ApiTokenService` |
| FC-33 callee tenant fallback | Done (security part: shared-definition fallback with tenant binding) | `SangamTenantIdProvider` |
| FC-31 instance links, R19-authorised | Descriptor done; the table is WS-PORTS'/WS-ENGINE's | `ResourceRegistry.INSTANCE_LINK` |
| A-17 LDAP + Spring Security | Done | `LdapDirectorySync`, `oidc/*`; the Spring Security adapters in sangam-server `org.sangam.security.spring` (M-141a) |
| B-11 fine-grained model for admin APIs | Done (virtual admin resource types, the same deny-wins engine) | `ResourceRegistry`, all `admin/*` |
| EC-17 multi-tenant administration | Done (service side; the A01 UI is WS-UI) | `TenantAdminService`, `RoleAdminService.bootstrapTenantAdmins` |
| EC-20 operator permissions + operator audit | Done | `BulkOperationGuard`, `Permission`, `SecurityAuditService` |
| D-14 no deny-wins in the built-in authorization | Done | `PolicyCompiler`; engine authorization off in `SangamSecurityEnginePlugin` |
| D-15 TaskQuery cannot take the predicate | Done (the builder); the ArchUnit rule belongs to sangam-server | `AuthorizedQueryBuilder` |
| D-36 candidate user AND groups | Done (the product predicate ORs all principal ids) | `PolicyCompiler` |
| D-55 slow count under authorization | Done (membership join, split wildcard branches, capped count); the benchmark is still to run | `PolicyCompiler`, `SecurityDialect.cappedCount` |

## Third-party dependencies added

| Dependency | Licence | Scope |
|---|---|---|
| nimbus-jose-jwt (Boot BOM) | Apache-2.0 | test (GovSsoSimulatorConformanceTest) |
| operaton-engine / operaton-engine-dmn 2.2.0 | Apache-2.0 | provided (supplied by sangam-engine) |
| jqwik 1.10.2 (version managed in the root pom) | EPL-2.0 | test (M-086 property test) |

Versions come from the Spring Boot 4.1.2 BOM imported by the parent (no BOM in this pom). Since M-141a (decisions 72-75) this jar has no Spring and no servlet dependency: the Spring Security adapters (resource server configuration, token filters, identity resolution filter, authentication token, client JWT verifier) are in the Spring host, sangam-server, package `org.sangam.security.spring`. `HostFrameworkBoundaryTest.securityJarStaysFrameworkFree` keeps it that way.

## Integration notes (for the integrator and WS-ENGINE)

1. **Table reconciliation.** `src/main/resources/db/sangam-security-reference.sql` is the contract this code uses. The
   sangam-persistence baseline differs. Please add a changeset, or tell me which names to switch to:
   - `AUTH_EFFECTIVE`: add `cond_key VARCHAR(64) NOT NULL` (`'-'` when `condition_id` is null) to the primary key, so
     that a conditional and an unconditional row of one key can coexist. Index `(tenant_id, resource_type, resource_id,
     principal_id, deny_flag)`.
   - `AUTH_MEMBERSHIP`: this code uses `(member_id → principal_id, source_id, valid_from, valid_to)` with a primary key
     that includes `source_id`. Persistence has `(principal_id → group_id, source, policy_version)`. Please adopt this
     module's shape, which covers user→group, user→role and user→seat edges.
   - `AUTH_GRANT` (persistence) corresponds to this module's `AUTH_GRANT_SOURCE` (key plus `source_id`). Please adopt
     `AUTH_GRANT_SOURCE`, or map `grant_id ↔ source_id`.
   - `AUTH_CONDITION`: this code uses `expression` (Condition JSON). Persistence has `source_expression`/`compiled_json`.
   - Org tables: `SEC_PERSON ↔ PERSON`, `SEC_ORG_UNIT + SEC_ORG_UNIT_PARENT ↔ ORG_UNIT (+ORG_UNIT_CLOSURE)`,
     `SEC_SEAT + SEC_SEAT_PLACEMENT ↔ SEAT`, `SEC_POSTING ↔ SEAT_ASSIGNMENT`, `SEC_LEAVE ↔ LEAVE_RECORD`,
     `SEC_POWER_TABLE ↔ DELEGATION_POWER_VERSION`, `SEC_TENANT ↔ SANGAM_TENANT`, plus the new
     `SEC_ORG_UNIT_NAME` (effective-dated renames). This module needs effective-dated
     unit parents and seat placements (AJ-02 step 3), `pool_flag`, `power_cap`, `expiry_notified` and the stored DMN
     XML and hash. A rename is mechanical: each table is used in one service.
   - New tables with no persistence counterpart: `SEC_POLICY`, `SEC_POLICY_CHANGE`, `SEC_ROLE`, `SEC_ROLE_BINDING`,
     `SEC_GROUP`, `SEC_GROUP_MEMBER`, `SEC_PRIVILEGE`, `SEC_PRIVILEGE_MAPPING`, `SEC_API_TOKEN`, `SEC_WORKER_BINDING`,
     `SEC_BULK_PLAN`, `SEC_FIELD_RULE`, `SEC_TENANT_CHECKLIST`, `SEC_ORG_LEVEL`, `SEC_CASE_ACTOR`,
     `SEC_SOD_EXCEPTION`, `SEC_APPROVAL`.
   - SQL Server: `SEC_PERSON_SUBJECT` must be a filtered unique index (`WHERE external_subject IS NOT NULL`).
   - `AUTH_GRANT_SOURCE` key: the primary key ends in `source_key` (hex SHA-256 of `source_id`, written by
     `AuthEffectiveMaintainer`), not `source_id`. Worst-case key: SQL Server 1,534 bytes (limit 1,700), MySQL 3,066
     bytes (limit 3,072). `source_id` stays as a plain column for lookups through `AUTH_GRANT_SOURCE_SRC`.
   - Go-live checklist (ADR-D-20): core items in code; regime items come from `goLiveEvidence` in the jurisdiction
     packs named by tenant config `jurisdictions`, plus config `goLiveRequiredEvidence`. Whoever enables a jurisdiction
     must also write it to tenant config, otherwise its items are not required. Use `TenantAdminService.missingEvidence`.
   - Resource type `document` maps to `AI_KB_CHUNK.document_id` (scope `kb:<kb_key>`), with no default grant.
     `KnowledgeBaseService`'s default predicate ignores `*`-type rows and `kb:` scopes; bind its `visibility` to `PolicyCompiler`.
2. **Audit chain id length.** Case events use the case id (the business key, else the process instance id) as
   `AUDIT_EVENT.chain_id`, which is `VARCHAR(64)`. Business keys must be ≤ 64 characters, or widen the column.
3. **Engine plugin.** Add `new SangamSecurityEnginePlugin(sangamSecurity)` next to `SangamProcessEnginePlugin`. It
   sets `authorizationEnabled=false` and the `TenantIdProvider`.
4. **Identity binding outside HTTP.** The job executor and the worker API must bind an identity with
   `TenantContext.runAs(...)`: `SecurityIdentity.system(tenant, onBehalfOf)` for jobs (VI.4 rule 7), and the
   API-token identity for workers (R26). This belongs in a sangam-engine command interceptor.
5. **Spring beans** that a Spring host must provide for `org.sangam.security.spring.SangamResourceServerConfiguration` (sangam-server; not imported by the standalone server, which adds the filters to its own chain): `SangamSecurity`,
   `Transactions` (product DataSource) and `SecurityProperties` bound with `@ConfigurationProperties("sangam.security")`.
   For production, set `sangam.security.production=true`; the dev token filter then refuses to start.
6. **Denial audit transaction (SL-132).** Pass a leaf-pool `Transactions` as `Options.denialTx`; denials go to the
   `sec:d:` denial chain of their chain and never wait for the caller's `ROOT_SEQ` row. `SecurityAuditService.denial`
   writes `AUTH_DENIED`, `SOD_VIOLATION_BLOCKED` and `POWER_LIMIT_EXCEEDED` to `denialChain(tenant, chainId)` (`sec:d:`
   plus the first 32 hex digits of SHA-256(tenant NUL chainId), where `chainId` is the root id or `sec:<tenant>`) and
   commits them on their own through `denialTx`, so they survive the command rollback. Only denial writers append to a
   denial chain, so its `ROOT_SEQ` row is never held by a caller. The pool must be a leaf pool (its connections are never
   held while another connection is requested); a second connection from the caller's pool is the SL-43 pool deadlock.
   With `denialTx` null the denial is written in-line and rolls back with the caller. The REST adapters use the same
   chain id (`org.sangam.rest.adapter.jdbc.DenialAudit`), and the audit timeline, export and verify read the denial
   chains with their chains. In the server, `SecurityWiring` passes the leaf pool `sangam-security-audit`
   (`SecurityAuditPoolWiring`, `sangam.security.denial-audit.pool.maximum-size`, default 2) as `denialTx`; only when
   that pool is absent does `SecurityWiring.denialTransactions` fall back to the product transactions.
7. **ArchUnit rule** (sangam-server, R19 / D-15): forbid `TaskService.createTaskQuery`, `HistoryService.create*Query`
   and native engine queries for protected types outside `AuthorizedQueryBuilder`.
8. **H2** must run with `MODE=PostgreSQL` (the core `AuditChain` uses `ON CONFLICT DO NOTHING`).
9. **REST (WS-REST).** Map `SangamSecurityException.httpStatus()/code()` to problem+json. Map
   `AuthorizedQueryBuilder.CappedCount` to `X-Total-Count` / `X-Total-Count-Capped`. The task complete handler calls
   `ApprovalBindingService.verifySubmittedHash`, then `DelegationOfPowersService.require`, then `recordApproval`. The
   case-update path calls `onMaterialChange`.
10. **Packs (WS-PACKS).** User tasks declare `sangam.seat` / `sangam.level` + `sangam.designation` and `sangam.duty`
    as `operaton:properties`. Power tables follow the `DmnPowerRows` contract (inputs `designation`, `levelType`,
    `caseType` (the manifest's rule case type, decision 222), `amount`, …; outputs `maxAmount`, `allowed`, `ruleRef`).
11. **Server bridge.** `SecurityAdminBridge` / `SecurityIdentityAdminBridge` can drop their direct SQL:
    use `security.directory().orgUnits/seats/postings/powerTables/policies/roles/groups/users/sodExceptions(...)`
    (pass `offset`, `limit`, the count cap; map `Page.total().capped()` to `X-Total-Count-Capped`), and
    `security.orgUnits().renameUnit(...)` for `updateOrgUnit` name changes. Person delegations = `postings` with
    `actingOnly=true`.
12. **Module resource types.** The modules' constants must equal the names in `ModuleResourceTypes`. Install the
    defaults per tenant with `security.defaults().install(c, admin, true)` (e.g. from AJ-01 onboarding). Every resolved
    person carries the implicit role `authenticated`. For config, ledger, billing, field, dispatch and geo, endpoints
    use object checks on the virtual types; field mapping: `field.capture` → START/MODIFY, `field.review` → APPROVE,
    `field.admin` → ADMIN on `FIELD`; `dispatch.operate` → START/MODIFY/ASSIGN/CANCEL on `DISPATCH`.
13. **Field encryption (WS-REST form drafts and others).** Build once:
    `FieldEncryptor enc = security.newFieldEncryptor(Transactions.of(ds), MasterKeyProvider.ofBase64("kek1", secrets), production)`.
    Encrypt with a context naming the purpose and row, e.g. `enc.encryptJson(tenant, "form-draft:" + draftId, values)`.
    Needs table `SEC_TENANT_KEY` (reference DDL). Encrypted columns must hold the text envelope (TEXT/CLOB).
14. **Dev tokens.** The person must exist with `external_subject = person`, or the tenant must be in
    `jit-provisioning-tenants`.

## Known gaps (honest list)

- **No tests yet** (phase rule). Planned: T13–T17, T37 (list/count/object property test per dialect), T38/D-55
  benchmark, and an H2 unit suite for the compiler and the services.
- SCIM 2.0 provisioning (R67, Stage D) is not implemented.
- SAML 2.0 (M-076, decisions 52, 73 and 99): in 0.1.0 a SAML IdP is connected only through Keycloak brokering
  (`deploy/compose/keycloak/README.md`). There is no server-side SAML session; `sangam.security.saml.enabled=true`
  refuses the start (`sangam-server` `SamlWiring`). The plain settings and assertion mapping (`saml/`) are kept for a
  later release and are not called in 0.1.0.
- The claims profile (M-077) is applied by the server's bearer-token path (`JwtPrincipalConverter`) when
  `sangam.security.claims-profile.*` is set, through `ClaimsProfile.linkKey` and `KeycloakClaimsMapper.mfaShown`;
  without it the token path links by its own claim settings, as before.
  IdP records (protocol, issuer, metadata URL, attribute mapping, MFA, session timeout) are managed outside this module,
  by sangam-rest `JdbcIdentityAdminAdapter`: create and update audit `IDP_CONFIGURED`, and any change puts the record
  back to DRAFT until it is tested again. The connection test covers OIDC discovery only; other protocols get a
  failed test result.
- Merkle checkpoints of the audit chain (X.2.3.4 rule 4) are a core `AuditChain` gap, not handled here.
- Field re-encryption (M-085) covers every store that registers a `ReencryptionSource` bean; the REST document
  (`REST_DOCUMENT.body`) and form draft (`FORM_DRAFT.data_ciphertext`) sources are in sangam-server
  `RestStoreReencryptionSources`. Documents under the global tenant `*` move only when `*` is in
  `sangam.security.reencryption.tenants`. Secret storage belongs to its own module. Idempotency responses are not swept
  (owner decision 30); they expire.
- R82 strategies are not in this module, which provides seat resolution and candidate exclusion only. The strategies
  (ROUND_ROBIN, LEAST_LOADED, SKILLS, PRIORITY) are the pack delegate `AssignmentStrategyDelegate` in sangam-packs.
