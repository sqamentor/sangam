// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.lawful;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceException.Code;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.consent.ChildSafeguards.ProcessingFlag;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.retention.RetentionSchedule;
import org.sangam.compliance.support.Sql;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The lawful-basis / legitimate-use registry. Every purpose a workflow processes personal data for is defined here
 * once, with a basis and exemptions taken from the tenant's jurisdiction pack, so that
 * {@link org.sangam.compliance.consent.PurposeBindingGuard} can refuse anything else. Validation rules are driven by
 * the pack's basis flags ({@code requiresLegalRef}, {@code subGrounds}, {@code subGroundRequiringRegisterRef},
 * {@code requiresStandardsAttestation}). IN: DPDP s.4, s.7, s.17, r.5, Second Schedule (CMP-001, CMP-013 … CMP-019,
 * CMP-043, CMP-052, CMP-081). Tables {@code CMP_PURPOSE} and {@code CMP_OBJECTION}.
 */
public final class LegitimateUseRegistry {

    private final Dialect dialect;
    private final ComplianceAudit audit;
    private final ComplianceConfig.Provider config;

    public LegitimateUseRegistry(Dialect dialect, ComplianceAudit audit, ComplianceConfig.Provider config) {
        this.dialect = dialect;
        this.audit = audit;
        this.config = config;
    }

    public PurposeDefinition define(Connection c, String tenantId, PurposeDefinition d, String actor) {
        JurisdictionPack pack = config.forTenant(tenantId).pack;
        List<String> problems = validate(pack, d);
        if (!problems.isEmpty()) {
            throw ComplianceException.invalid("purpose " + d.code() + ": " + String.join("; ", problems));
        }
        Optional<PurposeDefinition> old = find(c, tenantId, d.code());
        int version = old.map(o -> o.version() + 1).orElse(1);
        Object[] values = {Sql.json(d.names()), Sql.json(d.descriptions()), Sql.json(items(d)), d.basis(),
                Sql.blankToNull(d.legalRef()), Sql.blankToNull(d.subGround()), Sql.blankToNull(d.registerRef()), d.standardsAttested(),
                Sql.jsonList(new TreeSet<>(d.exemptions())), Sql.blankToNull(d.exemptionRef()), Sql.jsonList(new TreeSet<>(d.childExemptions())),
                Sql.enumList(d.flags()), d.retentionTrigger(), Sql.seconds(d.retention()), Sql.blankToNull(d.retentionClass()),
                Sql.blankToNull(d.recordClass()), Sql.seconds(d.recordRetention()), Sql.blankToNull(d.recordRetentionRef()), version,
                d.active(), Sql.blankToNull(d.specialCondition()), actor};
        Object[] p = new Object[values.length + 2];
        if (old.isPresent()) {
            System.arraycopy(values, 0, p, 0, values.length);
            p[values.length] = tenantId;
            p[values.length + 1] = d.code();
            Jdbc.update(c, "UPDATE CMP_PURPOSE SET names_json=?, descriptions_json=?, data_items_json=?, basis=?, legal_ref=?, sub_ground=?,"
                    + " register_ref=?, standards_attested=?, exemptions_json=?, exemption_ref=?, child_exemptions_json=?, flags_json=?,"
                    + " retention_trigger=?, retention_seconds=?, retention_class=?, record_class=?, record_retention_seconds=?,"
                    + " record_retention_ref=?, version=?, active=?, special_condition=?, defined_by=?, defined_at=" + dialect.now()
                    + " WHERE tenant_id=? AND purpose_code=?", p);
        } else {
            p[0] = tenantId;
            p[1] = d.code();
            System.arraycopy(values, 0, p, 2, values.length);
            Jdbc.update(c, "INSERT INTO CMP_PURPOSE(tenant_id, purpose_code, names_json, descriptions_json, data_items_json, basis,"
                    + " legal_ref, sub_ground, register_ref, standards_attested, exemptions_json, exemption_ref, child_exemptions_json,"
                    + " flags_json, retention_trigger, retention_seconds, retention_class, record_class, record_retention_seconds,"
                    + " record_retention_ref, version, active, special_condition, defined_by, defined_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,"
                    + dialect.now() + ")", p);
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("purpose", d.code());
        ev.put("version", version);
        ev.put("basis", d.basis());
        ev.put("citation", pack.legalBasis(d.basis()).map(JurisdictionPack.LegalBasis::citation).orElse(""));
        ev.put("legalRef", d.legalRef());
        ev.put("exemptions", new ArrayList<>(new TreeSet<>(d.exemptions())));
        ev.put("jurisdiction", pack.code());
        ev.put("actor", actor);
        ev.put("definitionHash", Sql.evidenceHash(Sql.kv("names", d.names(), "items", items(d), "basis", d.basis())));
        audit.organisation(c, tenantId, ComplianceAudit.PURPOSE_DEFINED, ev);
        return find(c, tenantId, d.code()).orElseThrow();
    }

    /** Validation rules driven by the jurisdiction pack. */
    public static List<String> validate(JurisdictionPack pack, PurposeDefinition d) {
        List<String> p = new ArrayList<>();
        if (d.code() == null || d.code().isBlank() || d.code().length() > 64) {
            p.add("code is required (max 64 characters)");
        }
        if (d.names().getOrDefault(pack.defaultLanguage(), "").isBlank()) {
            p.add("a name in the default language " + pack.defaultLanguage() + " is required");
        }
        if (d.dataItems().isEmpty()) {
            p.add("an itemised description of the personal data is required");
        }
        Optional<JurisdictionPack.LegalBasis> basis = pack.legalBasis(d.basis());
        if (basis.isEmpty()) {
            p.add("basis " + d.basis() + " is not a lawful basis of jurisdiction " + pack.code());
            return p;
        }
        JurisdictionPack.LegalBasis b = basis.get();
        if (b.consent() && d.descriptions().getOrDefault(pack.defaultLanguage(), "").isBlank()) {
            p.add("a description of the goods, services or uses enabled is required for a consent purpose");
        }
        if (b.requiresLegalRef() && (d.legalRef() == null || d.legalRef().isBlank())) {
            p.add(b.citation() + " requires the reference of the law, order or scheme");
        }
        if (!b.subGrounds().isEmpty() && !b.subGrounds().contains(d.subGround())) {
            p.add(b.citation() + " requires a sub-ground, one of " + b.subGrounds());
        }
        if (b.subGroundRequiringRegisterRef() != null && b.subGroundRequiringRegisterRef().equals(d.subGround())
                && (d.registerRef() == null || d.registerRef().isBlank())) {
            p.add(b.citation() + " sub-ground " + d.subGround() + " requires the notified database or register reference");
        }
        if (b.requiresStandardsAttestation() && !d.standardsAttested()) {
            p.add(b.citation() + " requires attestation that the processing meets the prescribed standards");
        }
        for (String e : d.exemptions()) {
            Optional<JurisdictionPack.Exemption> ex = pack.exemption(e);
            if (ex.isEmpty()) {
                p.add("exemption " + e + " is not defined by jurisdiction " + pack.code());
            } else if (ex.get().requiresStandardsAttestation() && !d.standardsAttested()) {
                p.add(ex.get().citation() + " requires attestation that the processing meets the prescribed standards");
            }
        }
        if (!d.exemptions().isEmpty() && (d.exemptionRef() == null || d.exemptionRef().isBlank())) {
            p.add("an exemption needs its notification or legal reference");
        }
        for (String e : d.childExemptions()) {
            if (pack.childExemption(e).isEmpty()) {
                p.add("child exemption " + e + " is not defined by jurisdiction " + pack.code());
            }
        }
        if (d.retentionClass() != null && !d.retentionClass().isBlank() && pack.retentionClass(d.retentionClass()).isEmpty()) {
            p.add("retention class " + d.retentionClass() + " is not defined by jurisdiction " + pack.code());
        }
        boolean sensitive = d.dataItems().stream().anyMatch(PurposeDefinition.DataItem::sensitive);
        if (sensitive && !pack.specialCategoryConditions().isEmpty()) {
            if (d.specialCondition() == null || pack.specialCategoryConditions().stream().noneMatch(m -> m.code().equals(d.specialCondition()))) {
                p.add("sensitive data needs a special-category condition, one of " + pack.specialCategoryConditions().stream()
                        .map(JurisdictionPack.VerificationMethod::code).toList());
            }
        }
        if (d.flags().contains(ProcessingFlag.DETRIMENTAL_TO_WELLBEING)) {
            p.add("processing flagged detrimental to a child's well-being is not allowed for any purpose");
        }
        if (d.retention() != null && d.retention().isNegative()) {
            p.add("retention must not be negative");
        }
        return p;
    }

    public Optional<PurposeDefinition> find(Connection c, String tenantId, String code) {
        return Jdbc.one(c, SELECT + " WHERE tenant_id=? AND purpose_code=?", LegitimateUseRegistry::map, tenantId, code);
    }

    public PurposeDefinition require(Connection c, String tenantId, String code) {
        return find(c, tenantId, code).orElseThrow(() -> ComplianceException.notFound("purpose", code));
    }

    public List<PurposeDefinition> list(Connection c, String tenantId) {
        return Jdbc.query(c, SELECT + " WHERE tenant_id=? ORDER BY purpose_code", LegitimateUseRegistry::map, tenantId);
    }

    public void deactivate(Connection c, String tenantId, String code, String actor) {
        PurposeDefinition d = require(c, tenantId, code);
        define(c, tenantId, new PurposeDefinition(d.code(), d.names(), d.descriptions(), d.dataItems(), d.basis(), d.legalRef(),
                d.subGround(), d.registerRef(), d.standardsAttested(), d.exemptions(), d.exemptionRef(), d.childExemptions(), d.flags(),
                d.retentionTrigger(), d.retention(), d.retentionClass(), d.recordClass(), d.recordRetention(), d.recordRetentionRef(),
                d.version(), false, d.specialCondition()), actor);
    }

    /** The data subject objected to processing on an objectable basis (IN: s.7(a)). */
    public void recordObjection(Connection c, String tenantId, String principalRef, String purposeCode, String channel) {
        JurisdictionPack pack = config.forTenant(tenantId).pack;
        PurposeDefinition d = require(c, tenantId, purposeCode);
        if (!pack.legalBasis(d.basis()).map(JurisdictionPack.LegalBasis::objectable).orElse(false)) {
            throw new ComplianceException(Code.INVALID_STATE, "the basis of purpose " + purposeCode + " is not objectable; withdraw consent instead");
        }
        Jdbc.insertIfAbsent(c, dialect, "INSERT INTO CMP_OBJECTION(tenant_id, principal_ref, purpose_code, channel, objected_at)"
                + " VALUES (?,?,?,?," + dialect.now() + ")", tenantId, principalRef, purposeCode, channel);
        audit.principal(c, tenantId, principalRef, ComplianceAudit.OBJECTION_RECORDED, Sql.kv("purpose", purposeCode, "channel", channel));
    }

    public boolean hasObjected(Connection c, String tenantId, String principalRef, String purposeCode) {
        return Jdbc.one(c, "SELECT 1 FROM CMP_OBJECTION WHERE tenant_id=? AND principal_ref=? AND purpose_code=?",
                rs -> 1, tenantId, principalRef, purposeCode).isPresent();
    }

    private static List<Map<String, Object>> items(PurposeDefinition d) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PurposeDefinition.DataItem i : d.dataItems()) {
            out.add(Sql.kv("code", i.code(), "names", i.names(), "sensitive", i.sensitive()));
        }
        return out;
    }

    private static final String SELECT = "SELECT purpose_code, names_json, descriptions_json, data_items_json, basis, legal_ref,"
            + " sub_ground, register_ref, standards_attested, exemptions_json, exemption_ref, child_exemptions_json, flags_json,"
            + " retention_trigger, retention_seconds, retention_class, record_class, record_retention_seconds, record_retention_ref,"
            + " version, active, special_condition FROM CMP_PURPOSE";

    private static PurposeDefinition map(ResultSet rs) throws SQLException {
        List<PurposeDefinition.DataItem> items = new ArrayList<>();
        for (Map<String, Object> m : Sql.objects(rs.getString("data_items_json"))) {
            items.add(new PurposeDefinition.DataItem(String.valueOf(m.get("code")), Sql.strings(m.get("names")),
                    Boolean.TRUE.equals(m.get("sensitive"))));
        }
        return new PurposeDefinition(rs.getString("purpose_code"), Sql.stringMap(rs.getString("names_json")),
                Sql.stringMap(rs.getString("descriptions_json")), items, rs.getString("basis"), rs.getString("legal_ref"),
                rs.getString("sub_ground"), rs.getString("register_ref"), Sql.bool(rs.getInt("standards_attested")),
                Sql.set(rs.getString("exemptions_json")), rs.getString("exemption_ref"), Sql.set(rs.getString("child_exemptions_json")),
                Sql.enums(ProcessingFlag.class, rs.getString("flags_json")),
                Sql.enumOrNull(RetentionSchedule.Trigger.class, rs.getString("retention_trigger")), Sql.duration(rs, "retention_seconds"),
                rs.getString("retention_class"), rs.getString("record_class"), Sql.duration(rs, "record_retention_seconds"),
                rs.getString("record_retention_ref"), rs.getInt("version"), Sql.bool(rs.getInt("active")), rs.getString("special_condition"));
    }
}
