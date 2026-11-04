// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.instrument;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.registry.RegistryContext;
import org.sangam.registry.RegistryException;
import org.sangam.registry.RegistryPorts;
import org.sangam.registry.kernel.Lifecycle;
import org.sangam.registry.kernel.ObjectKind;
import org.sangam.registry.kernel.ObjectTypeDefinition;
import org.sangam.registry.kernel.RegisteredObject;
import org.sangam.registry.kernel.RegisteredObjectService;
import org.sangam.registry.kernel.ValidityRule;
import org.sangam.registry.support.Db;
import org.sangam.registry.support.Json;
import org.sangam.registry.support.Maps;
import org.sangam.registry.support.RegistryAudit;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Issued instruments (N06): licences, permits, certificates, registrations, passes, consents: whatever the type
 * configuration says. The life of the issued thing: issue → (activate) → renewal due → renew, with suspend /
 * reinstate, revoke, surrender, expire, holder transfer, transfer by re-issue, amendment (endorsement) and duplicate
 * issue. Each step is a kernel transition (versioned, audited, outbox event), refreshes the verification code where
 * the printed facts change, and calls the document hook ({@link RegistryPorts.DocumentPort}) with the type's template
 * for that purpose, then the publish hook ({@link RegistryPorts.PublishPort}, e.g. a document locker push).
 */
public final class InstrumentService {

    /** Issue request. {@code conditions} are recorded as the first endorsement. */
    public record IssueRequest(String typeKey, String holderPartyId, String subjectRef, Map<String, Object> attributes,
                               List<Object> conditions, LocalDate validFrom, LocalDate validTo, String numberScope,
                               String displayName, String externalRef, boolean activate, String sourceRef) {
        public static IssueRequest from(Map<String, Object> m) {
            Object cond = m.get("conditions");
            return new IssueRequest(Maps.required(m, "typeKey"), Maps.str(m, "holderPartyId"), Maps.str(m, "subjectRef"),
                    Maps.map(m, "attributes"), cond instanceof List<?> l ? new ArrayList<>(l) : List.of(),
                    Maps.date(m, "validFrom"), Maps.date(m, "validTo"), Maps.str(m, "numberScope"),
                    Maps.str(m, "displayName"), Maps.str(m, "externalRef"), Maps.bool(m, "activate", true),
                    Maps.str(m, "sourceRef"));
        }
    }

    /** What an issuing step returns: the instrument, its new verification code / QR, and document references. */
    public record Issued(RegisteredObject instrument, String verificationCode, String qrToken, String documentRef,
                         String publishedRef) {
        public Map<String, Object> toMap() {
            return Maps.of("instrument", instrument.toMap(), "verificationCode", verificationCode, "qrToken", qrToken,
                    "documentRef", documentRef, "publishedRef", publishedRef);
        }
    }

    public record Endorsement(int seq, String type, Map<String, Object> content, Instant madeAt, String madeBy) {
    }

    public record Document(int seq, String purpose, String templateKey, String documentRef, String digestHex,
                           String publishedRef, Instant createdAt) {
    }

    /** Result of a sweep job. */
    public record SweepResult(int processed, List<String> ids, List<String> failures) {
    }

    private final Dialect dialect;
    private final RegisteredObjectService objects;
    private final VerificationService verification;
    private final RegistryAudit audit;
    private final RegistryPorts ports;

    public InstrumentService(Dialect dialect, RegisteredObjectService objects, VerificationService verification,
                             RegistryAudit audit, RegistryPorts ports) {
        this.dialect = dialect;
        this.objects = objects;
        this.verification = verification;
        this.audit = audit;
        this.ports = ports;
    }

    // --------------------------------------------------------------------------------------------------- issue

    /** Issues an instrument (the closing step of an application process calls this). */
    public Issued issue(Connection c, RegistryContext ctx, IssueRequest req) {
        ObjectTypeDefinition type = objects.types().current(c, ctx.tenant(), req.typeKey());
        if (type.kind() != ObjectKind.INSTRUMENT) {
            throw RegistryException.invalid("type " + type.typeKey() + " is a " + type.kind() + ", not an INSTRUMENT");
        }
        Map<String, Object> attrs = new LinkedHashMap<>(req.attributes() == null ? Map.of() : req.attributes());
        RegisteredObject o = objects.create(c, ctx, type, new RegisteredObjectService.CreateRequest(type.typeKey(), attrs,
                req.displayName(), req.holderPartyId(), req.subjectRef(), null, req.externalRef(), req.validFrom(),
                req.validTo(), req.numberScope(), true, true), type.lifecycle().initial());
        String number = o.number();
        if (number == null && type.numbering() != null) {
            number = objects.assignNumber(c, ctx, type, o.attrs(), req.numberScope(), o.id()).formatted();
        }
        String finalNumber = number;
        boolean activate = req.activate() && type.lifecycle().has("activate")
                && !type.lifecycle().available(o.state()).isEmpty() && type.lifecycle().available(o.state()).contains("activate");
        o = objects.mutate(c, ctx, o.id(), null, "ISSUE", req.sourceRef(), x -> {
            RegisteredObject n = finalNumber == null ? x : x.withNumber(finalNumber).withDisplayName(
                    req.displayName() != null ? req.displayName() : finalNumber);
            return activate ? n.withState(type.lifecycle().transition("activate", x.state()).to(), null, null) : n;
        });
        if (req.conditions() != null && !req.conditions().isEmpty()) {
            endorse(c, ctx, o.id(), "CONDITIONS", Maps.of("conditions", req.conditions()));
        }
        return documents(c, ctx, o, type, "ISSUE", true);
    }

    // ----------------------------------------------------------------------------------------------- renewal

    /**
     * Renews: allowed inside the renewal window, or after expiry within the grace period (late renewal). Pass
     * {@code force=true} with a reason to renew outside the window (recorded).
     */
    public Issued renew(Connection c, RegistryContext ctx, String id, Long expectedVersion, LocalDate givenValidTo,
                        Map<String, Object> attributePatch, String reason, boolean force) {
        RegisteredObject current = objects.get(c, ctx, id);
        ObjectTypeDefinition type = objects.typeOf(c, current);
        Instant now = Db.now(c, dialect);
        if (!force && !type.validity().renewalOpen(current.validTo(), now, ctx.zone())) {
            throw RegistryException.state("renewal is not open for " + current.number() + " (window "
                    + type.validity().renewalWindow() + " before, grace " + type.validity().grace() + " after "
                    + current.validTo() + ")");
        }
        if (force && (reason == null || reason.isBlank())) {
            throw RegistryException.invalid("a renewal outside the window needs a reason");
        }
        RegisteredObject o = objects.mutate(c, ctx, id, expectedVersion, "RENEW", reason, x -> {
            Lifecycle.Transition t = objects.checkTransition(c, ctx, x, "renew", reason, null, null);
            ValidityRule.Window w = type.validity().renew(x.validTo(), now.atZone(ctx.zone()).toLocalDate(),
                    x.renewalCount(), givenValidTo, ctx.zone());
            RegisteredObject n = x.withState(t.to(), null, null).withValidity(x.validFrom(), w.validTo(), w.renewalDueAt(),
                    x.renewalCount() + 1);
            return attributePatch == null || attributePatch.isEmpty() ? n : n.withAttrs(type.schema().validate(attributePatch, x.attrs()));
        });
        return documents(c, ctx, o, type, "RENEWAL", false);
    }

    /**
     * Deemed renewal: evaluates the type's {@code deemedRenewalDecision} (DMN) with the instrument's facts; when the
     * decision returns {@code deemed=true} the instrument is renewed by the system on the competent authority's
     * behalf, recording the decision output as the reason.
     *
     * @return the renewal, or null when the decision does not deem it renewed
     */
    public Issued deemedRenewal(Connection c, RegistryContext ctx, String id) {
        RegisteredObject o = objects.get(c, ctx, id);
        ObjectTypeDefinition type = objects.typeOf(c, o);
        String key = type.validity().deemedRenewalDecision();
        if (key == null) {
            throw RegistryException.state("type " + type.typeKey() + " has no deemed renewal rule");
        }
        Map<String, Object> vars = new LinkedHashMap<>(o.attrs());
        vars.put("state", o.state());
        vars.put("renewalCount", o.renewalCount());
        vars.put("validTo", o.validTo() == null ? null : o.validTo().toString());
        vars.put("typeKey", o.typeKey());
        List<Map<String, Object>> out = ports.decisions().evaluate(ctx.tenant(), key, vars);
        boolean deemed = !out.isEmpty() && Maps.bool(out.get(0), "deemed", false);
        if (!deemed) {
            return null;
        }
        String reason = "deemed renewal by " + key + ": " + Json.write(out.get(0));
        return renew(c, ctx, id, null, null, null, reason.length() > 250 ? reason.substring(0, 250) : reason, true);
    }

    // --------------------------------------------------------------------------------------- state changes

    public RegisteredObject suspend(Connection c, RegistryContext ctx, String id, Long expectedVersion, String reason,
                                    Instant until) {
        return objects.transition(c, ctx, id, expectedVersion, "suspend", reason, until, null);
    }

    public RegisteredObject reinstate(Connection c, RegistryContext ctx, String id, Long expectedVersion, String reason) {
        return objects.transition(c, ctx, id, expectedVersion, "reinstate", reason, null, null);
    }

    public RegisteredObject revoke(Connection c, RegistryContext ctx, String id, Long expectedVersion, String reason,
                                   String countersignedBy) {
        RegisteredObject o = objects.transition(c, ctx, id, expectedVersion, "revoke", reason, null, countersignedBy);
        supersedeCodes(c, ctx, id);
        return o;
    }

    public RegisteredObject surrender(Connection c, RegistryContext ctx, String id, Long expectedVersion, String reason) {
        RegisteredObject o = objects.transition(c, ctx, id, expectedVersion, "surrender", reason, null, null);
        supersedeCodes(c, ctx, id);
        return o;
    }

    /** Expires an instrument with the type's configured expiry transition ({@code extensions.expireTransition}). */
    public RegisteredObject expire(Connection c, RegistryContext ctx, String id) {
        return objects.transition(c, ctx, id, null, sweepName(c, ctx, id, RegisteredObjectService.SWEEP_EXPIRE),
                "validity ended", null, null);
    }

    /** Marks renewal due with the type's configured transition ({@code extensions.renewalDueTransition}). */
    public RegisteredObject markRenewalDue(Connection c, RegistryContext ctx, String id) {
        return objects.transition(c, ctx, id, null, sweepName(c, ctx, id, RegisteredObjectService.SWEEP_RENEWAL_DUE),
                null, null, null);
    }

    private String sweepName(Connection c, RegistryContext ctx, String id, String purpose) {
        return objects.sweepTransition(objects.typeOf(c, objects.get(c, ctx, id)), purpose);
    }

    /** A generic named transition of a configured lifecycle (custom states a pack adds). */
    public RegisteredObject transition(Connection c, RegistryContext ctx, String id, Long expectedVersion, String name,
                                       String reason, Instant until, String countersignedBy) {
        return objects.transition(c, ctx, id, expectedVersion, name, reason, until, countersignedBy);
    }

    // -------------------------------------------------------------------------------------------- transfers

    /** Holder change on the same instrument (the type must be transferable); a new code and document are issued. */
    public Issued transferHolder(Connection c, RegistryContext ctx, String id, Long expectedVersion, String newHolderPartyId,
                                 String reason) {
        RegisteredObject current = objects.get(c, ctx, id);
        ObjectTypeDefinition type = objects.typeOf(c, current);
        if (!type.transferable()) {
            throw RegistryException.state("type " + type.typeKey() + " is not transferable");
        }
        if (newHolderPartyId == null || newHolderPartyId.equals(current.holderPartyId())) {
            throw RegistryException.invalid("a different new holder is required");
        }
        RegisteredObject o = objects.mutate(c, ctx, id, expectedVersion, "TRANSFER_HOLDER", reason, x -> {
            Lifecycle.Transition t = objects.checkTransition(c, ctx, x, "transferHolder", reason, null, null);
            return x.withState(t.to(), reason, null).withHolder(newHolderPartyId);
        });
        return documents(c, ctx, o, type, "TRANSFER", true);
    }

    /**
     * Transfer by re-issue: the old instrument ends as TRANSFERRED and a new one (new number, same validity end
     * unless the type's rule says otherwise) is issued to the new holder, linked by {@code transferredFrom}.
     */
    public Issued transferByReissue(Connection c, RegistryContext ctx, String id, String newHolderPartyId, String reason) {
        RegisteredObject old = objects.get(c, ctx, id);
        ObjectTypeDefinition type = objects.typeOf(c, old);
        if (!type.transferable()) {
            throw RegistryException.state("type " + type.typeKey() + " is not transferable");
        }
        objects.transition(c, ctx, id, null, "transfer", reason, null, null);
        supersedeCodes(c, ctx, id);
        Map<String, Object> attrs = new LinkedHashMap<>(old.attrs());
        attrs.put("transferredFrom", old.number() == null ? old.id() : old.number());
        LocalDate to = old.validTo() == null ? null : old.validTo().atZone(ctx.zone()).toLocalDate().minusDays(1);
        Issued issued = issue(c, ctx, new IssueRequest(old.typeKey(), newHolderPartyId, old.subjectRef(),
                type.schema().additionalAllowed() ? attrs : old.attrs(), List.of(), null, to, null, null, null, true,
                "transfer of " + old.id()));
        audit.record(c, ctx, id, "INSTRUMENT_TRANSFERRED_BY_REISSUE", Maps.of("newInstrumentId", issued.instrument().id(),
                "newNumber", issued.instrument().number(), "reason", reason));
        return issued;
    }

    // ---------------------------------------------------------------------------- amendments and duplicates

    /**
     * Amendment: an endorsement records the change; the attributes change even where the schema marks a field
     * immutable (an amendment is the lawful way to change it), and a new document is issued.
     */
    public Issued amend(Connection c, RegistryContext ctx, String id, Long expectedVersion, Map<String, Object> patch,
                        String endorsementText) {
        if (endorsementText == null || endorsementText.isBlank()) {
            throw RegistryException.invalid("an amendment needs the endorsement text");
        }
        RegisteredObject current = objects.get(c, ctx, id);
        ObjectTypeDefinition type = objects.typeOf(c, current);
        RegisteredObject o = objects.mutate(c, ctx, id, expectedVersion, "AMEND", endorsementText, x -> {
            Lifecycle.Transition t = type.lifecycle().has("amend") ? objects.checkTransition(c, ctx, x, "amend", endorsementText, null, null) : null;
            Map<String, Object> merged = new LinkedHashMap<>(x.attrs());
            if (patch != null) {
                merged.putAll(patch);
            }
            RegisteredObject n = x.withAttrs(type.schema().validate(merged, null));
            return t == null ? n : n.withState(t.to(), null, null);
        });
        endorse(c, ctx, id, "AMENDMENT", Maps.of("text", endorsementText, "changes", patch));
        return documents(c, ctx, o, type, "AMENDMENT", false);
    }

    /** Duplicate issue (lost or damaged document): a new code (the old one is superseded) and a new document. */
    public Issued duplicate(Connection c, RegistryContext ctx, String id, String reason) {
        RegisteredObject o = objects.get(c, ctx, id);
        ObjectTypeDefinition type = objects.typeOf(c, o);
        if (!type.lifecycle().live(o.state())) {
            throw RegistryException.state("no duplicate of an instrument in state " + o.state());
        }
        endorse(c, ctx, id, "DUPLICATE_ISSUED", Maps.of("reason", reason));
        return documents(c, ctx, o, type, "DUPLICATE", true);
    }

    /** Appends an endorsement (amendment, condition, note) to the instrument. */
    public Endorsement endorse(Connection c, RegistryContext ctx, String id, String endorsementType, Map<String, Object> content) {
        objects.lock(c, ctx, id);
        int seq = Jdbc.one(c, "SELECT MAX(seq) FROM REG_ENDORSEMENT WHERE object_id=? AND tenant_id=?",
                rs -> rs.getInt(1), id, ctx.tenant()).orElse(0) + 1;
        Instant now = Db.now(c, dialect);
        Jdbc.update(c, "INSERT INTO REG_ENDORSEMENT(object_id, seq, tenant_id, endorsement_type, content_json, made_at, made_by)"
                + " VALUES (?,?,?,?,?,?,?)", id, seq, ctx.tenant(), endorsementType, Json.write(content), now, ctx.actor());
        audit.record(c, ctx, id, "INSTRUMENT_ENDORSED", Maps.of("seq", seq, "type", endorsementType));
        return new Endorsement(seq, endorsementType, content, now, ctx.actor());
    }

    public List<Endorsement> endorsements(Connection c, RegistryContext ctx, String id) {
        objects.get(c, ctx, id);
        return Jdbc.query(c, "SELECT seq, endorsement_type, content_json, made_at, made_by FROM REG_ENDORSEMENT WHERE object_id=?"
                        + " AND tenant_id=? ORDER BY seq", rs -> new Endorsement(rs.getInt(1), rs.getString(2),
                        Json.map(rs.getString(3)), Jdbc.instant(rs, "made_at"), rs.getString(5)), id, ctx.tenant());
    }

    public List<Document> documentsOf(Connection c, RegistryContext ctx, String id) {
        objects.get(c, ctx, id);
        return Jdbc.query(c, "SELECT doc_seq, purpose, template_key, document_ref, digest_hex, published_ref, created_at"
                        + " FROM REG_INSTRUMENT_DOC WHERE object_id=? AND tenant_id=? ORDER BY doc_seq",
                rs -> new Document(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), Jdbc.instant(rs, "created_at")), id, ctx.tenant());
    }

    /** Re-renders the current document for a purpose (e.g. after a template correction), without a new code. */
    public Issued regenerateDocument(Connection c, RegistryContext ctx, String id, String purpose) {
        RegisteredObject o = objects.get(c, ctx, id);
        return documents(c, ctx, o, objects.typeOf(c, o), purpose == null ? "ISSUE" : purpose, false);
    }

    // ------------------------------------------------------------------------------------------------ sweeps

    /**
     * Bulk expiry job: every instrument whose validity ended, in a state its type's lifecycle can expire from, takes
     * the configured expiry transition (one failure does not stop the sweep).
     */
    public SweepResult expireDue(Connection c, RegistryContext ctx, String typeKey, int limit) {
        Instant now = Db.now(c, dialect);
        return sweep(objects.dueForExpiry(c, ctx.tenant(), typeKey, now, limit), id -> expire(c, ctx, id));
    }

    /** Marks ACTIVE instruments whose renewal window opened as RENEWAL_DUE (renewal campaign input). */
    public SweepResult renewalDue(Connection c, RegistryContext ctx, String typeKey, int limit) {
        Instant now = Db.now(c, dialect);
        return sweep(objects.dueForRenewal(c, ctx.tenant(), typeKey, now, limit), id -> markRenewalDue(c, ctx, id));
    }

    /** Reinstates suspensions whose end date passed. */
    public SweepResult reinstateDue(Connection c, RegistryContext ctx, String typeKey, int limit) {
        Instant now = Db.now(c, dialect);
        return sweep(objects.dueForReinstatement(c, ctx.tenant(), typeKey, now, limit),
                id -> objects.transition(c, ctx, id, null, sweepName(c, ctx, id, RegisteredObjectService.SWEEP_REINSTATE),
                        "suspension period ended", null, null));
    }

    private SweepResult sweep(List<String> ids, java.util.function.Consumer<String> step) {
        List<String> done = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String id : ids) {
            try {
                step.accept(id);
                done.add(id);
            } catch (RegistryException e) {
                failed.add(id + ": " + e.getMessage());
            }
        }
        return new SweepResult(done.size(), done, failed);
    }

    // ------------------------------------------------------------------------------------- documents hook

    private void supersedeCodes(Connection c, RegistryContext ctx, String id) {
        Jdbc.update(c, "UPDATE REG_VERIFICATION SET status='SUPERSEDED', superseded_at=" + dialect.now()
                + " WHERE tenant_id=? AND target_id=? AND status='ACTIVE'", ctx.tenant(), id);
    }

    private Issued documents(Connection c, RegistryContext ctx, RegisteredObject o, ObjectTypeDefinition type,
                             String purpose, boolean newCode) {
        String code = newCode ? verification.issueCode(c, ctx, "INSTRUMENT", o.id()) : null;
        String qr = type.verification().qr() ? verification.qrToken(c, ctx, o) : null;
        String template = type.template(purpose);
        String docRef = null;
        String published = null;
        if (template != null) {
            Map<String, Object> model = new LinkedHashMap<>(o.toMap());
            model.put("typeTitle", type.title());
            model.put("purpose", purpose);
            model.put("verificationCode", code);
            model.put("qrToken", qr);
            model.put("endorsements", endorsements(c, ctx, o.id()).stream()
                    .map(e -> Maps.of("seq", e.seq(), "type", e.type(), "content", e.content())).toList());
            var doc = ports.documents().render(ctx, template, model);
            if (doc.isPresent()) {
                docRef = doc.get().documentRef();
                if (type.publishChannel() != null) {
                    published = ports.publisher().publish(ctx, type.publishChannel(), o.id(), docRef,
                            Maps.of("number", o.number(), "typeKey", o.typeKey(), "holderPartyId", o.holderPartyId(),
                                    "purpose", purpose)).orElse(null);
                }
                int seq = Jdbc.one(c, "SELECT MAX(doc_seq) FROM REG_INSTRUMENT_DOC WHERE object_id=? AND tenant_id=?",
                        rs -> rs.getInt(1), o.id(), ctx.tenant()).orElse(0) + 1;
                Jdbc.update(c, "INSERT INTO REG_INSTRUMENT_DOC(object_id, doc_seq, tenant_id, purpose, template_key, document_ref,"
                                + " digest_hex, published_ref, created_at) VALUES (?,?,?,?,?,?,?,?," + dialect.now() + ")",
                        o.id(), seq, ctx.tenant(), purpose, template, docRef, doc.get().sha256Hex(), published);
                audit.record(c, ctx, o.id(), "INSTRUMENT_DOCUMENT_GENERATED", Maps.of("purpose", purpose, "seq", seq,
                        "documentRef", docRef, "digest", doc.get().sha256Hex(), "publishedRef", published));
            }
        }
        return new Issued(o, code, qr, docRef, published);
    }
}
