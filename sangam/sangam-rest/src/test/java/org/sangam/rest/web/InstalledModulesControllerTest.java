// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.RequestContext;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Owner decision 399 (Q3): GET /api/v1/me/capabilities lists exactly the modules whose controllers are wired. */
class InstalledModulesControllerTest {

    private static final RequestContext CTX = new RequestContext("t1", "u1", Set.of("sangam-user"), null, null, null, null,
            null, "DEV_TOKEN");

    @Test
    void everyListedControllerExistsAndIsARestController() throws Exception {
        assertEquals(Set.of("ai", "billing", "case", "channels", "compliance", "field", "geo", "ledger", "programme",
                "registry", "resources"), InstalledModulesController.MODULE_CONTROLLERS.keySet());
        for (List<String> names : InstalledModulesController.MODULE_CONTROLLERS.values()) {
            for (String n : names) {
                Class<?> c = Class.forName(n, false, getClass().getClassLoader());
                assertNotNull(c.getAnnotation(RestController.class), n + " is not a @RestController");
            }
        }
    }

    @Test
    void aModuleIsInstalledOnlyWhenAllItsControllersAreWired() {
        String m = "org.sangam.rest.modules.";
        assertEquals(List.of(), InstalledModulesController.installed(Set.of()));
        assertEquals(List.of("ledger"), InstalledModulesController.installed(Set.of(m + "ledger.LedgerController",
                m + "cases.CaseFileController")));   // case needs CaseFile, Meeting and Reference controllers
        assertEquals(List.of("case", "geo"), InstalledModulesController.installed(Set.of(m + "geo.web.GeoController",
                m + "cases.CaseFileController", m + "cases.MeetingController", m + "cases.ReferenceController")));
    }

    @Test
    void readsTheWiredControllersOfTheContextWithoutCreatingThem() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            for (String n : List.of("org.sangam.rest.modules.ledger.LedgerController",
                    "org.sangam.rest.compliance.ComplianceController")) {
                RootBeanDefinition d = new RootBeanDefinition(Class.forName(n));
                d.setLazyInit(true);   // never instantiated: its ports are not in this context
                ctx.registerBeanDefinition(n, d);
            }
            ctx.registerBean(Unrelated.class);
            ctx.registerBean(InstalledModulesController.class);
            ctx.refresh();
            InstalledModulesController.Capabilities c = ctx.getBean(InstalledModulesController.class).capabilities(CTX);
            assertEquals(List.of("compliance", "ledger"), c.modules());
            assertTrue(ctx.getBeanFactory().getSingleton("org.sangam.rest.modules.ledger.LedgerController") == null,
                    "the module controller was created");
        }
    }

    @RestController
    @RequestMapping("/api/v1/unrelated")
    static class Unrelated {
        @GetMapping
        String get() {
            return "";
        }
    }
}
