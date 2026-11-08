// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.register;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.registry.RegistryContext;
import org.sangam.registry.RegistryException;
import org.sangam.registry.RegistryPorts;
import org.sangam.registry.numbering.NumberSeriesDefinition;
import org.sangam.registry.numbering.NumberingService;
import org.sangam.registry.support.Codes;
import org.sangam.registry.support.Db;
import org.sangam.registry.support.Json;
import org.sangam.registry.support.Maps;
import org.sangam.registry.support.RegistryAudit;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Statutory register books (N09): append-only, gap-free numbered and hash-chained.
 *
 * <ul>
 *   <li><b>Append-only.</b> There is no update or delete path. A mistake is fixed by a countersigned CORRECTION entry
 *       that references the entry it corrects; both stay in the book.</li>
 *   <li><b>Two chains.</b> Each entry carries {@code prev_hash} / {@code entry_hash} over its canonical envelope
 *       (the book's own chain, verifiable from the book table alone), and the same fact is appended to the core
 *       {@link AuditChain} with the book id as chain root (X.2.3.3), so a rewrite of the book table is detected
 *       against the audit chain too.</li>
 *   <li><b>Locks.</b> The book row is locked first, then the gap-free counter, then the audit root; every register
 *       operation takes them in that order.</li>
 *   <li><b>Period close.</b> Closing a book freezes it and records the head hash as the close digest.</li>
 *   <li><b>Certified extracts.</b> A range of entries is signed through the {@link RegistryPorts.SignerPort}
 *       (eSign / HSM) with a verification code, and optionally rendered as a document (PDF/A) by the
 *       {@link RegistryPorts.DocumentPort}.</li>
 * </ul>
 */
public final class RegisterBookService {

    public record Book(String id, String registerKey, String scopeKey, String periodKey, String status, long entryCount,
                       String headHashHex, Instant openedAt, Instant closedAt, String closedBy, String closeDigestHex) {
        public Map<String, Object> toMap() {
            return Maps.of("id", id, "registerKey", registerKey, "scope", scopeKey, "period", periodKey, "status", status,
                    "entryCount", entryCount, "headHash", headHashHex, "openedAt", openedAt, "closedAt", closedAt,
                    "closedBy", closedBy, "closeDigest", closeDigestHex);
        }
    }

    public record Entry(String bookId, long entryNo, String entryNumber, String entryType, Long correctsEntryNo,
                        Map<String, Object> columns, String refType, String refId, String madeBy,
                        String countersignedBy, Instant recordedAt, String prevHashHex, String entryHashHex,
                        String auditEventId) {
        public Map<String, Object> toMap() {
            return Maps.of("bookId", bookId, "entryNo", entryNo, "number", entryNumber, "type", entryType,
                    "correctsEntryNo", correctsEntryNo, "columns", columns, "refType", refType, "refId", refId,
                    "madeBy", madeBy, "countersignedBy", countersignedBy, "recordedAt", recordedAt,
                    "prevHash", prevHashHex, "entryHash", entryHashHex, "auditEventId", auditEventId);
        }
    }

    public record VerifyResult(boolean ok, long entriesChecked, Long firstBadEntry, String reason,
                               AuditChain.VerifyResult audit) {
    }

    /** A certified extract; {@code verificationCode} is returned only when the extract is issued. */
    public record Extract(String id, String bookId, long fromEntry, long toEntry, String digestHex, String algorithm,
                          String keyId, String signatureB64, String documentRef, Instant issuedAt, String issuedBy,
                          String verificationCode, Map<String, Object> payload) {
        public Map<String, Object> toMap() {
            return Maps.of("id", id, "bookId", bookId, "fromEntry", fromEntry, "toEntry", toEntry, "digest", digestHex,
                    "algorithm", algorithm, "keyId", keyId, "signature", signatureB64, "documentRef", documentRef,
                    "issuedAt", issuedAt, "issuedBy", issuedBy, "verificationCode", verificationCode, "payload", payload);
        }
    }

    public record ExtractVerification(boolean valid, String status, String extractId, String bookId, long fromEntry,
                                      long toEntry, boolean laterCorrections, Instant issuedAt) {
    }

    private final Dialect dialect;
    private final NumberingService numbering;
    private final RegistryAudit audit;
    private final RegistryPorts ports;

    public RegisterBookService(Dialect dialect, NumberingService numbering, RegistryAudit audit, RegistryPorts ports) {
        this.dialect = dialect;
        this.numbering = numbering;
        this.audit = audit;
        this.ports = ports;
    }

    // ------------------------------------------------------------------------------------------ definitions

    public RegisterDefinition define(Connection c, RegistryContext ctx, Map<String, Object> definition) {
        RegisterDefinition parsed = RegisterDefinition.from(definition, 1);
        NumberSeriesDefinition series = numbering.series(c, ctx.tenant(), parsed.series());
        if (!series.gapFree()) {
            throw RegistryException.invalid("a statutory register needs a GAP_FREE series; " + parsed.series() + " is "
                    + series.gapPolicy());
        }
        Instant now = Db.now(c, dialect);
        int version = Jdbc.one(c, "SELECT version FROM REG_REGISTER_DEF WHERE tenant_id=? AND register_key=?",
                rs -> rs.getInt(1), ctx.tenant(), parsed.registerKey()).orElse(0) + 1;
        RegisterDefinition def = RegisterDefinition.from(definition, version);
        if (version == 1) {
            if (!Jdbc.insertIfAbsent(c, dialect, "INSERT INTO REG_REGISTER_DEF(tenant_id, register_key, version, definition_json,"
                            + " updated_at, updated_by) VALUES (?,?,?,?,?,?)", ctx.tenant(), def.registerKey(), 1,
                    Json.write(def.toMap()), now, ctx.actor())) {
                throw RegistryException.conflict("register " + def.registerKey() + " was defined concurrently; retry");
            }
        } else {
            Jdbc.update(c, "UPDATE REG_REGISTER_DEF SET version=?, definition_json=?, updated_at=?, updated_by=?"
                            + " WHERE tenant_id=? AND register_key=? AND version=?", version, Json.write(def.toMap()), now,
                    ctx.actor(), ctx.tenant(), def.registerKey(), version - 1);
        }
        audit.record(c, ctx, registerRoot(ctx.tenant(), def.registerKey()), "REGISTER_DEFINED", def.toMap());
        return def;
    }

    public RegisterDefinition definition(Connection c, String tenant, String registerKey) {
        return Jdbc.one(c, "SELECT definition_json, version FROM REG_REGISTER_DEF WHERE tenant_id=? AND register_key=?",
                        rs -> RegisterDefinition.from(Json.map(rs.getString(1)), rs.getInt(2)), tenant, registerKey)
                .orElseThrow(() -> RegistryException.notFound("register", registerKey));
    }

    public List<RegisterDefinition> definitions(Connection c, String tenant) {
        return Jdbc.query(c, "SELECT definition_json, version FROM REG_REGISTER_DEF WHERE tenant_id=? ORDER BY register_key",
                rs -> RegisterDefinition.from(Json.map(rs.getString(1)), rs.getInt(2)), tenant);
    }

    // ------------------------------------------------------------------------------------------------ books

    private static final String BOOK_COLS = "id, register_key, scope_key, period_key, status, entry_count, head_hash,"
            + " opened_at, closed_at, closed_by, close_digest";

    private static Book mapBook(java.sql.ResultSet rs) throws java.sql.SQLException {
        byte[] head = rs.getBytes("head_hash");
        byte[] close = rs.getBytes("close_digest");
        return new Book(rs.getString("id"), rs.getString("register_key"), rs.getString("scope_key"),
                rs.getString("period_key"), rs.getString("status"), rs.getLong("entry_count"),
                head == null ? null : Hashes.hex(head), Jdbc.instant(rs, "opened_at"), Jdbc.instant(rs, "closed_at"),
                rs.getString("closed_by"), close == null ? null : Hashes.hex(close));
    }

    /** The book for (register, scope, date's period), created if absent, locked for the rest of the transaction. */
    private Book lockBook(Connection c, RegistryContext ctx, RegisterDefinition def, String scopeKey, String periodKey) {
        String where = "tenant_id=? AND register_key=? AND scope_key=? AND period_key=?";
        var existing = Jdbc.one(c, dialect.lockingSelect(BOOK_COLS, "REG_REGISTER_BOOK", where), RegisterBookService::mapBook,
                ctx.tenant(), def.registerKey(), scopeKey, periodKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        String id = Db.newId();
        Jdbc.insertIfAbsent(c, dialect, "INSERT INTO REG_REGISTER_BOOK(id, tenant_id, register_key, scope_key, period_key,"
                        + " status, entry_count, opened_at) VALUES (?,?,?,?,?,?,?," + dialect.now() + ")",
                id, ctx.tenant(), def.registerKey(), scopeKey, periodKey, "OPEN", 0L);
        Book b = Jdbc.one(c, dialect.lockingSelect(BOOK_COLS, "REG_REGISTER_BOOK", where), RegisterBookService::mapBook,
                ctx.tenant(), def.registerKey(), scopeKey, periodKey).orElseThrow();
        if (b.id().equals(id)) {
            audit.record(c, ctx, b.id(), "REGISTER_BOOK_OPENED", Maps.of("registerKey", def.registerKey(),
                    "scope", scopeKey, "period", periodKey));
        }
        return b;
    }

    private Book lockBookById(Connection c, RegistryContext ctx, String bookId) {
        return Jdbc.one(c, dialect.lockingSelect(BOOK_COLS, "REG_REGISTER_BOOK", "id=? AND tenant_id=?"),
                RegisterBookService::mapBook, bookId, ctx.tenant()).orElseThrow(() -> RegistryException.notFound("register book", bookId));
    }

    public Book book(Connection c, RegistryContext ctx, String bookId) {
        return Jdbc.one(c, "SELECT " + BOOK_COLS + " FROM REG_REGISTER_BOOK WHERE id=? AND tenant_id=?",
                RegisterBookService::mapBook, bookId, ctx.tenant()).orElseThrow(() -> RegistryException.notFound("register book", bookId));
    }

    public List<Book> books(Connection c, RegistryContext ctx, String registerKey, String scope, int page, int size) {
        String where = "b.tenant_id=? AND b.register_key=?" + (scope == null ? "" : " AND b.scope_key=?");
        List<Object> params = scope == null ? List.of(ctx.tenant(), registerKey) : List.of(ctx.tenant(), registerKey, scope);
        return Db.page(c, dialect, "SELECT b." + BOOK_COLS.replace(", ", ", b.") + " FROM REG_REGISTER_BOOK b", where, params,
                null, "b.period_key DESC, b.scope_key", page, size, RegisterBookService::mapBook);
    }

    // ---------------------------------------------------------------------------------------------- entries

    /**
     * Appends an entry: allocates the next gap-free number of the register's series for the scope and the date's
     * period, validates the columns, and chains the entry.
     *
     * @param date business date (selects the book period); null = today in the tenant zone
     */
    public Entry append(Connection c, RegistryContext ctx, String registerKey, String scope, LocalDate date,
                        Map<String, Object> columns, String refType, String refId) {
        RegisterDefinition def = definition(c, ctx.tenant(), registerKey);
        if (def.scopeRequired() && (scope == null || scope.isBlank())) {
            throw RegistryException.invalid("register " + registerKey + " needs a scope (office or unit)");
        }
        String scopeKey = scope == null || scope.isBlank() ? "-" : scope;
        LocalDate d = date == null ? LocalDate.now(ctx.zone()) : date;
        NumberSeriesDefinition series = numbering.series(c, ctx.tenant(), def.series());
        Map<String, Object> cols = def.columns().validate(columns, null);
        Book book = lockBook(c, ctx, def, scopeKey, series.periodKey(d));
        requireOpen(book);
        NumberingService.Allocation n = numbering.allocate(c, ctx, def.series(), scope, d, cols, "register-entry", book.id());
        return write(c, ctx, book, n.formatted(), "ENTRY", null, cols, refType, refId, null);
    }

    /**
     * Appends a countersigned correction of an earlier entry. The original stays unchanged; readers apply the latest
     * correction. The countersigning person must differ from the maker of the correction.
     */
    public Entry correct(Connection c, RegistryContext ctx, String bookId, long entryNo, Map<String, Object> columns,
                         String reason, String countersignedBy) {
        if (reason == null || reason.isBlank()) {
            throw RegistryException.invalid("a correction needs a reason");
        }
        Book book = lockBookById(c, ctx, bookId);
        requireOpen(book);
        RegisterDefinition def = definition(c, ctx.tenant(), book.registerKey());
        if (def.correctionCountersign()) {
            if (countersignedBy == null || countersignedBy.isBlank()) {
                throw RegistryException.invalid("a correction in register " + def.registerKey() + " must be countersigned");
            }
            if (countersignedBy.equals(ctx.actor())) {
                throw RegistryException.forbidden("the countersigning person must differ from the maker (SoD)");
            }
        }
        Entry original = entry(c, ctx, bookId, entryNo);
        if (!"ENTRY".equals(original.entryType())) {
            throw RegistryException.invalid("only an ENTRY can be corrected; correct the original entry " + original.correctsEntryNo());
        }
        Map<String, Object> merged = new LinkedHashMap<>(original.columns());
        if (columns != null) {
            merged.putAll(columns);
        }
        Map<String, Object> cols = def.columns().validate(merged, null);
        cols.put("_correctionReason", reason);
        long priorCorrections = Jdbc.one(c, "SELECT COUNT(*) FROM REG_REGISTER_ENTRY WHERE book_id=? AND corrects_entry_no=?",
                rs -> rs.getLong(1), bookId, entryNo).orElse(0L);
        return write(c, ctx, book, original.entryNumber() + "/C" + (priorCorrections + 1), "CORRECTION", entryNo, cols,
                original.refType(), original.refId(), countersignedBy);
    }

    private void requireOpen(Book book) {
        if (!"OPEN".equals(book.status())) {
            throw new RegistryException(RegistryException.Code.CLOSED, "register book " + book.id() + " is " + book.status());
        }
    }

    private Entry write(Connection c, RegistryContext ctx, Book book, String number, String type, Long corrects,
                        Map<String, Object> cols, String refType, String refId, String countersignedBy) {
        long entryNo = book.entryCount() + 1;
        byte[] prev = book.headHashHex() == null ? genesis(ctx.tenant(), book.id()) : unhex(book.headHashHex());
        Instant now = Db.now(c, dialect);
        Map<String, Object> envelope = envelope(book.id(), entryNo, number, type, corrects, cols, refType, refId,
                ctx.actor(), countersignedBy, now);
        byte[] hash = Hashes.sha256(prev, CanonicalJson.write(envelope).getBytes(StandardCharsets.UTF_8));
        AuditChain.AuditEvent ev = audit.record(c, ctx, book.id(), "CORRECTION".equals(type) ? "REGISTER_ENTRY_CORRECTED"
                : "REGISTER_ENTRY_APPENDED", Maps.of("registerKey", book.registerKey(), "entryNo", entryNo, "number", number,
                "corrects", corrects, "entryHash", Hashes.hex(hash), "countersignedBy", countersignedBy));
        Jdbc.update(c, "INSERT INTO REG_REGISTER_ENTRY(book_id, entry_no, tenant_id, entry_number, entry_type, corrects_entry_no,"
                        + " columns_json, ref_type, ref_id, made_by, countersigned_by, recorded_at, prev_hash, entry_hash, audit_event_id)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                book.id(), entryNo, ctx.tenant(), number, type, corrects, Json.write(cols), refType, refId, ctx.actor(),
                countersignedBy, now, prev, hash, ev.eventId());
        Jdbc.update(c, "UPDATE REG_REGISTER_BOOK SET entry_count=?, head_hash=? WHERE id=? AND tenant_id=?",
                entryNo, hash, book.id(), ctx.tenant());
        return new Entry(book.id(), entryNo, number, type, corrects, cols, refType, refId, ctx.actor(), countersignedBy, now,
                Hashes.hex(prev), Hashes.hex(hash), ev.eventId());
    }

    private static Map<String, Object> envelope(String bookId, long entryNo, String number, String type, Long corrects,
                                                Map<String, Object> cols, String refType, String refId, String madeBy,
                                                String countersignedBy, Instant at) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("v", 1);
        e.put("bookId", bookId);
        e.put("entryNo", entryNo);
        e.put("number", number);
        e.put("type", type);
        e.put("corrects", corrects);
        e.put("columns", Json.canonical(cols));
        e.put("refType", refType);
        e.put("refId", refId);
        e.put("madeBy", madeBy);
        e.put("countersignedBy", countersignedBy);
        e.put("recordedAt", at.toString());
        return e;
    }

    static byte[] genesis(String tenant, String bookId) {
        return Hashes.sha256("sangam-register-v1:" + tenant + ":" + bookId);
    }

    private static final String ENTRY_COLS = "book_id, entry_no, entry_number, entry_type, corrects_entry_no, columns_json,"
            + " ref_type, ref_id, made_by, countersigned_by, recorded_at, prev_hash, entry_hash, audit_event_id";

    private static Entry mapEntry(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Entry(rs.getString("book_id"), rs.getLong("entry_no"), rs.getString("entry_number"),
                rs.getString("entry_type"), Db.longOrNull(rs, "corrects_entry_no"), Json.map(rs.getString("columns_json")),
                rs.getString("ref_type"), rs.getString("ref_id"), rs.getString("made_by"), rs.getString("countersigned_by"),
                Jdbc.instant(rs, "recorded_at"), Hashes.hex(rs.getBytes("prev_hash")), Hashes.hex(rs.getBytes("entry_hash")),
                rs.getString("audit_event_id"));
    }

    public Entry entry(Connection c, RegistryContext ctx, String bookId, long entryNo) {
        return Jdbc.one(c, "SELECT " + ENTRY_COLS + " FROM REG_REGISTER_ENTRY WHERE book_id=? AND entry_no=? AND tenant_id=?",
                RegisterBookService::mapEntry, bookId, entryNo, ctx.tenant())
                .orElseThrow(() -> RegistryException.notFound("register entry", bookId + "#" + entryNo));
    }

    public List<Entry> entries(Connection c, RegistryContext ctx, String bookId, long fromEntry, long toEntry) {
        book(c, ctx, bookId);
        return Jdbc.query(c, "SELECT " + ENTRY_COLS + " FROM REG_REGISTER_ENTRY WHERE book_id=? AND tenant_id=?"
                        + " AND entry_no >= ? AND entry_no <= ? ORDER BY entry_no", RegisterBookService::mapEntry,
                bookId, ctx.tenant(), fromEntry, toEntry);
    }

    public List<Entry> entriesFor(Connection c, RegistryContext ctx, String refType, String refId) {
        return Jdbc.query(c, "SELECT " + ENTRY_COLS + " FROM REG_REGISTER_ENTRY WHERE tenant_id=? AND ref_type=? AND ref_id=?"
                + " ORDER BY recorded_at", RegisterBookService::mapEntry, ctx.tenant(), refType, refId);
    }

    /** An entry with its latest correction applied (the "effective" reading of the book). */
    public Map<String, Object> effective(Connection c, RegistryContext ctx, String bookId, long entryNo) {
        Entry original = entry(c, ctx, bookId, entryNo);
        List<Entry> corr = Jdbc.query(c, "SELECT " + ENTRY_COLS + " FROM REG_REGISTER_ENTRY WHERE book_id=? AND tenant_id=?"
                + " AND corrects_entry_no=? ORDER BY entry_no", RegisterBookService::mapEntry, bookId, ctx.tenant(), entryNo);
        Map<String, Object> out = original.toMap();
        if (!corr.isEmpty()) {
            Entry last = corr.get(corr.size() - 1);
            out.put("columns", last.columns());
            out.put("correctedBy", last.entryNo());
            out.put("corrections", corr.stream().map(Entry::entryNo).toList());
        }
        return out;
    }

    // ----------------------------------------------------------------------------------- close and verify

    /** Closes (freezes) a book at period end; its head hash becomes the close digest. */
    public Book close(Connection c, RegistryContext ctx, String bookId, String reason) {
        Book b = lockBookById(c, ctx, bookId);
        requireOpen(b);
        Instant now = Db.now(c, dialect);
        byte[] digest = b.headHashHex() == null ? genesis(ctx.tenant(), bookId) : unhex(b.headHashHex());
        Jdbc.update(c, "UPDATE REG_REGISTER_BOOK SET status='CLOSED', closed_at=?, closed_by=?, close_digest=? WHERE id=? AND tenant_id=?",
                now, ctx.actor(), digest, bookId, ctx.tenant());
        audit.record(c, ctx, bookId, "REGISTER_BOOK_CLOSED", Maps.of("registerKey", b.registerKey(), "entries", b.entryCount(),
                "closeDigest", Hashes.hex(digest), "reason", reason));
        return book(c, ctx, bookId);
    }

    /** Recomputes the book chain from its rows and verifies the audit chain of the book root. */
    public VerifyResult verify(Connection c, RegistryContext ctx, String bookId) {
        Book b = book(c, ctx, bookId);
        List<Entry> all = entries(c, ctx, bookId, 1, Long.MAX_VALUE);
        byte[] prev = genesis(ctx.tenant(), bookId);
        long expectedNo = 1;
        for (Entry e : all) {
            if (e.entryNo() != expectedNo) {
                return new VerifyResult(false, expectedNo - 1, expectedNo, "missing entry " + expectedNo, null);
            }
            if (!Hashes.hex(prev).equals(e.prevHashHex())) {
                return new VerifyResult(false, expectedNo - 1, e.entryNo(), "prev_hash does not link", null);
            }
            Map<String, Object> env = envelope(bookId, e.entryNo(), e.entryNumber(), e.entryType(), e.correctsEntryNo(),
                    e.columns(), e.refType(), e.refId(), e.madeBy(), e.countersignedBy(), e.recordedAt());
            byte[] h = Hashes.sha256(prev, CanonicalJson.write(env).getBytes(StandardCharsets.UTF_8));
            if (!Hashes.hex(h).equals(e.entryHashHex())) {
                return new VerifyResult(false, expectedNo - 1, e.entryNo(), "entry hash mismatch (row altered)", null);
            }
            prev = h;
            expectedNo++;
        }
        if (all.size() != b.entryCount()) {
            return new VerifyResult(false, all.size(), (long) all.size() + 1, "book entry count differs from rows", null);
        }
        if (b.headHashHex() != null && !b.headHashHex().equals(Hashes.hex(prev))) {
            return new VerifyResult(false, all.size(), null, "book head hash differs from last entry", null);
        }
        AuditChain.VerifyResult a = audit.verify(c, ctx.tenant(), bookId);
        return new VerifyResult(a.ok(), all.size(), null, a.ok() ? null : "audit chain: " + a.reason(), a);
    }

    // ---------------------------------------------------------------------------------- certified extracts

    /**
     * Issues a signed, certified extract of entries {@code fromEntry..toEntry} (inclusive). The payload is canonical
     * JSON; its SHA-256 digest is signed. The verification code is returned once and stored only as a hash.
     */
    public Extract certifiedExtract(Connection c, RegistryContext ctx, String bookId, long fromEntry, long toEntry,
                                    String purpose) {
        Book b = book(c, ctx, bookId);
        if (fromEntry < 1 || toEntry < fromEntry || toEntry > b.entryCount()) {
            throw RegistryException.invalid("entry range " + fromEntry + ".." + toEntry + " outside 1.." + b.entryCount());
        }
        RegisterDefinition def = definition(c, ctx.tenant(), b.registerKey());
        List<Entry> rows = entries(c, ctx, bookId, fromEntry, toEntry);
        String id = Db.newId();
        Instant now = Db.now(c, dialect);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", 1);
        payload.put("extractId", id);
        payload.put("tenant", ctx.tenant());
        payload.put("register", def.registerKey());
        payload.put("registerTitle", def.title());
        payload.put("scope", b.scopeKey());
        payload.put("period", b.periodKey());
        payload.put("bookId", bookId);
        payload.put("fromEntry", fromEntry);
        payload.put("toEntry", toEntry);
        payload.put("throughHash", rows.get(rows.size() - 1).entryHashHex());
        List<Object> list = new ArrayList<>();
        for (Entry e : rows) {
            list.add(Maps.of("entryNo", e.entryNo(), "number", e.entryNumber(), "type", e.entryType(),
                    "corrects", e.correctsEntryNo(), "columns", Json.canonical(e.columns()),
                    "recordedAt", e.recordedAt().toString(), "entryHash", e.entryHashHex()));
        }
        payload.put("entries", list);
        payload.put("issuedAt", now.toString());
        payload.put("issuedBy", ctx.actor());
        if (purpose != null) {
            payload.put("purpose", purpose);
        }
        byte[] canonical = CanonicalJson.write(payload).getBytes(StandardCharsets.UTF_8);
        byte[] digest = Hashes.sha256(canonical);
        RegistryPorts.Signature sig = ports.signer().sign(ctx.tenant(), "register-extract", canonical);
        String code = Codes.newCode();
        Jdbc.update(c, "INSERT INTO REG_VERIFICATION(code_hash, tenant_id, target_kind, target_id, status, issued_at)"
                + " VALUES (?,?,?,?,?,?)", Codes.hash(code), ctx.tenant(), "EXTRACT", id, "ACTIVE", now);
        String docRef = null;
        String template = def.extractTemplate();
        if (template != null) {
            Map<String, Object> model = new LinkedHashMap<>(payload);
            model.put("verificationCode", code);
            model.put("signature", Base64.getEncoder().encodeToString(sig.value()));
            model.put("keyId", sig.keyId());
            model.put("digest", Hashes.hex(digest));
            docRef = ports.documents().render(ctx, template, model).map(RegistryPorts.GeneratedDocument::documentRef).orElse(null);
        }
        Jdbc.update(c, "INSERT INTO REG_CERTIFIED_EXTRACT(id, tenant_id, book_id, from_entry, to_entry, purpose, payload_json,"
                        + " digest, sig_alg, key_id, signature, document_ref, issued_at, issued_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, ctx.tenant(), bookId, fromEntry, toEntry, purpose, new String(canonical, StandardCharsets.UTF_8), digest,
                sig.algorithm(), sig.keyId(), sig.value(), docRef, now, ctx.actor());
        audit.record(c, ctx, bookId, "REGISTER_EXTRACT_CERTIFIED", Maps.of("extractId", id, "fromEntry", fromEntry,
                "toEntry", toEntry, "digest", Hashes.hex(digest), "keyId", sig.keyId(), "purpose", purpose));
        return new Extract(id, bookId, fromEntry, toEntry, Hashes.hex(digest), sig.algorithm(), sig.keyId(),
                Base64.getEncoder().encodeToString(sig.value()), docRef, now, ctx.actor(), code, payload);
    }

    public Extract extract(Connection c, RegistryContext ctx, String extractId) {
        return Jdbc.one(c, "SELECT id, book_id, from_entry, to_entry, digest, sig_alg, key_id, signature, document_ref, issued_at,"
                        + " issued_by, payload_json FROM REG_CERTIFIED_EXTRACT WHERE id=? AND tenant_id=?",
                rs -> new Extract(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), Hashes.hex(rs.getBytes(5)),
                        rs.getString(6), rs.getString(7), Base64.getEncoder().encodeToString(rs.getBytes(8)), rs.getString(9),
                        Jdbc.instant(rs, "issued_at"), rs.getString(11), null, Json.map(rs.getString(12))),
                extractId, ctx.tenant()).orElseThrow(() -> RegistryException.notFound("certified extract", extractId));
    }

    /**
     * Verifies a certified extract by its code: the signature over the stored payload, the digest, and that the
     * extracted entries still carry the same hashes in the book. Reports later corrections of extracted entries.
     */
    public ExtractVerification verifyExtract(Connection c, RegistryContext ctx, String code) {
        if (!Codes.wellFormed(code)) {
            return new ExtractVerification(false, "MALFORMED", null, null, 0, 0, false, null);
        }
        var target = Jdbc.one(c, "SELECT target_id, status FROM REG_VERIFICATION WHERE code_hash=? AND tenant_id=? AND target_kind='EXTRACT'",
                rs -> new String[]{rs.getString(1), rs.getString(2)}, Codes.hash(code), ctx.tenant());
        if (target.isEmpty()) {
            return new ExtractVerification(false, "NOT_FOUND", null, null, 0, 0, false, null);
        }
        record Row(String bookId, long from, long to, byte[] digest, String alg, String keyId, byte[] sig, String payload,
                   Instant issuedAt) {
        }
        Row r = Jdbc.one(c, "SELECT book_id, from_entry, to_entry, digest, sig_alg, key_id, signature, payload_json, issued_at"
                        + " FROM REG_CERTIFIED_EXTRACT WHERE id=? AND tenant_id=?",
                rs -> new Row(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getBytes(4), rs.getString(5), rs.getString(6),
                        rs.getBytes(7), rs.getString(8), Jdbc.instant(rs, "issued_at")), target.get()[0], ctx.tenant()).orElseThrow();
        byte[] canonical = r.payload().getBytes(StandardCharsets.UTF_8);
        boolean sigOk = Arrays.equals(Hashes.sha256(canonical), r.digest())
                && ports.signer().verify(ctx.tenant(), r.keyId(), r.alg(), canonical, r.sig());
        if (!sigOk) {
            return new ExtractVerification(false, "INVALID_SIGNATURE", target.get()[0], r.bookId(), r.from(), r.to(), false, r.issuedAt());
        }
        Map<String, Object> payload = Json.map(r.payload());
        List<Entry> now = entries(c, ctx, r.bookId(), r.from(), r.to());
        List<Map<String, Object>> was = Maps.maps(payload, "entries");
        boolean intact = now.size() == was.size();
        for (int i = 0; intact && i < now.size(); i++) {
            intact = now.get(i).entryHashHex().equals(Maps.str(was.get(i), "entryHash"));
        }
        long later = Jdbc.one(c, "SELECT COUNT(*) FROM REG_REGISTER_ENTRY WHERE book_id=? AND tenant_id=? AND corrects_entry_no >= ?"
                + " AND corrects_entry_no <= ? AND entry_no > ?", rs -> rs.getLong(1), r.bookId(), ctx.tenant(), r.from(), r.to(), r.to()).orElse(0L);
        String status = !intact ? "BOOK_ALTERED" : "SUPERSEDED".equals(target.get()[1]) ? "SUPERSEDED" : "VALID";
        ports.metrics().increment("registry.extract.verified", Map.of("status", status));
        return new ExtractVerification(intact && "VALID".equals(status), status, target.get()[0], r.bookId(), r.from(),
                r.to(), later > 0, r.issuedAt());
    }

    static String registerRoot(String tenant, String registerKey) {
        return "register:" + Hashes.uuidV5(java.util.UUID.fromString("3c9e2a7b-5d41-4f6a-8b2c-9e0d1f2a3b4c"), tenant + "|" + registerKey);
    }

    private static byte[] unhex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
