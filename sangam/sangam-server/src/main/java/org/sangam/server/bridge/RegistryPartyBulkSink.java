// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.ops.bulk.BulkIngest;
import org.sangam.ops.bulk.IdentitySink;
import org.sangam.registry.RegistryContext;
import org.sangam.registry.RegistryException;
import org.sangam.registry.party.IdentifierType;
import org.sangam.registry.party.IdentifierTypeCatalogue;
import org.sangam.registry.party.PartyRecords.Address;
import org.sangam.registry.party.PartyRecords.Identifier;
import org.sangam.registry.party.PartyRecords.Party;
import org.sangam.registry.party.PartyService;
import org.sangam.rest.modules.registry.RegistryPermissions;
import org.sangam.security.tenant.TenantContext;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Bulk sink of registry parties (M-130, owner decision 93), over the public {@link PartyService} API only, so every
 * registry rule, audit fact, port and visibility check of a manual call applies unchanged. All work is on the row's
 * connection.
 *
 * <p><b>Idempotency key.</b> The external id is recorded on the party as an identifier of a configured identifier type
 * ({@code sangam.ops.bulk.registry-party.key-type}, default {@value #DEFAULT_KEY_TYPE}). That type must be
 * {@code unique} and not {@code sensitive}; it is seeded for all tenants (pack default layer) when no definition
 * exists, and a tenant definition that is not unique or is sensitive makes every row fail closed
 * ({@code KEY_TYPE_NOT_UNIQUE}). A unique claim serialises concurrent claims of one value in the registry, so two rows
 * never create two parties for one key.
 *
 * <ul>
 *   <li>Key not found: the party is created with matching off, so it is never merged automatically; the key is then
 *       claimed and the party matched with {@code record}, which only queues possible duplicates for review. When the
 *       key is already held by a party outside the creator's visibility the registry holds it for review; the row is
 *       then rejected ({@code EXTERNAL_KEY_HELD_FOR_REVIEW}) and rolled back, so no second party is created.</li>
 *   <li>Key found (under the creator's visibility): the fields the row gives are patched only when they differ;
 *       identifiers and addresses the party does not have yet are added. An unchanged row writes nothing.</li>
 *   <li>The row created the party before but its key is no longer found: rejected ({@code PARTY_KEY_MISSING}), never
 *       silently a second party.</li>
 * </ul>
 *
 * <p>Every row runs as the job's creator (owner decision 59): the creator's snapshot is the registry actor and is bound
 * to {@link TenantContext} for the row, so the registry's visibility, privacy and consent ports evaluate the creator,
 * never the system principal. The publish and system-of-record ports are not used by any party call.
 */
public final class RegistryPartyBulkSink implements IdentitySink {

    public static final String OBJECT_TYPE = "registry.party";
    public static final String DEFAULT_KEY_TYPE = "BULK_EXTERNAL_KEY";
    static final String REF = "party:";
    private static final List<String> FIELDS = List.of("names", "displayName", "birthDate", "gender", "source", "sourcePriority");

    private final PartyService parties;
    private final String keyType;

    public RegistryPartyBulkSink(PartyService parties, String keyType) {
        this.parties = Objects.requireNonNull(parties);
        if (keyType == null || keyType.isBlank() || keyType.length() > 64) {
            throw new IllegalArgumentException("the bulk party key type must be 1..64 characters");
        }
        this.keyType = keyType;
        if (parties.identifierTypes().find(IdentifierTypeCatalogue.ALL_TENANTS, keyType) == null) {
            parties.defineIdentifierTypes(IdentifierTypeCatalogue.ALL_TENANTS, List.of(keyTypeDefinition(keyType)));
        }
    }

    /** The seeded definition of the key type: unique, not sensitive, for persons and organisations. */
    static Map<String, Object> keyTypeDefinition(String keyType) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("typeKey", keyType);
        d.put("label", "Bulk external key");
        d.put("unique", true);
        d.put("sensitive", false);
        d.put("appliesTo", "ANY");
        return d;
    }

    @Override
    public String objectType() {
        return OBJECT_TYPE;
    }

    @Override
    public String permission() {
        return RegistryPermissions.WRITE;
    }

    @Override
    public String apply(Connection c, SecurityIdentity creator, String externalId, Map<String, Object> data, String existingRef) {
        RegistryContext ctx = new RegistryContext(creator.tenantId(), creator.personId(), null, null, null, null);
        Optional<SecurityIdentity> previous = TenantContext.current();
        TenantContext.set(creator);
        try {
            return REF + rejecting(() -> applyAs(c, ctx, externalId, data, existingRef));
        } finally {
            if (previous.isPresent()) {
                TenantContext.set(previous.get());
            } else {
                TenantContext.clear();
            }
        }
    }

    private String applyAs(Connection c, RegistryContext ctx, String externalId, Map<String, Object> data, String existingRef) {
        requireKeyType(c, ctx.tenant());
        List<Map<String, Object>> identifiers = identifiersWithoutKey(data, externalId);
        List<Party> found = parties.list(c, ctx, null, null, null, keyType, externalId, 0, 2);
        if (found.size() > 1) {
            throw new BulkIngest.RowRejectedException("KEY_AMBIGUOUS", "more than one party holds this external key");
        }
        if (found.isEmpty()) {
            if (existingRef != null && existingRef.startsWith(REF)) {
                throw new BulkIngest.RowRejectedException("PARTY_KEY_MISSING",
                        "the party this external id created is no longer found by its key (" + existingRef + ")");
            }
            return create(c, ctx, externalId, data, identifiers);
        }
        Party p = found.get(0);
        if ("MERGED".equals(p.status())) {
            p = parties.get(c, ctx, parties.resolve(c, ctx, p.id()));
        }
        update(c, ctx, p, data, identifiers);
        return p.id();
    }

    private void requireKeyType(Connection c, String tenant) {
        IdentifierType t = parties.identifierTypes(c, tenant).stream().filter(x -> keyType.equals(x.typeKey())).findFirst()
                .orElseThrow(() -> new BulkIngest.RowRejectedException("KEY_TYPE_NOT_CONFIGURED",
                        "identifier type " + keyType + " is not configured for this tenant"));
        if (!t.unique() || t.sensitive()) {
            throw new BulkIngest.RowRejectedException("KEY_TYPE_NOT_UNIQUE",
                    "identifier type " + keyType + " must be unique and not sensitive to key bulk parties");
        }
    }

    private List<Map<String, Object>> identifiersWithoutKey(Map<String, Object> data, String externalId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> spec : maps(data, "identifiers")) {
            if (keyType.equals(str(spec, "type"))) {
                if (!externalId.equals(str(spec, "value"))) {
                    throw new BulkIngest.RowRejectedException("KEY_MISMATCH", "an identifier of type " + keyType
                            + " must equal the external id");
                }
                continue;
            }
            out.add(spec);
        }
        return out;
    }

    // ------------------------------------------------------------------ create

    private String create(Connection c, RegistryContext ctx, String externalId, Map<String, Object> data,
                          List<Map<String, Object>> identifiers) {
        Map<String, Object> req = new LinkedHashMap<>(data);
        req.put("identifiers", identifiers);
        req.put("match", false); // decision 93: never merged automatically
        String id = parties.create(c, ctx, req).party().id();
        Identifier key;
        try {
            key = parties.addIdentifier(c, ctx, id, Map.of("type", keyType, "value", externalId));
        } catch (RegistryException e) {
            if (e.code() == RegistryException.Code.CONFLICT) {
                // a visible party claimed this key since the lookup (the claim is serialised): retried, then found
                throw new IllegalStateException("external key claimed concurrently; the row is retried", e);
            }
            throw e;
        }
        if (!"ACTIVE".equals(key.status())) {
            throw new BulkIngest.RowRejectedException("EXTERNAL_KEY_HELD_FOR_REVIEW",
                    "the external key is already recorded for a party outside the creator's visibility");
        }
        parties.matchParty(c, ctx, id, true); // possible duplicates are queued for review, never merged here
        return id;
    }

    // ------------------------------------------------------------------ update

    private void update(Connection c, RegistryContext ctx, Party p, Map<String, Object> data, List<Map<String, Object>> identifiers) {
        String kind = str(data, "kind");
        if (kind != null && !sameKind(kind, p.kind())) {
            throw new BulkIngest.RowRejectedException("KIND_CHANGED", "the kind of party " + p.id() + " cannot change");
        }
        Map<String, Object> given = new LinkedHashMap<>(data);
        if (!given.containsKey("names") && given.get("name") != null) {
            given.put("names", Map.of("full", String.valueOf(given.get("name"))));
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        for (String f : FIELDS) {
            if (given.containsKey(f) && !same(given.get(f), current(p, f))) {
                patch.put(f, given.get(f));
            }
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        map(given, "attributes").forEach((k, v) -> {
            if (!same(v, p.attrs().get(k))) {
                attrs.put(k, v);
            }
        });
        if (!attrs.isEmpty()) {
            patch.put("attributes", attrs);
        }
        if (!patch.isEmpty()) {
            parties.update(c, ctx, p.id(), null, patch); // locks the party under the creator's visibility
        }
        for (Map<String, Object> spec : identifiers) {
            String type = str(spec, "type");
            String value = str(spec, "value");
            boolean held = type != null && value != null && parties.list(c, ctx, null, null, null, type, value, 0, 50).stream()
                    .anyMatch(x -> x.id().equals(p.id()));
            if (!held) {
                parties.addIdentifier(c, ctx, p.id(), spec);
            }
        }
        List<Map<String, Object>> addresses = maps(data, "addresses");
        if (!addresses.isEmpty()) {
            List<Address> current = parties.addresses(c, ctx, p.id()).stream().filter(a -> "ACTIVE".equals(a.status())).toList();
            for (Map<String, Object> spec : addresses) {
                String type = str(spec, "type") == null ? "GENERAL" : str(spec, "type");
                boolean present = current.stream().anyMatch(a -> a.type().equals(type)
                        && Objects.equals(a.address(), map(spec, "address")) && Objects.equals(a.geometryRef(), str(spec, "geometryRef")));
                if (!present) {
                    parties.addAddress(c, ctx, p.id(), spec);
                }
            }
        }
    }

    private static Object current(Party p, String field) {
        return switch (field) {
            case "names" -> p.names();
            case "displayName" -> p.displayName();
            case "birthDate" -> p.birthDate();
            case "gender" -> p.gender();
            case "source" -> p.source();
            case "sourcePriority" -> p.sourcePriority();
            default -> null;
        };
    }

    private static boolean same(Object given, Object current) {
        if (given instanceof Map<?, ?> || current instanceof Map<?, ?>) {
            return Objects.equals(given, current);
        }
        return Objects.equals(given == null ? null : String.valueOf(given), current == null ? null : String.valueOf(current));
    }

    private static boolean sameKind(String given, String current) {
        String g = given.toUpperCase(Locale.ROOT);
        return g.equals(current) || ("ORGANIZATION".equals(g) && "ORGANISATION".equals(current));
    }

    // ------------------------------------------------------------------ shared

    /** Registry and security refusals can never succeed on a retry: row rejections. Anything else is retried. */
    static <T> T rejecting(Supplier<T> work) {
        try {
            return work.get();
        } catch (RegistryException e) {
            throw new BulkIngest.RowRejectedException("REGISTRY_" + e.code().name(), e.getMessage());
        } catch (SangamSecurityException e) {
            throw new BulkIngest.RowRejectedException(e.code(), e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new BulkIngest.RowRejectedException("INVALID_ROW", e.getMessage());
        }
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return Map.of();
        }
        if (v instanceof Map<?, ?> x) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<Object, Object>) x).forEach((k, y) -> out.put(String.valueOf(k), y));
            return out;
        }
        throw new BulkIngest.RowRejectedException("INVALID_OBJECT", "data." + key + " must be an object");
    }

    private static List<Map<String, Object>> maps(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> list)) {
            throw new BulkIngest.RowRejectedException("INVALID_LIST", "data." + key + " must be a list of objects");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?>)) {
                throw new BulkIngest.RowRejectedException("INVALID_LIST", "data." + key + " must be a list of objects");
            }
            out.add(map(Map.of("x", o), "x"));
        }
        return out;
    }
}
