// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.mock.env.MockEnvironment;

/**
 * Register E-33: what the start-up runners of the packs and of the first administrators do, without a server.
 * <p>
 * (d) Spring Boot sorts the runners with the order comparator and keeps no order among runners that declare none
 * (they are collected in an identity map), so "the tenant exists before its packs are deployed" must be declared:
 * the first-admins runner sorts before every runner without an order, the pack runner among them.
 * <p>
 * (c) The start-up deployment ({@code PacksWiring.deployAtStart}) deploys only for ids that are tenants of the
 * installation; the switch, its profile default, the default tenant and the handling of a failure are as before.
 */
class PacksStartupDeploymentTest {

    @Test
    void theFirstAdminsRunnerSortsBeforeEveryRunnerWithoutAnOrder() {
        ApplicationRunner firstAdmins = new FirstAdminBootstrap().sangamFirstAdmins(null, null, new MockEnvironment());
        ApplicationRunner unordered = args -> { };
        assertTrue(AnnotationAwareOrderComparator.INSTANCE.compare(firstAdmins, unordered) < 0,
                "the first-admins runner declares an order before the runners that declare none");
        for (int i = 0; i < 2; i++) {
            List<ApplicationRunner> runners = new ArrayList<>(i == 0 ? List.of(unordered, firstAdmins)
                    : List.of(firstAdmins, unordered));
            runners.sort(AnnotationAwareOrderComparator.INSTANCE);
            assertTrue(runners.get(0) == firstAdmins, "first whichever way the two were collected");
        }
    }

    // ------------------------------------------------------------------ (c) which ids the start-up deployment takes

    private static List<String> deployed(MockEnvironment env, java.util.function.Predicate<String> isTenant) {
        List<String> calls = new ArrayList<>();
        List<String> returned = PacksWiring.deployAtStart(calls::add, env, isTenant);
        assertEquals(calls, returned, "the ids it reports are the ids it deployed for");
        return calls;
    }

    private static MockEnvironment on(String tenants) {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.packs.deploy-on-start", "true");
        return tenants == null ? env : env.withProperty("sangam.packs.tenants", tenants);
    }

    @Test
    void aListedIdThatIsNoTenantIsSkippedAndTheTenantsBesideItAreDeployed() {
        java.util.Set<String> directory = java.util.Set.of("GOI.FIN", "RJ.PHED");
        assertEquals(List.of("GOI.FIN", "RJ.PHED"), deployed(on("GOI.OTHER, GOI.FIN,RJ.PHED"), directory::contains),
                "in the order listed, without the id that is no tenant");
        assertEquals(List.of(), deployed(on("GOI.OTHER"), directory::contains), "no tenant at all: nothing is deployed");
        assertEquals(List.of("GOI.FIN"), deployed(on("GOI.FIN,GOI.FIN, GOI.FIN"), directory::contains), "each id once");
        assertEquals(List.of(), deployed(on("goi.fin"), directory::contains), "a tenant id is matched exactly");
    }

    private static MockEnvironment h2(MockEnvironment env) {
        env.setActiveProfiles("h2");
        return env;
    }

    @Test
    void underProfileH2TheDefaultTenantIsCheckedLikeAListedOne() {
        MockEnvironment env = h2(on(null).withProperty("sangam.security.dev-token.tenant", "GOI.FIN"));
        assertEquals(List.of("GOI.FIN"), deployed(env, "GOI.FIN"::equals), "the dev-token tenant, when it is a tenant");
        assertEquals(List.of(), deployed(env, id -> false), "and not when it is none");
        assertEquals(List.of("dev"), deployed(h2(on(null)), "dev"::equals), "without a dev-token tenant the id is dev");
        assertEquals(List.of("dev"), deployed(h2(new MockEnvironment()), "dev"::equals),
                "under h2 the deployment is on by default, and so is its default tenant");
    }

    /**
     * Owner decision E-33 follow-up 2 (EV-M-0966, "Refuse outside tests"): only profile h2 falls back to the dev-token
     * tenant. Any other start with {@code sangam.packs.deploy-on-start=true} and no {@code sangam.packs.tenants} stops,
     * with an error that names both settings; nothing is deployed, not even for a dev-token tenant that exists.
     */
    @Test
    void outsideProfileH2AStartThatDeploysWithoutATenantListIsRefused() {
        for (String[] profiles : List.of(new String[0], new String[]{"postgres", "oidc"}, new String[]{"dev"})) {
            MockEnvironment env = on(null).withProperty("sangam.security.dev-token.tenant", "GOI.FIN");
            env.setActiveProfiles(profiles);
            List<String> calls = new ArrayList<>();
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> PacksWiring.deployAtStart(calls::add, env, id -> true), List.of(profiles).toString());
            assertTrue(refused.getMessage().contains("sangam.packs.deploy-on-start")
                    && refused.getMessage().contains("sangam.packs.tenants"), "names both settings: " + refused.getMessage());
            assertEquals(List.of(), calls, "nothing is deployed, the dev-token tenant included");
        }
        MockEnvironment off = new MockEnvironment();
        off.setActiveProfiles("postgres");
        assertEquals(List.of(), deployed(off, id -> true), "without deploy-on-start nothing is refused: the default is off");
        MockEnvironment empty = on("");
        empty.setActiveProfiles("postgres");
        assertEquals(List.of(), deployed(empty, id -> true), "an empty list is a list: nothing deployed, nothing refused");
        MockEnvironment listed = on("GOI.FIN");
        listed.setActiveProfiles("postgres");
        assertEquals(List.of("GOI.FIN"), deployed(listed, id -> true), "a listed tenant is deployed as before");
    }

    @Test
    void withoutATenantDirectoryEveryListedIdIsDeployedAsBefore() {
        assertEquals(List.of("a", "b.c"), deployed(on("a, b.c"), null), "sangam-security absent: nothing to check against");
    }

    @Test
    void theSwitchAndItsProfileDefaultAreUnchanged() {
        assertEquals(List.of(), deployed(new MockEnvironment().withProperty("sangam.packs.tenants", "a"), id -> true),
                "off by default outside profile h2");
        MockEnvironment h2 = new MockEnvironment().withProperty("sangam.packs.tenants", "a");
        h2.setActiveProfiles("h2");
        assertEquals(List.of("a"), deployed(h2, id -> true), "on by default under profile h2");
        h2.setProperty("sangam.packs.deploy-on-start", "false");
        assertEquals(List.of(), deployed(h2, id -> true), "switched off under h2");
        assertEquals(List.of(), deployed(on(""), id -> true), "an empty list is a list: no default tenant is taken");
    }

    @Test
    void aFailedDeploymentOrAFailedLookUpOfOneIdDoesNotStopTheOthers() {
        List<String> calls = new ArrayList<>();
        List<String> returned = PacksWiring.deployAtStart(id -> {
            calls.add(id);
            if (id.equals("a")) {
                throw new IllegalStateException("pack rejected");
            }
        }, on("a,b,c"), id -> {
            if (id.equals("b")) {
                throw new IllegalStateException("directory unavailable");
            }
            return true;
        });
        assertEquals(List.of("a", "c"), calls, "b could not be looked up and is not deployed; c is");
        assertEquals(List.of("a", "c"), returned);
    }
}
