// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.rag;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.ai.AiSchema;
import org.sangam.ai.llm.SimulatedLlm;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.integration.document.ocr.OcrPort;
import org.sangam.integration.document.ocr.OcrRejected;
import org.sangam.integration.document.ocr.OcrResult;
import org.sangam.integration.document.ocr.OcrUnavailable;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M-103, owner decision 378(3) option A (H2): OCR text waits for review and only an approval by another person ingests
 * it, with the transcript id as the provenance of every chunk and of every search hit; OCR failures record nothing.
 */
class OcrIngestionTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};

    Transactions tx;
    KnowledgeBaseService kb;
    final AtomicInteger calls = new AtomicInteger();
    String engineText = "treasury budget reappropriation order";

    final OcrPort engine = new OcrPort() {
        @Override
        public OcrResult recognise(byte[] image, String mediaType, List<String> languages) {
            calls.incrementAndGet();
            return new OcrResult(engineText, "tesseract 5.3.4", languages);
        }

        @Override
        public String notReadyReason() {
            return null;
        }
    };

    @BeforeEach
    void db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
            AiSchema.create(c, Dialect.POSTGRESQL);
        }
        tx = Transactions.of(ds);
        kb = new KnowledgeBaseService(Dialect.POSTGRESQL, tx, new SimulatedLlm(), 200, 20,
                (c, who) -> new SqlPredicate("1=1", List.of()), VectorIndex.portable());
    }

    OcrIngestion ingestion(OcrPort port) {
        return new OcrIngestion(Dialect.POSTGRESQL, tx, new AuditChain(Dialect.POSTGRESQL), port, kb);
    }

    long chunks(String kbKey) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM AI_KB_CHUNK WHERE tenant_id='t' AND kb_key=?", rs -> rs.getLong(1), kbKey).orElseThrow());
    }

    long transcripts() {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM AI_OCR_TRANSCRIPT", rs -> rs.getLong(1)).orElseThrow());
    }

    List<String> audit(String id) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT event_type FROM AUDIT_EVENT WHERE tenant_id='t' AND chain_id=? ORDER BY seq", rs -> rs.getString(1),
                "ai-ocr-transcript:" + id));
    }

    static SecurityIdentity who() {
        return new SecurityIdentity("t", "carol", "carol", Set.of(), Set.of(), Set.of(), Set.of(), "OIDC", null, null, false, Map.of());
    }

    @Test
    void aTranscriptWaitsForReviewAndOnlyAnotherPersonsApprovalIngestsItWithItsIdOnEveryHit() {
        OcrIngestion ocr = ingestion(engine);
        int v = kb.newVersion("t", "kb");
        OcrIngestion.Transcript t = ocr.submit("t", "kb", v, "scan-1", "2", PNG, "image/png", List.of("eng", "hin"), "alice");
        assertEquals(OcrIngestion.State.PENDING_REVIEW, t.state());
        assertEquals(engineText, t.text());
        assertEquals(Hashes.hex(Hashes.sha256(PNG)), t.imageSha256());
        assertEquals(List.of("eng", "hin"), t.languages());
        assertEquals("tesseract 5.3.4", t.engine());
        assertEquals("alice", t.createdBy());
        assertNull(t.chunks());
        assertEquals(0, chunks("kb"), "nothing reaches the knowledge base before review");

        OcrIngestion.ReviewException self = assertThrows(OcrIngestion.ReviewException.class,
                () -> ocr.decide("t", t.id(), true, null, null, "alice"));
        assertTrue(self.getMessage().startsWith("SELF_REVIEW_FORBIDDEN"), self.getMessage());
        assertEquals(0, chunks("kb"));

        OcrIngestion.Transcript approved = ocr.decide("t", t.id(), true, null, "checked against the scan", "bob");
        assertEquals(OcrIngestion.State.APPROVED, approved.state());
        assertEquals("bob", approved.reviewedBy());
        assertEquals(1, approved.chunks());
        assertEquals(1, chunks("kb"));
        assertEquals(List.of("AI_OCR_TRANSCRIBED", "AI_OCR_TRANSCRIPT_APPROVED"), audit(t.id()));

        kb.ingest("t", "kb", v, "typed-1", "1", "unrelated typed minutes");
        kb.activate("t", "kb", v);
        List<KnowledgeBaseService.Hit> hits = kb.retrieve(who(), "kb", engineText, 2);
        assertEquals("scan-1", hits.get(0).documentId());
        assertEquals("2", hits.get(0).docVersion());
        assertEquals(t.id(), hits.get(0).ocrTranscriptId(), "the search hit names the approved transcript");
        assertNull(hits.get(1).ocrTranscriptId(), "a typed document has no OCR provenance");

        OcrIngestion.ReviewException again = assertThrows(OcrIngestion.ReviewException.class,
                () -> ocr.decide("t", t.id(), false, null, "late", "dave"));
        assertTrue(again.getMessage().startsWith("OCR_TRANSCRIPT_NOT_PENDING"), again.getMessage());
    }

    @Test
    void theReviewersCorrectedTextIsWhatIsIngested() {
        OcrIngestion ocr = ingestion(engine);
        int v = kb.newVersion("t", "kb");
        OcrIngestion.Transcript t = ocr.submit("t", "kb", v, "scan-1", null, PNG, "image/png", List.of("eng"), "alice");
        OcrIngestion.Transcript approved = ocr.decide("t", t.id(), true, "pension sanction letter", null, "bob");
        assertEquals("pension sanction letter", approved.correctedText());
        assertEquals(engineText, approved.text(), "the engine's text is kept beside the correction");
        assertEquals(List.of("pension sanction letter"), tx.inTransaction(c -> Jdbc.query(c,
                "SELECT text_value FROM AI_KB_CHUNK WHERE ocr_transcript_id=?", rs -> rs.getString(1), t.id())));
    }

    @Test
    void aRejectionIngestsNothing() {
        OcrIngestion ocr = ingestion(engine);
        int v = kb.newVersion("t", "kb");
        OcrIngestion.Transcript t = ocr.submit("t", "kb", v, "scan-1", "1", PNG, "image/jpeg", List.of("eng"), "alice");
        OcrIngestion.Transcript rejected = ocr.decide("t", t.id(), false, "ignored", "illegible", "bob");
        assertEquals(OcrIngestion.State.REJECTED, rejected.state());
        assertEquals("illegible", rejected.reviewNote());
        assertNull(rejected.correctedText());
        assertEquals(0, chunks("kb"));
        assertEquals(List.of("AI_OCR_TRANSCRIBED", "AI_OCR_TRANSCRIPT_REJECTED"), audit(t.id()));
    }

    @Test
    void ocrFailuresFailClosedAndRecordNothing() {
        int v = kb.newVersion("t", "kb");
        assertThrows(OcrUnavailable.class, () -> ingestion(OcrPort.none("no engine"))
                .submit("t", "kb", v, "scan-1", "1", PNG, "image/png", List.of("eng"), "alice"));
        OcrPort refusing = new OcrPort() {
            @Override
            public OcrResult recognise(byte[] image, String mediaType, List<String> languages) {
                throw new OcrRejected("image too large", 413);
            }

            @Override
            public String notReadyReason() {
                return null;
            }
        };
        OcrRejected r = assertThrows(OcrRejected.class, () -> ingestion(refusing)
                .submit("t", "kb", v, "scan-1", "1", PNG, "image/png", List.of("eng"), "alice"));
        assertEquals(413, r.status());
        assertEquals(0, transcripts());
        assertEquals(0, chunks("kb"));
    }

    @Test
    void aVersionThatIsNotBuildingIsRefusedBeforeTheEngineAndAtApprovalWithNothingWritten() {
        OcrIngestion ocr = ingestion(engine);
        int v = kb.newVersion("t", "kb");
        OcrIngestion.Transcript t = ocr.submit("t", "kb", v, "scan-1", "1", PNG, "image/png", List.of("eng"), "alice");
        kb.activate("t", "kb", v);
        int before = calls.get();
        OcrIngestion.ReviewException submit = assertThrows(OcrIngestion.ReviewException.class,
                () -> ocr.submit("t", "kb", v, "scan-2", "1", PNG, "image/png", List.of("eng"), "alice"));
        assertTrue(submit.getMessage().startsWith("KB_VERSION_NOT_BUILDING"), submit.getMessage());
        assertEquals(before, calls.get(), "the engine is not called for a version that cannot take the text");
        assertThrows(IllegalArgumentException.class, () -> ocr.submit("t", "kb", 99, "scan-2", "1", PNG, "image/png", List.of("eng"), "alice"));

        OcrIngestion.ReviewException approve = assertThrows(OcrIngestion.ReviewException.class,
                () -> ocr.decide("t", t.id(), true, null, null, "bob"));
        assertTrue(approve.getMessage().startsWith("KB_VERSION_NOT_BUILDING"), approve.getMessage());
        assertEquals(OcrIngestion.State.PENDING_REVIEW, ocr.find("t", t.id()).orElseThrow().state(), "the refused approval rolled back");
        assertEquals(0, chunks("kb"));
    }

    @Test
    void aBlankTranscriptNeedsCorrectedTextToBeApproved() {
        engineText = "";
        OcrIngestion ocr = ingestion(engine);
        int v = kb.newVersion("t", "kb");
        OcrIngestion.Transcript t = ocr.submit("t", "kb", v, "scan-1", "1", PNG, "image/png", List.of("eng"), "alice");
        assertEquals("", t.text());
        OcrIngestion.EmptyTranscriptException e = assertThrows(OcrIngestion.EmptyTranscriptException.class,
                () -> ocr.decide("t", t.id(), true, "  ", null, "bob"));
        assertTrue(e.getMessage().startsWith("OCR_TRANSCRIPT_EMPTY"), e.getMessage());
        assertEquals(OcrIngestion.State.PENDING_REVIEW, ocr.find("t", t.id()).orElseThrow().state());
        assertEquals(1, ocr.decide("t", t.id(), true, "typed by the reviewer", null, "bob").chunks());
    }

    @Test
    void theQueueIsPerTenantNewestFirstAndFilters() {
        OcrIngestion ocr = ingestion(engine);
        int v = kb.newVersion("t", "kb");
        int w = kb.newVersion("t", "other");
        OcrIngestion.Transcript a = ocr.submit("t", "kb", v, "a", "1", PNG, "image/png", List.of("eng"), "alice");
        OcrIngestion.Transcript b = ocr.submit("t", "other", w, "b", "1", PNG, "image/png", List.of("eng"), "alice");
        ocr.decide("t", a.id(), false, null, null, "bob");
        assertEquals(Set.of(a.id(), b.id()), Set.copyOf(ocr.list("t", null, null, 100).stream().map(OcrIngestion.Summary::id).toList()));
        assertEquals(List.of(b.id()), ocr.list("t", OcrIngestion.State.PENDING_REVIEW, null, 100).stream().map(OcrIngestion.Summary::id).toList());
        assertEquals(List.of(a.id()), ocr.list("t", null, "kb", 100).stream().map(OcrIngestion.Summary::id).toList());
        assertEquals(1, ocr.list("t", null, null, 1).size());
        assertEquals(List.of(), ocr.list("other-tenant", null, null, 100));
        assertTrue(ocr.find("other-tenant", a.id()).isEmpty());
        OcrIngestion.ReviewException missing = assertThrows(OcrIngestion.ReviewException.class,
                () -> ocr.decide("other-tenant", b.id(), true, null, null, "bob"));
        assertTrue(missing.getMessage().startsWith("OCR_TRANSCRIPT_NOT_FOUND"), missing.getMessage());
    }
}
