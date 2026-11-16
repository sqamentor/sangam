// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.ai.AiSchema;
import org.sangam.ai.llm.SimulatedLlm;
import org.sangam.ai.rag.KnowledgeBaseService;
import org.sangam.ai.rag.OcrIngestion;
import org.sangam.ai.rag.VectorIndex;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.integration.document.ocr.OcrPort;
import org.sangam.integration.document.ocr.OcrRejected;
import org.sangam.integration.document.ocr.OcrResult;
import org.sangam.integration.document.ocr.OcrUnavailable;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

/**
 * M-103, owner decision 378(3) option A at the REST layer: the four OCR routes ask for {@code ai.admin} (submit) and
 * {@code ai.review} (queue, read, decide) before the service is called; an OCR failure answers 503 OCR_UNAVAILABLE or 422
 * OCR_REJECTED and records nothing; the decision route refuses self-review (409) and an approval ingests with the
 * transcript id on the search hit.
 */
class AiOcrControllerTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 7};

    static RequestContext ctx(String principal) {
        return new RequestContext("T1", principal, Set.of(), null, null, null, null, null, "OIDC");
    }

    /** Grants {@code held} to everyone; records every permission asked for. */
    static final class Perms implements PermissionPort {
        final Set<String> held;
        final List<String> asked = new ArrayList<>();

        Perms(String... held) {
            this.held = Set.of(held);
        }

        @Override
        public boolean has(RequestContext ctx, String permission) {
            asked.add(permission);
            return held.contains(permission);
        }

        @Override
        public Set<String> permissions(RequestContext ctx) {
            return held;
        }

        @Override
        public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
            return true;
        }
    }

    final List<String> engineCalls = new ArrayList<>();
    RuntimeException engineFailure;
    KnowledgeBaseService kb;
    AiApi api;

    @BeforeEach
    void db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
            AiSchema.create(c, Dialect.POSTGRESQL);
        }
        Transactions tx = Transactions.of(ds);
        kb = new KnowledgeBaseService(Dialect.POSTGRESQL, tx, new SimulatedLlm(), 200, 20,
                (c, who) -> new SqlPredicate("1=1", List.of()), VectorIndex.portable());
        OcrPort engine = new OcrPort() {
            @Override
            public OcrResult recognise(byte[] image, String mediaType, List<String> languages) {
                engineCalls.add(mediaType + " " + languages);
                if (engineFailure != null) {
                    throw engineFailure;
                }
                return new OcrResult("district treasury sanction order", "tesseract 5.3.4", languages);
            }

            @Override
            public String notReadyReason() {
                return null;
            }
        };
        api = new AiApi(null, kb, null, null, null, null, null, null, null,
                new OcrIngestion(Dialect.POSTGRESQL, tx, new AuditChain(Dialect.POSTGRESQL), engine, kb));
    }

    AiController controller(Perms perms) {
        return new AiController(api, perms, null, List.of());
    }

    static MockMultipartFile image(String mediaType) {
        return new MockMultipartFile("file", "scan.png", mediaType, PNG);
    }

    static int status(ResponseEntity<Map<String, Object>> r) {
        return r.getStatusCode().value();
    }

    @Test
    void eachRouteAsksForItsScopeBeforeTheServiceIsCalled() {
        int v = kb.newVersion("T1", "kb");
        Perms review = new Perms(AiController.REVIEW);
        ApiProblem submit = assertThrows(ApiProblem.class, () -> controller(review)
                .ocr(ctx("alice"), "kb", v, image("image/png"), "scan-1", "1", "eng"));
        assertEquals(403, submit.status());
        assertEquals(List.of(), engineCalls, "the engine is not called without ai.admin");

        Perms admin = new Perms(AiController.ADMIN);
        assertEquals(403, assertThrows(ApiProblem.class, () -> controller(admin).ocrTranscripts(ctx("bob"), null, null, null)).status());
        assertEquals(403, assertThrows(ApiProblem.class, () -> controller(admin).ocrTranscript(ctx("bob"), "x")).status());
        assertEquals(403, assertThrows(ApiProblem.class, () -> controller(admin)
                .decideOcr(ctx("bob"), "x", new AiController.OcrDecideRequest(true, null, null))).status());
        assertTrue(admin.asked.stream().allMatch(AiController.REVIEW::equals), admin.asked.toString());
    }

    @Test
    void submitThenAnotherReviewerApprovesAndTheSearchHitNamesTheTranscript() throws Exception {
        int v = kb.newVersion("T1", "kb");
        ResponseEntity<OcrIngestion.Transcript> created = controller(new Perms(AiController.ADMIN))
                .ocr(ctx("alice"), "kb", v, image("image/png"), "scan-1", "3", "eng, hin");
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        OcrIngestion.Transcript t = created.getBody();
        assertEquals("/api/v1/ai/ocr-transcripts/" + t.id(), created.getHeaders().getLocation().toString());
        assertEquals(List.of("image/png [eng, hin]"), engineCalls);
        assertEquals(OcrIngestion.State.PENDING_REVIEW, t.state());

        AiController reviewer = controller(new Perms(AiController.REVIEW));
        assertEquals(List.of(t.id()), reviewer.ocrTranscripts(ctx("bob"), "PENDING_REVIEW", "kb", 10).getBody().stream()
                .map(OcrIngestion.Summary::id).toList());
        assertEquals(400, assertThrows(ApiProblem.class, () -> reviewer.ocrTranscripts(ctx("bob"), "pending", null, null)).status());
        assertEquals(404, assertThrows(ApiProblem.class, () -> reviewer.ocrTranscript(ctx("bob"), "missing")).status());
        assertEquals(404, assertThrows(ApiProblem.class, () -> reviewer
                .decideOcr(ctx("bob"), "missing", new AiController.OcrDecideRequest(true, null, null))).status());

        OcrIngestion.ReviewException self = assertThrows(OcrIngestion.ReviewException.class, () -> reviewer
                .decideOcr(ctx("alice"), t.id(), new AiController.OcrDecideRequest(true, null, null)));
        ResponseEntity<Map<String, Object>> conflict = new AiProblemAdvice().review(self, new MockHttpServletRequest("POST", "/x"));
        assertEquals(409, status(conflict));
        assertEquals("SELF_REVIEW_FORBIDDEN", conflict.getBody().get("code"));

        OcrIngestion.Transcript approved = reviewer.decideOcr(ctx("bob"), t.id(), new AiController.OcrDecideRequest(true, null, "ok"));
        assertEquals(OcrIngestion.State.APPROVED, approved.state());
        assertEquals(approved, reviewer.ocrTranscript(ctx("bob"), t.id()));
        kb.activate("T1", "kb", v);
        List<KnowledgeBaseService.Hit> hits = kb.retrieve(new SecurityIdentity("T1", "carol", "carol", Set.of(), Set.of(), Set.of(),
                Set.of(), "OIDC", null, null, false, Map.of()), "kb", "district treasury sanction order", 1);
        assertEquals(t.id(), hits.get(0).ocrTranscriptId());
    }

    @Test
    void ocrFailuresAnswer503And422AndRecordNothing() {
        int v = kb.newVersion("T1", "kb");
        AiController admin = controller(new Perms(AiController.ADMIN));
        AiProblemAdvice advice = new AiProblemAdvice();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/ai/knowledge-bases/kb/versions/1/ocr");

        engineFailure = new OcrUnavailable("OCR is not ready: no URL");
        OcrUnavailable down = assertThrows(OcrUnavailable.class, () -> admin.ocr(ctx("alice"), "kb", v, image("image/png"), "s", null, "eng"));
        ResponseEntity<Map<String, Object>> r503 = advice.ocrUnavailable(down, req);
        assertEquals(503, status(r503));
        assertEquals("OCR_UNAVAILABLE", r503.getBody().get("code"));

        engineFailure = new OcrRejected("the media type must be one of [image/png, image/jpeg, image/tiff]", 0);
        OcrRejected refused = assertThrows(OcrRejected.class, () -> admin.ocr(ctx("alice"), "kb", v, image("application/pdf"), "s", null, "eng"));
        ResponseEntity<Map<String, Object>> r422 = advice.ocrRejected(refused, req);
        assertEquals(422, status(r422));
        assertEquals("OCR_REJECTED", r422.getBody().get("code"));

        assertEquals(List.of(), controller(new Perms(AiController.REVIEW)).ocrTranscripts(ctx("bob"), null, null, null).getBody());
    }

    @Test
    void aClosedVersionIs409AndABlankApprovalIs422() throws Exception {
        int v = kb.newVersion("T1", "kb");
        OcrIngestion.Transcript t = controller(new Perms(AiController.ADMIN))
                .ocr(ctx("alice"), "kb", v, image("image/png"), "scan-1", null, "eng").getBody();
        kb.activate("T1", "kb", v);
        AiController reviewer = controller(new Perms(AiController.REVIEW));
        OcrIngestion.ReviewException closed = assertThrows(OcrIngestion.ReviewException.class, () -> reviewer
                .decideOcr(ctx("bob"), t.id(), new AiController.OcrDecideRequest(true, null, null)));
        ResponseEntity<Map<String, Object>> r = new AiProblemAdvice().review(closed, new MockHttpServletRequest("POST", "/x"));
        assertEquals(409, status(r));
        assertEquals("KB_VERSION_NOT_BUILDING", r.getBody().get("code"));
        ResponseEntity<Map<String, Object>> empty = new AiProblemAdvice().emptyTranscript(
                new OcrIngestion.EmptyTranscriptException("OCR_TRANSCRIPT_EMPTY: x"), new MockHttpServletRequest("POST", "/x"));
        assertEquals(422, status(empty));
        assertEquals("OCR_TRANSCRIPT_EMPTY", empty.getBody().get("code"));
    }
}
