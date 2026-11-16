// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ai;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.ai.agent.AgentRuntime;
import org.sangam.ai.draft.DraftService;
import org.sangam.ai.llm.Llm;
import org.sangam.ai.rag.OcrIngestion;
import org.sangam.integration.document.ocr.OcrRejected;
import org.sangam.integration.document.ocr.OcrUnavailable;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.web.Problems;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps sangam-ai failures to problem+json: review refusals 409, disabled LLM 503, invalid input 400; M-103 OCR: no ready engine
 * 503 OCR_UNAVAILABLE, an image the engine refuses 422 OCR_REJECTED, a blank approval 422 OCR_TRANSCRIPT_EMPTY.
 */
@RestControllerAdvice(assignableTypes = {AiController.class, AiUiController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AiProblemAdvice {

    @ExceptionHandler({AgentRuntime.ApprovalException.class, DraftService.ReviewException.class, OcrIngestion.ReviewException.class})
    public ResponseEntity<Map<String, Object>> review(RuntimeException e, HttpServletRequest req) {
        String code = e.getMessage() == null ? "REVIEW_REFUSED" : e.getMessage().split(":")[0];
        return send(ApiProblem.conflict(code, e.getMessage()), req);
    }

    /** SL-87: the model returned no content; 422 is the documented refusal of POST /ai/drafts (sangam-api.yaml). */
    @ExceptionHandler(DraftService.EmptyDraftException.class)
    public ResponseEntity<Map<String, Object>> emptyDraft(DraftService.EmptyDraftException e, HttpServletRequest req) {
        return send(ApiProblem.unprocessable("DRAFT_EMPTY", e.getMessage()), req);
    }

    /** M-103: no OCR engine, not ready, timed out or an unusable answer; no text is returned rather than a guess. */
    @ExceptionHandler(OcrUnavailable.class)
    public ResponseEntity<Map<String, Object>> ocrUnavailable(OcrUnavailable e, HttpServletRequest req) {
        return send(ApiProblem.unavailable("OCR_UNAVAILABLE", e.getMessage(), 60), req);
    }

    /** M-103: the engine refused the image (empty, wrong media type, too large, bad language codes, unreadable). */
    @ExceptionHandler(OcrRejected.class)
    public ResponseEntity<Map<String, Object>> ocrRejected(OcrRejected e, HttpServletRequest req) {
        return send(ApiProblem.unprocessable("OCR_REJECTED", e.getMessage()), req);
    }

    @ExceptionHandler(OcrIngestion.EmptyTranscriptException.class)
    public ResponseEntity<Map<String, Object>> emptyTranscript(OcrIngestion.EmptyTranscriptException e, HttpServletRequest req) {
        return send(ApiProblem.unprocessable("OCR_TRANSCRIPT_EMPTY", e.getMessage()), req);
    }

    @ExceptionHandler(Llm.DisabledException.class)
    public ResponseEntity<Map<String, Object>> disabled(Llm.DisabledException e, HttpServletRequest req) {
        return send(ApiProblem.unavailable("LLM_DISABLED", e.getMessage(), 3600), req);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e, HttpServletRequest req) {
        return send(ApiProblem.badRequest("VALIDATION_FAILED", e.getMessage()), req);
    }

    private static ResponseEntity<Map<String, Object>> send(ApiProblem p, HttpServletRequest req) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType(Problems.MEDIA_TYPE));
        return new ResponseEntity<>(Problems.body(p, req.getRequestURI()), h, HttpStatus.valueOf(p.status()));
    }
}
