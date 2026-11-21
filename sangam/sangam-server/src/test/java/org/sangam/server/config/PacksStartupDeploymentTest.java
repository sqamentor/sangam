// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

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
}
