// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * X.2.2.1: the government profile requires an Idempotency-Key on the power-table lifecycle. The contract split
 * power-table.transition into power-table.submit (submit) and power-table.decide (approve, reject), so the catalogue
 * names both; a name the contract no longer declares would silently stop requiring a key.
 */
class CommandCatalogGovernmentTest {

    final CommandCatalog catalog = new CommandCatalog();

    @Test
    void submitApproveAndRejectRequireAKeyInTheGovernmentProfile() {
        assertTrue(CommandCatalog.GOVERNMENT_REQUIRED.contains("power-table.submit"));
        assertTrue(CommandCatalog.GOVERNMENT_REQUIRED.contains("power-table.decide"));
        assertFalse(CommandCatalog.GOVERNMENT_REQUIRED.contains("power-table.transition"));
        for (String route : new String[] {"submit", "approve", "reject"}) {
            CommandCatalog.Command c = catalog.match("POST", "/api/v1/admin/power-tables/pt-1/" + route)
                    .orElseThrow(() -> new AssertionError(route + " is not an idempotent command")).command();
            assertTrue(c.requiredInGovernmentProfile(), route);
            assertEquals("submit".equals(route) ? "power-table.submit" : "power-table.decide", c.commandType(), route);
        }
    }

    @Test
    void everyGovernmentRequiredTypeIsAnIdempotentCommandOfTheContract() {
        Set<String> declared = catalog.commands().stream().map(CommandCatalog.Command::commandType)
                .collect(Collectors.toSet());
        Set<String> missing = new TreeSet<>(CommandCatalog.GOVERNMENT_REQUIRED);
        missing.removeAll(declared);
        assertEquals(Set.of(), missing, "names in GOVERNMENT_REQUIRED that the contract does not declare");
    }
}
