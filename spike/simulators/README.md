# simulators

Government-system simulators and the Testcontainers database harness for the Stage B spike (SANGAM.md Part XI). They follow ADR-D-13 (X.0b): every government integration is built from public detail (IX.7d) and gets a simulator. All data is synthetic. Wherever the real formats are still ON-REQUEST, the wire formats below are **this module's own design**. When the real specifications arrive, only the adapter mapping changes.

Build, from `spike/`:

```
.\mvnw.cmd -q -pl simulators verify
```

This needs JDK 21 and Docker for the PostgreSQL test. `product-api` must already be installed in `~/.m2`.

## Common: faults and the effects ledger

Every simulator has a `faults()` (`FaultInjector`) and a `ledger()` (`EffectsLedger`).

| Fault | Effect | Purpose |
|---|---|---|
| `Fault.delay(d)` | Waits `d` before answering. The request is still processed | Timeouts, slow partner |
| `Fault.dropResponse()` | Processes the request (the effect happens), then drops the answer. HTTP closes the connection with no status line; PFMS writes no status file | Lost response, `UNKNOWN_OUTCOME` (AC-03) |
| `Fault.duplicate()` | Delivers the request to the logic twice. The caller sees the first answer | Network/transport duplicate |
| `Fault.error(status)` | Answers `status` **without** processing | Partner error, no effect |

- `once(f)` is consumed by the next matching request. `times(n, f)` queues `n` of them. `always(f)` stays until `clear()`.
- `f.onRoute("/path")` restricts a fault to one route; the route name is the HTTP path, or `payment-file` for PFMS.
- The ledger records only the effects that were **actually executed**, as `(type, key, attributes)`.
- `ledger().count(type, key)`, `duplicates(type)` and `hasDuplicates(type)` let an experiment assert "no duplicate payment".

The HTTP simulators extend `SimHttpServer`: a JDK `com.sun.net.httpserver.HttpServer` on `127.0.0.1` with a free port and virtual threads. Use `new XxxSimulator().start()`, then `uri(path)`, then `close()`.

## 1. `IfmsJitSimulator` (Odisha IFMS JIT)

The shape follows the public DIGIT IFMS-adapter docs (IX.7d).

| Call | Request | Response |
|---|---|---|
| `POST /webservices/jitfs/v1.0/authenticate` | `{"clientId","clientSecret"}` | `{"status":"SUCCESS","authToken","expiresIn"}`, or 401 |
| `POST /webservices/jitfs/v1.0/get-jit-service-data` + `Authorization: Bearer <token>` | `{"serviceCode":"PI","params":{"billId","amount","operationId","beneficiary":{"name","accountNo","ifsc"}}}` | `{"serviceCode":"PI","status":"ACCEPTED","duplicate":bool,"operationId","paymentRef"}` |
| same | `{"serviceCode":"PD","params":{"operationId"}}` | `{"status":"SUCCESS"\|"FAILED"\|"NOT_FOUND","payments":[...]}` |
| same | `{"serviceCode":"FD","params":{"sinceHours":72}}` | `{"failures":[...]}` |

Errors look like `{"status":"ERROR","errorCode","message"}`. The codes are `INVALID_CREDENTIALS`, `TOKEN_INVALID_OR_EXPIRED`, `MISSING_FIELD`, `INVALID_AMOUNT`, `OPERATION_ID_REUSED` (409), `PD_NOT_SUPPORTED` and `UNKNOWN_SERVICE_CODE`. VA is not simulated.

`capabilityClass(...)` sets the A-2/A-8 class:
- `A_IDEMPOTENT_AND_LOOKUP` (the default): a duplicate PI with the same operationId returns `duplicate:true` and pays nothing more. The same operationId with a different payload gets 409.
- `B_LOOKUP_ONLY`: a duplicate PI pays again, and PD works.
- `C_NEITHER`: a duplicate PI pays again, and PD returns `PD_NOT_SUPPORTED`. This is idempotency OFF, for class (c).

Other settings:
- `failAccount(acc)`: payments to `acc` fail (`ACCOUNT_INVALID`) and appear in FD.
- `clock(...)` and `tokenTtl(...)` test token expiry.

Ledger types: `PAYMENT` and `PAYMENT_FAILED`, keyed by operationId.

## 2. `PfmsSftpSimulator` (PFMS file exchange)

- It uses `FileTransport`, with `LocalDirectoryTransport(root)` as the local implementation. A real SFTP client can implement the same interface later.
- The logical directories under the root are `outbound` (the product writes here), `inbound` (status files), `processed` and `rejected`.
- `start()` polls `outbound` every `pollInterval`. Tests can call `pollOnce()` instead.
- The status file is written after `statusDelay`.

**File format** (CSV, UTF-8, no quoting; fields must not contain commas; amounts have 2 decimals). The codec is `PfmsFiles`:

```
PAY_<ssid>_<batchId>.csv           product -> outbound
H,<ssid>,<schemeCode>,<batchId>,<recordCount>,<totalAmount>
D,<operationId>,<billId>,<beneficiaryName>,<accountNo>,<ifsc>,<amount>

STS_<batchId>_<seq>.csv            simulator -> inbound   (seq = 1, 2, ... per batch)
H,<batchId>,<paymentFileName>,ACCEPTED|REJECTED,<reason>
D,<operationId>,<pfmsRef>,SUCCESS|FAILED,<reason>
```

File reasons are `FORMAT_ERROR: ...` (including `COUNT_MISMATCH` and `TOTAL_MISMATCH`), `DUPLICATE_BATCH` and `SIM_ERROR_<status>`. Line reasons are `ACCOUNT_INVALID` and `DUPLICATE_OPERATION_ID`.

With `idempotent(true)` (the default), a repeated batchId is rejected and an operationId is paid at most once. `idempotent(false)` pays again.

Faults:

| Fault | Behaviour |
|---|---|
| DELAY | Adds to the status delay |
| DROP | Pays, but writes no status file |
| DUPLICATE | Processes the file twice, giving two status files |
| ERROR | Rejects the file unprocessed |

Ledger types: `PAYMENT` and `PAYMENT_FAILED`, keyed by operationId.

## 3. `EsignSimulator` (eSign API v2.1 shaped)

`POST /esign/2.1/signdoc` takes:

```
<Esign ver="2.1" txn="..." aspId="..." ...><Docs><InputHash id="1" hashAlgorithm="SHA256">hex</InputHash></Docs><Signature/></Esign>
```

and returns:

```
<EsignResp ver="2.1" status="1" txn ...><UserX509Certificate/><Signatures><DocSignature id="1">base64</DocSignature></Signatures></EsignResp>
```

On error it returns `status="0"` with `errCode` `SIM-E01`…`SIM-E06`. These are simulator codes, not real ESP codes.

- **The signature is fake:** Base64(HMAC-SHA256(per-instance key, `"SHA256:" + hash`)).
  - It binds to the hash: a different hash gives a different signature, and the same hash gives the same signature.
  - `verify(hash, sig)` checks it.
- `GET /esign/2.1/status?txn=` returns the last response for a txn. This is the outcome lookup for a lost response.
- The ASP request signature is not checked.
- Parsing refuses DOCTYPE and external entities.
- Ledger type: `SIGN`, keyed by txn.

## 4. `DigiLockerPushSimulator`

`POST /digilocker/issuer/push-uri` takes:

```
<PushUriRequest txn ...><UriDetails><DocType/><DigiLockerId/><URI/></UriDetails></PushUriRequest>
```

and returns `<PushUriResponse status="1|0" duplicate="true|false" errCode errMsg/>`. This approximates Issuer Push v1.7.2.

It is **idempotent by URI**:
- The same URI with the same holder and doc type returns `duplicate="true"` and records no new effect.
- The same URI with a different holder returns 409 `URI_CONFLICT`.

Ledger type: `PUSH`, keyed by URI.

## 5. `DltSmsSimulator` (TRAI DLT scrubbing)

`POST /dlt/v1/sms` takes `{"header","templateId","mobile","message"}`. It returns 200 `{"status":"ACCEPTED","messageId"}`, or 400 `{"status":"REJECTED","reason"}`.

It is configured with:
- `registerTemplate(id, header, body)`: `{#var#}` in the body matches up to 30 characters;
- `whitelistUrl(fixedPrefix)`.

Checks, in order:

| Check | Reason on failure |
|---|---|
| The template is registered | `TEMPLATE_NOT_REGISTERED` |
| The header is the template's header, optionally with the `-G` suffix | `HEADER_MISMATCH` |
| The mobile number is a valid Indian mobile | `INVALID_MOBILE` |
| The content matches the template | `CONTENT_MISMATCH` |
| Every URL starts with a whitelisted fixed part | `URL_NOT_WHITELISTED` |

The PE→TM chain is not modelled. Ledger type: `SMS_SENT`, keyed by messageId. SMS is not idempotent, so DUPLICATE sends twice.

## 6. IdP (Parichay stand-in)

See `IdpNote.md`. `KeycloakIdpTest` runs only with `SPIKE_IDP=true`.

## Database harness: `org.sangam.spike.sim.db.Databases`

```java
try (Databases.Running db = Databases.start("postgres")) {   // or "oracle", "sqlserver"
    DataSource ds = db.dataSource();   // HikariCP, max 10
    String url = db.jdbcUrl();         // jdbc:postgresql://...
    String dialect = db.dialect();     // postgresql | oracle | sqlserver
    String ping = db.selectOne();      // "select 1" / "select 1 from dual"
}
```

| Name | Image |
|---|---|
| `postgres` | `postgres:17` |
| `oracle` | `gvenzl/oracle-free:23-slim-faststart` |
| `sqlserver` | `mcr.microsoft.com/mssql/server:2022-latest`. The EULA is accepted via `acceptLicense()`, for dev/test only |

- **One database runs at a time.** `start` throws `IllegalStateException` while another is open, because the machine has 32 GB.
- Containers start only when `start` is called.
- In `DatabasesTest`, only PostgreSQL runs by default. The Oracle and SQL Server tests run only with `SPIKE_DB_ALL=true`.

## Licences (SANGAM.md X.3a)

| Dependency | Licence | Scope |
|---|---|---|
| Testcontainers `postgresql`, `oracle-free`, `mssqlserver` 1.21.4 | MIT | compile |
| HikariCP, Jackson | Apache-2.0 | compile |
| slf4j-api / slf4j-simple | MIT | compile / test |
| PostgreSQL JDBC (`org.postgresql:postgresql`) | BSD-2-Clause (permissive) | compile; it is the default database |
| **Oracle JDBC** `com.oracle.database.jdbc:ojdbc11` 23.8.0.25.04 | Oracle Free Use Terms | **`provided`** |
| SQL Server JDBC `com.microsoft.sqlserver:mssql-jdbc` 12.10.1.jre11 | MIT | **`provided`** (XI.5: fetched at build time, not shipped) |
| JUnit 5 | EPL-2.0 | test |

**How the drivers are kept out of every build (LIC-2):**
- Maven downloads `ojdbc11` and `mssql-jdbc` at build time.
- They are on this module's compile and test classpath, so the harness and its tests work.
- `provided` is **not transitive** and is never packaged, so nothing that depends on `simulators` inherits them.

**Consequence for consumers (e.g. `experiments`):**
- To run Oracle or SQL Server there, declare the driver in **that** module with `<scope>test</scope>` (or `provided`). Never use compile/runtime scope.
- `Databases.start("oracle")` fails fast with a clear message if the driver is missing.

Db2 and MySQL Connector/J are not used at all (LIC-3, LIC-4).

## Notes for this environment

- **Testcontainers version.** The parent pins Testcontainers 1.21.3. That version cannot talk to Docker Engine 29: it fails with "Could not find a valid Docker environment" and an empty `/info` (HTTP 400).
  - The parent now works around this with the surefire system property `api.version=1.44`, which this module inherits.
  - This module also overrides Testcontainers to **1.21.4** in its own `dependencyManagement`. 1.21.4 works with Docker 29 without that property, which matters when `Databases` runs outside surefire (e.g. from a `main`).
  - Suggestion: move the parent's `version.testcontainers` to 1.21.4, then remove both workarounds.
- **Windows: AF_UNIX temp directory.** On this host, every NIO `Selector` (JDK `HttpServer`, `HttpClient`) fails with "Unable to establish loopback connection" when the JDK creates its AF_UNIX pipe in the user temp directory (`C:\Users\<USER~1>\...`).
  - Surefire here passes `-Djdk.net.unixdomain.tmpdir=${project.build.directory}`.
  - Other modules that start these simulators need the same `argLine`.
- **PostgreSQL time zone.** Windows JVMs report India as the legacy alias `Asia/Calcutta`, which `postgres:17` rejects at connect.
  - `Databases.start("postgres")` therefore switches the JVM default zone to the canonical alias (`Asia/Kolkata`). It is the same zone.
