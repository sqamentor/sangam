Reading a specification does not approve implementing it, adding a dependency, or claiming conformance (owner, 2026-11-12).

# Spec lookups, Wave 4 (2026-11-12)

These are read-only lookups of public specifications. No terms were accepted, there was no sign-in or registration, and no spec file was kept in any repository. The eSign 2.1 PDF was fetched once to extract its text; both the temporary PDF copy and the extracted text were deleted afterwards. All access dates are 2026-11-12 (UTC).

---

## 1. DMN 1.6: namespace URIs

| URL | Accessed | Version / document |
|---|---|---|
| https://www.omg.org/spec/DMN/1.6/ | 2026-11-12 | DMN 1.6, formal/25-12-02 |
| https://www.omg.org/spec/DMN/1.6/About-DMN | 2026-11-12 | same |
| https://www.omg.org/spec/DMN/20240513/DMN16.xsd | 2026-11-12 | DMN 1.6 XSD (dtc/24-05-20), normative machine-readable file |

**Findings**

- **Specification:** OMG Decision Model and Notation (DMN), Version 1.6, OMG document **formal/25-12-02**. The formal PDF is at https://www.omg.org/spec/DMN/1.6/PDF.
- **MODEL namespace (DMN 1.6):** `https://www.omg.org/spec/DMN/20240513/MODEL/`. This is the `targetNamespace` and default `xmlns` of the normative DMN16.xsd.
- **FEEL / expression language URI (DMN 1.6):** `https://www.omg.org/spec/DMN/20240513/FEEL/`. In the XSD, `tDefinitions` gives this as the default for both `expressionLanguage` and `typeLanguage`.
- **DMNDI:** DMN 1.6 has no new DMNDI namespace. The 1.6 XSD imports `https://www.omg.org/spec/DMN/20230324/DMNDI/` (schemaLocation `DMNDI15.xsd`), which is the DMN 1.5 DMNDI. The DC and DI files listed are the DMN 1.2 ones (`https://www.omg.org/spec/DMN/20180521/DC.xsd` and `.../DI.xsd`).
- **Comparison with 1.5:** 1.5 uses `https://www.omg.org/spec/DMN/20230324/MODEL/`. 1.6 changes only the date segment, to `20240513`.
- **Other normative files:** `https://www.omg.org/spec/DMN/20240513/DMN16.xmi` (UML metamodel, dtc/24-05-27).

**Unresolved / below 97%**

- The OMG pages give the **publication date as "September 2026"**, but the document number formal/25-12-02 suggests December 2025. I recorded the date as the pages show it. The namespace URIs do not depend on it.
- The namespaces were confirmed from the normative XSD, not from the PDF text. Confidence that the XSD is authoritative for the namespace is at least 97%. The PDF was not read.

---

## 2. Arazzo: latest release and changes since 1.0.1

| URL | Accessed | Version |
|---|---|---|
| https://github.com/OAI/Arazzo-Specification/releases | 2026-11-12 | tags 1.1.0 (Latest), 1.0.1, 1.0.0 |
| https://spec.openapis.org/arazzo/v1.1.0.html | 2026-11-12 | Arazzo Specification 1.1.0 |
| https://spec.openapis.org/arazzo/v1.0.1.html | 2026-11-12 | Arazzo Specification 1.0.1 (for comparison) |

**Findings**

- **The latest release is 1.1.0.** Appendix A dates it 2026-05-17. The GitHub release is dated 18 May; the one-day difference is probably time zone or publish timing. Earlier releases: 1.0.1 (2025-01-16, a patch) and 1.0.0 (2024-05-29).
- 1.1.0 is a minor release. The comparison below is from the field lists of the two spec texts and the 1.1.0 release notes.

| Area | 1.0.1 | 1.1.0 (new or changed) |
|---|---|---|
| Source Description `type` | `openapi`, `arazzo` | adds **`asyncapi`** (AsyncAPI v3) |
| Root object | `arazzo, info, sourceDescriptions, workflows, components` | adds **`$self`**, the document's self-assigned URI and base URI (no fragment allowed). References to Arazzo documents SHOULD use the target's `$self` (identity-based referencing). |
| Step target | `operationId` / `operationPath` / `workflowId` (mutually exclusive) | adds **`channelPath`** (AsyncAPI); all four are mutually exclusive. With more than one non-arazzo source, `operationId` MUST be qualified as `$sourceDescriptions.<name>.<operationId>`, and so must a `workflowId` that points into another arazzo source. |
| Async step fields | none | **`action`** (`send` / `receive`, asyncapi only), **`correlationId`** (asyncapi receive only), **`timeout`** (ms; on expiry the step fails and `onFailure` runs), **`dependsOn`** (async coordination). A send step completes once the message is sent; broker acknowledgement is not modelled. |
| Parameter `in` | `path, query, header, cookie` | adds **`querystring`**, aligned with OAS 3.2; it must resolve to a string. `in` may be omitted only when the target is a `workflowId`. |
| Success Action | `name, type(end/goto), workflowId, stepId, criteria` | adds **`parameters`**, which pass workflow inputs on `goto` to a workflow. `in` MUST NOT be used there. |
| Failure Action | `name, type(end/retry/goto), workflowId, stepId, retryAfter, retryLimit, criteria` | adds **`parameters`**, as for Success Action. With `retry` plus `workflowId`/`stepId`, control returns after that target completes. `retryLimit` defaults to one retry if omitted and MUST be exhausted before later failure actions run. A `Retry-After` header SHOULD override `retryAfter`. |
| Criterion | `simple, regex, jsonpath, xpath` | same types, with **evaluation semantics clarified**: `simple` passes on a truthy result; `null` equals only `null`; string comparison is case-insensitive. `regex` requires `context`; a null context fails. `jsonpath` (RFC 9535) passes on a non-empty nodelist. `xpath` defaults to 3.1 and uses the Effective Boolean Value. Any evaluation error means failure (SHOULD be logged). Multiple criteria are combined with AND. Runtime expressions may be embedded in `{}`. Release notes list JSONPath/XPath support "where JSONPointer was already supported". |
| Components | `inputs, parameters, successActions, failureActions` | **same four fields.** Keys must match `^[a-zA-Z0-9\.\-_]+$`; components are document-scoped and have effect only when referenced. A Reusable Object has `reference` plus optional `value` (parameters only). |
| Runtime expressions | `$url $method $statusCode $request. $response. $inputs. $outputs. $steps. $workflows. $sourceDescriptions. $components.` | adds **`$message.`** (async) and **`$self`**. The ABNF is clarified. 1.0.1 had removed `message` from the 1.0.x expression syntax; 1.1.0 brings it back for AsyncAPI. |

- **Changes in 1.0.1 itself (patch over 1.0.0):** the `workflowId` description in success and failure actions was clarified; `retryAfter` wording was fixed; `body` was removed from the Parameter `in` values; `message` was removed from expression syntax; a JSON Schema was added; header and payload references moved to RFC 9110.

**Unresolved / below 97%**

- Appendix A of 1.1.0 has no itemised change list. The table comes from comparing field lists across both spec texts plus the release notes. A line-by-line diff of the normative prose was not done.
- Whether `dependsOn` and `parameters` on actions also have new constraints in the Workflow Object was not checked field by field.

---

## 3. Temporal HTTP API (grpc-gateway)

| URL | Accessed | Version |
|---|---|---|
| https://raw.githubusercontent.com/temporalio/api/master/temporal/api/workflowservice/v1/service.proto | 2026-11-12 | temporalio/api `master` (HEAD at access time), package `temporal.api.workflowservice.v1` |
| https://raw.githubusercontent.com/temporalio/api/master/temporal/api/workflowservice/v1/request_response.proto | 2026-11-12 | same |
| https://raw.githubusercontent.com/temporalio/api/master/temporal/api/enums/v1/workflow.proto | 2026-11-12 | same |
| https://github.com/temporalio/temporal/releases/tag/v1.22.0 | 2026-11-12 | Temporal Server v1.22.0 (2023-09-05) |

**Findings**

- **API version:** WorkflowService `temporal.api.workflowservice.v1`. The HTTP paths come from `google.api.http` annotations. Each RPC has a primary binding without a prefix plus an `additional_bindings` entry under **`/api/v1/...`**, the documented and commonly used prefix.
- **Server version that added the HTTP API: v1.22.0** (released 2023-09-05). The release notes say the frontend workflow service "can now [be] exposed via HTTP in addition to the existing gRPC". It is configured with `services.frontend.rpc.httpPort` and is disabled if unset or 0. The default Docker template uses port **7243**.
- **Start a workflow:** `POST /api/v1/namespaces/{namespace}/workflows/{workflow_id}`, body `*` (also `POST /namespaces/{namespace}/workflows/{workflow_id}`). The current proto also sets the request header `temporal-resource-id: workflow:{workflow_id}`.
  - Request `StartWorkflowExecutionRequest` (proto names; JSON uses lowerCamelCase): `namespace`(1), `workflow_id`(2), `workflow_type`(3), `task_queue`(4), `input`(5), `workflow_execution_timeout`(6), `workflow_run_timeout`(7), `workflow_task_timeout`(8), `identity`(9), **`request_id`(10, idempotency key, typically a UUIDv4)**, **`workflow_id_reuse_policy`(11)**, **`workflow_id_conflict_policy`(22)**, `retry_policy`(12), `cron_schedule`(13), `memo`(14), `search_attributes`(15), `header`(16), `request_eager_execution`(17), `workflow_start_delay`(20), `completion_callbacks`(21), `user_metadata`(23), `links`(24), `versioning_override`(25), **`on_conflict_options`(26)**, `priority`(27), `eager_worker_deployment_options`(28), `time_skipping_config`(29). Fields 18 and 19 are server-internal.
  - Response `StartWorkflowExecutionResponse`: `run_id`(1), **`started`(3, true if a new run was started)**, `status`(5), `eager_workflow_task`(2), `link`(4).
- **Signal-with-start (an alternative idempotent entry point):** `POST /api/v1/namespaces/{namespace}/workflows/{workflow_id}/signal-with-start/{signal_name}`, body `*`.
- **Describe (poll for status):** `GET /api/v1/namespaces/{namespace}/workflows/{execution.workflow_id}` (query param `execution.run_id` optional). The response `DescribeWorkflowExecutionResponse` has `execution_config`, `workflow_execution_info` (holds the status), `pending_activities`, `pending_children`, `pending_workflow_task`, `callbacks`, `pending_nexus_operations`, `workflow_extended_info`.
- **Await the result:** `GET /api/v1/namespaces/{namespace}/workflows/{execution.workflow_id}/history` with **`wait_new_event=true`** and **`history_event_filter_type=HISTORY_EVENT_FILTER_TYPE_CLOSE_EVENT`** (enum value 2). This long-polls until a close event or a timeout. The response has `history`, `raw_history`, `next_page_token`, `archived`. The result payload is in the close event (for example the completed-event attributes). Request fields: `namespace`, `execution`, `maximum_page_size`, `next_page_token`, `wait_new_event`, `history_event_filter_type`, `skip_archival`.
- **Not exposed over HTTP:** `PollWorkflowExecutionUpdate` (the proto says "We don't expose update polling API to HTTP...") and `ExecuteMultiOperation` ("To be exposed over HTTP in the future").
- **Idempotency:**
  - `WorkflowIdReusePolicy` covers a *closed* prior run: `ALLOW_DUPLICATE`=1, `ALLOW_DUPLICATE_FAILED_ONLY`=2, `REJECT_DUPLICATE`=3, and `TERMINATE_IF_RUNNING`=4, which is **deprecated**. Its replacement is ALLOW_DUPLICATE plus conflict policy TERMINATE_EXISTING, which needs Server ≥ v1.24.0. A denied start returns `WorkflowExecutionAlreadyStartedFailure`.
  - `WorkflowIdConflictPolicy` covers a *running* workflow: `FAIL`=1 (returns `WorkflowExecutionAlreadyStartedFailure`), **`USE_EXISTING`=2 (returns a handle to the running workflow; the response has `started=false`)**, `TERMINATE_EXISTING`=3. The proto notes that "it is *never* valid to have two actively running instances of the same workflow id."
  - `request_id` deduplicates retries of the same start call.
  - **For idempotent start-or-attach:** a stable `workflow_id`, plus `workflow_id_conflict_policy=USE_EXISTING`, plus a stable `request_id`. Read `started` and `run_id` from the response.

**Unresolved / below 97%**

- **Minimum server version for `WorkflowIdConflictPolicy`:** the proto says ≥ v1.24.0. Which server version first exposed each field over HTTP was not traced field by field.
- **JSON field casing over the gateway:** lowerCamelCase is the protobuf JSON mapping default, but the exact gateway marshaller options were not verified against a running server or the server's `openapi` file.
- **docs.temporal.io:** no dedicated HTTP API reference page was found there. These findings rest on the proto annotations and the v1.22.0 release notes, both first-party.
- **Pinned commit:** the proto was read at `master` HEAD on the access date. A pinned commit SHA was not recorded.

---

## 4. A2A and MCP

### 4a. A2A (Agent2Agent)

| URL | Accessed | Version |
|---|---|---|
| https://a2a-protocol.org/latest/specification/ | 2026-11-12 | page states "Latest Released Version" 1.0.0 |
| https://github.com/a2aproject/A2A/releases | 2026-11-12 | latest tag **v1.0.1** (2026-05-28); v1.0.0 (2026-03-12); v0.3.0 (2025-07-30) |

**Findings**

- **Current release:** A2A **v1.0.1** (GitHub "Latest", 28 May 2026), a patch on **1.0.0**. Version negotiation uses the `A2A-Version` header with `Major.Minor` only (e.g. `1.0`), so 1.0.0 and 1.0.1 negotiate as `1.0`.
- **1.0 method names are PascalCase.** The 0.x names `message/send`, `message/stream` and `tasks/resubscribe` do not appear in 1.0.
- **Methods, with their HTTP+JSON bindings (§5.3 mapping table):**
  - `SendMessage`: `POST /message:send`
  - **`SendStreamingMessage`** (streaming): `POST /message:stream`
  - `GetTask`: `GET /tasks/{id}`
  - `ListTasks`: `GET /tasks`
  - `CancelTask`: `POST /tasks/{id}:cancel`
  - **`SubscribeToTask`** (streaming): `POST /tasks/{id}:subscribe`. It returns `UnsupportedOperationError` if the task is already terminal.
  - `GetExtendedAgentCard`: `GET /extendedAgentCard`
- **Push-notification config methods:**
  - `CreateTaskPushNotificationConfig`: `POST /tasks/{id}/pushNotificationConfigs`
  - `GetTaskPushNotificationConfig`: `GET /tasks/{id}/pushNotificationConfigs/{configId}`
  - `ListTaskPushNotificationConfigs`: `GET /tasks/{id}/pushNotificationConfigs`
  - `DeleteTaskPushNotificationConfig`: `DELETE /tasks/{id}/pushNotificationConfigs/{configId}`
- **Streaming:** Server-Sent Events, `text/event-stream`. On JSON-RPC each event is `data: {"jsonrpc":"2.0","id":…,"result":{StreamResponse}}`.
- **Push delivery:** the agent sends `POST {webhook url}` with `Content-Type: application/a2a+json` and `Authorization: {scheme} {credentials}`. The body is a `StreamResponse` with exactly one of `task`, `message`, `statusUpdate`, `artifactUpdate`.
  - Config `TaskPushNotificationConfig` fields: `url` (required), `token` (optional), `authentication` (`AuthenticationInfo`: `scheme` required from the IANA registry, `credentials` optional).

**Unresolved / below 97%**

- The §11.3 REST URL table and the remainder of the page past §10.4 were not read. The paths above come from the §5.3 mapping table.
- The spec page header says 1.0.0 while GitHub shows v1.0.1. The patch's changes were not reviewed.
- gRPC and REST path prefixes (for example a tenant or version prefix) were not checked.

### 4b. MCP (Model Context Protocol)

| URL | Accessed | Version |
|---|---|---|
| https://modelcontextprotocol.io/specification/versioning | 2026-11-12 | current revision **2026-07-28** |
| https://modelcontextprotocol.io/specification/2026-07-28/changelog | 2026-11-12 | 2026-07-28 (changes since 2025-11-25) |
| https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http | 2026-11-12 | 2026-07-28 |
| https://modelcontextprotocol.io/specification/2026-07-28/basic/patterns/mrtr | 2026-11-12 | 2026-07-28 |

**Findings**

- **The current revision is 2026-07-28**, marked "Current". The previous revision is 2025-11-25. **2026-07-28 makes major breaking changes:**
  - **Stateless:** the `initialize` / `notifications/initialized` handshake is removed. Every request carries `_meta` with `io.modelcontextprotocol/protocolVersion` and `io.modelcontextprotocol/clientCapabilities`, and SHOULD carry `clientInfo`.
  - **New mandatory `server/discover` RPC.**
  - **Version errors:** `UnsupportedProtocolVersionError` (-32022).
  - **Removed:** `ping`, `logging/setLevel`, `notifications/roots/list_changed`.
- **Server-initiated requests no longer exist as separate JSON-RPC requests.** Sampling (`sampling/createMessage`), elicitation (`elicitation/create`) and roots (`roots/list`) now use **Multi Round-Trip Requests (MRTR, SEP-2322)**:
  - The server returns a result with `resultType: "input_required"` (`InputRequiredResult`), carrying `inputRequests` (a map from id to `ElicitRequest` / `CreateMessageRequest` / `ListRootsRequest`) and/or an opaque `requestState`.
  - The client retries the original request with a **new JSON-RPC id**, `inputResponses`, and the echoed `requestState`.
  - Only allowed on `tools/call`, `resources/read` and `prompts/get`.
  - The server MUST treat `requestState` as attacker-controlled and MUST protect its integrity (HMAC/AEAD) when it affects authorization or business logic. It SHOULD bind principal, TTL and originating request.
  - All results now carry `resultType` (`"complete"` or `"input_required"`).
- **Deprecated in 2026-07-28 (SEP-2577):** the Roots, Sampling and Logging features, plus the `includeContext` values `thisServer` and `allServers`. Elicitation is not deprecated. Also removed: `notifications/elicitation/complete` and the URL-mode `elicitationId`.
- **Streamable HTTP in 2026-07-28:**
  - **Endpoint and methods:** one MCP endpoint, POST only. GET and DELETE should get 405. Protocol sessions and `Mcp-Session-Id` are removed. SSE resumability (`Last-Event-ID`) is removed; a broken stream loses the request, and the client MUST re-issue it with a new id.
  - **Request headers:** clients MUST send `Accept: application/json, text/event-stream`, **`MCP-Protocol-Version`** (must equal `_meta` protocolVersion), **`Mcp-Method`**, and **`Mcp-Name`** (for `tools/call`, `resources/read` and `prompts/get`). Optional `Mcp-Param-{Name}` headers are driven by `x-mcp-header`.
  - **Header validation:** on any header/body mismatch the server MUST reject with 400 and `HeaderMismatch` (-32020).
  - **Responses:** a response is either `application/json` or an SSE stream scoped to the request. The server MUST NOT send JSON-RPC requests on the stream. Closing the stream means cancellation.
  - **Change notifications:** delivered via the **`subscriptions/listen`** long-lived POST stream (it replaces the GET stream and `resources/subscribe`).
  - **Origin:** the server MUST validate `Origin` (403 if invalid) and SHOULD bind to localhost when running locally.
- Tasks moved to the extension `io.modelcontextprotocol/tasks`.

**Unresolved / below 97%**

- If Sangam's earlier plans assumed MCP **2025-11-25** behaviour (initialize handshake, sessions, server-initiated sampling, elicitation and roots over SSE, GET stream), those assumptions are **out of date for 2026-07-28**. The owner should decide which revision or revisions to target. A server may support several versions.
- The full `schema.ts` / `schema.json` for 2026-07-28 was not read.

---

## 5. ABDM Fidelius encryption

| URL | Accessed | Source / version |
|---|---|---|
| https://raw.githubusercontent.com/NHA-ABDM/ABDM-wrapper/master/src/main/java/in/nha/abdm/wrapper/v1/common/cipher/CipherKeyManager.java | 2026-11-12 | NHA-ABDM/ABDM-wrapper `master` (official NHA GitHub org; Apache-2.0; fork of atulai-sg/abdm-wrapper; last push 2025-02-21) |
| https://raw.githubusercontent.com/NHA-ABDM/ABDM-wrapper/master/src/main/java/in/nha/abdm/wrapper/v1/hip/hrp/dataTransfer/encryption/EncryptionService.java | 2026-11-12 | same |
| https://raw.githubusercontent.com/NHA-ABDM/ABDM-wrapper/master/src/main/java/in/nha/abdm/wrapper/v1/common/requests/HealthInformationKeyMaterial.java | 2026-11-12 | same |
| https://raw.githubusercontent.com/NHA-ABDM/ABDM-wrapper/master/src/main/java/in/nha/abdm/wrapper/v1/common/requests/HealthInformationDhPublicKey.java | 2026-11-12 | same |
| https://raw.githubusercontent.com/NHA-ABDM/ABDM-wrapper/master/src/main/java/in/nha/abdm/wrapper/v1/common/requests/HealthInformationPushRequest.java | 2026-11-12 | same |
| https://github.com/mgrmtech/fidelius-cli (README, `abdm/Encryption and Decryption Implementation Guidelines for FHIR data in ABDM.md`, `src/main/java/com/mgrm/fidelius/{Constants,Utils,encryption/EncryptionController,keypairgen/KeyPairGenController}.java`) | 2026-11-12 | `main` HEAD; reference CLI named in the brief; no LICENSE file in the tree |
| https://sandbox.abdm.gov.in/docs/ (301 to https://sandbox.nha.gov.in/) | 2026-11-12 | landing page only; no encryption docs found |
| https://abdm.gov.in:8081/uploads/HIP_HIU_Guidelines_f85df336ec.pdf | 2026-11-12 | **connection refused**, not read |

**Findings (the NHA wrapper and fidelius-cli agree on every point)**

- **Key agreement:** `ECDH` via BouncyCastle, curve name **`curve25519`** from BouncyCastle `CustomNamedCurves`. This is BouncyCastle's **short-Weierstrass form of Curve25519**, not RFC 7748 X25519, because keys are handled as EC points with `getQ()` and `decodePoint`. The provider is `BouncyCastleProvider.PROVIDER_NAME`.
- **Key encodings:**
  - **Public key `keyValue`:** Base64 of the **uncompressed EC point**, `getQ().getEncoded(false)`: 65 bytes starting `0x04`, so 88 Base64 characters. fidelius-cli also accepts an **X.509 SubjectPublicKeyInfo** Base64 form and picks the parser by length (88 characters means a raw point; otherwise X.509).
  - **Private key:** Base64 of `getD().toByteArray()`, a BigInteger byte array that is not fixed-width.
  - **Base64 variant:** BouncyCastle `org.bouncycastle.util.encoders.Base64`, standard alphabet with padding.
- **Nonce:** 32 random bytes (`SecureRandom`), Base64-encoded.
- **Salt and IV:**
  - `xor[i] = senderNonce[i] ^ requesterNonce[i % len]`, over 32 bytes.
  - **salt = xor[0..20)**, the first 20 bytes.
  - **IV = xor[len-12..len)**, the last 12 bytes.
- **Key derivation:** **HKDF with SHA-256** (BouncyCastle `HKDFBytesGenerator(new SHA256Digest())`).
  - **IKM:** the raw ECDH shared secret bytes. The shared secret is Base64-encoded, then decoded again before HKDF.
  - **salt:** the 20 bytes above.
  - **info:** `null`.
  - **Output:** 32 bytes (an AES-256 key).
- **Cipher:** **AES-256-GCM** (`GCMBlockCipher(AESEngine)`), **128-bit tag**, no associated data. The ciphertext includes the tag and is Base64-encoded. Plaintext uses `String.getBytes()`, i.e. the platform default charset. That is a portability hazard: use UTF-8 explicitly.
- **keyMaterial in the data push** (`HealthInformationPushRequest`: `pageNumber`, `pageCount`, `transactionId`, `entries`, `keyMaterial`):
  - `keyMaterial.cryptoAlg`: `"ECDH"`
  - `keyMaterial.curve`: `"Curve25519"` (wire value per forum examples; the wrapper's constant is `curve25519`, used only for the BouncyCastle lookup)
  - `keyMaterial.dhPublicKey.expiry`: ISO-8601 timestamp string
  - `keyMaterial.dhPublicKey.parameters`: `"Curve25519/32byte random key"` (the wrapper's `PARAMETERS` constant)
  - `keyMaterial.dhPublicKey.keyValue`: the sender's public key, Base64
  - `keyMaterial.nonce`: the sender's 32-byte nonce, Base64
  - In the HIU's request the same structure carries the requester's key and nonce. The HIP replies with its own key and nonce in the push.

**Unresolved / below 97%**

- **No official NHA/ABDM prose specification was reachable.** The sandbox docs URL now redirects to a landing page with no encryption documents. The official guidelines PDF on abdm.gov.in:8081 refused the connection. The ABDM developer forum (devforum.abdm.gov.in) was seen only in search snippets and is not a specification. The findings above rest on **NHA's own reference code** (the NHA-ABDM wrapper) and fidelius-cli, not a normative document.
- **Wire form of `keyValue` in the HIP data push:** the wrapper's `EncryptionService` builds `keyToShare` from `ecKey.getEncoded()` (likely X.509 SPKI), while its key generator emits the raw point. A third-party PR (IUI-Solutions-HealthDoc/healthdoc #613, not authoritative) reports that the ABDM receiver rejected a raw 65-byte point on the HIP push and accepted X.509 SPKI, while the HIU request kept the raw point. **Confidence about 80%. Owner or sandbox confirmation needed.**
- **Exact `curve` string case on the wire** (`Curve25519` or `curve25519`) and the `expiry` format were not confirmed from an official schema.
- **mgrmtech/fidelius-cli has no LICENSE file in its tree.** Reading it is fine; reusing its code is not cleared.

---

## 6. CCA eSign API 2.1

| URL | Accessed | Version |
|---|---|---|
| https://cca.gov.in/eSignAPI.html | 2026-11-12 | listing page: eSign API 2.1, released 07 Feb 2023, ESP readiness by 07.04.2023 |
| https://cca.gov.in/sites/files/pdf/ACT/eSign-APIv2.1.pdf | 2026-11-12 | "eSign API Specifications", Version 2.1, Feb 2023; document control: release 04.05.2018, last update 07.02.2023; owner CCA, MeitY |

**Findings**

- **Scope:** eSign under the Second Schedule of the IT Act, 2000 ("e-authentication technique using Aadhaar e-KYC services"), online Aadhaar e-KYC by the ESP. The request root `Esign@ver` is `"2.1"`. Transport is HTTPS POST, `Content-Type: application/xml`, as a browser redirect to the ESP URL.
- **Request:** `<Esign ver sc ts txn ekycId ekycIdType aspId AuthMode responseSigType responseUrl><Docs><InputHash id hashAlgorithm docInfo>hex</InputHash></Docs><Signature/></Esign>`.
  - `InputHash` is a **SHA-256 hex** digest with `hashAlgorithm="SHA256"`, `id="1"` (one hash only), and `docInfo` of at most 50 characters.
  - `sc="Y"` records explicit consent; `ts` is IST within ±30 minutes; `AuthMode` 1=OTP/TOTP, 2=fingerprint, 3=iris, 4=face (face added 07.02.2023).
- **`responseSigType` values, which set the DocSignature type:**
  1. `rawrsa`: raw RSA (PKCS#1) signature.
  2. `PKCS7`: CMS with **only the signer certificate** and **no revocation information**.
  3. `PKCS7pdf`: CMS with **all issuer certificates up to and including the root CA**. It also carries the **CRLs/OCSP responses of each issuer certificate** (OCSP only if a CRL has more than 5 entries), **timestamped by the CA's TSA**, with revocation info as a **signed attribute `pdfRevocationInfoArchival` (1.2.840.113583.1.1.8)**, i.e. PAdES/Adobe-style.
  4. `PKCS7complete`: all issuer certificates and their revocation info in **unsigned** attributes.
  5. `Rawecdsa`: raw ECDSA.

  `PKCS7pdf` and `PKCS7complete` were added on 10.08.2020.
- **Response:** `<EsignResp status ts txn resCode errCode errMsg><UserX509Certificate>…</UserX509Certificate><Signatures><DocSignature id sigHashAlgorithm="SHA256" error>…</DocSignature></Signatures><Signature>ESP signature</Signature></EsignResp>`.
  - `status`: "1" on success, "0" on failure.
  - `errCode` and `errMsg`: "NA" on success.
  - `resCode`: the ESP's unique transaction id; the ASP is expected to store it in its audit log.
- **DocSignature:** "Signed value in raw (PKCS#1) or raw (ECDSA) or PKCS7 (CMS) signature format as per the request XML." The PKCS7 variants sign the hash only, so DocSignature is in effect a **detached CMS** whose content is the document hash. The encoding is **Base64**: the spec states Base64 only for `UserX509Certificate`, and Base64 for DocSignature is inferred from XML text content and common practice.
- **UserX509Certificate:** the Base64 DER signer certificate, which can be saved as a PEM `.cer` with begin/end lines. It is a one-time e-KYC-class DSC that embeds the e-KYC number, name, e-KYC response code, auth type and timestamp. Its class is "Single Factor" for OTP and "Multi Factor" for biometric, per India PKI CP.
- **XML signatures:** both the request `<Signature>` (ASP) and the response `<Signature>` (ESP) are **W3C XML Signature (XMLDSig, Second Edition)** over the whole XML. The ASP signature is mandatory, and the ESP verifies it against the certificate mapped to the ASP (errors ESP-911 and ESP-922). The ASP verifies the ESP signature to detect tampering. **The algorithm, canonicalisation, transform (enveloped) and KeyInfo contents are not specified** in 2.1.
- **Trust anchors:** the spec names none explicitly beyond "all issuer certificates up to and including root CA" for `PKCS7pdf`. Under India PKI that chain ends at the **CCA root (Root Certifying Authority of India)** through the licensed CA's certificate. The spec says the ASP should provide a mechanism to verify and accept the DSC.
- **Revocation:** required inside the signature only for `PKCS7pdf` (CRL or OCSP per issuer, in the signed attribute) and `PKCS7complete` (unsigned). Explicitly **excluded** for `PKCS7`. No OCSP/CRL duty is stated for the ASP's own verification.
- **Error codes (Annexure 1):** ESP-901 to 999, for example ESP-910 duplicate txn, ESP-911 input XML signature verification failed, ESP-946 UI page expired.

**Unresolved / below 97%**

- **XMLDSig profile:** the signature algorithm (RSA-SHA256?), C14N method, enveloped transform and KeyInfo content are not in 2.1. The ESP's response-signing certificate and how the ASP obtains or pins it are not specified. These come from the ESP's onboarding or SDK. **Owner or ESP input is needed.**
- **CCA root and CA certificate URLs** (India PKI root certificate distribution, CCA repository) were **not looked up**. Doing so is a separate lookup on cca.gov.in.
- **DocSignature encoding (Base64):** inferred, not stated, confidence about 95%. Whether the CMS for `PKCS7` is detached (eContent absent) is inferred from "signs the document hash", confidence about 90%.
- **Related guidance not read:** the e-authentication guidelines (https://cca.gov.in/sites/files/pdf/esign/CCA-EAUTH.pdf) may add verification and revocation duties.
- **Superseding versions:** the listing page shows no 2.2, and the 3.x line is a separate family (CA e-KYC account based). 2.1 is the newest "Online Aadhaar & Others" version on the page.
- **cca.gov.in footer links:** the "Terms & Conditions" and "Copyright" pages in the footer were not opened. The spec PDF is publicly linked without any gate.
