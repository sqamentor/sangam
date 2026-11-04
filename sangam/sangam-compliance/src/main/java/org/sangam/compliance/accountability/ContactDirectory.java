// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.accountability;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.support.Sql;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Designated contacts (table {@code CMP_CONTACT}, effective-dated). Roles and their requirements come from the pack's
 * {@code contactRoles}: residency ({@code mustBeResident}), required fields, and whether the role is the published
 * contact on notices and responses. IN: DPO (s.10(2)(a), resident in India), contact person (s.8(9), r.9), grievance
 * officer (SPDI r.5(9)), CERT-In point of contact (Directions para (iii), Annexure II), CISO, records officer.
 */
public final class ContactDirectory {

    public record Contact(String role, String name, String designation, String organisation, String email, String phone,
                          String mobile, String address, boolean resident, Instant validFrom) {
        public Map<String, Object> asMap() {
            return Sql.kv("role", role, "name", name, "designation", designation, "organisation", organisation, "email", email,
                    "phone", phone, "mobile", mobile, "address", address, "resident", resident);
        }
    }

    private final Dialect dialect;
    private final ComplianceAudit audit;
    private final ComplianceConfig.Provider config;

    public ContactDirectory(Dialect dialect, ComplianceAudit audit, ComplianceConfig.Provider config) {
        this.dialect = dialect;
        this.audit = audit;
        this.config = config;
    }

    public Contact set(Connection c, String tenantId, Contact contact, String actor) {
        Sql.requireText(contact.name(), "name");
        Sql.requireText(contact.email(), "email");
        JurisdictionPack pack = config.forTenant(tenantId).pack;
        JurisdictionPack.ContactRole role = pack.contactRole(contact.role()).orElseThrow(() ->
                ComplianceException.invalid("contact role " + contact.role() + " is not defined by jurisdiction " + pack.code()));
        if (role.mustBeResident() && !contact.resident()) {
            throw ComplianceException.invalid(role.code() + " must be resident in " + pack.homeCountry() + " (" + role.citation() + ")");
        }
        Map<String, Object> fields = contact.asMap();
        for (String f : role.requiredFields()) {
            Object v = fields.get(f);
            if (v == null || String.valueOf(v).isBlank()) {
                throw ComplianceException.invalid(role.code() + " requires " + f + " (" + role.citation() + ")");
            }
        }
        Instant now = Sql.now(c, dialect);
        Jdbc.update(c, "UPDATE CMP_CONTACT SET valid_to=? WHERE tenant_id=? AND role=? AND valid_to IS NULL", now, tenantId, contact.role());
        Jdbc.update(c, "INSERT INTO CMP_CONTACT(tenant_id, contact_id, role, name, designation, organisation, email, phone, mobile,"
                        + " address, resident, valid_from, valid_to, set_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,NULL,?)",
                tenantId, Sql.newId(), contact.role(), contact.name(), contact.designation(), contact.organisation(), contact.email(),
                contact.phone(), contact.mobile(), contact.address(), contact.resident(), now, actor);
        audit.organisation(c, tenantId, ComplianceAudit.CONTACT_CHANGED, Sql.kv("role", contact.role(), "actor", actor,
                "resident", contact.resident()));
        return current(c, tenantId, contact.role()).orElseThrow();
    }

    public Optional<Contact> current(Connection c, String tenantId, String role) {
        return Jdbc.one(c, SELECT + " WHERE tenant_id=? AND role=? AND valid_to IS NULL", ContactDirectory::map, tenantId, role);
    }

    /** The published contact: the first pack role marked {@code published} that is filled. */
    public Optional<Contact> publishedContact(Connection c, String tenantId) {
        for (JurisdictionPack.ContactRole r : config.forTenant(tenantId).pack.contactRoles()) {
            if (r.published()) {
                Optional<Contact> ct = current(c, tenantId, r.code());
                if (ct.isPresent()) {
                    return ct;
                }
            }
        }
        return Optional.empty();
    }

    public List<Contact> all(Connection c, String tenantId) {
        return Jdbc.query(c, SELECT + " WHERE tenant_id=? AND valid_to IS NULL ORDER BY role", ContactDirectory::map, tenantId);
    }

    private static final String SELECT = "SELECT role, name, designation, organisation, email, phone, mobile, address, resident,"
            + " valid_from FROM CMP_CONTACT";

    private static Contact map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Contact(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), Sql.bool(rs.getInt(9)), Jdbc.instant(rs, "valid_from"));
    }
}
