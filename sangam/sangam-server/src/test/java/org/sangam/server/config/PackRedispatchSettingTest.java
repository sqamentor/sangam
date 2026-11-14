package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sangam.integration.packs.PackReconciliationHooks;
import org.springframework.mock.env.MockEnvironment;

/**
 * Decision 290: sangam.integration.reconciliation.max-redispatches bounds automatic re-dispatches; unset is the
 * default 3, a positive integer is used as given, anything else refuses the start naming the property. Written NOT_RUN.
 */
class PackRedispatchSettingTest {

    @Test
    void unsetGivesTheDefault() {
        assertEquals(3, PackReconciliationHooks.DEFAULT_MAX_REDISPATCHES);
        assertEquals(PackReconciliationHooks.DEFAULT_MAX_REDISPATCHES,
                IntegrationWiring.maxRedispatches(new MockEnvironment()));
    }

    @Test
    void aPositiveIntegerIsUsed() {
        assertEquals(1, IntegrationWiring.maxRedispatches(
                new MockEnvironment().withProperty(IntegrationWiring.MAX_REDISPATCHES_PROPERTY, "1")));
        assertEquals(7, IntegrationWiring.maxRedispatches(
                new MockEnvironment().withProperty(IntegrationWiring.MAX_REDISPATCHES_PROPERTY, " 7 ")));
    }

    @Test
    void anythingElseRefusesTheStart() {
        for (String bad : new String[] {"0", "-1", "", " ", "three", "2.5", "99999999999"}) {
            MockEnvironment env = new MockEnvironment().withProperty(IntegrationWiring.MAX_REDISPATCHES_PROPERTY, bad);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> IntegrationWiring.maxRedispatches(env), bad);
            assertTrue(e.getMessage().contains(IntegrationWiring.MAX_REDISPATCHES_PROPERTY), e.getMessage());
        }
    }
}
