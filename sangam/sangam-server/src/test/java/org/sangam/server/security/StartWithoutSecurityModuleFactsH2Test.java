// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import org.junit.jupiter.api.Test;
import org.sangam.server.SangamServerApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Register E-31(d): the development start without the security module ({@code sangam.modules.security.enabled=false},
 * profile h2, owner decision 75) with every other module at its default. {@link SeatHeaderWithoutSecurityModuleH2Test}
 * starts the same way with {@code sangam.modules.ops.enabled=false}, because the ops module's secret administration
 * ({@code OpsWiring.sangamSecretAdmin}) asks for the field encryptor that only the security module's key wiring
 * provides.
 * <p>
 * This test records the fact as a line {@code SEAT-LEFT | no security module | INFO | ...} and holds only this: the
 * start either succeeds, or it fails for that one known reason. Any other cause of failure is a new defect.
 */
class StartWithoutSecurityModuleFactsH2Test {

    @Test
    void theDevelopmentStartWithoutTheSecurityModuleAndTheOpsModuleOn() {
        String outcome;
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(SangamServerApplication.class).profiles("h2").run(
                "--server.port=0", "--management.server.port=0",
                "--spring.datasource.url=jdbc:h2:mem:seat-left-off-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;MODE=PostgreSQL",
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.h2.Driver",
                "--operaton.bpm.database.schema-update=true", "--sangam.db.require-rcsi=false", "--sangam.persistence.dev-schema=false",
                "--sangam.engine.allow-multiple-engines-per-jvm=true", "--sangam.modules.security.enabled=false")) {
            outcome = "the server starts (port " + ctx.getEnvironment().getProperty("local.server.port") + ")";
        } catch (RuntimeException e) {
            StringBuilder chain = new StringBuilder();
            for (Throwable t = e; t != null && chain.length() < 4000; t = t.getCause()) {
                chain.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" <- ");
            }
            outcome = "the start fails: " + chain;
            assertTrue(chain.toString().contains("sangamSecretAdmin") && chain.toString().contains("FieldEncryptor"),
                    "the only known cause is OpsWiring.sangamSecretAdmin without a FieldEncryptor: " + chain);
        }
        System.out.println("SEAT-LEFT | no security module | INFO | profile h2, sangam.modules.security.enabled=false,"
                + " sangam.modules.ops.enabled at its default: " + (outcome.length() > 900 ? outcome.substring(0, 900) : outcome));
    }
}
