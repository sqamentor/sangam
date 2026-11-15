# SPDX-License-Identifier: Apache-2.0
# sangam-channels: conversations, live stream, opt-ins, channel status and provider webhooks. Executed inside
# gen_openapi.py. Off unless sangam.modules.channels.enabled=true.

TAGS.append(("Channels", "Conversations, opt-ins, channel status and provider webhooks (sangam-channels)"))
CHR = ["R19", "R81"]

schema("ChannelAttachment", "A stored conversation attachment.", "key name contentType sizeBytes:long sha256", ["sizeBytes"])
schema("ChannelMessage", "A conversation message.", "id conversationId sender body attachments:list:ChannelAttachment at",
       ["id", "conversationId", "sender"])
schema("ChannelConversation", "A conversation.", "id tenantId title caseRef retentionClass", ["id", "tenantId"])
schema("ChannelCreateConversation", "Create a conversation.", "title caseRef retentionClass participants:list:s", ["title"])
schema("ChannelAddParticipant", "Add a participant.", "principal", ["principal"])
schema("ChannelSendMessage", "Send a message.", "body attachments:list:ChannelAttachment clientKey", ["body"])
schema("ChannelOptInRequest", "Record or withdraw an opt-in.", "channel externalId principalId evidence",
       ["channel", "externalId", "principalId"])
schema("ChannelStatus", "Runtime state of a channel entry: ACTIVE, NO_ADAPTER or INVALID.", "key channel state detail",
       ["key", "channel", "state"])
schema("ChannelHookResult", "What a webhook delivery produced.", "accepted:int duplicates:int notOptedIn:int")
schema("ChannelSlackHookResult", "Slack Events API answer: challenge for url_verification, otherwise the delivery counts.",
       "challenge accepted:int duplicates:int notOptedIn:int")
# Inbound media (integrator decision on W3-C 13:02:49Z): the webhook only verifies, writes the inbox row and answers
# (Slack needs the answer within 3 s); media is downloaded, scanned (M-082) and stored afterwards by a worker.
HOOK_ASYNC = ("The delivery is verified, written to the inbox and acknowledged at once (within 3 s); attached media is "
              "downloaded, malware-scanned and stored afterwards by a worker, and the stored document key then reaches the "
              "conversation.")

CC = "/channels/conversations"
HE = ("BadRequest", "Unauthorized", "NotFound", "Unprocessable")
op("post", CC, "createConversation", "Channels", "Create a conversation", "The caller becomes a participant.", CHR,
   body="ChannelCreateConversation", resp=(201, "ChannelConversation"), cmd="channels.conversation.create", perm="channels.write")
op("post", CC + "/{id}/participants", "addConversationParticipant", "Channels", "Add a participant", "Same tenant only (R19).", CHR,
   params=[pp("id")], body="ChannelAddParticipant", resp=(204, None), cmd="channels.conversation.addParticipant", perm="channels.write")
op("get", CC + "/{id}/messages", "getConversationMessages", "Channels", "Conversation history", "Participants only.", CHR,
   params=[pp("id"), q("limit", "int")], resp=(200, "array:ChannelMessage"), perm="channels.read")
op("post", CC + "/{id}/messages", "sendConversationMessage", "Channels", "Send a message", "Stored first; live delivery follows commit.",
   CHR, params=[pp("id")], body="ChannelSendMessage", resp=(200, "ChannelMessage"), cmd="channels.conversation.send", perm="channels.write")
op("post", CC + "/{id}/attachments", "uploadConversationAttachment", "Channels", "Upload an attachment",
   "Scanned before it is stored; refused when no scanner is configured unless sangam.channels.allow-unscanned=true.", CHR,
   params=[pp("id"), q("name", required=True)],
   body={"required": True, "content": {"application/octet-stream": {"schema": {"type": "string", "format": "binary"}}}},
   resp=(200, "ChannelAttachment"), cmd="channels.conversation.upload", perm="channels.write")
op("get", CC + "/{id}/stream", "streamConversation", "Channels", "Live message stream",
   "Server-sent events, event name 'message', event id = message id, data = ChannelMessage (M-040). Live across nodes: a "
   "message stored on any node reaches every open stream of the conversation. With Last-Event-ID the stream first replays "
   "the messages after that message, at least once (the client drops duplicates by message id), then goes live. A comment "
   "line heartbeat is sent at most every 30 s while idle.", CHR,
   params=[pp("id"), {"name": "Last-Event-ID", "in": "header", "required": False, "schema": {"type": "string"},
                      "description": "Id of the last message the client received; the messages after it are replayed first"}],
   resp=(200, "ChannelMessage"), resp_ct="text/event-stream", perm="channels.read")
op("get", CC + "/search", "searchConversations", "Channels", "Search messages", "Only conversations the caller takes part in.", CHR,
   params=[q("text", required=True), q("limit", "int")], resp=(200, "array:ChannelMessage"), perm="channels.read")
op("post", "/channels/opt-ins", "recordChannelOptIn", "Channels", "Record an opt-in", "Audited, with evidence.", CHR,
   body="ChannelOptInRequest", resp=(204, None), cmd="channels.optIn", perm="channels.admin")
op("post", "/channels/opt-ins/withdraw", "withdrawChannelOptIn", "Channels", "Withdraw an opt-in", "Audited, with evidence.", CHR,
   body="ChannelOptInRequest", resp=(204, None), cmd="channels.optOut", perm="channels.admin")
op("get", "/channels/status", "getChannelStatus", "Channels", "Channel status", "State of each conversation-channels entry.", CHR,
   resp=(200, "array:ChannelStatus"), perm="channels.read")

HB = "/hooks/channels/{tenant}"
RAW = {"required": True, "content": {"application/json": {"schema": {"type": "object"}},
                                     "application/xml": {"schema": {"type": "string"}}}}
op("get", HB + "/whatsapp", "verifyWhatsAppHook", "Channels", "WhatsApp subscription check", "Public: the verify token is compared.",
   CHR, params=[pp("tenant"), q("hub.verify_token", required=True), q("hub.challenge", required=True)],
   resp=(200, {"text/plain": {"schema": {"type": "string"}}}), errors=HE)
public(HB + "/whatsapp", "get")
op("post", HB + "/whatsapp", "receiveWhatsAppHook", "Channels", "WhatsApp webhook", "Public: X-Hub-Signature-256 is verified on the raw body first. " + HOOK_ASYNC,
   CHR, params=[pp("tenant")], body=RAW, resp=(200, "ChannelHookResult"), errors=HE)
public(HB + "/whatsapp", "post")
WQ = [pp("tenant"), q("timestamp", required=True), q("nonce", required=True), q("signature", required=True)]
op("get", HB + "/wechat", "verifyWeChatHook", "Channels", "WeChat URL check", "Public: the signature is verified.", CHR,
   params=WQ + [q("echostr", required=True)], resp=(200, {"text/plain": {"schema": {"type": "string"}}}), errors=HE)
public(HB + "/wechat", "get")
op("post", HB + "/wechat", "receiveWeChatHook", "Channels", "WeChat webhook",
   "Public: the signature is verified first. Security mode (encrypt_type=aes): msg_signature is verified over the Encrypt "
   "element and the message is decrypted with the tenant's EncodingAESKey (config sangam.channels.webhooks.<tenant>.wechat."
   "encoding-aes-key-ref, a secret reference, and wechat.app-id, which must match the decrypted app id); plain mode as before. "
   "401 WEBHOOK_SIGNATURE_INVALID on a bad signature. " + HOOK_ASYNC, CHR,
   params=WQ + [q("encrypt_type", desc="'aes' in security mode"), q("msg_signature", desc="Signature over the Encrypt element (security mode)")],
   body=RAW, resp=(200, "ChannelHookResult"), errors=HE)
public(HB + "/wechat", "post")
op("post", HB + "/slack", "receiveSlackHook", "Channels", "Slack Events API webhook",
   "Public: X-Slack-Signature (v0 HMAC-SHA256 over 'v0:<timestamp>:<raw body>' with the tenant's signing secret, config "
   "sangam.channels.webhooks.<tenant>.slack.signing-secret-ref) and the timestamp window are verified on the raw body first; "
   "401 WEBHOOK_SIGNATURE_INVALID otherwise, 404 when Slack is not configured for the tenant. url_verification answers "
   "{challenge}; every other envelope answers the counts. " + HOOK_ASYNC, CHR,
   params=[pp("tenant"),
           {"name": "X-Slack-Request-Timestamp", "in": "header", "required": True, "schema": {"type": "string"},
            "description": "Unix seconds of the request; stale requests are refused"},
           {"name": "X-Slack-Signature", "in": "header", "required": True, "schema": {"type": "string"},
            "description": "v0=<hex HMAC-SHA256>"}],
   body={"required": True, "content": {"application/json": {"schema": {"type": "object"}}}},
   resp=(200, "ChannelSlackHookResult"), errors=HE)
public(HB + "/slack", "post")
op("put", HB + "/matrix/transactions/{txnId}", "receiveMatrixTransaction", "Channels", "Matrix application-service transaction",
   "Public: the homeserver token (Authorization: Bearer <hs_token>, or the legacy access_token query parameter) is compared "
   "with the tenant's (config sangam.channels.webhooks.<tenant>.matrix.hs-token-ref, matrix.user-id); 401 "
   "WEBHOOK_SIGNATURE_INVALID otherwise, 404 when Matrix is not configured for the tenant. A repeated txnId is answered "
   "again without a second delivery (inbox deduplication). " + HOOK_ASYNC, CHR,
   params=[pp("tenant"), pp("txnId", "homeserver transaction id"),
           q("access_token", desc="Legacy homeserver token; prefer the Authorization header")],
   body={"required": True, "content": {"application/json": {"schema": {"type": "object", "properties": {
       "events": {"type": "array", "items": {"type": "object"}}}, "required": ["events"]}}}},
   resp=(200, "ChannelHookResult"), errors=HE)
public(HB + "/matrix/transactions/{txnId}", "put")
op("post", HB + "/teams", "receiveTeamsHook", "Channels", "Teams webhook", "Public: the Bot Framework token is verified first. " + HOOK_ASYNC, CHR,
   params=[pp("tenant")], body=RAW, resp=(200, "ChannelHookResult"), errors=HE)
public(HB + "/teams", "post")

op("get", CC, "listConversations", "Channels", "List conversations", "Only conversations the caller participates in; caseRef narrows to a case.", params=[q("caseRef"), q("limit", "int")], resp=(200, {"application/json": {"schema": {"type": "array", "items": {}}}}), perm="channels.read")
op("get", "/channels/opt-ins", "listChannelOptIns", "Channels", "List opt-ins", "The tenant's mappings, without evidence.", params=[q("channel")], resp=(200, {"application/json": {"schema": {"type": "array", "items": {}}}}), perm="channels.admin")
