// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.transfer;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceException.Code;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.ports.CompliancePorts.GeoResolver;
import org.sangam.compliance.support.Sql;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.net.URI;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Cross-border transfer control; every decision is recorded in {@code CMP_TRANSFER_DECISION} and audited. Rules in
 * order: home country → allowed; negative list → denied; government order → denied; localised data category → denied
 * (a public record with an approval reference excepted); deny-by-default without approval → denied; recipient's
 * equivalent protection not attested → denied; otherwise allowed. IN: DPDP s.16, r.15, r.13(4); SPDI r.7; Public
 * Records Act s.4; MeitY cloud terms; UIDAI ADV (CMP-042, CMP-075, CMP-080, CMP-125, CMP-131, CMP-145).
 */
public final class CrossBorderTransferGuard {

    /**
     * @param mechanism              transfer tool under an adequacy-or-safeguard pack (e.g. EU {@code SCC_2021_914}, {@code BCR},
     *                               or an Art. 49 derogation code)
     * @param impactAssessmentRef    transfer impact assessment reference when the pack requires one with safeguards
     * @param recipientConditionMet  the recipient meets the adequacy condition (e.g. certified under the EU-US DPF)
     */
    public record TransferRequest(String destinationCountry, String recipient, String purposeCode, Set<String> dataCategories,
                                  String approvalRef, boolean recipientSameProtectionAttested, String mechanism,
                                  String impactAssessmentRef, boolean recipientConditionMet) {
        public TransferRequest(String destinationCountry, String recipient, String purposeCode, Set<String> dataCategories,
                               String approvalRef, boolean recipientSameProtectionAttested) {
            this(destinationCountry, recipient, purposeCode, dataCategories, approvalRef, recipientSameProtectionAttested, null, null, false);
        }
    }

    public record Decision(String decisionId, boolean allowed, String rule, String reason) {
    }

    private final Dialect dialect;
    private final ComplianceAudit audit;
    private final ComplianceConfig.Provider config;
    private final DataLocalisationPolicy localisation;
    private final GeoResolver geo;

    public CrossBorderTransferGuard(Dialect dialect, ComplianceAudit audit, ComplianceConfig.Provider config,
                                    DataLocalisationPolicy localisation, GeoResolver geo) {
        this.dialect = dialect;
        this.audit = audit;
        this.config = config;
        this.localisation = localisation;
        this.geo = geo == null ? GeoResolver.NONE : geo;
    }

    public Decision evaluate(Connection c, String tenantId, TransferRequest r, String actor) {
        ComplianceConfig cfg = config.forTenant(tenantId);
        String cc = r.destinationCountry() == null ? "" : r.destinationCountry().trim().toUpperCase(Locale.ROOT);
        String rule;
        String reason;
        boolean allowed;
        List<String> localised = new ArrayList<>();
        if (r.dataCategories() != null) {
            for (String d : r.dataCategories()) {
                if (localisation.localised(tenantId, d)) {
                    localised.add(d.toUpperCase(Locale.ROOT));
                }
            }
        }
        boolean hasApproval = r.approvalRef() != null && !r.approvalRef().isBlank();
        if (cc.isEmpty()) {
            allowed = false;
            rule = "UNKNOWN_DESTINATION";
            reason = "destination country unknown: denied (fail closed)";
        } else if (cfg.pack.isHome(cc)) {
            allowed = true;
            rule = "DOMESTIC";
            reason = "destination in the home country";
        } else if (cfg.negativeListCountries.contains(cc)) {
            allowed = false;
            rule = "NEGATIVE_LIST";
            reason = cc + " is on the restricted-country list";
        } else if (cfg.orderRestrictedCountries.contains(cc)) {
            allowed = false;
            rule = "AUTHORITY_ORDER";
            reason = cc + " is restricted by an order of the government";
        } else if (!localised.isEmpty() && !(localised.equals(List.of("PUBLIC_RECORD")) && hasApproval)) {
            allowed = false;
            rule = "LOCALISED_CATEGORY";
            reason = "localised data categories " + localised + " may not leave the home country";
        } else if ("NONE".equals(cfg.pack.transferMode())) {
            allowed = true;
            rule = "NO_TRANSFER_RESTRICTION";
            reason = "the jurisdiction does not restrict transfers abroad (contractual safeguards still apply)";
        } else if ("ADEQUACY_OR_SAFEGUARD".equals(cfg.pack.transferMode())) {
            var adequate = cfg.pack.adequateDestinations().stream().filter(a -> a.country().equals(cc)).findFirst();
            var safeguard = cfg.pack.transferSafeguards().stream().filter(s -> s.code().equals(r.mechanism())).findFirst();
            var derogation = cfg.pack.transferDerogations().stream().filter(s -> s.code().equals(r.mechanism())).findFirst();
            if (adequate.isPresent() && (adequate.get().condition() == null || r.recipientConditionMet())) {
                allowed = true;
                rule = "ADEQUACY";
                reason = "adequacy decision " + adequate.get().citation();
            } else if (safeguard.isPresent()) {
                boolean tiaMissing = cfg.pack.transferImpactAssessmentRequired()
                        && (r.impactAssessmentRef() == null || r.impactAssessmentRef().isBlank());
                allowed = !tiaMissing;
                rule = tiaMissing ? "SAFEGUARD_WITHOUT_IMPACT_ASSESSMENT" : "APPROPRIATE_SAFEGUARD";
                reason = safeguard.get().code() + " (" + safeguard.get().citation() + ")" + (tiaMissing ? ": transfer impact assessment missing" : "");
            } else if (derogation.isPresent()) {
                allowed = true;
                rule = "DEROGATION";
                reason = derogation.get().code() + " (" + derogation.get().citation() + ")";
            } else {
                allowed = false;
                rule = "NO_TRANSFER_MECHANISM";
                reason = cc + " has no adequacy finding" + (adequate.isPresent() ? " for this recipient" : "")
                        + " and no safeguard or derogation was given";
            }
        } else if (cfg.denyCrossBorderByDefault && !hasApproval) {
            allowed = false;
            rule = "DENY_BY_DEFAULT";
            reason = "transfers abroad need an approval reference for this tenant";
        } else if (!r.recipientSameProtectionAttested()) {
            allowed = false;
            rule = "SAME_PROTECTION";
            reason = "the recipient's equivalent protection is not attested";
        } else {
            allowed = true;
            rule = "ALLOWED_WITH_APPROVAL";
            reason = hasApproval ? "approved: " + r.approvalRef() : "allowed by configuration";
        }
        String id = Sql.newId();
        Instant now = Sql.now(c, dialect);
        Jdbc.update(c, "INSERT INTO CMP_TRANSFER_DECISION(tenant_id, decision_id, destination_country, recipient, purpose_code,"
                        + " data_categories_json, approval_ref, allowed, rule_id, reason, decided_by, decided_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                tenantId, id, cc, r.recipient(), r.purposeCode(), Sql.jsonList(r.dataCategories() == null ? Set.of() : new java.util.TreeSet<>(r.dataCategories())),
                r.approvalRef(), allowed, rule, reason, actor, now);
        audit.organisation(c, tenantId, ComplianceAudit.TRANSFER_DECIDED, Sql.kv("decision", id, "country", cc, "allowed", allowed,
                "rule", rule, "purpose", r.purposeCode(), "actor", actor));
        return new Decision(id, allowed, rule, reason);
    }

    /** {@link #evaluate} or {@code TRANSFER_NOT_PERMITTED}. */
    public Decision require(Connection c, String tenantId, TransferRequest r, String actor) {
        Decision d = evaluate(c, tenantId, r, actor);
        if (!d.allowed()) {
            throw new ComplianceException(Code.TRANSFER_NOT_PERMITTED, d.reason());
        }
        return d;
    }

    /** Egress check for a connector endpoint: resolves the endpoint's country and evaluates the transfer. */
    public Decision requireEndpoint(Connection c, String tenantId, URI endpoint, String purposeCode, Set<String> dataCategories,
                                    String approvalRef, boolean recipientAttested, String actor) {
        Optional<String> cc = geo.countryOf(endpoint);
        return require(c, tenantId, new TransferRequest(cc.orElse(""), endpoint.getHost(), purposeCode, dataCategories, approvalRef,
                recipientAttested), actor);
    }

    public long deniedCount(Connection c, String tenantId, Instant from, Instant to) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM CMP_TRANSFER_DECISION WHERE tenant_id=? AND allowed=0 AND decided_at>=? AND decided_at<?",
                rs -> rs.getLong(1), tenantId, from, to).orElse(0L);
    }

    public long allowedAbroadCount(Connection c, String tenantId, Instant from, Instant to) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM CMP_TRANSFER_DECISION WHERE tenant_id=? AND allowed=1 AND destination_country<>?"
                + " AND decided_at>=? AND decided_at<?", rs -> rs.getLong(1), tenantId, config.forTenant(tenantId).pack.homeCountry(), from, to).orElse(0L);
    }
}
