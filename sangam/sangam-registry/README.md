# sangam-registry (M4: registered-object kernel)

Owner: GENERIC-REGISTRY workstream. Package `org.sangam.registry`. Depends on `sangam-core` only, plus Jackson,
spring-web (annotations) and the Operaton delegate API (`provided`). No new third-party dependency. Ed25519 and
HMAC-SHA256 come from the JDK.

There are no country, department or sector names in the code (SANGAM.md X.0e). Types, lifecycles, validity rules,
number formats, register columns, identifier types, match rules, asset classes and custody receipt series are all
data that packs supply (ADR-D-20).

## Coverage, file by file

| File | Capability / requirement |
|---|---|
| `RegistrySchema.java` | DDL for all 33 `REG_*` tables (dialect tokens; four Tier-1 dialects, A-15). The integrator copies it into Liquibase |
| `RegistryPorts.java` | Ports with standalone defaults: events (outbox, VI.2), signer, documents (C11), publish (document locker), consent (R53, compliance module), privacy masks (R19), identifier vault (R50), external matcher, visibility (VI.4), DMN decisions, system of record (facade mode, D-22), metrics |
| `RegistryModule.java` | Wiring of every service; `install()` for the BPMN delegates |
| `RegistryContext.java`, `RegistryException.java` | Tenant from identity (VI.4 rule 6), actor, on-behalf-of, purpose, operationId (R36); error codes mapped to RFC 9457 |
| `kernel/ObjectTypeDefinition`, `ObjectSchema`, `FieldDefinition`, `Lifecycle`, `ValidityRule`, `ObjectKind` | M4 kernel: configured types (R78 data objects), field validation, sensitive/public/immutable fields, state machine with reason, countersign (maker-checker, SoD) and `until`, validity/renewal window/grace/alignment, kinds RECORD / INSTRUMENT / ASSET / CUSTODY_ITEM |
| `kernel/ObjectTypeService` | Versioned type catalogue: effective-dated versions; objects keep their version |
| `kernel/RegisteredObjectService`, `RegisteredObject` | Create/update/transition, row lock plus optimistic version, `REG_OBJECT_VERSION` history, as-of reads, audit chain per object, visibility predicate, expiry/renewal/reinstatement queries, facade mirror (N-common rule) |
| `numbering/*` | N09 numbering. Gap-free series: one counter row per series, scope and period, locked per dialect; voids carry a reason; period close; gap report; lock-wait metric. Unique-only series: lock-free block allocation. Formats: `{seq:W}`, `{fy}`, `{scope}`, `{attr:x}`, `{check}` (LUHN / MOD97) |
| `register/*` | N09 statutory register books: append-only; countersigned correction entries; book hash chain plus core AuditChain (X.2.3.3); period close with close digest; signed certified extracts with a verification code and a document hook (PDF/A, R61) |
| `instrument/InstrumentService` | N06: issue, activate, renewal due, renew (window, grace, deemed renewal via DMN), suspend with end date, reinstate, revoke (countersign if configured), surrender, expire, holder transfer, transfer by re-issue, amendment endorsements, duplicate, document generation and publish hooks, bulk sweeps |
| `instrument/VerificationService` | N06 public verification: hashed checksummed codes; signed minimal QR (`SGV1.`, no personal data); online status check; masked public view |
| `party/*` | N14: persons and organisations; configurable identifier types (pattern, normalisation, LUHN/VERHOEFF/MOD11/MOD97, unique, sensitive → vault + keyed hash); relationships, addresses, household roll-up, eligibility snapshots (VI.4 rule 5); blocking with deterministic and probabilistic (Fellegi-Sunter style) matching, plus an external matcher; review queue; merge with survivorship and LIFO unmerge; audit trail; consent and masking through ports |
| `asset/*` | N03: asset classes with inheritance; hierarchy; location/custodian moves; depreciation (straight line, declining balance, units of production, sum of years; conventions); meters and readings with rollover; time/meter/condition maintenance plans; idempotent due work-order generation; work-order state machine (calibration findings, parts, cost, downtime); MTBF/MTTR/availability |
| `custody/*` | N18: tracked items; per-item hash-chained custody events plus AuditChain; two-step transfer and handover with both signatures; seal, break and reseal; inspection; nesting containers with cascade; receipts (series and document); disposal order and execution by different persons; gap check |
| `bpmn/RegistryDelegate`, `PartyDelegate`, `AssetDelegate`, `CustodyDelegate`, `DelegateSupport` | BPMN building blocks. They run on the engine command's connection (same transaction). Registry errors become BPMN errors `REGISTRY_<CODE>`; operationId = UUIDv5 (T35) |
| `rest/*` | REST under `/api/v1/registry` (section below); problem+json; `Idempotency-Key` used as the operationId; `If-Match` / `expectedVersion` for optimistic concurrency |
| `support/*` | JSON/maps helpers, DB clock (A-1), paging (capped at 1,000, R28), verification codes and check digits, Ed25519 signer, HMAC vault, audit plus event writer |

## REST endpoints

Base `/api/v1/registry`.

### Kernel

- Types: `POST /types`, `GET /types?kind`, `GET /types/{typeKey}`, `GET /types/{typeKey}/versions/{v}`, `POST /types/{typeKey}/versions/{v}/retire`
- Objects: `POST /objects`, `GET /objects?typeKey&kind&state&holderPartyId&parentId&number&subjectRef&page&size`, `GET /objects/{id}`, `GET /objects/by-number/{number}?typeKey`, `PATCH /objects/{id}`, `GET /objects/{id}/transitions`, `POST /objects/{id}/transitions/{name}`, `GET /objects/{id}/versions`, `GET /objects/{id}/as-of?at`, `GET /objects/{id}/audit/verify`, `POST /objects/mirror`

### Numbering

- Series: `PUT /number-series/{key}`, `GET /number-series`, `GET /number-series/{key}`
- Numbers: `POST /number-series/{key}/allocate`, `POST /number-series/{key}/void`, `POST /number-series/{key}/mark-used`, `POST /number-series/{key}/close-period`
- Reports: `GET /number-series/{key}/gaps?scope&period`, `GET /number-series/{key}/allocations?scope&period&page&size`

### Registers

- Definitions: `PUT /registers/{key}`, `GET /registers`, `GET /registers/{key}`
- Entries: `POST /registers/{key}/entries`, `GET /registers/books/{bookId}/entries?from&count`, `GET /registers/books/{bookId}/entries/{n}`, `POST /registers/books/{bookId}/entries/{n}/corrections`, `GET /registers/entries?refType&refId`
- Books: `GET /registers/{key}/books?scope`, `GET /registers/books/{bookId}`, `POST /registers/books/{bookId}/close`, `GET /registers/books/{bookId}/verify`
- Extracts: `POST /registers/books/{bookId}/extracts`, `GET /registers/extracts/{id}`, `GET /registers/public/{tenant}/extracts/verify?code` (public)

### Instruments

- Issue: `POST /instruments`
- Lifecycle: `POST /instruments/{id}/renew`, `POST /instruments/{id}/deemed-renewal`, `POST /instruments/{id}/suspend`, `POST /instruments/{id}/reinstate`, `POST /instruments/{id}/revoke`, `POST /instruments/{id}/surrender`, `POST /instruments/{id}/expire`, `POST /instruments/{id}/transitions/{name}`
- Transfer and amendment: `POST /instruments/{id}/transfer-holder`, `POST /instruments/{id}/transfer-reissue`, `POST /instruments/{id}/amend`, `POST /instruments/{id}/duplicate`
- Endorsements and documents: `POST /instruments/{id}/endorsements`, `GET /instruments/{id}/endorsements`, `GET /instruments/{id}/documents`, `POST /instruments/{id}/documents/regenerate?purpose`
- Jobs: `POST /instruments/jobs/expire`, `POST /instruments/jobs/renewal-due`, `POST /instruments/jobs/reinstate`
- Public: `GET /instruments/public/{tenant}/verify?code`, `POST /instruments/public/verify-qr`

### Parties

Base `/parties`.

- Parties: `POST /parties`, `GET /parties?kind&status&name&idType&idValue&page&size`, `GET /parties/{id}`, `PATCH /parties/{id}`, `POST /parties/{id}/deactivate`, `POST /parties/{id}/reactivate`, `GET /parties/{id}/resolve`, `GET /parties/{id}/audit/verify`
- Identifiers: `GET /parties/{id}/identifiers`, `POST /parties/{id}/identifiers`, `POST /parties/identifiers/{iid}/retire`, `POST /parties/identifiers/{iid}/reveal`
- Relationships and addresses: `GET /parties/{id}/relationships`, `POST /parties/{id}/relationships`, `POST /parties/relationships/{rid}/end`, `GET /parties/{id}/addresses`, `POST /parties/{id}/addresses`, `POST /parties/addresses/{aid}/retire`
- Household and snapshots: `GET /parties/{id}/household?types&depth`, `POST /parties/{id}/snapshots`, `GET /parties/snapshots?reference&partyId`, `GET /parties/snapshots/{sid}`, `GET /parties/snapshots/{sid}/verify`
- Matching: `POST /parties/match`, `POST /parties/{id}/match?record`, `GET /parties/candidates?status&partyId`, `POST /parties/candidates/{cid}/confirm`, `POST /parties/candidates/{cid}/reject`
- Merge: `POST /parties/merge`, `POST /parties/merges/{mid}/unmerge`, `GET /parties/{id}/merges`
- Configuration: `GET|PUT /parties/config/identifier-types`, `DELETE /parties/config/identifier-types/{key}`, `GET|PUT /parties/config/match`

### Assets

Base `/assets`.

- Classes: `POST|GET /assets/classes`, `GET /assets/classes/{key}?resolved`
- Assets: `POST|GET /assets`, `GET /assets/{id}`, `GET /assets/by-tag/{tag}`, `PATCH /assets/{id}`
- Location and hierarchy: `POST /assets/{id}/move`, `GET /assets/{id}/movements`, `POST /assets/{id}/reparent`, `GET /assets/{id}/children|ancestors|subtree`, `POST /assets/{id}/dispose`
- Depreciation: `GET /assets/{id}/depreciation`, `GET /assets/{id}/book-value?asOf`
- Meters: `GET|POST /assets/{id}/meters`, `POST|GET /assets/{id}/meters/{type}/readings`, `POST /assets/readings/{rid}/correct|reject`
- Plans: `GET|POST /assets/{id}/plans`, `PATCH /assets/plans/{pid}`, `POST /assets/plans/{pid}/status|trigger`
- Work orders: `POST|GET /assets/work-orders`, `GET /assets/work-orders/{wid}`, `POST /assets/work-orders/generate-due`, `POST /assets/work-orders/{wid}/assign|start|hold|resume|complete|close|cancel`
- Reliability: `GET /assets/{id}/reliability?from&to`

### Custody

Base `/custody`.

- Items: `POST|GET /custody/items`, `GET /custody/items/{id}`, `GET /custody/items/by-label/{label}`, `GET /custody/items/{id}/events`, `GET /custody/items/{id}/verify`
- Transfers: `GET|POST /custody/items/{id}/transfers`, `POST /custody/items/{id}/handover`, `GET /custody/transfers/{tid}`, `GET /custody/transfers/pending?toCustodian`, `POST /custody/transfers/{tid}/accept|reject|cancel`
- Seals, location and containers: `POST /custody/items/{id}/seal|break-seal|inspect|location|container|take-out`
- Disposal: `GET|POST /custody/items/{id}/disposal-orders`, `GET /custody/disposal-orders/{oid}`, `POST /custody/disposal-orders/{oid}/execute|cancel`
- Gap check: `GET /custody/gap-check?inTransitHours&staleDays&limit`

## Integration notes (for the integrator)

1. **Schema.** Copy `RegistrySchema.TEMPLATES` into the sangam-persistence Liquibase changelogs (four dialects). The
   registry also writes the core `AUDIT_EVENT`, `AUDIT_PAYLOAD` and `ROOT_SEQ` tables.
2. **Wiring.** In sangam-server:
   - Build `new RegistryModule(dialect, ports, Transactions.of(plainPool)).install()`.
   - Expose its services as beans.
   - Register the controllers in `rest/` and `RegistryRestSupport` (with `Transactions.of(engine)` or the pool, and a `CallerResolver` over `SecurityIdentity`).
   - Register `RegistryExceptionHandler`.
   - Public verification endpoints (`/instruments/public/**`, `/registers/public/**`) must be permitted without authentication and rate-limited.
3. **Ports to bind:**
   - `EventPort` → core `Outbox` (same transaction).
   - `SignerPort` → the tenant key store, an HSM or the integration module's eSign connector. Without a binding, the default is an ephemeral Ed25519 key, so signatures do not survive a restart.
   - `DocumentPort` → flowable-ports `OutputTemplateRenderer` and `ContentService`.
   - `PublishPort` → the integration module's document-locker push connector, idempotent by object id.
   - `ConsentPort` and `PrivacyPort` → sangam-compliance and the security `FieldMaskService` through an adapter. The registry never imports compliance.
   - `IdentifierVault` → the vault service. The default HMAC pepper is random per start, which is development only.
   - `VisibilityPort` → security `AuthorizedQueryBuilder`. Resource types are `registry-object`, `registry-party`, plus the asset and custody aliases. The public actor `system:public-verification` must see instruments for verification.
   - `DecisionPort` → the engine's DMN service.
   - `SystemOfRecordPort` → connectors, for facade mode (type `extensions.systemOfRecord = EXTERNAL`).
   - `MetricsPort` → Micrometer.
4. **Delegates.** Use `operaton:class="org.sangam.registry.bpmn.RegistryDelegate"` (also `PartyDelegate`, `AssetDelegate`, `CustodyDelegate`) with field `op`. Timer-started processes with `EXPIRE_DUE`, `RENEWAL_DUE`, `REINSTATE_DUE` and `GENERATE_DUE` run the sweeps.
5. **Lock order.**
   - Register: book row, then counter, then audit root.
   - Objects: object row, then audit root.
   - Gap-free numbers hold their counter lock until commit, so callers allocate late in the transaction.
6. **Seed data.** Party identifier types and the match configuration have two layers:
   - The pack seed (`defineIdentifierTypes` / `defineMatchConfig` at start-up) is the default.
   - Administrator changes (`PUT /parties/config/*`, `DELETE /parties/config/identifier-types/{key}`) are stored in `REG_PARTY_CONFIG`. They win over the seed and every node re-reads them when the table changes.

   Types, series, registers and asset classes are stored in tables.
8. **Start-up guard.** `RegistryModule` refuses to start on a profile outside `dev`, `h2`, `test`, `local` while the ephemeral Ed25519 signer or the ephemeral HMAC vault is bound. Pass Spring's active profiles to the four-argument constructor, and configure `Ed25519Signer.fromBase64(...)` (or a key-store / HSM / eSign signer) and `HmacIdentifierVault.fromBase64(pepper)` (or a vault service). A configured HMAC vault holds no clear values; a sensitive type that needs revealable values requires a real vault service.
9. **Visibility in de-duplication.**
   - Matches are returned only inside the caller's visibility predicate.
   - Cross-boundary matches are queued as REVIEW candidates flagged `crossBoundary`. They are listed and resolvable only by a reviewer who sees both parties.
   - A unique identifier already held by a hidden party is stored as `HELD_FOR_REVIEW`, without naming the holder.
10. **Sweeps.** Expiry, renewal-due and reinstatement sweeps take their states from each type version's lifecycle: the states from which `extensions.<purpose>Transition` is allowed. The defaults are `expire`, `renewalDue` and `reinstate`.
7. **Nulls.** `Jdbc.bind` uses `Types.NULL` for nulls, the same as core. If the Oracle driver rejects it, the fix belongs in core.

## Not run yet

Following the phase rule, only compilation was checked (`mvnw -pl sangam-registry -am install -DskipTests`). There
are no tests yet.
