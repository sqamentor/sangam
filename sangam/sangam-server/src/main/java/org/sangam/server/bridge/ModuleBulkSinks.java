// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import org.sangam.config.master.MasterCode;
import org.sangam.config.store.JdbcConfigStore;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.ops.bulk.BulkIngest;
import org.sangam.ops.bulk.IdentitySink;
import org.sangam.resources.catalogue.Resource;
import org.sangam.resources.catalogue.ResourceCatalogue;
import org.sangam.resources.roster.RosterBulkApply;
import org.sangam.resources.roster.RosterModel;
import org.sangam.resources.roster.RosterService;
import org.sangam.resources.support.ResourcesException;
import org.sangam.rest.modules.config.ConfigPermissions;
import org.sangam.rest.modules.resources.ResourcesPermissions;

import java.sql.Connection;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Bulk sinks of module object types (M-130, SL-44), registered by {@code OpsWiring}. Every sink works only on the
 * row's connection, so the bulk transaction holds all of its effects and a retried row is idempotent.
 *
 * <ul>
 *   <li>{@value #RESOURCE}: create-or-update by resource id (the external id) through
 *       {@link ResourceCatalogue#upsert}, as the job's creator; the catalogue's per-object guard decides each row.</li>
 *   <li>{@value #ROSTER_DUTY}: one roster assignment per external id through {@link RosterBulkApply}, as the job's
 *       creator; the roster guard and every roster rule decide each row.</li>
 *   <li>{@value #MASTER_CODE}: one master code per external id, upserted by key in the job tenant's own configuration
 *       scope through {@link JdbcConfigStore#put(Connection, MasterCode)}. Master codes have no per-object permission;
 *       the scope is always the job's tenant, and the key a row first wrote cannot change later.</li>
 * </ul>
 * A refusal that can never succeed becomes {@link BulkIngest.RowRejectedException} with the module's reason code; a
 * stale version or an unavailable dependency stays a retryable failure.
 */
public final class ModuleBulkSinks {

    public static final String RESOURCE = "resources.resource";
    public static final String ROSTER_DUTY = "resources.rosterDuty";
    public static final String MASTER_CODE = "config.masterCode";

    private ModuleBulkSinks() {
    }

    // ------------------------------------------------------------------ resources

    /** Row data: {@code typeId, code, name, parentId, orgUnitId, locationId, geoLat, geoLon, capacity, unitLabel, calendarId, attributes}. */
    public static IdentitySink resources(ResourceCatalogue catalogue) {
        return new IdentitySink() {
            @Override
            public String objectType() {
                return RESOURCE;
            }

            @Override
            public String permission() {
                return ResourcesPermissions.ADMIN;
            }

            @Override
            public String apply(Connection c, SecurityIdentity creator, String externalId, Map<String, Object> data, String existingRef) {
                Object id = data.get("resourceId");
                if (id != null && !externalId.equals(String.valueOf(id))) {
                    throw new BulkIngest.RowRejectedException("RESOURCE_ID_MISMATCH", "data.resourceId must equal the external id");
                }
                Resource.Draft d = new Resource.Draft(externalId, str(data, "typeId"), str(data, "code"), str(data, "name"),
                        str(data, "parentId"), str(data, "orgUnitId"), str(data, "locationId"), str(data, "geoLat"),
                        str(data, "geoLon"), integer(data, "capacity"), str(data, "unitLabel"), str(data, "calendarId"),
                        map(data, "attributes"));
                return "resource:" + rejecting(() -> catalogue.upsert(c, creator, d)).resourceId();
            }
        };
    }

    // ------------------------------------------------------------------ roster duties

    /** Row data: {@code periodId, personId, shiftId, day (yyyy-MM-dd), kind, demandId, dutySeatId, locationId, override, overrideReason}. */
    public static IdentitySink rosterDuties(RosterService roster) {
        RosterBulkApply bulk = new RosterBulkApply(roster);
        return new IdentitySink() {
            @Override
            public String objectType() {
                return ROSTER_DUTY;
            }

            @Override
            public String permission() {
                return ResourcesPermissions.ADMIN;
            }

            @Override
            public String apply(Connection c, SecurityIdentity creator, String externalId, Map<String, Object> data, String existingRef) {
                RosterService.AssignCommand cmd = new RosterService.AssignCommand(required(data, "periodId"), required(data, "personId"),
                        required(data, "shiftId"), date(data, "day", true), kind(str(data, "kind")), str(data, "demandId"),
                        str(data, "dutySeatId"), str(data, "locationId"), Boolean.TRUE.equals(data.get("override")),
                        str(data, "overrideReason"));
                String previous = existingRef != null && existingRef.startsWith(ASSIGNMENT) ? existingRef.substring(ASSIGNMENT.length()) : null;
                return ASSIGNMENT + rejecting(() -> bulk.apply(c, creator, cmd, previous)).assignmentId();
            }
        };
    }

    private static final String ASSIGNMENT = "assignment:";

    private static RosterModel.AssignmentKind kind(String s) {
        if (s == null) {
            return RosterModel.AssignmentKind.DUTY;
        }
        try {
            return RosterModel.AssignmentKind.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BulkIngest.RowRejectedException("INVALID_KIND", "unknown assignment kind: " + s);
        }
    }

    // ------------------------------------------------------------------ master codes

    /**
     * Row data: {@code scheme, segment, code, parentCode, name ({lang: text} or text), flags, validFrom, validTo,
     * sourceRef}. The scope is the job's tenant.
     */
    public static BulkIngest.Sink masterCodes(JdbcConfigStore store) {
        return new BulkIngest.Sink() {
            @Override
            public String objectType() {
                return MASTER_CODE;
            }

            @Override
            public String permission() {
                return ConfigPermissions.WRITE;
            }

            @Override
            public String apply(Connection c, String tenant, String actor, String externalId, Map<String, Object> data, String existingRef) {
                Object scope = data.get("scope");
                if (scope != null && !tenant.equals(String.valueOf(scope))) {
                    throw new BulkIngest.RowRejectedException("SCOPE_NOT_OWNED", "a bulk master code is written in the tenant's own scope only");
                }
                MasterCode m = new MasterCode(tenant, required(data, "scheme"), required(data, "segment"), required(data, "code"),
                        str(data, "parentCode"), names(data.get("name")), flags(data), date(data, "validFrom", false),
                        date(data, "validTo", false), str(data, "sourceRef") == null ? "bulk" : str(data, "sourceRef"));
                String ref = "master:" + m.key() + "|" + (m.validFrom() == null ? "" : m.validFrom());
                if (existingRef != null && !existingRef.equals(ref)) {
                    throw new BulkIngest.RowRejectedException("KEY_CHANGED", "this external id already wrote master code " + existingRef);
                }
                try {
                    store.put(c, m);
                } catch (IllegalArgumentException e) {
                    throw new BulkIngest.RowRejectedException("INVALID_MASTER_CODE", e.getMessage());
                }
                return ref;
            }
        };
    }

    private static Map<String, String> names(Object name) {
        Map<String, String> out = new LinkedHashMap<>();
        if (name instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (k != null && v != null) {
                    out.put(String.valueOf(k), String.valueOf(v));
                }
            });
        } else if (name != null) {
            out.put("en", String.valueOf(name));
        }
        return out;
    }

    private static Map<String, Object> flags(Map<String, Object> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        map(data, "flags").forEach((k, v) -> {
            if (v != null) {
                out.put(k, v);
            }
        });
        return out;
    }

    // ------------------------------------------------------------------ shared

    /** Module refusals that can never succeed become row rejections; stale and unavailable stay retryable. */
    static <T> T rejecting(Supplier<T> work) {
        try {
            return work.get();
        } catch (ResourcesException e) {
            if (e.code() == ResourcesException.Code.STALE || e.code() == ResourcesException.Code.UNAVAILABLE) {
                throw e;
            }
            throw new BulkIngest.RowRejectedException(e.reason() == null ? e.code().name() : e.reason(), e.getMessage());
        } catch (SangamSecurityException e) {
            throw new BulkIngest.RowRejectedException(e.code(), e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new BulkIngest.RowRejectedException("INVALID_ROW", e.getMessage());
        }
    }

    static String str(Map<String, Object> data, String key) {
        Object v = data.get(key);
        return v == null ? null : String.valueOf(v);
    }

    static String required(Map<String, Object> data, String key) {
        String v = str(data, key);
        if (v == null || v.isBlank()) {
            throw new BulkIngest.RowRejectedException("FIELD_REQUIRED", "data." + key + " is required");
        }
        return v;
    }

    static Integer integer(Map<String, Object> data, String key) {
        Object v = data.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(v).strip());
        } catch (NumberFormatException e) {
            throw new BulkIngest.RowRejectedException("INVALID_NUMBER", "data." + key + " must be a whole number");
        }
    }

    static LocalDate date(Map<String, Object> data, String key, boolean required) {
        String v = required ? required(data, key) : str(data, key);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(v.strip());
        } catch (DateTimeParseException e) {
            throw new BulkIngest.RowRejectedException("INVALID_DATE", "data." + key + " must be yyyy-MM-dd");
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, Object> data, String key) {
        Object v = data.get(key);
        if (v == null) {
            return Map.of();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<Object, Object>) m).forEach((k, x) -> out.put(String.valueOf(k), x));
            return out;
        }
        throw new BulkIngest.RowRejectedException("INVALID_OBJECT", "data." + key + " must be an object");
    }
}
