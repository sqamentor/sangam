import sys
base = sys.argv[1] + '/sangam-flowable-ports/src/main/java/org/sangam/ports/events/channel/'


def edit(fn, pairs):
    p = base + fn
    s = open(p, encoding='utf-8').read().replace('\r\n', '\n')
    for a, b in pairs:
        assert a in s, (fn, a[:80])
        s = s.replace(a, b, 1)
    open(p, 'w', encoding='utf-8', newline='\r\n').write(s)


edit('RabbitAdapters.java', [
(''' * {@code properties.prefetch}. Outbound: publisher confirms, persistent messages.
 */''', ''' * {@code properties.prefetch}. Outbound: publisher confirms, persistent messages.
 *
 * <p>CloudEvents (M-065, AMQP protocol binding): binary mode writes every attribute as an application property
 * {@code cloudEvents_<attr>} and {@code content-type} = the data content type; structured mode sends
 * {@code application/cloudevents+json}. SL-142 (C3-06): a delivery of a tenant that may not be stored yet (FROZEN,
 * IMPORTING) is not acknowledged: after {@code properties.redeliverBackoffMs} it is nacked with requeue; a MOVED or
 * PURGED tenant's delivery is parked, then acknowledged.
 */'''),
('''        @Override
        public synchronized void send(String tenantId, String eventKey, String messageId, String body) throws Exception {
            Map<String, Object> headers = new HashMap<>();
            headers.put("event-key", eventKey);
            if (tenantId != null) {
                headers.put("tenant-id", tenantId);
            }
            AMQP.BasicProperties props = new AMQP.BasicProperties.Builder().messageId(messageId).contentType("application/json")
                    .deliveryMode(2).headers(headers).build();
            String exchange = ch.exchange() == null ? "" : ch.exchange();
            String routingKey = ch.routingKey() != null ? ch.routingKey() : ch.queue() != null ? ch.queue() : ch.destination();
            channel.basicPublish(exchange, routingKey, true, props, body.getBytes(StandardCharsets.UTF_8));''', '''        @Override
        public void send(String tenantId, String eventKey, String messageId, String body) throws Exception {
            send(ChannelAdapters.Outgoing.json(tenantId, eventKey, messageId, body));
        }

        @Override
        public synchronized void send(ChannelAdapters.Outgoing m) throws Exception {
            Map<String, Object> headers = new HashMap<>();
            headers.put("event-key", m.eventKey());
            if (m.tenantId() != null) {
                headers.put("tenant-id", m.tenantId());
            }
            m.cloudEventsOrEmpty().forEach((k, v) -> headers.put("cloudEvents_" + k, v));
            AMQP.BasicProperties props = new AMQP.BasicProperties.Builder().messageId(m.messageId()).contentType(m.contentType())
                    .deliveryMode(2).headers(headers).build();
            String exchange = ch.exchange() == null ? "" : ch.exchange();
            String routingKey = ch.routingKey() != null ? ch.routingKey() : ch.queue() != null ? ch.queue() : ch.destination();
            channel.basicPublish(exchange, routingKey, true, props, m.body().getBytes(StandardCharsets.UTF_8));'''),
('''                String id = d.getProperties().getMessageId();
                processor.receive(tenant, ch.key(), new String(d.getBody(), StandardCharsets.UTF_8), headers, id);
                channel.basicAck(tag, false);
            } catch (RuntimeException e) {''', '''                if (d.getProperties().getContentType() != null) {
                    headers.putIfAbsent("content-type", d.getProperties().getContentType());
                }
                String id = d.getProperties().getMessageId();
                if (ChannelAdapters.deliver(processor, tenant, ch, new String(d.getBody(), StandardCharsets.UTF_8), headers, id)) {
                    channel.basicAck(tag, false);
                } else {
                    // SL-142: the tenant may not take it yet; back off so a long freeze is not a hot redelivery loop
                    ChannelAdapters.sleep(ChannelAdapters.redeliverBackoffMs(ch));
                    channel.basicNack(tag, false, true);
                }
            } catch (RuntimeException e) {'''),
])

edit('KafkaAdapters.java', [
(''' * (e.g. {@code kafka.security.protocol}); secrets come from {@code secretRef}-style references only.
 */''', ''' * (e.g. {@code kafka.security.protocol}); secrets come from {@code secretRef}-style references only.
 *
 * <p>CloudEvents (M-065, Kafka protocol binding): binary mode writes {@code ce_<attr>} headers and
 * {@code content-type} = the data content type; structured mode sets {@code content-type: application/cloudevents+json}.
 * SL-142 (C3-06): a record of a tenant that may not be stored yet is not committed: the consumer seeks back and backs
 * off ({@code retryBackoffMs}); a MOVED or PURGED tenant's record is parked, then committed.
 */'''),
('''        @Override
        public void send(String tenantId, String eventKey, String messageId, String body) throws Exception {
            String key = ch.properties().getOrDefault("recordKey", "messageId").equals("tenant") ? tenantId : messageId;
            ProducerRecord<String, String> r = new ProducerRecord<>(topic, key, body);
            r.headers().add("message-id", messageId.getBytes(StandardCharsets.UTF_8));
            r.headers().add("event-key", eventKey.getBytes(StandardCharsets.UTF_8));
            if (tenantId != null) {
                r.headers().add("tenant-id", tenantId.getBytes(StandardCharsets.UTF_8));
            }''', '''        @Override
        public void send(String tenantId, String eventKey, String messageId, String body) throws Exception {
            send(ChannelAdapters.Outgoing.json(tenantId, eventKey, messageId, body));
        }

        @Override
        public void send(ChannelAdapters.Outgoing m) throws Exception {
            String key = ch.properties().getOrDefault("recordKey", "messageId").equals("tenant") ? m.tenantId() : m.messageId();
            ProducerRecord<String, String> r = new ProducerRecord<>(topic, key, m.body());
            r.headers().add("message-id", m.messageId().getBytes(StandardCharsets.UTF_8));
            r.headers().add("event-key", m.eventKey().getBytes(StandardCharsets.UTF_8));
            if (m.tenantId() != null) {
                r.headers().add("tenant-id", m.tenantId().getBytes(StandardCharsets.UTF_8));
            }
            if (!"application/json".equals(m.contentType()) || m.binary()) {
                r.headers().add("content-type", m.contentType().getBytes(StandardCharsets.UTF_8));
            }
            m.cloudEventsOrEmpty().forEach((k, v) -> r.headers().add("ce_" + k, v.getBytes(StandardCharsets.UTF_8)));'''),
('''                            processor.receive(tenant, ch.key(), rec.value(), headers, id);
                            c.commitSync(Map.of(tp, new OffsetAndMetadata(rec.offset() + 1)));''', '''                            if (!ChannelAdapters.deliver(processor, tenant, ch, rec.value(), headers, id)) {
                                // SL-142: not admitted yet (FROZEN, IMPORTING): re-read this record after the back-off
                                c.seek(tp, rec.offset());
                                sleep(Long.parseLong(ch.property("retryBackoffMs", "5000")));
                                break;
                            }
                            c.commitSync(Map.of(tp, new OffsetAndMetadata(rec.offset() + 1)));'''),
])

edit('JmsAdapters.java', [
(''' * factory), persistent delivery, {@code JMSCorrelationID} = message id.
 */''', ''' * factory), persistent delivery, {@code JMSCorrelationID} = message id.
 *
 * <p>CloudEvents (M-065): JMS has no CloudEvents protocol binding and JMS property names cannot hold {@code -} or
 * {@code :}, so binary mode uses the AMQP binding's {@code cloudEvents_<attr>} properties (as the integration
 * {@code JmsPublisher} does) and {@code cloudEvents_datacontenttype}; structured mode sends the CloudEvent as the text
 * body. SL-142 (C3-06): a message of a tenant that may not be stored yet is not acknowledged: after
 * {@code properties.redeliverBackoffMs} the session is recovered and the broker redelivers it; a MOVED or PURGED
 * tenant's message is parked, then acknowledged.
 */'''),
('''        @Override
        public void send(String tenantId, String eventKey, String messageId, String body) {
            try (JMSContext ctx = cf.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                Destination d = isTopic(ch) ? ctx.createTopic(destinationName(ch)) : ctx.createQueue(destinationName(ch));
                JMSProducer p = ctx.createProducer().setJMSCorrelationID(messageId)
                        .setProperty("messageId", messageId).setProperty("eventKey", eventKey);
                if (tenantId != null) {
                    p.setProperty("tenantId", tenantId);
                }
                p.send(d, body);
            }
        }''', '''        @Override
        public void send(String tenantId, String eventKey, String messageId, String body) {
            send(ChannelAdapters.Outgoing.json(tenantId, eventKey, messageId, body));
        }

        @Override
        public void send(ChannelAdapters.Outgoing m) {
            try (JMSContext ctx = cf.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                Destination d = isTopic(ch) ? ctx.createTopic(destinationName(ch)) : ctx.createQueue(destinationName(ch));
                JMSProducer p = ctx.createProducer().setJMSCorrelationID(m.messageId())
                        .setProperty("messageId", m.messageId()).setProperty("eventKey", m.eventKey());
                if (m.tenantId() != null) {
                    p.setProperty("tenantId", m.tenantId());
                }
                if (!m.binary() && !"application/json".equals(m.contentType())) {
                    p.setProperty("contentType", m.contentType());
                }
                m.cloudEventsOrEmpty().forEach((k, v) -> p.setProperty("cloudEvents_" + k, v));
                p.send(d, m.body());
            }
        }'''),
('''                processor.receive(tenant, ch.key(), body, headers, id);
                m.acknowledge();''', '''                if (ChannelAdapters.deliver(processor, tenant, ch, body, headers, id)) {
                    m.acknowledge();
                } else {
                    // SL-142: the tenant may not take it yet; back off, then let the broker redeliver it
                    ChannelAdapters.sleep(ChannelAdapters.redeliverBackoffMs(ch));
                    session.recover();
                }'''),
])
print("ok")
