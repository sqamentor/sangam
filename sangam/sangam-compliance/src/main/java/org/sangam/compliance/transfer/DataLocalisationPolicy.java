// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.transfer;

import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceException.Code;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionPack;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Data localisation as configuration: home country, hosting categories acceptable for the public sector, a required
 * hosting category (e.g. a state data centre), hosting accreditation validity, localised data categories and log
 * residency, all from the pack and the tenant configuration (IN: MeitY cloud terms and empanelment / STQC, CERT-In
 * government guidelines Annexure 5.1, Public Records Act s.4, DPDP r.13(4), CERT-In para (iv), UIDAI ADV; CMP-075,
 * CMP-091, CMP-104, CMP-131, CMP-132, CMP-142, CMP-145, CMP-150).
 */
public final class DataLocalisationPolicy {

    private final ComplianceConfig.Provider config;

    public DataLocalisationPolicy(ComplianceConfig.Provider config) {
        this.config = config;
    }

    public boolean isHome(String tenantId, String country) {
        return country != null && config.forTenant(tenantId).pack.homeCountry().equals(country.trim().toUpperCase(Locale.ROOT));
    }

    public void requireHome(String tenantId, String country, String what) {
        if (!isHome(tenantId, country)) {
            throw new ComplianceException(Code.LOCALISATION_VIOLATION, what + " must be located in "
                    + config.forTenant(tenantId).pack.homeCountry() + "; found '" + country + "'");
        }
    }

    public boolean localised(String tenantId, String dataCategory) {
        return dataCategory != null && config.forTenant(tenantId).localisedDataCategories.contains(dataCategory.toUpperCase(Locale.ROOT));
    }

    public List<String> findings(String tenantId, LocalDate today) {
        ComplianceConfig cfg = config.forTenant(tenantId);
        JurisdictionPack pack = cfg.pack;
        List<String> f = new ArrayList<>();
        if (cfg.publicSector() && !pack.homeCountry().equals(cfg.hostingCountry)) {
            f.add("hosting country is " + cfg.hostingCountry + ", not " + pack.homeCountry());
        }
        Optional<JurisdictionPack.HostingCategory> cat = pack.hostingCategory(cfg.hostingCategory);
        if (cat.isEmpty()) {
            f.add("hosting category " + cfg.hostingCategory + " is not declared / not defined by the pack");
        } else if (cfg.publicSector() && !cat.get().acceptableForPublicSector()) {
            f.add("hosting category " + cfg.hostingCategory + " is not acceptable for the public sector (" + cat.get().citation() + ")");
        }
        if (cat.isPresent() && cat.get().acceptableForPublicSector() && !cat.get().citation().isBlank() && cfg.accreditationValidUntil != null
                && cfg.accreditationValidUntil.isBefore(today)) {
            f.add("hosting accreditation " + cfg.accreditationRef + " expired on " + cfg.accreditationValidUntil);
        }
        if (!cfg.requiredHostingCategory.isBlank() && !cfg.requiredHostingCategory.equals(cfg.hostingCategory)) {
            f.add("hosting category " + cfg.requiredHostingCategory + " is required, but the deployment uses " + cfg.hostingCategory);
        }
        if (cfg.pack.hasLimit(ComplianceConfig.INCIDENT_LOG_MIN) && !pack.homeCountry().equals(cfg.logRegion)) {
            f.add("log region is " + cfg.logRegion + ", not " + pack.homeCountry());
        }
        if (cfg.publicSector() && !cfg.denyCrossBorderByDefault) {
            f.add("cross-border transfer is not denied by default for a public-sector deployment");
        }
        for (String required : pack.localisedCategories()) {
            if (!cfg.localisedDataCategories.contains(required.toUpperCase(Locale.ROOT))) {
                f.add("data category " + required + " (pack default) is not marked as localised");
            }
        }
        return f;
    }
}
