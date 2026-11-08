// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.rest;

import org.sangam.registry.party.IdentifierType;
import org.sangam.registry.party.PartyRecords;
import org.sangam.registry.party.PartyService;
import org.sangam.registry.support.Maps;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Party / beneficiary registry REST API (N14) under {@code /api/v1/registry/parties}. */
@RestController
@RequestMapping("/api/v1/registry/parties")
public class PartyController {

    private static final String IDEM = "Idempotency-Key";
    private static final String PURPOSE = "X-Purpose";

    private final PartyService parties;
    private final RegistryRestSupport support;

    public PartyController(PartyService parties, RegistryRestSupport support) {
        this.parties = parties;
        this.support = support;
    }

    // ------------------------------------------------------------------------------------------------- parties

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestHeader(value = IDEM, required = false) String key,
                                                      @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(support.txOp(key, (c, ctx) -> parties.create(c, ctx, body).toMap()));
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String kind,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String name,
                                          @RequestParam(required = false) String idType,
                                          @RequestParam(required = false) String idValue,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size,
                                          @RequestHeader(value = PURPOSE, required = false) String purpose) {
        return support.tx(purpose, (c, ctx) -> parties.list(c, ctx, kind, status, name, idType, idValue, page, size)
                .stream().map(PartyRecords.Party::toMap).toList());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id,
                                   @RequestHeader(value = PURPOSE, required = false) String purpose) {
        return support.tx(purpose, (c, ctx) -> parties.view(c, ctx, id));
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestHeader(value = IDEM, required = false) String key,
                                      @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> parties.update(c, ctx, id, Maps.lng(body, "expectedVersion"), body).toMap());
    }

    @PostMapping("/{id}/deactivate")
    public Map<String, Object> deactivate(@PathVariable String id, @RequestHeader(value = IDEM, required = false) String key,
                                          @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> parties.deactivate(c, ctx, id, Maps.str(body, "reason")).toMap());
    }

    @PostMapping("/{id}/reactivate")
    public Map<String, Object> reactivate(@PathVariable String id, @RequestHeader(value = IDEM, required = false) String key,
                                          @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> parties.reactivate(c, ctx, id, Maps.str(body, "reason")).toMap());
    }

    @GetMapping("/{id}/resolve")
    public Map<String, Object> resolve(@PathVariable String id) {
        return support.tx((c, ctx) -> Maps.of("partyId", id, "resolvedId", parties.resolve(c, ctx, id)));
    }

    @GetMapping("/{id}/audit/verify")
    public Object verifyAudit(@PathVariable String id) {
        return support.tx((c, ctx) -> parties.verifyAudit(c, ctx, id));
    }

    // --------------------------------------------------------------------------------------------- identifiers

    @GetMapping("/{id}/identifiers")
    public List<Map<String, Object>> identifiers(@PathVariable String id,
                                                 @RequestHeader(value = PURPOSE, required = false) String purpose) {
        return support.tx(purpose, (c, ctx) -> parties.listIdentifiers(c, ctx, id));
    }

    @PostMapping("/{id}/identifiers")
    public ResponseEntity<Map<String, Object>> addIdentifier(@PathVariable String id,
                                                             @RequestHeader(value = IDEM, required = false) String key,
                                                             @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(support.txOp(key, (c, ctx) -> parties.addIdentifier(c, ctx, id, body).toMap()));
    }

    @PostMapping("/identifiers/{identifierId}/retire")
    public Map<String, Object> retireIdentifier(@PathVariable String identifierId,
                                                @RequestHeader(value = IDEM, required = false) String key,
                                                @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> parties.retireIdentifier(c, ctx, identifierId, Maps.str(body, "reason")).toMap());
    }

    @PostMapping("/identifiers/{identifierId}/reveal")
    public Map<String, Object> reveal(@PathVariable String identifierId,
                                      @RequestHeader(value = PURPOSE, required = false) String purpose) {
        return support.tx(purpose, (c, ctx) -> Maps.of("identifierId", identifierId,
                "value", parties.revealIdentifier(c, ctx, identifierId)));
    }

    // ------------------------------------------------------------------------------- relationships, addresses

    @GetMapping("/{id}/relationships")
    public List<Map<String, Object>> relationships(@PathVariable String id,
                                                   @RequestHeader(value = PURPOSE, required = false) String purpose) {
        return support.tx(purpose, (c, ctx) -> parties.relationships(c, ctx, id).stream()
                .map(PartyRecords.Relationship::toMap).toList());
    }

    @PostMapping("/{id}/relationships")
    public ResponseEntity<Map<String, Object>> addRelationship(@PathVariable String id,
                                                               @RequestHeader(value = IDEM, required = false) String key,
                                                               @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(support.txOp(key, (c, ctx) -> parties.addRelationship(c, ctx, id,
                Maps.required(body, "toParty"), Maps.required(body, "type"), Maps.map(body, "attributes"),
                Maps.instant(body, "validFrom"), Maps.instant(body, "validTo")).toMap()));
    }

    @PostMapping("/relationships/{relationshipId}/end")
    public Map<String, Object> endRelationship(@PathVariable String relationshipId,
                                               @RequestHeader(value = IDEM, required = false) String key,
                                               @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> parties.endRelationship(c, ctx, relationshipId, Maps.str(body, "reason")).toMap());
    }

    @GetMapping("/{id}/addresses")
    public List<Map<String, Object>> addresses(@PathVariable String id,
                                               @RequestHeader(value = PURPOSE, required = false) String purpose) {
        return support.tx(purpose, (c, ctx) -> parties.addresses(c, ctx, id).stream().map(PartyRecords.Address::toMap).toList());
    }

    @PostMapping("/{id}/addresses")
    public ResponseEntity<Map<String, Object>> addAddress(@PathVariable String id,
                                                          @RequestHeader(value = IDEM, required = false) String key,
                                                          @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(support.txOp(key, (c, ctx) -> parties.addAddress(c, ctx, id, body).toMap()));
    }

    @PostMapping("/addresses/{addressId}/retire")
    public Map<String, Object> retireAddress(@PathVariable String addressId,
                                             @RequestHeader(value = IDEM, required = false) String key,
                                             @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> parties.retireAddress(c, ctx, addressId, Maps.str(body, "reason")).toMap());
    }

    @GetMapping("/{id}/household")
    public Map<String, Object> household(@PathVariable String id, @RequestParam(required = false) List<String> types,
                                         @RequestParam(defaultValue = "1") int depth,
                                         @RequestHeader(value = PURPOSE, required = false) String purpose) {
        return support.tx(purpose, (c, ctx) -> parties.household(c, ctx, id, types, depth).toMap());
    }

    // ----------------------------------------------------------------------------------------------- snapshots

    @PostMapping("/{id}/snapshots")
    public ResponseEntity<Map<String, Object>> snapshot(@PathVariable String id,
                                                        @RequestHeader(value = PURPOSE, required = false) String purpose,
                                                        @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(support.tx(purpose, (c, ctx) ->
                parties.snapshot(c, ctx, id, Maps.required(body, "reference"), Maps.map(body, "extra")).toMap()));
    }

    @GetMapping("/snapshots")
    public List<Map<String, Object>> snapshots(@RequestParam(required = false) String reference,
                                               @RequestParam(required = false) String partyId) {
        return support.tx((c, ctx) -> parties.snapshots(c, ctx, reference, partyId).stream()
                .map(PartyRecords.Snapshot::toMap).toList());
    }

    @GetMapping("/snapshots/{snapshotId}")
    public Map<String, Object> getSnapshot(@PathVariable String snapshotId) {
        return support.tx((c, ctx) -> parties.getSnapshot(c, ctx, snapshotId).toMap());
    }

    @GetMapping("/snapshots/{snapshotId}/verify")
    public Map<String, Object> verifySnapshot(@PathVariable String snapshotId) {
        return support.tx((c, ctx) -> Maps.of("snapshotId", snapshotId, "valid", parties.verifySnapshot(c, ctx, snapshotId)));
    }

    // ------------------------------------------------------------------------------------------------ matching

    @PostMapping("/match")
    public List<Map<String, Object>> match(@RequestBody Map<String, Object> probe) {
        return support.tx((c, ctx) -> parties.findMatches(c, ctx, probe).stream().map(PartyRecords.MatchResult::toMap).toList());
    }

    @PostMapping("/{id}/match")
    public List<Map<String, Object>> rematch(@PathVariable String id, @RequestParam(defaultValue = "true") boolean record) {
        return support.tx((c, ctx) -> parties.matchParty(c, ctx, id, record).stream()
                .map(PartyRecords.MatchResult::toMap).toList());
    }

    @GetMapping("/candidates")
    public List<Map<String, Object>> candidates(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) String partyId,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        return support.tx((c, ctx) -> parties.candidates(c, ctx, status, partyId, page, size).stream()
                .map(PartyRecords.Candidate::toMap).toList());
    }

    @PostMapping("/candidates/{candidateId}/confirm")
    public Map<String, Object> confirm(@PathVariable String candidateId,
                                       @RequestHeader(value = IDEM, required = false) String key,
                                       @RequestBody(required = false) Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> parties.confirmCandidate(c, ctx, candidateId, Maps.str(body, "survivorId"),
                Maps.str(body, "reason")).toMap());
    }

    @PostMapping("/candidates/{candidateId}/reject")
    public Map<String, Object> reject(@PathVariable String candidateId,
                                      @RequestHeader(value = IDEM, required = false) String key,
                                      @RequestBody(required = false) Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> parties.rejectCandidate(c, ctx, candidateId, Maps.str(body, "reason")).toMap());
    }

    // --------------------------------------------------------------------------------------------- merge

    @PostMapping("/merge")
    public Map<String, Object> merge(@RequestHeader(value = IDEM, required = false) String key,
                                     @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> parties.merge(c, ctx, Maps.required(body, "survivorId"),
                Maps.required(body, "mergedId"), Maps.str(body, "reason"), Maps.str(body, "candidateId")).toMap());
    }

    @PostMapping("/merges/{mergeEventId}/unmerge")
    public Map<String, Object> unmerge(@PathVariable String mergeEventId,
                                       @RequestHeader(value = IDEM, required = false) String key,
                                       @RequestBody Map<String, Object> body) {
        return support.txOp(key, (c, ctx) -> parties.unmerge(c, ctx, mergeEventId, Maps.str(body, "reason")).toMap());
    }

    @GetMapping("/{id}/merges")
    public List<Map<String, Object>> merges(@PathVariable String id) {
        return support.tx((c, ctx) -> parties.merges(c, ctx, id).stream().map(PartyRecords.MergeEvent::toMap).toList());
    }

    // ----------------------------------------------------------------------------------------- configuration

    @GetMapping("/config/identifier-types")
    public List<Map<String, Object>> identifierTypes() {
        return support.tx((c, ctx) -> parties.identifierTypes(c, ctx.tenant()).stream().map(IdentifierType::toMap).toList());
    }

    /** Saves identifier types for the caller's tenant (stored; overrides the pack seed). */
    @PutMapping("/config/identifier-types")
    public List<Map<String, Object>> saveIdentifierTypes(@RequestBody List<Map<String, Object>> body) {
        return support.tx((c, ctx) -> parties.saveIdentifierTypes(c, ctx, body).stream()
                .map(IdentifierType::toMap).toList());
    }

    /** Hides an identifier type for the caller's tenant (stored). */
    @org.springframework.web.bind.annotation.DeleteMapping("/config/identifier-types/{typeKey}")
    public org.springframework.http.ResponseEntity<Void> retireIdentifierType(@PathVariable String typeKey) {
        support.tx((c, ctx) -> {
            parties.retireIdentifierType(c, ctx, typeKey);
            return null;
        });
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @GetMapping("/config/match")
    public Map<String, Object> matchConfig() {
        return support.tx((c, ctx) -> parties.matchConfig(c, ctx.tenant()).toMap());
    }

    /** Saves the match configuration for the caller's tenant (stored; overrides the pack seed). */
    @PutMapping("/config/match")
    public Map<String, Object> saveMatchConfig(@RequestBody Map<String, Object> body) {
        return support.tx((c, ctx) -> parties.saveMatchConfig(c, ctx, body).toMap());
    }
}
