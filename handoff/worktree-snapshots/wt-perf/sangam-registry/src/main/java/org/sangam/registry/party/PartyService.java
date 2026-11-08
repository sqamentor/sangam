// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.party;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.registry.RegistryContext;
import org.sangam.registry.RegistryException;
import org.sangam.registry.RegistryPorts;
import org.sangam.registry.party.PartyRecords.Address;
import org.sangam.registry.party.PartyRecords.Candidate;
import org.sangam.registry.party.PartyRecords.Household;
import org.sangam.registry.party.PartyRecords.Identifier;
import org.sangam.registry.party.PartyRecords.MatchResult;
import org.sangam.registry.party.PartyRecords.MergeEvent;
import org.sangam.registry.party.PartyRecords.Party;
import org.sangam.registry.party.PartyRecords.Relationship;
import org.sangam.registry.party.PartyRecords.Snapshot;
import org.sangam.registry.support.Db;
import org.sangam.registry.support.Json;
import org.sangam.registry.support.Maps;
import org.sangam.registry.support.RegistryAudit;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Party / beneficiary registry (N14): persons and organisations with configurable identifier types, relationships,
 * addresses, household roll-up, eligibility snapshots, and de-duplication (blocking, deterministic rules,
 * probabilistic scoring, an optional external matcher) with merge, exact unmerge and a full audit trail.
 *
 * <p>Privacy: sensitive identifiers are stored only as a keyed hash, a mask and a vault token
 * ({@link RegistryPorts.IdentifierVault}); views for a declared purpose are checked with the compliance module's
 * {@link RegistryPorts.ConsentPort} and masked by the {@link RegistryPorts.PrivacyPort}; audit payloads carry field
 * names and digests, never names or identifier values. Uniqueness of a {@code unique} identifier is concurrency-safe:
 * the claim takes the row lock of an audit chain rooted at the identifier's hash, so concurrent claims of the same
 * value serialise. De-duplication scores against all parties of the tenant (a duplicate hidden from the caller is
 * still a duplicate) but returns only parties inside the caller's visibility predicate (VI.4); a match across the
 * visibility boundary is queued as a cross-boundary REVIEW candidate that only a reviewer who sees both parties can
 * list or resolve. A unique identifier already held by a hidden party is stored as HELD_FOR_REVIEW and queued the
 * same way, without naming the holder.
 *
 * <p>Configuration: identifier types and the match configuration are seeded from pack configuration (default) and
 * may be overridden per tenant by administrators; overrides are stored in {@code REG_PARTY_CONFIG}.
 */
public final class PartyService {

    public static final String RESOURCE = "registry-party";
    private static final UUID ID_NS = UUID.fromString("3e9a7c51-0b2d-4f6e-8a1c-5d4b3a2f1e0c");
    private static final List<String> SENSITIVE_VIEW_FIELDS = List.of("birthDate", "gender", "identifiers", "addresses",
            "relationships", "attributes");
    private static final String KIND_PERSON = "PERSON";
    private static final String KIND_ORG = "ORGANISATION";

    /** Result of a create: the party, the duplicates found, and the survivor when it was auto-merged. */
    public record CreateResult(Party party, List<MatchResult> matches, String autoMergedInto) {
        public Map<String, Object> toMap() {
            return Maps.of("party", party.toMap(), "matches", matches.stream().map(MatchResult::toMap).toList(),
                    "autoMergedInto", autoMergedInto);
        }
    }

    private final Dialect dialect;
    private final RegistryAudit audit;
    private final RegistryPorts ports;
    private final IdentifierTypeCatalogue catalogue = new IdentifierTypeCatalogue();
    private final Map<String, MatchConfig> matchConfigs = new ConcurrentHashMap<>();
    private final Map<String, MatchConfig> savedMatch = new ConcurrentHashMap<>();
    private final Map<String, String> configStamps = new ConcurrentHashMap<>();

    public PartyService(Dialect dialect, RegistryAudit audit, RegistryPorts ports) {
        this.dialect = dialect;
        this.audit = audit;
        this.ports = ports;
    }

    // -------------------------------------------------------------------------------------------- configuration

    public IdentifierTypeCatalogue identifierTypes() {
        return catalogue;
    }

    /**
     * Seeds identifier types for a tenant ({@code *} = all tenants) from pack configuration (the default layer, loaded
     * at start-up; not stored). Types saved by administrators ({@link #saveIdentifierTypes}) win over the seed.
     */
    public List<IdentifierType> defineIdentifierTypes(String tenant, List<Map<String, Object>> definitions) {
        return catalogue.define(tenant, definitions);
    }

    /** Seeds the match configuration of a tenant ({@code *} = all) from pack configuration (default layer). */
    public MatchConfig defineMatchConfig(String tenant, Map<String, Object> config) {
        MatchConfig cfg = MatchConfig.from(config);
        matchConfigs.put(tenant, cfg);
        return cfg;
    }

    /** The identifier types in force for the caller's tenant (saved layer refreshed from the table). */
    public List<IdentifierType> identifierTypes(Connection c, String tenant) {
        refreshConfig(c, tenant);
        return catalogue.list(tenant);
    }

    /** Saves identifier types for the caller's tenant in {@code REG_PARTY_CONFIG} (survives restarts; audited). */
    public List<IdentifierType> saveIdentifierTypes(Connection c, RegistryContext ctx, List<Map<String, Object>> definitions) {
        List<IdentifierType> parsed = new ArrayList<>();
        for (Map<String, Object> d : definitions) {
            IdentifierType t = IdentifierType.from(d);
            parsed.add(t);
            saveConfigRow(c, ctx, CFG_ID_TYPE, t.typeKey(), "ACTIVE", d);
        }
        refreshConfig(c, ctx.tenant());
        return parsed;
    }

    /** Hides an identifier type for the caller's tenant (seeded or saved); existing identifiers stay readable. */
    public void retireIdentifierType(Connection c, RegistryContext ctx, String typeKey) {
        saveConfigRow(c, ctx, CFG_ID_TYPE, typeKey, "RETIRED", Maps.of("typeKey", typeKey));
        refreshConfig(c, ctx.tenant());
    }

    /** Saves the match configuration of the caller's tenant in {@code REG_PARTY_CONFIG} (survives restarts; audited). */
    public MatchConfig saveMatchConfig(Connection c, RegistryContext ctx, Map<String, Object> config) {
        MatchConfig cfg = MatchConfig.from(config);
        saveConfigRow(c, ctx, CFG_MATCH, "-", "ACTIVE", config);
        refreshConfig(c, ctx.tenant());
        return cfg;
    }

    /** The match configuration in force: saved for the tenant, else seeded for the tenant, else seeded for all. */
    public MatchConfig matchConfig(Connection c, String tenant) {
        refreshConfig(c, tenant);
        return matchConfig(tenant);
    }

    /** The match configuration from the layers already loaded (no table read). */
    public MatchConfig matchConfig(String tenant) {
        MatchConfig cfg = savedMatch.get(tenant);
        if (cfg == null) {
            cfg = matchConfigs.get(tenant);
        }
        if (cfg == null) {
            cfg = matchConfigs.get(IdentifierTypeCatalogue.ALL_TENANTS);
        }
        return cfg == null ? MatchConfig.defaults() : cfg;
    }

    private static final String CFG_ID_TYPE = "IDENTIFIER_TYPE";
    private static final String CFG_MATCH = "MATCH";

    private void saveConfigRow(Connection c, RegistryContext ctx, String kind, String key, String status,
                               Map<String, Object> definition) {
        Instant now = Db.now(c, dialect);
        String json = Json.write(definition);
        int n = Jdbc.update(c, "UPDATE REG_PARTY_CONFIG SET version=version+1, status=?, definition_json=?, updated_at=?, updated_by=?"
                + " WHERE tenant_id=? AND config_kind=? AND config_key=?", status, json, now, ctx.actor(), ctx.tenant(), kind, key);
        if (n == 0 && !Jdbc.insertIfAbsent(c, dialect, "INSERT INTO REG_PARTY_CONFIG(tenant_id, config_kind, config_key, version,"
                        + " status, definition_json, updated_at, updated_by) VALUES (?,?,?,?,?,?,?,?)",
                ctx.tenant(), kind, key, 1L, status, json, now, ctx.actor())) {
            throw RegistryException.conflict("party configuration " + kind + "/" + key + " was saved concurrently; retry");
        }
        audit.record(c, ctx, configRoot(ctx.tenant()), "PARTY_CONFIG_SAVED", Maps.of("kind", kind, "key", key,
                "status", status, "digest", Hashes.hex(Hashes.sha256(CanonicalJson.write(Json.canonical(definition))))));
    }

    /**
     * Reloads the tenant's saved layer when {@code REG_PARTY_CONFIG} changed (stamp = row count and version sum), so a
     * change saved on one node is seen by every node on its next call.
     */
    private void refreshConfig(Connection c, String tenant) {
        String stamp = Jdbc.one(c, "SELECT COUNT(*), SUM(version) FROM REG_PARTY_CONFIG WHERE tenant_id=?",
                rs -> rs.getLong(1) + ":" + rs.getLong(2), tenant).orElse("0:0");
        if (stamp.equals(configStamps.get(tenant))) {
            return;
        }
        Map<String, IdentifierType> types = new LinkedHashMap<>();
        Set<String> retired = new HashSet<>();
        MatchConfig[] match = new MatchConfig[1];
        Jdbc.query(c, "SELECT config_kind, config_key, status, definition_json FROM REG_PARTY_CONFIG WHERE tenant_id=?",
                rs -> {
                    String kind = rs.getString(1);
                    String key = rs.getString(2);
                    boolean active = "ACTIVE".equals(rs.getString(3));
                    Map<String, Object> def = Json.map(rs.getString(4));
                    if (CFG_ID_TYPE.equals(kind)) {
                        if (active) {
                            types.put(key, IdentifierType.from(def));
                        } else {
                            retired.add(key);
                        }
                    } else if (CFG_MATCH.equals(kind) && active) {
                        match[0] = MatchConfig.from(def);
                    }
                    return null;
                }, tenant);
        catalogue.replaceSaved(tenant, types, retired);
        if (match[0] == null) {
            savedMatch.remove(tenant);
        } else {
            savedMatch.put(tenant, match[0]);
        }
        configStamps.put(tenant, stamp);
    }

    private static String configRoot(String tenant) {
        return "partycfg:" + Hashes.uuidV5(ID_NS, "config|" + tenant);
    }

    // --------------------------------------------------------------------------------------------------- create

    /**
     * Creates a party. Request: {@code kind} (PERSON / ORGANISATION), {@code names} ({@code full}, {@code given},
     * {@code family}, {@code other}, {@code aliases}), {@code displayName}, {@code birthDate} (yyyy, yyyy-MM or
     * yyyy-MM-dd), {@code gender}, {@code attributes}, {@code source}, {@code sourcePriority},
     * {@code identifiers} [{type, value, issuer, validFrom, validTo, verified}], {@code addresses}
     * [{type, address, geometryRef, primary}], {@code match} (default true).
     */
    public CreateResult create(Connection c, RegistryContext ctx, Map<String, Object> req) {
        String kind = kind(Maps.str(req, "kind", KIND_PERSON));
        Map<String, Object> names = Maps.map(req, "names");
        if (names.isEmpty() && Maps.str(req, "name") != null) {
            names.put("full", Maps.str(req, "name"));
        }
        String display = displayName(kind, names, Maps.str(req, "displayName"));
        String birth = birthDate(Maps.str(req, "birthDate"));
        Instant now = Db.now(c, dialect);
        String id = Db.newId();
        Jdbc.update(c, "INSERT INTO REG_PARTY(id, tenant_id, party_kind, status, merged_into, display_name, name_json, birth_date,"
                        + " gender, attrs_json, source, source_priority, version, created_at, created_by, updated_at, updated_by)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, ctx.tenant(), kind, "ACTIVE", null, display, Json.write(names), birth, Maps.str(req, "gender"),
                Json.write(Maps.map(req, "attributes")), Maps.str(req, "source"), Maps.integer(req, "sourcePriority", 0),
                1L, now, ctx.actor(), now, ctx.actor());
        audit.record(c, ctx, id, "PARTY_CREATED", Maps.of("kind", kind, "source", Maps.str(req, "source"),
                "fields", fieldNames(req)));
        for (Map<String, Object> idSpec : Maps.maps(req, "identifiers")) {
            insertIdentifier(c, ctx, id, kind, idSpec, now);
        }
        for (Map<String, Object> a : Maps.maps(req, "addresses")) {
            insertAddress(c, ctx, id, a, now);
        }
        rebuildBlocks(c, ctx.tenant(), id);
        Party party = load(c, ctx.tenant(), id);
        List<MatchResult> matches = Maps.bool(req, "match", true) ? matchParty(c, ctx, id, true) : List.of();
        String mergedInto = null;
        MatchConfig cfg = matchConfig(c, ctx.tenant());
        if (cfg.autoMergeEnabled() && !matches.isEmpty() && "AUTO_MERGE".equals(matches.get(0).decision())) {
            MatchResult top = matches.get(0);
            String candId = candidateId(c, ctx.tenant(), id, top.partyId());
            merge(c, ctx, top.partyId(), id, "automatic merge (" + top.ruleKey() + ")", candId);
            mergedInto = top.partyId();
            party = load(c, ctx.tenant(), id);
        }
        return new CreateResult(party, matches, mergedInto);
    }

    /** Attribute update (patch) with optimistic concurrency: names, displayName, birthDate, gender, attributes, source. */
    public Party update(Connection c, RegistryContext ctx, String id, Long expectedVersion, Map<String, Object> patch) {
        Party p = lockParty(c, ctx, id);
        checkVersion(p, expectedVersion);
        requireStatus(p, "ACTIVE", "INACTIVE");
        Map<String, Object> names = patch.containsKey("names") ? Maps.map(patch, "names") : p.names();
        String birth = patch.containsKey("birthDate") ? birthDate(Maps.str(patch, "birthDate")) : p.birthDate();
        String gender = patch.containsKey("gender") ? Maps.str(patch, "gender") : p.gender();
        Map<String, Object> attrs = new LinkedHashMap<>(p.attrs());
        if (patch.containsKey("attributes")) {
            Maps.map(patch, "attributes").forEach((k, v) -> {
                if (v == null) {
                    attrs.remove(k);
                } else {
                    attrs.put(k, v);
                }
            });
        }
        String display = patch.containsKey("displayName") || patch.containsKey("names")
                ? displayName(p.kind(), names, Maps.str(patch, "displayName")) : p.displayName();
        Party after = new Party(p.id(), p.tenant(), p.kind(), p.status(), p.mergedInto(), display, names, birth, gender,
                attrs, patch.containsKey("source") ? Maps.str(patch, "source") : p.source(),
                patch.containsKey("sourcePriority") ? Maps.integer(patch, "sourcePriority", 0) : p.sourcePriority(),
                p.version(), p.createdAt(), p.createdBy(), p.updatedAt(), p.updatedBy());
        save(c, ctx, after, p.version());
        audit.record(c, ctx, id, "PARTY_UPDATED", Maps.of("version", p.version() + 1, "fields", fieldNames(patch)));
        rebuildBlocks(c, ctx.tenant(), id);
        return load(c, ctx.tenant(), id);
    }

    public Party deactivate(Connection c, RegistryContext ctx, String id, String reason) {
        return setStatus(c, ctx, id, "INACTIVE", reason, "ACTIVE");
    }

    public Party reactivate(Connection c, RegistryContext ctx, String id, String reason) {
        return setStatus(c, ctx, id, "ACTIVE", reason, "INACTIVE");
    }

    private Party setStatus(Connection c, RegistryContext ctx, String id, String to, String reason, String from) {
        if (reason == null || reason.isBlank()) {
            throw RegistryException.invalid("a reason is required");
        }
        Party p = lockParty(c, ctx, id);
        requireStatus(p, from);
        save(c, ctx, withStatus(p, to, p.mergedInto()), p.version());
        audit.record(c, ctx, id, "PARTY_" + to, Maps.of("reason", reason, "version", p.version() + 1));
        return load(c, ctx.tenant(), id);
    }

    // ---------------------------------------------------------------------------------------------------- reads

    /** The party row (visibility applied, no consent check); use {@link #view} for disclosure. */
    public Party get(Connection c, RegistryContext ctx, String id) {
        SqlPredicate vis = ports.visibility().visible(ctx, RESOURCE, "p");
        List<Object> params = new ArrayList<>(List.of(id, ctx.tenant()));
        params.addAll(vis.params());
        return Jdbc.one(c, "SELECT " + Party.COLUMNS + " FROM REG_PARTY p WHERE p.id=? AND p.tenant_id=? AND (" + vis.sql() + ")",
                Party::map, params.toArray()).orElseThrow(() -> RegistryException.notFound("party", id));
    }

    /**
     * The disclosed view of a party with identifiers (masked), addresses and relationships: consent-checked for the
     * context's purpose and masked by the privacy port.
     */
    public Map<String, Object> view(Connection c, RegistryContext ctx, String id) {
        Party p = get(c, ctx, id);
        consent(ctx, id, "READ");
        Map<String, Object> v = new LinkedHashMap<>(p.toMap());
        v.put("identifiers", identifiers(c, ctx.tenant(), id, true).stream().map(this::publicIdentifier).toList());
        v.put("addresses", addressesOf(c, ctx.tenant(), id, true).stream().map(Address::toMap).toList());
        v.put("relationships", relationshipsOf(c, ctx.tenant(), id, true).stream().map(Relationship::toMap).toList());
        return ports.privacy().mask(ctx, RESOURCE, v, SENSITIVE_VIEW_FIELDS);
    }

    /** Follows merged-into links to the surviving golden record. */
    public String resolve(Connection c, RegistryContext ctx, String id) {
        String cur = id;
        Set<String> seen = new HashSet<>();
        while (true) {
            if (!seen.add(cur)) {
                throw RegistryException.state("merge cycle at party " + cur);
            }
            String into = get(c, ctx, cur).mergedInto();
            if (into == null) {
                return cur;
            }
            cur = into;
        }
    }

    /** Lists parties; filters are optional; an identifier filter matches by keyed hash. */
    public List<Party> list(Connection c, RegistryContext ctx, String kind, String status, String nameContains,
                            String idType, String idValue, int page, int size) {
        StringBuilder where = new StringBuilder("p.tenant_id=?");
        List<Object> params = new ArrayList<>(List.of(ctx.tenant()));
        if (kind != null) {
            where.append(" AND p.party_kind=?");
            params.add(kind(kind));
        }
        if (status != null) {
            where.append(" AND p.status=?");
            params.add(status.toUpperCase());
        }
        if (nameContains != null && !nameContains.isBlank()) {
            where.append(" AND LOWER(p.display_name) LIKE ?");
            params.add("%" + nameContains.toLowerCase().replace("%", "").replace("_", "") + "%");
        }
        if (idType != null && idValue != null) {
            refreshConfig(c, ctx.tenant());
            IdentifierType t = catalogue.get(ctx.tenant(), idType);
            String hash = ports.vault().protect(ctx.tenant(), idType, t.normaliseValue(idValue), false).hashHex();
            where.append(" AND EXISTS (SELECT 1 FROM REG_PARTY_IDENTIFIER i WHERE i.tenant_id=p.tenant_id AND i.party_id=p.id"
                    + " AND i.id_type=? AND i.value_hash=? AND i.status='ACTIVE')");
            params.add(idType);
            params.add(hash);
        }
        return Db.page(c, dialect, "SELECT " + Party.COLUMNS + " FROM REG_PARTY p", where.toString(), params,
                ports.visibility().visible(ctx, RESOURCE, "p"), "p.display_name, p.id", page, size, Party::map);
    }

    public AuditChain.VerifyResult verifyAudit(Connection c, RegistryContext ctx, String id) {
        get(c, ctx, id);
        return audit.verify(c, ctx.tenant(), id);
    }

    // ---------------------------------------------------------------------------------------------- identifiers

    public Identifier addIdentifier(Connection c, RegistryContext ctx, String partyId, Map<String, Object> spec) {
        Party p = lockParty(c, ctx, partyId);
        requireStatus(p, "ACTIVE", "INACTIVE");
        Identifier i = insertIdentifier(c, ctx, partyId, p.kind(), spec, Db.now(c, dialect));
        rebuildBlocks(c, ctx.tenant(), partyId);
        return i;
    }

    public Identifier retireIdentifier(Connection c, RegistryContext ctx, String identifierId, String reason) {
        Identifier i = identifier(c, ctx.tenant(), identifierId);
        lockParty(c, ctx, i.partyId());
        if (!"ACTIVE".equals(i.status())) {
            throw RegistryException.state("identifier " + identifierId + " is " + i.status());
        }
        Jdbc.update(c, "UPDATE REG_PARTY_IDENTIFIER SET status='RETIRED', valid_to=? WHERE id=? AND tenant_id=?",
                Db.now(c, dialect), identifierId, ctx.tenant());
        audit.record(c, ctx, i.partyId(), "PARTY_IDENTIFIER_RETIRED", Maps.of("identifierId", identifierId,
                "type", i.idType(), "reason", reason));
        rebuildBlocks(c, ctx.tenant(), i.partyId());
        return identifier(c, ctx.tenant(), identifierId);
    }

    public List<Map<String, Object>> listIdentifiers(Connection c, RegistryContext ctx, String partyId) {
        get(c, ctx, partyId);
        consent(ctx, partyId, "READ");
        return identifiers(c, ctx.tenant(), partyId, false).stream().map(this::publicIdentifier).toList();
    }

    /** The clear value of a vaulted identifier, for a declared purpose; consent-checked and audited. */
    public String revealIdentifier(Connection c, RegistryContext ctx, String identifierId) {
        if (ctx.purpose() == null || ctx.purpose().isBlank()) {
            throw RegistryException.invalid("revealing an identifier requires a declared purpose");
        }
        Identifier i = identifier(c, ctx.tenant(), identifierId);
        get(c, ctx, i.partyId());
        consent(ctx, i.partyId(), "REVEAL_IDENTIFIER");
        String value = i.vaultRef() == null ? i.value()
                : ports.vault().reveal(ctx.tenant(), i.vaultRef(), ctx.purpose())
                .orElseThrow(() -> RegistryException.notFound("vault token", i.vaultRef()));
        audit.record(c, ctx, i.partyId(), "PARTY_IDENTIFIER_REVEALED", Maps.of("identifierId", identifierId, "type", i.idType()));
        return value;
    }

    private Identifier insertIdentifier(Connection c, RegistryContext ctx, String partyId, String partyKind,
                                        Map<String, Object> spec, Instant now) {
        String typeKey = Maps.required(spec, "type");
        refreshConfig(c, ctx.tenant());
            IdentifierType t = catalogue.get(ctx.tenant(), typeKey);
        if (!t.appliesTo(partyKind)) {
            throw RegistryException.invalid("identifier type " + typeKey + " does not apply to " + partyKind);
        }
        String normalised = t.validate(Maps.required(spec, "value"));
        RegistryPorts.ProtectedValue pv = ports.vault().protect(ctx.tenant(), typeKey, normalised, t.sensitive());
        if (t.unique()) {
            // serialises concurrent claims of the same value on the ROOT_SEQ row lock of this chain
            audit.record(c, ctx, identifierRoot(ctx.tenant(), typeKey, pv.hashHex()), "PARTY_IDENTIFIER_CLAIMED",
                    Maps.of("type", typeKey, "partyId", partyId));
        }
        List<String> holders = Jdbc.query(c, "SELECT i.party_id FROM REG_PARTY_IDENTIFIER i JOIN REG_PARTY p ON p.id=i.party_id"
                        + " WHERE i.tenant_id=? AND i.id_type=? AND i.value_hash=? AND i.status='ACTIVE' AND p.status<>'MERGED'",
                rs -> rs.getString(1), ctx.tenant(), typeKey, pv.hashHex());
        if (holders.contains(partyId)) {
            throw RegistryException.conflict("party " + partyId + " already has this " + typeKey);
        }
        String status = "ACTIVE";
        if (t.unique() && !holders.isEmpty()) {
            Set<String> visible = visibleIds(c, ctx, holders);
            if (!visible.isEmpty()) {
                throw RegistryException.conflict(typeKey + " is already recorded for party " + visible.iterator().next());
            }
            // held by a party the caller cannot see: do not reveal it; hold this identifier and queue the pair for a
            // reviewer who can see both parties
            status = "HELD_FOR_REVIEW";
            upsertCandidate(c, ctx, partyId, new MatchResult(holders.get(0), 1.0, "REVIEW", "unique-identifier",
                    Maps.of("identifierType", typeKey)), true);
        }
        String id = Db.newId();
        Jdbc.update(c, "INSERT INTO REG_PARTY_IDENTIFIER(id, tenant_id, party_id, id_type, value_normalized, value_hash,"
                        + " value_masked, vault_ref, issuer, valid_from, valid_to, verified_flag, status, moved_from_party, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, ctx.tenant(), partyId, typeKey, t.sensitive() ? null : normalised, pv.hashHex(), pv.masked(),
                pv.vaultRef(), Maps.str(spec, "issuer"), Maps.instant(spec, "validFrom"), Maps.instant(spec, "validTo"),
                Maps.bool(spec, "verified", false) ? 1 : 0, status, null, now);
        audit.record(c, ctx, partyId, "PARTY_IDENTIFIER_ADDED", Maps.of("identifierId", id, "type", typeKey,
                "sensitive", t.sensitive(), "verified", Maps.bool(spec, "verified", false)));
        return identifier(c, ctx.tenant(), id);
    }

    private Map<String, Object> publicIdentifier(Identifier i) {
        Map<String, Object> m = i.toMap();
        if (i.vaultRef() != null) {
            m.remove("value");
        }
        return m;
    }

    private Identifier identifier(Connection c, String tenant, String id) {
        return Jdbc.one(c, "SELECT " + Identifier.COLUMNS + " FROM REG_PARTY_IDENTIFIER i WHERE i.id=? AND i.tenant_id=?",
                Identifier::map, id, tenant).orElseThrow(() -> RegistryException.notFound("identifier", id));
    }

    private List<Identifier> identifiers(Connection c, String tenant, String partyId, boolean activeOnly) {
        return Jdbc.query(c, "SELECT " + Identifier.COLUMNS + " FROM REG_PARTY_IDENTIFIER i WHERE i.tenant_id=? AND i.party_id=?"
                + (activeOnly ? " AND i.status='ACTIVE'" : "") + " ORDER BY i.created_at, i.id", Identifier::map, tenant, partyId);
    }

    static String identifierRoot(String tenant, String type, String hash) {
        return "idv:" + Hashes.uuidV5(ID_NS, tenant + "|" + type + "|" + hash);
    }

    // ------------------------------------------------------------------------------------ relationships / addresses

    public Relationship addRelationship(Connection c, RegistryContext ctx, String fromParty, String toParty, String type,
                                        Map<String, Object> attrs, Instant validFrom, Instant validTo) {
        if (type == null || !type.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw RegistryException.invalid("relationship type must be 1..64 characters of [A-Za-z0-9._:-]");
        }
        if (Objects.equals(fromParty, toParty)) {
            throw RegistryException.invalid("a party cannot be related to itself");
        }
        requireStatus(get(c, ctx, fromParty), "ACTIVE", "INACTIVE");
        requireStatus(get(c, ctx, toParty), "ACTIVE", "INACTIVE");
        int dup = Jdbc.one(c, "SELECT COUNT(*) FROM REG_PARTY_RELATIONSHIP WHERE tenant_id=? AND from_party=? AND to_party=?"
                + " AND rel_type=? AND status='ACTIVE'", rs -> rs.getInt(1), ctx.tenant(), fromParty, toParty, type).orElse(0);
        if (dup > 0) {
            throw RegistryException.conflict("relationship " + type + " already recorded");
        }
        String id = Db.newId();
        Jdbc.update(c, "INSERT INTO REG_PARTY_RELATIONSHIP(id, tenant_id, from_party, to_party, rel_type, status, valid_from,"
                        + " valid_to, attrs_json, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, ctx.tenant(), fromParty, toParty, type, "ACTIVE", validFrom, validTo, Json.write(attrs == null ? Map.of() : attrs),
                Db.now(c, dialect));
        Map<String, Object> facts = Maps.of("relationshipId", id, "type", type, "fromParty", fromParty, "toParty", toParty);
        audit.record(c, ctx, fromParty, "PARTY_RELATIONSHIP_ADDED", facts);
        audit.record(c, ctx, toParty, "PARTY_RELATIONSHIP_ADDED", facts);
        return relationship(c, ctx.tenant(), id);
    }

    public Relationship endRelationship(Connection c, RegistryContext ctx, String relationshipId, String reason) {
        Relationship r = relationship(c, ctx.tenant(), relationshipId);
        get(c, ctx, r.fromParty());
        if (!"ACTIVE".equals(r.status())) {
            throw RegistryException.state("relationship " + relationshipId + " is " + r.status());
        }
        Jdbc.update(c, "UPDATE REG_PARTY_RELATIONSHIP SET status='ENDED', valid_to=? WHERE id=? AND tenant_id=?",
                Db.now(c, dialect), relationshipId, ctx.tenant());
        Map<String, Object> facts = Maps.of("relationshipId", relationshipId, "reason", reason);
        audit.record(c, ctx, r.fromParty(), "PARTY_RELATIONSHIP_ENDED", facts);
        audit.record(c, ctx, r.toParty(), "PARTY_RELATIONSHIP_ENDED", facts);
        return relationship(c, ctx.tenant(), relationshipId);
    }

    public List<Relationship> relationships(Connection c, RegistryContext ctx, String partyId) {
        get(c, ctx, partyId);
        consent(ctx, partyId, "READ");
        return relationshipsOf(c, ctx.tenant(), partyId, false);
    }

    private Relationship relationship(Connection c, String tenant, String id) {
        return Jdbc.one(c, "SELECT " + Relationship.COLUMNS + " FROM REG_PARTY_RELATIONSHIP r WHERE r.id=? AND r.tenant_id=?",
                Relationship::map, id, tenant).orElseThrow(() -> RegistryException.notFound("relationship", id));
    }

    private List<Relationship> relationshipsOf(Connection c, String tenant, String partyId, boolean activeOnly) {
        return Jdbc.query(c, "SELECT " + Relationship.COLUMNS + " FROM REG_PARTY_RELATIONSHIP r WHERE r.tenant_id=?"
                        + " AND (r.from_party=? OR r.to_party=?)" + (activeOnly ? " AND r.status='ACTIVE'" : "")
                        + " ORDER BY r.created_at, r.id", Relationship::map, tenant, partyId, partyId);
    }

    public Address addAddress(Connection c, RegistryContext ctx, String partyId, Map<String, Object> spec) {
        Party p = lockParty(c, ctx, partyId);
        requireStatus(p, "ACTIVE", "INACTIVE");
        return insertAddress(c, ctx, partyId, spec, Db.now(c, dialect));
    }

    public Address retireAddress(Connection c, RegistryContext ctx, String addressId, String reason) {
        Address a = address(c, ctx.tenant(), addressId);
        lockParty(c, ctx, a.partyId());
        Jdbc.update(c, "UPDATE REG_PARTY_ADDRESS SET status='RETIRED', primary_flag=0, valid_to=? WHERE id=? AND tenant_id=?",
                Db.now(c, dialect), addressId, ctx.tenant());
        audit.record(c, ctx, a.partyId(), "PARTY_ADDRESS_RETIRED", Maps.of("addressId", addressId, "reason", reason));
        return address(c, ctx.tenant(), addressId);
    }

    public List<Address> addresses(Connection c, RegistryContext ctx, String partyId) {
        get(c, ctx, partyId);
        consent(ctx, partyId, "READ");
        return addressesOf(c, ctx.tenant(), partyId, false);
    }

    private Address insertAddress(Connection c, RegistryContext ctx, String partyId, Map<String, Object> spec, Instant now) {
        Map<String, Object> addr = Maps.map(spec, "address");
        if (addr.isEmpty()) {
            throw RegistryException.invalid("address content is required");
        }
        boolean primary = Maps.bool(spec, "primary", false);
        if (primary) {
            Jdbc.update(c, "UPDATE REG_PARTY_ADDRESS SET primary_flag=0 WHERE tenant_id=? AND party_id=?", ctx.tenant(), partyId);
        }
        String id = Db.newId();
        Jdbc.update(c, "INSERT INTO REG_PARTY_ADDRESS(id, tenant_id, party_id, address_type, address_json, geometry_ref,"
                        + " primary_flag, valid_from, valid_to, status, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, ctx.tenant(), partyId, Maps.str(spec, "type", "GENERAL"), Json.write(addr), Maps.str(spec, "geometryRef"),
                primary ? 1 : 0, Maps.instant(spec, "validFrom"), Maps.instant(spec, "validTo"), "ACTIVE", now);
        audit.record(c, ctx, partyId, "PARTY_ADDRESS_ADDED", Maps.of("addressId", id, "type", Maps.str(spec, "type", "GENERAL"),
                "primary", primary, "digest", digestHex(addr)));
        return address(c, ctx.tenant(), id);
    }

    private Address address(Connection c, String tenant, String id) {
        return Jdbc.one(c, "SELECT " + Address.COLUMNS + " FROM REG_PARTY_ADDRESS a WHERE a.id=? AND a.tenant_id=?",
                Address::map, id, tenant).orElseThrow(() -> RegistryException.notFound("address", id));
    }

    private List<Address> addressesOf(Connection c, String tenant, String partyId, boolean activeOnly) {
        return Jdbc.query(c, "SELECT " + Address.COLUMNS + " FROM REG_PARTY_ADDRESS a WHERE a.tenant_id=? AND a.party_id=?"
                + (activeOnly ? " AND a.status='ACTIVE'" : "") + " ORDER BY a.created_at, a.id", Address::map, tenant, partyId);
    }

    // ---------------------------------------------------------------------------------------- household / snapshot

    /**
     * Household (or any group) roll-up: parties reachable through ACTIVE, currently valid relationships of the given
     * types (null or empty = any type), in both directions, up to {@code depth} hops (1..5). The root is member 0.
     */
    public Household household(Connection c, RegistryContext ctx, String partyId, List<String> types, int depth) {
        Party root = get(c, ctx, partyId);
        consent(ctx, partyId, "READ");
        int max = Math.max(1, Math.min(depth <= 0 ? 1 : depth, 5));
        Instant now = Db.now(c, dialect);
        List<Map<String, Object>> members = new ArrayList<>();
        members.add(Maps.of("partyId", root.id(), "displayName", root.displayName(), "depth", 0, "relation", "SELF"));
        Set<String> seen = new HashSet<>(Set.of(root.id()));
        Deque<String[]> frontier = new ArrayDeque<>();
        frontier.add(new String[]{root.id(), "0"});
        while (!frontier.isEmpty()) {
            String[] cur = frontier.poll();
            int d = Integer.parseInt(cur[1]);
            if (d >= max) {
                continue;
            }
            for (Relationship r : relationshipsOf(c, ctx.tenant(), cur[0], true)) {
                if (types != null && !types.isEmpty() && !types.contains(r.type())) {
                    continue;
                }
                if (r.validTo() != null && !r.validTo().isAfter(now) || r.validFrom() != null && r.validFrom().isAfter(now)) {
                    continue;
                }
                String other = r.fromParty().equals(cur[0]) ? r.toParty() : r.fromParty();
                if (!seen.add(other)) {
                    continue;
                }
                Party op = load(c, ctx.tenant(), other);
                if ("MERGED".equals(op.status())) {
                    continue;
                }
                members.add(Maps.of("partyId", other, "displayName", op.displayName(), "depth", d + 1,
                        "relation", r.type(), "direction", r.fromParty().equals(cur[0]) ? "OUT" : "IN", "via", cur[0],
                        "kind", op.kind(), "birthDate", op.birthDate(), "gender", op.gender()));
                frontier.add(new String[]{other, Integer.toString(d + 1)});
            }
        }
        Map<String, Object> masked = ports.privacy().mask(ctx, RESOURCE, Maps.of("members", members), SENSITIVE_VIEW_FIELDS);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out = masked.get("members") instanceof List<?> l ? (List<Map<String, Object>>) l : members;
        return new Household(root.id(), out, out.size());
    }

    /**
     * Eligibility snapshot (VI.4 rule 5): freezes the party, its active identifiers (masked), addresses and household
     * for a reference (a sanction, application or decision id), with a SHA-256 digest of the canonical JSON, so a
     * later change to the party does not alter an approved decision.
     */
    public Snapshot snapshot(Connection c, RegistryContext ctx, String partyId, String reference, Map<String, Object> extra) {
        if (reference == null || reference.isBlank()) {
            throw RegistryException.invalid("a snapshot needs a reference");
        }
        Party p = get(c, ctx, partyId);
        consent(ctx, partyId, "SNAPSHOT");
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("party", p.toMap());
        content.put("identifiers", identifiers(c, ctx.tenant(), partyId, true).stream()
                .map(i -> Maps.of("type", i.idType(), "masked", i.masked(), "verified", i.verified(), "hash", i.valueHash())).toList());
        content.put("addresses", addressesOf(c, ctx.tenant(), partyId, true).stream().map(Address::toMap).toList());
        content.put("household", household(c, ctx, partyId, null, 1).members());
        if (extra != null && !extra.isEmpty()) {
            content.put("extra", extra);
        }
        byte[] digest = Hashes.sha256(CanonicalJson.write(Json.canonical(content)));
        Instant now = Db.now(c, dialect);
        String id = Db.newId();
        Jdbc.update(c, "INSERT INTO REG_PARTY_SNAPSHOT(id, tenant_id, party_id, reference, purpose, snapshot_json, digest,"
                        + " taken_at, taken_by) VALUES (?,?,?,?,?,?,?,?,?)",
                id, ctx.tenant(), partyId, reference, ctx.purpose(), Json.write(content), digest, now, ctx.actor());
        audit.record(c, ctx, partyId, "PARTY_SNAPSHOT_TAKEN", Maps.of("snapshotId", id, "reference", reference,
                "digest", Hashes.hex(digest)));
        return getSnapshot(c, ctx, id);
    }

    public Snapshot getSnapshot(Connection c, RegistryContext ctx, String snapshotId) {
        return Jdbc.one(c, "SELECT id, party_id, reference, purpose, snapshot_json, digest, taken_at, taken_by FROM REG_PARTY_SNAPSHOT"
                        + " WHERE id=? AND tenant_id=?", rs -> new Snapshot(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), Json.map(rs.getString(5)), Hashes.hex(rs.getBytes(6)), Jdbc.instant(rs, "taken_at"),
                        rs.getString(8)), snapshotId, ctx.tenant())
                .orElseThrow(() -> RegistryException.notFound("snapshot", snapshotId));
    }

    public List<Snapshot> snapshots(Connection c, RegistryContext ctx, String reference, String partyId) {
        if (reference == null && partyId == null) {
            throw RegistryException.invalid("give a reference or a party id");
        }
        StringBuilder sql = new StringBuilder("SELECT id, party_id, reference, purpose, snapshot_json, digest, taken_at, taken_by"
                + " FROM REG_PARTY_SNAPSHOT WHERE tenant_id=?");
        List<Object> params = new ArrayList<>(List.of(ctx.tenant()));
        if (reference != null) {
            sql.append(" AND reference=?");
            params.add(reference);
        }
        if (partyId != null) {
            sql.append(" AND party_id=?");
            params.add(partyId);
        }
        sql.append(" ORDER BY taken_at, id");
        return Jdbc.query(c, sql.toString(), rs -> new Snapshot(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), Json.map(rs.getString(5)), Hashes.hex(rs.getBytes(6)), Jdbc.instant(rs, "taken_at"),
                rs.getString(8)), params.toArray());
    }

    /** True when the stored snapshot content still matches its digest. */
    public boolean verifySnapshot(Connection c, RegistryContext ctx, String snapshotId) {
        Snapshot s = getSnapshot(c, ctx, snapshotId);
        return Hashes.hex(Hashes.sha256(CanonicalJson.write(Json.canonical(s.content())))).equals(s.digestHex());
    }

    // ------------------------------------------------------------------------------------------------ matching

    /**
     * Finds possible duplicates of a probe (not stored): same shape as a create request. Returns REVIEW and
     * AUTO_MERGE results, best first.
     */
    public List<MatchResult> findMatches(Connection c, RegistryContext ctx, Map<String, Object> probeSpec) {
        String kind = kind(Maps.str(probeSpec, "kind", KIND_PERSON));
        Map<String, Object> names = Maps.map(probeSpec, "names");
        if (names.isEmpty() && Maps.str(probeSpec, "name") != null) {
            names.put("full", Maps.str(probeSpec, "name"));
        }
        Map<String, Set<String>> hashes = new LinkedHashMap<>();
        for (Map<String, Object> idSpec : Maps.maps(probeSpec, "identifiers")) {
            String type = Maps.required(idSpec, "type");
            refreshConfig(c, ctx.tenant());
            IdentifierType t = catalogue.get(ctx.tenant(), type);
            String v = t.normaliseValue(Maps.required(idSpec, "value"));
            hashes.computeIfAbsent(type, k -> new java.util.TreeSet<>())
                    .add(ports.vault().protect(ctx.tenant(), type, v, false).hashHex());
        }
        PartyMatcher.Probe probe = new PartyMatcher.Probe(null, kind,
                PartyMatcher.fullName(names, Maps.str(probeSpec, "displayName")), Maps.str(names, "given"),
                Maps.str(names, "family"), birthDate(Maps.str(probeSpec, "birthDate")), Maps.str(probeSpec, "gender"),
                Maps.map(probeSpec, "attributes"), hashes);
        // an unstored probe cannot be queued; matches the caller cannot see are dropped (they are queued for review
        // when the party is created, see matchParty)
        return score(c, ctx, probe, null).visible();
    }

    /**
     * Matches an existing party against the registry; with {@code record}, stores the candidate pairs. Only parties
     * the caller can see (the security module's visibility predicate) are returned. A match with a party outside the
     * caller's visibility is recorded, when {@code record} is set, as a cross-boundary REVIEW candidate (never an
     * automatic merge) for a reviewer who can see both parties; nothing about it is returned to the caller.
     */
    public List<MatchResult> matchParty(Connection c, RegistryContext ctx, String partyId, boolean record) {
        Party p = get(c, ctx, partyId);
        PartyMatcher.Probe probe = PartyMatcher.probe(p, identifiers(c, ctx.tenant(), partyId, true));
        Scored scored = score(c, ctx, probe, partyId);
        if (record) {
            for (MatchResult r : scored.visible()) {
                upsertCandidate(c, ctx, partyId, r, false);
            }
            for (MatchResult r : scored.hidden()) {
                upsertCandidate(c, ctx, partyId, r, true);
            }
        }
        return scored.visible();
    }

    /** Scored candidates split by the caller's visibility. */
    private record Scored(List<MatchResult> visible, List<MatchResult> hidden) {
    }

    /** Ids among {@code ids} that the caller may see. */
    private Set<String> visibleIds(Connection c, RegistryContext ctx, Collection<String> ids) {
        Set<String> out = new HashSet<>();
        if (ids.isEmpty()) {
            return out;
        }
        SqlPredicate vis = ports.visibility().visible(ctx, RESOURCE, "p");
        List<String> all = new ArrayList<>(ids);
        for (int i = 0; i < all.size(); i += 500) {
            List<String> chunk = all.subList(i, Math.min(all.size(), i + 500));
            List<Object> params = new ArrayList<>(List.of(ctx.tenant()));
            params.addAll(chunk);
            params.addAll(vis.params());
            out.addAll(Jdbc.query(c, "SELECT p.id FROM REG_PARTY p WHERE p.tenant_id=? AND p.id IN ("
                    + String.join(",", java.util.Collections.nCopies(chunk.size(), "?")) + ") AND (" + vis.sql() + ")",
                    rs -> rs.getString(1), params.toArray()));
        }
        return out;
    }

    private Scored score(Connection c, RegistryContext ctx, PartyMatcher.Probe probe, String excludeId) {
        Scored all = scoreAll(c, ctx, probe, excludeId);
        Set<String> visible = visibleIds(c, ctx, all.visible().stream().map(MatchResult::partyId).toList());
        List<MatchResult> v = new ArrayList<>();
        List<MatchResult> h = new ArrayList<>();
        for (MatchResult r : all.visible()) {
            (visible.contains(r.partyId()) ? v : h).add(r);
        }
        return new Scored(v, h);
    }

    /** Scores against every candidate of the tenant (not filtered); the caller splits by visibility. */
    private Scored scoreAll(Connection c, RegistryContext ctx, PartyMatcher.Probe probe, String excludeId) {
        MatchConfig cfg = matchConfig(c, ctx.tenant());
        Set<String> keys = PartyMatcher.blockingKeys(probe);
        if (keys.isEmpty()) {
            return new Scored(List.of(), List.of());
        }
        List<Object> params = new ArrayList<>(List.of(ctx.tenant(), probe.kind()));
        params.addAll(keys);
        String where = "b.tenant_id=? AND p.party_kind=? AND p.status='ACTIVE' AND b.block_key IN ("
                + String.join(",", java.util.Collections.nCopies(keys.size(), "?")) + ")";
        if (excludeId != null) {
            where += " AND b.party_id<>?";
            params.add(excludeId);
        }
        List<String> ids = Db.page(c, dialect, "SELECT DISTINCT b.party_id FROM REG_PARTY_BLOCK b JOIN REG_PARTY p"
                + " ON p.id=b.party_id AND p.tenant_id=b.tenant_id", where, params, null, "b.party_id", 0,
                cfg.maxCandidates(), rs -> rs.getString(1));
        List<PartyMatcher.Probe> others = new ArrayList<>();
        for (String id : ids) {
            others.add(PartyMatcher.probe(load(c, ctx.tenant(), id), identifiers(c, ctx.tenant(), id, true)));
        }
        Map<String, Double> external = new HashMap<>();
        if (cfg.externalWeight() != 0 && !others.isEmpty()) {
            for (RegistryPorts.ExternalScore s : ports.matcher().score(ctx, probe.toMap(),
                    others.stream().map(PartyMatcher.Probe::toMap).toList())) {
                external.put(s.candidatePartyId(), s.score());
            }
        }
        List<MatchResult> out = new ArrayList<>();
        for (PartyMatcher.Probe o : others) {
            MatchResult r = PartyMatcher.score(probe, o, cfg, catalogue, ctx.tenant());
            Double ext = external.get(o.partyId());
            if (ext != null) {
                r = PartyMatcher.withExternal(r, ext, cfg);
            }
            if (!"NON_MATCH".equals(r.decision())) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingDouble(MatchResult::score).reversed());
        return new Scored(out, List.of());
    }

    /**
     * Records a candidate pair. A cross-boundary pair (the other party is outside the caller's visibility) is always
     * a REVIEW candidate flagged {@code crossBoundary}, so it is never merged automatically.
     */
    private String upsertCandidate(Connection c, RegistryContext ctx, String partyId, MatchResult found, boolean crossBoundary) {
        MatchResult r = found;
        if (crossBoundary) {
            Map<String, Object> d = new LinkedHashMap<>(found.details() == null ? Map.of() : found.details());
            d.put("crossBoundary", true);
            r = new MatchResult(found.partyId(), found.score(), "REVIEW", found.ruleKey(), d);
        }
        String a = partyId.compareTo(r.partyId()) < 0 ? partyId : r.partyId();
        String b = a.equals(partyId) ? r.partyId() : partyId;
        Instant now = Db.now(c, dialect);
        boolean inserted = Jdbc.insertIfAbsent(c, dialect, "INSERT INTO REG_MATCH_CANDIDATE(id, tenant_id, party_a, party_b, score,"
                        + " decision, rule_key, details_json, status, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                Db.newId(), ctx.tenant(), a, b, BigDecimal.valueOf(r.score()), r.decision(), r.ruleKey(), Json.write(r.details()),
                "OPEN", now);
        if (!inserted) {
            Jdbc.update(c, "UPDATE REG_MATCH_CANDIDATE SET score=?, decision=?, rule_key=?, details_json=? WHERE tenant_id=?"
                            + " AND party_a=? AND party_b=? AND status='OPEN'", BigDecimal.valueOf(r.score()), r.decision(),
                    r.ruleKey(), Json.write(r.details()), ctx.tenant(), a, b);
        } else {
            Map<String, Object> facts = Maps.of("partyA", a, "partyB", b, "score", Double.toString(r.score()),
                    "decision", r.decision(), "rule", r.ruleKey());
            audit.record(c, ctx, a, "PARTY_MATCH_CANDIDATE", facts);
            audit.record(c, ctx, b, "PARTY_MATCH_CANDIDATE", facts);
        }
        return candidateId(c, ctx.tenant(), a, b);
    }

    private String candidateId(Connection c, String tenant, String x, String y) {
        String a = x.compareTo(y) < 0 ? x : y;
        String b = a.equals(x) ? y : x;
        return Jdbc.one(c, "SELECT id FROM REG_MATCH_CANDIDATE WHERE tenant_id=? AND party_a=? AND party_b=?",
                rs -> rs.getString(1), tenant, a, b).orElse(null);
    }

    /**
     * The review queue (status OPEN by default). Only pairs whose two parties are both visible to the caller are
     * listed, so cross-boundary candidates reach only a reviewer who can see both sides.
     */
    public List<Candidate> candidates(Connection c, RegistryContext ctx, String status, String partyId, int page, int size) {
        SqlPredicate va = ports.visibility().visible(ctx, RESOURCE, "pa");
        SqlPredicate vb = ports.visibility().visible(ctx, RESOURCE, "pb");
        StringBuilder where = new StringBuilder("m.tenant_id=? AND m.status=?"
                + " AND EXISTS (SELECT 1 FROM REG_PARTY pa WHERE pa.tenant_id=m.tenant_id AND pa.id=m.party_a AND (" + va.sql() + "))"
                + " AND EXISTS (SELECT 1 FROM REG_PARTY pb WHERE pb.tenant_id=m.tenant_id AND pb.id=m.party_b AND (" + vb.sql() + "))");
        List<Object> params = new ArrayList<>(List.of(ctx.tenant(), status == null ? "OPEN" : status.toUpperCase()));
        params.addAll(va.params());
        params.addAll(vb.params());
        if (partyId != null) {
            where.append(" AND (m.party_a=? OR m.party_b=?)");
            params.add(partyId);
            params.add(partyId);
        }
        return Db.page(c, dialect, "SELECT m.id, m.party_a, m.party_b, m.score, m.decision, m.rule_key, m.details_json, m.status,"
                        + " m.created_at, m.resolved_at, m.resolved_by FROM REG_MATCH_CANDIDATE m", where.toString(), params, null,
                "m.score DESC, m.id", page, size, Candidate::map);
    }

    /** One candidate; NOT_FOUND unless the caller can see both parties (no hint that a hidden pair exists). */
    public Candidate candidate(Connection c, RegistryContext ctx, String candidateId) {
        Candidate cand = Jdbc.one(c, "SELECT id, party_a, party_b, score, decision, rule_key, details_json, status, created_at, resolved_at,"
                        + " resolved_by FROM REG_MATCH_CANDIDATE WHERE id=? AND tenant_id=?", Candidate::map, candidateId, ctx.tenant())
                .orElseThrow(() -> RegistryException.notFound("match candidate", candidateId));
        if (visibleIds(c, ctx, List.of(cand.partyA(), cand.partyB())).size() != 2) {
            throw RegistryException.notFound("match candidate", candidateId);
        }
        return cand;
    }

    /** Confirms a candidate as a duplicate and merges it; the survivor defaults to the older party. */
    public MergeEvent confirmCandidate(Connection c, RegistryContext ctx, String candidateId, String survivorId, String reason) {
        Candidate cand = candidate(c, ctx, candidateId);
        if (!"OPEN".equals(cand.status())) {
            throw RegistryException.state("candidate " + candidateId + " is " + cand.status());
        }
        String survivor = survivorId;
        if (survivor == null) {
            Party a = load(c, ctx.tenant(), cand.partyA());
            Party b = load(c, ctx.tenant(), cand.partyB());
            survivor = a.createdAt().isAfter(b.createdAt()) ? b.id() : a.id();
        }
        if (!survivor.equals(cand.partyA()) && !survivor.equals(cand.partyB())) {
            throw RegistryException.invalid("the survivor must be one of the candidate pair");
        }
        String merged = survivor.equals(cand.partyA()) ? cand.partyB() : cand.partyA();
        return merge(c, ctx, survivor, merged, reason == null ? "match confirmed" : reason, candidateId);
    }

    /** Rejects a candidate: the pair is never proposed again. */
    public Candidate rejectCandidate(Connection c, RegistryContext ctx, String candidateId, String reason) {
        Candidate cand = candidate(c, ctx, candidateId);
        if (!"OPEN".equals(cand.status())) {
            throw RegistryException.state("candidate " + candidateId + " is " + cand.status());
        }
        Jdbc.update(c, "UPDATE REG_MATCH_CANDIDATE SET status='REJECTED', resolved_at=?, resolved_by=? WHERE id=? AND tenant_id=?",
                Db.now(c, dialect), ctx.actor(), candidateId, ctx.tenant());
        Map<String, Object> facts = Maps.of("candidateId", candidateId, "reason", reason);
        audit.record(c, ctx, cand.partyA(), "PARTY_MATCH_REJECTED", facts);
        audit.record(c, ctx, cand.partyB(), "PARTY_MATCH_REJECTED", facts);
        return candidate(c, ctx, candidateId);
    }

    // ---------------------------------------------------------------------------------------------- merge / unmerge

    /**
     * Merges {@code mergedId} into {@code survivorId}: the golden record follows the survivorship rules; identifiers,
     * relationships and addresses move to the survivor (an identifier the survivor already holds is kept as
     * DUPLICATE); the merged party becomes MERGED with {@code merged_into}. Before images and every moved row are
     * kept in {@code REG_MERGE_EVENT}, so {@link #unmerge} restores exactly.
     */
    public MergeEvent merge(Connection c, RegistryContext ctx, String survivorId, String mergedId, String reason,
                            String candidateId) {
        if (survivorId == null || mergedId == null || survivorId.equals(mergedId)) {
            throw RegistryException.invalid("merge needs two different parties");
        }
        Map<String, Party> locked = lockPair(c, ctx, survivorId, mergedId);
        Party s = locked.get(survivorId);
        Party m = locked.get(mergedId);
        requireStatus(s, "ACTIVE", "INACTIVE");
        requireStatus(m, "ACTIVE", "INACTIVE");
        if (!s.kind().equals(m.kind())) {
            throw RegistryException.invalid("cannot merge a " + s.kind() + " with a " + m.kind());
        }
        MatchConfig cfg = matchConfig(c, ctx.tenant());
        Instant now = Db.now(c, dialect);
        String tenant = ctx.tenant();

        List<Identifier> survivorIds = identifiers(c, tenant, s.id(), false);
        List<Map<String, Object>> movedIds = new ArrayList<>();
        for (Identifier i : identifiers(c, tenant, m.id(), false)) {
            boolean dup = "ACTIVE".equals(i.status()) && survivorIds.stream().anyMatch(x -> "ACTIVE".equals(x.status())
                    && x.idType().equals(i.idType()) && x.valueHash().equals(i.valueHash()));
            Jdbc.update(c, "UPDATE REG_PARTY_IDENTIFIER SET party_id=?, moved_from_party=?, status=? WHERE id=? AND tenant_id=?",
                    s.id(), m.id(), dup ? "DUPLICATE" : i.status(), i.id(), tenant);
            movedIds.add(Maps.of("id", i.id(), "status", i.status(), "movedFrom", i.movedFromParty()));
        }
        List<Map<String, Object>> movedRels = new ArrayList<>();
        for (Relationship r : relationshipsOf(c, tenant, m.id(), false)) {
            String from = r.fromParty().equals(m.id()) ? s.id() : r.fromParty();
            String to = r.toParty().equals(m.id()) ? s.id() : r.toParty();
            String st = from.equals(to) ? "MERGED_SELF" : r.status();
            Jdbc.update(c, "UPDATE REG_PARTY_RELATIONSHIP SET from_party=?, to_party=?, status=? WHERE id=? AND tenant_id=?",
                    from, to, st, r.id(), tenant);
            movedRels.add(Maps.of("id", r.id(), "from", r.fromParty(), "to", r.toParty(), "status", r.status()));
        }
        boolean survivorHasPrimary = addressesOf(c, tenant, s.id(), true).stream().anyMatch(Address::primary);
        List<Map<String, Object>> movedAddrs = new ArrayList<>();
        for (Address a : addressesOf(c, tenant, m.id(), false)) {
            boolean demote = a.primary() && survivorHasPrimary;
            Jdbc.update(c, "UPDATE REG_PARTY_ADDRESS SET party_id=?, primary_flag=? WHERE id=? AND tenant_id=?",
                    s.id(), a.primary() && !demote ? 1 : 0, a.id(), tenant);
            movedAddrs.add(Maps.of("id", a.id(), "primary", a.primary()));
        }

        Party after = survive(cfg, s, m);
        save(c, ctx, after, s.version());
        save(c, ctx, withStatus(m, "MERGED", s.id()), m.version());
        Party survivorAfter = load(c, tenant, s.id());

        String eventId = Db.newId();
        Map<String, Object> moved = Maps.of("identifiers", movedIds, "relationships", movedRels, "addresses", movedAddrs);
        Jdbc.update(c, "INSERT INTO REG_MERGE_EVENT(id, tenant_id, survivor_id, merged_id, status, survivor_before_json,"
                        + " merged_before_json, survivor_after_json, moved_json, reason, candidate_id, merged_at, merged_by)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                eventId, tenant, s.id(), m.id(), "APPLIED", Json.write(s.image()), Json.write(m.image()),
                Json.write(survivorAfter.image()), Json.write(moved), reason, candidateId, now, ctx.actor());

        String a = s.id().compareTo(m.id()) < 0 ? s.id() : m.id();
        String b = a.equals(s.id()) ? m.id() : s.id();
        Jdbc.update(c, "UPDATE REG_MATCH_CANDIDATE SET status='MERGED', resolved_at=?, resolved_by=? WHERE tenant_id=?"
                + " AND party_a=? AND party_b=? AND status='OPEN'", now, ctx.actor(), tenant, a, b);
        Jdbc.update(c, "UPDATE REG_MATCH_CANDIDATE SET status='SUPERSEDED', resolved_at=?, resolved_by=? WHERE tenant_id=?"
                + " AND (party_a=? OR party_b=?) AND status='OPEN'", now, ctx.actor(), tenant, m.id(), m.id());

        Jdbc.update(c, "DELETE FROM REG_PARTY_BLOCK WHERE tenant_id=? AND party_id=?", tenant, m.id());
        rebuildBlocks(c, tenant, s.id());

        Map<String, Object> facts = Maps.of("mergeEventId", eventId, "survivorId", s.id(), "mergedId", m.id(),
                "reason", reason, "candidateId", candidateId, "identifiersMoved", movedIds.size(),
                "relationshipsMoved", movedRels.size(), "addressesMoved", movedAddrs.size());
        audit.record(c, ctx, s.id(), "PARTY_MERGED_SURVIVOR", facts);
        audit.record(c, ctx, m.id(), "PARTY_MERGED_INTO", facts);
        ports.consent().partiesMerged(ctx, s.id(), m.id());
        return mergeEvent(c, tenant, eventId);
    }

    /**
     * Undoes a merge exactly: both parties get their before images back and every moved row returns. Merges are
     * undone last-in-first-out: a later APPLIED merge into the same survivor must be undone first.
     */
    public MergeEvent unmerge(Connection c, RegistryContext ctx, String mergeEventId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw RegistryException.invalid("a reason is required to unmerge");
        }
        String tenant = ctx.tenant();
        MergeEvent ev = Jdbc.one(c, dialect.lockingSelect(MergeEvent.COLUMNS, "REG_MERGE_EVENT", "id=? AND tenant_id=?"),
                MergeEvent::map, mergeEventId, tenant).orElseThrow(() -> RegistryException.notFound("merge event", mergeEventId));
        if (!"APPLIED".equals(ev.status())) {
            throw RegistryException.state("merge " + mergeEventId + " is " + ev.status());
        }
        int later = Jdbc.one(c, "SELECT COUNT(*) FROM REG_MERGE_EVENT WHERE tenant_id=? AND survivor_id=? AND status='APPLIED'"
                        + " AND (merged_at > ? OR (merged_at = ? AND id > ?))", rs -> rs.getInt(1),
                tenant, ev.survivorId(), ev.mergedAt(), ev.mergedAt(), ev.id()).orElse(0);
        if (later > 0) {
            throw RegistryException.state("a later merge into " + ev.survivorId() + " must be undone first (LIFO)");
        }
        Map<String, Party> locked = lockPair(c, ctx, ev.survivorId(), ev.mergedId());
        Party s = locked.get(ev.survivorId());
        Party m = locked.get(ev.mergedId());
        if ("MERGED".equals(s.status())) {
            throw RegistryException.state("survivor " + s.id() + " was merged into " + s.mergedInto() + "; undo that merge first");
        }
        if (!"MERGED".equals(m.status()) || !s.id().equals(m.mergedInto())) {
            throw RegistryException.state("party " + m.id() + " is no longer merged into " + s.id());
        }
        Instant now = Db.now(c, dialect);
        for (Map<String, Object> i : Maps.maps(ev.moved(), "identifiers")) {
            Jdbc.update(c, "UPDATE REG_PARTY_IDENTIFIER SET party_id=?, moved_from_party=?, status=? WHERE id=? AND tenant_id=?",
                    m.id(), Maps.str(i, "movedFrom"), Maps.str(i, "status"), Maps.str(i, "id"), tenant);
        }
        for (Map<String, Object> r : Maps.maps(ev.moved(), "relationships")) {
            Jdbc.update(c, "UPDATE REG_PARTY_RELATIONSHIP SET from_party=?, to_party=?, status=? WHERE id=? AND tenant_id=?",
                    Maps.str(r, "from"), Maps.str(r, "to"), Maps.str(r, "status"), Maps.str(r, "id"), tenant);
        }
        for (Map<String, Object> a : Maps.maps(ev.moved(), "addresses")) {
            Jdbc.update(c, "UPDATE REG_PARTY_ADDRESS SET party_id=?, primary_flag=? WHERE id=? AND tenant_id=?",
                    m.id(), Maps.bool(a, "primary", false) ? 1 : 0, Maps.str(a, "id"), tenant);
        }
        save(c, ctx, fromImage(s, ev.survivorBefore()), s.version());
        save(c, ctx, fromImage(m, ev.mergedBefore()), m.version());
        rebuildBlocks(c, tenant, s.id());
        rebuildBlocks(c, tenant, m.id());
        Jdbc.update(c, "UPDATE REG_MERGE_EVENT SET status='UNDONE', unmerged_at=?, unmerged_by=?, unmerge_reason=? WHERE id=?"
                + " AND tenant_id=?", now, ctx.actor(), reason, ev.id(), tenant);
        if (ev.candidateId() != null) {
            Jdbc.update(c, "UPDATE REG_MATCH_CANDIDATE SET status='UNMERGED', resolved_at=?, resolved_by=? WHERE id=? AND tenant_id=?",
                    now, ctx.actor(), ev.candidateId(), tenant);
        }
        Map<String, Object> facts = Maps.of("mergeEventId", ev.id(), "survivorId", s.id(), "restoredId", m.id(), "reason", reason);
        audit.record(c, ctx, s.id(), "PARTY_UNMERGED_SURVIVOR", facts);
        audit.record(c, ctx, m.id(), "PARTY_UNMERGED_RESTORED", facts);
        ports.consent().partiesUnmerged(ctx, s.id(), m.id());
        return mergeEvent(c, tenant, ev.id());
    }

    /** Merge history of a party (as survivor or as merged), newest first. */
    public List<MergeEvent> merges(Connection c, RegistryContext ctx, String partyId) {
        get(c, ctx, partyId);
        return Jdbc.query(c, "SELECT " + MergeEvent.COLUMNS + " FROM REG_MERGE_EVENT WHERE tenant_id=? AND (survivor_id=? OR merged_id=?)"
                + " ORDER BY merged_at DESC, id DESC", MergeEvent::map, ctx.tenant(), partyId, partyId);
    }

    public MergeEvent mergeEvent(Connection c, String tenant, String id) {
        return Jdbc.one(c, "SELECT " + MergeEvent.COLUMNS + " FROM REG_MERGE_EVENT WHERE id=? AND tenant_id=?",
                MergeEvent::map, id, tenant).orElseThrow(() -> RegistryException.notFound("merge event", id));
    }

    /** Field-level survivorship. Aliases always collect the merged party's names. */
    private static Party survive(MatchConfig cfg, Party s, Party m) {
        Map<String, Object> names = new LinkedHashMap<>(asMap(pick(cfg.survivorship("names"), s.names(), m.names(), s, m)));
        Set<String> aliases = new LinkedHashSet<>();
        Object existing = names.get("aliases");
        if (existing instanceof Collection<?> col) {
            col.forEach(x -> aliases.add(String.valueOf(x)));
        }
        Object mAliases = m.names().get("aliases");
        if (mAliases instanceof Collection<?> col) {
            col.forEach(x -> aliases.add(String.valueOf(x)));
        }
        String mFull = PartyMatcher.fullName(m.names(), m.displayName());
        if (mFull != null && !Similarity.normalise(mFull).equals(Similarity.normalise(PartyMatcher.fullName(names, s.displayName())))) {
            aliases.add(mFull);
        }
        if (!aliases.isEmpty()) {
            names.put("aliases", new ArrayList<>(aliases));
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>(s.attrs().keySet());
        keys.addAll(m.attrs().keySet());
        String attrRule = cfg.survivorship("attributes");
        for (String k : keys) {
            Object v = pick(cfg.survivorship().getOrDefault("attr:" + k, attrRule), s.attrs().get(k), m.attrs().get(k), s, m);
            if (v != null) {
                attrs.put(k, v);
            }
        }
        String display = (String) pick(cfg.survivorship("displayName"), s.displayName(), m.displayName(), s, m);
        return new Party(s.id(), s.tenant(), s.kind(), s.status(), null, display, names,
                (String) pick(cfg.survivorship("birthDate"), s.birthDate(), m.birthDate(), s, m),
                (String) pick(cfg.survivorship("gender"), s.gender(), m.gender(), s, m), attrs,
                s.source(), Math.max(s.sourcePriority(), m.sourcePriority()), s.version(), s.createdAt(), s.createdBy(),
                s.updatedAt(), s.updatedBy());
    }

    private static Object pick(String rule, Object sv, Object mv, Party s, Party m) {
        boolean sEmpty = empty(sv);
        boolean mEmpty = empty(mv);
        if (sEmpty) {
            return mv;
        }
        if (mEmpty) {
            return sv;
        }
        return switch (rule) {
            case "MOST_RECENT" -> m.updatedAt() != null && s.updatedAt() != null && m.updatedAt().isAfter(s.updatedAt()) ? mv : sv;
            case "MOST_COMPLETE" -> completeness(mv) > completeness(sv) ? mv : sv;
            case "SOURCE_PRIORITY" -> m.sourcePriority() > s.sourcePriority() ? mv : sv;
            default -> sv;
        };
    }

    private static boolean empty(Object v) {
        return v == null || v instanceof String str && str.isBlank() || v instanceof Map<?, ?> mm && mm.isEmpty();
    }

    private static int completeness(Object v) {
        if (v instanceof Map<?, ?> mm) {
            int n = 0;
            for (Object x : mm.values()) {
                if (!empty(x)) {
                    n++;
                }
            }
            return n;
        }
        return v == null ? 0 : v.toString().length();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> mm ? (Map<String, Object>) mm : new LinkedHashMap<>();
    }

    private static Party fromImage(Party cur, Map<String, Object> img) {
        return new Party(cur.id(), cur.tenant(), cur.kind(), Maps.str(img, "status", "ACTIVE"), Maps.str(img, "mergedInto"),
                Maps.str(img, "displayName", cur.displayName()), Maps.map(img, "names"), Maps.str(img, "birthDate"),
                Maps.str(img, "gender"), Maps.map(img, "attributes"), Maps.str(img, "source"),
                Maps.integer(img, "sourcePriority", 0), cur.version(), cur.createdAt(), cur.createdBy(), cur.updatedAt(),
                cur.updatedBy());
    }

    // --------------------------------------------------------------------------------------------------- helpers

    private void consent(RegistryContext ctx, String partyId, String processing) {
        if (ctx.purpose() == null || ctx.purpose().isBlank()) {
            return;
        }
        RegistryPorts.ConsentDecision d = ports.consent().check(ctx, partyId, ctx.purpose(), processing);
        if (!d.permitted()) {
            throw new RegistryException(RegistryException.Code.CONSENT_REQUIRED, "no consent for purpose " + ctx.purpose()
                    + " on party " + partyId + (d.reason() == null ? "" : ": " + d.reason()));
        }
    }

    private Party load(Connection c, String tenant, String id) {
        return Jdbc.one(c, "SELECT " + Party.COLUMNS + " FROM REG_PARTY p WHERE p.id=? AND p.tenant_id=?", Party::map, id, tenant)
                .orElseThrow(() -> RegistryException.notFound("party", id));
    }

    private Party lockParty(Connection c, RegistryContext ctx, String id) {
        get(c, ctx, id);
        return Jdbc.one(c, dialect.lockingSelect(Party.COLUMNS.replace("p.", ""), "REG_PARTY", "id=? AND tenant_id=?"),
                Party::map, id, ctx.tenant()).orElseThrow(() -> RegistryException.notFound("party", id));
    }

    /** Locks two parties in ascending id order (deadlock-free). */
    private Map<String, Party> lockPair(Connection c, RegistryContext ctx, String x, String y) {
        String first = x.compareTo(y) < 0 ? x : y;
        String second = first.equals(x) ? y : x;
        Map<String, Party> out = new HashMap<>();
        out.put(first, lockParty(c, ctx, first));
        out.put(second, lockParty(c, ctx, second));
        return out;
    }

    private void save(Connection c, RegistryContext ctx, Party p, long fromVersion) {
        int n = Jdbc.update(c, "UPDATE REG_PARTY SET status=?, merged_into=?, display_name=?, name_json=?, birth_date=?, gender=?,"
                        + " attrs_json=?, source=?, source_priority=?, version=?, updated_at=?, updated_by=?"
                        + " WHERE id=? AND tenant_id=? AND version=?",
                p.status(), p.mergedInto(), p.displayName(), Json.write(p.names()), p.birthDate(), p.gender(),
                Json.write(p.attrs()), p.source(), p.sourcePriority(), fromVersion + 1, Db.now(c, dialect), ctx.actor(),
                p.id(), ctx.tenant(), fromVersion);
        if (n == 0) {
            throw RegistryException.conflict("party " + p.id() + " changed concurrently");
        }
    }

    private static Party withStatus(Party p, String status, String mergedInto) {
        return new Party(p.id(), p.tenant(), p.kind(), status, mergedInto, p.displayName(), p.names(), p.birthDate(),
                p.gender(), p.attrs(), p.source(), p.sourcePriority(), p.version(), p.createdAt(), p.createdBy(),
                p.updatedAt(), p.updatedBy());
    }

    private void rebuildBlocks(Connection c, String tenant, String partyId) {
        Jdbc.update(c, "DELETE FROM REG_PARTY_BLOCK WHERE tenant_id=? AND party_id=?", tenant, partyId);
        Party p = load(c, tenant, partyId);
        if ("MERGED".equals(p.status())) {
            return;
        }
        for (String key : PartyMatcher.blockingKeys(PartyMatcher.probe(p, identifiers(c, tenant, partyId, true)))) {
            Jdbc.insertIfAbsent(c, dialect, "INSERT INTO REG_PARTY_BLOCK(tenant_id, block_key, party_id) VALUES (?,?,?)",
                    tenant, key, partyId);
        }
    }

    private static void checkVersion(Party p, Long expected) {
        if (expected != null && expected != p.version()) {
            throw RegistryException.conflict("party " + p.id() + " is at version " + p.version() + ", expected " + expected);
        }
    }

    private static void requireStatus(Party p, String... allowed) {
        for (String a : allowed) {
            if (a.equals(p.status())) {
                return;
            }
        }
        throw RegistryException.state("party " + p.id() + " is " + p.status()
                + ("MERGED".equals(p.status()) ? " into " + p.mergedInto() : ""));
    }

    private static String kind(String k) {
        String v = k.toUpperCase();
        if ("ORGANIZATION".equals(v)) {
            v = KIND_ORG;
        }
        if (!KIND_PERSON.equals(v) && !KIND_ORG.equals(v)) {
            throw RegistryException.invalid("party kind must be PERSON or ORGANISATION");
        }
        return v;
    }

    private static String displayName(String kind, Map<String, Object> names, String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.length() > 255 ? explicit.substring(0, 255) : explicit;
        }
        String full = PartyMatcher.fullName(names, null);
        if (full == null || full.isBlank()) {
            throw RegistryException.invalid(KIND_ORG.equals(kind) ? "an organisation needs names.full (its legal name)"
                    : "a person needs names.full or names.given / names.family");
        }
        return full.length() > 255 ? full.substring(0, 255) : full;
    }

    private static String birthDate(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String s = v.trim();
        if (!s.matches("\\d{4}(-\\d{2}(-\\d{2})?)?")) {
            throw RegistryException.invalid("birthDate must be yyyy, yyyy-MM or yyyy-MM-dd");
        }
        try {
            if (s.length() == 10) {
                java.time.LocalDate.parse(s);
            } else if (s.length() == 7) {
                java.time.YearMonth.parse(s);
            }
        } catch (java.time.format.DateTimeParseException e) {
            throw RegistryException.invalid("birthDate is not a valid date");
        }
        return s;
    }

    private static List<String> fieldNames(Map<String, Object> m) {
        return m == null ? List.of() : m.keySet().stream().sorted().toList();
    }

    private static String digestHex(Map<String, Object> m) {
        return Hashes.hex(Hashes.sha256(CanonicalJson.write(Json.canonical(m))));
    }
}
