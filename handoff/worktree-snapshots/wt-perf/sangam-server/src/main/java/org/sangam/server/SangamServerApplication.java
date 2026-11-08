// SPDX-License-Identifier: Apache-2.0
package org.sangam.server;

import org.sangam.server.config.LiquibaseMode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sangam server: the Sangam API ({@code /api/v1/**}, sangam-rest), the Operaton REST API ({@code /engine-rest/**},
 * unchanged, behind Sangam authentication) and the engine with Sangam plugins (ARCHITECTURE.md section 2).
 * Default adapters come from {@code org.sangam.rest.config.SangamRestAutoConfiguration}; module beans declared in
 * {@link org.sangam.server.config.ModuleWiring} replace them. {@code SANGAM_LIQUIBASE_MODE} one-shot modes
 * ({@code migrate-only}, {@code update-sql}, {@code release-locks}) exit 0 after their schema task.
 */
@SpringBootApplication(scanBasePackages = {"org.sangam.server", "org.sangam.rest.web"})
public class SangamServerApplication {

    public static void main(String[] args) {
        LiquibaseMode.resolve(args).applySystemProperties();
        SpringApplication.run(SangamServerApplication.class, args);
    }
}
