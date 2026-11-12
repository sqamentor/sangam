// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** SL-104 (ADR-D-20): the cell's jurisdiction and residency come from configuration; there is no country default. */
class CellJurisdictionConfigTest {

    @Test
    void aCellWithoutAConfiguredJurisdictionFailsAndNamesTheProperty() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CellWiring.jurisdictionAndResidency(new MockEnvironment()));
        assertTrue(e.getMessage().contains("sangam.cell.jurisdiction"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> CellWiring.jurisdictionAndResidency(
                new MockEnvironment().withProperty("sangam.cell.jurisdiction", " ")));
    }

    @Test
    void residencyDefaultsToTheConfiguredJurisdiction() {
        assertArrayEquals(new String[]{"XA", "XA"}, CellWiring.jurisdictionAndResidency(
                new MockEnvironment().withProperty("sangam.cell.jurisdiction", "XA")));
        assertArrayEquals(new String[]{"XA", "XA"}, CellWiring.jurisdictionAndResidency(
                new MockEnvironment().withProperty("sangam.cell.jurisdiction", "XA").withProperty("sangam.cell.residency", "")));
    }

    @Test
    void anExplicitResidencyWins() {
        assertArrayEquals(new String[]{"XA", "XB"}, CellWiring.jurisdictionAndResidency(new MockEnvironment()
                .withProperty("sangam.cell.jurisdiction", "XA").withProperty("sangam.cell.residency", "XB")));
    }
}