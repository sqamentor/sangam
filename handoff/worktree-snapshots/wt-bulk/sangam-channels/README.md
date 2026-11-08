# sangam-channels

Conversations, bots, messenger adapters, inbound mail, polled inbound channels, and SAP and ServiceNow connectors through the connector SPI. Independent implementations from public API documents. No vendor library or artwork is included.

**Evidence level:** compiled, with `ChannelsTest` run on H2 and loopback simulators. Never run against a real Teams, WhatsApp, WeChat, ServiceNow, SAP or Azure endpoint. The simulators are written from the same reading of the public documents, so they cannot reveal a misreading. The IMAP source has not been run at all.

## Coverage

| NG | File | Status | Stated limits |
|---|---|---|---|
| NG-11 | `messenger/MessengerConnectors.teams`, `WebhookVerifiers.botFramework`, `InboundMessageParsers.teams` | Partial | Notifications, Adaptive Cards and verified inbound. **Gap:** no Teams app package or manifest, no tab task app, no live task list updates, no token acquisition from the identity provider |
| NG-12 | `conversation/ConversationService` | Back end done | **Gap:** WebSocket endpoint and UI. Search is a substring scan. Retention runs on request, not on a schedule |
| NG-13 | `bot/BotBridge`, `resources/bots/sample-bot.bpmn` | Back end done | Needs the `ProcessGateway` adapter and a `bot.prompt` worker. BPMN parsing tested; the dialogue has not run on the engine |
| NG-14 | `MessengerConnectors.whatsApp`, `weChat`, `WebhookVerifiers`, `OptInRegistry`, `sim/MessengerSimulator` | Partial | Text and template send, verified inbound, opt-in. **Gap:** WeChat encrypted message mode, media download, provider token refresh, template approval rules |
| NG-15 | `enterprise/EnterpriseConnectors.sapOData`, `EnterpriseSimulator` | Partial | OData read and create with CSRF. **Gap:** RFC (customer-supplied client library needed), update and delete, and the licence review the design asks for |
| NG-16 | `EnterpriseConnectors.serviceNow`, `serviceNowWebhookValid` | Back end done | Table API with lookup by correlation column, which needs a writable column on the target table. The webhook HMAC is our convention, set in the customer's outbound REST message, not a ServiceNow standard |
| NG-27 | `mail/InboundMail` | Done, untested live | Message-ID idempotency, attachments to R74 with a scan hook. Run on `Simulated` only. **Gap:** Microsoft Graph source. The `Poison` port needs binding to `PoisonInbound` |
| NG-40 | `inbound/PolledChannels` | Back end done | File, SFTP (through `FileTransport`), SQL cursor, Service Bus REST. The SFTP path was tested with the local transport only. Not visible in `InboundChannelManager` until wired |
| Correction | `messenger/ChannelRuntime` | Done, needs wiring | Registry channel entries now build a connector. **Gap:** MATRIX and SLACK have no adapter and are refused explicitly |

## Integration notes

- **Tables:** `ChannelsSchema` (`CH_CONVERSATION`, `CH_PARTICIPANT`, `CH_MESSAGE`, `CH_OPT_IN`, `CH_INBOUND_SEEN`, `CH_POLL_CURSOR`) must be added to the persistence generator.
- **REST validation:** `JdbcRegistryAdapter.CHANNELS` must add `WECHAT`, or such entries cannot be stored. Matrix and Slack entries should warn that they have no adapter.
- **Server wiring:**
  - Webhook endpoints for each provider: verify first on the raw body, dedupe with the inbox, map through `OptInRegistry`, then call `BotBridge.onInbound`.
  - `ConversationService.LiveDelivery` over WebSocket; `DocumentStore`; the malware scanner; the R19 `mayJoin` check.
  - Schedulers for `InboundMail.poll` and every `PolledChannels.*.pollOnce`, each with `Receiver.of(InboundEventProcessor)`.
  - `ChannelRuntime` and the connectors in `ConnectorRegistry`, with a tenant `EgressPolicy` and a vault-backed `SecretResolver`. The Bot Framework key set must be refreshed outside `WebhookVerifiers`.
- **Screens needed:**
  - Conversation view with attachments and search, plus a conversation panel on a case.
  - Opt-in admin and channel admin.
  - Inbound channel status (last poll, poison items, SQL cursor).
  - Teams tab and task module, if NG-11 goes further.

## Third-party dependencies

Eclipse Angus Mail 2.0.4 (EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0, already used by sangam-integration). Jackson (Apache-2.0). H2 for tests only.
