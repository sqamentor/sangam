// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.kernel;

import org.sangam.core.layer.Jdbc;
import org.sangam.registry.support.Json;
import org.sangam.registry.support.Maps;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;

/** The current state of one registered object (any kind). Immutable; changes go through the kernel service. */
public record RegisteredObject(String id, String tenant, String typeKey, int typeVersion, ObjectKind kind,
                               String number, String displayName, String state, String stateReason, Instant stateUntil,
                               long version, Instant validFrom, Instant validTo, Instant renewalDueAt, int renewalCount,
                               String holderPartyId, String subjectRef, String parentId, String externalRef,
                               Map<String, Object> attrs, Instant createdAt, String createdBy, Instant updatedAt,
                               String updatedBy) {

    static final String COLUMNS = "o.id, o.tenant_id, o.type_key, o.type_version, o.kind, o.object_number, o.display_name,"
            + " o.state, o.state_reason, o.state_until, o.version, o.valid_from, o.valid_to, o.renewal_due_at,"
            + " o.renewal_count, o.holder_party_id, o.subject_ref, o.parent_id, o.external_ref, o.attrs_json,"
            + " o.created_at, o.created_by, o.updated_at, o.updated_by";

    static RegisteredObject map(ResultSet rs) throws SQLException {
        return new RegisteredObject(rs.getString("id"), rs.getString("tenant_id"), rs.getString("type_key"),
                rs.getInt("type_version"), ObjectKind.valueOf(rs.getString("kind")), rs.getString("object_number"),
                rs.getString("display_name"), rs.getString("state"), rs.getString("state_reason"),
                Jdbc.instant(rs, "state_until"), rs.getLong("version"), Jdbc.instant(rs, "valid_from"),
                Jdbc.instant(rs, "valid_to"), Jdbc.instant(rs, "renewal_due_at"), rs.getInt("renewal_count"),
                rs.getString("holder_party_id"), rs.getString("subject_ref"), rs.getString("parent_id"),
                rs.getString("external_ref"), Json.map(rs.getString("attrs_json")), Jdbc.instant(rs, "created_at"),
                rs.getString("created_by"), Jdbc.instant(rs, "updated_at"), rs.getString("updated_by"));
    }

    public RegisteredObject withState(String s, String reason, Instant until) {
        return new RegisteredObject(id, tenant, typeKey, typeVersion, kind, number, displayName, s, reason, until, version,
                validFrom, validTo, renewalDueAt, renewalCount, holderPartyId, subjectRef, parentId, externalRef, attrs,
                createdAt, createdBy, updatedAt, updatedBy);
    }

    public RegisteredObject withAttrs(Map<String, Object> a) {
        return new RegisteredObject(id, tenant, typeKey, typeVersion, kind, number, displayName, state, stateReason,
                stateUntil, version, validFrom, validTo, renewalDueAt, renewalCount, holderPartyId, subjectRef, parentId,
                externalRef, a, createdAt, createdBy, updatedAt, updatedBy);
    }

    public RegisteredObject withValidity(Instant from, Instant to, Instant due, int renewals) {
        return new RegisteredObject(id, tenant, typeKey, typeVersion, kind, number, displayName, state, stateReason,
                stateUntil, version, from, to, due, renewals, holderPartyId, subjectRef, parentId, externalRef, attrs,
                createdAt, createdBy, updatedAt, updatedBy);
    }

    public RegisteredObject withHolder(String party) {
        return new RegisteredObject(id, tenant, typeKey, typeVersion, kind, number, displayName, state, stateReason,
                stateUntil, version, validFrom, validTo, renewalDueAt, renewalCount, party, subjectRef, parentId,
                externalRef, attrs, createdAt, createdBy, updatedAt, updatedBy);
    }

    public RegisteredObject withNumber(String n) {
        return new RegisteredObject(id, tenant, typeKey, typeVersion, kind, n, displayName, state, stateReason,
                stateUntil, version, validFrom, validTo, renewalDueAt, renewalCount, holderPartyId, subjectRef, parentId,
                externalRef, attrs, createdAt, createdBy, updatedAt, updatedBy);
    }

    public RegisteredObject withDisplayName(String n) {
        return new RegisteredObject(id, tenant, typeKey, typeVersion, kind, number, n, state, stateReason,
                stateUntil, version, validFrom, validTo, renewalDueAt, renewalCount, holderPartyId, subjectRef, parentId,
                externalRef, attrs, createdAt, createdBy, updatedAt, updatedBy);
    }

    public RegisteredObject withLinks(String subject, String parent) {
        return new RegisteredObject(id, tenant, typeKey, typeVersion, kind, number, displayName, state, stateReason,
                stateUntil, version, validFrom, validTo, renewalDueAt, renewalCount, holderPartyId, subject, parent,
                externalRef, attrs, createdAt, createdBy, updatedAt, updatedBy);
    }

    public Map<String, Object> toMap() {
        return Maps.of("id", id, "typeKey", typeKey, "typeVersion", typeVersion, "kind", kind.name(), "number", number,
                "displayName", displayName, "state", state, "stateReason", stateReason, "stateUntil", stateUntil,
                "version", version, "validFrom", validFrom, "validTo", validTo, "renewalDueAt", renewalDueAt,
                "renewalCount", renewalCount, "holderPartyId", holderPartyId, "subjectRef", subjectRef,
                "parentId", parentId, "externalRef", externalRef, "attributes", attrs, "createdAt", createdAt,
                "createdBy", createdBy, "updatedAt", updatedAt, "updatedBy", updatedBy);
    }
}
