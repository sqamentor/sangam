// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.logs;

import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceException.Code;
import org.sangam.compliance.config.ComplianceConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Log retention: the effective retention is the longest of the configured value and the pack's log limits
 * ({@code logRetentionMinDays}, {@code incidentLogRetentionDays}); when the pack sets an incident-log floor, logs must
 * reside in the jurisdiction's home country (IN: CERT-In para (iv) 180 days in India; DPDP r.6(1)(e), r.8(3) one year;
 * CMP-058, CMP-066, CMP-091, CMP-092, CMP-106).
 */
public final class LogRetentionPolicy {

    private LogRetentionPolicy() {
    }

    public static Duration effective(ComplianceConfig cfg) {
        return Duration.ofDays(cfg.effectiveLogRetentionDays());
    }

    public static Instant purgeAfter(ComplianceConfig cfg, Instant segmentEnd) {
        return segmentEnd.plus(effective(cfg));
    }

    public static boolean residencyRequired(ComplianceConfig cfg) {
        return cfg.pack.hasLimit(ComplianceConfig.INCIDENT_LOG_MIN);
    }

    public static void requireResidency(ComplianceConfig cfg, String country, String what) {
        if (!residencyRequired(cfg)) {
            return;
        }
        String cc = country == null ? "" : country.trim().toUpperCase(Locale.ROOT);
        if (!cfg.pack.homeCountry().equals(cc)) {
            throw new ComplianceException(Code.LOCALISATION_VIOLATION, what + " must be located in " + cfg.pack.homeCountry() + " ("
                    + cfg.pack.limitCitation(ComplianceConfig.INCIDENT_LOG_MIN) + "); found '" + cc + "'");
        }
    }

    public static boolean compliant(ComplianceConfig cfg, int retentionDays, String country) {
        int floor = Math.max(cfg.pack.limit(ComplianceConfig.LOG_MIN, 0), cfg.pack.limit(ComplianceConfig.INCIDENT_LOG_MIN, 0));
        boolean residency = !residencyRequired(cfg) || cfg.pack.homeCountry().equalsIgnoreCase(country == null ? "" : country);
        return retentionDays >= floor && residency;
    }
}
