// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.kernel;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.registry.RegistryContext;
import org.sangam.registry.RegistryException;
import org.sangam.registry.RegistryPorts;
import org.sangam.registry.numbering.NumberingService;
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
import java.util.function.UnaryOperator;

/**
 * The registered-object kernel (M4): create, update, lifecycle transitions, validity, versions and queries for every
 * configured type and kind. Each change locks the object row (L4), checks the expected version when the caller gives
 * one (optimistic concurrency for REST clients), writes the new current row, appends the full version to
 * {@code REG_OBJECT_VERSION}, and appends a hash-chained audit event (chain root = object id) plus an outbox event in
 * the same transaction. Kind services (instruments, assets, custody) build on {@link #mutate}.
 */
public final class RegisteredObjectService {

    public static final String RESOURCE = "registry-object";

    /** A create request. Dates are calendar dates in the tenant zone. */
    public record CreateRequest(String typeKey, Map<String, Object> attributes, String displayName,
                                String holderPartyId, String subjectRef, String parentId, String externalRef,
                                LocalDate validFrom, LocalDate validTo, String numberScope, boolean assignNumber,
                                boolean applyValidity) {

        public static CreateRequest from(Map<String, Object> m) {
            return new CreateRequest(Maps.required(m, "typeKey"), Maps.map(m, "attributes"), Maps.str(m, "displayName"),
                    Maps.str(m, "holderPartyId"), Maps.str(m, "subjectRef"), Maps.str(m, "parentId"),
                    Maps.str(m, "externalRef"), Maps.date(m, "validFrom"), Maps.date(m, "validTo"),
                    Maps.str(m, "numberScope"), Maps.bool(m, "assignNumber", true), Maps.bool(m, "applyValidity", true));
        }
    }

    /** A list query; null fields do not filter. */
    public record Query(String typeKey, String kind, String state, String holderPartyId, String parentId,
                        String number, String subjectRef, int page, int size) {
    }

    /** One version row. */
    public record Version(long version, String changeType, String state, String reason, Instant validFrom,
                          Instant validTo, String holderPartyId, Map<String, Object> attrs, String auditEventId,
                          Instant changedAt, String changedBy) {
    }

    private final Dialect dialect;
    private final ObjectTypeService types;
    private final NumberingService numbering;
    private final RegistryAudit audit;
    private final RegistryPorts ports;

    public RegisteredObjectService(Dialect dialect, ObjectTypeService types, NumberingService numbering,
                                   RegistryAudit audit, RegistryPorts ports) {
        this.dialect = dialect;
        this.types = types;
        this.numbering = numbering;
        this.audit = audit;
        this.ports = ports;
    }

    public ObjectTypeService types() {
        return types;
    }

    public NumberingService numbering() {
        return numbering;
    }

    public Dialect dialect() {
        return dialect;
    }

    public RegistryAudit audit() {
        return audit;
    }

    // ------------------------------------------------------------------------------------------------ create

    public RegisteredObject create(Connection c, RegistryContext ctx, CreateRequest req) {
        ObjectTypeDefinition type = types.current(c, ctx.tenant(), req.typeKey());
        return create(c, ctx, type, req, type.lifecycle().initial());
    }

    /** Create with an explicit initial state (kind services; e.g. an instrument issued straight to ACTIVE). */
    public RegisteredObject create(Connection c, RegistryContext ctx, ObjectTypeDefinition type, CreateRequest req,
                                   String initialState) {
        if (!type.lifecycle().states().containsKey(initialState)) {
            throw RegistryException.invalid("state " + initialState + " is not part of type " + type.typeKey());
        }
        if (req.parentId() != null) {
            RegisteredObject parent = get(c, ctx, req.parentId());
            if (!type.parentTypes().isEmpty() && !type.parentTypes().contains(parent.typeKey())) {
                throw RegistryException.invalid("a " + type.typeKey() + " cannot be placed under a " + parent.typeKey());
            }
        }
        Map<String, Object> attrs = type.schema().validate(req.attributes(), null);
        Instant now = Db.now(c, dialect);
        String id = Db.newId();
        ValidityRule.Window w = new ValidityRule.Window(null, null, null);
        if (req.applyValidity() && type.validity().hasValidity()) {
            LocalDate from = req.validFrom() == null ? now.atZone(ctx.zone()).toLocalDate() : req.validFrom();
            w = type.validity().first(from, req.validTo(), ctx.zone());
        } else if (req.validFrom() != null || req.validTo() != null) {
            w = new ValidityRule.Window(req.validFrom() == null ? null : req.validFrom().atStartOfDay(ctx.zone()).toInstant(),
                    req.validTo() == null ? null : req.validTo().plusDays(1).atStartOfDay(ctx.zone()).toInstant(), null);
        }
        String number = null;
        if (req.assignNumber() && type.numbering() != null && "CREATE".equals(type.numbering().assignOn())) {
            number = assignNumber(c, ctx, type, attrs, req.numberScope(), id).formatted();
        }
        String display = req.displayName() != null ? req.displayName()
                : number != null ? number : Maps.str(attrs, "name", type.title());
        RegisteredObject o = new RegisteredObject(id, ctx.tenant(), type.typeKey(), type.version(), type.kind(), number,
                display, initialState, null, null, 1, w.validFrom(), w.validTo(), w.renewalDueAt(), 0,
                req.holderPartyId(), req.subjectRef(), req.parentId(), req.externalRef(), attrs, now, ctx.actor(), now,
                ctx.actor());
        Jdbc.update(c, "INSERT INTO REG_OBJECT(id, tenant_id, type_key, type_version, kind, object_number, display_name, state,"
                        + " state_reason, state_until, version, valid_from, valid_to, renewal_due_at, renewal_count, holder_party_id,"
                        + " subject_ref, parent_id, external_ref, attrs_json, created_at, created_by, updated_at, updated_by)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                o.id(), o.tenant(), o.typeKey(), o.typeVersion(), o.kind().name(), o.number(), o.displayName(), o.state(),
                null, null, 1L, o.validFrom(), o.validTo(), o.renewalDueAt(), 0, o.holderPartyId(), o.subjectRef(),
                o.parentId(), o.externalRef(), Json.write(attrs), now, ctx.actor(), now, ctx.actor());
        AuditChain.AuditEvent ev = audit.record(c, ctx, id, "REGISTRY_OBJECT_CREATED", Maps.of("typeKey", type.typeKey(),
                "typeVersion", type.version(), "kind", type.kind().name(), "number", number, "state", initialState,
                "attrsDigest", digest(attrs)));
        insertVersion(c, o, "CREATE", null, ev.eventId(), now, ctx.actor());
        pushExternal(ctx, type, o, "CREATE");
        return o;
    }

    /** Allocates the type's number for an object (used at create or by the kind service at issue). */
    public NumberingService.Allocation assignNumber(Connection c, RegistryContext ctx, ObjectTypeDefinition type,
                                                    Map<String, Object> attrs, String explicitScope, String objectId) {
        if (type.numbering() == null) {
            throw RegistryException.invalid("type " + type.typeKey() + " has no numbering");
        }
        String scope = explicitScope;
        if (scope == null && type.numbering().scopeAttribute() != null) {
            scope = Maps.str(attrs, type.numbering().scopeAttribute());
        }
        return numbering.allocate(c, ctx, type.numbering().series(), scope, null, attrs, RESOURCE, objectId);
    }

    // --------------------------------------------------------------------------------------- update / mutate

    /** Attribute update (patch), validated against the object's type version. */
    public RegisteredObject update(Connection c, RegistryContext ctx, String id, Long expectedVersion,
                                   Map<String, Object> patch, String displayName, String reason) {
        return mutate(c, ctx, id, expectedVersion, "UPDATE", reason, o -> {
            ObjectTypeDefinition type = typeOf(c, o);
            if (type.lifecycle().terminal(o.state())) {
                throw RegistryException.state("object " + id + " is in terminal state " + o.state());
            }
            RegisteredObject n = o.withAttrs(type.schema().validate(patch, o.attrs()));
            return displayName == null ? n : n.withDisplayName(displayName);
        });
    }

    /**
     * Lifecycle transition.
     *
     * @param countersignedBy second person for transitions that require maker-checker (must differ from the actor)
     * @param until           end of a temporary state (suspension), where the transition allows it
     */
    public RegisteredObject transition(Connection c, RegistryContext ctx, String id, Long expectedVersion, String name,
                                       String reason, Instant until, String countersignedBy) {
        return mutate(c, ctx, id, expectedVersion, name.toUpperCase(), reason, o -> {
            Lifecycle.Transition t = checkTransition(c, ctx, o, name, reason, until, countersignedBy);
            return o.withState(t.to(), reason, t.untilAllowed() ? until : null);
        });
    }

    /** Validates a transition for a kind service that changes more than the state in the same version. */
    public Lifecycle.Transition checkTransition(Connection c, RegistryContext ctx, RegisteredObject o, String name,
                                                String reason, Instant until, String countersignedBy) {
        ObjectTypeDefinition type = typeOf(c, o);
        Lifecycle.Transition t = type.lifecycle().transition(name, o.state());
        if (t.reasonRequired() && (reason == null || reason.isBlank())) {
            throw RegistryException.invalid("transition " + name + " requires a reason");
        }
        if (t.countersign()) {
            if (countersignedBy == null || countersignedBy.isBlank()) {
                throw RegistryException.invalid("transition " + name + " requires a countersignature");
            }
            if (countersignedBy.equals(ctx.actor())) {
                throw RegistryException.forbidden("the countersigning person must differ from the maker (SoD)");
            }
        }
        if (until != null && !t.untilAllowed()) {
            throw RegistryException.invalid("transition " + name + " does not take an end date");
        }
        return t;
    }

    /**
     * The one write path: locks the row, checks the expected version, applies {@code change}, stores the new
     * current row and version, audits.
     */
    public RegisteredObject mutate(Connection c, RegistryContext ctx, String id, Long expectedVersion, String changeType,
                                   String reason, UnaryOperator<RegisteredObject> change) {
        RegisteredObject before = lock(c, ctx, id);
        if (expectedVersion != null && expectedVersion != before.version()) {
            throw RegistryException.conflict("object " + id + " is at version " + before.version()
                    + ", expected " + expectedVersion);
        }
        RegisteredObject changed = change.apply(before);
        Instant now = Db.now(c, dialect);
        long v = before.version() + 1;
        RegisteredObject after = new RegisteredObject(before.id(), before.tenant(), before.typeKey(), before.typeVersion(),
                before.kind(), changed.number(), changed.displayName(), changed.state(), changed.stateReason(),
                changed.stateUntil(), v, changed.validFrom(), changed.validTo(), changed.renewalDueAt(),
                changed.renewalCount(), changed.holderPartyId(), changed.subjectRef(), changed.parentId(),
                changed.externalRef(), changed.attrs(), before.createdAt(), before.createdBy(), now, ctx.actor());
        Jdbc.update(c, "UPDATE REG_OBJECT SET object_number=?, display_name=?, state=?, state_reason=?, state_until=?, version=?,"
                        + " valid_from=?, valid_to=?, renewal_due_at=?, renewal_count=?, holder_party_id=?, subject_ref=?, parent_id=?,"
                        + " external_ref=?, attrs_json=?, updated_at=?, updated_by=? WHERE id=? AND tenant_id=? AND version=?",
                after.number(), after.displayName(), after.state(), after.stateReason(), after.stateUntil(), v,
                after.validFrom(), after.validTo(), after.renewalDueAt(), after.renewalCount(), after.holderPartyId(),
                after.subjectRef(), after.parentId(), after.externalRef(), Json.write(after.attrs()), now, ctx.actor(),
                id, ctx.tenant(), before.version());
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("change", changeType);
        facts.put("version", v);
        facts.put("fromState", before.state());
        facts.put("toState", after.state());
        if (reason != null) {
            facts.put("reason", reason);
        }
        if (!java.util.Objects.equals(before.holderPartyId(), after.holderPartyId())) {
            facts.put("fromHolder", before.holderPartyId());
            facts.put("toHolder", after.holderPartyId());
        }
        if (!java.util.Objects.equals(before.validTo(), after.validTo())) {
            facts.put("validTo", after.validTo());
        }
        facts.put("attrsDigest", digest(after.attrs()));
        AuditChain.AuditEvent ev = audit.record(c, ctx, id, "REGISTRY_OBJECT_" + changeType, facts);
        insertVersion(c, after, changeType, reason, ev.eventId(), now, ctx.actor());
        pushExternal(ctx, typeOf(c, after), after, changeType);
        return after;
    }

    /** Reads the object under a row lock (inside the caller's transaction), honouring visibility. */
    public RegisteredObject lock(Connection c, RegistryContext ctx, String id) {
        get(c, ctx, id);
        return Jdbc.one(c, dialect.lockingSelect(RegisteredObject.COLUMNS.replace("o.", ""), "REG_OBJECT", "id=? AND tenant_id=?"),
                RegisteredObject::map, id, ctx.tenant()).orElseThrow(() -> RegistryException.notFound("object", id));
    }

    private void insertVersion(Connection c, RegisteredObject o, String changeType, String reason, String auditEventId,
                               Instant at, String by) {
        Jdbc.update(c, "INSERT INTO REG_OBJECT_VERSION(object_id, version, tenant_id, change_type, state, reason, valid_from,"
                        + " valid_to, holder_party_id, attrs_json, audit_event_id, changed_at, changed_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                o.id(), o.version(), o.tenant(), changeType, o.state(), reason, o.validFrom(), o.validTo(),
                o.holderPartyId(), Json.write(o.attrs()), auditEventId, at, by);
    }

    private void pushExternal(RegistryContext ctx, ObjectTypeDefinition type, RegisteredObject o, String change) {
        if ("EXTERNAL".equalsIgnoreCase(Maps.str(type.extensions(), "systemOfRecord"))) {
            ports.records().push(ctx, type.typeKey(), o.id(), o.externalRef(), change, o.toMap());
        }
    }

    // ------------------------------------------------------------------------------------------------ facade

    /**
     * Facade mode: creates or refreshes the local mirror of an object held by an external system of record. The
     * external view's {@code attributes}, {@code state}, {@code number}, {@code validFrom}/{@code validTo} and
     * {@code holderPartyId} are copied (validated against the schema).
     */
    public RegisteredObject mirror(Connection c, RegistryContext ctx, String typeKey, String externalRef) {
        Map<String, Object> ext = ports.records().fetch(ctx, typeKey, externalRef)
                .orElseThrow(() -> RegistryException.notFound("external record", externalRef));
        ObjectTypeDefinition type = types.current(c, ctx.tenant(), typeKey);
        var existing = Jdbc.one(c, "SELECT id FROM REG_OBJECT WHERE tenant_id=? AND type_key=? AND external_ref=?",
                rs -> rs.getString(1), ctx.tenant(), typeKey, externalRef);
        String state = Maps.str(ext, "state", type.lifecycle().initial());
        if (!type.lifecycle().states().containsKey(state)) {
            throw RegistryException.invalid("external state " + state + " is not part of type " + typeKey);
        }
        Instant from = Maps.instant(ext, "validFrom");
        Instant to = Maps.instant(ext, "validTo");
        if (existing.isEmpty()) {
            RegisteredObject o = create(c, ctx, type, new CreateRequest(typeKey, Maps.map(ext, "attributes"),
                    Maps.str(ext, "displayName"), Maps.str(ext, "holderPartyId"), Maps.str(ext, "subjectRef"), null,
                    externalRef, null, null, null, false, false), state);
            return mutate(c, ctx, o.id(), null, "MIRROR", "external system of record", x ->
                    x.withNumber(Maps.str(ext, "number")).withValidity(from, to, type.validity().renewalDueAt(to, ctx.zone()), x.renewalCount()));
        }
        return mutate(c, ctx, existing.get(), null, "MIRROR", "external system of record", x ->
                x.withAttrs(type.schema().validate(Maps.map(ext, "attributes"), null)).withState(state, null, null)
                        .withNumber(Maps.str(ext, "number")).withHolder(Maps.str(ext, "holderPartyId"))
                        .withValidity(from, to, type.validity().renewalDueAt(to, ctx.zone()), x.renewalCount()));
    }

    // ------------------------------------------------------------------------------------------------ queries

    public RegisteredObject get(Connection c, RegistryContext ctx, String id) {
        SqlPredicate vis = ports.visibility().visible(ctx, RESOURCE, "o");
        List<Object> params = new ArrayList<>(List.of(id, ctx.tenant()));
        params.addAll(vis.params());
        return Jdbc.one(c, "SELECT " + RegisteredObject.COLUMNS + " FROM REG_OBJECT o WHERE o.id=? AND o.tenant_id=? AND ("
                        + vis.sql() + ")", RegisteredObject::map, params.toArray())
                .orElseThrow(() -> RegistryException.notFound("object", id));
    }

    public RegisteredObject byNumber(Connection c, RegistryContext ctx, String typeKey, String number) {
        SqlPredicate vis = ports.visibility().visible(ctx, RESOURCE, "o");
        List<Object> params = new ArrayList<>(List.of(ctx.tenant(), number));
        String typeFilter = "";
        if (typeKey != null) {
            typeFilter = " AND o.type_key=?";
            params.add(typeKey);
        }
        params.addAll(vis.params());
        List<RegisteredObject> rows = Jdbc.query(c, "SELECT " + RegisteredObject.COLUMNS + " FROM REG_OBJECT o WHERE o.tenant_id=?"
                + " AND o.object_number=?" + typeFilter + " AND (" + vis.sql() + ")", RegisteredObject::map, params.toArray());
        if (rows.isEmpty()) {
            throw RegistryException.notFound("object with number", number);
        }
        if (rows.size() > 1) {
            throw RegistryException.conflict("number " + number + " is used by several types; give the type");
        }
        return rows.get(0);
    }

    public List<RegisteredObject> list(Connection c, RegistryContext ctx, Query q) {
        StringBuilder where = new StringBuilder("o.tenant_id=?");
        List<Object> params = new ArrayList<>(List.of(ctx.tenant()));
        add(where, params, "o.type_key", q.typeKey());
        add(where, params, "o.kind", q.kind() == null ? null : q.kind().toUpperCase());
        add(where, params, "o.state", q.state());
        add(where, params, "o.holder_party_id", q.holderPartyId());
        add(where, params, "o.parent_id", q.parentId());
        add(where, params, "o.object_number", q.number());
        add(where, params, "o.subject_ref", q.subjectRef());
        return Db.page(c, dialect, "SELECT " + RegisteredObject.COLUMNS + " FROM REG_OBJECT o", where.toString(), params,
                ports.visibility().visible(ctx, RESOURCE, "o"), "o.created_at DESC, o.id", q.page(), q.size(),
                RegisteredObject::map);
    }

    private static void add(StringBuilder where, List<Object> params, String col, Object v) {
        if (v != null) {
            where.append(" AND ").append(col).append("=?");
            params.add(v);
        }
    }

    public List<Version> history(Connection c, RegistryContext ctx, String id) {
        get(c, ctx, id);
        return Jdbc.query(c, "SELECT version, change_type, state, reason, valid_from, valid_to, holder_party_id, attrs_json,"
                        + " audit_event_id, changed_at, changed_by FROM REG_OBJECT_VERSION WHERE object_id=? AND tenant_id=? ORDER BY version",
                rs -> new Version(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), Jdbc.instant(rs, "valid_from"),
                        Jdbc.instant(rs, "valid_to"), rs.getString(7), Json.map(rs.getString(8)), rs.getString(9),
                        Jdbc.instant(rs, "changed_at"), rs.getString(11)), id, ctx.tenant());
    }

    /** The object as it was at an instant (from the version history). */
    public Version asOf(Connection c, RegistryContext ctx, String id, Instant at) {
        Version found = null;
        for (Version v : history(c, ctx, id)) {
            if (!v.changedAt().isAfter(at)) {
                found = v;
            }
        }
        if (found == null) {
            throw RegistryException.notFound("version of " + id + " at", at.toString());
        }
        return found;
    }

    /** Recomputes the object's audit hash chain. */
    public AuditChain.VerifyResult verifyAudit(Connection c, RegistryContext ctx, String id) {
        get(c, ctx, id);
        return audit.verify(c, ctx.tenant(), id);
    }

    /** Sweep purposes and their default transition names (overridable per type, see {@link #sweepTransition}). */
    public static final String SWEEP_EXPIRE = "expire";
    public static final String SWEEP_RENEWAL_DUE = "renewalDue";
    public static final String SWEEP_REINSTATE = "reinstate";

    /**
     * The transition a sweep job fires for a type: the type's {@code extensions.<purpose>Transition} (for example
     * {@code "expireTransition": "lapse"}), else the purpose name itself.
     */
    public String sweepTransition(ObjectTypeDefinition type, String purpose) {
        return Maps.str(type.extensions(), purpose + "Transition", purpose);
    }

    /**
     * The states from which a sweep may fire for a type: every state of the type's configured lifecycle from which
     * the sweep's transition is allowed. Empty when the lifecycle has no such transition (the type is not swept).
     */
    public List<String> sweepStates(ObjectTypeDefinition type, String purpose) {
        String name = sweepTransition(type, purpose);
        List<String> out = new ArrayList<>();
        for (String s : type.lifecycle().states().keySet()) {
            if (type.lifecycle().available(s).contains(name)) {
                out.add(s);
            }
        }
        return out;
    }

    /** Ids of objects whose validity ended before {@code now}, in a state the type's lifecycle can expire from. */
    public List<String> dueForExpiry(Connection c, String tenant, String typeKey, Instant now, int limit) {
        return due(c, tenant, typeKey, SWEEP_EXPIRE, "valid_to", now, limit);
    }

    /** Ids of objects whose renewal window has opened, in a state the type's lifecycle can mark renewal-due from. */
    public List<String> dueForRenewal(Connection c, String tenant, String typeKey, Instant now, int limit) {
        return due(c, tenant, typeKey, SWEEP_RENEWAL_DUE, "renewal_due_at", now, limit);
    }

    /** Ids of objects whose temporary state ended, in a state the type's lifecycle can reinstate from. */
    public List<String> dueForReinstatement(Connection c, String tenant, String typeKey, Instant now, int limit) {
        return due(c, tenant, typeKey, SWEEP_REINSTATE, "state_until", now, limit);
    }

    /**
     * Sweep candidates: for each (type, version) with due rows, the states come from that version's configured
     * lifecycle, so packs that rename or add states are swept correctly. {@code timeColumn} is a fixed column name.
     */
    private List<String> due(Connection c, String tenant, String typeKey, String purpose, String timeColumn,
                             Instant now, int limit) {
        String cond = "o." + timeColumn + " IS NOT NULL AND o." + timeColumn + " <= ?";
        List<Object> params = new ArrayList<>(List.of(tenant, now));
        String tf = "";
        if (typeKey != null) {
            tf = " AND o.type_key=?";
            params.add(typeKey);
        }
        List<Object[]> versions = Jdbc.query(c, "SELECT DISTINCT o.type_key, o.type_version FROM REG_OBJECT o WHERE o.tenant_id=? AND "
                + cond + tf, rs -> new Object[]{rs.getString(1), rs.getInt(2)}, params.toArray());
        List<String> out = new ArrayList<>();
        for (Object[] tv : versions) {
            if (out.size() >= limit) {
                break;
            }
            ObjectTypeDefinition def = types.version(c, tenant, (String) tv[0], (Integer) tv[1]);
            List<String> states = sweepStates(def, purpose);
            if (states.isEmpty()) {
                continue;
            }
            List<Object> p = new ArrayList<>(List.of(tenant, now, tv[0], tv[1]));
            p.addAll(states);
            out.addAll(Db.page(c, dialect, "SELECT o.id FROM REG_OBJECT o", "o.tenant_id=? AND " + cond
                            + " AND o.type_key=? AND o.type_version=? AND o.state IN ("
                            + String.join(",", java.util.Collections.nCopies(states.size(), "?")) + ")",
                    p, null, "o.id", 0, limit - out.size(), rs -> rs.getString(1)));
        }
        return out;
    }

    public ObjectTypeDefinition typeOf(Connection c, RegisteredObject o) {
        return types.version(c, o.tenant(), o.typeKey(), o.typeVersion());
    }

    static String digest(Map<String, Object> attrs) {
        return org.sangam.core.layer.Hashes.hex(org.sangam.core.layer.Hashes.sha256(
                org.sangam.core.layer.CanonicalJson.write(Json.canonical(attrs))));
    }
}
