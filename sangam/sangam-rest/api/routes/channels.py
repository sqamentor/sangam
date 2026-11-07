# SPDX-License-Identifier: Apache-2.0
# sangam-channels: conversations, live stream, opt-ins, channel status and provider webhooks. Executed inside
# gen_openapi.py. Off unless sangam.modules.channels.enabled=true.

TAGS.append(("Channels", "Conversations, opt-ins, channel status and provider webhooks (sangam-channels)"))
CHR = ["R19", "R81"]

schema("ChannelAttachment", "A stored conversation attachment.", "key name contentType sizeBytes:long sha256")
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
   "Server-sent events, event name 'message'. Streams live on one node; reconnect and read history to catch up.", CHR,
   params=[pp("id")], resp=(200, "ChannelMessage"), resp_ct="text/event-stream", perm="channels.read")
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
op("post", HB + "/whatsapp", "receiveWhatsAppHook", "Channels", "WhatsApp webhook", "Public: X-Hub-Signature-256 is verified on the raw body first.",
   CHR, params=[pp("tenant")], body=RAW, resp=(200, "ChannelHookResult"), errors=HE)
public(HB + "/whatsapp", "post")
WQ = [pp("tenant"), q("timestamp", required=True), q("nonce", required=True), q("signature", required=True)]
op("get", HB + "/wechat", "verifyWeChatHook", "Channels", "WeChat URL check", "Public: the signature is verified.", CHR,
   params=WQ + [q("echostr", required=True)], resp=(200, {"text/plain": {"schema": {"type": "string"}}}), errors=HE)
public(HB + "/wechat", "get")
op("post", HB + "/wechat", "receiveWeChatHook", "Channels", "WeChat webhook", "Public: the signature is verified first.", CHR,
   params=WQ, body=RAW, resp=(200, "ChannelHookResult"), errors=HE)
public(HB + "/wechat", "post")
op("post", HB + "/teams", "receiveTeamsHook", "Channels", "Teams webhook", "Public: the Bot Framework token is verified first.", CHR,
   params=[pp("tenant")], body=RAW, resp=(200, "ChannelHookResult"), errors=HE)
public(HB + "/teams", "post")

op("get", CC, "listConversations", "Channels", "List conversations", "Only conversations the caller participates in; caseRef narrows to a case.", params=[q("caseRef"), q("limit", "int")], resp=(200, {"application/json": {"schema": {"type": "array", "items": {}}}}), perm="channels.read")
op("get", "/channels/opt-ins", "listChannelOptIns", "Channels", "List opt-ins", "The tenant's mappings, without evidence.", params=[q("channel")], resp=(200, {"application/json": {"schema": {"type": "array", "items": {}}}}), perm="channels.admin")
