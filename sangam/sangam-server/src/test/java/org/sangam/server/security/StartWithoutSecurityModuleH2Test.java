// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import org.junit.jupiter.api.Test;
import org.sangam.ops.secrets.SecretAdmin;
import org.sangam.server.SangamServerApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Register E-31(d), joint decision EV-M-0962 (Q-E): the development start without the security module
 * ({@code sangam.modules.security.enabled=false}, profile h2, owner decision 75) with every other module at its default,
 * the ops module included. {@code OpsWiring.sangamSecretAdmin} asked for the field encryptor that only the security
 * module's key wiring provides, so this start failed unless the ops module was switched off as well
 * ({@link SeatHeaderWithoutSecurityModuleH2Test} had to do that).
 * <p>
 * Required: the server starts; the secret store fails closed (a secret is neither stored in plain text nor read
 * back), so a write is refused and the refusal names the missing security module.
 */
class StartWithoutSecurityModuleH2Test {

    @Test
    void theServerStartsWithoutTheSecurityModuleAndTheSecretStoreFailsClosed() {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(SangamServerApplication.class).profiles("h2").run(
                "--server.port=0", "--management.server.port=0",
                "--spring.datasource.url=jdbc:h2:mem:seat-left-off-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;MODE=PostgreSQL",
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.h2.Driver",
                "--operaton.bpm.database.schema-update=true", "--sangam.db.require-rcsi=false", "--sangam.persistence.dev-schema=false",
                "--sangam.engine.allow-multiple-engines-per-jvm=true", "--sangam.modules.security.enabled=false")) {
            assertTrue(ctx.getEnvironment().getProperty("local.server.port", Integer.class, 0) > 0, "the server listens");
            SecretAdmin secrets = ctx.getBean(SecretAdmin.class);
            RuntimeException refused = assertThrows(RuntimeException.class,
                    () -> secrets.put("dev", "smtp-password", "not-stored", "seat-left"), "a secret write without a field encryptor");
            StringBuilder chain = new StringBuilder();
            for (Throwable t = refused; t != null; t = t.getCause()) {
                chain.append(t.getMessage()).append(" <- ");
            }
            assertTrue(chain.toString().contains("security module"), "the refusal names the missing security module: " + chain);
        }
    }
}
