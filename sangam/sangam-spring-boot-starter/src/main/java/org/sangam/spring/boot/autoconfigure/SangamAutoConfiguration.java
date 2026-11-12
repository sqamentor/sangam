// SPDX-License-Identifier: Apache-2.0
package org.sangam.spring.boot.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

/**
 * M-014: Sangam embedded in a Spring Boot application. It brings the standalone server's product wiring (the
 * {@code org.sangam.server.config} {@code *Wiring} classes, the bridges, the server controllers and filters, and the
 * {@code org.sangam.rest.web} controllers) into the host through {@link SangamProductImportSelector}: the same classes,
 * not a copy, so the host and the server run one wiring.
 *
 * <ul>
 *   <li><b>Order.</b> First among the auto-configurations ({@link Ordered#HIGHEST_PRECEDENCE}), so the product beans
 *       are registered before every other auto-configuration checks its {@code @ConditionalOnMissingBean} (sangam-rest's
 *       defaults, the Operaton starter's job executor, Spring Boot's own), exactly as in the server, whose scan
 *       registers them as user configuration ahead of every auto-configuration.</li>
 *   <li><b>Server.</b> The standalone server scans these packages itself; its {@code ModuleWiring} bean is then present
 *       and this configuration backs off, so the server's bean set does not change with the starter on its class path
 *       ({@code ServerBeanSetEqualityTest}).</li>
 *   <li><b>Modules.</b> Which modules are on is the {@code sangam.modules.<module>.enabled} flags, as in the server.
 *       A host gets the core on and the packs and domain modules off by default (sangam-defaults.yaml, loaded by
 *       {@link SangamDefaultsEnvironmentPostProcessor}).</li>
 *   <li><b>Security.</b> The product's {@code SecurityFilterChain} has no request matcher and ends with
 *       {@code denyAll()}: a host serving its own routes declares its own chain with a {@code securityMatcher} for them
 *       and a higher precedence ({@code @Order} below {@code Ordered.LOWEST_PRECEDENCE}); see the module README.</li>
 * </ul>
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnMissingBean(type = SangamAutoConfiguration.SERVER_WIRING_MARKER)
@Import(SangamProductImportSelector.class)
public class SangamAutoConfiguration {

    /** A class of the product wiring that is always a bean once the wiring is loaded (unconditional, no beans). */
    public static final String SERVER_WIRING_MARKER = "org.sangam.server.config.ModuleWiring";
}
