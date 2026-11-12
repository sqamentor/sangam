// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.onboarding.deps.PackDependencyResolver;
import org.sangam.onboarding.deps.PackRequirementsCatalog;
import org.sangam.onboarding.deps.PlatformInfo;
import org.sangam.packs.spi.PackRegistry;

/**
 * SL-125 (fail closed, owner 2026-11-12): every shipped pack whose steps reach the fiscal period resolver (a ledger or
 * billing step without an explicit period) requires {@code locale.fiscal}, so onboarding refuses it
 * (PROFILE_KEY_MISSING) for a tenant without a fiscal year instead of the run failing later. A pack that never needs a
 * fiscal year (the US health claim pack posts on the explicit period OPEN) does not require it.
 */
class FiscalPackRequirementsTest {

    private static PackDependencyResolver resolver() {
        return new PackDependencyResolver(PackRegistry.discover(), PackRequirementsCatalog.scan(FiscalPackRequirementsTest.class.getClassLoader()),
                new PlatformInfo("0.1.0-SNAPSHOT", "2.2.0", Map.of("sangam-config", "0.1.0-SNAPSHOT", "sangam-ledger", "0.1.0-SNAPSHOT")));
    }

    @Test
    void everyPackReachingTheFiscalResolverRequiresLocaleFiscal() {
        for (String pack : List.of("gov.ifms.bill", "gov.ifms.budget-allocation", "gov.ifms.budget-formulation",
                "gov.ifms.loans-advances", "gov.ifms.reappropriation", "gov.ifms.sna-sparsh", "gov.ifms.surrender-lapse",
                "gov.ifms.budget-reappropriation", "gov.intl.pfm-execution", "universal.billing-cycle",
                "sec.util.meter-to-cash", "sec.util.new-connection")) {
            var r = resolver().resolve(List.of(pack), Map.of(), Map.of(), false);
            assertTrue(r.profileKeys().containsKey("locale.fiscal"), pack + " must require locale.fiscal: " + r.profileKeys());
            assertTrue(r.profileKeys().get("locale.fiscal").contains(pack), pack + ": " + r.profileKeys());
        }
    }

    @Test
    void theUsHealthClaimPackDoesNotNeedAFiscalYear() {
        var r = resolver().resolve(List.of("sec.ushealth.claim"), Map.of(), Map.of(), false);
        assertFalse(r.profileKeys().containsKey("locale.fiscal"), r.profileKeys()::toString);
    }
}
