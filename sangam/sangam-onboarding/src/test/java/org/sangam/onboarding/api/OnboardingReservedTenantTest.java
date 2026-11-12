// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** SL-86: the reserved shared tenant key ('-') can never be onboarded as a tenant. */
class OnboardingReservedTenantTest {

    private static OnboardingRequest request(String tenant) {
        return new OnboardingRequest(new OnboardingRequest.TenantSpec(tenant, "name", Map.of(), "state-1", null, null, null,
                null, null, null, null), null, null, null, null, null, null, null, null);
    }

    @Test
    void theSharedKeyIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> request("-"));
    }

    @Test
    void aRealTenantIsAccepted() {
        assertEquals("RJ", request("RJ").tenantId());
    }
}
