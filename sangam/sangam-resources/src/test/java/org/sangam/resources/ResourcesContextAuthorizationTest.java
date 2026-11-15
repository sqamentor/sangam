// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.security.EnvironmentPolicy;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.dialect.ResourcesDialect;
import org.sangam.resources.port.Visibility;

/**
 * RM-27 / RM-28: the resources context has no permissive default. It used to start with a guard that allowed every
 * object check and a visibility that showed every row of the tenant, which the server reached whenever sangam-security
 * was off (a warning and nothing else). Now a context without a stated guard and visibility does not build, and the
 * unguarded form exists only for a validated development environment (owner decisions 143 and 152).
 */
class ResourcesContextAuthorizationTest {

    static final SecurityIdentity ACTOR = SecurityIdentity.system("t1", null);

    static EnvironmentPolicy.Resolution environment(String flag, String... profiles) {
        return EnvironmentPolicy.resolve(flag, "test", EnvironmentPolicy.signals(List.of(profiles)));
    }

    static ResourcesContext.Builder builder() {
        return ResourcesContext.builder(ResourcesDialect.H2);
    }

    @Test
    void aContextWithoutStatedAuthorizationDoesNotBuild() {
        IllegalStateException none = assertThrows(IllegalStateException.class, () -> builder().build());
        assertTrue(none.getMessage().contains("no object guard"), none.getMessage());
        IllegalStateException guardOnly = assertThrows(IllegalStateException.class,
                () -> builder().guard((c, a, t, p, id) -> {
                    throw new IllegalStateException("denied");
                }).build());
        assertTrue(guardOnly.getMessage().contains("no list visibility"), guardOnly.getMessage());
        IllegalStateException visibilityOnly = assertThrows(IllegalStateException.class,
                () -> builder().visibility(Visibility.TENANT_ONLY).build());
        assertTrue(visibilityOnly.getMessage().contains("no object guard"), visibilityOnly.getMessage());
        assertThrows(IllegalStateException.class, () -> builder().guard(null).visibility(null).build(),
                "null is not a statement");
    }

    @Test
    void theStatedGuardAndVisibilityAreTheOnesUsed() {
        List<String> asked = new ArrayList<>();
        Visibility none = (c, a, t, p, alias) -> new SqlPredicate("1=0", List.of());
        ResourcesContext ctx = builder().guard((c, a, t, p, id) -> asked.add(t + "|" + p + "|" + id)).visibility(none)
                .build();
        ctx.guard().require(null, ACTOR, ResourcesContext.RT_BOOKING, ResourcesContext.P_APPROVE, "b-1");
        assertEquals(List.of("RES_BOOKING|APPROVE|b-1"), asked);
        assertEquals("1=0", ctx.visibility().visible(null, ACTOR, ResourcesContext.RT_BOOKING, ResourcesContext.P_READ, "b")
                .sql());
    }

    @Test
    void withoutAuthorizationIsRefusedOutsideAValidatedDevelopmentEnvironment() {
        for (EnvironmentPolicy.Resolution refused : List.of(
                environment("true"),                    // production
                environment("true", "dev"),             // production wins over a development profile (decision 198)
                environment("false"),                   // staging: not production, not development either
                environment("false", "postgres"))) {    // a neutral profile is no development signal
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> builder().withoutAuthorization(refused), refused.describe());
            assertTrue(e.getMessage().contains(refused.mode().name()), e.getMessage());
        }
        assertThrows(NullPointerException.class, () -> builder().withoutAuthorization(null),
                "an unknown environment is not a development environment");
        // a missing switch never resolves at all, so no Resolution exists to pass
        assertThrows(EnvironmentPolicy.ConfigurationError.class, () -> environment(null, "dev"));
        // a refused attempt leaves the builder without authorization: it still does not build
        ResourcesContext.Builder b = builder();
        assertThrows(IllegalStateException.class, () -> b.withoutAuthorization(environment("true")));
        assertThrows(IllegalStateException.class, b::build);
    }

    @Test
    void aValidatedDevelopmentEnvironmentMayRunUnguarded() {
        for (String profile : List.of("dev", "h2", "test", "local")) {
            ResourcesContext ctx = assertDoesNotThrow(
                    () -> builder().withoutAuthorization(environment("false", profile)).build(), profile);
            assertDoesNotThrow(() -> ctx.guard().require(null, ACTOR, ResourcesContext.RT_RESOURCE,
                    ResourcesContext.P_ADMIN, "r-1"));
            assertNotNull(ctx.visibility().visible(null, ACTOR, ResourcesContext.RT_RESOURCE, ResourcesContext.P_READ, "r"));
        }
        assertEquals(Map.of(), builder().withoutAuthorization(environment("false", "test")).build().settings());
    }
}
