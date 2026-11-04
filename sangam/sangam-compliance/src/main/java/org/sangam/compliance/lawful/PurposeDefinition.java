// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.lawful;

import org.sangam.compliance.consent.ChildSafeguards.ProcessingFlag;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.retention.RetentionSchedule;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One specified purpose with its lawful basis, itemised personal data, the goods/services/uses it enables, its
 * exemptions and its retention. Basis, exemption, child-exemption and retention-class codes are codes of the tenant's
 * jurisdiction pack (IN: {@code CONSENT}, {@code S7B_STATE_BENEFIT}, {@code S17_4}, {@code FOURTH_SCH_A3},
 * {@code THIRD_SCH_ECOMMERCE} ...).
 *
 * @param names        language code → purpose name (the pack's default language at least)
 * @param descriptions language code → description of the goods, services or uses enabled
 * @param subGround    a sub-ground the basis requires (IN s.7(b): {@code i} or {@code ii})
 * @param standardsAttested the processing meets the standards the basis or exemption requires (IN: Second Schedule)
 */
public record PurposeDefinition(String code, Map<String, String> names, Map<String, String> descriptions,
                                List<DataItem> dataItems, String basis, String legalRef, String subGround,
                                String registerRef, boolean standardsAttested, Set<String> exemptions, String exemptionRef,
                                Set<String> childExemptions, Set<ProcessingFlag> flags,
                                RetentionSchedule.Trigger retentionTrigger, Duration retention, String retentionClass,
                                String recordClass, Duration recordRetention, String recordRetentionRef, int version,
                                boolean active) {

    /** @param sensitive a special category under the pack (IN: SPDI r.3, Aadhaar): masked in summaries */
    public record DataItem(String code, Map<String, String> names, boolean sensitive) {
    }

    public PurposeDefinition {
        names = names == null ? Map.of() : Map.copyOf(names);
        descriptions = descriptions == null ? Map.of() : Map.copyOf(descriptions);
        dataItems = dataItems == null ? List.of() : List.copyOf(dataItems);
        exemptions = exemptions == null ? Set.of() : Set.copyOf(exemptions);
        childExemptions = childExemptions == null ? Set.of() : Set.copyOf(childExemptions);
        flags = flags == null ? Set.of() : Set.copyOf(flags);
    }

    public Set<String> dataCategoryCodes() {
        return Set.copyOf(dataItems.stream().map(DataItem::code).toList());
    }

    public boolean consentBased(JurisdictionPack pack) {
        return pack.legalBasis(basis).map(JurisdictionPack.LegalBasis::consent).orElse(false);
    }

    public RetentionSchedule retentionSchedule(JurisdictionPack pack) {
        Duration period = null;
        Instant floor = null;
        if (retentionClass != null && !retentionClass.isBlank()) {
            JurisdictionPack.RetentionClass rc = pack.retentionClass(retentionClass)
                    .orElseThrow(() -> new IllegalStateException("unknown retention class " + retentionClass));
            period = Duration.ofDays(rc.periodDays());
            floor = rc.floorFromPhase() == null ? null : pack.phaseStart(rc.floorFromPhase());
        }
        return new RetentionSchedule(code, retentionTrigger, retention, period, floor, recordClass, recordRetention,
                recordRetentionRef, true);
    }

    public boolean retentionRequiredByLaw(JurisdictionPack pack) {
        return recordClass != null && !recordClass.isBlank() || recordRetentionRef != null && !recordRetentionRef.isBlank()
                || pack.legalBasis(basis).map(JurisdictionPack.LegalBasis::impliesRetentionByLaw).orElse(false);
    }

    public boolean noticeAndConsentDisapplied(JurisdictionPack pack) {
        return exemptions.stream().anyMatch(e -> pack.exemption(e).map(JurisdictionPack.Exemption::disappliesNoticeAndConsent).orElse(false));
    }

    public boolean erasureDisapplied(JurisdictionPack pack) {
        return exemptions.stream().anyMatch(e -> pack.exemption(e).map(JurisdictionPack.Exemption::disappliesErasure).orElse(false));
    }

    public String name(String languageCode) {
        String n = names.get(languageCode);
        return n != null ? n : names.getOrDefault("en", names.values().stream().findFirst().orElse(code));
    }

    public String description(String languageCode) {
        String n = descriptions.get(languageCode);
        return n != null ? n : descriptions.getOrDefault("en", descriptions.values().stream().findFirst().orElse(""));
    }
}
