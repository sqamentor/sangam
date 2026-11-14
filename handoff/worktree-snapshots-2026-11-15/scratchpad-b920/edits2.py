import sys
p = sys.argv[1] + '/sangam-flowable-ports/src/main/java/org/sangam/ports/events/channel/HttpOutboundAdapter.java'
s = open(p, encoding='utf-8').read().replace('\r\n', '\n')
a = '''    @Override
    public void send(String tenantId, String eventKey, String messageId, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", messageId)
                .header("X-Sangam-Message-Id", messageId)
                .header("X-Sangam-Event-Key", eventKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (secret != null) {
            b.header("X-Sangam-Signature", "sha256=" + WebhookInboundEndpoint.hmacHex(secret, body));
        }'''
b = '''    @Override
    public void send(String tenantId, String eventKey, String messageId, String body) throws Exception {
        send(ChannelAdapters.Outgoing.json(tenantId, eventKey, messageId, body));
    }

    /** M-065 (CloudEvents HTTP binding): binary mode adds {@code ce-<attr>} headers; Content-Type is the message's. */
    @Override
    public void send(ChannelAdapters.Outgoing m) throws Exception {
        String body = m.body();
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", m.contentType())
                .header("Idempotency-Key", m.messageId())
                .header("X-Sangam-Message-Id", m.messageId())
                .header("X-Sangam-Event-Key", m.eventKey())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        m.cloudEventsOrEmpty().forEach((k, v) -> b.header("ce-" + k, v));
        if (secret != null) {
            b.header("X-Sangam-Signature", "sha256=" + WebhookInboundEndpoint.hmacHex(secret, body));
        }'''
assert a in s
s = s.replace(a, b)
s = s.replace(''' * (HMAC-SHA256 of the body) when the channel has {@code properties.secretRef}. Non-2xx responses throw.''', ''' * (HMAC-SHA256 of the body) when the channel has {@code properties.secretRef}. Non-2xx responses throw. CloudEvents
 * (M-065): structured mode posts {@code application/cloudevents+json}, binary mode adds {@code ce-} headers.''')
open(p, 'w', encoding='utf-8', newline='\r\n').write(s)
print('ok')
