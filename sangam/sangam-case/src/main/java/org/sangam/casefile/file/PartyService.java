// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.file;

import org.sangam.casefile.CaseFileException;
import org.sangam.casefile.common.CaseEvents;
import org.sangam.casefile.config.CaseConfig;
import org.sangam.casefile.config.CaseConfigRegistry;
import org.sangam.casefile.ports.CaseFilePorts;
import org.sangam.casefile.support.Db;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The party model (N04): parties (persons and organisations) registered once per tenant, and their roles on cases
 * (roles are data from the case type). Identifiers are matched through unsalted SHA-256 keys of the normalised value
 * ({@code CF_PARTY_KEY}), so duplicate detection never needs the raw value in an index. Conflict check: the same party
 * in two roles the case type declares conflicting, on this case or on other open cases of the type; duplicate check:
 * open cases of the same type that share a party in the type's {@code duplicateCheck.roles}.
 */
public class PartyService {

    public record Party(String tenantId, String partyId, String kind, String displayName, Map<String, Object> identifiers,
                        Map<String, Object> contact, String externalRef, String createdBy, Instant createdAt) {
    }

    public record PartyInput(String kind, String displayName, Map<String, Object> identifiers, Map<String, Object> contact,
                             String externalRef) {
    }

    public record CaseParty(String tenantId, String casePartyId, String caseId, String partyId, String role, String capacity,
                            String representedBy, boolean masked, Instant validFrom, Instant validTo, String addedBy,
                            Instant addedAt, Party party) {
    }

    /** A finding of the conflict check. {@code kind}: SAME_CASE_CONFLICT, CROSS_CASE_CONFLICT, OTHER_OPEN_CASE. */
    public record Conflict(String kind, String caseId, String caseNumber, String partyId, String role, String otherRole) {
    }

    public record DuplicateCandidate(String caseId, String caseNumber, String title, List<String> sharedParties) {
    }

    private final Dialect dialect;
    private final CaseConfigRegistry config;
    private final CaseEvents events;
    private final CaseFilePorts.PartyDirectory directory;

    public PartyService(Dialect dialect, CaseConfigRegistry config, CaseEvents events, CaseFilePorts.PartyDirectory directory) {
        this.dialect = dialect;
        this.config = config;
        this.events = events;
        this.directory = directory == null ? CaseFilePorts.PartyDirectory.NONE : directory;
    }

    // ------------------------------------------------------------------ registry

    public Party register(Connection c, String tenant, PartyInput in, String actor) {
        String t = Db.tenant(tenant);
        String kind = Db.str(in.kind()) == null ? "PERSON" : in.kind();
        String name = in.displayName();
        Map<String, Object> contact = in.contact() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(in.contact());
        if (in.externalRef() != null) {
            Optional<Map<String, Object>> ext = directory.resolve(c, t, in.externalRef());
            if (ext.isPresent()) {
                name = name == null ? Db.str(ext.get(), "displayName", null) : name;
                Db.obj(ext.get().get("contact")).forEach(contact::putIfAbsent);
            }
        }
        Db.requireText(name, "displayName");
        String id = Db.newId();
        Map<String, Object> ids = in.identifiers() == null ? Map.of() : in.identifiers();
        List<String> keys = identifierKeys(ids);
        Jdbc.update(c, "INSERT INTO CF_PARTY(tenant_id, party_id, party_kind, display_name, name_key, identifiers_json, identifier_keys,"
                        + " contact_json, external_ref, created_by, created_at) VALUES (?,?,?,?,?,?,?,?,?,?," + dialect.now() + ")",
                t, id, kind, name, nameKey(name), Db.json(ids), Db.json(keys), Db.json(contact), in.externalRef(), actor);
        for (String k : keys) {
            Jdbc.insertIfAbsent(c, dialect, "INSERT INTO CF_PARTY_KEY(tenant_id, key_hash, party_id) VALUES (?,?,?)", t, k, id);
        }
        return party(c, t, id);
    }

    public Party party(Connection c, String tenant, String partyId) {
        return Jdbc.one(c, "SELECT * FROM CF_PARTY WHERE tenant_id=? AND party_id=?", PartyService::partyRow, Db.tenant(tenant), partyId)
                .orElseThrow(() -> CaseFileException.notFound("party", partyId));
    }

    public Party updateContact(Connection c, String tenant, String partyId, Map<String, Object> contact, String actor) {
        party(c, tenant, partyId);
        Jdbc.update(c, "UPDATE CF_PARTY SET contact_json=? WHERE tenant_id=? AND party_id=?", Db.json(contact), Db.tenant(tenant), partyId);
        return party(c, tenant, partyId);
    }

    /** Existing parties with any matching identifier, or (when none are given) the same normalised name. */
    public List<Party> candidates(Connection c, String tenant, Map<String, Object> identifiers, String name) {
        String t = Db.tenant(tenant);
        Set<String> ids = new LinkedHashSet<>();
        for (String k : identifierKeys(identifiers == null ? Map.of() : identifiers)) {
            ids.addAll(Jdbc.query(c, "SELECT party_id FROM CF_PARTY_KEY WHERE tenant_id=? AND key_hash=?", rs -> rs.getString(1), t, k));
        }
        if (ids.isEmpty() && name != null && !name.isBlank()) {
            ids.addAll(Jdbc.query(c, "SELECT party_id FROM CF_PARTY WHERE tenant_id=? AND name_key=?", rs -> rs.getString(1), t, nameKey(name)));
        }
        List<Party> out = new ArrayList<>();
        for (String id : ids) {
            out.add(party(c, t, id));
            if (out.size() >= 50) {
                break;
            }
        }
        return out;
    }

    /** Registers the party unless one with a matching identifier exists (then that one is returned). */
    public Party registerOrMatch(Connection c, String tenant, PartyInput in, String actor) {
        if (in.identifiers() != null && !in.identifiers().isEmpty()) {
            List<Party> found = candidates(c, tenant, in.identifiers(), null);
            if (found.size() == 1) {
                return found.get(0);
            }
        }
        return register(c, tenant, in, actor);
    }

    // ------------------------------------------------------------------ roles on cases

    public CaseParty addToCase(Connection c, String tenant, String caseId, String partyId, String role, String capacity,
                               String representedBy, boolean masked, String actor) {
        String t = Db.tenant(tenant);
        CaseFileService.CaseFile cf = caseRow(c, t, caseId);
        if (cf.status() != CaseFileService.Status.OPEN) {
            throw new CaseFileException(CaseFileException.Code.CASE_CLOSED, "case is " + cf.status());
        }
        CaseConfig.CaseType type = config.caseType(c, t, cf.caseType(), cf.typeVersion());
        CaseConfig.Role r = type.role(role);
        if (r == null) {
            throw CaseFileException.invalid("case type " + type.key() + " has no role " + role);
        }
        party(c, t, partyId);
        List<CaseParty> current = caseParties(c, t, caseId, false);
        long inRole = current.stream().filter(p -> p.role().equals(role)).count();
        if (r.max() > 0 && inRole >= r.max()) {
            throw new CaseFileException(CaseFileException.Code.PARTY_CONFLICT, "role " + role + " allows at most " + r.max());
        }
        for (CaseParty p : current) {
            if (p.partyId().equals(partyId) && p.role().equals(role)) {
                throw new CaseFileException(CaseFileException.Code.PARTY_CONFLICT, "party already holds role " + role);
            }
            if (p.partyId().equals(partyId) && type.conflicting(role, p.role())) {
                throw new CaseFileException(CaseFileException.Code.PARTY_CONFLICT,
                        "role " + role + " conflicts with the party's role " + p.role() + " on this case");
            }
        }
        if (representedBy != null) {
            party(c, t, representedBy);
        }
        String id = Db.newId();
        Instant now = Db.now(c, dialect);
        Jdbc.update(c, "INSERT INTO CF_CASE_PARTY(tenant_id, case_party_id, case_id, party_id, party_role, capacity, represented_by, masked,"
                        + " valid_from, added_by, added_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                t, id, caseId, partyId, role, capacity, representedBy, Db.small(masked || r.sensitive()), now, actor, now);
        events.record(c, t, CaseEvents.CASE, caseId, "PARTY_ADDED", actor, role,
                Db.kv("casePartyId", id, "partyId", partyId, "role", role, "masked", masked || r.sensitive()));
        return caseParty(c, t, id);
    }

    public CaseParty removeFromCase(Connection c, String tenant, String casePartyId, String reason, String actor) {
        String t = Db.tenant(tenant);
        CaseParty p = caseParty(c, t, casePartyId);
        if (p.validTo() != null) {
            throw CaseFileException.state("party role already ended");
        }
        Db.requireText(reason, "reason");
        Jdbc.update(c, "UPDATE CF_CASE_PARTY SET valid_to=" + dialect.now() + ", removed_by=?, remove_reason=? WHERE tenant_id=? AND case_party_id=?",
                actor, reason, t, casePartyId);
        events.record(c, t, CaseEvents.CASE, p.caseId(), "PARTY_REMOVED", actor, p.role(),
                Db.kv("casePartyId", casePartyId, "partyId", p.partyId(), "role", p.role(), "reason", reason));
        return caseParty(c, t, casePartyId);
    }

    public CaseParty caseParty(Connection c, String tenant, String casePartyId) {
        String t = Db.tenant(tenant);
        CaseParty p = Jdbc.one(c, "SELECT * FROM CF_CASE_PARTY WHERE tenant_id=? AND case_party_id=?", PartyService::casePartyRow, t, casePartyId)
                .orElseThrow(() -> CaseFileException.notFound("case party", casePartyId));
        return withParty(c, t, p);
    }

    public List<CaseParty> caseParties(Connection c, String tenant, String caseId, boolean includeEnded) {
        String t = Db.tenant(tenant);
        List<CaseParty> rows = Jdbc.query(c, "SELECT * FROM CF_CASE_PARTY WHERE tenant_id=? AND case_id=?"
                + (includeEnded ? "" : " AND valid_to IS NULL") + " ORDER BY added_at", PartyService::casePartyRow, t, caseId);
        List<CaseParty> out = new ArrayList<>();
        for (CaseParty p : rows) {
            out.add(withParty(c, t, p));
        }
        return out;
    }

    /** Cases (any status) where the party holds or held a role. */
    public List<CaseParty> casesOf(Connection c, String tenant, String partyId) {
        return Jdbc.query(c, "SELECT * FROM CF_CASE_PARTY WHERE tenant_id=? AND party_id=? ORDER BY added_at DESC",
                PartyService::casePartyRow, Db.tenant(tenant), partyId);
    }

    /**
     * Conflict check before (or after) adding {@code partyId} in {@code role} to a case of {@code caseType}: conflicting
     * roles on the same case, conflicting roles on other open cases of the same type, and other open cases of the type
     * the party appears on (for the officer's information).
     */
    public List<Conflict> conflictCheck(Connection c, String tenant, String caseType, String caseIdOrNull, String partyId, String role) {
        String t = Db.tenant(tenant);
        CaseConfig.CaseType type = config.caseType(c, t, caseType);
        List<Conflict> out = new ArrayList<>();
        List<Object[]> rows = Jdbc.query(c, "SELECT cp.case_id, cp.party_role, cf.case_number, cf.case_type FROM CF_CASE_PARTY cp"
                        + " JOIN CF_CASE cf ON cf.tenant_id=cp.tenant_id AND cf.case_id=cp.case_id"
                        + " WHERE cp.tenant_id=? AND cp.party_id=? AND cp.valid_to IS NULL AND cf.status='OPEN'",
                rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)}, t, partyId);
        for (Object[] r : rows) {
            String otherCase = (String) r[0];
            String otherRole = (String) r[1];
            boolean sameType = caseType.equals(r[3]);
            boolean conflicting = sameType && type.conflicting(role, otherRole);
            if (otherCase.equals(caseIdOrNull)) {
                if (conflicting || otherRole.equals(role)) {
                    out.add(new Conflict("SAME_CASE_CONFLICT", otherCase, (String) r[2], partyId, role, otherRole));
                }
            } else if (conflicting) {
                out.add(new Conflict("CROSS_CASE_CONFLICT", otherCase, (String) r[2], partyId, role, otherRole));
            } else if (sameType) {
                out.add(new Conflict("OTHER_OPEN_CASE", otherCase, (String) r[2], partyId, role, otherRole));
            }
        }
        return out;
    }

    /** Open cases of the type sharing any of {@code partyIds} in the type's duplicate-check roles. */
    public List<DuplicateCandidate> duplicates(Connection c, String tenant, String caseType, String excludeCaseIdOrNull,
                                               List<String> partyIds) {
        String t = Db.tenant(tenant);
        CaseConfig.CaseType type = config.caseType(c, t, caseType);
        if (type.duplicateRoles().isEmpty() || partyIds == null || partyIds.isEmpty()) {
            return List.of();
        }
        Map<String, DuplicateCandidate> found = new LinkedHashMap<>();
        for (String pid : new LinkedHashSet<>(partyIds)) {
            List<Object[]> rows = Jdbc.query(c, "SELECT cf.case_id, cf.case_number, cf.title, cp.party_role FROM CF_CASE_PARTY cp"
                            + " JOIN CF_CASE cf ON cf.tenant_id=cp.tenant_id AND cf.case_id=cp.case_id"
                            + " WHERE cp.tenant_id=? AND cp.party_id=? AND cp.valid_to IS NULL AND cf.status='OPEN' AND cf.case_type=?",
                    rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)}, t, pid, caseType);
            for (Object[] r : rows) {
                if (r[0].equals(excludeCaseIdOrNull) || !type.duplicateRoles().contains((String) r[3])) {
                    continue;
                }
                DuplicateCandidate d = found.computeIfAbsent((String) r[0],
                        k -> new DuplicateCandidate(k, (String) r[1], (String) r[2], new ArrayList<>()));
                if (!d.sharedParties().contains(pid)) {
                    d.sharedParties().add(pid);
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    /** Moves active party roles from one case to another (merge); duplicates on the target are ended. */
    int moveRoles(Connection c, String tenant, String fromCase, String toCase, String actor) {
        String t = Db.tenant(tenant);
        List<CaseParty> target = caseParties(c, t, toCase, false);
        int moved = 0;
        for (CaseParty p : caseParties(c, t, fromCase, false)) {
            boolean dup = target.stream().anyMatch(x -> x.partyId().equals(p.partyId()) && x.role().equals(p.role()));
            if (dup) {
                Jdbc.update(c, "UPDATE CF_CASE_PARTY SET valid_to=" + dialect.now() + ", removed_by=?, remove_reason='merged'"
                        + " WHERE tenant_id=? AND case_party_id=?", actor, t, p.casePartyId());
            } else {
                Jdbc.update(c, "UPDATE CF_CASE_PARTY SET case_id=? WHERE tenant_id=? AND case_party_id=?", toCase, t, p.casePartyId());
                moved++;
            }
        }
        return moved;
    }

    /** Copies selected party roles onto another case (split). */
    int copyRoles(Connection c, String tenant, List<String> casePartyIds, String toCase, String actor) {
        String t = Db.tenant(tenant);
        int n = 0;
        Instant now = Db.now(c, dialect);
        for (String id : casePartyIds) {
            CaseParty p = caseParty(c, t, id);
            Jdbc.update(c, "INSERT INTO CF_CASE_PARTY(tenant_id, case_party_id, case_id, party_id, party_role, capacity, represented_by,"
                            + " masked, valid_from, added_by, added_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    t, Db.newId(), toCase, p.partyId(), p.role(), p.capacity(), p.representedBy(), Db.small(p.masked()), now, actor, now);
            n++;
        }
        return n;
    }

    private CaseFileService.CaseFile caseRow(Connection c, String t, String caseId) {
        return Jdbc.one(c, "SELECT * FROM CF_CASE WHERE tenant_id=? AND case_id=?", CaseFileService::row, t, caseId)
                .orElseThrow(() -> CaseFileException.notFound("case", caseId));
    }

    private CaseParty withParty(Connection c, String t, CaseParty p) {
        Party party = Jdbc.one(c, "SELECT * FROM CF_PARTY WHERE tenant_id=? AND party_id=?", PartyService::partyRow, t, p.partyId()).orElse(null);
        return new CaseParty(p.tenantId(), p.casePartyId(), p.caseId(), p.partyId(), p.role(), p.capacity(), p.representedBy(),
                p.masked(), p.validFrom(), p.validTo(), p.addedBy(), p.addedAt(), party);
    }

    /** {@code type:value} keys, value normalised (case, spaces, separators) and hashed. */
    static List<String> identifierKeys(Map<String, Object> identifiers) {
        List<String> keys = new ArrayList<>();
        identifiers.forEach((type, value) -> {
            if (value != null && !value.toString().isBlank()) {
                String v = value.toString().toUpperCase(Locale.ROOT).replaceAll("[\\s\\-/.]", "");
                keys.add(Db.hashText(type.toUpperCase(Locale.ROOT) + ":" + v).substring(0, 64));
            }
        });
        return keys;
    }

    static String nameKey(String name) {
        String n = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        n = n.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
        return n.length() > 255 ? n.substring(0, 255) : n;
    }

    static Party partyRow(ResultSet rs) throws SQLException {
        return new Party(rs.getString("tenant_id"), rs.getString("party_id"), rs.getString("party_kind"), rs.getString("display_name"),
                Db.map(rs.getString("identifiers_json")), Db.map(rs.getString("contact_json")), rs.getString("external_ref"),
                rs.getString("created_by"), Jdbc.instant(rs, "created_at"));
    }

    static CaseParty casePartyRow(ResultSet rs) throws SQLException {
        return new CaseParty(rs.getString("tenant_id"), rs.getString("case_party_id"), rs.getString("case_id"), rs.getString("party_id"),
                rs.getString("party_role"), rs.getString("capacity"), rs.getString("represented_by"), rs.getInt("masked") != 0,
                Jdbc.instant(rs, "valid_from"), Jdbc.instant(rs, "valid_to"), rs.getString("added_by"), Jdbc.instant(rs, "added_at"), null);
    }
}
