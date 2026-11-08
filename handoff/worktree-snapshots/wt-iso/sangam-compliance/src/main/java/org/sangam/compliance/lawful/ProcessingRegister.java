// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.lawful;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.support.Sql;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Record of processing activities (table {@code CMP_PROCESSING_ACTIVITY}): which workflow (process key) processes
 * which personal data for which purpose, through which processors, to which recipients, stored where. It feeds the
 * access summary, the privacy policy, the impact assessment and the transfer checks (IN: CMP-001, CMP-020, CMP-021,
 * CMP-035, CMP-120).
 */
public final class ProcessingRegister {

    /**
     * @param processors            Data Processors engaged : cloud/SDC host, system integrator, SMS gateway ...
     * @param recipients            other Data Fiduciaries the data is shared with
     * @param storageCountry        ISO country of storage (defaults to the jurisdiction's home country)
     * @param transfersAbroad       countries data is transferred to 
     * @param automatedDecision     data is used to make a decision affecting the principal
     * @param algorithmicSoftware   algorithmic software is used on the data
     * @param highRisk              impact assessment required
     */
    public record ProcessingActivity(String activityId, String purposeCode, String processKey, String description,
                                     Set<String> dataCategories, List<String> processors, List<String> recipients,
                                     String storageCountry, List<String> transfersAbroad, boolean automatedDecision,
                                     boolean algorithmicSoftware, boolean highRisk) {
        public ProcessingActivity {
            dataCategories = dataCategories == null ? Set.of() : Set.copyOf(dataCategories);
            processors = processors == null ? List.of() : List.copyOf(processors);
            recipients = recipients == null ? List.of() : List.copyOf(recipients);
            transfersAbroad = transfersAbroad == null ? List.of() : List.copyOf(transfersAbroad);
            storageCountry = storageCountry == null || storageCountry.isBlank() ? null : storageCountry.toUpperCase();
        }
    }

    private final Dialect dialect;
    private final ComplianceAudit audit;
    private final LegitimateUseRegistry purposes;
    private final org.sangam.compliance.config.ComplianceConfig.Provider config;

    public ProcessingRegister(Dialect dialect, ComplianceAudit audit, LegitimateUseRegistry purposes,
                              org.sangam.compliance.config.ComplianceConfig.Provider config) {
        this.dialect = dialect;
        this.audit = audit;
        this.purposes = purposes;
        this.config = config;
    }

    public ProcessingActivity register(Connection c, String tenantId, ProcessingActivity in, String actor) {
        Sql.requireText(in.activityId(), "activityId");
        ProcessingActivity a = in.storageCountry() != null ? in : new ProcessingActivity(in.activityId(), in.purposeCode(),
                in.processKey(), in.description(), in.dataCategories(), in.processors(), in.recipients(),
                config.forTenant(tenantId).pack.homeCountry(), in.transfersAbroad(), in.automatedDecision(), in.algorithmicSoftware(),
                in.highRisk());
        PurposeDefinition p = purposes.require(c, tenantId, a.purposeCode());
        if (!p.dataCategoryCodes().containsAll(a.dataCategories())) {
            throw ComplianceException.invalid("activity " + a.activityId() + " uses data categories not itemised for purpose "
                    + a.purposeCode() + " (data minimisation)");
        }
        Object[] values = {a.purposeCode(), a.processKey(), a.description(), Sql.jsonList(a.dataCategories()),
                Sql.jsonList(a.processors()), Sql.jsonList(a.recipients()), a.storageCountry(), Sql.jsonList(a.transfersAbroad()),
                a.automatedDecision(), a.algorithmicSoftware(), a.highRisk(), actor};
        int n = Jdbc.update(c, "UPDATE CMP_PROCESSING_ACTIVITY SET purpose_code=?, process_key=?, description=?, data_categories_json=?,"
                + " processors_json=?, recipients_json=?, storage_country=?, transfers_json=?, automated_decision=?,"
                + " algorithmic_software=?, high_risk=?, updated_by=?, updated_at=" + dialect.now()
                + " WHERE tenant_id=? AND activity_id=?", concat(values, tenantId, a.activityId()));
        if (n == 0) {
            Jdbc.update(c, "INSERT INTO CMP_PROCESSING_ACTIVITY(purpose_code, process_key, description, data_categories_json,"
                    + " processors_json, recipients_json, storage_country, transfers_json, automated_decision, algorithmic_software,"
                    + " high_risk, updated_by, tenant_id, activity_id, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,"
                    + dialect.now() + ")", concat(values, tenantId, a.activityId()));
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("activity", a.activityId());
        ev.put("purpose", a.purposeCode());
        ev.put("processKey", a.processKey());
        ev.put("storageCountry", a.storageCountry());
        ev.put("transfersAbroad", a.transfersAbroad());
        ev.put("actor", actor);
        audit.organisation(c, tenantId, ComplianceAudit.PROCESSING_REGISTERED, ev);
        return a;
    }

    public List<ProcessingActivity> list(Connection c, String tenantId) {
        return Jdbc.query(c, SELECT + " WHERE tenant_id=? ORDER BY activity_id", ProcessingRegister::map, tenantId);
    }

    public List<ProcessingActivity> byPurpose(Connection c, String tenantId, String purposeCode) {
        return Jdbc.query(c, SELECT + " WHERE tenant_id=? AND purpose_code=? ORDER BY activity_id", ProcessingRegister::map,
                tenantId, purposeCode);
    }

    public Optional<ProcessingActivity> byProcessKey(Connection c, String tenantId, String processKey) {
        return Jdbc.query(c, SELECT + " WHERE tenant_id=? AND process_key=? ORDER BY activity_id", ProcessingRegister::map,
                tenantId, processKey).stream().findFirst();
    }

    private static Object[] concat(Object[] a, Object... b) {
        Object[] out = new Object[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static final String SELECT = "SELECT activity_id, purpose_code, process_key, description, data_categories_json,"
            + " processors_json, recipients_json, storage_country, transfers_json, automated_decision, algorithmic_software,"
            + " high_risk FROM CMP_PROCESSING_ACTIVITY";

    private static ProcessingActivity map(ResultSet rs) throws SQLException {
        return new ProcessingActivity(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                Sql.set(rs.getString(5)), Sql.list(rs.getString(6)), Sql.list(rs.getString(7)), rs.getString(8),
                Sql.list(rs.getString(9)), Sql.bool(rs.getInt(10)), Sql.bool(rs.getInt(11)), Sql.bool(rs.getInt(12)));
    }
}
