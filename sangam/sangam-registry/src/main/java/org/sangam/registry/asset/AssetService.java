// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.asset;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
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

import java.math.BigDecimal;
import org.sangam.core.money.Money;
import java.math.RoundingMode;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Asset registry (N03): configured asset classes with inheritance, asset registration on the kernel (kind ASSET),
 * hierarchy, location and custodian movements, disposal and depreciation. Every change is audited on the asset's
 * chain (root = asset id) and published as an event.
 */
public final class AssetService {

    public static final String RESOURCE = "registry-asset";
    private static final int MAX_DEPTH = 64;

    private final Dialect dialect;
    private final RegisteredObjectService objects;
    private final RegistryAudit audit;
    private final RegistryPorts ports;

    public AssetService(Dialect dialect, RegisteredObjectService objects, RegistryAudit audit, RegistryPorts ports) {
        this.dialect = dialect;
        this.objects = objects;
        this.audit = audit;
        this.ports = ports;
    }

    /** An asset with its kernel object. */
    public record AssetView(RegisteredObject object, Asset asset) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>(object.toMap());
            m.put("asset", asset.toMap());
            return m;
        }
    }

    /** A list query; null fields do not filter. */
    public record Query(String classKey, String status, String parentAssetId, String locationRef, String custodianRef,
                        int page, int size) {
    }

    // ------------------------------------------------------------------------------------------------ classes

    public AssetClassDefinition defineClass(Connection c, RegistryContext ctx, Map<String, Object> definition) {
        AssetClassDefinition def = AssetClassDefinition.from(definition);
        if (def.parentClass() != null) {
            rawClass(c, ctx.tenant(), def.parentClass());
            String p = def.parentClass();
            for (int i = 0; p != null; i++) {
                if (p.equals(def.classKey()) || i > MAX_DEPTH) {
                    throw RegistryException.invalid("asset class hierarchy would contain a cycle at " + def.classKey());
                }
                p = rawClass(c, ctx.tenant(), p).parentClass();
            }
        }
        Instant now = Db.now(c, dialect);
        String json = Json.write(def.toMap());
        int n = Jdbc.update(c, "UPDATE REG_ASSET_CLASS SET parent_class=?, version=version+1, definition_json=?, updated_at=?,"
                + " updated_by=? WHERE tenant_id=? AND class_key=?", def.parentClass(), json, now, ctx.actor(), ctx.tenant(), def.classKey());
        if (n == 0 && !Jdbc.insertIfAbsent(c, dialect, "INSERT INTO REG_ASSET_CLASS(tenant_id, class_key, parent_class, version,"
                        + " definition_json, updated_at, updated_by) VALUES (?,?,?,?,?,?,?)",
                ctx.tenant(), def.classKey(), def.parentClass(), 1, json, now, ctx.actor())) {
            throw RegistryException.conflict("asset class " + def.classKey() + " was defined concurrently; retry");
        }
        audit.record(c, ctx, classRoot(ctx.tenant(), def.classKey()), "ASSET_CLASS_DEFINED", def.toMap());
        return def;
    }

    /** The class as stored (without inheritance). */
    public AssetClassDefinition rawClass(Connection c, String tenant, String classKey) {
        return Jdbc.one(c, "SELECT definition_json FROM REG_ASSET_CLASS WHERE tenant_id=? AND class_key=?",
                        rs -> AssetClassDefinition.from(Json.map(rs.getString(1))), tenant, classKey)
                .orElseThrow(() -> RegistryException.notFound("asset class", classKey));
    }

    /** The effective class: settings inherited along the parent chain. */
    public AssetClassDefinition resolveClass(Connection c, String tenant, String classKey) {
        List<AssetClassDefinition> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String k = classKey;
        while (k != null) {
            if (!seen.add(k) || seen.size() > MAX_DEPTH) {
                throw RegistryException.invalid("asset class hierarchy contains a cycle at " + k);
            }
            AssetClassDefinition d = rawClass(c, tenant, k);
            chain.add(d);
            k = d.parentClass();
        }
        AssetClassDefinition eff = null;
        for (int i = chain.size() - 1; i >= 0; i--) {
            eff = chain.get(i).inherit(eff);
        }
        return eff;
    }

    public List<Map<String, Object>> listClasses(Connection c, RegistryContext ctx) {
        return Jdbc.query(c, "SELECT class_key, parent_class, version, definition_json, updated_at, updated_by FROM REG_ASSET_CLASS"
                        + " WHERE tenant_id=? ORDER BY class_key",
                rs -> Maps.of("classKey", rs.getString(1), "parentClass", rs.getString(2), "version", rs.getInt(3),
                        "definition", Json.map(rs.getString(4)), "updatedAt", Jdbc.instant(rs, "updated_at"),
                        "updatedBy", rs.getString(6)), ctx.tenant());
    }

    // ----------------------------------------------------------------------------------------------- register

    /**
     * Registers an asset. Request fields: {@code classKey}, {@code tag}, {@code attributes}, {@code displayName},
     * {@code parentAssetId}, {@code locationRef}, {@code geometryRef}, {@code custodianRef}, {@code holderPartyId},
     * {@code acquisitionDate}, {@code inServiceDate}, {@code acquisitionValue}, {@code residualValue}, {@code currency},
     * {@code usefulLifeMonths}, {@code depreciationMethod}, {@code depreciationRate}, {@code warrantyRef},
     * {@code warrantyUntil}, {@code externalRef}, {@code numberScope}, {@code typeKey} (overrides the class's type).
     */
    public AssetView register(Connection c, RegistryContext ctx, Map<String, Object> req) {
        String classKey = Maps.required(req, "classKey");
        String tag = Maps.required(req, "tag");
        AssetClassDefinition cls = resolveClass(c, ctx.tenant(), classKey);
        String typeKey = Maps.str(req, "typeKey", cls.objectType());
        if (typeKey == null) {
            throw RegistryException.invalid("asset class " + classKey + " has no objectType");
        }
        ObjectTypeDefinition type = objects.types().current(c, ctx.tenant(), typeKey);
        if (type.kind() != ObjectKind.ASSET) {
            throw RegistryException.invalid("type " + typeKey + " is not of kind ASSET");
        }
        if (Jdbc.one(c, "SELECT object_id FROM REG_ASSET WHERE tenant_id=? AND asset_tag=?", rs -> rs.getString(1),
                ctx.tenant(), tag).isPresent()) {
            throw RegistryException.conflict("asset tag " + tag + " is already registered");
        }
        String parent = Maps.str(req, "parentAssetId");
        if (parent != null) {
            get(c, ctx, parent);
        }
        String method = Maps.str(req, "depreciationMethod");
        if (method != null && !DepreciationParams.METHODS.contains(method.toUpperCase())) {
            throw RegistryException.invalid("depreciation method must be one of " + DepreciationParams.METHODS);
        }
        LocalDate acq = Maps.date(req, "acquisitionDate");
        LocalDate inService = Maps.date(req, "inServiceDate");
        LocalDate warrantyUntil = Maps.date(req, "warrantyUntil");
        RegisteredObject o = objects.create(c, ctx, type, new RegisteredObjectService.CreateRequest(typeKey,
                Maps.map(req, "attributes"), Maps.str(req, "displayName", tag), Maps.str(req, "holderPartyId"),
                Maps.str(req, "locationRef"), parent, Maps.str(req, "externalRef"), null, null,
                Maps.str(req, "numberScope"), true, false), type.lifecycle().initial());
        try {
            Jdbc.update(c, "INSERT INTO REG_ASSET(object_id, tenant_id, class_key, asset_tag, parent_asset_id, location_ref,"
                            + " geometry_ref, custodian_ref, acquisition_date, in_service_date, acquisition_value, residual_value,"
                            + " currency, useful_life_months, depreciation_method, depreciation_rate, warranty_ref, warranty_until,"
                            + " disposed_date, status, version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    o.id(), ctx.tenant(), classKey, tag, parent, Maps.str(req, "locationRef"), Maps.str(req, "geometryRef"),
                    Maps.str(req, "custodianRef"), acq == null ? null : acq.toString(),
                    inService == null ? null : inService.toString(), minor(req, "acquisitionValue", Maps.str(req, "currency")),
                    minor(req, "residualValue", Maps.str(req, "currency")), Maps.str(req, "currency"), Maps.integer(req, "usefulLifeMonths"),
                    method == null ? null : method.toUpperCase(), Maps.decimal(req, "depreciationRate"),
                    Maps.str(req, "warrantyRef"), warrantyUntil == null ? null : warrantyUntil.toString(), null,
                    "IN_SERVICE", 1L);
        } catch (Jdbc.LayerSqlException e) {
            if (dialect.isUniqueViolation(e.sql())) {
                throw RegistryException.conflict("asset tag " + tag + " is already registered");
            }
            throw e;
        }
        Instant base = inService != null ? inService.atStartOfDay(ctx.zone()).toInstant() : Db.now(c, dialect);
        for (AssetClassDefinition.MeterSpec m : cls.meters()) {
            AssetRows.insertMeter(c, ctx.tenant(), o.id(), m);
        }
        for (AssetClassDefinition.PlanSpec p : cls.plans()) {
            AssetRows.insertPlan(c, ctx.tenant(), o.id(), p, base, BigDecimal.ZERO);
        }
        audit.record(c, ctx, o.id(), "ASSET_REGISTERED", Maps.of("classKey", classKey, "tag", tag, "parentAssetId", parent,
                "locationRef", Maps.str(req, "locationRef"), "custodianRef", Maps.str(req, "custodianRef"),
                "acquisitionValue", Maps.str(req, "acquisitionValue"), "currency", Maps.str(req, "currency"), "meters", cls.meters().size(),
                "plans", cls.plans().size()));
        return new AssetView(o, asset(c, ctx.tenant(), o.id()));
    }

    // ------------------------------------------------------------------------------------------------ queries

    public Asset get(Connection c, RegistryContext ctx, String id) {
        objects.get(c, ctx, id);
        return asset(c, ctx.tenant(), id);
    }

    public AssetView view(Connection c, RegistryContext ctx, String id) {
        return new AssetView(objects.get(c, ctx, id), asset(c, ctx.tenant(), id));
    }

    public AssetView byTag(Connection c, RegistryContext ctx, String tag) {
        String id = Jdbc.one(c, "SELECT object_id FROM REG_ASSET WHERE tenant_id=? AND asset_tag=?", rs -> rs.getString(1),
                ctx.tenant(), tag).orElseThrow(() -> RegistryException.notFound("asset with tag", tag));
        return view(c, ctx, id);
    }

    Asset asset(Connection c, String tenant, String id) {
        return Jdbc.one(c, "SELECT " + Asset.COLUMNS + " FROM REG_ASSET a WHERE a.object_id=? AND a.tenant_id=?", Asset::map,
                id, tenant).orElseThrow(() -> RegistryException.notFound("asset", id));
    }

    Asset lockAsset(Connection c, String tenant, String id) {
        return Jdbc.one(c, dialect.lockingSelect(Asset.COLUMNS.replace("a.", ""), "REG_ASSET", "object_id=? AND tenant_id=?"),
                Asset::map, id, tenant).orElseThrow(() -> RegistryException.notFound("asset", id));
    }

    public List<Asset> list(Connection c, RegistryContext ctx, Query q) {
        StringBuilder where = new StringBuilder("a.tenant_id=?");
        List<Object> params = new ArrayList<>(List.of(ctx.tenant()));
        add(where, params, "a.class_key", q.classKey());
        add(where, params, "a.status", q.status());
        add(where, params, "a.parent_asset_id", q.parentAssetId());
        add(where, params, "a.location_ref", q.locationRef());
        add(where, params, "a.custodian_ref", q.custodianRef());
        return Db.page(c, dialect, "SELECT " + Asset.COLUMNS + " FROM REG_ASSET a JOIN REG_OBJECT o ON o.id = a.object_id",
                where.toString(), params, ports.visibility().visible(ctx, RegisteredObjectService.RESOURCE, "o"),
                "a.asset_tag", q.page(), q.size(), Asset::map);
    }

    private static void add(StringBuilder where, List<Object> params, String col, Object v) {
        if (v != null) {
            where.append(" AND ").append(col).append("=?");
            params.add(v);
        }
    }

    public List<Asset> children(Connection c, RegistryContext ctx, String id) {
        get(c, ctx, id);
        return Jdbc.query(c, "SELECT " + Asset.COLUMNS + " FROM REG_ASSET a WHERE a.tenant_id=? AND a.parent_asset_id=?"
                + " ORDER BY a.asset_tag", Asset::map, ctx.tenant(), id);
    }

    /** Ancestors from the direct parent up to the root. */
    public List<Asset> ancestors(Connection c, RegistryContext ctx, String id) {
        List<Asset> out = new ArrayList<>();
        Asset a = get(c, ctx, id);
        Set<String> seen = new HashSet<>(List.of(id));
        while (a.parentAssetId() != null) {
            if (!seen.add(a.parentAssetId()) || out.size() > MAX_DEPTH) {
                throw RegistryException.state("asset hierarchy contains a cycle at " + a.parentAssetId());
            }
            a = asset(c, ctx.tenant(), a.parentAssetId());
            out.add(a);
        }
        return out;
    }

    /** The whole subtree below an asset (breadth first, the asset itself excluded). */
    public List<Asset> subtree(Connection c, RegistryContext ctx, String id) {
        get(c, ctx, id);
        List<Asset> out = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>(List.of(id));
        Set<String> seen = new HashSet<>(List.of(id));
        while (!queue.isEmpty()) {
            for (Asset ch : Jdbc.query(c, "SELECT " + Asset.COLUMNS + " FROM REG_ASSET a WHERE a.tenant_id=? AND a.parent_asset_id=?"
                    + " ORDER BY a.asset_tag", Asset::map, ctx.tenant(), queue.poll())) {
                if (seen.add(ch.id())) {
                    out.add(ch);
                    queue.add(ch.id());
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ changes

    /** Moves an asset to a new location and/or custodian; the movement is kept in {@code REG_ASSET_MOVEMENT}. */
    public Asset move(Connection c, RegistryContext ctx, String id, String toLocation, String toCustodian,
                      String geometryRef, String reason) {
        if (toLocation == null && toCustodian == null && geometryRef == null) {
            throw RegistryException.invalid("a move needs a new location, custodian or geometry");
        }
        RegisteredObject o = objects.mutate(c, ctx, id, null, "MOVE", reason,
                x -> toLocation == null ? x : x.withLinks(toLocation, x.parentId()));
        Asset a = lockAsset(c, ctx.tenant(), id);
        requireInService(a);
        String loc = toLocation == null ? a.locationRef() : toLocation;
        String cust = toCustodian == null ? a.custodianRef() : toCustodian;
        String geo = geometryRef == null ? a.geometryRef() : geometryRef;
        Instant now = Db.now(c, dialect);
        Jdbc.update(c, "UPDATE REG_ASSET SET location_ref=?, custodian_ref=?, geometry_ref=?, version=version+1"
                + " WHERE object_id=? AND tenant_id=?", loc, cust, geo, id, ctx.tenant());
        Jdbc.update(c, "INSERT INTO REG_ASSET_MOVEMENT(id, tenant_id, asset_id, from_location, to_location, from_custodian,"
                        + " to_custodian, reason, moved_at, moved_by) VALUES (?,?,?,?,?,?,?,?,?,?)",
                Db.newId(), ctx.tenant(), id, a.locationRef(), loc, a.custodianRef(), cust, reason, now, ctx.actor());
        audit.record(c, ctx, o.id(), "ASSET_MOVED", Maps.of("fromLocation", a.locationRef(), "toLocation", loc,
                "fromCustodian", a.custodianRef(), "toCustodian", cust, "reason", reason));
        return asset(c, ctx.tenant(), id);
    }

    /** Movement history, newest first. */
    public List<Map<String, Object>> movements(Connection c, RegistryContext ctx, String id) {
        get(c, ctx, id);
        return Jdbc.query(c, "SELECT id, from_location, to_location, from_custodian, to_custodian, reason, moved_at, moved_by"
                        + " FROM REG_ASSET_MOVEMENT WHERE tenant_id=? AND asset_id=? ORDER BY moved_at DESC, id DESC",
                rs -> Maps.of("id", rs.getString(1), "fromLocation", rs.getString(2), "toLocation", rs.getString(3),
                        "fromCustodian", rs.getString(4), "toCustodian", rs.getString(5), "reason", rs.getString(6),
                        "movedAt", Jdbc.instant(rs, "moved_at"), "movedBy", rs.getString(8)), ctx.tenant(), id);
    }

    /** Places an asset under a new parent (null = top level), refusing cycles. */
    public Asset reparent(Connection c, RegistryContext ctx, String id, String newParentId, String reason) {
        if (id.equals(newParentId)) {
            throw RegistryException.invalid("an asset cannot be its own parent");
        }
        if (newParentId != null) {
            get(c, ctx, newParentId);
            for (Asset anc : ancestors(c, ctx, newParentId)) {
                if (anc.id().equals(id)) {
                    throw RegistryException.invalid("asset " + newParentId + " is below " + id + ": that would be a cycle");
                }
            }
        }
        objects.mutate(c, ctx, id, null, "REPARENT", reason, x -> x.withLinks(x.subjectRef(), newParentId));
        Asset a = lockAsset(c, ctx.tenant(), id);
        Jdbc.update(c, "UPDATE REG_ASSET SET parent_asset_id=?, version=version+1 WHERE object_id=? AND tenant_id=?",
                newParentId, id, ctx.tenant());
        audit.record(c, ctx, id, "ASSET_REPARENTED", Maps.of("fromParent", a.parentAssetId(), "toParent", newParentId,
                "reason", reason));
        return asset(c, ctx.tenant(), id);
    }

    /**
     * Updates financial and warranty data: {@code acquisitionValue}, {@code residualValue}, {@code currency},
     * {@code usefulLifeMonths}, {@code depreciationMethod}, {@code depreciationRate}, {@code acquisitionDate},
     * {@code inServiceDate}, {@code warrantyRef}, {@code warrantyUntil}.
     */
    public Asset updateDetails(Connection c, RegistryContext ctx, String id, Long expectedVersion, Map<String, Object> patch,
                               String reason) {
        get(c, ctx, id);
        Asset a = lockAsset(c, ctx.tenant(), id);
        if (expectedVersion != null && expectedVersion != a.version()) {
            throw RegistryException.conflict("asset " + id + " is at version " + a.version() + ", expected " + expectedVersion);
        }
        requireInService(a);
        String method = Maps.str(patch, "depreciationMethod", a.depreciationMethod());
        if (method != null && !DepreciationParams.METHODS.contains(method.toUpperCase())) {
            throw RegistryException.invalid("depreciation method must be one of " + DepreciationParams.METHODS);
        }
        LocalDate acq = Maps.date(patch, "acquisitionDate");
        LocalDate ins = Maps.date(patch, "inServiceDate");
        LocalDate wu = Maps.date(patch, "warrantyUntil");
        String ccy = Maps.str(patch, "currency", a.currency());
        if (!java.util.Objects.equals(ccy, a.currency()) && !patch.containsKey("acquisitionValue")
                && (a.acquisitionValue() != null || a.residualValue() != null)) {
            throw RegistryException.invalid("changing the currency requires restating acquisitionValue / residualValue");
        }
        Long value = patch.containsKey("acquisitionValue") ? minor(patch, "acquisitionValue", ccy)
                : a.acquisitionValue() == null ? null : a.acquisitionValue().minor();
        Long residual = patch.containsKey("residualValue") ? minor(patch, "residualValue", ccy)
                : a.residualValue() == null ? null : a.residualValue().minor();
        Integer life = patch.containsKey("usefulLifeMonths") ? Maps.integer(patch, "usefulLifeMonths") : a.usefulLifeMonths();
        BigDecimal rate = patch.containsKey("depreciationRate") ? Maps.decimal(patch, "depreciationRate") : a.depreciationRate();
        Jdbc.update(c, "UPDATE REG_ASSET SET acquisition_value=?, residual_value=?, currency=?, useful_life_months=?,"
                        + " depreciation_method=?, depreciation_rate=?, acquisition_date=?, in_service_date=?, warranty_ref=?,"
                        + " warranty_until=?, version=version+1 WHERE object_id=? AND tenant_id=? AND version=?",
                value, residual, ccy, life, method == null ? null : method.toUpperCase(),
                rate, acq == null ? a.acquisitionDate() : acq.toString(), ins == null ? a.inServiceDate() : ins.toString(),
                Maps.str(patch, "warrantyRef", a.warrantyRef()), wu == null ? a.warrantyUntil() : wu.toString(),
                id, ctx.tenant(), a.version());
        audit.record(c, ctx, id, "ASSET_DETAILS_UPDATED", Maps.of("changes", patch, "reason", reason));
        return asset(c, ctx.tenant(), id);
    }

    /**
     * Disposes of an asset: the kernel transition (the class's {@code disposeTransition}, default {@code archive})
     * and the disposal date. Assets with children still in service cannot be disposed.
     */
    public AssetView dispose(Connection c, RegistryContext ctx, String id, LocalDate date, String method, String reason,
                             String transition) {
        Asset a = get(c, ctx, id);
        requireInService(a);
        for (Asset ch : children(c, ctx, id)) {
            if ("IN_SERVICE".equals(ch.status())) {
                throw RegistryException.state("asset " + id + " still has component " + ch.tag() + " in service");
            }
        }
        AssetClassDefinition cls = resolveClass(c, ctx.tenant(), a.classKey());
        String tr = transition != null ? transition : cls.disposeTransition() != null ? cls.disposeTransition() : "archive";
        RegisteredObject o = objects.transition(c, ctx, id, null, tr, reason == null ? "disposal" : reason, null, null);
        LocalDate d = date == null ? LocalDate.now(ctx.zone()) : date;
        lockAsset(c, ctx.tenant(), id);
        Jdbc.update(c, "UPDATE REG_ASSET SET status='DISPOSED', disposed_date=?, version=version+1 WHERE object_id=? AND tenant_id=?",
                d.toString(), id, ctx.tenant());
        Money bv = bookValue(c, ctx, id, d);
        audit.record(c, ctx, id, "ASSET_DISPOSED", Maps.of("date", d, "method", method, "reason", reason,
                "bookValue", bv == null ? null : bv.toPlainString(), "currency", bv == null ? null : bv.currencyCode(),
                "transition", tr));
        return new AssetView(o, asset(c, ctx.tenant(), id));
    }

    private static void requireInService(Asset a) {
        if (!"IN_SERVICE".equals(a.status())) {
            throw RegistryException.state("asset " + a.tag() + " is " + a.status());
        }
    }

    // ------------------------------------------------------------------------------------------- depreciation

    /** Depreciation inputs: asset values override the class's parameters. */
    public DepreciationCalculator.Inputs depreciationInputs(Connection c, RegistryContext ctx, String id) {
        Asset a = get(c, ctx, id);
        DepreciationParams p = resolveClass(c, ctx.tenant(), a.classKey()).depreciation();
        String method = a.depreciationMethod() != null ? a.depreciationMethod() : p.effectiveMethod();
        int life = a.usefulLifeMonths() != null ? a.usefulLifeMonths() : p.usefulLifeMonths() == null ? 0 : p.usefulLifeMonths();
        Money cost = a.acquisitionValue();
        Money residual = a.residualValue();
        if (residual == null && cost != null && p.residualPercent() != null) {
            // one rounding of a derived amount (residual % of cost) to whole minor units
            residual = Money.round(cost.toMajor().multiply(p.residualPercent()).movePointLeft(2), cost.currencyCode(),
                    RoundingMode.HALF_UP);
        }
        LocalDate inService = a.inServiceDate() != null ? LocalDate.parse(a.inServiceDate())
                : a.acquisitionDate() != null ? LocalDate.parse(a.acquisitionDate()) : null;
        Map<YearMonth, BigDecimal> units = null;
        if ("UNITS_OF_PRODUCTION".equals(method)) {
            String meterType = p.unitsMeterType();
            if (meterType == null) {
                meterType = Jdbc.query(c, "SELECT meter_type FROM REG_METER WHERE tenant_id=? AND asset_id=? ORDER BY meter_type",
                        rs -> rs.getString(1), ctx.tenant(), id).stream().findFirst().orElse(null);
            }
            units = new TreeMap<>();
            if (meterType != null) {
                for (Object[] r : Jdbc.query(c, "SELECT r.read_at, r.delta FROM REG_METER_READING r JOIN REG_METER m ON m.id = r.meter_id"
                                + " WHERE m.tenant_id=? AND m.asset_id=? AND m.meter_type=? AND r.status='VALID' ORDER BY r.read_at",
                        rs -> new Object[]{Jdbc.instant(rs, "read_at"), Db.decimal(rs, "delta")}, ctx.tenant(), id, meterType)) {
                    YearMonth ym = YearMonth.from(((Instant) r[0]).atZone(ctx.zone()));
                    units.merge(ym, (BigDecimal) r[1], BigDecimal::add);
                }
            }
        }
        BigDecimal rate = a.depreciationRate() != null ? a.depreciationRate() : p.rate();
        return new DepreciationCalculator.Inputs(method, cost, residual, life, rate, p.unitsTotal(),
                p.effectiveConvention(), inService, units);
    }

    public List<DepreciationCalculator.Line> depreciationSchedule(Connection c, RegistryContext ctx, String id) {
        return DepreciationCalculator.schedule(depreciationInputs(c, ctx, id));
    }

    public Money bookValue(Connection c, RegistryContext ctx, String id, LocalDate asOf) {
        return DepreciationCalculator.bookValue(depreciationInputs(c, ctx, id), asOf == null ? LocalDate.now(ctx.zone()) : asOf);
    }

    /**
     * Money field of a request as minor units of {@code currency} (API boundary, SD-398): a decimal string or number in
     * major units, rejected if it has more fraction digits than the currency allows or has no currency.
     */
    static Long minor(Map<String, Object> req, String field, String currency) {
        Object v = req.get(field);
        if (v == null) {
            return null;
        }
        if (currency == null) {
            throw RegistryException.invalid(field + " needs a currency (ISO 4217)");
        }
        try {
            return Money.parse(v.toString(), currency).minor();
        } catch (RuntimeException e) {
            throw RegistryException.invalid(field + ": " + e.getMessage());
        }
    }

    static String classRoot(String tenant, String classKey) {
        return "assetclass:" + org.sangam.core.layer.Hashes.uuidV5(
                java.util.UUID.fromString("3e2d1c0b-9a8f-4e7d-8c6b-5a4f3e2d1c0b"), tenant + "|" + classKey);
    }
}
