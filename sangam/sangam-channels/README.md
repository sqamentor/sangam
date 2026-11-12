# sangam-channels

Conversations, bots, messenger adapters, inbound mail, polled inbound channels, and SAP and ServiceNow connectors through the connector SPI. Independent implementations from public API documents. No vendor library or artwork is included.

**Evidence level:** compiled, with `ChannelsTest` run on H2 and loopback simulators. Never run against a real Teams, WhatsApp, WeChat, ServiceNow, SAP or Azure endpoint. The simulators are written from the same reading of the public documents, so they cannot reveal a misreading. The IMAP source has not been run at all.

## Coverage

| NG | File | Status | Stated limits |
|---|---|---|---|
| NG-11 | `messenger/MessengerConnectors.teams`, `WebhookVerifiers.botFramework`, `InboundMessageParsers.teams` | Partial | Notifications, Adaptive Cards and verified inbound. **Gap:** no Teams app package or manifest, no tab task app, no live task list updates, no token acquisition from the identity provider |
| NG-12 | `conversation/ConversationService` | Back end done | Live delivery is a server-sent event stream (no WebSocket); the UI exists (see Screens). Search is a substring scan. Retention runs on request, not on a schedule |
| NG-13 | `bot/BotBridge`, `resources/bots/sample-bot.bpmn` | Back end done | Needs the `ProcessGateway` adapter and a `bot.prompt` worker. BPMN parsing tested; the dialogue has not run on the engine |
| NG-14 | `MessengerConnectors.whatsApp`, `weChat`, `WebhookVerifiers`, `OptInRegistry`, `sim/MessengerSimulator` | Partial | Text and template send, verified inbound, opt-in. **Gap:** WeChat encrypted message mode, media download, provider token refresh, template approval rules |
| NG-15 | `enterprise/EnterpriseConnectors.sapOData`, `EnterpriseSimulator` | Partial | OData read and create with CSRF. **Gap:** RFC (customer-supplied client library needed), update and delete, and the licence review the design asks for |
| NG-16 | `EnterpriseConnectors.serviceNow`, `serviceNowWebhookValid` | Back end done | Table API with lookup by correlation column, which needs a writable column on the target table. The webhook HMAC is our convention, set in the customer's outbound REST message, not a ServiceNow standard |
| NG-27 | `mail/InboundMail` | Done, untested live | Message-ID idempotency, attachments to R74 with a scan hook. Run on `Simulated` only. **Gap:** Microsoft Graph source. The `Poison` port needs binding to `PoisonInbound` |
| NG-40 | `inbound/PolledChannels` | Back end done | File, SFTP (through `FileTransport`), SQL cursor, Service Bus REST. The SFTP path was tested with the local transport only. The server schedules file, SQL and Service Bus sources from `sangam.channels.poll.*` (no SFTP source in the server configuration); they are not registered in the ports' `InboundChannelManager` |
| Correction | `messenger/ChannelRuntime` | Done, wired (`ChannelRuntimeBinder`) | Registry channel entries now build a connector. **Gap:** MATRIX and SLACK have no adapter and are refused explicitly |

## Integration notes

- **Tables:** the `ChannelsSchema` tables (`CH_CONVERSATION`, `CH_PARTICIPANT`, `CH_MESSAGE`, `CH_OPT_IN`, `CH_INBOUND_SEEN`, `CH_POLL_CURSOR`) are in the persistence generator and its five baselines (`sangam-persistence/tools/generate_changelogs.py`, `sangam-0001-baseline.sql`).
- **REST validation:** `JdbcRegistryAdapter.CHANNELS` accepts `MATRIX`, `TEAMS`, `SLACK`, `WHATSAPP` and `WECHAT`. Matrix and Slack entries are stored, but the channel runtime reports them as `NO_ADAPTER` (`CHANNEL_ADAPTER_NOT_AVAILABLE`) and logs a warning; nothing is delivered.
- **Server wiring** (sangam-server `ChannelsWiring`, `ChannelsScheduler`, `ChannelRuntimeBinder`; off unless `sangam.modules.channels.enabled=true`):
  - Webhook endpoints: sangam-rest `ChannelHooksController` at `/api/v1/hooks/channels/{tenant}/{whatsapp,wechat,teams}`. It verifies first, dedupes in `CH_INBOUND_SEEN`, maps through `OptInRegistry`, then calls `BotBridge.onInbound`.
  - `ConversationService` with the live hub `ChannelsLiveHub`, served as a server-sent event stream (`GET /api/v1/channels/conversations/{id}/stream`, not a WebSocket); a file-system `DocumentStore` (`sangam.channels.documents-dir`); the malware scanner bean if present (without one, attachments are refused unless `sangam.channels.allow-unscanned=true`); and the R19 `mayJoin` check.
  - One leader-elected scheduler for `InboundMail.poll` (IMAP) and every `PolledChannels.*.pollOnce` (file, SQL, Service Bus), each with `Receiver.of(InboundEventProcessor)`, plus a per-tenant refresh of the channel runtime. A poisoned mail is audited as `INBOUND_MAIL_POISON`; the `Poison` port is not bound to `PoisonInbound`.
  - `ChannelRuntime` connectors are registered in `ConnectorRegistry` per `conversation-channels` registry entry, with a per-tenant egress policy (`sangam.channels.egress.<tenant>`, deny all by default) and the server's `SecretResolver`. The Bot Framework key set is fetched outside `WebhookVerifiers` (`BotFrameworkKeys`, `sangam.channels.teams.jwks-uri`).
- **Screens:** `sangam-ui` `src/modules/platform.tsx`, page `/m/channels`: inbound channel status (`/channels/status`), a conversation view (message search, open by id, create, send, attach) and opt-in register and withdraw. The same conversation panel is on the case page (`areas/tasklist/StartAndCases.tsx`). The UI polls messages every 10 seconds; it does not use the event stream. Not built: a channel admin screen of its own (channel entries are registry documents), and the Teams tab and task module (NG-11).

## Third-party dependencies

Eclipse Angus Mail 2.0.4 (EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0, already used by sangam-integration). Jackson (Apache-2.0). H2 for tests only.
