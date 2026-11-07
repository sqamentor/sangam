# Connector API verification

Date of every check: 2026-11-08. Scope: `sangam/sangam-channels` and `sangam/sangam-ai`, code and simulators, against official public docs.

**Result values:**
- **MATCH**: the code agrees with the source.
- **FIXED**: there was a mismatch, now corrected in code or simulator.
- **GAP**: the documented behaviour is not implemented, and this is stated rather than fixed.
- **[U]**: not confirmed from an official page.

**Evidence:**
- **[F]** the page was fetched. Pages may reach us as a summary, so exact wording is not guaranteed.
- **[S]** search-result summary only.

**Limits:** Nothing was tested against a real provider. All tests are loopback or simulator tests, and the simulators are written by us from these same docs, so a passing test does not prove interoperability.

## Sources

| Key | URL |
|---|---|
| T1 | https://learn.microsoft.com/en-us/azure/bot-service/rest-api/bot-framework-rest-connector-authentication [F] |
| T2 | https://learn.microsoft.com/en-us/azure/bot-service/rest-api/bot-framework-rest-connector-api-reference [F] |
| T3 | https://learn.microsoft.com/en-us/azure/bot-service/rest-api/bot-framework-rest-connector-send-and-receive-messages [F] |
| W1 | https://developers.facebook.com/docs/whatsapp/cloud-api/reference/messages [F] |
| W2 | https://developers.facebook.com/docs/graph-api/webhooks/getting-started [F] |
| W3 | https://developers.facebook.com/docs/whatsapp/cloud-api/guides/set-up-webhooks/ and https://developers.facebook.com/docs/whatsapp/cloud-api/guides/send-messages [S] |
| C1 | https://developers.weixin.qq.com/doc/service/en/api/customer/message/api_sendcustommessage [S; fetch returned 404] |
| C2 | https://developers.weixin.qq.com/doc/offiaccount/en/Basic_Information/Access_Overview.html [S; fetch returned 404 or connection reset] |
| N1 | https://www.servicenow.com/docs/r/api-reference/rest-apis/c_TableAPI.html [F] |
| P1 | https://help.sap.com/doc/6ea3c713111343e8af4b68e9edbd5881/3.0.11/en-US/70/8e4dc6511e45cf888fa4e9664aa337/content.htm [S] |
| P2 | https://community.sap.com/t5/technology-blog-posts-by-sap/how-does-csrf-token-work-sap-gateway/ba-p/13520186 [S; SAP-authored community post, not product documentation] |
| B1 | https://learn.microsoft.com/en-us/rest/api/servicebus/peek-lock-message-non-destructive-read [F] |
| B2 | https://learn.microsoft.com/en-us/rest/api/servicebus/delete-message [F] |
| B3 | https://learn.microsoft.com/en-us/azure/service-bus-messaging/service-bus-sas [F] |
| M1 | https://modelcontextprotocol.io/docs/2026-07-28/learn/versioning [F] |
| M2 | https://modelcontextprotocol.io/specification/2026-07-28/changelog [F] |
| M3 | https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning [F] |
| M4 | https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http [F] |
| M5 | https://modelcontextprotocol.io/specification/2026-07-28/server/discover [F] |
| M6 | https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle [F] |
| M7 | https://modelcontextprotocol.io/specification/2025-11-25/basic/transports [F] |
| M8 | https://modelcontextprotocol.io/specification/2025-11-25/server/tools [F] |
| A1 | https://a2a-protocol.org/latest/specification/ [F; about 200,000 of 256,159 characters read, with the JSON-RPC section partly cut off] |

## Checks

### Teams / Bot Framework

| Check | Source | Result | Detail |
|---|---|---|---|
| `POST {serviceUrl}/v3/conversations/{id}/activities` | T2, T3 | MATCH | |
| Answer is a ResourceResponse with `id`; 201 is a documented success | T2 | MATCH | The simulator returns 201 |
| Bearer token: client credentials, scope `https://api.botframework.com/.default` | T1 | GAP | The connector reads `tokenRef`; the secret binding must fetch and refresh the token. Now documented in the Javadoc |
| Activity fields | T3 | FIXED | Added `conversation`, plus optional `from` and `recipient`. T3's example includes them; whether Teams requires them for a non-reply send is **[U]** |
| Inbound JWT: issuer, audience, RS256, signature | T1 | MATCH | |
| Inbound JWT: `serviceUrl` claim equals the activity's `serviceUrl` | T1 | **FIXED** | It was not checked, and T1 calls all checks important. The method signature changed, so the integrator must pass the value |
| Inbound JWT: validity period and 5 minute skew | T1 | **FIXED** | `nbf` was ignored and there was no skew |
| Channel endorsements | T1 | GAP | Not checked; stated in the Javadoc |
| Signing keys refreshed at least every 24 hours | T1 | GAP | The integrator's job; stated in the Javadoc |
| Conversation reference for later sends | T3 | FIXED | Added `InboundMessageParsers.teamsReference` |
| Teams live-update and notification app features | | **[U]** | Not checked |

### WhatsApp Cloud API

| Check | Source | Result | Detail |
|---|---|---|---|
| `POST /{Version}/{Phone-Number-ID}/messages`, bearer token | W1 | MATCH | The version belongs in `apiBase` |
| `recipient_type` listed as required | W1 | **FIXED** | Was missing; defaults to `individual` |
| Answer has `contacts`, and `messages[].id`, `message_status` | W1 | FIXED | Simulator updated. The connector reads `messages[0].id`, which matches |
| Template: `name`, `language.code`, `components` | W3 | MATCH [S] | W1's schema was empty on the fetched page |
| Text: `text.body` | W3 | MATCH [S] | Same limitation |
| `X-Hub-Signature-256: sha256=...` with app secret | W2 | MATCH | |
| GET handshake with `hub.mode`, `hub.verify_token`, `hub.challenge` | W2 | **FIXED** | Was absent; added `WebhookVerifiers.whatsAppChallenge` |
| Inbound `entry[].changes[].value.messages[]` | W3 | MATCH [S] | |
| `message_status` values `held_for_quality_assessment`, `paused` | W1 | GAP | Treated as success; the caller gets only the id |

### WeChat Official Account

The official pages could not be fetched, so these results rest on search summaries.

| Check | Source | Result | Detail |
|---|---|---|---|
| `POST https://api.weixin.qq.com/cgi-bin/message/custom/send` | C1 | MATCH [S] | |
| `touser`, `msgtype`, `text.content`; `access_token` in the query | | **[U]** | Not shown in the summaries; unchanged |
| `errcode` in an HTTP 200 answer; 0 is success | | **[U]** | |
| Retryable codes -1 and 45009 | | **[U]** | Unchanged |
| About 48 hour service window | C1 | GAP | Not modelled. An outside-window error becomes a business failure. The code 45015 comes from a third-party guide **[U]** |
| Signature: SHA-1 of sorted token, timestamp, nonce | C2 | MATCH [S] | The host echoes `echostr` |
| Encrypted mode with `msg_signature` | C2 | GAP | Plain mode only; already stated in the parser |

### ServiceNow Table API

| Check | Source | Result | Detail |
|---|---|---|---|
| POST `/api/now/table/{table}` returns 201 and `result` with `sys_id` | N1 | MATCH | |
| PATCH `/{sys_id}` returns 200 and `result` | N1 | MATCH in connector, **FIXED** in simulator | The simulator treated PATCH as a read and changed nothing |
| GET by id; GET list with `result` as an array; `sysparm_limit`, `sysparm_query` | N1 | MATCH | |
| `correlation_id` column for lookups | | **[U]** | Configurable. Table-specific presence not checked |
| HMAC webhook check | | n/a | Sangam's own convention, not a ServiceNow contract; the customer configures it |

### SAP OData

No official product page specifying the wire contract was fetched.

| Check | Source | Result | Detail |
|---|---|---|---|
| `X-CSRF-Token: Fetch`, then send the token with its session cookies | P1, P2 | MATCH [S] | |
| 403 with `X-CSRF-Token: Required`; retrieve again and resubmit | P1, P2 | **FIXED** | Was a business failure; now one retry. Simulator sends the header and can expire a token |
| Location header on 201 | | FIXED in simulator, **[U]** | Standard OData behaviour from memory |
| OData V2 `d` wrapper versus V4 | | **[U]** | The connector returns the raw body; `$batch` is not implemented |
| Key literal in parentheses is percent-encoded | | **[U]** | Unchanged; test against a real Gateway |

### Azure Service Bus

| Check | Source | Result | Detail |
|---|---|---|---|
| Peek-lock `POST {entity}/messages/head?timeout=`, 201 and 204 | B1 | MATCH | |
| Subscription path `{topic}/subscriptions/{sub}` | B1 | MATCH | Passed in `entityPath` |
| `Location` used unchanged for DELETE; 200 on success | B1, B2 | MATCH | |
| `BrokerProperties.MessageId` | B1 | MATCH | |
| SAS format: `sr`, `sig`, `se`, `skn`; HMAC-SHA256 over the encoded URI, a newline, the expiry | B3 | MATCH | |
| `sr` value | B3 | **FIXED** | The code lower-cased the resource, which B3 does not describe and which alters case in the entity path |
| Lock expiry for rejected messages | B1 | MATCH | Redelivery follows lock expiry. Unlock is not used |
| Microsoft Entra ID preferred over SAS | B3 | GAP | SAS only. Fails on a namespace with local auth disabled |

### MCP

The code was pinned to `2025-06-18`. The current version is **2026-07-28** (M1), which is stateless and breaking.

| Check | Source | Result | Detail |
|---|---|---|---|
| Client version | M1, M2, M3 | **FIXED** | Now dual-era: probes with `server/discover`, falls back to `initialize` |
| Modern `_meta` keys; `Mcp-Method`, `Mcp-Name`, `MCP-Protocol-Version` headers | M2, M4 | **FIXED** | Added, with Base64 sentinel encoding |
| `x-mcp-header` mirroring; invalid tools excluded | M4 | **FIXED** | Added |
| Legacy: use the negotiated version; disconnect if unsupported | M6 | **FIXED** | The client ignored the server's answer |
| Legacy: session 404 starts a new session | M7 | **FIXED** | Was an error |
| SSE: priming event and notifications before the response | M4, M7 | **FIXED** | The client took the first `data:` line, which would misread a real server's stream |
| `tools/list` pagination | M8 | **FIXED** | `nextCursor` was ignored |
| Annotations untrusted unless the server is trusted | M8 | **FIXED, behaviour change** | `readOnlyHint` skipped approval. Now needs `trustAnnotations=true` |
| Server: `initialize` echoes a supported version | M6 | **FIXED** | It always returned its own |
| Server: `server/discover`, `resultType`, `ttlMs`, `cacheScope`, `-32022` | M2, M3, M5 | **FIXED** | Added |
| Server: header mismatch, `-32020`, HTTP 400 | M4 | FIXED as helper | `validateHttpHeaders`; the HTTP host must call it |
| Server: Origin check, 202 for notifications, 405 for GET | M4, M7 | GAP | Belongs to the host, as the class is transport-neutral. No HTTP host was checked |
| `tools/call` shape; `isError`; unknown tool `-32602` | M8 | MATCH | |
| `input_required` results | M2 | GAP | Reported as failed, not handled |
| Authorization (issuer validation and similar) | M2 | GAP | Static bearer token only |

### A2A

The code used 0.3 shapes. The current version is **1.0** (A1), with different method names and enums.

| Check | Source | Result | Detail |
|---|---|---|---|
| Methods `SendMessage`, `GetTask` | A1 | **FIXED** | Server takes 1.0 and 0.3; client tries 1.0, then 0.3 |
| `A2A-Version` header; empty means 0.3 | A1 | **FIXED** | Client sends it; `handle` has an overload; `-32009` when unsupported |
| `ROLE_USER`, `TASK_STATE_*`, `{"text": ...}` part | A1 | **FIXED** | |
| Answer wraps `task` or `message` | A1 | **FIXED** | |
| Card `supportedInterfaces` | A1 | **FIXED** | `url` and `protocolVersion` kept for 0.3 clients; whether 1.0 clients tolerate the extras is **[U]** |
| `/.well-known/agent-card.json` | A1 | FIXED as constant | Host must serve it. Path for 0.3 **[U]** |
| `-32001` means TaskNotFound | A1 | **FIXED** | It was used for "caller not allowed", now `-32000`; missing tasks return `-32001` |
| 0.3 fallback rule | A1 | **[U]** | The section read gives no compatibility rule. The fallback is our design; it only follows a refusal |
| Unsupported-version answer with `supportedVersions` and HTTP 400 | A1 | GAP | The host sets the status; `-32009` carries a message, not the list |
| Streaming, push, `CancelTask`, `ListTasks` | A1 | GAP | Not implemented |

## Tests

`mvnw.cmd -q -B -o -pl sangam-ai,sangam-channels -am test -Dtest=ProtocolLoopbackTest,AiRuntimeTest,ChannelsTest` passed. New tests cover the MCP legacy fallback with SSE, the server's two eras and header check, the A2A fallback, the OData token retry, the `serviceUrl` rejection and the WhatsApp handshake. Only those three test classes were run, not the full reactor.

## Integrator actions

1. `WebhookVerifiers.botFramework` has a new `activityServiceUrl` parameter. No production caller was found in `sangam-ai`, `sangam-channels` or `sangam-server`.
2. `McpClient.Config` has a new `trustAnnotations` option, off by default, so read-only tools now need approval unless a server is marked trusted.
3. Any HTTP host for `McpServer` and `A2a.Server` must add the Origin check, status codes and header validation, and pass `A2A-Version`.
