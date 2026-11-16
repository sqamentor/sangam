// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.rag;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.document.ocr.OcrPort;
import org.sangam.integration.document.ocr.OcrRejected;
import org.sangam.integration.document.ocr.OcrResult;
import org.sangam.integration.document.ocr.OcrUnavailable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * M-103, owner decision 378(3) option A: OCR into a knowledge base with review before ingest. {@link #submit} runs the
 * image through the {@link OcrPort} (outside any transaction) and records the text as an AI_OCR_TRANSCRIPT row in state
 * {@code PENDING_REVIEW}; nothing reaches the knowledge base. {@link #decide} by a reviewer who is not the submitter
 * either rejects it (nothing is ingested) or approves it, optionally with corrected text: the approved text is ingested
 * into the transcript's knowledge-base version, which must still be BUILDING, and every chunk records the transcript id
 * ({@link KnowledgeBaseService.Hit#ocrTranscriptId()}). The approval and the chunks commit in one transaction.
 *
 * <p>Fail closed: no OCR engine, not ready, timed out or an unusable answer is {@link OcrUnavailable} (REST 503
 * OCR_UNAVAILABLE); an image the engine refuses is {@link OcrRejected} (REST 422 OCR_REJECTED). Neither records a
 * transcript, so there is never a guessed text to review.
 */
public final class OcrIngestion {

    public enum State { PENDING_REVIEW, APPROVED, REJECTED }

    /** A refusal of a review step; the message starts with a stable code (REST 409). */
    public static final class ReviewException extends RuntimeException {
        public ReviewException(String message) {
            super(message);
        }
    }

    /** The approved text is blank (REST 422 OCR_TRANSCRIPT_EMPTY): nothing would be ingested. */
    public static final class EmptyTranscriptException extends RuntimeException {
        public EmptyTranscriptException(String message) {
            super(message);
        }
    }

    /** One transcript. {@code text} is the engine's text ("" when it found none); {@code chunks} is set on approval. */
    public record Transcript(String id, String kb, int kbVersion, String documentId, String docVersion, String imageSha256,
                             String mediaType, String engine, List<String> languages, String text, State state,
                             String correctedText, Integer chunks, String createdBy, Instant createdAt, String reviewedBy,
                             Instant reviewedAt, String reviewNote) {
    }

    /** A transcript without its texts, for the review queue. */
    public record Summary(String id, String kb, int kbVersion, String documentId, String docVersion, String mediaType,
                          String engine, List<String> languages, State state, Integer chunks, String createdBy,
                          Instant createdAt, String reviewedBy, Instant reviewedAt) {
    }

    public static final int MAX_LIST = 500;

    private static final String COLUMNS = "transcript_id,kb_key,kb_version,document_id,doc_version,image_sha256,media_type,engine,"
            + "language_codes,ocr_text,state,corrected_text,chunks,created_by,created_at,reviewed_by,reviewed_at,review_note";

    private final Dialect dialect;
    private final Transactions tx;
    private final AuditChain audit;
    private final OcrPort ocr;
    private final KnowledgeBaseService knowledge;

    public OcrIngestion(Dialect dialect, Transactions tx, AuditChain audit, OcrPort ocr, KnowledgeBaseService knowledge) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.tx = Objects.requireNonNull(tx, "tx");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ocr = Objects.requireNonNull(ocr, "ocr");
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
    }

    /**
     * Recognises {@code image} and records the transcript for review. The version must exist (else
     * {@link IllegalArgumentException}) and be BUILDING (else {@link ReviewException} KB_VERSION_NOT_BUILDING) before the
     * engine is called. {@link OcrUnavailable} and {@link OcrRejected} propagate and nothing is recorded.
     */
    public Transcript submit(String tenantId, String kb, int version, String documentId, String docVersion, byte[] image,
                             String mediaType, List<String> languages, String actor) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId is required");
        }
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("the submitting principal is required");
        }
        tx.inTransaction(c -> {
            requireBuilding(c, tenantId, kb, version);
            return null;
        });
        OcrResult r = ocr.recognise(image, mediaType, languages);
        String id = Hashes.uuidV7().toString();
        String sha = Hashes.hex(Hashes.sha256(image));
        String text = r.text() == null || r.text().isEmpty() ? null : r.text();
        String langs = String.join(",", r.languages() == null || r.languages().isEmpty() ? languages : r.languages());
        return tx.inTransaction(c -> {
            Instant now = Jdbc.dbNow(c, dialect);
            Jdbc.update(c, "INSERT INTO AI_OCR_TRANSCRIPT(tenant_id," + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    tenantId, id, kb, version, documentId, docVersion, sha, mediaType, r.engine(), langs, text,
                    State.PENDING_REVIEW.name(), null, null, actor, now, null, null, null);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("transcriptId", id);
            p.put("kb", kb);
            p.put("kbVersion", version);
            p.put("documentId", documentId);
            p.put("imageSha256", sha);
            p.put("engine", String.valueOf(r.engine()));
            audit.append(c, tenantId, root(id), "AI_OCR_TRANSCRIBED", p);
            return read(c, tenantId, id).orElseThrow();
        });
    }

    public Optional<Transcript> find(String tenantId, String id) {
        return tx.inTransaction(c -> read(c, tenantId, id));
    }

    /** Newest first; {@code stateOrNull} and {@code kbOrNull} filter; {@code limit} is clamped to 1..{@value #MAX_LIST}. */
    public List<Summary> list(String tenantId, State stateOrNull, String kbOrNull, int limit) {
        int n = Math.min(Math.max(limit, 1), MAX_LIST);
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM AI_OCR_TRANSCRIPT WHERE tenant_id=?");
        List<Object> params = new ArrayList<>(List.of(tenantId));
        if (stateOrNull != null) {
            sql.append(" AND state=?");
            params.add(stateOrNull.name());
        }
        if (kbOrNull != null) {
            sql.append(" AND kb_key=?");
            params.add(kbOrNull);
        }
        sql.append(" ORDER BY created_at DESC, transcript_id DESC");
        sql.append(switch (dialect) {
            case POSTGRESQL, MYSQL -> " LIMIT " + n;
            case ORACLE, SQLSERVER -> " OFFSET 0 ROWS FETCH NEXT " + n + " ROWS ONLY";
        });
        return tx.inTransaction(c -> Jdbc.query(c, sql.toString(), OcrIngestion::map, params.toArray())).stream()
                .map(t -> new Summary(t.id(), t.kb(), t.kbVersion(), t.documentId(), t.docVersion(), t.mediaType(), t.engine(),
                        t.languages(), t.state(), t.chunks(), t.createdBy(), t.createdAt(), t.reviewedBy(), t.reviewedAt()))
                .toList();
    }

    /**
     * Rejects, or approves and ingests. Refusals ({@link ReviewException}): OCR_TRANSCRIPT_NOT_FOUND,
     * OCR_TRANSCRIPT_NOT_PENDING (already decided, also when a concurrent decision won), SELF_REVIEW_FORBIDDEN (the
     * reviewer submitted it), KB_VERSION_NOT_BUILDING. An approval whose text ({@code correctedText} when not blank, else
     * the engine's) is blank is {@link EmptyTranscriptException}. The text is embedded before the transaction, so an LLM
     * failure leaves the transcript pending.
     */
    public Transcript decide(String tenantId, String id, boolean approve, String correctedText, String note, String reviewer) {
        if (reviewer == null || reviewer.isBlank()) {
            throw new IllegalArgumentException("the reviewing principal is required");
        }
        Transcript t = find(tenantId, id).orElseThrow(() -> new ReviewException("OCR_TRANSCRIPT_NOT_FOUND: " + id));
        checkReviewable(t, reviewer);
        String corrected = correctedText == null || correctedText.isBlank() ? null : correctedText;
        if (!approve) {
            tx.inTransaction(c -> {
                claim(c, tenantId, id, State.REJECTED, null, null, reviewer, note);
                audit.append(c, tenantId, root(id), "AI_OCR_TRANSCRIPT_REJECTED", payload(id, reviewer, note, null));
                return null;
            });
            return find(tenantId, id).orElseThrow();
        }
        String text = corrected != null ? corrected : t.text();
        if (text == null || text.isBlank()) {
            throw new EmptyTranscriptException("OCR_TRANSCRIPT_EMPTY: transcript " + id
                    + " has no text; approve with correctedText or reject it");
        }
        knowledge.ingest(tenantId, t.kb(), t.kbVersion(), t.documentId(), t.docVersion(), text, id, (c, chunks) -> {
            requireBuilding(c, tenantId, t.kb(), t.kbVersion());
            claim(c, tenantId, id, State.APPROVED, corrected, chunks, reviewer, note);
            audit.append(c, tenantId, root(id), "AI_OCR_TRANSCRIPT_APPROVED", payload(id, reviewer, note, chunks));
        });
        return find(tenantId, id).orElseThrow();
    }

    private static void checkReviewable(Transcript t, String reviewer) {
        if (t.state() != State.PENDING_REVIEW) {
            throw new ReviewException("OCR_TRANSCRIPT_NOT_PENDING: transcript " + t.id() + " is " + t.state());
        }
        if (t.createdBy().equals(reviewer)) {
            throw new ReviewException("SELF_REVIEW_FORBIDDEN: the submitter of transcript " + t.id() + " cannot review it");
        }
    }

    /** Moves PENDING_REVIEW to {@code next}; zero rows means another decision came first. */
    private void claim(Connection c, String tenantId, String id, State next, String corrected, Integer chunks, String reviewer, String note) {
        int n = Jdbc.update(c, "UPDATE AI_OCR_TRANSCRIPT SET state=?, corrected_text=?, chunks=?, reviewed_by=?, reviewed_at=?, review_note=?"
                        + " WHERE tenant_id=? AND transcript_id=? AND state=? AND created_by<>?",
                next.name(), corrected, chunks, reviewer, Jdbc.dbNow(c, dialect), note == null || note.isBlank() ? null : note,
                tenantId, id, State.PENDING_REVIEW.name(), reviewer);
        if (n != 1) {
            throw new ReviewException("OCR_TRANSCRIPT_NOT_PENDING: transcript " + id + " was decided concurrently");
        }
    }

    private void requireBuilding(Connection c, String tenantId, String kb, int version) {
        String state = Jdbc.one(c, "SELECT state FROM AI_KB_VERSION WHERE tenant_id=? AND kb_key=? AND version=?",
                        rs -> rs.getString(1), tenantId, kb, version)
                .orElseThrow(() -> new IllegalArgumentException("no knowledge base version " + kb + " v" + version));
        if (!"BUILDING".equals(state)) {
            throw new ReviewException("KB_VERSION_NOT_BUILDING: version " + version + " of " + kb + " is " + state
                    + "; only a BUILDING version accepts documents");
        }
    }

    private static Map<String, Object> payload(String id, String reviewer, String note, Integer chunks) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("transcriptId", id);
        p.put("reviewer", reviewer);
        p.put("note", String.valueOf(note));
        if (chunks != null) {
            p.put("chunks", chunks);
        }
        return p;
    }

    private static String root(String id) {
        return "ai-ocr-transcript:" + id;
    }

    private static Optional<Transcript> read(Connection c, String tenantId, String id) {
        return Jdbc.one(c, "SELECT " + COLUMNS + " FROM AI_OCR_TRANSCRIPT WHERE tenant_id=? AND transcript_id=?", OcrIngestion::map, tenantId, id);
    }

    private static Transcript map(ResultSet rs) throws SQLException {
        int chunks = rs.getInt(13);
        Integer chunksOrNull = rs.wasNull() ? null : chunks;
        String text = rs.getString(10);
        return new Transcript(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), List.copyOf(Arrays.asList(rs.getString(9).split(","))), text == null ? "" : text,
                State.valueOf(rs.getString(11)), rs.getString(12), chunksOrNull, rs.getString(14), Jdbc.instant(rs, "created_at"),
                rs.getString(16), Jdbc.instant(rs, "reviewed_at"), rs.getString(18));
    }
}
