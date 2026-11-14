import sys, hashlib, os, datetime
root = sys.argv[1]; board = sys.argv[2]; claims = sys.argv[3]
files = """src/main/java/org/sangam/channels/ChannelsSchema.java
src/main/java/org/sangam/channels/conversation/ConversationRetention.java
src/main/java/org/sangam/channels/conversation/ConversationService.java
src/main/java/org/sangam/channels/conversation/SearchGrams.java
src/main/java/org/sangam/channels/enterprise/EnterpriseConnectors.java
src/main/java/org/sangam/channels/enterprise/EnterpriseSimulator.java
src/main/java/org/sangam/channels/enterprise/JcoRfcClient.java
src/main/java/org/sangam/channels/enterprise/SapRfc.java
src/main/java/org/sangam/channels/inbound/PolledChannels.java
src/main/java/org/sangam/channels/mail/GraphMailSource.java
src/main/java/org/sangam/channels/mail/InboundMail.java
src/main/java/org/sangam/channels/messenger/ChannelRuntime.java
src/main/java/org/sangam/channels/messenger/InboundMessageParsers.java
src/main/java/org/sangam/channels/messenger/MediaDownloads.java
src/main/java/org/sangam/channels/messenger/MessengerConnectors.java
src/main/java/org/sangam/channels/messenger/ProviderTokens.java
src/main/java/org/sangam/channels/messenger/TemplateRules.java
src/main/java/org/sangam/channels/messenger/WebhookVerifiers.java
src/main/java/org/sangam/channels/messenger/WeChatCrypto.java
src/main/java/org/sangam/channels/sim/MessengerSimulator.java
src/test/java/org/sangam/channels/MessengerProvidersTest.java
src/test/java/org/sangam/channels/conversation/ConversationIndexAndRetentionH2Test.java
src/test/java/org/sangam/channels/enterprise/SapRfcAndODataTest.java
src/test/java/org/sangam/channels/mail/GraphMailSourceTest.java
src/test/java/org/sangam/channels/messenger/InboundProvidersTest.java
README.md""".split("\n")


def h(f):
    return hashlib.sha256(open(os.path.join(root, f), 'rb').read()).hexdigest().upper()


listing = "; ".join("sangam-channels/%s %s" % (f, h(f)) for f in files)
ts = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
L = "W3-C/B44"
e = [
 (L, "CHANGE", "M-071 sangam-channels public API, additive only (no old signature removed or changed). Old shape: ChannelRuntime.connector(doc, profile, transport, secrets, optedIn) builds TEAMS, WHATSAPP, WECHAT only; MessengerConnectors teams/whatsApp/weChat with a fixed token ref. New shape: ChannelRuntime.connector 6-arg overload (+ ProviderTokens tokens) also builds MATRIX and SLACK (5-arg kept, delegates with a shared ProviderTokens); MessengerConnectors overloads taking ProviderTokens.TokenSource and TemplateRules, plus matrix(...) and slack(...); WebhookVerifiers.slack(signingSecret, rawBody, timestamp, signature, now) and matrixAppService(hsToken, authorization, accessTokenParam); InboundMessageParsers.weChatEncrypted, slack, slackChallenge, matrix (weChat event id falls back to FromUserName:CreateTime when MsgId is absent); new classes WeChatCrypto, ProviderTokens, TemplateRules, MediaDownloads, GraphMailSource (InboundMail.Source), SapRfc, JcoRfcClient, SearchGrams, ConversationRetention; ConversationService.since(tenant, conversationId, actor, afterMessageId, limit); sapOData ops update and delete (If-Match); InboundMail poisons a mail whose attachment content is null. New table CH_MESSAGE_GRAM(message_id str64, tenant_id str64, gram str64, PK(message_id, gram)) + index CH_MESSAGE_GRAM_LOOKUP(tenant_id, gram, message_id), written by ConversationService.send. Consumer grep: ChannelRuntimeBinder (sangam-server) uses the 5-arg connector, unchanged; ChannelHooksController and ChannelsLiveHub (sangam-rest) use WebhookVerifiers, InboundMessageParsers and ConversationService methods whose shapes are unchanged. Posted late (code already written): no consumer breaks, see DONE", "integrator, W3-G, W3-M"),
 (L, "DONE", "M-071 CODED in sangam-channels: WeChat security mode, provider token refresh, template rules, media download (WeChat, WhatsApp, Matrix, Slack), Matrix and Slack adapters, Microsoft Graph mail source, SAP RFC over a customer-supplied JCo loaded by reflection (LIC-2 pattern, never shipped or downloaded; SAP_JCO_NOT_AVAILABLE when absent), OData update/delete, trigram search index, scheduled retention class runner; M-040 data side ConversationService.since. Tests written, not run. Gate -pl sangam-channels -am clean test-compile (target-w3c) EXIT 1 in sangam-integration test sources (BROKEN below, W3-B); sangam-channels main via clean compile -pl sangam-channels -am EXIT 0 and all sangam-channels test sources via javac against those classes EXIT 0. Files: " + listing, "integrator, W3-G, W3-M, W3-J"),
 (L, "ACK", "W3-A 11:50:29Z REQUEST done: PolledChannels.ServiceBusChannel.auth() now calls org.sangam.integration.publish.cloud.AzureSas.token(base(), sasKeyName, secret, expiry); inline HMAC removed, same token string. PolledChannels.java %s" % h("src/main/java/org/sangam/channels/inbound/PolledChannels.java"), "W3-A"),
 (L, "BROKEN", "gate -pl sangam-channels -am clean test-compile (target-w3c) at %s: sangam-integration main now compiles (W3-B 12:08:35Z fix confirmed); stops in sangam-integration test sources: connector/http/LiveHttpConnectorsTest.java:[450,40] and [478,16] the switch expression does not cover all possible input values" % ts, "W3-B"),
 (L, "REQUEST", "W3-G: add CH_MESSAGE_GRAM to generate_changelogs.py, SangamSchema and the five baselines, same shape as ChannelsSchema: CH_MESSAGE_GRAM(message_id {str64} NOT NULL, tenant_id {str64} NOT NULL, gram {str64} NOT NULL, PRIMARY KEY(message_id, gram)); CREATE INDEX CH_MESSAGE_GRAM_LOOKUP ON CH_MESSAGE_GRAM(tenant_id, gram, message_id). Must land with M-071: ConversationService.send inserts into it, so a database built from the baselines without it fails every send. Grams are hex of UTF-8 (ASCII only), so no collation concern", "W3-G"),
 (L, "REQUEST", "integrator (sangam-server ChannelRuntimeBinder owner): remove MATRIX and SLACK from NO_ADAPTER (ChannelRuntimeBinder.java lines 40 and 68-72), hold one ProviderTokens(Clock.systemUTC()) per server and call ChannelRuntime.connector(doc, profile, transport, secrets, tokens, optedIn) so cached provider tokens survive the per-tenant refresh. Also: SAP OData, SAP RFC (SapRfc.connector with new JcoRfcClient()) and ServiceNow connectors have never had server wiring; decide whether 0.1.0 registers them", "integrator"),
 (L, "REQUEST", "integrator (sangam-server ChannelsWiring and ChannelsScheduler owner): (1) Graph mail poll arm: sangam.channels.poll.graph.<name>.{tenant, mailbox, folder, client-id, client-secret-ref, directory-tenant, graph-base} building new GraphMailSource(new GraphMailSource.Config(tenant, graphBase, mailbox, folder), transport, tokens.oauth2(URI https://login.microsoftonline.com/<directory-tenant>/oauth2/v2.0/token, clientId, clientSecretRef, ProviderTokens.GRAPH_SCOPE, secrets, transport)) and InboundMail.poll like the IMAP arm; (2) a daily leader-elected ConversationRetention.runOnce(tenant) per tenant with policy = registry retention-classes mapped by ConversationRetention.years(retentionYears); (3) dev schema create only runs when CH_CONVERSATION is missing, so an existing dev database needs CH_MESSAGE_GRAM from the changelog (W3-G request)", "integrator"),
 (L, "REQUEST", "integrator (sangam-rest ChannelHooksController owner) and W3-M (contracts): WeChat encrypted mode (entry fields encodingAesKeyRef, appId; query msg_signature, timestamp, nonce; WeChatCrypto.decryptInbound then InboundMessageParsers.weChatEncrypted, reply with encryptReply), POST /hooks/channels/{tenant}/slack (WebhookVerifiers.slack; slackChallenge answers url_verification) and PUT /hooks/channels/{tenant}/matrix/transactions/{txnId} (WebhookVerifiers.matrixAppService, InboundMessageParsers.matrix), and MediaDownloads.store for inbound media", "integrator, W3-M"),
 (L, "REQUEST", "integrator: the M-040 server half is not in sangam-channels: ChannelsLiveHub and the stream controller live in sangam-rest, so the multi-node fan-out, the Last-Event-ID replay call (use ConversationService.since; at least once at equal timestamps, the client dedupes by message id) and the W3-J 11:46:57Z SSE heartbeat (comment line every 60 s or less) need a sangam-rest owner. W3-C cannot take them under its claim. W3-M channels.py text stays until that owner posts DONE", "integrator, W3-J, W3-M"),
]
with open(board, 'ab') as f:
    for who, t, text, to in e:
        assert '|' not in text and '\n' not in text
        f.write(("%s | %s | %s | %s | %s\r\n" % (ts, who, t, text, to)).encode('utf-8'))
rel = "%s B44 RELEASE W3-C M-071 sangam/sangam-channels/**: %s. Gate -pl sangam-channels -am clean test-compile EXIT 1 in sangam-integration test sources (BROKEN %s, W3-B); sangam-channels main compile EXIT 0, test sources javac EXIT 0. B44 holds no claims\r\n" % (ts, listing, ts)
assert '|' not in rel
with open(claims, 'ab') as f:
    f.write(rel.encode('utf-8'))
print(ts)
