// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.conversation;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.core.ports.DocumentStore;
import org.sangam.integration.connector.Json;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * In-app conversations with multimedia attachments, search, audit and live delivery. Only participants read, post or search
 * a conversation; adding a participant also passes the {@code mayJoin} policy check (R19). Hidden conversations do not
 * affect results. Sends are idempotent on (conversation, sender, clientKey). Message bodies are never in the audit trail,
 * only their hash. Retention classes are stored; {@link #purgeBefore} applies a class, on request or on the schedule of
 * {@link ConversationRetention}. Search goes through a trigram index ({@code CH_MESSAGE_GRAM}).
 */
public final class ConversationService {

    public record Attachment(String key, String name, String contentType, long sizeBytes, String sha256) {
    }

    public record Message(String id, String conversationId, String sender, String body, List<Attachment> attachments, Instant at) {
    }

    public record Conversation(String id, String tenantId, String title, String caseRef, String retentionClass) {
    }

    /** Live delivery, for example WebSocket sessions. Called after commit; a delivery failure never loses a message. */
    @FunctionalInterface
    public interface LiveDelivery {
        void deliver(String tenantId, Set<String> recipients, Message message);
    }

    /** Scans a stored attachment candidate; returning false refuses it. */
    @FunctionalInterface
    public interface MalwareScanner {
        boolean clean(String name, byte[] content);
    }

    public static final class AccessDenied extends RuntimeException {
        public AccessDenied() {
            super("CONVERSATION_ACCESS_DENIED");
        }
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final AuditChain audit;
    private final DocumentStore documents;
    private final LiveDelivery live;
    private final MalwareScanner scanner;
    private final BiPredicate<String, String> mayJoin;

    public ConversationService(Dialect dialect, Transactions tx, AuditChain audit, DocumentStore documents, LiveDelivery live, MalwareScanner scanner,
                               BiPredicate<String, String> mayJoin) {
        this.dialect = dialect;
        this.tx = tx;
        this.audit = audit;
        this.documents = documents;
        this.live = live;
        this.scanner = scanner;
        this.mayJoin = mayJoin;
    }

    public Conversation create(String tenantId, String actor, String title, String caseRef, String retentionClass, Set<String> participants) {
        String id = UUID.randomUUID().toString();
        tx.inTransaction(c -> {
            Instant now = Jdbc.dbNow(c, dialect);
            Jdbc.update(c, "INSERT INTO CH_CONVERSATION(id,tenant_id,title,case_ref,retention_class,created_by,created_at) VALUES (?,?,?,?,?,?,?)",
                    id, tenantId, title, caseRef, retentionClass, actor, now);
            Jdbc.update(c, "INSERT INTO CH_PARTICIPANT(conversation_id,principal_id,added_by,added_at) VALUES (?,?,?,?)", id, actor, actor, now);
            audit.append(c, tenantId, chain(id), "CONVERSATION_CREATED", Map.of("conversationId", id, "actor", actor, "caseRef", String.valueOf(caseRef)));
            for (String p : participants) {
                if (!p.equals(actor)) {
                    add(c, tenantId, id, actor, p, now);
                }
            }
            return null;
        });
        return new Conversation(id, tenantId, title, caseRef, retentionClass);
    }

    public void addParticipant(String tenantId, String conversationId, String actor, String principal) {
        tx.inTransaction(c -> {
            requireParticipant(c, tenantId, conversationId, actor);
            add(c, tenantId, conversationId, actor, principal, Jdbc.dbNow(c, dialect));
            return null;
        });
    }

    private void add(Connection c, String tenantId, String id, String actor, String principal, Instant now) {
        if (!mayJoin.test(tenantId, principal)) {
            throw new AccessDenied();
        }
        if (Jdbc.insertIfAbsent(c, dialect, "INSERT INTO CH_PARTICIPANT(conversation_id,principal_id,added_by,added_at) VALUES (?,?,?,?)", id, principal, actor, now)) {
            audit.append(c, tenantId, chain(id), "CONVERSATION_PARTICIPANT_ADDED", Map.of("conversationId", id, "principal", principal, "actor", actor));
        }
    }

    /** Stores the bytes as an immutable document (R74) for a later {@link #send}. */
    public Attachment upload(String tenantId, String conversationId, String actor, String name, String contentType, byte[] content) {
        tx.inTransaction(c -> {
            requireParticipant(c, tenantId, conversationId, actor);
            return null;
        });
        if (!scanner.clean(name, content)) {
            throw new IllegalArgumentException("ATTACHMENT_REFUSED_BY_SCANNER");
        }
        String key = tenantId + "/conversations/" + conversationId + "/" + UUID.randomUUID();
        try {
            DocumentStore.StoredObject o = documents.put(key, new ByteArrayInputStream(content), contentType);
            return new Attachment(o.key(), name, contentType, o.sizeBytes(), o.sha256Hex());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Message send(String tenantId, String conversationId, String sender, String bodyOrNull, List<Attachment> attachments, String clientKey) {
        // SL-87: an attachment-only message has an empty body. It is stored as NULL (Oracle stores '' as NULL anyway, and
        // CH_MESSAGE.body is nullable) and read back as "". A message with neither text nor attachments is refused.
        String body = bodyOrNull == null ? "" : bodyOrNull;
        if (body.isEmpty() && attachments.isEmpty()) {
            throw new IllegalArgumentException("a message needs a body or at least one attachment");
        }
        String prefix = tenantId + "/conversations/" + conversationId + "/";
        for (Attachment a : attachments) {
            if (!a.key().startsWith(prefix)) {
                throw new IllegalArgumentException("attachment does not belong to this conversation");
            }
        }
        record Sent(Message message, boolean created, Set<String> recipients) {
        }
        Sent sent = tx.inTransaction(c -> {
            requireParticipant(c, tenantId, conversationId, sender);
            if (clientKey != null) {
                Optional<Message> dup = Jdbc.one(c, SELECT + " WHERE conversation_id=? AND sender=? AND client_key=?", ConversationService::map, conversationId, sender, clientKey);
                if (dup.isPresent()) {
                    return new Sent(dup.get(), false, Set.of());
                }
            }
            String id = UUID.randomUUID().toString();
            Instant now = Jdbc.dbNow(c, dialect);
            Jdbc.update(c, "INSERT INTO CH_MESSAGE(id,tenant_id,conversation_id,sender,body,attachments,client_key,created_at) VALUES (?,?,?,?,?,?,?,?)",
                    id, tenantId, conversationId, sender, body.isEmpty() ? null : body, Json.write(attachments), clientKey, now);
            List<Object[]> grams = new ArrayList<>();
            for (String g : SearchGrams.of(body)) {
                grams.add(new Object[]{id, tenantId, g});
            }
            if (!grams.isEmpty()) {
                Jdbc.batch(c, "INSERT INTO CH_MESSAGE_GRAM(message_id,tenant_id,gram) VALUES (?,?,?)", grams);
            }
            audit.append(c, tenantId, chain(conversationId), "CONVERSATION_MESSAGE", Map.of("messageId", id, "sender", sender, "bodyHash", Hashes.hex(Hashes.sha256(body)),
                    "attachments", attachments.stream().map(Attachment::sha256).toList()));
            Set<String> to = Set.copyOf(Jdbc.query(c, "SELECT principal_id FROM CH_PARTICIPANT WHERE conversation_id=?", rs -> rs.getString(1), conversationId));
            return new Sent(new Message(id, conversationId, sender, body, attachments, now), true, to);
        });
        if (sent.created()) {
            try {
                live.deliver(tenantId, sent.recipients(), sent.message());
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(ConversationService.class).warn("live delivery failed for message {}: {}", sent.message().id(), e.toString());
            }
        }
        return sent.message();
    }

    public List<Message> history(String tenantId, String conversationId, String actor, int limit) {
        return tx.read(c -> {
            requireParticipant(c, tenantId, conversationId, actor);
            List<Message> all = Jdbc.query(c, SELECT + " WHERE conversation_id=? ORDER BY created_at DESC, id", ConversationService::map, conversationId);
            return List.copyOf(all.subList(0, Math.min(limit, all.size())));
        });
    }

    /**
     * Messages after {@code afterMessageId}, oldest first, for an event-stream reconnect ({@code Last-Event-ID}, the event id
     * being the message id). Message ids are random, so the only order is {@code created_at}: every message stored at or
     * after that message's instant is returned except the message itself. A message stored at the same instant may come
     * again (at least once, never lost); the client dedupes by id. A message whose transaction took its time before the
     * Last-Event-ID message but committed after it is not replayed, so the client re-reads the history after a reconnect as
     * well. An id that is not a message of this conversation (unknown, purged or from another conversation) returns the
     * newest {@code limit} messages, oldest first.
     */
    public List<Message> since(String tenantId, String conversationId, String actor, String afterMessageId, int limit) {
        return tx.read(c -> {
            requireParticipant(c, tenantId, conversationId, actor);
            Optional<Instant> at = afterMessageId == null ? Optional.empty()
                    : Jdbc.one(c, "SELECT created_at FROM CH_MESSAGE WHERE id=? AND conversation_id=?", rs -> Jdbc.instant(rs, "created_at"), afterMessageId, conversationId);
            List<Message> out;
            if (at.isPresent()) {
                out = Jdbc.query(c, SELECT + " WHERE conversation_id=? AND created_at >= ? AND id <> ? ORDER BY created_at, id",
                        ConversationService::map, conversationId, at.get(), afterMessageId);
                return List.copyOf(out.subList(0, Math.min(limit, out.size())));
            }
            out = new ArrayList<>(Jdbc.query(c, SELECT + " WHERE conversation_id=? ORDER BY created_at DESC, id", ConversationService::map, conversationId));
            List<Message> newest = new ArrayList<>(out.subList(0, Math.min(limit, out.size())));
            Collections.reverse(newest);
            return List.copyOf(newest);
        });
    }

    /**
     * Messages stored at or after {@code fromInclusive} (database time; null = from the first message), oldest first, for a
     * live catch-up across nodes (M-040). A message's time is taken before its transaction commits, so a reader that wants
     * every message re-reads a window behind the newest time it has seen and drops ids it already has.
     */
    public List<Message> messagesFrom(String tenantId, String conversationId, String actor, Instant fromInclusive, int limit) {
        return tx.inTransaction(c -> {
            requireParticipant(c, tenantId, conversationId, actor);
            List<Message> out = fromInclusive == null
                    ? Jdbc.query(c, SELECT + " WHERE conversation_id=? ORDER BY created_at, id", ConversationService::map, conversationId)
                    : Jdbc.query(c, SELECT + " WHERE conversation_id=? AND created_at >= ? ORDER BY created_at, id", ConversationService::map,
                            conversationId, fromInclusive);
            return List.copyOf(out.subList(0, Math.min(limit, out.size())));
        });
    }

    /**
     * Case-insensitive substring search across the conversations the actor participates in. A needle of three or more code
     * points is answered through the trigram index {@code CH_MESSAGE_GRAM} ({@link SearchGrams}, NG-12), which only narrows
     * the candidates: the substring condition still decides, so the result is the substring scan's.
     */
    public List<Message> search(String tenantId, String actor, String text, int limit) {
        String needle = "%" + text.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        // COALESCE: an attachment-only message (NULL body, SL-87) still matches an empty search text, as '' did before
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE tenant_id=? AND LOWER(COALESCE(body, '')) LIKE ? ESCAPE '\\' AND conversation_id IN ")
                .append("(SELECT conversation_id FROM CH_PARTICIPANT WHERE principal_id=?)");
        List<Object> params = new ArrayList<>(List.of(tenantId, needle, actor));
        Set<String> grams = SearchGrams.forQuery(text);
        if (!grams.isEmpty()) {
            sql.append(" AND id IN (SELECT message_id FROM CH_MESSAGE_GRAM WHERE tenant_id=? AND gram IN (")
                    .append(String.join(",", Collections.nCopies(grams.size(), "?")))
                    .append(") GROUP BY message_id HAVING COUNT(DISTINCT gram)=?)");
            params.add(tenantId);
            params.addAll(grams);
            params.add(grams.size());
        }
        sql.append(" ORDER BY created_at DESC, id");
        return tx.read(c -> {
            List<Message> all = Jdbc.query(c, sql.toString(), ConversationService::map, params.toArray());
            return List.copyOf(all.subList(0, Math.min(limit, all.size())));
        });
    }

    /** Removes messages of one retention class older than {@code cutoff}, from the store and the tables; returns the count. */
    public int purgeBefore(String tenantId, String retentionClass, Instant cutoff, String actor) {
        List<Message> old = tx.inTransaction(c -> Jdbc.query(c, SELECT + " WHERE tenant_id=? AND created_at < ? AND conversation_id IN "
                + "(SELECT id FROM CH_CONVERSATION WHERE tenant_id=? AND retention_class=?)", ConversationService::map, tenantId, cutoff, tenantId, retentionClass));
        for (Message m : old) {
            for (Attachment a : m.attachments()) {
                try {
                    documents.delete(a.key());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return tx.inTransaction(c -> {
            int n = 0;
            for (Message m : old) {
                Jdbc.update(c, "DELETE FROM CH_MESSAGE_GRAM WHERE message_id=?", m.id());
                n += Jdbc.update(c, "DELETE FROM CH_MESSAGE WHERE id=?", m.id());
            }
            audit.append(c, tenantId, "conversations:" + tenantId, "CONVERSATION_PURGED", Map.of("class", retentionClass, "before", cutoff.toString(), "count", n, "actor", actor));
            return n;
        });
    }

    private void requireParticipant(Connection c, String tenantId, String id, String actor) {
        boolean ok = Jdbc.one(c, "SELECT 1 FROM CH_PARTICIPANT p JOIN CH_CONVERSATION k ON k.id=p.conversation_id WHERE p.conversation_id=? AND p.principal_id=? AND k.tenant_id=?",
                rs -> true, id, actor, tenantId).isPresent();
        if (!ok) {
            throw new AccessDenied();
        }
    }

    private static String chain(String id) {
        return "conversation:" + id;
    }

    private static final String SELECT = "SELECT id,conversation_id,sender,body,attachments,created_at FROM CH_MESSAGE";

    private static Message map(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<Attachment> atts = new ArrayList<>();
        for (var n : Json.tree(rs.getBytes(5))) {
            atts.add(Json.MAPPER.convertValue(n, Attachment.class));
        }
        String body = rs.getString(4);
        return new Message(rs.getString(1), rs.getString(2), rs.getString(3), body == null ? "" : body, atts, Jdbc.instant(rs, "created_at"));
    }

    /** Conversations the actor participates in, newest first; {@code caseRefOrNull} narrows to one case. */
    public List<Conversation> list(String tenantId, String actor, String caseRefOrNull, int limit) {
        String sql = "SELECT k.id,k.tenant_id,k.title,k.case_ref,k.retention_class FROM CH_CONVERSATION k JOIN CH_PARTICIPANT p ON p.conversation_id=k.id "
                + "WHERE k.tenant_id=? AND p.principal_id=?" + (caseRefOrNull == null ? "" : " AND k.case_ref=?") + " ORDER BY k.created_at DESC";
        Object[] params = caseRefOrNull == null ? new Object[]{tenantId, actor} : new Object[]{tenantId, actor, caseRefOrNull};
        List<Conversation> all = tx.read(c -> Jdbc.query(c, sql, rs -> new Conversation(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), params));
        return all.size() > limit ? List.copyOf(all.subList(0, limit)) : all;
    }
}
