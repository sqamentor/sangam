// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.mail;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.core.ports.DocumentStore;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.SecretResolver;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Inbound email as a channel: reads unseen mail, stores raw text and attachments as documents (R74) after a malware scan, then
 * hands an event to the sink. Idempotent on (channel, Message-ID) in {@code CH_INBOUND_SEEN}. A message the sink or parser
 * refuses is recorded by {@link Poison} and not retried. {@link Imap} is the live source; {@link Simulated} serves the simulator
 * profile. Microsoft Graph polling is not implemented; IMAP access to the same mailbox is the supported route.
 */
public final class InboundMail {

    public record Attachment(String name, String contentType, byte[] content) {
    }

    public record Mail(String messageId, String from, String subject, String text, List<Attachment> attachments) {
    }

    /** Where mail comes from. {@code acknowledge} marks one message as handled at the source. */
    public interface Source extends AutoCloseable {
        List<Mail> fetch(int max) throws Exception;

        void acknowledge(String messageId) throws Exception;

        @Override
        void close();
    }

    public record StoredAttachment(String documentKey, String name, String contentType, long sizeBytes, String sha256) {
    }

    /** The normal event pipeline, for example {@code InboundEventProcessor.receive}. */
    @FunctionalInterface
    public interface Sink {
        void accept(String tenantId, String channel, Mail mail, List<StoredAttachment> attachments);
    }

    @FunctionalInterface
    public interface Scanner {
        boolean clean(String name, byte[] content);
    }

    @FunctionalInterface
    public interface Poison {
        void record(String tenantId, String channel, String messageId, String reason);
    }

    public record PollResult(int accepted, int duplicates, int poisoned) {
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final AuditChain audit;
    private final DocumentStore documents;
    private final Scanner scanner;
    private final Sink sink;
    private final Poison poison;

    public InboundMail(Dialect dialect, Transactions tx, AuditChain audit, DocumentStore documents, Scanner scanner, Sink sink, Poison poison) {
        this.dialect = dialect;
        this.tx = tx;
        this.audit = audit;
        this.documents = documents;
        this.scanner = scanner;
        this.sink = sink;
        this.poison = poison;
    }

    public PollResult poll(String tenantId, String channel, Source source, int max) throws Exception {
        int ok = 0;
        int dup = 0;
        int bad = 0;
        for (Mail m : source.fetch(max)) {
            if (m.messageId() == null || m.messageId().isBlank()) {
                poison.record(tenantId, channel, "(none)", "message has no Message-ID");
                bad++;
                continue;
            }
            if (seen(tenantId, channel, m.messageId())) {
                source.acknowledge(m.messageId());
                dup++;
                continue;
            }
            try {
                List<StoredAttachment> stored = store(tenantId, channel, m);
                sink.accept(tenantId, channel, m, stored);
                markSeen(tenantId, channel, m, stored.size());
                ok++;
            } catch (IOException e) {
                throw e;    // store unavailable: leave unseen so that the next poll retries
            } catch (RuntimeException e) {
                poison.record(tenantId, channel, m.messageId(), e.getMessage());
                markSeen(tenantId, channel, m, 0);
                bad++;
            }
            source.acknowledge(m.messageId());
        }
        return new PollResult(ok, dup, bad);
    }

    private boolean seen(String tenantId, String channel, String id) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT 1 FROM CH_INBOUND_SEEN WHERE tenant_id=? AND channel=? AND message_id=?", rs -> true, tenantId, channel, id).isPresent());
    }

    private void markSeen(String tenantId, String channel, Mail m, int attachments) {
        tx.inTransaction(c -> {
            Jdbc.insertIfAbsent(c, dialect, "INSERT INTO CH_INBOUND_SEEN(tenant_id,channel,message_id,seen_at) VALUES (?,?,?,?)", tenantId, channel, m.messageId(), Jdbc.dbNow(c, dialect));
            audit.append(c, tenantId, "mail:" + channel, "INBOUND_MAIL", Map.of("messageId", m.messageId(), "attachments", attachments));
            return null;
        });
    }

    private List<StoredAttachment> store(String tenantId, String channel, Mail m) throws IOException {
        List<StoredAttachment> out = new ArrayList<>();
        for (Attachment a : m.attachments()) {
            if (!scanner.clean(a.name(), a.content())) {
                throw new IllegalArgumentException("attachment refused by scanner: " + a.name());
            }
            String key = tenantId + "/mail/" + channel + "/" + UUID.randomUUID();
            DocumentStore.StoredObject o = documents.put(key, new ByteArrayInputStream(a.content()), a.contentType());
            out.add(new StoredAttachment(o.key(), a.name(), a.contentType(), o.sizeBytes(), o.sha256Hex()));
        }
        return out;
    }

    // ---- sources -------------------------------------------------------------------------------

    /** In-process mailbox for the simulator profile. */
    public static final class Simulated implements Source {
        private final Deque<Mail> inbox = new ArrayDeque<>();

        public synchronized Simulated deliver(Mail m) {
            inbox.add(m);
            return this;
        }

        @Override
        public synchronized List<Mail> fetch(int max) {
            return inbox.stream().limit(max).toList();
        }

        @Override
        public synchronized void acknowledge(String messageId) {
            inbox.removeIf(m -> messageId.equals(m.messageId()));
        }

        @Override
        public void close() {
        }
    }

    /** IMAPS source (Jakarta Mail). The host passes the egress policy, TLS is required, the password is a secret reference. */
    public static final class Imap implements Source {
        public record Config(String tenantId, String host, int port, String user, String passwordRef, String folder) {
        }

        private final Config config;
        private final EgressPolicy egress;
        private final SecretResolver secrets;
        private Store store;
        private Folder folder;
        private final java.util.Map<String, Message> open = new java.util.HashMap<>();

        public Imap(Config config, EgressPolicy egress, SecretResolver secrets) {
            this.config = config;
            this.egress = egress;
            this.secrets = secrets;
        }

        @Override
        public List<Mail> fetch(int max) throws Exception {
            egress.check(config.tenantId(), URI.create("https://" + config.host() + "/"));
            Properties p = new Properties();
            p.put("mail.imaps.ssl.checkserveridentity", "true");
            p.put("mail.imaps.connectiontimeout", "10000");
            p.put("mail.imaps.timeout", "30000");
            if (store == null || !store.isConnected()) {
                store = Session.getInstance(p).getStore("imaps");
                store.connect(config.host(), config.port(), config.user(), secrets.require(config.passwordRef()));
                folder = store.getFolder(config.folder());
                folder.open(Folder.READ_WRITE);
            }
            List<Mail> out = new ArrayList<>();
            open.clear();
            Message[] unseen = folder.search(new jakarta.mail.search.FlagTerm(new Flags(Flags.Flag.SEEN), false));
            for (int i = 0; i < Math.min(max, unseen.length); i++) {
                Message m = unseen[i];
                String[] ids = m.getHeader("Message-ID");
                String id = ids == null || ids.length == 0 ? null : ids[0];
                Address[] from = m.getFrom();
                List<Attachment> atts = new ArrayList<>();
                StringBuilder text = new StringBuilder();
                walk(m, text, atts);
                out.add(new Mail(id, from == null || from.length == 0 ? null : from[0].toString(), m.getSubject(), text.toString(), atts));
                if (id != null) {
                    open.put(id, m);
                }
            }
            return out;
        }

        private static void walk(Part part, StringBuilder text, List<Attachment> atts) throws MessagingException, IOException {
            if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null) {
                atts.add(new Attachment(part.getFileName(), part.getContentType(), part.getInputStream().readAllBytes()));
            } else if (part.isMimeType("text/plain")) {
                text.append(part.getContent());
            } else if (part.isMimeType("multipart/*")) {
                Multipart mp = (Multipart) part.getContent();
                for (int i = 0; i < mp.getCount(); i++) {
                    BodyPart bp = mp.getBodyPart(i);
                    walk(bp, text, atts);
                }
            }
        }

        @Override
        public void acknowledge(String messageId) throws MessagingException {
            Message m = open.remove(messageId);
            if (m != null) {
                m.setFlag(Flags.Flag.SEEN, true);
            }
        }

        @Override
        public void close() {
            try {
                if (folder != null && folder.isOpen()) {
                    folder.close(false);
                }
                if (store != null) {
                    store.close();
                }
            } catch (MessagingException e) {
                org.slf4j.LoggerFactory.getLogger(Imap.class).warn("IMAP close: {}", e.toString());
            }
        }
    }
}
