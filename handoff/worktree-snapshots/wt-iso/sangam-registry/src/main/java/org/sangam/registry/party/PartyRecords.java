// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.party;

import org.sangam.core.layer.Jdbc;
import org.sangam.registry.support.Db;
import org.sangam.registry.support.Json;
import org.sangam.registry.support.Maps;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Row records of the party registry. */
public final class PartyRecords {

    private PartyRecords() {
    }

    /** A person or organisation (golden record). */
    public record Party(String id, String tenant, String kind, String status, String mergedInto, String displayName,
                        Map<String, Object> names, String birthDate, String gender, Map<String, Object> attrs,
                        String source, int sourcePriority, long version, Instant createdAt, String createdBy,
                        Instant updatedAt, String updatedBy) {

        static final String COLUMNS = "p.id, p.tenant_id, p.party_kind, p.status, p.merged_into, p.display_name, p.name_json,"
                + " p.birth_date, p.gender, p.attrs_json, p.source, p.source_priority, p.version, p.created_at, p.created_by,"
                + " p.updated_at, p.updated_by";

        static Party map(ResultSet rs) throws SQLException {
            return new Party(rs.getString("id"), rs.getString("tenant_id"), rs.getString("party_kind"), rs.getString("status"),
                    rs.getString("merged_into"), rs.getString("display_name"), Json.map(rs.getString("name_json")),
                    rs.getString("birth_date"), rs.getString("gender"), Json.map(rs.getString("attrs_json")),
                    rs.getString("source"), rs.getInt("source_priority"), rs.getLong("version"),
                    Jdbc.instant(rs, "created_at"), rs.getString("created_by"), Jdbc.instant(rs, "updated_at"),
                    rs.getString("updated_by"));
        }

        /** The mutable content (the before/after image of a merge). */
        public Map<String, Object> image() {
            return Maps.of("displayName", displayName, "names", names, "birthDate", birthDate, "gender", gender,
                    "attributes", attrs, "source", source, "sourcePriority", sourcePriority, "status", status,
                    "mergedInto", mergedInto, "updatedAt", updatedAt);
        }

        public Map<String, Object> toMap() {
            return Maps.of("id", id, "kind", kind, "status", status, "mergedInto", mergedInto, "displayName", displayName,
                    "names", names, "birthDate", birthDate, "gender", gender, "attributes", attrs, "source", source,
                    "sourcePriority", sourcePriority, "version", version, "createdAt", createdAt, "createdBy", createdBy,
                    "updatedAt", updatedAt, "updatedBy", updatedBy);
        }
    }

    /** An identifier; the clear value is present only for non-sensitive types. */
    public record Identifier(String id, String partyId, String idType, String value, String valueHash, String masked,
                             String vaultRef, String issuer, Instant validFrom, Instant validTo, boolean verified,
                             String status, String movedFromParty, Instant createdAt) {

        static final String COLUMNS = "i.id, i.party_id, i.id_type, i.value_normalized, i.value_hash, i.value_masked, i.vault_ref,"
                + " i.issuer, i.valid_from, i.valid_to, i.verified_flag, i.status, i.moved_from_party, i.created_at";

        static Identifier map(ResultSet rs) throws SQLException {
            return new Identifier(rs.getString("id"), rs.getString("party_id"), rs.getString("id_type"),
                    rs.getString("value_normalized"), rs.getString("value_hash"), rs.getString("value_masked"),
                    rs.getString("vault_ref"), rs.getString("issuer"), Jdbc.instant(rs, "valid_from"),
                    Jdbc.instant(rs, "valid_to"), rs.getInt("verified_flag") == 1, rs.getString("status"),
                    rs.getString("moved_from_party"), Jdbc.instant(rs, "created_at"));
        }

        public Map<String, Object> toMap() {
            return Maps.of("id", id, "partyId", partyId, "type", idType, "value", value, "masked", masked,
                    "vaulted", vaultRef != null, "issuer", issuer, "validFrom", validFrom, "validTo", validTo,
                    "verified", verified, "status", status, "movedFromParty", movedFromParty, "createdAt", createdAt);
        }
    }

    public record Relationship(String id, String fromParty, String toParty, String type, String status,
                               Instant validFrom, Instant validTo, Map<String, Object> attrs, Instant createdAt) {

        static final String COLUMNS = "r.id, r.from_party, r.to_party, r.rel_type, r.status, r.valid_from, r.valid_to,"
                + " r.attrs_json, r.created_at";

        static Relationship map(ResultSet rs) throws SQLException {
            return new Relationship(rs.getString("id"), rs.getString("from_party"), rs.getString("to_party"),
                    rs.getString("rel_type"), rs.getString("status"), Jdbc.instant(rs, "valid_from"),
                    Jdbc.instant(rs, "valid_to"), Json.map(rs.getString("attrs_json")), Jdbc.instant(rs, "created_at"));
        }

        public Map<String, Object> toMap() {
            return Maps.of("id", id, "fromParty", fromParty, "toParty", toParty, "type", type, "status", status,
                    "validFrom", validFrom, "validTo", validTo, "attributes", attrs, "createdAt", createdAt);
        }
    }

    public record Address(String id, String partyId, String type, Map<String, Object> address, String geometryRef,
                          boolean primary, Instant validFrom, Instant validTo, String status, Instant createdAt) {

        static final String COLUMNS = "a.id, a.party_id, a.address_type, a.address_json, a.geometry_ref, a.primary_flag,"
                + " a.valid_from, a.valid_to, a.status, a.created_at";

        static Address map(ResultSet rs) throws SQLException {
            return new Address(rs.getString("id"), rs.getString("party_id"), rs.getString("address_type"),
                    Json.map(rs.getString("address_json")), rs.getString("geometry_ref"), rs.getInt("primary_flag") == 1,
                    Jdbc.instant(rs, "valid_from"), Jdbc.instant(rs, "valid_to"), rs.getString("status"),
                    Jdbc.instant(rs, "created_at"));
        }

        public Map<String, Object> toMap() {
            return Maps.of("id", id, "partyId", partyId, "type", type, "address", address, "geometryRef", geometryRef,
                    "primary", primary, "validFrom", validFrom, "validTo", validTo, "status", status, "createdAt", createdAt);
        }
    }

    /** A scored possible duplicate. */
    public record MatchResult(String partyId, double score, String decision, String ruleKey, Map<String, Object> details) {
        public Map<String, Object> toMap() {
            return Maps.of("partyId", partyId, "score", score, "decision", decision, "rule", ruleKey, "details", details);
        }
    }

    public record Candidate(String id, String partyA, String partyB, BigDecimal score, String decision, String ruleKey,
                            Map<String, Object> details, String status, Instant createdAt, Instant resolvedAt,
                            String resolvedBy) {

        static Candidate map(ResultSet rs) throws SQLException {
            return new Candidate(rs.getString("id"), rs.getString("party_a"), rs.getString("party_b"),
                    Db.decimal(rs, "score"), rs.getString("decision"), rs.getString("rule_key"),
                    Json.map(rs.getString("details_json")), rs.getString("status"), Jdbc.instant(rs, "created_at"),
                    Jdbc.instant(rs, "resolved_at"), rs.getString("resolved_by"));
        }

        public Map<String, Object> toMap() {
            return Maps.of("id", id, "partyA", partyA, "partyB", partyB, "score", score, "decision", decision,
                    "rule", ruleKey, "details", details, "status", status, "createdAt", createdAt,
                    "resolvedAt", resolvedAt, "resolvedBy", resolvedBy);
        }
    }

    public record MergeEvent(String id, String survivorId, String mergedId, String status,
                             Map<String, Object> survivorBefore, Map<String, Object> mergedBefore,
                             Map<String, Object> survivorAfter, Map<String, Object> moved, String reason,
                             String candidateId, Instant mergedAt, String mergedBy, Instant unmergedAt,
                             String unmergedBy, String unmergeReason) {

        static final String COLUMNS = "id, survivor_id, merged_id, status, survivor_before_json, merged_before_json,"
                + " survivor_after_json, moved_json, reason, candidate_id, merged_at, merged_by, unmerged_at, unmerged_by, unmerge_reason";

        static MergeEvent map(ResultSet rs) throws SQLException {
            return new MergeEvent(rs.getString("id"), rs.getString("survivor_id"), rs.getString("merged_id"),
                    rs.getString("status"), Json.map(rs.getString("survivor_before_json")),
                    Json.map(rs.getString("merged_before_json")), Json.map(rs.getString("survivor_after_json")),
                    Json.map(rs.getString("moved_json")), rs.getString("reason"), rs.getString("candidate_id"),
                    Jdbc.instant(rs, "merged_at"), rs.getString("merged_by"), Jdbc.instant(rs, "unmerged_at"),
                    rs.getString("unmerged_by"), rs.getString("unmerge_reason"));
        }

        public Map<String, Object> toMap() {
            return Maps.of("id", id, "survivorId", survivorId, "mergedId", mergedId, "status", status,
                    "survivorBefore", survivorBefore, "mergedBefore", mergedBefore, "survivorAfter", survivorAfter,
                    "moved", moved, "reason", reason, "candidateId", candidateId, "mergedAt", mergedAt,
                    "mergedBy", mergedBy, "unmergedAt", unmergedAt, "unmergedBy", unmergedBy,
                    "unmergeReason", unmergeReason);
        }
    }

    public record Snapshot(String id, String partyId, String reference, String purpose, Map<String, Object> content,
                           String digestHex, Instant takenAt, String takenBy) {
        public Map<String, Object> toMap() {
            return Maps.of("id", id, "partyId", partyId, "reference", reference, "purpose", purpose, "content", content,
                    "digest", digestHex, "takenAt", takenAt, "takenBy", takenBy);
        }
    }

    /** A household (or other group) roll-up. */
    public record Household(String rootPartyId, List<Map<String, Object>> members, int size) {
        public Map<String, Object> toMap() {
            return Maps.of("rootPartyId", rootPartyId, "members", members, "size", size);
        }
    }
}
