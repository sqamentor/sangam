// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.accountability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.jurisdiction.JurisdictionRegistry;

/** M-087: the IN pack ships a data-processor agreement template that covers the s.8(2) / r.6(1)(f) clauses. */
class ProcessorAgreementTemplateTest {

    static final JurisdictionPack IN = JurisdictionRegistry.discover().find("IN").orElseThrow();

    @Test
    void theInPackShipsTheEnglishTemplate() {
        ProcessorAgreementTemplate t = ProcessorAgreementTemplate.bundled(IN, "en").orElseThrow();
        assertEquals("IN", t.jurisdiction());
        for (String clause : List.of("section 8(2)", "rule 6(1)(f)", "section 8(5)", "section 8(6)", "rule 7",
                "rule 8(3)", "section 16", "six hours", "72 hours")) {
            assertTrue(t.text().contains(clause), "the template cites " + clause);
        }
        assertTrue(t.placeholders().containsAll(List.of("fiduciaryName", "processorName", "breachNoticeHours",
                "processingLocations", "purposes")));
    }

    @Test
    void otherLanguagesAndPacksWithoutTemplateAreEmpty() {
        assertTrue(ProcessorAgreementTemplate.bundled(IN, "hi").isEmpty(), "English only in the first release");
        assertTrue(ProcessorAgreementTemplate.bundled(IN, "../x").isEmpty(), "no path tricks in the language code");
        JurisdictionPack gdpr = JurisdictionRegistry.discover().find("EU-GDPR").orElseThrow();
        assertTrue(ProcessorAgreementTemplate.bundled(gdpr, "en").isEmpty());
    }

    @Test
    void fillRefusesGapsAndFillsEveryPlaceholder() {
        ProcessorAgreementTemplate t = ProcessorAgreementTemplate.bundled(IN, "en").orElseThrow();
        Map<String, String> values = new HashMap<>();
        t.placeholders().forEach(p -> values.put(p, "v-" + p));
        values.put("breachNoticeHours", " ");
        ComplianceException e = assertThrows(ComplianceException.class, () -> t.fill(values));
        assertTrue(e.getMessage().contains("breachNoticeHours"));
        values.put("breachNoticeHours", "24");
        String out = t.fill(values);
        assertFalse(out.contains("{{"), "every placeholder is filled");
        assertTrue(out.contains("within 24 hours"));
    }
}
