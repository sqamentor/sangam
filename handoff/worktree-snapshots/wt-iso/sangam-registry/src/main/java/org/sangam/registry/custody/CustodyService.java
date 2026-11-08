// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.custody;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.registry.RegistryContext;
import org.sangam.registry.RegistryException;
import org.sangam.registry.RegistryPorts;
import org.sangam.registry.kernel.ObjectKind;
import org.sangam.registry.kernel.ObjectTypeDefinition;
import org.sangam.registry.kernel.RegisteredObject;
import org.sangam.registry.kernel.RegisteredObjectService;
import org.sangam.registry.support.Db;
import org.sangam.registry.support.Json;
import org.sangam.registry.support.Maps;
import org.sangam.registry.support.RegistryAudit;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Chain of custody of physical items (N18): any tracked, labelled item whose holder must be known at every moment
 * (seized or held property, evidence, physical files, samples, specimens, sealed packets, exhibits). Domain specifics
 * are configuration of the item's type (kind {@code CUSTODY_ITEM}): schema, receipt series
 * ({@code extensions.receiptSeries}, optional {@code extensions.receiptScopeAttribute}), receipt template
 * ({@code documents.RECEIPT}), {@code extensions.allowSamePersonTransfer} and {@code extensions.disposalTransition}.
 *
 * <p>Every custody fact is one {@code REG_CUSTODY_EVENT} row. Its {@code seq} is gapless per item, allocated under
 * the item row lock; {@code event_hash = SHA-256(prev_hash || JCS(envelope))} with genesis
 * {@code SHA-256("sangam-custody-v1:" || tenant || ":" || itemId)}; the same fact is appended to the core audit chain
 * (root = item id) in the same transaction. Moving a container moves its whole content tree, each nested item with
 * its own chained event. Lock order: the item row (and for container operations, rows in ascending id order), then
 * the kernel object row, then the audit root row.
 */
public final class CustodyService {

    public static final String IN_CUSTODY = "IN_CUSTODY";
    public static final String IN_TRANSIT = "IN_TRANSIT";
    public static final String DISPOSED = "DISPOSED";
    public static final String RELEASED = "RELEASED";

    private static final Set<String> LABEL_KINDS = Set.of("BARCODE", "QR", "RFID", "OTHER");
    private static final Set<String> METHODS = Set.of("DESTROY", "RETURN", "AUCTION", "TRANSFER_OUT", "RELEASE");
    private static final Set<String> RELEASE_METHODS = Set.of("RETURN", "TRANSFER_OUT", "RELEASE");
    private static final HexFormat HEX = HexFormat.of();
    private static final int MAX_DEPTH = 64;

    private static final String ITEM_COLS = "object_id, label, label_kind, description, case_ref, container_id,"
            + " container_flag, seal_no, seal_status, custodian_ref, location_ref, custody_state, pending_transfer_id,"
            + " event_count, head_hash, last_event_at, version";
    private static final String JOINED = "SELECT i.object_id, i.label, i.label_kind, i.description, i.case_ref,"
            + " i.container_id, i.container_flag, i.seal_no, i.seal_status, i.custodian_ref, i.location_ref,"
            + " i.custody_state, i.pending_transfer_id, i.event_count, i.head_hash, i.last_event_at, i.version,"
            + " o.type_key, o.state AS object_state, o.object_number"
            + " FROM REG_CUSTODY_ITEM i JOIN REG_OBJECT o ON o.id = i.object_id AND o.tenant_id = i.tenant_id";
    private static final String EVENT_COLS = "id, item_id, seq, event_type, from_custodian, to_custodian, location_ref,"
            + " condition_text, seal_no, seal_intact, from_signature_ref, to_signature_ref, witness_ref, receipt_number,"
            + " transfer_id, details_json, occurred_at, recorded_by, prev_hash, event_hash, audit_event_id";
    private static final String TRANSFER_COLS = "id, item_id, from_custodian, to_custodian, to_location, reason, status,"
            + " initiated_at, initiated_by, from_signature_ref, resolved_at, resolved_by, to_signature_ref,"
            + " condition_text, seal_intact, receipt_number";
    private static final String DISPOSAL_COLS = "id, item_id, method, authority_ref, status, ordered_at, ordered_by,"
            + " executed_at, executed_by, details_json";

    /** An appended event and the item state after it. */
    public record Appended(CustodyItem item, CustodyEvent event) {
    }

    /** Result of a chain check: the local custody chain and the core audit chain of the item. */
    public record ChainVerification(boolean ok, long eventsChecked, Long firstBadSeq, String reason, boolean auditOk,
                                    Long auditFirstBadSeq, String auditReason) {
    }

    /** Custody gaps found by {@link #gapCheck}. */
    public record GapReport(Instant checkedAt, List<CustodyItem> withoutCustodian, List<CustodyItem> inTransitTooLong,
                            List<CustodyItem> stale, List<CustodyItem> sealBroken) {
        public int total() {
            return withoutCustodian.size() + inTransitTooLong.size() + stale.size() + sealBroken.size();
        }
    }

    /** A list query; null fields do not filter. */
    public record Query(String typeKey, String custodianRef, String caseRef, String containerId, String custodyState,
                        String label, int page, int size) {
    }

    private record Receipt(String number, String documentRef) {
    }

    private record SealCheck(Boolean intact, boolean discrepancy) {
    }

    /** Builder of one event's facts. */
    private static final class Ev {
        final String type;
        String from;
        String to;
        String location;
        String condition;
        String sealNo;
        Boolean sealIntact;
        String fromSig;
        String toSig;
        String witness;
        String receipt;
        String transferId;
        Map<String, Object> details = new LinkedHashMap<>();
        Instant occurredAt;

        Ev(String type) {
            this.type = type;
        }

        Ev detail(String k, Object v) {
            if (v != null) {
                details.put(k, v);
            }
            return this;
        }
    }

    /** The mutable fields of an item after an event. */
    private static final class Next {
        String custodian;
        String location;
        String state;
        String sealNo;
        String sealStatus;
        String containerId;
        String pendingTransferId;

        Next(CustodyItem i) {
            custodian = i.custodianRef();
            location = i.locationRef();
            state = i.custodyState();
            sealNo = i.sealNo();
            sealStatus = i.sealStatus();
            containerId = i.containerId();
            pendingTransferId = i.pendingTransferId();
        }
    }

    private final Dialect dialect;
    private final RegisteredObjectService objects;
    private final RegistryAudit audit;
    private final RegistryPorts ports;

    public CustodyService(Dialect dialect, RegisteredObjectService objects, RegistryAudit audit, RegistryPorts ports) {
        this.dialect = dialect;
        this.objects = objects;
        this.audit = audit;
        this.ports = ports;
    }

    // ------------------------------------------------------------------------------------------- register

    /** Registers a tracked item (kernel object + custody row + REGISTERED event). */
    public CustodyItem register(Connection c, RegistryContext ctx, CustodyRequests.Register r) {
        ObjectTypeDefinition type = objects.types().current(c, ctx.tenant(), r.typeKey());
        if (type.kind() != ObjectKind.CUSTODY_ITEM) {
            throw RegistryException.invalid("type " + r.typeKey() + " is not a custody item type");
        }
        String kind = r.labelKind() == null ? "OTHER" : r.labelKind().toUpperCase();
        if (!LABEL_KINDS.contains(kind)) {
            throw RegistryException.invalid("labelKind must be one of " + LABEL_KINDS);
        }
        text("label", r.label());
        text("description", r.description());
        if (Jdbc.one(c, "SELECT object_id FROM REG_CUSTODY_ITEM WHERE tenant_id=? AND label=?", rs -> rs.getString(1),
                ctx.tenant(), r.label()).isPresent()) {
            throw RegistryException.conflict("label " + r.label() + " is already registered");
        }
        CustodyItem container = null;
        if (r.containerId() != null) {
            container = lockItem(c, ctx, r.containerId());
            requireActive(container);
            if (!container.container()) {
                throw RegistryException.invalid("item " + r.containerId() + " is not a container");
            }
            if (IN_TRANSIT.equals(container.custodyState())) {
                throw RegistryException.state("container " + r.containerId() + " is in transit");
            }
        }
        RegisteredObject o = objects.create(c, ctx, type, new RegisteredObjectService.CreateRequest(r.typeKey(),
                r.attributes(), r.displayName() != null ? r.displayName() : r.label(), r.holderPartyId(), r.caseRef(),
                null, r.externalRef(), null, null, null, true, true), type.lifecycle().initial());
        Instant now = Db.now(c, dialect);
        String custodian = container != null ? container.custodianRef() : r.custodianRef();
        String location = container != null ? container.locationRef() : r.locationRef();
        String sealStatus = r.sealNo() == null ? "NONE" : "INTACT";
        try {
            Jdbc.update(c, "INSERT INTO REG_CUSTODY_ITEM(object_id, tenant_id, label, label_kind, description, case_ref,"
                            + " container_id, container_flag, seal_no, seal_status, custodian_ref, location_ref, custody_state,"
                            + " pending_transfer_id, event_count, head_hash, last_event_at, version)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    o.id(), ctx.tenant(), r.label(), kind, r.description(), r.caseRef(), r.containerId(), r.container(),
                    r.sealNo(), sealStatus, custodian, location, IN_CUSTODY, null, 0L, null, now, 0L);
        } catch (Jdbc.LayerSqlException e) {
            if (dialect.isUniqueViolation(e.sql())) {
                throw RegistryException.conflict("label " + r.label() + " is already registered");
            }
            throw e;
        }
        CustodyItem item = new CustodyItem(o.id(), type.typeKey(), o.number(), o.state(), r.label(), kind,
                r.description(), r.caseRef(), r.containerId(), r.container(), r.sealNo(), sealStatus, custodian, location,
                IN_CUSTODY, null, 0, null, now, 0);
        Ev ev = new Ev("REGISTERED");
        ev.to = custodian;
        ev.location = location;
        ev.sealNo = r.sealNo();
        ev.sealIntact = r.sealNo() == null ? null : Boolean.TRUE;
        ev.toSig = r.signatureRef();
        ev.witness = r.witnessRef();
        ev.detail("labelKind", kind).detail("caseRef", r.caseRef()).detail("containerId", r.containerId())
                .detail("container", r.container());
        Appended a = append(c, ctx, item, ev, new Next(item));
        if (container != null) {
            append(c, ctx, container, new Ev("CONTENT_ADDED").detail("itemId", o.id()), new Next(container));
        }
        return a.item();
    }

    // ------------------------------------------------------------------------------------------- transfers

    /** Starts a two-step transfer: the item goes IN_TRANSIT until the receiver accepts or rejects. */
    public CustodyTransfer initiateTransfer(Connection c, RegistryContext ctx, String itemId, CustodyRequests.Transfer t) {
        CustodyItem item = lockItem(c, ctx, itemId);
        requireMovable(item);
        text("toCustodian", t.toCustodian());
        text("reason", t.reason());
        if (t.toCustodian().equals(item.custodianRef())) {
            throw RegistryException.invalid("the item is already with " + t.toCustodian());
        }
        Instant now = Db.now(c, dialect);
        String tid = Db.newId();
        Jdbc.update(c, "INSERT INTO REG_CUSTODY_TRANSFER(" + TRANSFER_COLS + ", tenant_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tid, itemId, item.custodianRef(), t.toCustodian(), t.toLocation(), t.reason(), "PENDING", now, ctx.actor(),
                t.fromSignatureRef(), null, null, null, null, null, null, ctx.tenant());
        Ev ev = new Ev("TRANSFER_INITIATED");
        ev.from = item.custodianRef();
        ev.to = t.toCustodian();
        ev.location = t.toLocation();
        ev.fromSig = t.fromSignatureRef();
        ev.transferId = tid;
        ev.detail("reason", t.reason());
        Next nx = new Next(item);
        nx.state = IN_TRANSIT;
        nx.pendingTransferId = tid;
        Appended a = append(c, ctx, item, ev, nx);
        cascade(c, ctx, a.item(), child -> {
            Ev e = new Ev("TRANSFER_INITIATED");
            e.from = child.custodianRef();
            e.to = t.toCustodian();
            e.transferId = tid;
            return e;
        }, n -> n.state = IN_TRANSIT, 0);
        return transfer(c, ctx, tid);
    }

    /** The receiver accepts a pending transfer and signs the receipt. */
    public CustodyTransfer acceptTransfer(Connection c, RegistryContext ctx, String transferId, CustodyRequests.Accept a) {
        CustodyTransfer t = transfer(c, ctx, transferId);
        CustodyItem item = lockItem(c, ctx, t.itemId());
        t = transferRaw(c, ctx.tenant(), transferId);
        requirePending(item, t);
        ObjectTypeDefinition type = typeOf(c, ctx, item);
        if (!Maps.bool(type.extensions(), "allowSamePersonTransfer", false) && ctx.actor().equals(t.initiatedBy())) {
            throw RegistryException.forbidden("the receiver must differ from the person who handed the item over");
        }
        if (a.toSignatureRef() == null || a.toSignatureRef().isBlank()) {
            throw RegistryException.invalid("the receiver's signature (toSignatureRef) is required");
        }
        text("conditionText", a.conditionText());
        SealCheck sc = sealCheck(item, a.sealIntact(), a.observedSealNo());
        Receipt receipt = receipt(c, ctx, item, type, transferId, t.fromCustodian(), t.toCustodian());
        Instant now = Db.now(c, dialect);
        Jdbc.update(c, "UPDATE REG_CUSTODY_TRANSFER SET status='ACCEPTED', resolved_at=?, resolved_by=?, to_signature_ref=?,"
                        + " condition_text=?, seal_intact=?, receipt_number=? WHERE id=? AND tenant_id=?",
                now, ctx.actor(), a.toSignatureRef(), a.conditionText(), sc.intact(), receipt.number(), transferId, ctx.tenant());
        String location = a.locationRef() != null ? a.locationRef() : t.toLocation() != null ? t.toLocation() : item.locationRef();
        Ev ev = new Ev("TRANSFER_ACCEPTED");
        ev.from = t.fromCustodian();
        ev.to = t.toCustodian();
        ev.location = location;
        ev.condition = a.conditionText();
        ev.sealNo = item.sealNo();
        ev.sealIntact = sc.intact();
        ev.fromSig = t.fromSignatureRef();
        ev.toSig = a.toSignatureRef();
        ev.witness = a.witnessRef();
        ev.receipt = receipt.number();
        ev.transferId = transferId;
        ev.detail("receiptDocumentRef", receipt.documentRef()).detail("observedSealNo", a.observedSealNo())
                .detail("sealDiscrepancy", sc.discrepancy() ? Boolean.TRUE : null);
        Next nx = new Next(item);
        nx.custodian = t.toCustodian();
        nx.location = location;
        nx.state = IN_CUSTODY;
        nx.pendingTransferId = null;
        if (sc.discrepancy()) {
            nx.sealStatus = "BROKEN";
        }
        Appended ap = append(c, ctx, item, ev, nx);
        if (sc.discrepancy()) {
            sealDiscrepancy(c, ctx, ap.item(), a.observedSealNo(), transferId);
        }
        String to = t.toCustodian();
        cascade(c, ctx, ap.item(), child -> {
            Ev e = new Ev("TRANSFER_ACCEPTED");
            e.from = child.custodianRef();
            e.to = to;
            e.location = location;
            e.transferId = transferId;
            e.receipt = receipt.number();
            return e;
        }, n -> {
            n.custodian = to;
            n.location = location;
            n.state = IN_CUSTODY;
        }, 0);
        return transfer(c, ctx, transferId);
    }

    /** The receiver refuses a pending transfer; the item stays with the sender. */
    public CustodyTransfer rejectTransfer(Connection c, RegistryContext ctx, String transferId, String reason,
                                          String signatureRef) {
        return closeTransfer(c, ctx, transferId, "REJECTED", "TRANSFER_REJECTED", reason, signatureRef);
    }

    /** The sender withdraws a pending transfer. */
    public CustodyTransfer cancelTransfer(Connection c, RegistryContext ctx, String transferId, String reason) {
        return closeTransfer(c, ctx, transferId, "CANCELLED", "TRANSFER_CANCELLED", reason, null);
    }

    private CustodyTransfer closeTransfer(Connection c, RegistryContext ctx, String transferId, String status,
                                          String eventType, String reason, String signatureRef) {
        if (reason == null || reason.isBlank()) {
            throw RegistryException.invalid("a reason is required");
        }
        text("reason", reason);
        CustodyTransfer t = transfer(c, ctx, transferId);
        CustodyItem item = lockItem(c, ctx, t.itemId());
        t = transferRaw(c, ctx.tenant(), transferId);
        requirePending(item, t);
        Instant now = Db.now(c, dialect);
        Jdbc.update(c, "UPDATE REG_CUSTODY_TRANSFER SET status=?, resolved_at=?, resolved_by=?, to_signature_ref=?,"
                + " condition_text=? WHERE id=? AND tenant_id=?", status, now, ctx.actor(), signatureRef, reason, transferId, ctx.tenant());
        Ev ev = new Ev(eventType);
        ev.from = t.fromCustodian();
        ev.to = t.toCustodian();
        ev.toSig = signatureRef;
        ev.transferId = transferId;
        ev.detail("reason", reason);
        Next nx = new Next(item);
        nx.state = IN_CUSTODY;
        nx.pendingTransferId = null;
        Appended ap = append(c, ctx, item, ev, nx);
        cascade(c, ctx, ap.item(), child -> {
            Ev e = new Ev(eventType);
            e.transferId = transferId;
            return e;
        }, n -> n.state = IN_CUSTODY, 0);
        return transfer(c, ctx, transferId);
    }

    /** Single-step handover with both parties' signatures (e.g. scan-to-handover in a field app). */
    public CustodyTransfer handover(Connection c, RegistryContext ctx, String itemId, CustodyRequests.Handover h) {
        CustodyItem item = lockItem(c, ctx, itemId);
        requireMovable(item);
        if (h.fromSignatureRef() == null || h.fromSignatureRef().isBlank()
                || h.toSignatureRef() == null || h.toSignatureRef().isBlank()) {
            throw RegistryException.invalid("a handover needs both signatures (fromSignatureRef and toSignatureRef)");
        }
        text("toCustodian", h.toCustodian());
        text("reason", h.reason());
        text("conditionText", h.conditionText());
        if (h.toCustodian().equals(item.custodianRef())) {
            throw RegistryException.invalid("the item is already with " + h.toCustodian());
        }
        ObjectTypeDefinition type = typeOf(c, ctx, item);
        SealCheck sc = sealCheck(item, h.sealIntact(), h.observedSealNo());
        String tid = Db.newId();
        Receipt receipt = receipt(c, ctx, item, type, tid, item.custodianRef(), h.toCustodian());
        Instant now = Db.now(c, dialect);
        String location = h.toLocation() != null ? h.toLocation() : item.locationRef();
        Jdbc.update(c, "INSERT INTO REG_CUSTODY_TRANSFER(" + TRANSFER_COLS + ", tenant_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tid, itemId, item.custodianRef(), h.toCustodian(), location, h.reason(), "ACCEPTED", now, ctx.actor(),
                h.fromSignatureRef(), now, ctx.actor(), h.toSignatureRef(), h.conditionText(), sc.intact(), receipt.number(),
                ctx.tenant());
        Ev ev = new Ev("HANDOVER");
        ev.from = item.custodianRef();
        ev.to = h.toCustodian();
        ev.location = location;
        ev.condition = h.conditionText();
        ev.sealNo = item.sealNo();
        ev.sealIntact = sc.intact();
        ev.fromSig = h.fromSignatureRef();
        ev.toSig = h.toSignatureRef();
        ev.witness = h.witnessRef();
        ev.receipt = receipt.number();
        ev.transferId = tid;
        ev.occurredAt = h.occurredAt();
        ev.detail("reason", h.reason()).detail("receiptDocumentRef", receipt.documentRef())
                .detail("observedSealNo", h.observedSealNo()).detail("sealDiscrepancy", sc.discrepancy() ? Boolean.TRUE : null);
        Next nx = new Next(item);
        nx.custodian = h.toCustodian();
        nx.location = location;
        if (sc.discrepancy()) {
            nx.sealStatus = "BROKEN";
        }
        Appended ap = append(c, ctx, item, ev, nx);
        if (sc.discrepancy()) {
            sealDiscrepancy(c, ctx, ap.item(), h.observedSealNo(), tid);
        }
        String to = h.toCustodian();
        cascade(c, ctx, ap.item(), child -> {
            Ev e = new Ev("HANDOVER");
            e.from = child.custodianRef();
            e.to = to;
            e.location = location;
            e.transferId = tid;
            e.receipt = receipt.number();
            return e;
        }, n -> {
            n.custodian = to;
            n.location = location;
        }, 0);
        return transfer(c, ctx, tid);
    }

    private void sealDiscrepancy(Connection c, RegistryContext ctx, CustodyItem item, String observed, String transferId) {
        Ev e = new Ev("SEAL_DISCREPANCY");
        e.sealNo = item.sealNo();
        e.sealIntact = Boolean.FALSE;
        e.transferId = transferId;
        e.detail("observedSealNo", observed);
        append(c, ctx, item, e, new Next(item));
    }

    private SealCheck sealCheck(CustodyItem item, Boolean sealIntact, String observedSealNo) {
        if (item.sealNo() == null || !"INTACT".equals(item.sealStatus())) {
            return new SealCheck(sealIntact, false);
        }
        if (sealIntact == null && observedSealNo == null) {
            throw RegistryException.invalid("the item is sealed: the receiver must confirm the seal (sealIntact or observedSealNo)");
        }
        boolean mismatch = observedSealNo != null && !observedSealNo.equals(item.sealNo());
        boolean broken = Boolean.FALSE.equals(sealIntact) || mismatch;
        return new SealCheck(!broken, broken);
    }

    private Receipt receipt(Connection c, RegistryContext ctx, CustodyItem item, ObjectTypeDefinition type,
                            String transferId, String from, String to) {
        String series = Maps.str(type.extensions(), "receiptSeries");
        String number = null;
        Map<String, Object> attrs = objects.get(c, ctx, item.objectId()).attrs();
        if (series != null) {
            String scopeAttr = Maps.str(type.extensions(), "receiptScopeAttribute");
            String scope = scopeAttr == null ? null : Maps.str(attrs, scopeAttr);
            Map<String, Object> fmt = new LinkedHashMap<>(attrs);
            fmt.put("label", item.label());
            number = objects.numbering().allocate(c, ctx, series, scope, null, fmt, "custody-transfer", transferId).formatted();
        }
        String docRef = null;
        String template = type.documents().get("RECEIPT");
        if (template != null) {
            Map<String, Object> model = Maps.of("receiptNumber", number, "transferId", transferId, "itemId", item.objectId(),
                    "label", item.label(), "description", item.description(), "caseRef", item.caseRef(),
                    "fromCustodian", from, "toCustodian", to, "sealNo", item.sealNo(), "attributes", attrs,
                    "issuedBy", ctx.actor());
            docRef = ports.documents().render(ctx, template, model).map(RegistryPorts.GeneratedDocument::documentRef).orElse(null);
        }
        return new Receipt(number, docRef);
    }

    // ------------------------------------------------------------------------------------------- seals and state

    /** Seals the item (or reseals it after the seal was broken). */
    public CustodyItem seal(Connection c, RegistryContext ctx, String itemId, CustodyRequests.Seal s) {
        CustodyItem item = lockItem(c, ctx, itemId);
        requireActive(item);
        notInTransit(item);
        text("sealNo", s.sealNo());
        text("reason", s.reason());
        if ("INTACT".equals(item.sealStatus())) {
            throw RegistryException.state("the item is already sealed (" + item.sealNo() + "); break the seal first");
        }
        Ev ev = new Ev("BROKEN".equals(item.sealStatus()) ? "RESEALED" : "SEALED");
        ev.sealNo = s.sealNo();
        ev.sealIntact = Boolean.TRUE;
        ev.witness = s.witnessRef();
        ev.detail("reason", s.reason()).detail("previousSealNo", item.sealNo());
        Next nx = new Next(item);
        nx.sealNo = s.sealNo();
        nx.sealStatus = "INTACT";
        return append(c, ctx, item, ev, nx).item();
    }

    /** Records an authorised opening of the seal (examination, sampling). */
    public CustodyItem breakSeal(Connection c, RegistryContext ctx, String itemId, String reason, String witnessRef) {
        if (reason == null || reason.isBlank()) {
            throw RegistryException.invalid("a reason is required to break a seal");
        }
        text("reason", reason);
        CustodyItem item = lockItem(c, ctx, itemId);
        requireActive(item);
        notInTransit(item);
        if (!"INTACT".equals(item.sealStatus())) {
            throw RegistryException.state("the item has no intact seal");
        }
        Ev ev = new Ev("SEAL_BROKEN");
        ev.sealNo = item.sealNo();
        ev.sealIntact = Boolean.FALSE;
        ev.witness = witnessRef;
        ev.detail("reason", reason);
        Next nx = new Next(item);
        nx.sealStatus = "BROKEN";
        return append(c, ctx, item, ev, nx).item();
    }

    /** Records an inspection or a condition note. */
    public CustodyItem inspect(Connection c, RegistryContext ctx, String itemId, String conditionText, String witnessRef,
                               Map<String, Object> details) {
        text("conditionText", conditionText);
        CustodyItem item = lockItem(c, ctx, itemId);
        requireActive(item);
        Ev ev = new Ev("INSPECTED");
        ev.condition = conditionText;
        ev.witness = witnessRef;
        ev.sealNo = item.sealNo();
        if (details != null) {
            details.forEach(ev::detail);
        }
        return append(c, ctx, item, ev, new Next(item)).item();
    }

    /** Moves the item (and its contents) to another location with the same custodian. */
    public CustodyItem changeLocation(Connection c, RegistryContext ctx, String itemId, String locationRef, String reason) {
        text("locationRef", locationRef);
        text("reason", reason);
        if (locationRef == null || locationRef.isBlank()) {
            throw RegistryException.invalid("locationRef is required");
        }
        CustodyItem item = lockItem(c, ctx, itemId);
        requireActive(item);
        notInTransit(item);
        if (item.containerId() != null) {
            throw RegistryException.state("the item moves with its container " + item.containerId() + "; take it out first");
        }
        Ev ev = new Ev("LOCATION_CHANGED");
        ev.from = item.custodianRef();
        ev.to = item.custodianRef();
        ev.location = locationRef;
        ev.detail("reason", reason).detail("previousLocation", item.locationRef());
        Next nx = new Next(item);
        nx.location = locationRef;
        Appended ap = append(c, ctx, item, ev, nx);
        cascade(c, ctx, ap.item(), child -> {
            Ev e = new Ev("LOCATION_CHANGED");
            e.location = locationRef;
            e.detail("previousLocation", child.locationRef());
            return e;
        }, n -> n.location = locationRef, 0);
        return ap.item();
    }

    // ------------------------------------------------------------------------------------------- containers

    /** Puts an item into a container (a bag in a box in a room); the item then moves with the container. */
    public CustodyItem putIntoContainer(Connection c, RegistryContext ctx, String itemId, String containerId, String reason) {
        if (itemId.equals(containerId)) {
            throw RegistryException.invalid("an item cannot contain itself");
        }
        text("reason", reason);
        get(c, ctx, itemId);
        get(c, ctx, containerId);
        CustodyItem first = lockItem(c, ctx, itemId.compareTo(containerId) < 0 ? itemId : containerId);
        CustodyItem second = lockItem(c, ctx, itemId.compareTo(containerId) < 0 ? containerId : itemId);
        CustodyItem item = first.objectId().equals(itemId) ? first : second;
        CustodyItem container = first.objectId().equals(itemId) ? second : first;
        requireActive(item);
        requireActive(container);
        notInTransit(item);
        notInTransit(container);
        if (!container.container()) {
            throw RegistryException.invalid("item " + containerId + " is not a container");
        }
        if (item.containerId() != null) {
            throw RegistryException.state("the item is already in container " + item.containerId() + "; take it out first");
        }
        String up = container.containerId();
        for (int depth = 0; up != null; depth++) {
            if (up.equals(itemId) || depth > MAX_DEPTH) {
                throw RegistryException.invalid("putting " + itemId + " into " + containerId + " would create a cycle");
            }
            up = Jdbc.one(c, "SELECT container_id FROM REG_CUSTODY_ITEM WHERE object_id=? AND tenant_id=?",
                    rs -> rs.getString(1), up, ctx.tenant()).orElse(null);
        }
        Ev ev = new Ev("CONTAINED");
        ev.from = item.custodianRef();
        ev.to = container.custodianRef();
        ev.location = container.locationRef();
        ev.detail("containerId", containerId).detail("reason", reason).detail("previousLocation", item.locationRef());
        Next nx = new Next(item);
        nx.containerId = containerId;
        nx.custodian = container.custodianRef();
        nx.location = container.locationRef();
        Appended ap = append(c, ctx, item, ev, nx);
        String cust = container.custodianRef();
        String loc = container.locationRef();
        cascade(c, ctx, ap.item(), child -> {
            Ev e = new Ev("LOCATION_CHANGED");
            e.from = child.custodianRef();
            e.to = cust;
            e.location = loc;
            return e;
        }, n -> {
            n.custodian = cust;
            n.location = loc;
        }, 0);
        append(c, ctx, container, new Ev("CONTENT_ADDED").detail("itemId", itemId).detail("reason", reason), new Next(container));
        return ap.item();
    }

    /** Takes an item out of its container; it stays with the container's custodian and location. */
    public CustodyItem takeOutOfContainer(Connection c, RegistryContext ctx, String itemId, String reason) {
        text("reason", reason);
        CustodyItem probe = get(c, ctx, itemId);
        if (probe.containerId() == null) {
            throw RegistryException.state("the item is not in a container");
        }
        String containerId = probe.containerId();
        CustodyItem first = lockItem(c, ctx, itemId.compareTo(containerId) < 0 ? itemId : containerId);
        CustodyItem second = lockItem(c, ctx, itemId.compareTo(containerId) < 0 ? containerId : itemId);
        CustodyItem item = first.objectId().equals(itemId) ? first : second;
        CustodyItem container = first.objectId().equals(itemId) ? second : first;
        if (!containerId.equals(item.containerId())) {
            throw RegistryException.conflict("the item's container changed concurrently; retry");
        }
        requireActive(item);
        notInTransit(item);
        Ev ev = new Ev("UNCONTAINED");
        ev.from = item.custodianRef();
        ev.to = item.custodianRef();
        ev.location = item.locationRef();
        ev.detail("containerId", containerId).detail("reason", reason);
        Next nx = new Next(item);
        nx.containerId = null;
        Appended ap = append(c, ctx, item, ev, nx);
        append(c, ctx, container, new Ev("CONTENT_REMOVED").detail("itemId", itemId).detail("reason", reason), new Next(container));
        return ap.item();
    }

    // ------------------------------------------------------------------------------------------- disposal

    /** Orders disposal under an authority reference (an order of a competent authority or committee). */
    public DisposalOrder orderDisposal(Connection c, RegistryContext ctx, String itemId, CustodyRequests.Disposal d) {
        String method = d.method().toUpperCase();
        if (!METHODS.contains(method)) {
            throw RegistryException.invalid("method must be one of " + METHODS);
        }
        text("authorityRef", d.authorityRef());
        CustodyItem item = lockItem(c, ctx, itemId);
        requireActive(item);
        if (Jdbc.one(c, "SELECT id FROM REG_DISPOSAL_ORDER WHERE tenant_id=? AND item_id=? AND status='ORDERED'",
                rs -> rs.getString(1), ctx.tenant(), itemId).isPresent()) {
            throw RegistryException.state("the item already has an open disposal order");
        }
        Instant now = Db.now(c, dialect);
        String id = Db.newId();
        Jdbc.update(c, "INSERT INTO REG_DISPOSAL_ORDER(" + DISPOSAL_COLS + ", tenant_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, itemId, method, d.authorityRef(), "ORDERED", now, ctx.actor(), null, null, Json.write(d.details()),
                ctx.tenant());
        Ev ev = new Ev("DISPOSAL_ORDERED");
        ev.detail("orderId", id).detail("method", method).detail("authorityRef", d.authorityRef());
        append(c, ctx, item, ev, new Next(item));
        return disposalOrder(c, ctx, id);
    }

    /**
     * Executes a disposal order. The executing person must differ from the orderer (maker-checker); the item ends
     * DISPOSED (destroy, auction) or RELEASED (return, transfer out, release), and the kernel object takes the type's
     * disposal transition when its lifecycle has one.
     */
    public DisposalOrder executeDisposal(Connection c, RegistryContext ctx, String orderId, String witnessRef,
                                         Map<String, Object> details) {
        DisposalOrder o = disposalOrder(c, ctx, orderId);
        CustodyItem item = lockItem(c, ctx, o.itemId());
        o = disposalRaw(c, ctx.tenant(), orderId);
        if (!"ORDERED".equals(o.status())) {
            throw RegistryException.state("disposal order " + orderId + " is " + o.status());
        }
        if (ctx.actor().equals(o.orderedBy())) {
            throw RegistryException.forbidden("the disposal must be executed by a person other than the one who ordered it");
        }
        requireActive(item);
        notInTransit(item);
        if (item.containerId() != null) {
            throw RegistryException.state("take the item out of container " + item.containerId() + " first");
        }
        long activeContents = Jdbc.one(c, "SELECT COUNT(*) FROM REG_CUSTODY_ITEM WHERE tenant_id=? AND container_id=?"
                        + " AND custody_state NOT IN ('DISPOSED','RELEASED')", rs -> rs.getLong(1), ctx.tenant(), item.objectId())
                .orElse(0L);
        if (activeContents > 0) {
            throw RegistryException.state("the container still holds " + activeContents + " item(s)");
        }
        String newState = RELEASE_METHODS.contains(o.method()) ? RELEASED : DISPOSED;
        Instant now = Db.now(c, dialect);
        Map<String, Object> merged = new LinkedHashMap<>(o.details() == null ? Map.of() : o.details());
        if (details != null) {
            merged.putAll(details);
        }
        if (witnessRef != null) {
            merged.put("witnessRef", witnessRef);
        }
        Jdbc.update(c, "UPDATE REG_DISPOSAL_ORDER SET status='EXECUTED', executed_at=?, executed_by=?, details_json=?"
                + " WHERE id=? AND tenant_id=?", now, ctx.actor(), Json.write(merged), orderId, ctx.tenant());
        Ev ev = new Ev(newState);
        ev.from = item.custodianRef();
        ev.witness = witnessRef;
        ev.sealNo = item.sealNo();
        ev.detail("orderId", orderId).detail("method", o.method()).detail("authorityRef", o.authorityRef());
        if (details != null) {
            details.forEach(ev::detail);
        }
        Next nx = new Next(item);
        nx.state = newState;
        nx.pendingTransferId = null;
        append(c, ctx, item, ev, nx);
        RegisteredObject obj = objects.get(c, ctx, item.objectId());
        ObjectTypeDefinition type = objects.typeOf(c, obj);
        String configured = Maps.str(type.extensions(), "disposalTransition");
        List<String> candidates = new ArrayList<>();
        if (configured != null) {
            candidates.add(configured);
        }
        candidates.add(RELEASED.equals(newState) ? "release" : "dispose");
        candidates.add("archive");
        List<String> available = type.lifecycle().available(obj.state());
        for (String name : candidates) {
            if (type.lifecycle().has(name) && available.contains(name)) {
                objects.transition(c, ctx, obj.id(), null, name,
                        "disposal order " + orderId + " (" + o.method() + ", " + o.authorityRef() + ")", null, o.orderedBy());
                break;
            }
        }
        return disposalOrder(c, ctx, orderId);
    }

    /** Cancels an open disposal order. */
    public DisposalOrder cancelDisposal(Connection c, RegistryContext ctx, String orderId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw RegistryException.invalid("a reason is required");
        }
        DisposalOrder o = disposalOrder(c, ctx, orderId);
        CustodyItem item = lockItem(c, ctx, o.itemId());
        o = disposalRaw(c, ctx.tenant(), orderId);
        if (!"ORDERED".equals(o.status())) {
            throw RegistryException.state("disposal order " + orderId + " is " + o.status());
        }
        Map<String, Object> merged = new LinkedHashMap<>(o.details() == null ? Map.of() : o.details());
        merged.put("cancelReason", reason);
        merged.put("cancelledBy", ctx.actor());
        Jdbc.update(c, "UPDATE REG_DISPOSAL_ORDER SET status='CANCELLED', details_json=? WHERE id=? AND tenant_id=?",
                Json.write(merged), orderId, ctx.tenant());
        append(c, ctx, item, new Ev("DISPOSAL_CANCELLED").detail("orderId", orderId).detail("reason", reason), new Next(item));
        return disposalOrder(c, ctx, orderId);
    }

    // ------------------------------------------------------------------------------------------- queries

    public CustodyItem get(Connection c, RegistryContext ctx, String itemId) {
        SqlPredicate vis = ports.visibility().visible(ctx, RegisteredObjectService.RESOURCE, "o");
        List<Object> params = new ArrayList<>(List.of(itemId, ctx.tenant()));
        params.addAll(vis.params());
        return Jdbc.one(c, JOINED + " WHERE i.object_id=? AND i.tenant_id=? AND (" + vis.sql() + ")",
                CustodyService::joined, params.toArray()).orElseThrow(() -> RegistryException.notFound("custody item", itemId));
    }

    public CustodyItem byLabel(Connection c, RegistryContext ctx, String label) {
        String id = Jdbc.one(c, "SELECT object_id FROM REG_CUSTODY_ITEM WHERE tenant_id=? AND label=?", rs -> rs.getString(1),
                ctx.tenant(), label).orElseThrow(() -> RegistryException.notFound("custody item with label", label));
        return get(c, ctx, id);
    }

    public List<CustodyItem> list(Connection c, RegistryContext ctx, Query q) {
        StringBuilder where = new StringBuilder("i.tenant_id=?");
        List<Object> params = new ArrayList<>(List.of(ctx.tenant()));
        add(where, params, "o.type_key", q.typeKey());
        add(where, params, "i.custodian_ref", q.custodianRef());
        add(where, params, "i.case_ref", q.caseRef());
        add(where, params, "i.container_id", q.containerId());
        add(where, params, "i.custody_state", q.custodyState() == null ? null : q.custodyState().toUpperCase());
        add(where, params, "i.label", q.label());
        return Db.page(c, dialect, JOINED, where.toString(), params,
                ports.visibility().visible(ctx, RegisteredObjectService.RESOURCE, "o"), "i.last_event_at DESC, i.object_id",
                q.page(), q.size(), CustodyService::joined);
    }

    /** The chain of custody of an item, oldest first. */
    public List<CustodyEvent> events(Connection c, RegistryContext ctx, String itemId) {
        get(c, ctx, itemId);
        return Jdbc.query(c, "SELECT " + EVENT_COLS + " FROM REG_CUSTODY_EVENT WHERE tenant_id=? AND item_id=? ORDER BY seq",
                CustodyService::event, ctx.tenant(), itemId);
    }

    public List<CustodyTransfer> transfers(Connection c, RegistryContext ctx, String itemId) {
        get(c, ctx, itemId);
        return Jdbc.query(c, "SELECT " + TRANSFER_COLS + " FROM REG_CUSTODY_TRANSFER WHERE tenant_id=? AND item_id=?"
                + " ORDER BY initiated_at, id", CustodyService::transferRow, ctx.tenant(), itemId);
    }

    public CustodyTransfer transfer(Connection c, RegistryContext ctx, String transferId) {
        CustodyTransfer t = transferRaw(c, ctx.tenant(), transferId);
        get(c, ctx, t.itemId());
        return t;
    }

    /** Pending transfers addressed to a custodian (the receiver's inbox). */
    public List<CustodyTransfer> pendingFor(Connection c, RegistryContext ctx, String toCustodian, int page, int size) {
        return Db.page(c, dialect, "SELECT t." + TRANSFER_COLS.replace(", ", ", t.") + " FROM REG_CUSTODY_TRANSFER t"
                        + " JOIN REG_OBJECT o ON o.id = t.item_id AND o.tenant_id = t.tenant_id",
                "t.tenant_id=? AND t.to_custodian=? AND t.status='PENDING'", List.of(ctx.tenant(), toCustodian),
                ports.visibility().visible(ctx, RegisteredObjectService.RESOURCE, "o"), "t.initiated_at, t.id", page, size,
                CustodyService::transferRow);
    }

    public List<DisposalOrder> disposalOrders(Connection c, RegistryContext ctx, String itemId) {
        get(c, ctx, itemId);
        return Jdbc.query(c, "SELECT " + DISPOSAL_COLS + " FROM REG_DISPOSAL_ORDER WHERE tenant_id=? AND item_id=?"
                + " ORDER BY ordered_at, id", CustodyService::disposalRow, ctx.tenant(), itemId);
    }

    public DisposalOrder disposalOrder(Connection c, RegistryContext ctx, String orderId) {
        DisposalOrder o = disposalRaw(c, ctx.tenant(), orderId);
        get(c, ctx, o.itemId());
        return o;
    }

    /**
     * Custody gap check: items in custody without a custodian, items in transit longer than {@code transitLimit},
     * items with no custody event for longer than {@code staleLimit} (null skips that check), items with a broken seal.
     */
    public GapReport gapCheck(Connection c, RegistryContext ctx, Duration transitLimit, Duration staleLimit, int limit) {
        Instant now = Db.now(c, dialect);
        SqlPredicate vis = ports.visibility().visible(ctx, RegisteredObjectService.RESOURCE, "o");
        int max = limit <= 0 ? 100 : limit;
        List<CustodyItem> noCustodian = Db.page(c, dialect, JOINED,
                "i.tenant_id=? AND i.custodian_ref IS NULL AND i.custody_state NOT IN ('DISPOSED','RELEASED')",
                List.of(ctx.tenant()), vis, "i.last_event_at, i.object_id", 0, max, CustodyService::joined);
        List<CustodyItem> transit = transitLimit == null ? List.of() : Db.page(c, dialect, JOINED,
                "i.tenant_id=? AND i.custody_state='IN_TRANSIT' AND i.last_event_at <= ?",
                List.of(ctx.tenant(), now.minus(transitLimit)), vis, "i.last_event_at, i.object_id", 0, max, CustodyService::joined);
        List<CustodyItem> stale = staleLimit == null ? List.of() : Db.page(c, dialect, JOINED,
                "i.tenant_id=? AND i.custody_state NOT IN ('DISPOSED','RELEASED') AND i.last_event_at <= ?",
                List.of(ctx.tenant(), now.minus(staleLimit)), vis, "i.last_event_at, i.object_id", 0, max, CustodyService::joined);
        List<CustodyItem> broken = Db.page(c, dialect, JOINED,
                "i.tenant_id=? AND i.seal_status='BROKEN' AND i.custody_state NOT IN ('DISPOSED','RELEASED')",
                List.of(ctx.tenant()), vis, "i.last_event_at, i.object_id", 0, max, CustodyService::joined);
        return new GapReport(now, noCustodian, transit, stale, broken);
    }

    /** Recomputes the item's custody hash chain and its core audit chain. */
    public ChainVerification verifyChain(Connection c, RegistryContext ctx, String itemId) {
        CustodyItem item = get(c, ctx, itemId);
        List<CustodyEvent> evs = events(c, ctx, itemId);
        AuditChain.VerifyResult ar = audit.verify(c, ctx.tenant(), itemId);
        byte[] expectedPrev = genesis(ctx.tenant(), itemId);
        long n = 0;
        for (CustodyEvent e : evs) {
            n++;
            if (e.seq() != n) {
                return bad(n, e.seq(), "gap in seq", ar);
            }
            if (!HEX.formatHex(expectedPrev).equals(e.prevHash())) {
                return bad(n, e.seq(), "prev_hash link broken", ar);
            }
            byte[] h = hash(ctx.tenant(), e, expectedPrev);
            if (!HEX.formatHex(h).equals(e.eventHash())) {
                return bad(n, e.seq(), "event hash mismatch (row altered)", ar);
            }
            expectedPrev = h;
        }
        if (n != item.eventCount()) {
            return bad(n, n + 1, "event count on the item does not match the chain", ar);
        }
        if (n > 0 && !HEX.formatHex(expectedPrev).equals(item.headHash())) {
            return bad(n, n, "item head hash does not match the last event", ar);
        }
        return new ChainVerification(true, n, null, null, ar.ok(), ar.firstBadSeq(), ar.reason());
    }

    private static ChainVerification bad(long n, long seq, String reason, AuditChain.VerifyResult ar) {
        return new ChainVerification(false, n, seq, reason, ar.ok(), ar.firstBadSeq(), ar.reason());
    }

    // ------------------------------------------------------------------------------------------- internals

    private Appended append(Connection c, RegistryContext ctx, CustodyItem item, Ev ev, Next next) {
        text("condition", ev.condition);
        text("witnessRef", ev.witness);
        text("locationRef", ev.location);
        Instant now = Db.now(c, dialect);
        long seq = item.eventCount() + 1;
        byte[] prev = item.headHash() == null ? genesis(ctx.tenant(), item.objectId()) : HEX.parseHex(item.headHash());
        String detailsJson = Json.write(ev.details);
        Instant occurred = ev.occurredAt == null ? now : ev.occurredAt.truncatedTo(ChronoUnit.MICROS);
        String id = Db.newId();
        CustodyEvent draft = new CustodyEvent(id, item.objectId(), seq, ev.type, ev.from, ev.to, ev.location, ev.condition,
                ev.sealNo, ev.sealIntact, ev.fromSig, ev.toSig, ev.witness, ev.receipt, ev.transferId,
                Json.map(detailsJson), occurred, ctx.actor(), HEX.formatHex(prev), null, null);
        byte[] hash = hash(ctx.tenant(), draft, prev);
        AuditChain.AuditEvent a = audit.record(c, ctx, item.objectId(), "CUSTODY_" + ev.type, Maps.of("seq", seq,
                "custodyEventId", id, "eventHash", HEX.formatHex(hash), "from", ev.from, "to", ev.to, "location", ev.location,
                "sealNo", ev.sealNo, "sealIntact", ev.sealIntact, "transferId", ev.transferId, "receipt", ev.receipt,
                "label", item.label()));
        Jdbc.update(c, "INSERT INTO REG_CUSTODY_EVENT(" + EVENT_COLS + ", tenant_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, item.objectId(), seq, ev.type, ev.from, ev.to, ev.location, ev.condition, ev.sealNo, ev.sealIntact,
                ev.fromSig, ev.toSig, ev.witness, ev.receipt, ev.transferId, detailsJson, occurred, ctx.actor(), prev, hash,
                a.eventId(), ctx.tenant());
        int n = Jdbc.update(c, "UPDATE REG_CUSTODY_ITEM SET custodian_ref=?, location_ref=?, custody_state=?, seal_no=?, seal_status=?,"
                        + " container_id=?, pending_transfer_id=?, event_count=?, head_hash=?, last_event_at=?, version=?"
                        + " WHERE object_id=? AND tenant_id=? AND version=?",
                next.custodian, next.location, next.state, next.sealNo, next.sealStatus, next.containerId,
                next.pendingTransferId, seq, hash, now, item.version() + 1, item.objectId(), ctx.tenant(), item.version());
        if (n == 0) {
            throw RegistryException.conflict("custody item " + item.objectId() + " changed concurrently; retry");
        }
        CustodyItem after = new CustodyItem(item.objectId(), item.typeKey(), item.objectNumber(), item.objectState(),
                item.label(), item.labelKind(), item.description(), item.caseRef(), next.containerId, item.container(),
                next.sealNo, next.sealStatus, next.custodian, next.location, next.state, next.pendingTransferId, seq,
                HEX.formatHex(hash), now, item.version() + 1);
        CustodyEvent event = new CustodyEvent(id, draft.itemId(), seq, draft.eventType(), draft.fromCustodian(),
                draft.toCustodian(), draft.locationRef(), draft.conditionText(), draft.sealNo(), draft.sealIntact(),
                draft.fromSignatureRef(), draft.toSignatureRef(), draft.witnessRef(), draft.receiptNumber(),
                draft.transferId(), draft.details(), occurred, ctx.actor(), draft.prevHash(), HEX.formatHex(hash), a.eventId());
        return new Appended(after, event);
    }

    /** Applies an event to every nested content item of {@code container} (depth first), each locked and chained. */
    private void cascade(Connection c, RegistryContext ctx, CustodyItem container,
                         java.util.function.Function<CustodyItem, Ev> eventFor, Consumer<Next> change, int depth) {
        if (!container.container()) {
            return;
        }
        if (depth > MAX_DEPTH) {
            throw RegistryException.state("container nesting deeper than " + MAX_DEPTH);
        }
        List<String> children = Jdbc.query(c, "SELECT object_id FROM REG_CUSTODY_ITEM WHERE tenant_id=? AND container_id=?"
                        + " AND custody_state NOT IN ('DISPOSED','RELEASED') ORDER BY object_id", rs -> rs.getString(1),
                ctx.tenant(), container.objectId());
        Set<String> seen = new HashSet<>();
        for (String id : children) {
            if (!seen.add(id)) {
                continue;
            }
            CustodyItem child = lockRaw(c, ctx.tenant(), id);
            Ev ev = eventFor.apply(child);
            ev.detail("viaContainer", container.objectId());
            Next nx = new Next(child);
            change.accept(nx);
            Appended ap = append(c, ctx, child, ev, nx);
            cascade(c, ctx, ap.item(), eventFor, change, depth + 1);
        }
    }

    /** Locks the item row after a visibility-checked read. */
    private CustodyItem lockItem(Connection c, RegistryContext ctx, String itemId) {
        get(c, ctx, itemId);
        return lockRaw(c, ctx.tenant(), itemId);
    }

    private CustodyItem lockRaw(Connection c, String tenant, String itemId) {
        var meta = Jdbc.one(c, "SELECT type_key, state, object_number FROM REG_OBJECT WHERE id=? AND tenant_id=?",
                rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, itemId, tenant)
                .orElseThrow(() -> RegistryException.notFound("custody item", itemId));
        return Jdbc.one(c, dialect.lockingSelect(ITEM_COLS, "REG_CUSTODY_ITEM", "object_id=? AND tenant_id=?"),
                        rs -> item(rs, meta[0], meta[1], meta[2]), itemId, tenant)
                .orElseThrow(() -> RegistryException.notFound("custody item", itemId));
    }

    private ObjectTypeDefinition typeOf(Connection c, RegistryContext ctx, CustodyItem item) {
        return objects.typeOf(c, objects.get(c, ctx, item.objectId()));
    }

    private static void requireActive(CustodyItem item) {
        if (item.terminal()) {
            throw RegistryException.state("item " + item.objectId() + " is " + item.custodyState());
        }
    }

    private static void notInTransit(CustodyItem item) {
        if (IN_TRANSIT.equals(item.custodyState())) {
            throw RegistryException.state("item " + item.objectId() + " is in transit (transfer " + item.pendingTransferId() + ")");
        }
    }

    private static void requireMovable(CustodyItem item) {
        requireActive(item);
        if (!IN_CUSTODY.equals(item.custodyState()) || item.pendingTransferId() != null) {
            throw RegistryException.state("item " + item.objectId() + " has a pending transfer");
        }
        if (item.containerId() != null) {
            throw RegistryException.state("item " + item.objectId() + " is inside container " + item.containerId()
                    + "; transfer the container or take the item out first");
        }
    }

    private static void requirePending(CustodyItem item, CustodyTransfer t) {
        if (!"PENDING".equals(t.status())) {
            throw RegistryException.state("transfer " + t.id() + " is " + t.status());
        }
        if (!t.id().equals(item.pendingTransferId())) {
            throw RegistryException.state("transfer " + t.id() + " is not the item's pending transfer");
        }
    }

    private static void text(String field, String v) {
        if (v != null && v.length() > 255) {
            throw RegistryException.invalid(field + ": longer than 255 characters");
        }
    }

    private static void add(StringBuilder where, List<Object> params, String col, Object v) {
        if (v != null) {
            where.append(" AND ").append(col).append("=?");
            params.add(v);
        }
    }

    static byte[] genesis(String tenant, String itemId) {
        return Hashes.sha256("sangam-custody-v1:" + tenant + ":" + itemId);
    }

    static byte[] hash(String tenant, CustodyEvent e, byte[] prev) {
        Map<String, Object> env = Maps.of("schema", "sangam-custody-v1", "tenant", tenant, "itemId", e.itemId(),
                "seq", e.seq(), "eventId", e.id(), "type", e.eventType(), "from", e.fromCustodian(), "to", e.toCustodian(),
                "location", e.locationRef(), "condition", e.conditionText(), "sealNo", e.sealNo(),
                "sealIntact", e.sealIntact(), "fromSignature", e.fromSignatureRef(), "toSignature", e.toSignatureRef(),
                "witness", e.witnessRef(), "receipt", e.receiptNumber(), "transferId", e.transferId(),
                "details", e.details() == null ? Map.of() : e.details(), "occurredAt", e.occurredAt().toString(),
                "recordedBy", e.recordedBy());
        return Hashes.sha256(prev, CanonicalJson.write(Json.canonical(env)).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------------------------------- mappers

    private static CustodyItem joined(ResultSet rs) throws SQLException {
        return item(rs, rs.getString("type_key"), rs.getString("object_state"), rs.getString("object_number"));
    }

    private static CustodyItem item(ResultSet rs, String typeKey, String objectState, String number) throws SQLException {
        byte[] head = rs.getBytes("head_hash");
        return new CustodyItem(rs.getString("object_id"), typeKey, number, objectState, rs.getString("label"),
                rs.getString("label_kind"), rs.getString("description"), rs.getString("case_ref"),
                rs.getString("container_id"), rs.getInt("container_flag") == 1, rs.getString("seal_no"),
                rs.getString("seal_status"), rs.getString("custodian_ref"), rs.getString("location_ref"),
                rs.getString("custody_state"), rs.getString("pending_transfer_id"), rs.getLong("event_count"),
                head == null ? null : HEX.formatHex(head), Jdbc.instant(rs, "last_event_at"), rs.getLong("version"));
    }

    private static Boolean optFlag(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v == 1;
    }

    private static CustodyEvent event(ResultSet rs) throws SQLException {
        return new CustodyEvent(rs.getString("id"), rs.getString("item_id"), rs.getLong("seq"), rs.getString("event_type"),
                rs.getString("from_custodian"), rs.getString("to_custodian"), rs.getString("location_ref"),
                rs.getString("condition_text"), rs.getString("seal_no"), optFlag(rs, "seal_intact"),
                rs.getString("from_signature_ref"), rs.getString("to_signature_ref"), rs.getString("witness_ref"),
                rs.getString("receipt_number"), rs.getString("transfer_id"), Json.map(rs.getString("details_json")),
                Jdbc.instant(rs, "occurred_at"), rs.getString("recorded_by"), HEX.formatHex(rs.getBytes("prev_hash")),
                HEX.formatHex(rs.getBytes("event_hash")), rs.getString("audit_event_id"));
    }

    private static CustodyTransfer transferRow(ResultSet rs) throws SQLException {
        return new CustodyTransfer(rs.getString("id"), rs.getString("item_id"), rs.getString("from_custodian"),
                rs.getString("to_custodian"), rs.getString("to_location"), rs.getString("reason"), rs.getString("status"),
                Jdbc.instant(rs, "initiated_at"), rs.getString("initiated_by"), rs.getString("from_signature_ref"),
                Jdbc.instant(rs, "resolved_at"), rs.getString("resolved_by"), rs.getString("to_signature_ref"),
                rs.getString("condition_text"), optFlag(rs, "seal_intact"), rs.getString("receipt_number"));
    }

    private static DisposalOrder disposalRow(ResultSet rs) throws SQLException {
        return new DisposalOrder(rs.getString("id"), rs.getString("item_id"), rs.getString("method"),
                rs.getString("authority_ref"), rs.getString("status"), Jdbc.instant(rs, "ordered_at"),
                rs.getString("ordered_by"), Jdbc.instant(rs, "executed_at"), rs.getString("executed_by"),
                Json.map(rs.getString("details_json")));
    }

    private CustodyTransfer transferRaw(Connection c, String tenant, String transferId) {
        return Jdbc.one(c, "SELECT " + TRANSFER_COLS + " FROM REG_CUSTODY_TRANSFER WHERE id=? AND tenant_id=?",
                CustodyService::transferRow, transferId, tenant).orElseThrow(() -> RegistryException.notFound("transfer", transferId));
    }

    private DisposalOrder disposalRaw(Connection c, String tenant, String orderId) {
        return Jdbc.one(c, "SELECT " + DISPOSAL_COLS + " FROM REG_DISPOSAL_ORDER WHERE id=? AND tenant_id=?",
                CustodyService::disposalRow, orderId, tenant).orElseThrow(() -> RegistryException.notFound("disposal order", orderId));
    }
}
