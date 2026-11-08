// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.licence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Regression for SG-26: a consumer BOM (Spring Boot 4 manages liquibase 5.0.4) must not run FSL Liquibase 5. */
class LiquibaseLicenceGuardTest {

    @Test
    void allowsFourX() {
        assertDoesNotThrow(() -> LiquibaseLicenceGuard.check("4.33.0"));
    }

    @Test
    void refusesFiveXAndUnknown() {
        assertThrows(IllegalStateException.class, () -> LiquibaseLicenceGuard.check("5.0.4"));
        assertThrows(IllegalStateException.class, () -> LiquibaseLicenceGuard.check("DEV"));
        assertThrows(IllegalStateException.class, () -> LiquibaseLicenceGuard.check(null));
    }
}
