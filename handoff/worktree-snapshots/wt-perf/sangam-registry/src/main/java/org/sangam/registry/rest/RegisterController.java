// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.rest;

import org.sangam.registry.register.RegisterBookService;
import org.sangam.registry.register.RegisterDefinition;
import org.sangam.registry.support.Maps;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Statutory register books REST API. Base path {@code /api/v1/registry/registers}. */
@RestController
@RequestMapping("/api/v1/registry/registers")
public class RegisterController {

    private static final String IDEM = "Idempotency-Key";

    private final RegisterBookService registers;
    private final RegistryRestSupport support;

    public RegisterController(RegisterBookService registers, RegistryRestSupport support) {
        this.registers = registers;
        this.support = support;
    }

    @PutMapping("/{registerKey}")
    public Map<String, Object> define(@PathVariable String registerKey, @RequestBody Map<String, Object> body) {
        Map<String, Object> def = new LinkedHashMap<>(body);
        def.put("registerKey", registerKey);
        return support.tx((c, ctx) -> registers.define(c, ctx, def).toMap());
    }

    @GetMapping
    public List<Map<String, Object>> definitions() {
        return support.tx((c, ctx) -> registers.definitions(c, ctx.tenant()).stream().map(RegisterDefinition::toMap).toList());
    }

    @GetMapping("/{registerKey}")
    public Map<String, Object> definition(@PathVariable String registerKey) {
        return support.tx((c, ctx) -> registers.definition(c, ctx.tenant(), registerKey).toMap());
    }

    @PostMapping("/{registerKey}/entries")
    public ResponseEntity<Map<String, Object>> append(@PathVariable String registerKey,
                                                      @RequestHeader(value = IDEM, required = false) String idem,
                                                      @RequestBody Map<String, Object> body) {
        RegisterBookService.Entry e = support.txOp(idem, (c, ctx) -> registers.append(c, ctx, registerKey,
                Maps.str(body, "scope"), Maps.date(body, "date"), Maps.map(body, "columns"), Maps.str(body, "refType"),
                Maps.str(body, "refId")));
        return ResponseEntity.status(201).body(e.toMap());
    }

    @GetMapping("/{registerKey}/books")
    public List<Map<String, Object>> books(@PathVariable String registerKey, @RequestParam(required = false) String scope,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        return support.tx((c, ctx) -> registers.books(c, ctx, registerKey, scope, page, size).stream()
                .map(RegisterBookService.Book::toMap).toList());
    }

    @GetMapping("/books/{bookId}")
    public Map<String, Object> book(@PathVariable String bookId) {
        return support.tx((c, ctx) -> registers.book(c, ctx, bookId).toMap());
    }

    @GetMapping("/books/{bookId}/entries")
    public List<Map<String, Object>> entries(@PathVariable String bookId, @RequestParam(defaultValue = "1") long from,
                                             @RequestParam(defaultValue = "100") long count) {
        long to = from + Math.max(1, Math.min(count, 1000)) - 1;
        return support.tx((c, ctx) -> registers.entries(c, ctx, bookId, from, to).stream()
                .map(RegisterBookService.Entry::toMap).toList());
    }

    @GetMapping("/books/{bookId}/entries/{entryNo}")
    public Map<String, Object> effective(@PathVariable String bookId, @PathVariable long entryNo) {
        return support.tx((c, ctx) -> registers.effective(c, ctx, bookId, entryNo));
    }

    @PostMapping("/books/{bookId}/entries/{entryNo}/corrections")
    public ResponseEntity<Map<String, Object>> correct(@PathVariable String bookId, @PathVariable long entryNo,
                                                       @RequestHeader(value = IDEM, required = false) String idem,
                                                       @RequestBody Map<String, Object> body) {
        RegisterBookService.Entry e = support.txOp(idem, (c, ctx) -> registers.correct(c, ctx, bookId, entryNo,
                Maps.map(body, "columns"), Maps.required(body, "reason"), Maps.str(body, "countersignedBy")));
        return ResponseEntity.status(201).body(e.toMap());
    }

    @GetMapping("/entries")
    public List<Map<String, Object>> entriesFor(@RequestParam String refType, @RequestParam String refId) {
        return support.tx((c, ctx) -> registers.entriesFor(c, ctx, refType, refId).stream()
                .map(RegisterBookService.Entry::toMap).toList());
    }

    @PostMapping("/books/{bookId}/close")
    public Map<String, Object> close(@PathVariable String bookId, @RequestBody(required = false) Map<String, Object> body) {
        return support.tx((c, ctx) -> registers.close(c, ctx, bookId, Maps.str(body, "reason")).toMap());
    }

    @GetMapping("/books/{bookId}/verify")
    public RegisterBookService.VerifyResult verify(@PathVariable String bookId) {
        return support.tx((c, ctx) -> registers.verify(c, ctx, bookId));
    }

    @PostMapping("/books/{bookId}/extracts")
    public ResponseEntity<Map<String, Object>> extract(@PathVariable String bookId,
                                                       @RequestHeader(value = IDEM, required = false) String idem,
                                                       @RequestBody Map<String, Object> body) {
        RegisterBookService.Extract x = support.txOp(idem, (c, ctx) -> registers.certifiedExtract(c, ctx, bookId,
                Long.parseLong(Maps.required(body, "fromEntry")), Long.parseLong(Maps.required(body, "toEntry")),
                Maps.str(body, "purpose")));
        return ResponseEntity.status(201).body(x.toMap());
    }

    @GetMapping("/extracts/{extractId}")
    public Map<String, Object> getExtract(@PathVariable String extractId) {
        return support.tx((c, ctx) -> registers.extract(c, ctx, extractId).toMap());
    }

    /** Public verification of a certified extract (tenant in the path; no authentication). */
    @GetMapping("/public/{tenant}/extracts/verify")
    public RegisterBookService.ExtractVerification verifyExtract(@PathVariable String tenant, @RequestParam String code) {
        return support.publicTx(tenant, (c, ctx) -> registers.verifyExtract(c, ctx, code));
    }
}
