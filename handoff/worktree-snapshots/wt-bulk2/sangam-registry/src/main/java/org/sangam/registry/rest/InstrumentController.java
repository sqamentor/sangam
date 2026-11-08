// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.rest;

import org.sangam.registry.instrument.InstrumentService;
import org.sangam.registry.instrument.VerificationService;
import org.sangam.registry.support.Maps;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Issued instruments REST API and public verification. Base path {@code /api/v1/registry/instruments}. */
@RestController
@RequestMapping("/api/v1/registry/instruments")
public class InstrumentController {

    private static final String IDEM = "Idempotency-Key";
    private static final String IF_MATCH = "If-Match";

    private final InstrumentService instruments;
    private final VerificationService verification;
    private final RegistryRestSupport support;

    public InstrumentController(InstrumentService instruments, VerificationService verification, RegistryRestSupport support) {
        this.instruments = instruments;
        this.verification = verification;
        this.support = support;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> issue(@RequestHeader(value = IDEM, required = false) String idem,
                                                     @RequestBody Map<String, Object> body) {
        InstrumentService.Issued i = support.txOp(idem, (c, ctx) -> instruments.issue(c, ctx, InstrumentService.IssueRequest.from(body)));
        return ResponseEntity.status(201).body(i.toMap());
    }

    @PostMapping("/{id}/renew")
    public Map<String, Object> renew(@PathVariable String id, @RequestHeader(value = IDEM, required = false) String idem,
                                     @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
                                     @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return support.txOp(idem, (c, ctx) -> instruments.renew(c, ctx, id, RegistryController.version(ifMatch, b),
                Maps.date(b, "validTo"), Maps.map(b, "attributes"), Maps.str(b, "reason"), Maps.bool(b, "force", false)).toMap());
    }

    @PostMapping("/{id}/deemed-renewal")
    public ResponseEntity<Map<String, Object>> deemed(@PathVariable String id) {
        InstrumentService.Issued i = support.tx((c, ctx) -> instruments.deemedRenewal(c, ctx, id));
        return i == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(i.toMap());
    }

    @PostMapping("/{id}/suspend")
    public Map<String, Object> suspend(@PathVariable String id, @RequestHeader(value = IDEM, required = false) String idem,
                                       @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
                                       @RequestBody Map<String, Object> body) {
        return support.txOp(idem, (c, ctx) -> instruments.suspend(c, ctx, id, RegistryController.version(ifMatch, body),
                Maps.required(body, "reason"), Maps.instant(body, "until")).toMap());
    }

    @PostMapping("/{id}/reinstate")
    public Map<String, Object> reinstate(@PathVariable String id, @RequestHeader(value = IDEM, required = false) String idem,
                                         @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
                                         @RequestBody Map<String, Object> body) {
        return support.txOp(idem, (c, ctx) -> instruments.reinstate(c, ctx, id, RegistryController.version(ifMatch, body),
                Maps.required(body, "reason")).toMap());
    }

    @PostMapping("/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable String id, @RequestHeader(value = IDEM, required = false) String idem,
                                      @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
                                      @RequestBody Map<String, Object> body) {
        return support.txOp(idem, (c, ctx) -> instruments.revoke(c, ctx, id, RegistryController.version(ifMatch, body),
                Maps.required(body, "reason"), Maps.str(body, "countersignedBy")).toMap());
    }

    @PostMapping("/{id}/surrender")
    public Map<String, Object> surrender(@PathVariable String id, @RequestHeader(value = IDEM, required = false) String idem,
                                         @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
                                         @RequestBody Map<String, Object> body) {
        return support.txOp(idem, (c, ctx) -> instruments.surrender(c, ctx, id, RegistryController.version(ifMatch, body),
                Maps.required(body, "reason")).toMap());
    }

    @PostMapping("/{id}/expire")
    public Map<String, Object> expire(@PathVariable String id) {
        return support.tx((c, ctx) -> instruments.expire(c, ctx, id).toMap());
    }

    @PostMapping("/{id}/transitions/{name}")
    public Map<String, Object> transition(@PathVariable String id, @PathVariable String name,
                                          @RequestHeader(value = IDEM, required = false) String idem,
                                          @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
                                          @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return support.txOp(idem, (c, ctx) -> instruments.transition(c, ctx, id, RegistryController.version(ifMatch, b), name,
                Maps.str(b, "reason"), Maps.instant(b, "until"), Maps.str(b, "countersignedBy")).toMap());
    }

    @PostMapping("/{id}/transfer-holder")
    public Map<String, Object> transferHolder(@PathVariable String id, @RequestHeader(value = IDEM, required = false) String idem,
                                              @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
                                              @RequestBody Map<String, Object> body) {
        return support.txOp(idem, (c, ctx) -> instruments.transferHolder(c, ctx, id, RegistryController.version(ifMatch, body),
                Maps.required(body, "newHolderPartyId"), Maps.required(body, "reason")).toMap());
    }

    @PostMapping("/{id}/transfer-reissue")
    public Map<String, Object> transferReissue(@PathVariable String id, @RequestHeader(value = IDEM, required = false) String idem,
                                               @RequestBody Map<String, Object> body) {
        return support.txOp(idem, (c, ctx) -> instruments.transferByReissue(c, ctx, id, Maps.required(body, "newHolderPartyId"),
                Maps.required(body, "reason")).toMap());
    }

    @PostMapping("/{id}/amend")
    public Map<String, Object> amend(@PathVariable String id, @RequestHeader(value = IDEM, required = false) String idem,
                                     @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
                                     @RequestBody Map<String, Object> body) {
        return support.txOp(idem, (c, ctx) -> instruments.amend(c, ctx, id, RegistryController.version(ifMatch, body),
                Maps.map(body, "attributes"), Maps.required(body, "endorsement")).toMap());
    }

    @PostMapping("/{id}/duplicate")
    public Map<String, Object> duplicate(@PathVariable String id, @RequestHeader(value = IDEM, required = false) String idem,
                                         @RequestBody Map<String, Object> body) {
        return support.txOp(idem, (c, ctx) -> instruments.duplicate(c, ctx, id, Maps.required(body, "reason")).toMap());
    }

    @PostMapping("/{id}/endorsements")
    public ResponseEntity<InstrumentService.Endorsement> endorse(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(201).body(support.tx((c, ctx) -> instruments.endorse(c, ctx, id,
                Maps.required(body, "type"), Maps.map(body, "content"))));
    }

    @GetMapping("/{id}/endorsements")
    public List<InstrumentService.Endorsement> endorsements(@PathVariable String id) {
        return support.tx((c, ctx) -> instruments.endorsements(c, ctx, id));
    }

    @GetMapping("/{id}/documents")
    public List<InstrumentService.Document> documents(@PathVariable String id) {
        return support.tx((c, ctx) -> instruments.documentsOf(c, ctx, id));
    }

    @PostMapping("/{id}/documents/regenerate")
    public Map<String, Object> regenerate(@PathVariable String id, @RequestParam(required = false) String purpose) {
        return support.tx((c, ctx) -> instruments.regenerateDocument(c, ctx, id, purpose).toMap());
    }

    @PostMapping("/jobs/expire")
    public InstrumentService.SweepResult expireDue(@RequestParam(required = false) String typeKey,
                                                   @RequestParam(defaultValue = "500") int limit) {
        return support.tx((c, ctx) -> instruments.expireDue(c, ctx, typeKey, limit));
    }

    @PostMapping("/jobs/renewal-due")
    public InstrumentService.SweepResult renewalDue(@RequestParam(required = false) String typeKey,
                                                    @RequestParam(defaultValue = "500") int limit) {
        return support.tx((c, ctx) -> instruments.renewalDue(c, ctx, typeKey, limit));
    }

    @PostMapping("/jobs/reinstate")
    public InstrumentService.SweepResult reinstateDue(@RequestParam(required = false) String typeKey,
                                                      @RequestParam(defaultValue = "500") int limit) {
        return support.tx((c, ctx) -> instruments.reinstateDue(c, ctx, typeKey, limit));
    }

    // ---------------------------------------------------------------------------------- public verification

    /** Public verification by code (tenant in the path; no authentication; masked minimal view). */
    @GetMapping("/public/{tenant}/verify")
    public VerificationService.Result verifyCode(@PathVariable String tenant, @RequestParam String code) {
        return support.publicTx(tenant, (c, ctx) -> verification.verifyCode(c, ctx, code));
    }

    /** Public verification of a scanned QR token (the tenant is inside the signed payload). */
    @PostMapping("/public/verify-qr")
    public VerificationService.Result verifyQr(@RequestBody Map<String, Object> body) {
        String token = Maps.required(body, "token");
        String tenant = verification.parseQr(token).map(p -> Maps.str(p, "tn")).orElse("-");
        return support.publicTx(tenant, (c, ctx) -> verification.verifyQr(c, token));
    }
}
