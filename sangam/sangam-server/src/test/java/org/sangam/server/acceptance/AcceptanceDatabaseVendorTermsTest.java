// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance;

import org.junit.jupiter.api.Test;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decision 122 (RM-35): the SQL Server acceptance database passes the EULA acceptance on only when the operator has set
 * SANGAM_ACCEPT_MSSQL_EULA=Y; otherwise the lane refuses with a NOT-RUN reason before any container exists. No container
 * is started here.
 */
class AcceptanceDatabaseVendorTermsTest {

    private static final DockerImageName IMAGE = DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest");

    private static UnaryOperator<String> env(Map<String, String> values) {
        return values::get;
    }

    @Test
    void withoutTheVariableTheLaneRefusesAsNotRun() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AcceptanceDatabase.sqlServer(IMAGE, env(Map.of())));
        assertTrue(e.getMessage().startsWith("NOT-RUN:"), e.getMessage());
        assertTrue(e.getMessage().contains("SANGAM_ACCEPT_MSSQL_EULA=Y") && e.getMessage().contains("not set")
                && e.getMessage().contains("decision 122"), e.getMessage());
    }

    @Test
    void onlyExactlyYAccepts() {
        for (String other : new String[] {"N", "", " Y", "y", "yes", "true", "TRUE", "1"}) {
            assertFalse(AcceptanceDatabase.mssqlEulaAccepted(env(Map.of("SANGAM_ACCEPT_MSSQL_EULA", other))), other);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> AcceptanceDatabase.sqlServer(IMAGE, env(Map.of("SANGAM_ACCEPT_MSSQL_EULA", other))), other);
            assertTrue(e.getMessage().startsWith("NOT-RUN:") && e.getMessage().contains("'" + other + "'"),
                    e.getMessage());
        }
        assertTrue(AcceptanceDatabase.mssqlEulaAccepted(env(Map.of("SANGAM_ACCEPT_MSSQL_EULA", "Y"))));
    }

    @Test
    void theOperatorsAcceptanceIsPassedToTheContainer() {
        MSSQLServerContainer c = AcceptanceDatabase.sqlServer(IMAGE, env(Map.of("SANGAM_ACCEPT_MSSQL_EULA", "Y")));
        assertEquals("Y", c.getEnvMap().get("ACCEPT_EULA"), "acceptLicense() only after the operator's Y");
    }

    @Test
    void theVariableNameAndValueMatchTheComposeFile() {
        assertEquals("SANGAM_ACCEPT_MSSQL_EULA", AcceptanceDatabase.MSSQL_EULA_VARIABLE);
        assertEquals("Y", AcceptanceDatabase.MSSQL_EULA_ACCEPTED);
    }
}
