// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.asset;

import org.sangam.registry.support.Db;
import org.sangam.registry.support.Maps;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/** The asset-specific part of a registered object of kind ASSET ({@code REG_ASSET}; id = the kernel object id). */
public record Asset(String id, String tenant, String classKey, String tag, String parentAssetId, String locationRef,
                    String geometryRef, String custodianRef, String acquisitionDate, String inServiceDate,
                    BigDecimal acquisitionValue, BigDecimal residualValue, String currency, Integer usefulLifeMonths,
                    String depreciationMethod, BigDecimal depreciationRate, String warrantyRef, String warrantyUntil,
                    String disposedDate, String status, long version) {

    static final String COLUMNS = "a.object_id, a.tenant_id, a.class_key, a.asset_tag, a.parent_asset_id, a.location_ref,"
            + " a.geometry_ref, a.custodian_ref, a.acquisition_date, a.in_service_date, a.acquisition_value, a.residual_value,"
            + " a.currency, a.useful_life_months, a.depreciation_method, a.depreciation_rate, a.warranty_ref,"
            + " a.warranty_until, a.disposed_date, a.status, a.version";

    static Asset map(ResultSet rs) throws SQLException {
        return new Asset(rs.getString("object_id"), rs.getString("tenant_id"), rs.getString("class_key"),
                rs.getString("asset_tag"), rs.getString("parent_asset_id"), rs.getString("location_ref"),
                rs.getString("geometry_ref"), rs.getString("custodian_ref"), rs.getString("acquisition_date"),
                rs.getString("in_service_date"), Db.decimal(rs, "acquisition_value"), Db.decimal(rs, "residual_value"),
                rs.getString("currency"), Db.intOrNull(rs, "useful_life_months"), rs.getString("depreciation_method"),
                Db.decimal(rs, "depreciation_rate"), rs.getString("warranty_ref"), rs.getString("warranty_until"),
                rs.getString("disposed_date"), rs.getString("status"), rs.getLong("version"));
    }

    public Map<String, Object> toMap() {
        return Maps.of("id", id, "classKey", classKey, "tag", tag, "parentAssetId", parentAssetId,
                "locationRef", locationRef, "geometryRef", geometryRef, "custodianRef", custodianRef,
                "acquisitionDate", acquisitionDate, "inServiceDate", inServiceDate, "acquisitionValue", acquisitionValue,
                "residualValue", residualValue, "currency", currency, "usefulLifeMonths", usefulLifeMonths,
                "depreciationMethod", depreciationMethod, "depreciationRate", depreciationRate,
                "warrantyRef", warrantyRef, "warrantyUntil", warrantyUntil, "disposedDate", disposedDate,
                "status", status, "version", version);
    }
}
